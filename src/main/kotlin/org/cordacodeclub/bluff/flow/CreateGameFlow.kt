package org.cordacodeclub.bluff.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.requireThat
import net.corda.core.crypto.MerkleTree
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.internal.toMultiMap
import net.corda.core.node.StatesToRecord
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import org.cordacodeclub.bluff.contract.GameContract
import org.cordacodeclub.bluff.dealer.CardDeckDatabaseService
import org.cordacodeclub.bluff.dealer.containsAll
import org.cordacodeclub.bluff.player.ActionRequest
import org.cordacodeclub.bluff.round.CallOrRaiseRequest
import org.cordacodeclub.bluff.round.DealerRoundAccumulator
import org.cordacodeclub.bluff.round.addElementsOf
import org.cordacodeclub.bluff.state.BettingRound
import org.cordacodeclub.bluff.state.GameState
import org.cordacodeclub.bluff.state.TokenState

object CreateGameFlow {

    @InitiatingFlow
    @StartableByRPC
    /**
     * This flow is startable by the dealer party.
     * And it has to be signed by the players and dealer.
     * @param players list of parties joining the game
     * @param blindBetId the hash of the transaction that ran the blind bets
     * @param previousRoundId the hash of the transaction from the previous betting round
     */
    class GameCreator(private val previousRoundId: SecureHash) : FlowLogic<SignedTransaction?>() {

        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction.")
            object SAVING_OTHER_TRANSACTIONS : ProgressTracker.Step("Saving transactions from other players.")
            object VERIFYING_TRANSACTION : ProgressTracker.Step("Verifying contract constraints.")
            object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")
            object GATHERING_SIGS : ProgressTracker.Step("Gathering the counterparty's signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }

            object FINALISING_TRANSACTION :
                ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }
        }

        private fun tracker() = ProgressTracker(
            GENERATING_TRANSACTION,
            SAVING_OTHER_TRANSACTIONS,
            VERIFYING_TRANSACTION,
            SIGNING_TRANSACTION,
            GATHERING_SIGS,
            FINALISING_TRANSACTION
        )

        override val progressTracker = tracker()

        @Suspendable
        override fun call(): SignedTransaction? {

            //On initial call we use transaction from blindBet. Later rounds require previous transaction from this flow.
            val latestRoundTx = serviceHub.validatedTransactions.getTransaction(previousRoundId)!!
            val latestGameStateRef = latestRoundTx.tx.outRefsOfType<GameState>().single()
            val latestGameState = latestGameStateRef.state.data
            val players = latestGameState.players

            val potTokens = latestRoundTx.tx.outRefsOfType<TokenState>()
                .map { it.state.data.owner to it }
                .toMultiMap()
            val minter = potTokens.entries.flatMap { entry ->
                entry.value.map { it.state.data.minter }
            }.toSet().single()

            val cardService = serviceHub.cordaService(CardDeckDatabaseService::class.java)
            val deckInfo = cardService.getCardDeck(MerkleTree.getMerkleTree(latestGameState.hashedCards).hash)!!
            val communityCardsAmount = when (latestGameState.bettingRound) {
                BettingRound.FLOP -> 3
                BettingRound.TURN -> 4
                BettingRound.RIVER -> 5
                else -> 0
            }

            val dealer = serviceHub.myInfo.legalIdentities.first()

            requireThat {
                val blindBetPlayers = latestGameState.players.toSet()
                "There needs at least 2 players" using (players.size >= 2)
                "We should have the same players as in the previous bet" using (blindBetPlayers == players.toSet())
            }

            val playerFlows = players.map { it.party to (it.folded to initiateFlow(it.party)) }.toMap()

            // Player after smallBet and bigBet starts
            // Send only their cards to each player, ask for bets
            val accumulated = subFlow(
                DealerRoundAccumulatorFlow(
                    deckInfo = deckInfo,
                    playerFlows = playerFlows.values.filter { !it.first }.map { it.second },
                    accumulator = DealerRoundAccumulator(
                        minter = minter,
                        players = players,
                        currentPlayerIndex = latestGameState.lastBettor + 1,
                        committedPotSums = potTokens.mapValues { entry ->
                            entry.value.map { it.state.data.amount }.sum()
                        },
                        newBets = mapOf(),
                        newTransactions = setOf(),
                        lastRaiseIndex = 1,
                        playerCountSinceLastRaise = 0
                    )
                )
            )

            progressTracker.currentStep = SAVING_OTHER_TRANSACTIONS
            // TODO check more the transactions before saving them?
            serviceHub.recordTransactions(
                statesToRecord = StatesToRecord.ALL_VISIBLE, txs = accumulated.newTransactions
            )

            progressTracker.currentStep = GENERATING_TRANSACTION
            // TODO Our game theoretic risk is that a player that folded will not bother signing the tx

            val gameCommand = GameContract.Commands.CarryOn()
            val updatedGameState = latestGameState.copy(
                lastBettor = latestGameState.lastBettor + 1, // TODO fix that
                bettingRound = latestGameState.bettingRound.next()
            )

            val txBuilder = TransactionBuilder(notary = latestRoundTx.notary)
                .also {
                    it.addElementsOf(potTokens, accumulated)
                    it.addCommand(Command(gameCommand, listOf(dealer.owningKey)))
                    it.addInputState(latestGameStateRef)
                    it.addOutputState(updatedGameState, GameContract.ID)
                }

            progressTracker.currentStep = VERIFYING_TRANSACTION
            txBuilder.verify(serviceHub)

            progressTracker.currentStep = SIGNING_TRANSACTION
            val signedTx = serviceHub.signInitialTransaction(txBuilder)

            progressTracker.currentStep = GATHERING_SIGS
            val signers = accumulated.newBets.values.flatten().map { it.state.data.owner }.toSet()
            val fullySignedTx = subFlow(
                CollectSignaturesFlow(
                    signedTx,
                    signers.map { playerFlows[it]!!.second },
                    GATHERING_SIGS.childProgressTracker()
                )
            )
            // Inform the non-signing parties of the tx id
            val nonSigners = players.map { it.party }.minus(signers)
            nonSigners.forEach {
                playerFlows[it]!!.second.send(fullySignedTx.coreTransaction.id)
            }

            progressTracker.currentStep = FINALISING_TRANSACTION
            val tx = try {
                subFlow(
                    FinalityFlow(
                        fullySignedTx,
                        playerFlows.values.map { it.second },
                        FINALISING_TRANSACTION.childProgressTracker()
                    )
                )
            } catch (e: Throwable) {
                println(e)
                throw e
            }
            return tx
        }
    }

    class PlayerSideResponseAccumulatorFlowByPoller(otherPartySession: FlowSession) :
        PlayerSideResponseAccumulatorFlow(otherPartySession) {

        @Suspendable
        override fun getActionRequest(request: CallOrRaiseRequest): ActionRequest {
            return subFlow(PlayerResponseCollectingByPollerFlow(request, request.minter))
        }

        @Suspendable
        override fun createOwn(otherPartySession: FlowSession): PlayerSideResponseAccumulatorFlow {
            return PlayerSideResponseAccumulatorFlowByPoller(otherPartySession)
        }
    }

    @InitiatedBy(GameCreator::class)
    class GameResponder(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction?>() {

        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object RECEIVING_REQUESTS : ProgressTracker.Step("Receiving requests.")
            object RECEIVED_ROUND_DONE : ProgressTracker.Step("Received round done.")
            object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.") {
                override fun childProgressTracker(): ProgressTracker {
                    return SignTransactionFlow.tracker()
                }
            }

            object CHECKING_VALIDITY : ProgressTracker.Step("Checking transaction validity.")
            object RECEIVING_FINALISED_TRANSACTION : ProgressTracker.Step("Receiving finalised transaction.")

            fun tracker() = ProgressTracker(
                RECEIVING_REQUESTS,
                RECEIVED_ROUND_DONE,
                SIGNING_TRANSACTION,
                CHECKING_VALIDITY,
                RECEIVING_FINALISED_TRANSACTION
            )
        }

        override val progressTracker: ProgressTracker = tracker()

        @Suspendable
        override fun call(): SignedTransaction? {
            val me = serviceHub.myInfo.legalIdentities.first()

            progressTracker.currentStep = RECEIVING_REQUESTS
            val responseAccumulator = subFlow(
                PlayerSideResponseAccumulatorFlowByPoller(otherPartySession = otherPartySession)
            )

            progressTracker.currentStep = RECEIVED_ROUND_DONE

            val allPlayerStateRefs = responseAccumulator.allNewBets
                .also { allPlayerStates ->
                    allPlayerStates.filter {
                        it.state.data.owner == me
                    }.also { myNewBets ->
                        requireThat {
                            "Only my new bets are in the list" using (myNewBets.toSet() == responseAccumulator.myNewBets.toSet())
                        }
                    }
                }.map { it.ref }

            progressTracker.currentStep = SIGNING_TRANSACTION
            logger.info("signing transaction")
            val txId = if (responseAccumulator.myNewBets.isEmpty()) {
                // We are actually sent the transaction hash because we have nothing to sign
                progressTracker.currentStep = CHECKING_VALIDITY // We have to do it
                otherPartySession.receive<SecureHash>().unwrap { it }
            } else {
                val signTransactionFlow =
                    object : SignTransactionFlow(otherPartySession, SIGNING_TRANSACTION.childProgressTracker()) {

                        override fun checkTransaction(stx: SignedTransaction) = requireThat {
                            this@GameResponder.progressTracker.currentStep = CHECKING_VALIDITY
                            // Making sure we see our previously received states, which have been checked
                            // TODO add require back when we know how to make sure there are no more tokens than the ones
                            // We have been told there will be
//                            "We should have only known allNewBets" using
//                                    (stx.coreTransaction.inputs.toSet() == allPlayerStateRefs.toSet())
                            "We should have the same cards" using
                                    (MerkleTree.getMerkleTree(stx.tx.outRefsOfType<GameState>().single().state.data.hashedCards)
                                        .containsAll(responseAccumulator.myCards))
                            // TODO check that my cards are at my index?
                        }
                    }
                try {
                    subFlow(signTransactionFlow).id
                } catch (e: Exception) {
                    println(e)
                    throw e
                }
            }

            progressTracker.currentStep = RECEIVING_FINALISED_TRANSACTION
            val tx = try {
                subFlow(ReceiveFinalityFlow(otherPartySession, expectedTxId = txId))
            } catch (e: Throwable) {
                // TODO find why the finality flow throws with "Counter-flow errored"
                println(e)
                throw e
            }
            return tx
        }
    }
}

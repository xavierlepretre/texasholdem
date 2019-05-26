package org.cordacodeclub.bluff.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.requireThat
import net.corda.core.crypto.MerkleTree
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.internal.toMultiMap
import net.corda.core.node.StatesToRecord
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import org.cordacodeclub.bluff.contract.GameContract
import org.cordacodeclub.bluff.dealer.CardDeckDatabaseService
import org.cordacodeclub.bluff.dealer.CardDeckInfo.Companion.CARDS_PER_PLAYER
import org.cordacodeclub.bluff.dealer.containsAll
import org.cordacodeclub.bluff.state.*
import org.cordacodeclub.bluff.user.PlayerDatabaseService
import org.cordacodeclub.bluff.user.UserResponder
import org.cordacodeclub.bluff.contract.GameContract.Commands.*

//Initial flow
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
    class GameCreator(private val players: List<Party>, private val blindBetId: SecureHash, private val previousRoundId: SecureHash?) :
        FlowLogic<SignedTransaction>() {

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
        override fun call(): SignedTransaction {

            //On initial call we use transaction from blindBet. Later rounds require previous transaction from this flow.
            val latestRoundTx = if (previousRoundId != null) serviceHub.validatedTransactions.getTransaction(previousRoundId)!!
            else serviceHub.validatedTransactions.getTransaction(blindBetId)!!
            val latestGameState = latestRoundTx.tx.outRefsOfType<GameState>().single().state.data

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

            requireThat {
                val blindBetParticipants = latestGameState.participants.toSet()
                "There needs at least 2 players" using (players.size >= 2)
                "We should have the same players as in the previous bet" using (blindBetParticipants == players.toSet())
            }

            val dealer = serviceHub.myInfo.legalIdentities.first()
            val allFlows = players.map { initiateFlow(it) }

            // PLayer after smallBet and bigBet starts
            // Send only their cards to each player, ask for bets
            val accumulated = RoundTableAccumulator(
                minter = minter,
                players = players.map { ActivePlayer(it, false) },
                currentPlayerIndex = latestGameState.lastBettor + 1,
                committedPotSums = potTokens.mapValues { entry ->
                    entry.value.map { it.state.data.amount }.sum()
                },
                newBets = mapOf(),
                newTransactions = setOf(),
                lastRaiseIndex = 1,
                playerCountSinceLastRaise = 0
            ).doUntilIsRoundDone { accumulator ->
                CallOrRaiseRequest(
                    minter = minter,
                    lastRaise = accumulator.currentLevel,
                    yourWager = accumulator.currentPlayerSum,
                    yourCards = deckInfo.cards.drop(accumulator.currentPlayerIndex * CARDS_PER_PLAYER).take(
                            CARDS_PER_PLAYER
                    ),
                    communityCards = deckInfo.cards.drop(players.size * CARDS_PER_PLAYER).take(communityCardsAmount)
                ).let { request ->
                    allFlows[accumulator.currentPlayerIndex].sendAndReceive<CallOrRaiseResponse>(request).unwrap { it }
                }.let { response ->
                    accumulator.stepForwardWhenCurrentPlayerSent(response)
                }
            }

            // Notify all players that the round is done. We need to do this because the responder flow has to move on
            with(RoundTableDone(accumulated.newBets.flatMap { it.value })) {
                allFlows.forEach { it.send(this) }
            }

            progressTracker.currentStep = SAVING_OTHER_TRANSACTIONS
            // TODO check more the transactions before saving them?
            serviceHub.recordTransactions(
                statesToRecord = StatesToRecord.ALL_VISIBLE, txs = accumulated.newTransactions
            )

            progressTracker.currentStep = GENERATING_TRANSACTION
            // TODO Our game theoretic risk is that a player that folded will not bother signing the tx

            var command: GameContract.Commands
            var updatedGameState: GameState
            if (latestGameState.bettingRound == BettingRound.BLIND_BET) {
                command = Create()
                updatedGameState = latestGameState.copy(lastBettor = latestGameState.lastBettor + 1)
            } else {
                command = CarryOn()
                updatedGameState = latestGameState.copy(lastBettor = latestGameState.lastBettor + 1, bettingRound = latestGameState.bettingRound.next())
            }

            val txBuilder = TransactionBuilder(notary = latestRoundTx.notary)
                .also {
                    it.addElementsOf(potTokens, accumulated)
                    it.addCommand(Command(command, listOf(dealer.owningKey)))
                    it.addOutputState(updatedGameState, GameContract.ID)
                    Unit
                }

            progressTracker.currentStep = VERIFYING_TRANSACTION
            txBuilder.verify(serviceHub)

            progressTracker.currentStep = SIGNING_TRANSACTION
            val signedTx = serviceHub.signInitialTransaction(txBuilder)

            progressTracker.currentStep = GATHERING_SIGS
            // TODO Send a filtered transaction to each new bettor, ask for signature
            val fullySignedTx = subFlow(
                CollectSignaturesFlow(
                    signedTx,
                    accumulated.newBets.keys.let { newBettors ->
                        players.mapIndexedNotNull { index, party ->
                            if (newBettors.contains(party)) allFlows[index]
                            else null
                        }
                    },
                    GATHERING_SIGS.childProgressTracker()
                )
            )

            // Finalise transaction
            progressTracker.currentStep = FINALISING_TRANSACTION
            return subFlow(
                FinalityFlow(
                    fullySignedTx,
                    allFlows,
                    FINALISING_TRANSACTION.childProgressTracker()
                )
            )
        }
    }

    @InitiatedBy(GameCreator::class)
    class GameResponder(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {

        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object RECEIVING_REQUESTS : ProgressTracker.Step("Receiving requests.")
            object RECEIVED_ROUND_DONE : ProgressTracker.Step("Received round done.")
            object RECEIVING_ALL_TOKEN_STATES : ProgressTracker.Step("Receiving all players token moreBets.")
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
                RECEIVING_ALL_TOKEN_STATES,
                SIGNING_TRANSACTION,
                CHECKING_VALIDITY,
                RECEIVING_FINALISED_TRANSACTION
            )
        }

        override val progressTracker: ProgressTracker = tracker()

        @Suspendable
        override fun call(): SignedTransaction {
            val me = serviceHub.myInfo.legalIdentities.first()
            val playerDatabaseService = serviceHub.cordaService(PlayerDatabaseService::class.java)
            val userResponder = UserResponder(me, playerDatabaseService)
            logger.info("created userResponder")

            val responseBuilder: ResponseAccumulator.(CallOrRaiseRequest) -> CallOrRaiseResponse =
                { request ->
                    // The initiating flow expects a response
                    requireThat {
                        "We should be starting with no card or be sent the same cards again"
                            .using(myCards.isEmpty() || request.yourCards == myCards)
                        "Card should be assigned to me" using (request.yourCards.map { it.owner }.single() == me.name)
                        "My wager should match my new bets" using (myNewBets.map { it.state.data.amount }.sum() == request.yourWager)
                    }
                    val userResponse = userResponder.getAction(request)
                    when (userResponse.action!!) {
                        Action.Call -> CallOrRaiseResponse(
                            subFlow(
                                TokenStateCollectorFlow(
                                    TokenState(
                                        request.minter, me,
                                        request.lastRaise - request.yourWager,
                                        false
                                    )
                                )
                            ),
                            serviceHub
                        )
                        Action.Raise -> CallOrRaiseResponse(
                            subFlow(
                                TokenStateCollectorFlow(
                                    TokenState(
                                        request.minter, me,
                                        request.lastRaise - request.yourWager + userResponse.addAmount,
                                        false
                                    )
                                )
                            ),
                            serviceHub
                        )
                        Action.Fold -> CallOrRaiseResponse()
                    }
                }

            val looper: ResponseAccumulator.() -> ResponseAccumulator = {
                otherPartySession.receive<RoundTableRequest>().unwrap { it }
                    .let { request ->
                        // confirm we are not lied to with previously known cards
                        //save incomplete deck
                        when (request) {
                            is RoundTableDone -> this.stepForwardWhenIsDone(request = request)
                            is CallOrRaiseRequest -> responseBuilder(request)
                                .let { response ->
                                    this.stepForwardWhenSending(request, response)
                                        .also {
                                            otherPartySession.send(response)
                                        }
                                }
                            else -> throw IllegalArgumentException("Unknown type $request")
                        }
                    }
            }

            progressTracker.currentStep = RECEIVING_FINALISED_TRANSACTION
            val responseAccumulator = ResponseAccumulator(
                myCards = listOf(),
                myNewBets = listOf(),
                allNewBets = listOf(),
                isDone = false
            )
                .doUntilIsRoundDone(looper)
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

            // TODO Conditionally receive a filtered transaction to sign

            progressTracker.currentStep = SIGNING_TRANSACTION
            val txId = if (responseAccumulator.myNewBets.isEmpty()) {
                // We are actually sent the transaction hash because we have nothing to sign
                progressTracker.currentStep = CHECKING_VALIDITY // We have to do it
                otherPartySession.receive<SecureHash>().unwrap { it }
            } else {
                val signTransactionFlow =
                    object : SignTransactionFlow(otherPartySession, SIGNING_TRANSACTION.childProgressTracker()) {

                        override fun checkTransaction(stx: SignedTransaction) = requireThat {
                            progressTracker.currentStep = CHECKING_VALIDITY
                            // Making sure we see our previously received states, which have been checked
                            "We should have only known allNewBets" using
                                    (stx.coreTransaction.inputs.toSet() == allPlayerStateRefs.toSet())
                            "We should have the same cards" using
                                    (MerkleTree.getMerkleTree(stx.tx.outRefsOfType<GameState>().single().state.data.hashedCards)
                                        .containsAll(responseAccumulator.myCards))
                            // TODO check that my cards are at my index?
                        }
                    }
                subFlow(signTransactionFlow).id
            }

            progressTracker.currentStep = RECEIVING_FINALISED_TRANSACTION
            return subFlow(ReceiveFinalityFlow(otherPartySession, expectedTxId = txId))
        }
    }
}

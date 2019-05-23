package org.cordacodeclub.bluff.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.requireThat
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.internal.toMultiMap
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import org.cordacodeclub.bluff.contract.GameContract
import org.cordacodeclub.bluff.dealer.CardDeckDatabaseService
import org.cordacodeclub.bluff.dealer.CardDeckInfo
import org.cordacodeclub.bluff.dealer.CardDeckInfo.Companion.CARDS_PER_PLAYER
import org.cordacodeclub.bluff.dealer.containsAll
import org.cordacodeclub.bluff.state.ActivePlayer
import org.cordacodeclub.bluff.state.GameState
import org.cordacodeclub.bluff.state.TokenState
import org.cordacodeclub.bluff.user.PlayerDatabaseService
import org.cordacodeclub.bluff.user.UserResponder

//Initial flow
object CreateGameFlow {

    @InitiatingFlow
    @StartableByRPC
    /**
     * This flow is startable by the dealer party.
     * And it has to be signed by the players and dealer.
     * @param players list of parties joining the game
     * @param blindBetId the hash of the transaction that ran the blind bets
     */
    class GameCreator(private val players: List<Party>, private val blindBetId: SecureHash) :
        FlowLogic<SignedTransaction>() {

        private val blindBetTx = serviceHub.validatedTransactions.getTransaction(blindBetId)!!
        private val potTokens = blindBetTx.tx.outRefsOfType<TokenState>()
            .map { it.state.data.owner to it }
            .toMultiMap()
        private val minter = potTokens.entries.flatMap { entry ->
            entry.value.map { it.state.data.minter }
        }.toSet().single()

        init {
            requireThat {
                val blindBetParticipants = blindBetTx.inputs.flatMap {
                    serviceHub.toStateAndRef<TokenState>(it).state.data.participants
                }.toSet()
                "There needs at least 2 players" using (players.size >= 2)
                "We should have the same players as in blind bet" using (blindBetParticipants == players.toSet())
            }
        }

        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction.")
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
            VERIFYING_TRANSACTION,
            SIGNING_TRANSACTION,
            GATHERING_SIGS,
            FINALISING_TRANSACTION
        )

        override val progressTracker = tracker()

        @Suspendable
        override fun call(): SignedTransaction {

            val dealer = serviceHub.myInfo.legalIdentities.first()
            val allFlows = players.map { initiateFlow(it) }

            val deckInfo = CardDeckInfo.createShuffledWith(players.map { it.name }, dealer.name)
                .also {
                    // Save deck to database
                    serviceHub.cordaService(CardDeckDatabaseService::class.java).addDeck(it)
                }

            // 3rd player starts
            // Send only their cards to each player, ask for bets
            val accumulated = RoundTableAccumulator(
                minter = minter,
                players = players.map { ActivePlayer(it, false) },
                currentPlayerIndex = 2,
                committedPotSums = potTokens.mapValues { entry ->
                    entry.value.map { it.state.data.amount }.sum()
                },
                newBets = mapOf(),
                lastRaiseIndex = 1,
                playerCountSinceLastRaise = 0
            ).doUntilIsRoundDone { accumulator ->
                CallOrRaiseRequest(
                    minter = minter,
                    lastRaise = accumulator.currentLevel,
                    yourWager = accumulator.currentPlayerSum,
                    yourCards = deckInfo.cards.drop(accumulator.currentPlayerIndex * CARDS_PER_PLAYER).take(
                        CARDS_PER_PLAYER
                    )
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

            progressTracker.currentStep = GENERATING_TRANSACTION
            // TODO Our game theoretic risk is that a player that folded will not bother signing the tx
            val txBuilder = TransactionBuilder(notary = blindBetTx.notary)
                .also {
                    it.addElementsOf(potTokens, accumulated)
                    it.addCommand(
                        Command(
                            GameContract.Commands.Create(),
                            listOf(serviceHub.myInfo.legalIdentities.first().owningKey)
                        )
                    )
                    it.addOutputState(
                        GameState(
                            deckInfo.merkleTree,
                            // At this stage, we are hiding all cards
                            deckInfo.cards.map { null },
                            players.plus(dealer)
                        ),
                        GameContract.ID
                    )
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
                            serviceHub.vaultService.collectTokenStatesUntil(
                                minter = request.minter,
                                owner = me,
                                amount = request.lastRaise - request.yourWager
                            )
                        )
                        Action.Raise -> CallOrRaiseResponse(
                            serviceHub.vaultService.collectTokenStatesUntil(
                                minter = request.minter,
                                owner = me,
                                amount = request.lastRaise - request.yourWager + userResponse.addAmount
                            )
                        )
                        Action.Fold -> CallOrRaiseResponse()
                    }
                }

            val looper: ResponseAccumulator.() -> ResponseAccumulator = {
                otherPartySession.receive<RoundTableRequest>().unwrap { it }
                    .let { request ->
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
                                    (stx.tx.outRefsOfType<GameState>().single().state.data.tree
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

package org.cordacodeclub.bluff.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndRef
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
import org.cordacodeclub.bluff.state.*
import org.cordacodeclub.grom356.Card

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
    class GameCreator(val players: List<Party>, val blindBetId: SecureHash) : FlowLogic<SignedTransaction>() {

        val blindBetTx: SignedTransaction
        val potTokens: Map<Party, List<StateAndRef<TokenState>>>
        val minter: Party

        init {
            blindBetTx = serviceHub.validatedTransactions.getTransaction(blindBetId)!!
            potTokens = blindBetTx.tx.outRefsOfType<TokenState>()
                .map { it.state.data.owner to it }
                .toMultiMap()
            minter = potTokens.entries.flatMap { entry ->
                entry.value.map { it.state.data.minter }
            }.toSet().single()
            requireThat {
                val blindBetParticipants = blindBetTx.inputs.flatMap {
                    serviceHub.toStateAndRef<TokenState>(it).state.data.participants
                }.toSet()
                "There needs at least 2 players" using (players.size >= 2)
                "We should have the same players as in blind bet" using
                        (blindBetParticipants == players.toSet())
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

            const val PLAYER_CARD_COUNT = 2
            const val COMMUNITY_CARDS_COUNT = 3
        }

        fun tracker() = ProgressTracker(
            GENERATING_TRANSACTION,
            VERIFYING_TRANSACTION,
            SIGNING_TRANSACTION,
            GATHERING_SIGS,
            FINALISING_TRANSACTION
        )

        override val progressTracker = tracker()

        @Suspendable
        override fun call(): SignedTransaction {

            val allFlows = players.map { initiateFlow(it) }

            // TODO better shuffling algorithm?
            val shuffled = Card.newDeck().shuffled()

            // TODO Encrypt first cards with player's key and last cards with dealer's key

            // Assign cards
            val assignedCards: List<AssignedCard> = shuffled.mapIndexed { index, card ->
                val playerIndex: Int = index / 2
                if (playerIndex < players.size) ClearCard(card, players[playerIndex]) // TODO encrypt
                else if (playerIndex < players.size + 5) ClearCard(card, minter) // TODO encrypt Future river
                else ClearCard(card, minter) // TODO encrypt
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
                    yourCards = assignedCards.drop(accumulator.currentPlayerIndex * PLAYER_CARD_COUNT).take(
                        PLAYER_CARD_COUNT
                    )
                ).let { request ->
                    allFlows[accumulator.currentPlayerIndex].sendAndReceive<CallOrRaiseResponse>(request).unwrap { it }
                }.let { response ->
                    accumulator.stepForwardWhenCurrentPlayerSent(response)
                }
            }

            // Notify all players that the round is done. We need to do this because the responder flow has to move on
            allFlows.forEach { it.send(RoundTableDone(true)) }

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
                    it.addOutputState(GameState(assignedCards), GameContract.ID)
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
            object RECEIVING_FINALISED_TRANSACTION : ProgressTracker.Step("Receiving finalised transaction.")

            fun tracker() = ProgressTracker(
                RECEIVING_FINALISED_TRANSACTION
            )
        }

        override val progressTracker: ProgressTracker = tracker()

        @Suspendable
        override fun call(): SignedTransaction {
            // TODO do while receiving
            val request = otherPartySession.receive<RoundTableRequest>().unwrap { it }
            when (request) {
                is RoundTableDone -> null // TODO
                is CallOrRaiseRequest -> { // TODO
                }
                else -> throw IllegalArgumentException("Unknown request type $request")
            }

            // TODO Send states or call or pass?

            // TODO Conditionally receive a filtered transaction to sign

            progressTracker.currentStep = RECEIVING_FINALISED_TRANSACTION
            return subFlow(ReceiveFinalityFlow(otherPartySession))
        }
    }
}

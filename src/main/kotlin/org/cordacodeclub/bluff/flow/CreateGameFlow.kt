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
import org.cordacodeclub.bluff.state.ActivePlayer
import org.cordacodeclub.bluff.state.TokenState
import org.cordacodeclub.grom356.Card

//Initial flow
object CreateGameFlow {

    // To use in a .fold.
    // We send the request to the player, the player returns a list of StateAndRef.
    // This is the list of responses for players.
    class Accumulator(val request: CallOrRaiseRequest, val states: List<List<StateAndRef<TokenState>>>)


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
        val potTokens: Map<Party, List<TokenState>>
        val minter: Party

        init {
            blindBetTx = serviceHub.validatedTransactions.getTransaction(blindBetId)!!
            potTokens = blindBetTx.coreTransaction.outputs.map { it.data as TokenState }
                .map { it.owner to it }
                .toMultiMap()
            minter = potTokens.entries.flatMap { entry ->
                entry.value.map { it.minter }
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

            // 3rd player starts
            // Send only their cards to each player, ask for bets
            val accumulated = RoundTableAccumulator(
                minter = minter,
                players = players.map { ActivePlayer(it, false) },
                currentPlayerIndex = 2,
                committedPotSums = potTokens.mapValues { entry ->
                    entry.value.map { it.amount }.sum()
                },
                newBets = mapOf(),
                lastRaiseIndex = 1,
                playerCountSinceLastRaise = 0
            ).doUntilIsRoundDone { accumulator ->
                CallOrRaiseRequest(
                    minter = minter,
                    lastRaise = accumulator.currentLevel,
                    yourWager = accumulator.currentPlayerSum,
                    yourCards = shuffled.drop(accumulator.currentPlayerIndex * PLAYER_CARD_COUNT).take(PLAYER_CARD_COUNT)
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
            val newBettors = accumulated.newBets.keys
            val command = Command(
                GameContract.Commands.CarryOn(),
                newBettors.map { it.owningKey }
            )
            val txBuilder = TransactionBuilder(notary = blindBetTx.notary)
            txBuilder.addCommand(command)

            // Add existing pot tokens
            blindBetTx.tx.outRefsOfType<TokenState>().forEach {
                txBuilder.addInputState(it)
            }

            // Add new bet tokens as inputs
            accumulated.newBets.forEach { entry ->
                entry.value.forEach { state ->
                    txBuilder.addInputState(state)
                }
            }

            // Create and add new Pot token summaries in outputs
            accumulated.newBets
                .mapValues { entry ->
                    entry.value.map { it.state.data.amount }.sum()
                }
                .toList()
                .plus(potTokens
                    .mapValues { entry ->
                        entry.value.map { it.amount }.sum()
                    }
                    .toList())
                .toMultiMap()
                .forEach { entry ->
                    txBuilder.addOutputState(
                        TokenState(
                            minter = minter, owner = entry.key,
                            amount = entry.value.sum(), isPot = true
                        ), GameContract.ID
                    )
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
                    players.mapIndexed { index, party -> if (newBettors.contains(party)) index else -1 }
                        .filter { it >= 0 }
                        .map { allFlows[it] },
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

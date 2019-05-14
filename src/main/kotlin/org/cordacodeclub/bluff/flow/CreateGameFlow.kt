package org.cordacodeclub.bluff.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.requireThat
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.internal.toMultiMap
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import org.cordacodeclub.bluff.contract.GameContract
import org.cordacodeclub.bluff.state.TokenState
import org.cordacodeclub.grom356.Card

//Initial flow
object CreateGameFlow {

    @CordaSerializable
    data class RaiseRequest(val minter: Party, val lastRaise: Long, val yourWager: Long, val yourCards: List<Card>) {
        init {
            requireThat {
                "There can be only 2 cards" using (yourCards.size == GameCreator.PLAYER_CARD_COUNT)
                "Your wager cannot be higher than the last raise" using (yourWager <= lastRaise)
            }
        }
    }

    // To use in a .fold.
    // We send the request to the player, the player returns a list of StateAndRef.
    // This is the list of responses for players.
    class Accumulator(val request: RaiseRequest, val states: List<List<StateAndRef<TokenState>>>)

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
            FINALISING_TRANSACTION
        )

        override val progressTracker = tracker()

        /**
         * The flow logic is encapsulated within the call() method.
         */
        @Suspendable
        override fun call(): SignedTransaction {

            // Obtain a reference to the notary we want to use.
            val notary = serviceHub.networkMapCache.notaryIdentities.first()
            val allFlows = players.map { initiateFlow(it) }

            // Shuffle cards
            // TODO better shuffling algorithm?
            val shuffled = Card.newDeck().shuffled()

            // TODO Encrypt first cards with player's key and last cards with dealer's key

            // Send only their cards to each player, ask for bets
            val currentBets = potTokens.map { entry ->
                entry.key to entry.value.map { it.amount }.sum()
            }.toMap()

            val newBets = allFlows.foldIndexed(
                Accumulator(
                    RaiseRequest(
                        minter = minter,
                        lastRaise = currentBets.map { it.value }.max()!!,
                        yourWager = 0,
                        yourCards = listOf()
                    ),
                    listOf()
                )
            ) { index, acc, flow ->
                val previousWager = currentBets[players[index]] ?: 0
                val cards = shuffled.drop(index * PLAYER_CARD_COUNT).take(PLAYER_CARD_COUNT)
                val request = acc.request.copy(yourWager = previousWager, yourCards = cards)
                try {
                    val newStates = flow.sendAndReceive<List<StateAndRef<TokenState>>>(request).unwrap { it }
                    val raisedBy = newStates.map { it.state.data.amount }.sum()
                    requireThat {
                        "The minter should be in all new states" using
                                (newStates.map { it.state.data.minter }.toSet().single() == minter)
                        "The player should be the owner of all states" using
                                (newStates.map { it.state.data.owner }.toSet().single() == players[index])
                        "The amount should reach the last raise" using
                                (previousWager + raisedBy >= request.lastRaise)
                    }
                    Accumulator(
                        request.copy(lastRaise = previousWager + raisedBy),
                        acc.states.plusElement(newStates)
                    )
                } catch (e: FlowException) {
                    // TODO Confirm this is a fold
                    // TODO There is also call and pass?
                    Accumulator(
                        request,
                        acc.states.plusElement(listOf())
                    )
                }
            }.states

            val remainingPlayers = newBets.flatMap { list ->
                list.map { it.state.data.owner }
            }

            progressTracker.currentStep = GENERATING_TRANSACTION
            val minter = serviceHub.myInfo.legalIdentities.first()
            val command = Command(
                GameContract.Commands.Create(), remainingPlayers.map { it.owningKey }
            )

            val txBuilder = TransactionBuilder(notary = notary)

            txBuilder.addCommand(command)

            // TODO Create new Pot Tokens

            progressTracker.currentStep = VERIFYING_TRANSACTION
            txBuilder.verify(serviceHub)

            progressTracker.currentStep = SIGNING_TRANSACTION
            val signedTx = serviceHub.signInitialTransaction(txBuilder)

            // TODO Send a filtered transaction to each player, ask for signature

            // Finalise transaction

            progressTracker.currentStep = FINALISING_TRANSACTION
            return subFlow(
                FinalityFlow(
                    signedTx,
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
            // TODO Receive a request

            // TODO Send states or call or pass?

            // TODO Receive a filtered transaction to sign

            progressTracker.currentStep = RECEIVING_FINALISED_TRANSACTION
            return subFlow(ReceiveFinalityFlow(otherPartySession))
        }
    }
}

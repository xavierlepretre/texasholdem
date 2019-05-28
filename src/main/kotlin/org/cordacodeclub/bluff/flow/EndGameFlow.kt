package org.cordacodeclub.bluff.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.crypto.MerkleTree
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import org.cordacodeclub.bluff.contract.GameContract
import org.cordacodeclub.bluff.contract.WinningHandContract
import org.cordacodeclub.bluff.dealer.CardDeckDatabaseService
import org.cordacodeclub.bluff.dealer.CardDeckInfo
import org.cordacodeclub.bluff.dealer.getLeaves
import org.cordacodeclub.bluff.player.PlayerDatabaseService
import org.cordacodeclub.bluff.player.PlayerResponderPoller
import org.cordacodeclub.bluff.state.GameState
import org.cordacodeclub.bluff.state.PlayerHandState
import org.cordacodeclub.bluff.state.TokenState


object EndGameFlow {

    @CordaSerializable
    class HandRequest(val cardDeckInfo: CardDeckInfo)

    @CordaSerializable
    class HandResponse(
            val states: PlayerHandState
    )

    @CordaSerializable
    class HandAccumulator(
            val request: HandRequest,
            val states: List<PlayerHandState>
    )

    @InitiatingFlow
    @StartableByRPC
    /**
     * This flow is startable by the dealer party.
     * And it has to be signed by the players and dealer.
     * @param players list of parties joining the game
     * @param gameId the hash of the transaction with the GameState
     */

    class Initiator(
            private val players: List<Party>,
            private val gameId: SecureHash
    ) : FlowLogic<SignedTransaction>() {

        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object GET_STATES : ProgressTracker.Step("Retrieving relevant states.")
            object COLLECTING_HAND_STATES : ProgressTracker.Step("Get all hands from players.")
            object REVEAL_HANDS : ProgressTracker.Step("Show player hand cards.")
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
                GET_STATES,
                COLLECTING_HAND_STATES,
                REVEAL_HANDS,
                GENERATING_TRANSACTION,
                VERIFYING_TRANSACTION,
                SIGNING_TRANSACTION,
                GATHERING_SIGS,
                FINALISING_TRANSACTION
        )

        override val progressTracker = tracker()

        @Suspendable
        override fun call(): SignedTransaction {

            progressTracker.currentStep = GET_STATES
            val gameStateTx = serviceHub.validatedTransactions.getTransaction(gameId)!!
            val gameState = gameStateTx.tx.outRefsOfType<GameState>().single().state.data
            val potTokens = gameStateTx.tx.outRefsOfType<TokenState>().map { it.state.data }
            val minter = potTokens.map { it.minter }.toSet().single()

            val cardService = serviceHub.cordaService(CardDeckDatabaseService::class.java)
            val deckInfo = cardService.getCardDeck(MerkleTree.getMerkleTree(gameState.hashedCards).hash)!!
            val leaves = deckInfo.merkleTree.getLeaves()
            val dealer = serviceHub.myInfo.legalIdentities.first()
            val allFlows = players.map { initiateFlow(it) }

            progressTracker.currentStep = COLLECTING_HAND_STATES
            // TODO add some requirement checks
            val accumulator = (0 until players.size)
                    .fold(
                            HandAccumulator(
                                    request = HandRequest(cardDeckInfo = deckInfo),
                                    states = listOf())
                    ) { accumulator, playerIndex ->
                        val response = allFlows[playerIndex]
                                .sendAndReceive<HandResponse>(accumulator.request).unwrap { it }
                        val receivedStates = response.states
                        HandAccumulator(
                                request = HandRequest(cardDeckInfo = deckInfo),
                                states = accumulator.states.plusElement(receivedStates))
                    }

            progressTracker.currentStep = REVEAL_HANDS
            //Collect hands, assemble deck, compare hashes
            //Pass to contract for winner selection
            val playerHands = accumulator.states
            val handAssignedCards = playerHands.flatMap { it.cardIndexes }.map { deckInfo.cards[it] }


            // TODO Select the winner and create token state for the winner(update all other token states)
            val updatedGameState = gameState.copy(cards = handAssignedCards)

//            players.forEachIndexed { index, _ ->
//                allFlows[index]
//                        .sendAndReceive<HandResponse>(HandRequest(cardDeckInfo = deckInfo)).unwrap { it }
//                        .also { response ->
//                            requireThat {
//                                "Players should send their hand states" using (response.states.isNotEmpty())
//                            }
//                        }
//            }

            progressTracker.currentStep = GENERATING_TRANSACTION
            val notary = serviceHub.networkMapCache.notaryIdentities.first()
            val txBuilder = TransactionBuilder(notary = notary)

            txBuilder.addCommand(Command(
                    WinningHandContract.Commands.Compare(),
                    players.map { it.owningKey }
            ))

            // TODO needs to be stateAndRef
            //txBuilder.addInputState(gameState)
            playerHands.forEach { txBuilder.addOutputState(it, WinningHandContract.ID) }
            txBuilder.addOutputState(updatedGameState, GameContract.ID)

            progressTracker.currentStep = VERIFYING_TRANSACTION
            txBuilder.verify(serviceHub)

            progressTracker.currentStep = SIGNING_TRANSACTION
            val signedTx = serviceHub.signInitialTransaction(txBuilder)

            progressTracker.currentStep = FINALISING_TRANSACTION
            return subFlow(
                    FinalityFlow(
                            signedTx,
                            players.map { initiateFlow(it) },
                            FINALISING_TRANSACTION.childProgressTracker()
                    )
            )
        }
    }

    @InitiatedBy(Initiator::class)
    class Responder(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {

        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object RECEIVING_REQUEST_FOR_STATE : ProgressTracker.Step("Receiving request for best player hand.")
            object SENDING_HAND_STATE : ProgressTracker.Step("Sending back hand state.")
            object RECEIVING_FINALISED_TRANSACTION : ProgressTracker.Step("Receiving finalised transaction.")

            fun tracker() = ProgressTracker(
                    RECEIVING_REQUEST_FOR_STATE,
                    SENDING_HAND_STATE,
                    RECEIVING_FINALISED_TRANSACTION
            )
        }

        override val progressTracker: ProgressTracker = tracker()

        @Suspendable
        override fun call(): SignedTransaction {

            val me = serviceHub.myInfo.legalIdentities.first()
            progressTracker.currentStep = RECEIVING_REQUEST_FOR_STATE
            val request = otherPartySession.receive<HandRequest>().unwrap { it }

            // TODO incorporate merkle tree hasshes for correct card verification
            val playerCardService = serviceHub.cordaService(PlayerDatabaseService::class.java)
            val userResponder = PlayerResponderPoller(me, playerCardService)
            val latestPlayerAction = playerCardService.getPlayerAction(me.name.toString())

            val gameCards = request.cardDeckInfo.cards
            val myCards = playerCardService.getPlayerCards(me.name.toString())
            val cardIndexes = myCards.map { card -> gameCards.map { it.card }.indexOf(card) }

            // TODO proper request for hand
            // TODO shuffle community cards -> convert into interface
            val playerHandState = PlayerHandState(cardIndexes.shuffled().take(5), me)

            progressTracker.currentStep = SENDING_HAND_STATE
            otherPartySession.send(HandResponse(states = playerHandState))

            progressTracker.currentStep = RECEIVING_FINALISED_TRANSACTION
            return subFlow(ReceiveFinalityFlow(otherPartySession))
        }
    }
}
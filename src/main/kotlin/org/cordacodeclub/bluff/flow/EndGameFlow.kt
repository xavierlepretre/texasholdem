package org.cordacodeclub.bluff.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.*
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
import org.cordacodeclub.bluff.contract.TokenContract
import org.cordacodeclub.bluff.contract.WinningHandContract
import org.cordacodeclub.bluff.dealer.*
import org.cordacodeclub.bluff.state.*
import org.cordacodeclub.grom356.Hand


object EndGameFlow {

    @CordaSerializable
    class HandRequest(
            val cardDeckInfo: CardDeckInfo,
            val players: List<ActivePlayer>
    )

    @CordaSerializable
    class HandResponse(
            val states: PlayerHandState
    )

    @CordaSerializable
    class HandAccumulator(
            val request: HandRequest,
            val states: List<PlayerHandState>
    )


    /**
     * This flow is startable by the dealer party.
     * And it has to be signed by the players and dealer.
     * @param players list of parties joining the game
     * @param gameId the hash of the transaction with the GameState
     */

    @InitiatingFlow
    @StartableByRPC
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
            object REVEAL_HANDS : ProgressTracker.Step("Reveal the cards of players that showed their hand.")
            object WINNER_SELECTION : ProgressTracker.Step("Select the winner and prepare the pot prize.")
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
                WINNER_SELECTION,
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
            val gameStateAndRef = gameStateTx.tx.outRefsOfType<GameState>().single()
            val gameState = gameStateAndRef.state.data
            val potTokens = gameStateTx.tx.outRefsOfType<TokenState>().map { it.state.data }
            val minter = potTokens.map { it.minter }.toSet().single()
            val dealer = serviceHub.myInfo.legalIdentities.first()

            val cardService = serviceHub.cordaService(CardDeckDatabaseService::class.java)
            val deckInfo = cardService.getCardDeck(MerkleTree.getMerkleTree(gameState.hashedCards).hash)!!
            val merkleTree = deckInfo.merkleTree
            val allFlows = players.map { initiateFlow(it) }
            val notary = serviceHub.networkMapCache.notaryIdentities.first()

            progressTracker.currentStep = COLLECTING_HAND_STATES
            // TODO add some requirement checks
            val accumulator = (0 until players.size)
                    .fold(
                            HandAccumulator(
                                    request = HandRequest(cardDeckInfo = deckInfo, players = gameState.players),
                                    states = listOf()
                            )
                    ) { accumulator, playerIndex ->
                        println("Sending HandReqest to ${playerIndex}")
                        val response = allFlows[playerIndex]
                                .sendAndReceive<HandResponse>(accumulator.request).unwrap { it }
                        val receivedStates = response.states
                        HandAccumulator(
                                request = HandRequest(cardDeckInfo = deckInfo, players = gameState.players),
                                states = accumulator.states.plusElement(receivedStates)
                        )
                    }

            progressTracker.currentStep = REVEAL_HANDS
            //Collect hands, assemble deck, compare hashes
            //Pass to contract for winner selection
            val playerHandStates = accumulator.states
            val playerCards = playerHandStates.map { players.indexOf(it.owner) }
                    .flatMap { deckInfo.getPlayerCards(it) }
            val communityCards = deckInfo.getCommunityCards(players.size)

            //use card service to compare the decks
            val incompleteDeck = playerCards + communityCards

            requireThat {
                // "All bettors must be part of the current game" using (players.map { it.name }.containsAll(incompleteDeck.map { it.owner }))
                "All the hand cards must be part of the current game" using (deckInfo.cards.containsAll(incompleteDeck))
                "The cards must be part of the existing game deck" using (merkleTree.containsAll(incompleteDeck))
            }
            val updatedGameState = gameState.copy(cards = incompleteDeck, bettingRound = BettingRound.END)

            progressTracker.currentStep = WINNER_SELECTION
            //Assemble hands to determine the winning hand
            val playerHands = playerHandStates.map {
                it.owner to Hand.eval(it.cardIndexes.map { deckInfo.cards[it].card })
            }

            val sortedPlayerHands = playerHands.map { it.first to it.second }.sortedBy { it.second }
            val updatedPlayerHandStates = playerHandStates.map {
                val place = sortedPlayerHands.map { it.first }.indexOf(it.owner) + 1
                it.copy(place = place)
            }
            val winningHand = sortedPlayerHands.map { it.first to it.second }.sortedBy { it.second }.first()

            //Create a winner token state
            //TODO proper transfer of token ownership (update all other token states)
            val prizeTokens = potTokens.map { it.amount }.sum()
            val winningTokenState = TokenState(minter, winningHand.first, prizeTokens, false)

            progressTracker.currentStep = GENERATING_TRANSACTION
            val txBuilder = TransactionBuilder(notary = notary)

            txBuilder.addCommand(Command(
                    WinningHandContract.Commands.Compare(),
                    (players).map { it.owningKey }
            ))

            txBuilder.addCommand(Command(
                    GameContract.Commands.Close(),
                    listOf(dealer).map { it.owningKey }
            ))

            txBuilder.addCommand(Command(
                    TokenContract.Commands.Reward(),
                    listOf(dealer).map { it.owningKey }
            ))

            //Inputs to consume
            txBuilder.addInputState(gameStateAndRef)

            //New outputs
            updatedPlayerHandStates.forEach { txBuilder.addOutputState(it, WinningHandContract.ID) }
            txBuilder.addOutputState(updatedGameState, GameContract.ID)
            txBuilder.addOutputState(winningTokenState, TokenContract.ID)

            progressTracker.currentStep = VERIFYING_TRANSACTION
            txBuilder.verify(serviceHub)

            progressTracker.currentStep = SIGNING_TRANSACTION
            val signedTx = serviceHub.signInitialTransaction(txBuilder)

            progressTracker.currentStep = GATHERING_SIGS
            val fullySignedTx = subFlow(
                    CollectSignaturesFlow(
                            signedTx,
                            allFlows,
                            GATHERING_SIGS.childProgressTracker()
                    )
            )

            //allFlows.map { it.send(fullySignedTx.id) }

            val result = (players + dealer + minter).map { it.name to it.owningKey }
            result.map { println(it) }

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

    @InitiatedBy(Initiator::class)
    open class Responder(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {

        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object RECEIVING_REQUEST_FOR_STATE : ProgressTracker.Step("Receiving request for best player hand.")
            object SENDING_HAND_STATE : ProgressTracker.Step("Sending back hand state.")
            object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.") {
                override fun childProgressTracker() = SignTransactionFlow.tracker()
            }

            object RECEIVING_FINALISED_TRANSACTION : ProgressTracker.Step("Receiving finalised transaction.")


            fun tracker() = ProgressTracker(
                    RECEIVING_REQUEST_FOR_STATE,
                    SENDING_HAND_STATE,
                    SIGNING_TRANSACTION,
                    RECEIVING_FINALISED_TRANSACTION
            )
        }

        override val progressTracker: ProgressTracker = tracker()
        @Suspendable
        override fun call(): SignedTransaction {

            val me = serviceHub.myInfo.legalIdentities.first()
            progressTracker.currentStep = RECEIVING_REQUEST_FOR_STATE
            println("Receiving HandResponse from $me ${otherPartySession}")
            val request = otherPartySession.receive<HandRequest>().unwrap { it }

            // TODO incorporate merkle tree hashes for correct card verification
            //Game cards
            val deck = request.cardDeckInfo
            val gameCards = deck.cards.map { it.card }
            val communityCards = deck.getCommunityCards(request.players.size)

            //Player cards
            val myPosition = request.players.indexOf(request.players.find { it.party == me })
            val myCards = deck.getPlayerCards(myPosition)
            val myGameCards = myCards + communityCards

            //We can assume the player chooses the best hand possible
            val myHandCards = Hand.eval(myGameCards.map { it.card }).cards
            val myHandCardIndexes = myHandCards.map { gameCards.map { it.toString() }.indexOf(it.toString()) }
            val playerHandState = PlayerHandState(myHandCardIndexes, me)

            requireThat {
                "Cards should be part of the current game deck" using (gameCards.containsAll(myCards.map { it.card }))
                "Card hashes must be in the Merkle root" using (deck.hashedCards.containsAll(deck.cards.map { it.hash }))
            }

            progressTracker.currentStep = SENDING_HAND_STATE
            otherPartySession.send(HandResponse(states = playerHandState))

            val signTransactionFlow =
                    object : SignTransactionFlow(otherPartySession, SIGNING_TRANSACTION.childProgressTracker()) {
                        override fun checkTransaction(stx: SignedTransaction) = requireThat {
                            println("Hello 1")
                        }
                    }
            val txId = subFlow(signTransactionFlow).id

            progressTracker.currentStep = RECEIVING_FINALISED_TRANSACTION

            return subFlow(ReceiveFinalityFlow(otherPartySession, expectedTxId = txId))
        }
    }
}
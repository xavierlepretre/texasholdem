package org.cordacodeclub.bluff.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.requireThat
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
import org.cordacodeclub.bluff.player.PlayerDatabaseService
import org.cordacodeclub.bluff.state.ActivePlayer
import org.cordacodeclub.bluff.state.GameState
import org.cordacodeclub.bluff.state.PlayerHandState
import org.cordacodeclub.bluff.state.TokenState
import org.cordacodeclub.grom356.Hand
import kotlin.collections.containsAll


object EndGameFlow {

    @CordaSerializable
    class HandRequest(
            val cardDeckInfo: CardDeckInfo,
            val players: List<ActivePlayer>)

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

            val cardService = serviceHub.cordaService(CardDeckDatabaseService::class.java)
            val deckInfo = cardService.getCardDeck(MerkleTree.getMerkleTree(gameState.hashedCards).hash)!!
            val merkleTree = deckInfo.merkleTree
            val allFlows = players.map { initiateFlow(it) }

            progressTracker.currentStep = COLLECTING_HAND_STATES
            // TODO add some requirement checks
            val accumulator = (0 until players.size)
                    .fold(
                            HandAccumulator(
                                    request = HandRequest(cardDeckInfo = deckInfo, players = gameState.players),
                                    states = listOf()
                            )
                    ) { accumulator, playerIndex ->
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
                "All bettors must be part of the current game" using (players.map { it.name }.containsAll(incompleteDeck.map { it.owner }))
                "All the hand cards must be part of the current game" using (deckInfo.cards.containsAll(incompleteDeck))
                "The cards must be part of the existing game deck" using (merkleTree.containsAll(incompleteDeck))
            }
            val updatedGameState = gameState.copy(cards = incompleteDeck)


            progressTracker.currentStep = WINNER_SELECTION
            //Assemble hands to determine the winning hand
            val playerHands = playerHandStates.map {
                it.owner to Hand.eval(it.cardIndexes.map { deckInfo.cards[it].card })
            }
            val winningHand = playerHands.map { it.first to it.second }.sortedBy { it.second }.first()

            //Create a winner token state
            //TODO proper transfer of token ownership (update all other token states)
            val prizeTokens = potTokens.map { it.amount }.sum()
            //val updatedTokenState =
            val winningTokenState = TokenState(minter, winningHand.first, prizeTokens, false)

            progressTracker.currentStep = GENERATING_TRANSACTION
            val notary = serviceHub.networkMapCache.notaryIdentities.first()
            val txBuilder = TransactionBuilder(notary = notary)

            txBuilder.addCommand(Command(
                    WinningHandContract.Commands.Compare(),
                    players.map { it.owningKey }
            ))

            //Inputs to consume
            txBuilder.addInputState(gameStateAndRef)
            playerHandStates.forEach { txBuilder.addOutputState(it, WinningHandContract.ID) }

            //New outputs
            txBuilder.addOutputState(updatedGameState, GameContract.ID)
            txBuilder.addOutputState(winningTokenState, TokenContract.ID)

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
            val playerCardService = serviceHub.cordaService(PlayerDatabaseService::class.java)

            // TODO incorporate merkle tree hashes for correct card verification
            //Game cards
            val deck = request.cardDeckInfo
            val gameCards = deck.cards.map { it.card }
            val communityCards = deck.getCommunityCards(request.players.size)

            //Player cards
            val myPosition = request.players.indexOf(request.players.find { it.party == me })
            val myCards = deck.getPlayerCards(myPosition)
            val myGameCards = myCards + communityCards
            val mySavedCards = playerCardService.getPlayerCards(me.name.toString())

            //We can assume the player chooses the best hand possible
            val myHandCards = Hand.eval(myGameCards.map { it.card }).cards.map { gameCards.indexOf(it) }
            val playerHandState = PlayerHandState(myHandCards, me)

            //this can go in the contract eventually
            requireThat {
                "Cards should be part of the current game deck" using (gameCards.containsAll(mySavedCards))
                "Current cards at hand must be the same as before" using
                        ((myGameCards).sortedBy { it.card.rank } == mySavedCards.sortedBy { it!!.rank })
                "Card hashes must be in the Merkle root" using (deck.hashedCards.containsAll(deck.cards.map { it.hash }))
            }
            val latestPlayerAction = playerCardService.getPlayerAction(me.name.toString())

            progressTracker.currentStep = SENDING_HAND_STATE
            otherPartySession.send(HandResponse(states = playerHandState))

            progressTracker.currentStep = RECEIVING_FINALISED_TRANSACTION
            return subFlow(ReceiveFinalityFlow(otherPartySession))
        }
    }
}
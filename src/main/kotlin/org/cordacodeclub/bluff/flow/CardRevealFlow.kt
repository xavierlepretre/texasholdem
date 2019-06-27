package org.cordacodeclub.bluff.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import org.cordacodeclub.bluff.dealer.CardDeckDatabaseService
import org.cordacodeclub.bluff.dealer.IncompleteCardDeckInfo
import org.cordacodeclub.bluff.state.RoundState

object CardRevealFlow {

    @InitiatingFlow
    @StartableByRPC
    /**
     * The player launches this to get the cards in the GUI.
     */
    class Initiator(
        private val latestStepTxId: SecureHash,
        override val progressTracker: ProgressTracker = tracker()
    ) :
        FlowLogic<IncompleteCardDeckInfo>() {

        companion object {
            object GETTING_TX_INFO : ProgressTracker.Step("Getting the current transaction from the vault.")
            object SENDING_TX_ID : ProgressTracker.Step("Sending the current transaction id.")
            object RECEIVING_REVEALED_DECK : ProgressTracker.Step("Receiving incompletely revealed deck.")
            object RECEIVED_REVEALED_DECK : ProgressTracker.Step("Received incompletely revealed deck.")

            fun tracker() = ProgressTracker(
                GETTING_TX_INFO,
                SENDING_TX_ID,
                RECEIVING_REVEALED_DECK,
                RECEIVED_REVEALED_DECK
            )
        }

        @Suspendable
        override fun call(): IncompleteCardDeckInfo {
            progressTracker.currentStep = GETTING_TX_INFO
            val latestStepTx = serviceHub.validatedTransactions.getTransaction(latestStepTxId)
                ?: throw FlowException("Unknown transaction hash $latestStepTxId")
            val roundState = latestStepTx.coreTransaction.outputsOfType<RoundState>().singleOrNull()
                ?: throw FlowException("Transaction does not contain a single RoundState")

            progressTracker.currentStep = SENDING_TX_ID
            val dealerFlow = initiateFlow(roundState.dealer)
            dealerFlow.send(latestStepTxId)

            progressTracker.currentStep = RECEIVING_REVEALED_DECK
            val deck = dealerFlow.receive<IncompleteCardDeckInfo>().unwrap {
                if (it.rootHash != roundState.deckRootHash)
                    throw FlowException("The dealer returned cards for another deck")
                it
            }

            progressTracker.currentStep = RECEIVED_REVEALED_DECK
            return deck
        }
    }

    @InitiatedBy(Initiator::class)
    /**
     * Dealer side.
     */
    class Responder(private val otherPartySession: FlowSession) : FlowLogic<Unit>() {

        companion object {
            object RECEIVING_TX_ID : ProgressTracker.Step("Receiving the current transaction id.")
            object COLLECTING_DECK : ProgressTracker.Step("Collecting the current deck.")
            object REVEALING_CARDS : ProgressTracker.Step("Revealing cards.")
            object SENDING_REVEALED_DECK : ProgressTracker.Step("Sending incompletely revealed deck.")
            object SENT_REVEALED_DECK : ProgressTracker.Step("Incompletely revealed deck sent.")

            fun tracker() = ProgressTracker(
                RECEIVING_TX_ID,
                COLLECTING_DECK,
                REVEALING_CARDS,
                SENDING_REVEALED_DECK,
                SENT_REVEALED_DECK
            )
        }

        override val progressTracker: ProgressTracker = tracker()

        @Suspendable
        override fun call() {
            progressTracker.currentStep = RECEIVING_TX_ID
            val roundState = otherPartySession.receive<SecureHash>().unwrap {
                val latestStepTx = serviceHub.validatedTransactions.getTransaction(it)
                    ?: throw FlowException("Unknown transaction hash $it")
                latestStepTx.coreTransaction.outputsOfType<RoundState>().singleOrNull()
                    ?: throw FlowException("Transaction does not contain a single RoundState")
            }

            progressTracker.currentStep = COLLECTING_DECK
            val cardService = serviceHub.cordaService(CardDeckDatabaseService::class.java)
            val fullDeck = cardService.getCardDeck(roundState.deckRootHash)
                ?: throw FlowException("Unknown card deck root hash ${roundState.deckRootHash}")

            val playerAsking = otherPartySession.counterparty
            // TODO do we prevent folded players from asking?
            val me = serviceHub.myInfo.legalIdentities.first()

            progressTracker.currentStep = REVEALING_CARDS
            val nextRoundType = roundState.nextRoundTypeOrNull
                ?: throw FlowException("There is no card to reveal for the next step")
            val revealedDeck = try {
                IncompleteCardDeckInfo.withNullCards(fullDeck)
                    .revealOwnedCards(fullDeck, playerAsking.name, nextRoundType.playerCardsCount)
                    .revealOwnedCards(fullDeck, me.name, nextRoundType.communityCardsCount)
            } catch (e: IllegalArgumentException) {
                throw FlowException("Failed to reveal cards", e)
            }

            progressTracker.currentStep = SENDING_REVEALED_DECK
            otherPartySession.send(revealedDeck)

            progressTracker.currentStep = SENT_REVEALED_DECK
        }
    }
}
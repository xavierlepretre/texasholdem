package org.cordacodeclub.bluff.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.requireThat
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import org.cordacodeclub.bluff.contract.OneStepContract
import org.cordacodeclub.bluff.contract.TokenContract
import org.cordacodeclub.bluff.dealer.CardDeckDatabaseService
import org.cordacodeclub.bluff.dealer.CardDeckInfo
import org.cordacodeclub.bluff.dealer.IncompleteCardDeckInfo
import org.cordacodeclub.bluff.player.PlayerAction
import org.cordacodeclub.bluff.round.BettingRound
import org.cordacodeclub.bluff.state.PlayedAction
import org.cordacodeclub.bluff.state.RoundState
import org.cordacodeclub.bluff.state.TokenState

object BlindBet1OneStepFlow {

    @CordaSerializable
    data class CardDeckCreateRequest(val dealer: Party, val players: List<Party>)

    @InitiatingFlow
    @StartableByRPC
    /**
     * This flow is started by the player doing the first blind bet. Tx has to be signed by the dealer.
     * @param players list of parties starting the game
     * @param minter the desired minter of the tokens
     * @param dealer the desired dealer of the deck of cards
     * @param smallBet the starting small bet
     * @param progressTracker the overridden progress tracker
     */
    class Initiator(
        val players: List<Party>,
        val minter: Party,
        val dealer: Party,
        val smallBet: Long,
        override val progressTracker: ProgressTracker = tracker()
    ) :
        FlowLogic<SignedTransaction>() {

        init {
            require(smallBet > 0) { "SmallBet should not be 0" }
            require(players.size >= RoundState.MIN_PLAYER_COUNT) {
                "There should be at least ${RoundState.MIN_PLAYER_COUNT} players"
            }
            require(!players.contains(dealer)) { "The dealer cannot play" }
        }

        companion object {
            object CREATING_NEW_DECK : ProgressTracker.Step("Asking dealer to create a new deck.")
            object COLLECTING_TOKENS : ProgressTracker.Step("Collecting own tokens for blind bet.") {
                override fun childProgressTracker(): ProgressTracker {
                    return CollectOwnTokenStateFlow.tracker()
                }
            }

            object GENERATING_POT_STATES : ProgressTracker.Step("Generating betting pot.")
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

            fun tracker() = ProgressTracker(
                CREATING_NEW_DECK,
                COLLECTING_TOKENS,
                GENERATING_POT_STATES,
                GENERATING_TRANSACTION,
                VERIFYING_TRANSACTION,
                SIGNING_TRANSACTION,
                GATHERING_SIGS,
                FINALISING_TRANSACTION
            )
        }

        @Suspendable
        override fun call(): SignedTransaction {

            val me = serviceHub.myInfo.legalIdentities.first()

            progressTracker.currentStep = CREATING_NEW_DECK
            val createRequest = CardDeckCreateRequest(dealer, players)
            val playerFlows = players.minus(me).toSet().map { initiateFlow(it) }
            val dealerFlow = initiateFlow(dealer)
            // Priming the players too, even though only the dealer returns anything
            playerFlows.plus(dealerFlow).forEach { it.send(createRequest) }
            val deckRootHash = dealerFlow.receive<SecureHash>().unwrap { it }

            progressTracker.currentStep = COLLECTING_TOKENS
            val tokenStates = subFlow(
                CollectOwnTokenStateFlow(
                    TokenState(minter, me, smallBet, false),
                    COLLECTING_TOKENS.childProgressTracker()
                )
            )

            val notary = tokenStates.map { it.state.notary }.toSet().singleOrNull()
                ?: throw FlowException("Did not collect tokens from a single notary")

            progressTracker.currentStep = GENERATING_POT_STATES
            // We separate them to accommodate future owner tracking
            val potState = tokenStates.map { it.state.data.amount }.sum()
                .let { sum ->
                    tokenStates.first().state.data.copy(amount = sum, isPot = true)
                }

            progressTracker.currentStep = GENERATING_TRANSACTION
            val txBuilder = TransactionBuilder(notary = notary)

            txBuilder.addCommand(Command(TokenContract.Commands.BetToPot(), me.owningKey))
            tokenStates.forEach { txBuilder.addInputState(it) }
            txBuilder.addOutputState(potState, TokenContract.ID)

            txBuilder.addCommand(Command(OneStepContract.Commands.BetBlind1(), listOf(dealer.owningKey, me.owningKey)))
            txBuilder.addOutputState(
                RoundState(
                    minter = minter,
                    dealer = dealer,
                    deckRootHash = deckRootHash,
                    roundType = BettingRound.BLIND_BET_1,
                    currentPlayerIndex = players.indexOf(me),
                    players = players.map {
                        PlayedAction(it, if (it == me) PlayerAction.Raise else PlayerAction.Missing)
                    }
                ),
                OneStepContract.ID
            )

            progressTracker.currentStep = VERIFYING_TRANSACTION
            txBuilder.verify(serviceHub)

            progressTracker.currentStep = SIGNING_TRANSACTION
            val signedTx = serviceHub.signInitialTransaction(txBuilder)

            progressTracker.currentStep = GATHERING_SIGS
            val fullySignedTx = subFlow(
                CollectSignaturesFlow(signedTx, listOf(dealerFlow), GATHERING_SIGS.childProgressTracker())
            )

            progressTracker.currentStep = FINALISING_TRANSACTION
            return subFlow(
                FinalityFlow(
                    fullySignedTx,
                    playerFlows.plus(dealerFlow),
                    FINALISING_TRANSACTION.childProgressTracker()
                )
            )
        }
    }

    @InitiatedBy(Initiator::class)
    class Responder(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {

        companion object {
            object RECEIVING_REQUEST_FOR_DECK : ProgressTracker.Step("Receiving request for deck of cards.")
            object CREATING_CARD_DECK : ProgressTracker.Step("Creating deck of cards.")
            object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.") {
                override fun childProgressTracker(): ProgressTracker {
                    return SignTransactionFlow.tracker()
                }
            }

            object RECEIVING_FINALISED_TRANSACTION : ProgressTracker.Step("Receiving finalised transaction.")

            fun tracker() = ProgressTracker(
                RECEIVING_REQUEST_FOR_DECK,
                CREATING_CARD_DECK,
                SIGNING_TRANSACTION,
                RECEIVING_FINALISED_TRANSACTION
            )
        }

        override val progressTracker: ProgressTracker = tracker()

        @Suspendable
        override fun call(): SignedTransaction {

            val me = serviceHub.myInfo.legalIdentities.first()

            progressTracker.currentStep = RECEIVING_REQUEST_FOR_DECK

            val request = otherPartySession.receive<CardDeckCreateRequest>().unwrap { it }
            val txId =
                if (request.dealer == me) dealerResponse(request).id
                else playerResponse()

            progressTracker.currentStep = RECEIVING_FINALISED_TRANSACTION
            return subFlow(ReceiveFinalityFlow(otherPartySession, expectedTxId = txId))
        }

        @Suspendable
        fun dealerResponse(request: CardDeckCreateRequest): SignedTransaction {
            progressTracker.currentStep = CREATING_CARD_DECK
            val deck = CardDeckInfo.createShuffledWith(request.players.map { it.name }, request.dealer.name)
            serviceHub.cordaService(CardDeckDatabaseService::class.java).safeAddIncompleteDeck(
                IncompleteCardDeckInfo(deck)
            )
            otherPartySession.send(deck.rootHash)

            progressTracker.currentStep = SIGNING_TRANSACTION
            val signTransactionFlow =
                object : SignTransactionFlow(otherPartySession, SIGNING_TRANSACTION.childProgressTracker()) {

                    override fun checkTransaction(stx: SignedTransaction) = requireThat {
                        val state = stx.coreTransaction.outputsOfType<RoundState>().firstOrNull()
                            ?: throw FlowException("There is more than one RoundState")
                        "We should be the dealer" using (state.dealer == request.dealer)
                        "The players should be the same as in the request" using
                                (request.players.toSet() == state.players.map { it.player }.toSet())
                        "We should have our created deck" using (state.deckRootHash == deck.rootHash)
                    }
                }
            return subFlow(signTransactionFlow)
        }

        @Suspendable
        fun playerResponse(): SecureHash? {
            // Players do not respond
            progressTracker.currentStep = CREATING_CARD_DECK
            progressTracker.currentStep = SIGNING_TRANSACTION
            return null
        }
    }
}

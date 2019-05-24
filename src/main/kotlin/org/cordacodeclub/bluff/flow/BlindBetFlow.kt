package org.cordacodeclub.bluff.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.Requirements.using
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.requireThat
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.ServiceHub
import net.corda.core.node.StatesToRecord
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import org.cordacodeclub.bluff.contract.GameContract
import org.cordacodeclub.bluff.contract.TokenContract
import org.cordacodeclub.bluff.dealer.CardDeckDatabaseService
import org.cordacodeclub.bluff.dealer.CardDeckInfo
import org.cordacodeclub.bluff.state.GameState
import org.cordacodeclub.bluff.state.TokenState

object BlindBetFlow {

    @CordaSerializable
    class BlindBetRequest(val minter: Party, val amount: Long)

    @CordaSerializable
    class BlindBetResponse(
        val states: List<StateAndRef<TokenState>>,
        val transactions: Set<SignedTransaction>
    ) {
        init {
            "All transactions must be relevant to the states and vice versa" using
                    (states.map { it.ref.txhash }.toSet() == transactions.map { it.id }.toSet())
        }

        constructor(states: List<StateAndRef<TokenState>>, serviceHub: ServiceHub) :
                this(states, states.map { serviceHub.validatedTransactions.getTransaction(it.ref.txhash)!! }.toSet())
    }

    // To use in a .fold.
    // We send the request to the player, the player returns a list of StateAndRef.
    // This is the list of responses for important players.
    class Accumulator(
        val request: BlindBetRequest,
        val states: List<List<StateAndRef<TokenState>>>,
        val transactions: Set<SignedTransaction>
    )

    @InitiatingFlow
    @StartableByRPC
    /**
     * This flow is startable by the dealer party.
     * And it has to be signed by the players and dealer.
     * @param players list of parties starting the game
     * @param minter the desired minter of the tokens
     * @param smallBet the starting small bet
     */
    class Initiator(val players: List<Party>, val minter: Party, val smallBet: Long) : FlowLogic<SignedTransaction>() {

        init {
            requireThat {
                "Small Bet cannot be 0" using (smallBet > 0)
                "There needs at least $BLIND_PLAYER_COUNT players" using (players.size >= BLIND_PLAYER_COUNT)
            }
        }

        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object COLLECTING_BLINDBET_STATES : ProgressTracker.Step("Collecting all blind bets from first players.")
            object SAVING_OTHER_TRANSACTIONS : ProgressTracker.Step("Saving transactions from other players.")
            object PINGING_OTHER_PLAYERS : ProgressTracker.Step("Pinging other players.")
            object SENDING_TOKEN_STATES_TO_PLAYERS : ProgressTracker.Step("Sending token moreBets to other players.")
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

            const val BLIND_PLAYER_COUNT = 2
        }

        private fun tracker() = ProgressTracker(
            COLLECTING_BLINDBET_STATES,
            SAVING_OTHER_TRANSACTIONS,
            PINGING_OTHER_PLAYERS,
            SENDING_TOKEN_STATES_TO_PLAYERS,
            GENERATING_POT_STATES,
            GENERATING_TRANSACTION,
            VERIFYING_TRANSACTION,
            SIGNING_TRANSACTION,
            GATHERING_SIGS,
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
            val dealer = serviceHub.myInfo.legalIdentities.first()

            val deckInfo = CardDeckInfo.createShuffledWith(players.map { it.name }, dealer.name)
                .also {
                    // Save deck to database
                    serviceHub.cordaService(CardDeckDatabaseService::class.java).addDeck(it)
                }

            progressTracker.currentStep = COLLECTING_BLINDBET_STATES

            val requiredSigners = players.take(BLIND_PLAYER_COUNT)
            val allFlows = players.map { initiateFlow(it) }

            val accumulator = (0 until BLIND_PLAYER_COUNT)
                .fold(
                    Accumulator(
                        request = BlindBetRequest(minter = minter, amount = smallBet),
                        states = listOf(),
                        transactions = setOf()
                    )
                ) { accumulator, playerIndex ->
                    val response = allFlows[playerIndex]
                        .sendAndReceive<BlindBetResponse>(accumulator.request).unwrap { it }
                    val receivedStates = response.states
                        .filter { it.state.data.minter == minter }
                    val sum = receivedStates.map { it.state.data.amount }.sum()
                    requireThat {
                        "States should have the asked minter" using
                                (receivedStates.map { it.state.data.minter }.toSet().single() == minter)
                        "States should have the player as owner" using
                                (receivedStates.map { it.state.data.owner }.toSet().single() == players[playerIndex])
                        "States should all be non pot" using
                                (!receivedStates.map { it.state.data.isPot }.toSet().single())
                        "We have to receive at least ${accumulator.request.amount}, not $sum" using
                                (accumulator.request.amount <= sum)
                        "Transactions should all be there" using
                                (receivedStates.map { it.ref.txhash }.toSet() ==
                                        response.transactions.map { it.id }.toSet())
                    }
                    Accumulator(
                        request = BlindBetRequest(minter = minter, amount = 2 * sum),
                        states = accumulator.states.plusElement(receivedStates),
                        transactions = accumulator.transactions.plus(response.transactions)
                    )
                }

            progressTracker.currentStep = SAVING_OTHER_TRANSACTIONS
            val playerStates = accumulator.states
            // TODO check more the transactions before saving them?
            serviceHub.recordTransactions(
                statesToRecord = StatesToRecord.ALL_VISIBLE, txs = accumulator.transactions
            )

            progressTracker.currentStep = PINGING_OTHER_PLAYERS
            // We need to do it so that the responder flow is primed. We only care about finality with the other
            // players
            val requestOthers = BlindBetRequest(minter = minter, amount = 0L)
            players.drop(BLIND_PLAYER_COUNT).forEachIndexed { index, _ ->
                allFlows[index + BLIND_PLAYER_COUNT]
                    .sendAndReceive<BlindBetResponse>(requestOthers).unwrap { it }
                    .also { responseOthers ->
                        requireThat {
                            "Other players should not send any state" using (responseOthers.states.isEmpty())
                            "Other players should not send any tx" using (responseOthers.transactions.isEmpty())
                        }
                    }
            }

            progressTracker.currentStep = SENDING_TOKEN_STATES_TO_PLAYERS
            playerStates.flatMap { it }
                .also { flatStates ->
                    allFlows.forEach { flow ->
                        flow.send(flatStates)
                    }
                }

            progressTracker.currentStep = GENERATING_POT_STATES
            // We separate them to accommodate future owner tracking
            val potStates = playerStates.map { list ->
                list.map { it.state.data.amount }.sum()
                    .let { sum ->
                        list.first().state.data.copy(amount = sum, isPot = true)
                    }
            }

            progressTracker.currentStep = GENERATING_TRANSACTION
            val txBuilder = TransactionBuilder(notary = notary)

            txBuilder.addCommand(Command(
                TokenContract.Commands.BetToPot(),
                requiredSigners.map { it.owningKey }
            ))
            playerStates.forEach { list ->
                list.forEach {
                    txBuilder.addInputState(it)
                }
            }
            potStates.forEach {
                txBuilder.addOutputState(it, TokenContract.ID)
            }

            txBuilder.addCommand(
                Command(
                    GameContract.Commands.Create(),
                    listOf(dealer.owningKey)
                )
            )
            txBuilder.addOutputState(
                GameState(
                    // At this stage, we are hiding all cards
                    deckInfo.cards.map { it.hash },
                    deckInfo.cards.map { null },
                    players.plus(dealer)
                ),
                GameContract.ID
            )

            progressTracker.currentStep = VERIFYING_TRANSACTION
            txBuilder.verify(serviceHub)

            progressTracker.currentStep = SIGNING_TRANSACTION
            val signedTx = serviceHub.signInitialTransaction(txBuilder)

            progressTracker.currentStep = GATHERING_SIGS
            // Split flow between first 2 players
            val fullySignedTx = subFlow(
                CollectSignaturesFlow(
                    signedTx,
                    allFlows.take(BLIND_PLAYER_COUNT),
                    GATHERING_SIGS.childProgressTracker()
                )
            )
            // and the others
            allFlows.drop(BLIND_PLAYER_COUNT).forEach {
                it.send(fullySignedTx.id)
            }

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
    class CollectorAndSigner(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {

        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object RECEIVING_REQUEST_FOR_STATES : ProgressTracker.Step("Receiving request for token moreBets.")
            object SENDING_TOKEN_STATES : ProgressTracker.Step("Sending back token moreBets.")
            object RECEIVING_ALL_TOKEN_STATES : ProgressTracker.Step("Receiving all players token moreBets.")
            object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.") {
                override fun childProgressTracker(): ProgressTracker {
                    return SignTransactionFlow.tracker()
                }
            }

            object CHECKING_VALIDITY : ProgressTracker.Step("Checking transaction validity.")
            object RECEIVING_FINALISED_TRANSACTION : ProgressTracker.Step("Receiving finalised transaction.")

            fun tracker() = ProgressTracker(
                RECEIVING_REQUEST_FOR_STATES,
                SENDING_TOKEN_STATES,
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

            progressTracker.currentStep = RECEIVING_REQUEST_FOR_STATES
            // Receive an incomplete transaction and a minimum amount to bet
            val request = otherPartySession.receive<BlindBetRequest>().unwrap { it }

            // TODO What to check with the filtered transaction?

            val unconsumedStates = if (request.amount == 0L) {
                // This is not a player that matters
                listOf()
            } else {
                // Find the states to bet according to the amount requested
                serviceHub.vaultService.collectTokenStatesUntil(
                    minter = request.minter,
                    owner = me,
                    amount = request.amount
                )
            }

            progressTracker.currentStep = SENDING_TOKEN_STATES
            otherPartySession.send(BlindBetResponse(states = unconsumedStates, serviceHub = serviceHub))

            progressTracker.currentStep = RECEIVING_ALL_TOKEN_STATES
            val allPlayerStateRefs =
                otherPartySession.receive<List<StateAndRef<TokenState>>>().unwrap { it }.also { allPlayerStates ->
                    allPlayerStates.filter {
                        it.state.data.owner == me
                    }.also { myBlindBets ->
                        requireThat {
                            "Only my blind bets are in the list" using (myBlindBets.toSet() == unconsumedStates.toSet())
                            // TODO Check that the rules of blind bet were followed?
                        }
                    }
                }.map { it.ref }

            progressTracker.currentStep = SIGNING_TRANSACTION
            val txId = if (request.amount == 0L) {
                // We are actually sent the transaction hash
                progressTracker.currentStep = CHECKING_VALIDITY // We have to do it
                otherPartySession.receive<SecureHash>().unwrap { it }
            } else {
                val signTransactionFlow =
                    object : SignTransactionFlow(otherPartySession, SIGNING_TRANSACTION.childProgressTracker()) {

                        override fun checkTransaction(stx: SignedTransaction) = requireThat {
                            this@CollectorAndSigner.progressTracker.currentStep = CHECKING_VALIDITY
                            // Making sure we see our previously received states, which have been checked
                            "We should have only known moreBets" using (stx.coreTransaction.inputs.toSet() == allPlayerStateRefs.toSet())
                        }
                    }
                subFlow(signTransactionFlow).id
            }

            progressTracker.currentStep = RECEIVING_FINALISED_TRANSACTION
            return subFlow(ReceiveFinalityFlow(otherPartySession, expectedTxId = txId))
        }
    }
}

package org.cordacodeclub.bluff.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.requireThat
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.builder
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import org.cordacodeclub.bluff.contract.TokenContract
import org.cordacodeclub.bluff.state.TokenSchemaV1
import org.cordacodeclub.bluff.state.TokenState

object BlindBetFlow {

    @CordaSerializable
    class BlindBetRequest(val minter: Party, val amount: Long)

    // To use in a .fold.
    // We send the request to the player, the player returns a list of StateAndRef.
    // This is the list of responses for important players.
    class Accumulator(val request: BlindBetRequest, val states: List<List<StateAndRef<TokenState>>>)

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
                "BLIND_PLAYER_COUNT needs to be at least 2, it is $BLIND_PLAYER_COUNT" using (BLIND_PLAYER_COUNT >= 2)
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
            object PINGING_OTHER_PLAYERS : ProgressTracker.Step("Pinging other players.")
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

        fun tracker() = ProgressTracker(
            COLLECTING_BLINDBET_STATES,
            PINGING_OTHER_PLAYERS,
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

            progressTracker.currentStep = COLLECTING_BLINDBET_STATES

            val requiredSigners = players.take(BLIND_PLAYER_COUNT)
            val allFlows = players.map { initiateFlow(it) }

            val playerStates = (0 until BLIND_PLAYER_COUNT)
                .fold(
                    Accumulator(
                        request = BlindBetRequest(minter = minter, amount = smallBet),
                        states = listOf()
                    )
                ) { accumulator, playerIndex ->
                    val receivedStates = allFlows[playerIndex]
                        .sendAndReceive<List<StateAndRef<TokenState>>>(accumulator.request).unwrap { it }
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
                        // TODO enforce the doubling strictly?
                    }
                    Accumulator(
                        request = BlindBetRequest(minter = minter, amount = sum),
                        states = accumulator.states.plusElement(receivedStates)
                    )
                }.states

            progressTracker.currentStep = PINGING_OTHER_PLAYERS
            // We need to do it so that the responder flow is primed. We only care about finality with the other
            // players
            val requestOthers = BlindBetRequest(minter = minter, amount = 0L)
            players.drop(BLIND_PLAYER_COUNT).forEachIndexed { index, _ ->
                allFlows[index + BLIND_PLAYER_COUNT]
                    .sendAndReceive<List<StateAndRef<TokenState>>>(requestOthers).unwrap { it }
                    .also { statesOther ->
                        requireThat {
                            "Other players should not send any state" using (statesOther.isEmpty())
                        }
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

            val command = Command(
                TokenContract.Commands.BetToPot(),
                requiredSigners.map { it.owningKey }
            )

            progressTracker.currentStep = GENERATING_TRANSACTION
            val txBuilder = TransactionBuilder(notary = notary)

            txBuilder.addCommand(command)
            playerStates.forEach { list ->
                list.forEach {
                    txBuilder.addInputState(it)
                }
            }
            potStates.forEach {
                txBuilder.addOutputState(it, TokenContract.ID)
            }

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
                it.sendAndReceive<Unit>(fullySignedTx.id)
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
            object RECEIVING_REQUEST_FOR_STATES : ProgressTracker.Step("Receiving request for token states.")
            object SENDING_TOKEN_STATES : ProgressTracker.Step("Sending back token states.")
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
                SIGNING_TRANSACTION,
                CHECKING_VALIDITY,
                RECEIVING_FINALISED_TRANSACTION
            )
        }

        override val progressTracker: ProgressTracker = tracker()

        @Suspendable
        override fun call(): SignedTransaction {

            progressTracker.currentStep = RECEIVING_REQUEST_FOR_STATES
            // Receive an incomplete transaction and a minimum amount to bet
            val received = otherPartySession.receive<BlindBetRequest>().unwrap { it }

            // TODO What to check with the filtered transaction?

            val unconsumedStates = if (received.amount == 0L) {
                // This is not a player that matters
                listOf()
            } else {
                // Find the states to bet according to the amount requested
                var remainingAmount = received.amount

                builder {
                    val forMinter = TokenSchemaV1.PersistentToken::minter.equal(received.minter.toString())
                    val forOwner =
                        TokenSchemaV1.PersistentToken::owner.equal(serviceHub.myInfo.legalIdentities.first().toString())
                    val forIsNotPot = TokenSchemaV1.PersistentToken::isPot.equal(false)
                    val minterCriteria = QueryCriteria.VaultCustomQueryCriteria(forMinter)
                    val ownerCriteria = QueryCriteria.VaultCustomQueryCriteria(forOwner)
                    val isNotPotCriteria = QueryCriteria.VaultCustomQueryCriteria(forIsNotPot)
                    val unconsumedCriteria: QueryCriteria =
                        QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.UNCONSUMED)
                    val criteria = unconsumedCriteria.and(minterCriteria).and(ownerCriteria).and(isNotPotCriteria)
                    serviceHub.vaultService.queryBy<TokenState>(criteria).states
                }.takeWhile {
                    // TODO avoid paying more than necessary
                    // TODO soft lock the unconsumed states?
                    remainingAmount -= it.state.data.amount
                    remainingAmount > 0
                }
            }

            progressTracker.currentStep = SENDING_TOKEN_STATES
            otherPartySession.send(unconsumedStates)

            progressTracker.currentStep = SIGNING_TRANSACTION
            val txId = if (received.amount == 0L) {
                // We are actually sent the transaction hash
                otherPartySession.sendAndReceive<SecureHash>(Unit).unwrap { it }
            } else {
                val signTransactionFlow =
                    object : SignTransactionFlow(otherPartySession, SIGNING_TRANSACTION.childProgressTracker()) {

                        override fun checkTransaction(stx: SignedTransaction) = requireThat {
                            progressTracker.currentStep = CHECKING_VALIDITY
                            // Making sure we see our previously picked states
                            val receivedInputRefs = stx.coreTransaction.inputs.map {
                                if (serviceHub.validatedTransactions.getTransaction(it.txhash) != null) {
                                    serviceHub.toStateAndRef<TokenState>(it)
                                } else null
                            }.filter {
                                it != null && it.state.data.owner == serviceHub.myInfo.legalIdentities.first()
                            }.map {
                                it!!.ref
                            }

                            val previouslySent = unconsumedStates.map { it.ref }
                            "We should receive the same inputs" using (
                                    receivedInputRefs.containsAll(previouslySent) &&
                                            previouslySent.containsAll(receivedInputRefs))
                            // TODO Check that the rules of blind bet were followed
                        }
                    }
                subFlow(signTransactionFlow).id
            }

            progressTracker.currentStep = RECEIVING_FINALISED_TRANSACTION
            return subFlow(ReceiveFinalityFlow(otherPartySession, expectedTxId = txId))
        }
    }
}

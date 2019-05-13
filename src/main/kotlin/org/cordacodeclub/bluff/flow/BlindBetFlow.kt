package org.cordacodeclub.bluff.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.requireThat
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

//Initial flow
object BlindBetFlow {

    @CordaSerializable
    class BlindBetRequest(val minter: Party, val amount: Long);

    @InitiatingFlow
    @StartableByRPC
    /**
     * This flow is startable by the dealer party.
     * And it has to be signed by the players and delear.
     * @param players list of parties starting the game
     */
    class Initiator(val players: List<Party>, val minter: Party, val smallBet: Long) : FlowLogic<SignedTransaction>() {

        init {
            requireThat {
                "There needs at least 2 players" using (players.size >= 2)
            }
        }

        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object COLLECTING_SMALL_BLINDBET_STATES : ProgressTracker.Step("Collecting small blind bets from player 1.")
            object COLLECTING_BIG_BLINDBET_STATES : ProgressTracker.Step("Collecting big blind bets from player 2.")
            object GENERATING_POT_STATES : ProgressTracker.Step("Generating betting pot.")
            object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction based on new IOU.")
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

        fun tracker() = ProgressTracker(
            COLLECTING_SMALL_BLINDBET_STATES,
            COLLECTING_BIG_BLINDBET_STATES,
//            GENERATING_CARD_STATES,
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

            progressTracker.currentStep = COLLECTING_SMALL_BLINDBET_STATES

            val requiredSigners = players.take(2)
            val first2Flows = requiredSigners.map { initiateFlow(it) }
            val request0 = BlindBetRequest(minter = minter, amount = smallBet)
            val states0 = first2Flows.get(0)
                .sendAndReceive<List<StateAndRef<TokenState>>>(request0).unwrap { it }
                .filter { it.state.data.minter == minter }
            val sum0 = states0.map { it.state.data.amount }.sum()
            requireThat {
                "We have to receive at least $smallBet" using (smallBet <= sum0)
            }

            progressTracker.currentStep = COLLECTING_BIG_BLINDBET_STATES
            val request1 = BlindBetRequest(minter = minter, amount = sum0)
            val states1 = first2Flows.get(1)
                .sendAndReceive<List<StateAndRef<TokenState>>>(request1).unwrap { it }
                .filter { it.state.data.minter == minter }
            val sum1 = states1.map { it.state.data.amount }.sum()
            requireThat {
                "We have to receive at least $sum0" using (sum0 <= sum1)
                // TODO enforce the doubling strictly?
            }

            val command = Command(
                TokenContract.Commands.BetToPot(),
                requiredSigners.map { it.owningKey }
            )

            progressTracker.currentStep = GENERATING_POT_STATES
            val potState0 = TokenState(minter = minter, owner = requiredSigners.get(0), amount = sum0, isPot = true)
            // We separate them to accomodate future owner tracking
            val potState1 = TokenState(minter = minter, owner = requiredSigners.get(1), amount = sum1, isPot = true)

            progressTracker.currentStep = GENERATING_TRANSACTION
            val txBuilder = TransactionBuilder(notary = notary)

            txBuilder.addCommand(command)
            states0.forEach { txBuilder.addInputState(it) }
            states1.forEach { txBuilder.addInputState(it) }
            txBuilder.addOutputState(potState0, TokenContract.ID)
            txBuilder.addOutputState(potState1, TokenContract.ID)

            progressTracker.currentStep = VERIFYING_TRANSACTION
            txBuilder.verify(serviceHub)

            progressTracker.currentStep = SIGNING_TRANSACTION
            val signedTx = serviceHub.signInitialTransaction(txBuilder)

            progressTracker.currentStep = GATHERING_SIGS
            val fullySignedTx = subFlow(
                CollectSignaturesFlow(
                    signedTx,
                    first2Flows,
                    GATHERING_SIGS.childProgressTracker()
                )
            )

            progressTracker.currentStep = FINALISING_TRANSACTION
            return subFlow(
                FinalityFlow(
                    fullySignedTx,
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

            fun tracker() = ProgressTracker(
                RECEIVING_REQUEST_FOR_STATES,
                SENDING_TOKEN_STATES,
                SIGNING_TRANSACTION,
                CHECKING_VALIDITY
            )
        }

        override val progressTracker: ProgressTracker = tracker()

        @Suspendable
        override fun call(): SignedTransaction {

            progressTracker.currentStep = RECEIVING_REQUEST_FOR_STATES
            // Receive an incomplete transaction and a minimum amount to bet
            val received = otherPartySession.receive<BlindBetRequest>().unwrap { it }

            // TODO What to check with the filtered transaction?

            // Find the states to bet according to the amount requested
            var remainingAmount = received.amount
            val unconsumedStates = builder {
                val forMinter = TokenSchemaV1.PersistentToken::minter.equal(received.minter.toString())
                val forOwner =
                    TokenSchemaV1.PersistentToken::owner.equal(serviceHub.myInfo.legalIdentities.first().toString())
                val minterCriteria = QueryCriteria.VaultCustomQueryCriteria(forMinter)
                val ownerCriteria = QueryCriteria.VaultCustomQueryCriteria(forOwner)
                val unconsumedCriteria: QueryCriteria =
                    QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.UNCONSUMED)
                val criteria = unconsumedCriteria.and(minterCriteria).and(ownerCriteria)
                serviceHub.vaultService.queryBy<TokenState>(criteria).states
            }.takeWhile {
                // TODO avoid paying more than necessary
                remainingAmount -= it.state.data.amount
                remainingAmount > 0
            }

            // TODO soft lock the unconsumed states?

            progressTracker.currentStep = SENDING_TOKEN_STATES
            otherPartySession.send(unconsumedStates)

            progressTracker.currentStep = SIGNING_TRANSACTION
            val signTransactionFlow =
                object : SignTransactionFlow(otherPartySession, SIGNING_TRANSACTION.childProgressTracker()) {

                    override fun checkTransaction(stx: SignedTransaction) = requireThat {
                        progressTracker.currentStep = CHECKING_VALIDITY
                        // Making sure we see our previously picked states
                        val receivedInputRefs = stx.coreTransaction.inputs.map {
                            serviceHub.toStateAndRef<TokenState>(it)
                        }.filter {
                            it.state.data.owner == serviceHub.myInfo.legalIdentities.first()
                        }.map {
                            it.ref
                        }

                        val previouslySent = unconsumedStates.map { it.ref }
                        "We should receive the same inputs" using (
                                receivedInputRefs.containsAll(previouslySent) &&
                                        previouslySent.containsAll(receivedInputRefs))
                    }
                }
            return subFlow(signTransactionFlow)
        }
    }
}

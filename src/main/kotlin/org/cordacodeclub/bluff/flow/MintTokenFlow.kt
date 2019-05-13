package org.cordacodeclub.bluff.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import org.cordacodeclub.bluff.contract.TokenContract
import org.cordacodeclub.bluff.state.TokenState

//Initial flow
object MintTokenFlow {

    @InitiatingFlow
    @StartableByRPC
    /**
     * This flow is startable by the dealer party.
     * And it has to be signed by the players and delear.
     * @param players list of parties being issued tokens
     * @param amountPerPlayer the number of tokens minted to each player
     */
    class Minter(val players: List<Party>, val amountPerPlayer: Long) : FlowLogic<SignedTransaction>() {

        init {
            requireThat {
                "amountPerPlayer must be >0" using (amountPerPlayer > 0)
                "There needs at least 1 player" using (players.size > 0)
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

            progressTracker.currentStep = GENERATING_TRANSACTION
            val minter = serviceHub.myInfo.legalIdentities.first()
            val command = Command(
                TokenContract.Commands.Mint(), minter.owningKey
            )

            val txBuilder = TransactionBuilder(notary = notary)

            txBuilder.addCommand(command)

            players.forEach { player ->
                (1..amountPerPlayer).forEach {
                    // We issue many tokens of 1 each to facilitate betting.
                    txBuilder.addOutputState(TokenState(minter = minter, owner = player, amount = 1, isPot = false))
                }
            }

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

    @InitiatedBy(Minter::class)
    class Recipient(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {

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
            progressTracker.currentStep = RECEIVING_FINALISED_TRANSACTION
            return subFlow(ReceiveFinalityFlow(otherPartySession))
        }
    }
}

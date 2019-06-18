package org.cordacodeclub.bluff.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.requireThat
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import org.cordacodeclub.bluff.contract.TokenContract
import org.cordacodeclub.bluff.state.TokenState

class TransferOwnTokenFlow(
    private val inputStates: List<StateAndRef<TokenState>>,
    private val outputAmounts: List<Long>,
    override val progressTracker: ProgressTracker = tracker()
) : FlowLogic<SignedTransaction>() {

    private val notary = inputStates.map { it.state.notary }.toSet().single()
    private val minter = inputStates.map { it.state.data.minter }.toSet().single()
    private val owner = inputStates.map { it.state.data.owner }.toSet().single()
    private val isPot = inputStates.map { it.state.data.isPot }.toSet().single()

    init {
        requireThat {
            "Inputs sum should be the same as outputs sum" using
                    (inputStates.map { it.state.data.amount }.sum() == outputAmounts.sum())
            "All output amounts should be > 0" using (outputAmounts.all { it > 0 })
        }
    }

    companion object {
        object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction.")
        object VERIFYING_TRANSACTION : ProgressTracker.Step("Verifying contract constraints.")
        object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")
        object FINALISING_TRANSACTION :
            ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }

        @JvmStatic
        fun tracker() = ProgressTracker(
            GENERATING_TRANSACTION,
            VERIFYING_TRANSACTION,
            SIGNING_TRANSACTION,
            FINALISING_TRANSACTION
        )
    }

    @Suspendable
    override fun call(): SignedTransaction {

        requireThat {
            "I should be the owner" using (owner == serviceHub.myInfo.legalIdentities.first())
        }

        progressTracker.currentStep = GENERATING_TRANSACTION

        val txBuilder = TransactionBuilder(notary = notary)
        val command = Command(
            TokenContract.Commands.Transfer(),
            owner.owningKey
        )
        txBuilder.addCommand(command)
        inputStates.forEach { txBuilder.addInputState(it) }
        outputAmounts.forEach {
            txBuilder.addOutputState(
                TokenState(minter = minter, owner = owner, amount = it, isPot = isPot))
        }

        progressTracker.currentStep = VERIFYING_TRANSACTION
        txBuilder.verify(serviceHub)

        progressTracker.currentStep = SIGNING_TRANSACTION
        val signedTx = serviceHub.signInitialTransaction(txBuilder)

        progressTracker.currentStep = FINALISING_TRANSACTION
        return subFlow(
            FinalityFlow(
                signedTx,
                listOf(),
                FINALISING_TRANSACTION.childProgressTracker()
            )
        )
    }
}
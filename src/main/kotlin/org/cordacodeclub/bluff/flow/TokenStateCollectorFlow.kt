package org.cordacodeclub.bluff.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.requireThat
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.Builder.equal
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.builder
import net.corda.core.utilities.ProgressTracker
import org.cordacodeclub.bluff.state.TokenSchemaV1
import org.cordacodeclub.bluff.state.TokenState

class TokenStateCollectorFlow(
    val desired: TokenState,
    override val progressTracker: ProgressTracker = tracker()
) : FlowLogic<List<StateAndRef<TokenState>>>() {

    init {
        requireThat {
            "It can only be for a not isPot" using (!desired.isPot)
        }
    }

    companion object {
        object PREPARING_CRITERIA : ProgressTracker.Step("Preparing search criteria.")
        object GATHERING_STATES : ProgressTracker.Step("Gathering states from vault.")
        object SPLITTING_IF_NECESSARY : ProgressTracker.Step("Splitting large states if necessary.") {
            override fun childProgressTracker() = TransferOwnTokenFlow.tracker()
        }

        @JvmStatic
        fun tracker() = ProgressTracker(
            PREPARING_CRITERIA,
            GATHERING_STATES,
            SPLITTING_IF_NECESSARY
        )
    }

    @Suspendable
    override fun call(): List<StateAndRef<TokenState>> {
        progressTracker.currentStep = PREPARING_CRITERIA
        val forMinter = TokenSchemaV1.PersistentToken::minter.equal(desired.minter.toString())
        val forOwner =
            TokenSchemaV1.PersistentToken::owner.equal(desired.owner.toString())
        val forIsPot = TokenSchemaV1.PersistentToken::isPot.equal(desired.isPot)
        val minterCriteria = QueryCriteria.VaultCustomQueryCriteria(forMinter)
        val ownerCriteria = QueryCriteria.VaultCustomQueryCriteria(forOwner)
        val isNotPotCriteria = QueryCriteria.VaultCustomQueryCriteria(forIsPot)
        val unconsumedCriteria: QueryCriteria =
            QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.UNCONSUMED)
        val criteria = unconsumedCriteria.and(minterCriteria).and(ownerCriteria).and(isNotPotCriteria)

        var collectedThisFar = 0L
        val states = builder {
            progressTracker.currentStep = GATHERING_STATES
            serviceHub.vaultService.queryBy<TokenState>(criteria).states
        }.takeWhile {
            val before = collectedThisFar
            collectedThisFar += it.state.data.amount
            // TODO avoid paying more than necessary
            // TODO soft lock the unconsumed states?
            before < desired.amount
        }

        val total = states.map { it.state.data.amount }.sum()

        progressTracker.currentStep = SPLITTING_IF_NECESSARY
        // Let's do an "ugly knapsack problem
        return if (total == desired.amount) {
            states
        } else if (total > desired.amount) {
            val wentOverBy = total - desired.amount

            // Can we find a state with exactly this amount?
            val isOver = states.firstOrNull { it.state.data.amount == wentOverBy }

            if (isOver != null) {
                // Easy, remove it
                states.minus(isOver)
            } else {
                // Let's pick one that took us above and split it
                // If this fails, you should review the criteria and takeWhile
                val overState = states.first { it.state.data.amount > wentOverBy }
                val splitTx = subFlow(
                    TransferOwnTokenFlow(
                        listOf(overState),
                        listOf(overState.state.data.amount - wentOverBy, wentOverBy),
                        SPLITTING_IF_NECESSARY.childProgressTracker()
                    )
                )
                val replacementState = splitTx.tx.outRefsOfType<TokenState>().first()
                states.minus(overState).plus(replacementState)
            }
        } else {
            throw IllegalArgumentException("There are not have enough states to collect the amount")
        }
    }
}
package org.cordacodeclub.bluff.round

import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.requireThat
import net.corda.core.node.ServiceHub
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.SignedTransaction
import org.cordacodeclub.bluff.state.TokenState

@CordaSerializable
// Call: Same amount as previous player -> new states to reach above current level
// Raise bigger -> return no new state or new states to reach current level
// Fold: give away cards -> return no new state
class CallOrRaiseResponse(
    val isFold: Boolean,
    val moreBets: List<StateAndRef<TokenState>>,
    val transactions: Set<SignedTransaction>
) {
    init {
        requireThat {
            "All amounts must be strictly positive" using
                    (moreBets.fold(true) { isPos, state ->
                        isPos && state.state.data.amount > 0
                    })
            "All transactions must be relevant to the states and vice versa" using
                    (moreBets.map { it.ref.txhash }.toSet() == transactions.map { it.id }.toSet())
        }
    }

    // Fold constructor
    constructor() : this(true, listOf(), setOf())

    // Call or raise constructor
    constructor(states: List<StateAndRef<TokenState>>, txs: Set<SignedTransaction>) : this(false, states, txs)

    constructor(states: List<StateAndRef<TokenState>>, serviceHub: ServiceHub) :
            this(states, states.map { serviceHub.validatedTransactions.getTransaction(it.ref.txhash)!! }.toSet())
}

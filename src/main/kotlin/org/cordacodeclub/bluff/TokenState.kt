package org.cordacodeclub.bluff

import net.corda.core.contracts.ContractState
import net.corda.core.contracts.requireThat
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party

class TokenState(val owner: Party, val amount: Long) : ContractState {

    init {
        requireThat {
            "The value should be positive" using (amount> 0L)
        }
    }

    override val participants: List<AbstractParty>
        get() = listOf(owner)
}
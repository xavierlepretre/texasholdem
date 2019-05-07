package org.cordacodeclub.bluff.state

import net.corda.core.contracts.ContractState
import net.corda.core.contracts.requireThat
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party

data class TokenState(
    override val minter: Party,
    val owner: Party,
    override val amount: Long
) : ContractState, PokerToken {

    init {
        requireThat {
            "The value should be positive" using (amount > 0L)
        }
    }

    override val participants: List<AbstractParty>
        get() = listOf(owner)
}

interface PokerToken {
    val minter: Party
    val amount: Long
}

class PotState(override val minter: Party, override val amount: Long) : ContractState, PokerToken {

    init {
        requireThat {
            "The value should be positive" using (amount > 0L)
        }
    }

    override val participants: List<AbstractParty>
        get() = listOf()
}
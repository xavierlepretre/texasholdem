package org.cordacodeclub.bluff.state

import net.corda.core.contracts.ContractState
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party

data class PlayerHandState(
        val cardIndexes: List<Int>,
        val owner: Party
) : ContractState {
    override val participants: List<AbstractParty>
        get() = listOf(owner)
}
package org.cordacodeclub.bluff.state

import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState
import net.corda.core.identity.AbstractParty
import org.cordacodeclub.bluff.contract.GameContract

@BelongsToContract(GameContract::class)
data class GameState(val cards: List<AssignedCard>) : ContractState {
    override val participants: List<AbstractParty>
        get() = cards.map { it.owner }
}
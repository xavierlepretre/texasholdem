package org.cordacodeclub.bluff.state

import net.corda.core.contracts.ContractState
import net.corda.core.contracts.requireThat
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party

data class BlindBetState(
        val sb: Long,
        val bb: Long? = null,
        val owner: Party,
        val status: BlindBetStatus
) : ContractState {

    init {
        requireThat {
            "The small bet should be positive" using (sb > 0L)
            if (bb != null) "The blind bet should be more than small bet" using (bb > sb)
        }
    }
    override val participants: List<AbstractParty>
        get() = listOf(owner)
}


enum class BlindBetStatus { SMALLBLIND, BIGBLIND, PASS }
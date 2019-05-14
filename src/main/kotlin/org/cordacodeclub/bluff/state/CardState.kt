package org.cordacodeclub.bluff.state

import net.corda.core.contracts.ContractState
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party

data class CardState(
        val communityCards: List<Any>, //These will be Card objects
        override val playerCards: List<Any>,
        val remainingCards: List<Any>,
        val owner: Party,
        val dealer: Party,
        val blindBet: BlindBetState
        ) : ContractState, PlayerCardState {
    override val participants: List<AbstractParty> get() = listOf(owner, dealer)
}

interface PlayerCardState {
    val playerCards: List<Any>
}
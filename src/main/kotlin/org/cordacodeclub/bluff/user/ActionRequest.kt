package org.cordacodeclub.bluff.user

import net.corda.core.serialization.CordaSerializable
import org.cordacodeclub.bluff.flow.Action
import org.cordacodeclub.grom356.CardList

@CordaSerializable
data class ActionRequest(
    val id: Long,
    val party: String,
    val cards: String,
    val youBet: Long,
    val lastRaise: Long,
    val action: Action?,
    val addAmount: Long
) {
    val cardList = CardList.valueOf(cards)
}
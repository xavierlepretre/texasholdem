package org.cordacodeclub.bluff.user

import net.corda.core.serialization.CordaSerializable
import org.cordacodeclub.bluff.flow.Action

@CordaSerializable
data class ActionRequest(
    val id: Long,
    val party: String,
    val card1: String,
    val card2: String,
    val card3: String,
    val card4: String,
    val card5: String,
    val card6: String,
    val card7: String,
    val youBet: Long,
    val lastRaise: Long,
    val action: Action?,
    val addAmount: Long
) {
    val cards = listOf(card1, card2, card3, card4, card5, card6, card7)
}
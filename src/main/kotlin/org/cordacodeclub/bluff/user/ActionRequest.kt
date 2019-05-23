package org.cordacodeclub.bluff.user

import net.corda.core.identity.CordaX500Name
import net.corda.core.serialization.CordaSerializable
import org.cordacodeclub.bluff.flow.Action
import org.cordacodeclub.grom356.Card
import org.cordacodeclub.grom356.CardList

@CordaSerializable
data class ActionRequest(
    val id: Long,
    val player: CordaX500Name,
    val cards: List<Card>,
    val youBet: Long,
    val lastRaise: Long,
    val action: Action?,
    val addAmount: Long
) {
    constructor(
        id: Long,
        player: String,
        cards: String,
        youBet: Long,
        lastRaise: Long,
        action: Action?,
        addAmount: Long
    ) : this(
        id = id,
        player = CordaX500Name.parse(player),
        cards = CardList.valueOf(cards),
        youBet = youBet,
        lastRaise = lastRaise,
        action = action,
        addAmount = addAmount
    )
}
package org.cordacodeclub.bluff.state

import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import org.cordacodeclub.grom356.Card

@CordaSerializable
data class ClearCard(
        override val card: Card,
        override val owner: Party
) : AssignedCard {
    override val encrytedCard: String? = null
}
package org.cordacodeclub.bluff.state

import net.corda.core.identity.Party
import org.cordacodeclub.grom356.Card

data class EncryptedCard(
        override val encrytedCard: String,
        override val owner: Party
) : AssignedCard {
    override val card: Card? = null
}
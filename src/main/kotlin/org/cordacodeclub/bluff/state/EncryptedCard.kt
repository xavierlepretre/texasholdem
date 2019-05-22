package org.cordacodeclub.bluff.state

import net.corda.core.identity.Party

data class EncryptedCard(
    override val encrytedCard: String,
    override val owner: Party
) : AssignedCard {

    override val card = null
    override val salt = null
}
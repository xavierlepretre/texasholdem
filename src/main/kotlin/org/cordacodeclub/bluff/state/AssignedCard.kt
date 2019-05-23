package org.cordacodeclub.bluff.state

import net.corda.core.contracts.requireThat
import net.corda.core.identity.CordaX500Name
import net.corda.core.serialization.CordaSerializable
import org.cordacodeclub.grom356.Card

@CordaSerializable
data class AssignedCard(
    val card: Card,
    val salt: String,
    val owner: CordaX500Name
) {

    constructor(card: String, salt: String, owner: String) : this(
        card = Card.valueOf(card),
        salt = salt,
        owner = CordaX500Name.parse(owner)
    )

    init {
        requireThat {
            "salt must not be more than $SALT_LENGTH characters long" using (salt.length <= SALT_LENGTH)
        }
    }

    companion object {
        const val SALT_LENGTH = 50
    }
}
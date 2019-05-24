package org.cordacodeclub.bluff.state

import net.corda.core.contracts.requireThat
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.CordaX500Name
import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.serialize
import org.cordacodeclub.grom356.Card

@CordaSerializable
data class AssignedCard(
    val card: Card,
    val salt: ByteArray,
    val owner: CordaX500Name
) {

    val hash: SecureHash by lazy {
        serialize().hash
    }

    constructor(card: String, salt: ByteArray, owner: String) : this(
        card = Card.valueOf(card),
        salt = salt,
        owner = CordaX500Name.parse(owner)
    )

    init {
        requireThat {
            "salt must not be more than $SALT_LENGTH characters long" using (salt.size <= SALT_LENGTH)
        }
    }

    companion object {
        const val SALT_LENGTH = 50
    }
}


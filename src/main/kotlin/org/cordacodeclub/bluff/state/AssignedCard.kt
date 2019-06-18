package org.cordacodeclub.bluff.state

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

    val hash by lazy { serialize().hash }

    constructor(card: String, salt: ByteArray, owner: String) : this(
        card = Card.valueOf(card),
        salt = salt,
        owner = CordaX500Name.parse(owner)
    )

    init {
        require(salt.size == SALT_LENGTH) { "salt must be exactly $SALT_LENGTH characters long" }
    }

    companion object {
        const val SALT_LENGTH = 50
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AssignedCard

        if (hash != other.hash) return false

        return true
    }

    override fun hashCode(): Int {
        return hash.hashCode()
    }
}


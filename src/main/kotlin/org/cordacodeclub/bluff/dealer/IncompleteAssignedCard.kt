package org.cordacodeclub.bluff.dealer

import net.corda.core.crypto.SecureHash
import net.corda.core.identity.CordaX500Name
import net.corda.core.serialization.CordaSerializable
import org.cordacodeclub.bluff.state.AssignedCard
import org.cordacodeclub.grom356.Card

@CordaSerializable
data class IncompleteAssignedCard(
    val card: Card?,
    val salt: ByteArray?,
    val owner: CordaX500Name?,
    val hash: SecureHash
) {
    val hasNullable: Boolean

    constructor(card: String?, salt: ByteArray?, owner: String?, hash: ByteArray) : this(
        card = if (card == null) null else Card.valueOf(card),
        salt = salt,
        owner = if (owner == null) null else CordaX500Name.parse(owner),
        hash = SecureHash.sha256(hash)
    )

    constructor(assignedCard: AssignedCard?, hash: SecureHash) : this(
        card = assignedCard?.card,
        salt = assignedCard?.salt,
        owner = assignedCard?.owner,
        hash = hash
    )

    constructor(hash: SecureHash) : this(
        card = null,
        salt = null,
        owner = null,
        hash = hash
    )

    init {
        require(salt == null || salt.size == AssignedCard.SALT_LENGTH) {
            "salt must not be exactly ${AssignedCard.SALT_LENGTH} characters long"
        }
        hasNullable = card == null && salt == null && owner == null
        val noNull = card != null && salt != null && owner != null
        require(hasNullable xor noNull) {
            "All nullables should be null or all nullables should be not null, not a mix"
        }
        if (!hasNullable) require(AssignedCard(card!!, salt!!, owner!!).hash == hash) {
            "If there is no nullable element, the hash has to match that of the same AssignedCard"
        }
    }

    fun toAssignedCard() =
        if (hasNullable) null
        else AssignedCard(
            card = card!!,
            salt = salt!!,
            owner = owner!!
        )

    fun addsTo(current: IncompleteAssignedCard) = (!hasNullable && current.hasNullable).also {
        require(hash == current.hash) { "It does not represent the same card" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as IncompleteAssignedCard

        if (hash != other.hash) return false

        return true
    }

    override fun hashCode(): Int {
        return hash.hashCode()
    }
}


package org.cordacodeclub.bluff.dealer

import net.corda.core.crypto.MerkleTree
import net.corda.core.crypto.SecureHash
import net.corda.core.serialization.CordaSerializable
import org.cordacodeclub.bluff.state.AssignedCard

@CordaSerializable
open class HashedCardDeckInfo(val hashedCards: List<SecureHash>) {

    init {
        require(hashedCards.size == DECK_SIZE) { "There should be exactly $DECK_SIZE cards" }
    }

    val merkleTree: MerkleTree by lazy {
        MerkleTree.getMerkleTree(hashedCards)
    }

    // The Merkle tree root hash of the assigned cards. This is the unique identifier of the assigned deck.
    val rootHash: SecureHash by lazy {
        merkleTree.hash
    }

    companion object {
        const val DECK_SIZE = 52
        const val CARDS_PER_PLAYER = 2
        const val COMMUNITY_CARDS_COUNT = 5
    }

    fun getPlayerCardIndices(playerIndex: Int) =
        playerIndex * CARDS_PER_PLAYER until (playerIndex + 1) * CARDS_PER_PLAYER

    fun getCommunityCardIndices(playerCount: Int) =
        playerCount * CARDS_PER_PLAYER until (playerCount * CARDS_PER_PLAYER) + COMMUNITY_CARDS_COUNT
}

fun MerkleTree.getLeaves(partBuilt: List<MerkleTree.Leaf> = emptyList()): List<MerkleTree.Leaf> = when (this) {
    is MerkleTree.Leaf -> partBuilt.plus(this)
    is MerkleTree.Node -> partBuilt.plus(this.left.getLeaves().plus(this.right.getLeaves()))
    else -> throw IllegalArgumentException("Unkown type ${this::class}")
}

fun MerkleTree.containsAll(assignedCards: List<AssignedCard>) =
    getLeaves().containsAll(assignedCards)

fun List<MerkleTree.Leaf>.containsAll(assignedCards: List<AssignedCard>) =
    map { it.hash }.containsAll(assignedCards.map { it.hash })

fun List<MerkleTree.Leaf>.contains(assignedCard: AssignedCard) =
    map { it.hash }.contains(assignedCard.hash)
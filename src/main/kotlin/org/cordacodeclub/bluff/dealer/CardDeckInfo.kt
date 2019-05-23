package org.cordacodeclub.bluff.dealer

import net.corda.core.contracts.requireThat
import net.corda.core.crypto.MerkleTree
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.CordaX500Name
import org.cordacodeclub.bluff.state.AssignedCard
import org.cordacodeclub.bluff.state.AssignedCard.Companion.SALT_LENGTH
import org.cordacodeclub.grom356.Card
import kotlin.random.Random

data class CardDeckInfo(val cards: List<AssignedCard>) {

    val merkleTree: MerkleTree by lazy {
        cards.map {
            it.hash
        }.let {
            MerkleTree.getMerkleTree(it)
        }
    }

    // The Merkle tree root hash of the assigned cards. This is the unique identifier of the assigned deck.
    val rootHash: SecureHash by lazy {
        merkleTree.hash
    }

    init {
        requireThat {
            "There should be exactly $DECK_SIZE cards" using (cards.size == DECK_SIZE)
        }
    }

    companion object {

        const val DECK_SIZE = 52
        const val CARDS_PER_PLAYER = 2

        fun createShuffledWith(players: List<CordaX500Name>, dealer: CordaX500Name) =
            with(Random(System.nanoTime())) {
                // Shuffle cards
                // TODO better shuffling algorithm?
                Card.newDeck().shuffled().map {
                    it to nextBytes(SALT_LENGTH * 2).toString().take(SALT_LENGTH)
                }
            }.let { saltedCards ->
                saltedCards.mapIndexed { index, pair ->
                    // Assign cards
                    (index / CARDS_PER_PLAYER).let { playerIndex ->
                        if (playerIndex < players.size) players[playerIndex]
                        else dealer
                    }.let { owner ->
                        AssignedCard(pair.first, pair.second, owner)
                    }
                }
            }.let {
                CardDeckInfo(it)
            }
    }
}

fun MerkleTree.getLeaves(partBuilt : List<MerkleTree.Leaf> = emptyList()) : List<MerkleTree.Leaf> = when(this) {
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


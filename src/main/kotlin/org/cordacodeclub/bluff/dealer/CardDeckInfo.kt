package org.cordacodeclub.bluff.dealer

import net.corda.core.identity.CordaX500Name
import net.corda.core.serialization.CordaSerializable
import org.cordacodeclub.bluff.state.AssignedCard
import org.cordacodeclub.bluff.state.AssignedCard.Companion.SALT_LENGTH
import org.cordacodeclub.grom356.Card
import kotlin.random.Random

@CordaSerializable
data class CardDeckInfo(val cards: List<AssignedCard>) : HashedCardDeckInfo(cards.map { it.hash }) {

    companion object {

        fun createWith(cards: List<Card>, players: List<CordaX500Name>, dealer: CordaX500Name) =
            with(Random(System.nanoTime())) {
                cards.map { it to nextBytes(SALT_LENGTH) }
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

        fun createShuffledWith(players: List<CordaX500Name>, dealer: CordaX500Name) =
            createWith(Card.newDeck().shuffled(), players, dealer)
    }

    fun getPlayerCards(playerIndex: Int) = getPlayerCardIndices(playerIndex).map {
        cards[it]
    }

    fun getCommunityCards(playerCount: Int) = getCommunityCardIndices(playerCount).map {
        cards[it]
    }
}

// TODO Add functions to check player cards with their positions in merkle root


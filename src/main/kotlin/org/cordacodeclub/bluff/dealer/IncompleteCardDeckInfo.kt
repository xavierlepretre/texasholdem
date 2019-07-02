package org.cordacodeclub.bluff.dealer

import net.corda.core.crypto.SecureHash
import net.corda.core.identity.CordaX500Name
import net.corda.core.serialization.CordaSerializable
import org.cordacodeclub.bluff.state.AssignedCard

@CordaSerializable
class IncompleteCardDeckInfo(
    hashedCards: List<SecureHash>,
    val cards: List<AssignedCard?>
) : HashedCardDeckInfo(hashedCards) {

    val isComplete by lazy {
        cards.all { it != null }
    }

    val isEmpty by lazy {
        cards.all { it == null }
    }

    constructor(cardDeckInfo: CardDeckInfo) : this(
        cardDeckInfo.hashedCards,
        cardDeckInfo.cards.map { it }
    )

    constructor(
        fullDeck: CardDeckInfo,
        onlyPlayers: List<CordaX500Name>,
        dealer: CordaX500Name,
        dealerCardsCount: Int
    ) : this(
        fullDeck.hashedCards,
        dealerCardsCount.let { dealerCount ->
            var dealerRemaining = dealerCount
            fullDeck.cards.map {
                if (it.owner == dealer && dealerRemaining > 0) {
                    dealerRemaining--
                    it
                } else if (it.owner in onlyPlayers) it
                else null
            }
        }
    )

    init {
        require(hashedCards.size == cards.size) { "The 2 lists must have the same size" }
        require(cards.foldIndexed(true) { index, all, card ->
            all && (card == null || hashedCards[index] == card.hash)
        }) { "Non-null cards must hash correctly" }
    }

    companion object {
        fun withNullCards(deck: CardDeckInfo) = IncompleteCardDeckInfo(
            deck.hashedCards,
            (0 until HashedCardDeckInfo.DECK_SIZE).map { null as AssignedCard? })
    }

    fun revealOwnedCards(fullDeck: CardDeckInfo, owner: CordaX500Name, count: Int): IncompleteCardDeckInfo {
        var remaining = count
        return IncompleteCardDeckInfo(
            hashedCards,
            cards.mapIndexed { index, card ->
                if (0 < remaining && fullDeck.cards[index].owner == owner) {
                    remaining--
                    fullDeck.cards[index]
                } else {
                    card
                }
            }.also {
                require(remaining == 0) { "Could only reveal ${count - remaining} cards, not $count" }
            })
    }

}
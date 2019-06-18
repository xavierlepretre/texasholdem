package org.cordacodeclub.bluff.dealer

import net.corda.core.crypto.SecureHash
import net.corda.core.serialization.CordaSerializable
import org.cordacodeclub.bluff.state.AssignedCard

@CordaSerializable
class IncompleteCardDeckInfo(
    hashedCards: List<SecureHash>,
    val cards: List<AssignedCard?>
) : HashedCardDeckInfo(hashedCards) {

    val isComplete by lazy {
        cards.all { it != null}
    }

    constructor(cardDeckInfo: CardDeckInfo) : this(
        cardDeckInfo.hashedCards,
        cardDeckInfo.cards.map { it }
    )

    init {
        require(hashedCards.size == cards.size) { "The 2 lists must have the same size" }
        require(cards.foldIndexed(true) { index, all, card ->
            all && (card == null || hashedCards[index] == card.hash)
        }) { "Non-null cards must hash correctly" }
    }
}
package org.cordacodeclub.bluff.state

import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import org.cordacodeclub.bluff.dealer.IncompleteCardDeckInfo
import org.cordacodeclub.grom356.CardSet
import org.cordacodeclub.grom356.Hand

@CordaSerializable
data class PlayerHand(val player: Party, val hand: Hand)

@CordaSerializable
data class PlayerHandByIndex(val player: Party, val cardIndices: List<Int>)

fun List<PlayerHandByIndex>.sortDescendingWith(deck: IncompleteCardDeckInfo, dealer: CordaX500Name) =
    map {
        it.cardIndices
            .mapIndexed { index, inDeck ->
                deck.cards[inDeck].let { assignedCard ->
                    require(assignedCard != null) {
                        "Card $index for ${it.player}, in deck at $inDeck is null"
                    }
                    require(assignedCard.owner == it.player.name || assignedCard.owner == dealer) {
                        "Player ${it.player} does not own card $index, in deck at $inDeck"
                    }
                    assignedCard.card
                }
            }
            .let { list -> CardSet(list) }
            .let { set -> Hand.eval(set) }
            .let { hand -> PlayerHand(it.player, hand) }
    }.sortedByDescending { it.hand }

fun List<PlayerHand>.getWinners() = fold(listOf<PlayerHand>()) { list, element ->
    val currentTop = if(list.size > 0) list[0].hand.value else -1
    if (element.hand.value > currentTop) listOf(element)
    else if (element.hand.value == currentTop) list.plus(element)
    else list
}
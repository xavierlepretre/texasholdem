package org.cordacodeclub.bluff.contract

import net.corda.core.identity.CordaX500Name
import net.corda.testing.core.TestIdentity
import org.cordacodeclub.bluff.state.ClearCard
import org.cordacodeclub.bluff.state.PlayerHandState
import org.cordacodeclub.grom356.Card


fun main(args: Array<String>) {
    val cards = Card.newDeck()

    val testDealer = TestIdentity(CordaX500Name("testDealer", "London", "GB"))
    val testPartyA = TestIdentity(CordaX500Name("TestPlayerA", "London", "GB"))
    val testPartyB = TestIdentity(CordaX500Name("TestPlayerB", "London", "GB"))
    val testPartyC = TestIdentity(CordaX500Name("TestPlayerC", "London", "GB"))

    val playerAHand = PlayerHandState(listOf(10,20,30,40,50), testPartyA.party)
    val playerACards = playerAHand.cardIndexes.map { cards[it] }
    println("PlayerA cards:" + playerACards.map { it })
    val playerACardsAssigned = playerACards.map { ClearCard(it, testPartyA.party) }


    val playerBHand = PlayerHandState(listOf(5,15,30,40,50), testPartyB.party)
    val playerBCards = playerBHand.cardIndexes.map { cards[it] }
    println("PlayerB cards:" + playerBCards.map { it })
    val playerBCardsAssigned = playerBCards.map { ClearCard(it, testPartyB.party) }

    val playerCHand = PlayerHandState(listOf(3,6,30,40,50), testPartyC.party)
    val playerCCards = playerCHand.cardIndexes.map { cards[it] }
    println("PlayerC cards:" + playerCCards.map { it })
    val playerCCardsAssigned = playerCCards.map { ClearCard(it, testPartyC.party) }

    val remainingCards = cards - (playerACards + playerBCards + playerCCards)
    val remainingCardsAssigned = remainingCards.map { ClearCard(it, testDealer.party) }

    val gameCards = (playerACardsAssigned + playerBCardsAssigned + playerCCardsAssigned + remainingCardsAssigned)
    val playerHandStates =  listOf(playerAHand, playerBHand, playerCHand)
    println(sortedHands(playerHandStates, gameCards))
}

package org.cordacodeclub.bluff.contract

import net.corda.core.identity.CordaX500Name
import net.corda.testing.core.TestIdentity
import org.cordacodeclub.bluff.state.ClearCard
import org.cordacodeclub.bluff.state.PlayerHandState
import org.cordacodeclub.grom356.Card


fun main(args: Array<String>) {
    val cards = Card.newDeck().shuffled()

    val testDealer = TestIdentity(CordaX500Name("testDealer", "London", "GB"))
    val testPartyA = TestIdentity(CordaX500Name("TestPlayerA", "London", "GB"))
    val testPartyB = TestIdentity(CordaX500Name("TestPlayerB", "London", "GB"))
    val testPartyC = TestIdentity(CordaX500Name("TestPlayerC", "London", "GB"))

    //For test sake there is an assumption the first two cards are players and rest are community
    val playerAHand = PlayerHandState(listOf(0,1,6,7,8), testPartyA.party)
    val playerACards = playerAHand.cardIndexes.map { cards[it] }
    println("PlayerA cards:" + playerACards.map { it })
    val playerACardsAssignedOnly = playerACards.take(2).map { ClearCard(it, testPartyA.party) }


    val playerBHand = PlayerHandState(listOf(2,3,6,7,8), testPartyB.party)
    val playerBCards = playerBHand.cardIndexes.map { cards[it] }
    println("PlayerB cards:" + playerBCards.map { it })
    val playerBCardsAssignedOnly = playerBCards.take(2).map { ClearCard(it, testPartyB.party) }

    val playerCHand = PlayerHandState(listOf(4,5,6,7,8), testPartyC.party)
    val playerCCards = playerCHand.cardIndexes.map { cards[it] }
    println("PlayerC cards:" + playerCCards.map { it })
    val playerCCardsAssignedOnly = playerCCards.take(2).map { ClearCard(it, testPartyC.party) }

    val remainingCards = cards - cards.take(6)
    val remainingCardsAssigned = remainingCards.map { ClearCard(it, testDealer.party) }

    val gameCards = (playerACardsAssignedOnly + playerBCardsAssignedOnly + playerCCardsAssignedOnly + remainingCardsAssigned)
    val playerHandStates =  listOf(playerAHand, playerBHand, playerCHand)
    println("Winning hand: " + winningHand(playerHandStates, gameCards))
}

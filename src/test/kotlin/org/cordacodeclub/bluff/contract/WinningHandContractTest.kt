package org.cordacodeclub.bluff.contract

import net.corda.core.identity.CordaX500Name
import net.corda.testing.core.TestIdentity
import org.cordacodeclub.bluff.dealer.CardDeckInfo
import org.cordacodeclub.bluff.state.AssignedCard
import org.cordacodeclub.bluff.state.PlayerHandState
import org.cordacodeclub.grom356.Card


fun main(args: Array<String>) {

    val testDealer = TestIdentity(CordaX500Name("testDealer", "London", "GB"))
    val testPartyA = TestIdentity(CordaX500Name("TestPlayerA", "London", "GB"))
    val testPartyB = TestIdentity(CordaX500Name("TestPlayerB", "London", "GB"))
    val testPartyC = TestIdentity(CordaX500Name("TestPlayerC", "London", "GB"))

    val players = listOf(testPartyA.party, testPartyB.party, testPartyC.party)
    val deckInfo = CardDeckInfo.createShuffledWith(players.map { it.name }, testDealer.name)

    val playerAHand = PlayerHandState(listOf(0,1,6,7,8), testPartyA.party)
    val playerACards = deckInfo.cards.filter { it.owner == testPartyA.name }
    println("PlayerA cards:" + playerACards.map { it })

    val playerBHand = PlayerHandState(listOf(2,3,6,7,8), testPartyB.party)
    val playerBCards = deckInfo.cards.filter { it.owner == testPartyB.name }
    println("PlayerB cards:" + playerBCards.map { it })

    val playerCHand = PlayerHandState(listOf(4,5,6,7,8), testPartyC.party)
    val playerCCards = deckInfo.cards.filter { it.owner == testPartyC.name }
    println("PlayerC cards:" + playerCCards.map { it })

    val gameCards = deckInfo.cards
    val playerHandStates =  listOf(playerAHand, playerBHand, playerCHand)
    println("Winning hand: " + winningHand(playerHandStates, gameCards))
}

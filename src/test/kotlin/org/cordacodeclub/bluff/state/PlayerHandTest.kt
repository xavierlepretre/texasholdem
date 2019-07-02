package org.cordacodeclub.bluff.state

import net.corda.core.identity.CordaX500Name
import net.corda.testing.core.TestIdentity
import net.corda.testing.node.MockServices
import net.corda.testing.node.ledger
import org.cordacodeclub.bluff.dealer.CardDeckInfo
import org.cordacodeclub.bluff.dealer.HashedCardDeckInfo
import org.cordacodeclub.bluff.dealer.IncompleteCardDeckInfo
import org.cordacodeclub.grom356.Card
import org.junit.Before
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertFailsWith

class PlayerHandTest {

    private val ledgerServices = MockServices()
    private val dealer0 = TestIdentity(CordaX500Name("Dealer0", "London", "GB"))
    private val player0 = TestIdentity(CordaX500Name("Player0", "Madrid", "ES"))
    private val player1 = TestIdentity(CordaX500Name("Player1", "Berlin", "DE"))
    private val player2 = TestIdentity(CordaX500Name("Player2", "Bern", "CH"))
    private val player3 = TestIdentity(CordaX500Name("Player3", "New York City", "US"))
    private lateinit var fullDeck: CardDeckInfo
    private lateinit var validDeck: IncompleteCardDeckInfo

    @Before
    fun setup() {
        // For serialisation context
        ledgerServices.ledger {
            val random = Random(System.nanoTime())
            // Make it good for player0
            val goodCards = listOf(
                Card.valueOf("Qs"),
                Card.valueOf("Js"),
                Card.valueOf("Ts"),
                Card.valueOf("9s"),
                Card.valueOf("8s")
            )
            val otherCards = Card.newDeck().minus(goodCards)
            val deckCards = listOf(Card.valueOf("Qs"), Card.valueOf("Js"))
                .plus(otherCards.take(HashedCardDeckInfo.CARDS_PER_PLAYER * 3))
                .plus(listOf(Card.valueOf("Ts"), Card.valueOf("9s"), Card.valueOf("8s")))
                .plus(otherCards.drop(HashedCardDeckInfo.CARDS_PER_PLAYER * 3))
            fullDeck = CardDeckInfo.createWith(
                cards = deckCards,
                players = listOf(player0.name, player1.name, player2.name, player3.name),
                dealer = dealer0.name
            )
            validDeck = IncompleteCardDeckInfo(
                fullDeck = fullDeck,
                onlyPlayers = listOf(player0.name, player1.name, player2.name, player3.name),
                dealer = dealer0.name,
                dealerCardsCount = 3
            )
        }
    }

    @Test
    fun `sortWith returns correct list`() {
        val playerHands = listOf(
            PlayerHandByIndex(player0.party, listOf(0, 1, 8, 9, 10)),
            PlayerHandByIndex(player1.party, listOf(2, 3, 8, 9, 10))
        )
        val sorted = playerHands.sortDescendingWith(validDeck, dealer0.name)
        println(sorted)
    }

    @Test
    fun `sortWith fails if a required card is missing`() {
        val playerHands = listOf(
            PlayerHandByIndex(player0.party, listOf(0, 1, 11)),
            PlayerHandByIndex(player1.party, listOf(2, 3, 10))
        )
        assertFailsWith(IllegalArgumentException::class) { playerHands.sortDescendingWith(validDeck, dealer0.name) }
    }

    @Test
    fun `sortWith fails if a required card is incorrectly owned`() {
        val playerHands = listOf(
            PlayerHandByIndex(player0.party, listOf(2, 1, 9)),
            PlayerHandByIndex(player1.party, listOf(2, 3, 10))
        )
        assertFailsWith(IllegalArgumentException::class) { playerHands.sortDescendingWith(validDeck, dealer0.name) }
    }
}
package org.cordacodeclub.bluff.dealer

import net.corda.core.identity.CordaX500Name
import net.corda.testing.node.MockServices
import net.corda.testing.node.ledger
import org.cordacodeclub.bluff.state.AssignedCard
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IncompleteCardDeckInfoTest {

    private val ledgerServices = MockServices()
    private val dealer0 = CordaX500Name("Dealer0", "London", "GB")
    private val player0 = CordaX500Name("Player0", "Madrid", "ES")
    private val player1 = CordaX500Name("Player1", "Berlin", "DE")
    private val player2 = CordaX500Name("Player2", "Bern", "CH")
    private val player3 = CordaX500Name("Player3", "New York City", "US")
    private lateinit var fullDeck: CardDeckInfo

    @Before
    fun setup() {
        ledgerServices.ledger {
            // For serialisation context
            fullDeck = CardDeckInfo.createShuffledWith(
                listOf(player0, player1, player2, player3),
                dealer0
            )
        }
    }

    @Test
    fun `createNullList is correct`() {
        val nullList = IncompleteCardDeckInfo.withNullCards(fullDeck)
        assertEquals(HashedCardDeckInfo.DECK_SIZE, nullList.cards.size)
        assertTrue(nullList.cards.all { it == null })
    }

    @Test
    fun `Constructor with full deck makes it full`() {
        val deck = IncompleteCardDeckInfo(fullDeck)
        assertTrue(deck.isComplete)
    }

    @Test
    fun `Constructor fails if cards and hashes are not same length`() {
        assertFailsWith<IllegalArgumentException>("The 2 lists must have the same size") {
            IncompleteCardDeckInfo(
                fullDeck.hashedCards,
                (1 until HashedCardDeckInfo.DECK_SIZE).map { null as AssignedCard? }
            )
        }
    }

    @Test
    fun `Constructor fails if a card does not serialise to its hash`() {
        assertFailsWith<IllegalArgumentException>("Non-null cards must hash correctly") {
            IncompleteCardDeckInfo(
                fullDeck.hashedCards,
                (1 until HashedCardDeckInfo.DECK_SIZE).map { null as AssignedCard? }
                    .plus(fullDeck.cards[HashedCardDeckInfo.DECK_SIZE - 2])
            )
        }
    }

    @Test
    fun `isComplete reports correctly`() {
        val deck1 = IncompleteCardDeckInfo(fullDeck)
        assertTrue(deck1.isComplete)

        val listWithMissing = fullDeck.cards.mapIndexed { index, it ->
            if (index == 23) null
            else it
        }
        val deck2 = IncompleteCardDeckInfo(fullDeck.hashedCards, listWithMissing)
        assertFalse(deck2.isComplete)
    }

    @Test
    fun `isEmpty reports correctly`() {
        val deck = IncompleteCardDeckInfo.withNullCards(fullDeck)
        assertTrue(deck.isEmpty)

        val withOne = deck.cards.mapIndexed { index, it ->
            if (index == 41) fullDeck.cards[41]
            else it
        }
        assertFalse(withOne.isEmpty())
    }

    @Test
    fun `revealOwnedCards fails if there were not enough cards to reveal`() {
        val deck = IncompleteCardDeckInfo.withNullCards(fullDeck)

        assertFailsWith<IllegalArgumentException>("Could only reveal ${HashedCardDeckInfo.CARDS_PER_PLAYER} cards") {
            deck.revealOwnedCards(fullDeck, player0, HashedCardDeckInfo.CARDS_PER_PLAYER + 1)
        }
    }

    @Test
    fun `revealOwnedCards is correct`() {
        val deck = IncompleteCardDeckInfo.withNullCards(fullDeck)

        assertEquals(
            HashedCardDeckInfo.CARDS_PER_PLAYER,
            deck.revealOwnedCards(fullDeck, player0, HashedCardDeckInfo.CARDS_PER_PLAYER).cards
                .mapIndexed { index, it ->
                    if (it != null) {
                        assertTrue(index in setOf(0, 1))
                        assertEquals(player0, it.owner)
                        1
                    } else 0
                }.sum()
        )

        assertEquals(
            HashedCardDeckInfo.CARDS_PER_PLAYER,
            deck.revealOwnedCards(fullDeck, player1, HashedCardDeckInfo.CARDS_PER_PLAYER).cards
                .mapIndexed { index, it ->
                    if (it != null) {
                        assertTrue(index in setOf(2, 3))
                        assertEquals(player1, it.owner)
                        1
                    } else 0
                }.sum()
        )

        assertEquals(
            HashedCardDeckInfo.CARDS_PER_PLAYER,
            deck.revealOwnedCards(fullDeck, player2, HashedCardDeckInfo.CARDS_PER_PLAYER).cards
                .mapIndexed { index, it ->
                    if (it != null) {
                        assertTrue(index in setOf(4, 5))
                        assertEquals(player2, it.owner)
                        1
                    } else 0
                }.sum()
        )

        assertEquals(
            HashedCardDeckInfo.CARDS_PER_PLAYER,
            deck.revealOwnedCards(fullDeck, player3, HashedCardDeckInfo.CARDS_PER_PLAYER).cards
                .mapIndexed { index, it ->
                    if (it != null) {
                        assertTrue(index in setOf(6, 7))
                        assertEquals(player3, it.owner)
                        1
                    } else 0
                }.sum()
        )

        assertEquals(
            HashedCardDeckInfo.COMMUNITY_CARDS_COUNT,
            deck.revealOwnedCards(fullDeck, dealer0, HashedCardDeckInfo.COMMUNITY_CARDS_COUNT).cards
                .mapIndexed { index, it ->
                    if (it != null) {
                        assertTrue(index in 8..HashedCardDeckInfo.DECK_SIZE)
                        assertEquals(dealer0, it.owner)
                        1
                    } else 0
                }.sum()
        )
    }
}
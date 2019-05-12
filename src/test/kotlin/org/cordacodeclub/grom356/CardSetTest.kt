package org.cordacodeclub.grom356

import org.junit.Test
import kotlin.test.assertEquals

class CardSetTest {

    fun List<Card>.foldToSet(set: CardSet) = fold(set) { acc, card ->
        acc.add(card)
        acc
    }

    @Test
    fun checkSpadeSet() {
        val allSuit = Card.Rank.values()
            .map { Card(it, Card.Suit.SPADE) }
            .foldToSet(CardSet())
        assertEquals(0x1111111111111L, allSuit.longValue())
    }

    @Test
    fun checkHeartSet() {
        val allSuit = Card.Rank.values()
            .map { Card(it, Card.Suit.HEART) }
            .foldToSet(CardSet())
        assertEquals(0x2222222222222L, allSuit.longValue())
    }

    @Test
    fun checkDiamondSet() {
        val allSuit = Card.Rank.values()
            .map { Card(it, Card.Suit.DIAMOND) }
            .foldToSet(CardSet())
        assertEquals(0x4444444444444L, allSuit.longValue())
    }

    @Test
    fun checkClubSet() {
        val allSuit = Card.Rank.values()
            .map { Card(it, Card.Suit.CLUB) }
            .foldToSet(CardSet())
        assertEquals(0x8888888888888L, allSuit.longValue())
    }

    @Test
    fun checkAceSet() {
        val allRank = Card.Suit.values()
            .map { Card(Card.Rank.ACE, it) }
            .foldToSet(CardSet())
        assertEquals(0xFL, allRank.longValue())
    }

    @Test
    fun checkKingSet() {
        val allRank = Card.Suit.values()
            .map { Card(Card.Rank.KING, it) }
            .foldToSet(CardSet())
        assertEquals(0xF0L, allRank.longValue())
    }

    @Test
    fun checkQueenSet() {
        val allRank = Card.Suit.values()
            .map { Card(Card.Rank.QUEEN, it) }
            .foldToSet(CardSet())
        assertEquals(0xF00L, allRank.longValue())
    }

    @Test
    fun checkJackSet() {
        val allRank = Card.Suit.values()
            .map { Card(Card.Rank.JACK, it) }
            .foldToSet(CardSet())
        assertEquals(0xF000L, allRank.longValue())
    }

    @Test
    fun checkTenSet() {
        val allRank = Card.Suit.values()
            .map { Card(Card.Rank.TEN, it) }
            .foldToSet(CardSet())
        assertEquals(0xF0000L, allRank.longValue())
    }

    @Test
    fun checkNineSet() {
        val allRank = Card.Suit.values()
            .map { Card(Card.Rank.NINE, it) }
            .foldToSet(CardSet())
        assertEquals(0xF00000L, allRank.longValue())
    }

    @Test
    fun checkEightSet() {
        val allRank = Card.Suit.values()
            .map { Card(Card.Rank.EIGHT, it) }
            .foldToSet(CardSet())
        assertEquals(0xF000000L, allRank.longValue())
    }

    @Test
    fun checkSevenSet() {
        val allRank = Card.Suit.values()
            .map { Card(Card.Rank.SEVEN, it) }
            .foldToSet(CardSet())
        assertEquals(0xF0000000L, allRank.longValue())
    }

    @Test
    fun checkSixSet() {
        val allRank = Card.Suit.values()
            .map { Card(Card.Rank.SIX, it) }
            .foldToSet(CardSet())
        assertEquals(0xF00000000L, allRank.longValue())
    }

    @Test
    fun checkFiveSet() {
        val allRank = Card.Suit.values()
            .map { Card(Card.Rank.FIVE, it) }
            .foldToSet(CardSet())
        assertEquals(0xF000000000L, allRank.longValue())
    }

    @Test
    fun checkFourSet() {
        val allRank = Card.Suit.values()
            .map { Card(Card.Rank.FOUR, it) }
            .foldToSet(CardSet())
        assertEquals(0xF0000000000L, allRank.longValue())
    }

    @Test
    fun checkThreeSet() {
        val allRank = Card.Suit.values()
            .map { Card(Card.Rank.THREE, it) }
            .foldToSet(CardSet())
        assertEquals(0xF00000000000L, allRank.longValue())
    }

    @Test
    fun checkDueceSet() {
        val allRank = Card.Suit.values()
            .map { Card(Card.Rank.DUECE, it) }
            .foldToSet(CardSet())
        assertEquals(0xF000000000000L, allRank.longValue())
    }
}


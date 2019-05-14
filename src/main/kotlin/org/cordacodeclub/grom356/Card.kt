/*
 * Copyright 2012 Cameron Zemek <grom358@gmail.com>.
 */
package org.cordacodeclub.grom356;

/**
 * A poker card. Aces are high and suit is not important in ranking.
 *
 * @author Cameron Zemek <grom358@gmail.com>
 */
class Card(val rank: Rank, val suit: Suit) : Comparable<Card> {

    enum class Suit {
        SPADE,
        HEART,
        DIAMOND,
        CLUB;

        companion object {
            @JvmStatic
            val size = Suit.values().size

            @JvmStatic
            fun valueOf(letter: Char) = values().single { it.letter == letter }
        }

        val letter = Character.toLowerCase(name[0])
    }

    enum class Rank(val letter: Char, val value: Int) {
        ACE('A', 14),
        KING('K', 13),
        QUEEN('Q', 12),
        JACK('J', 11),
        TEN('T', 10),
        NINE('9', 9),
        EIGHT('8', 8),
        SEVEN('7', 7),
        SIX('6', 6),
        FIVE('5', 5),
        FOUR('4', 4),
        THREE('3', 3),
        DUECE('2', 2);

        companion object {
            @JvmStatic
            val size = Rank.values().size

            @JvmStatic
            fun valueOf(letter: Char) = values().single { it.letter == letter }

            @JvmStatic
            fun valueOf(value: Int) = values().single { it.value == value }
        }
    }

    companion object {
        val protoDeck = Rank.values().flatMap { rank ->
            Suit.values().map { suit ->
                Card(rank = rank, suit = suit)
            }
        }

        private fun valueOfWithMessage(index: Int, message: String) = when {
            index < 0 || index > protoDeck.size -> throw IllegalArgumentException(message)
            else -> protoDeck.get(index)
        }

        @JvmStatic
        fun valueOf(index: Int) = valueOfWithMessage(
            index = index,
            message = "Invalid card; index=$index"
        )

        @JvmStatic
        fun valueOf(num: Long) = valueOfWithMessage(
            index = when (num) {
                0L -> 0
                else -> java.lang.Long.numberOfTrailingZeros(num)
            },
            message = "Invalid card; num=$num"
        )

        @JvmStatic
        fun valueOf(rank: Rank, suit: Suit) = valueOf(Card(rank, suit).intValue)

        @JvmStatic
        fun valueOf(card: String) = when (card.length) {
            2 -> valueOf(Rank.valueOf(card[0]), Suit.valueOf(card[1]))
            else -> throw IllegalArgumentException("Invalid card format");
        }

        @JvmStatic
        fun newDeck() = protoDeck.toList()
    }

    val rankValue = rank.value

    /**
     * Number between 0 and 51 (inclusive), used as index value
     */
    val intValue = rank.ordinal * Suit.size + suit.ordinal

    /**
     * Return a number that contains 1 bit, used as bitset value
     */
    val longValue = 1L shl intValue

    override fun compareTo(other: Card) = intValue - other.intValue

    override fun toString() = "${rank.letter}${suit.letter}"
}

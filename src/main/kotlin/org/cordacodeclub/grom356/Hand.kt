package org.cordacodeclub.grom356

class Hand private constructor(
    val category: Category,
    val cards: List<Card>,
    val value: Int) : Comparable<Hand> {

    enum class Category {
        HIGH_CARD,
        PAIR,
        TWO_PAIR,
        THREE_OF_A_KIND,
        STRAIGHT,
        FLUSH,
        FULLHOUSE,
        FOUR_OF_A_KIND,
        STRAIGHT_FLUSH
    }

    companion object {
        private val STRAIGHT_FLUSH_MASK = 0x11111L
        private val ACE_LOW_STRAIGHT_FLUSH_MASK = 0x1111000000001L
        private val SUIT_MASK = 0x1111111111111L
        private val RANK_MASK = 0xFL

        private val HIGH_CARD = Category.HIGH_CARD.ordinal shl 24
        private val PAIR = Category.PAIR.ordinal shl 24
        private val TWO_PAIR = Category.TWO_PAIR.ordinal shl 24
        private val THREE_OF_A_KIND = Category.THREE_OF_A_KIND.ordinal shl 24
        private val STRAIGHT = Category.STRAIGHT.ordinal shl 24
        private val FLUSH = Category.FLUSH.ordinal shl 24
        private val FULLHOUSE = Category.FULLHOUSE.ordinal shl 24
        private val FOUR_OF_A_KIND = Category.FOUR_OF_A_KIND.ordinal shl 24
        private val STRAIGHT_FLUSH = Category.STRAIGHT_FLUSH.ordinal shl 24
        private val ACE_LOW_STRAIGHT = 0x5432E

        private fun handValue(category: Category, cardSet: CardSet): Hand {
            return handValue(category, cardSet.toList())
        }

        private fun handValue(category: Category, cardList: List<Card>): Hand {
            var value = category.ordinal shl 4
            var i = 0
            val n = Math.min(5, cardList.size)
            while (i < n) {
                val card = cardList[i]
                value = value shl 4 or card.rankValue
                ++i
            }
            while (i < 5) {
                value = value shl 4
                ++i
            }
            return Hand(category, cardList, value)
        }

        @JvmStatic
        fun eval(cs: CardSet): Hand {
            val value = cs.longValue()
            var test: Long
            var mask: Long

            // Straight flush
            run {
                var i = 0
                val n = Card.Rank.size * Card.Suit.size - 4 * Card.Suit.size
                while (i < n) {
                    mask = STRAIGHT_FLUSH_MASK shl i
                    test = value and mask
                    if (test == mask) {
                        return handValue(Category.STRAIGHT_FLUSH, CardSet(test))
                    }
                    ++i
                }
            }

            // Ace low straight flush
            run {
                var i = 0
                val n = Card.Suit.size
                while (i < n) {
                    mask = ACE_LOW_STRAIGHT_FLUSH_MASK shl i
                    test = value and mask
                    if (test == mask) {
                        val handSet = CardSet(test)
                        val cardList = handSet.toMutableList()
                        cardList.add(cardList.removeAt(0)) // Make the ace low
                        return handValue(Category.STRAIGHT_FLUSH, cardList)
                    }
                    ++i
                }
            }

            var threeOfKind: CardSet? = null
            var topPair: CardSet? = null
            var secondPair: CardSet? = null

            // Four of a Kind
            val spades = value and SUIT_MASK
            val hearts = value shr 1 and SUIT_MASK
            val diamonds = value shr 2 and SUIT_MASK
            val clubs = value shr 3 and SUIT_MASK
            val fourOfKind = spades and hearts and diamonds and clubs
            if (fourOfKind != 0L) {
                mask = RANK_MASK shl java.lang.Long.numberOfTrailingZeros(fourOfKind)
                val kickers = CardSet(value and mask.inv())
                val hand = CardSet(mask).toMutableList()
                hand.add(kickers.toList()[0])
                return handValue(Category.FOUR_OF_A_KIND, hand)
            }

            // Triples & Pairs
            val triples = clubs and diamonds and hearts or
                    (clubs and diamonds and spades) or
                    (clubs and hearts and spades) or
                    (diamonds and hearts and spades)
            var sets = clubs and diamonds or
                    (clubs and hearts) or
                    (clubs and spades) or
                    (diamonds and hearts) or
                    (diamonds and spades) or
                    (hearts and spades)
            if (triples != 0L) {
                mask = RANK_MASK shl java.lang.Long.numberOfTrailingZeros(triples)
                test = value and mask
                threeOfKind = CardSet(test)
                sets = sets and java.lang.Long.lowestOneBit(triples).inv() // Remove triple from sets
            }
            if (sets != 0L) {
                mask = RANK_MASK shl java.lang.Long.numberOfTrailingZeros(sets)
                test = value and mask
                topPair = CardSet(test)
                sets = sets and java.lang.Long.lowestOneBit(sets).inv() // Remove top pair from sets
            }
            if (sets != 0L) {
                mask = RANK_MASK shl java.lang.Long.numberOfTrailingZeros(sets)
                test = value and mask
                secondPair = CardSet(test)
            }

            if (threeOfKind != null && topPair != null) {
                val hand = threeOfKind.toMutableList()
                hand.addAll(topPair.toList())
                return handValue(Category.FULLHOUSE, hand)
            }

            // Search for flush
            run {
                var i = 0
                val n = Card.Suit.size
                while (i < n) {
                    mask = SUIT_MASK shl i
                    test = value and mask
                    val cardCount = java.lang.Long.bitCount(test)
                    if (cardCount >= 5) {
                        return handValue(Category.FLUSH, CardSet(test))
                    }
                    ++i
                }
            }

            // Search for straight
            var straightLength = 0
            var straight: Long = 0
            var i = 0
            val n = Card.Rank.size
            while (i < n) {
                mask = RANK_MASK shl i * Card.Suit.size
                test = value and mask
                if (test != 0L) {
                    straightLength++
                    straight = straight or java.lang.Long.lowestOneBit(test)
                } else {
                    straightLength = 0
                    straight = 0
                }
                if (straightLength == 5) {
                    return handValue(Category.STRAIGHT, CardSet(straight))
                }
                ++i
            }
            // Test for ace low straight
            if (straightLength == 4) {
                test = value and RANK_MASK
                if (test != 0L) {
                    straight = straight or java.lang.Long.lowestOneBit(test)
                    val handSet = CardSet(straight)
                    val cardList = handSet.toMutableList()
                    cardList.add(cardList.removeAt(0)) // Make the ace low
                    return handValue(Category.STRAIGHT, cardList)
                }
            }

            if (threeOfKind != null) {
                val hand = threeOfKind.toMutableList()
                val ks = CardSet(cs)
                ks.subtract(threeOfKind)
                hand.addAll(ks.subList(2))
                return handValue(Category.THREE_OF_A_KIND, hand)
            }

            if (topPair != null && secondPair != null) {
                val hand = topPair.toMutableList()
                hand.addAll(secondPair.toList())
                val ks = CardSet(cs)
                ks.subtract(topPair)
                ks.subtract(secondPair)
                hand.addAll(ks.subList(1))
                return handValue(Category.TWO_PAIR, hand)
            }

            if (topPair != null) {
                val hand = topPair.toMutableList()
                val ks = CardSet(cs)
                ks.subtract(topPair)
                hand.addAll(ks.subList(3))
                return handValue(Category.PAIR, hand)
            }

            // High card
            return handValue(Category.HIGH_CARD, cs.subList(5))
        }

        @JvmStatic
        fun eval(hand: Collection<Card>, board: Collection<Card>): Hand {
            val cs = CardSet()
            cs.addAll(hand)
            cs.addAll(board)
            return eval(cs)
        }

        @JvmStatic
        fun eval(cards: Collection<Card>): Hand {
            return eval(CardSet(cards))
        }

        private fun nextSetBit(num: Long, fromIndex: Int): Int {
            val mask = -0x1L shl fromIndex
            val word = num and mask
            return if (word == 0L) {
                -1
            } else java.lang.Long.numberOfTrailingZeros(word)
        }

        private fun encodeRanks(rankMask: Long, n: Int): Int {
            var value = 0
            var i = nextSetBit(rankMask, 0)
            var c = 0
            while (i >= 0 && c < n) {
                value = value shl 4
                value = value or 14 - (i shr 2)
                i = nextSetBit(rankMask, i + 1)
                c++
            }
            return value
        }

        @JvmStatic
        fun fastEval(cardSet: CardSet): Int {
            val cardMask = cardSet.longValue()
            val spades = cardMask and SUIT_MASK
            val hearts = cardMask shr 1 and SUIT_MASK
            val diamonds = cardMask shr 2 and SUIT_MASK
            val clubs = cardMask shr 3 and SUIT_MASK
            val ranks = spades or hearts or diamonds or clubs

            // Straight flush
            for (i in 0..8) {
                val handMask = STRAIGHT_FLUSH_MASK shl (i shl 2)
                if (spades and handMask == handMask) {
                    return STRAIGHT_FLUSH or encodeRanks(handMask, 5)
                }
                if (hearts and handMask == handMask) {
                    return STRAIGHT_FLUSH or encodeRanks(handMask, 5)
                }
                if (diamonds and handMask == handMask) {
                    return STRAIGHT_FLUSH or encodeRanks(handMask, 5)
                }
                if (clubs and handMask == handMask) {
                    return STRAIGHT_FLUSH or encodeRanks(handMask, 5)
                }
            }

            // Ace low straight flush
            if (spades and ACE_LOW_STRAIGHT_FLUSH_MASK == ACE_LOW_STRAIGHT_FLUSH_MASK) {
                return STRAIGHT_FLUSH or ACE_LOW_STRAIGHT
            }
            if (hearts and ACE_LOW_STRAIGHT_FLUSH_MASK == ACE_LOW_STRAIGHT_FLUSH_MASK) {
                return STRAIGHT_FLUSH or ACE_LOW_STRAIGHT
            }
            if (diamonds and ACE_LOW_STRAIGHT_FLUSH_MASK == ACE_LOW_STRAIGHT_FLUSH_MASK) {
                return STRAIGHT_FLUSH or ACE_LOW_STRAIGHT
            }
            if (clubs and ACE_LOW_STRAIGHT_FLUSH_MASK == ACE_LOW_STRAIGHT_FLUSH_MASK) {
                return STRAIGHT_FLUSH or ACE_LOW_STRAIGHT
            }

            // Four of a kind
            val fourOfAKind = spades and hearts and diamonds and clubs
            if (fourOfAKind != 0L) {
                val kicker = encodeRanks(ranks and fourOfAKind.inv(), 1)
                val fourOfAKindRank = encodeRanks(fourOfAKind, 1)
                return FOUR_OF_A_KIND or
                        (fourOfAKindRank shl 16) or
                        (fourOfAKindRank shl 12) or
                        (fourOfAKindRank shl 8) or
                        (fourOfAKindRank shl 4) or
                        kicker
            }

            // Fullhouse
            val triples = clubs and diamonds and hearts or
                    (clubs and diamonds and spades) or
                    (clubs and hearts and spades) or
                    (diamonds and hearts and spades)
            val triple = java.lang.Long.lowestOneBit(triples)
            val tripleRank = if (triple == 0L) 0 else encodeRanks(triple, 1)
            var sets = clubs and diamonds or
                    (clubs and hearts) or
                    (clubs and spades) or
                    (diamonds and hearts) or
                    (diamonds and spades) or
                    (hearts and spades)
            val setCount = java.lang.Long.bitCount(sets)
            if (triple != 0L && setCount >= 2) {
                val topPairRank = encodeRanks(sets and triple.inv(), 1)
                return FULLHOUSE or
                        (tripleRank shl 16) or
                        (tripleRank shl 12) or
                        (tripleRank shl 8) or
                        (topPairRank shl 4) or
                        topPairRank
            }

            // Flush
            if (java.lang.Long.bitCount(spades) >= 5) {
                return FLUSH or encodeRanks(spades, 5)
            }
            if (java.lang.Long.bitCount(hearts) >= 5) {
                return FLUSH or encodeRanks(hearts, 5)
            }
            if (java.lang.Long.bitCount(diamonds) >= 5) {
                return FLUSH or encodeRanks(diamonds, 5)
            }
            if (java.lang.Long.bitCount(clubs) >= 5) {
                return FLUSH or encodeRanks(clubs, 5)
            }

            // Straight
            for (i in 0..8) {
                val handMask = STRAIGHT_FLUSH_MASK shl (i shl 2)
                if (ranks and handMask == handMask) {
                    return STRAIGHT or encodeRanks(handMask, 5)
                }
            }
            if (ranks and ACE_LOW_STRAIGHT_FLUSH_MASK == ACE_LOW_STRAIGHT_FLUSH_MASK) {
                return STRAIGHT or ACE_LOW_STRAIGHT
            }

            // Three of kind
            if (triple != 0L) {
                val kickers = ranks and triple.inv()
                return THREE_OF_A_KIND or
                        (tripleRank shl 16) or
                        (tripleRank shl 12) or
                        (tripleRank shl 8) or
                        encodeRanks(kickers, 2)
            }

            // Two pair
            if (setCount > 1) {
                val pairs = encodeRanks(sets, 2)
                val topPairRank = pairs shr 4
                val secondPairRank = pairs and 0xF
                val topPair = java.lang.Long.lowestOneBit(sets)
                sets = sets and topPair.inv()
                val secondPair = java.lang.Long.lowestOneBit(sets)
                val kickers = ranks and topPair.inv() and secondPair.inv()
                return TWO_PAIR or
                        (topPairRank shl 16) or
                        (topPairRank shl 12) or
                        (secondPairRank shl 8) or
                        (secondPairRank shl 4) or
                        encodeRanks(kickers, 1)
            }

            // Pair
            if (setCount == 1) {
                val pair = encodeRanks(sets, 1)
                val kickers = ranks and sets.inv()
                return PAIR or (pair shl 16) or (pair shl 12) or encodeRanks(kickers, 3)
            }

            // High Card
            return HIGH_CARD or encodeRanks(ranks, 5)
        }
    }

    override fun compareTo(other: Hand) = other.value - value

    override fun toString() = "$category - $cards ($value)"
}
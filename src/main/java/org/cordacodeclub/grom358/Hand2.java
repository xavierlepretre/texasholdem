/*
 * Copyright 2012 Cameron Zemek <grom358@gmail.com>.
 */
package org.cordacodeclub.grom358;

import org.cordacodeclub.grom356.Card;
import org.cordacodeclub.grom356.CardSet;

import java.util.Collection;
import java.util.List;

/**
 * A poker hand.
 *
 * @author Cameron Zemek <grom358@gmail.com>
 */
public class Hand2 implements Comparable<Hand2> {
    static public enum Category {
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

    private Category category;
    private List<Card> cardList;
    private int handValue;

    private Hand2(Category category, List<Card> cardList, int handValue) {
        this.category = category;
        this.cardList = cardList;
        this.handValue = handValue;
    }

    public Category getCategory() {
        return category;
    }

    public List<Card> getCards() {
        return cardList;
    }

    public int getValue() {
        return handValue;
    }

    @Override
    public int compareTo(Hand2 o) {
        return o.handValue - handValue;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(category.toString());
        sb.append(" - ");
        sb.append(cardList);
        sb.append(" (");
        sb.append(handValue);
        sb.append(')');
        return sb.toString();
    }

    static private final long STRAIGHT_FLUSH_MASK = 0x11111L;
    static private final long ACE_LOW_STRAIGHT_FLUSH_MASK = 0x1111000000001L;
    static private final long SUIT_MASK = 0x1111111111111L;
    static private final long RANK_MASK = 0xFL;

    static private Hand2 handValue(Category category, List<Card> cardList) {
        int value = category.ordinal() << 4;
        int i = 0;
        int n = Math.min(5, cardList.size());
        for (; i < n; ++i) {
            Card card = cardList.get(i);
            value = (value << 4) | card.getRankValue();
        }
        for (; i < 5; ++i) {
            value <<= 4;
        }
        return new Hand2(category, cardList, value);
    }

    static private Hand2 handValue(Category category, CardSet cardSet) {
        return handValue(category, cardSet.toList());
    }

    static public Hand2 eval(CardSet cs) {
        long value = cs.longValue();
        long test, mask;

        // Straight flush
        for (int i = 0, n = (Card.Rank.getSize() * Card.Suit.getSize()) - (4 * Card.Suit.getSize());
             i < n; ++i) {
            mask = STRAIGHT_FLUSH_MASK << i;
            test = value & mask;
            if (test == mask) {
                return handValue(Category.STRAIGHT_FLUSH, new CardSet(test));
            }
        }

        // Ace low straight flush
        for (int i = 0, n = Card.Suit.getSize(); i < n; ++i) {
            mask = ACE_LOW_STRAIGHT_FLUSH_MASK << i;
            test = value & mask;
            if (test == mask) {
                CardSet handSet = new CardSet(test);
                List<Card> cardList = handSet.toList();
                cardList.add(cardList.remove(0)); // Make the ace low
                return handValue(Category.STRAIGHT_FLUSH, cardList);
            }
        }

        CardSet threeOfKind = null;
        CardSet topPair = null;
        CardSet secondPair = null;

        // Four of a Kind
        long spades = value & SUIT_MASK;
        long hearts = (value >> 1) & SUIT_MASK;
        long diamonds = (value >> 2) & SUIT_MASK;
        long clubs = (value >> 3) & SUIT_MASK;
        long fourOfKind = (spades & hearts & diamonds & clubs);
        if (fourOfKind != 0) {
            mask = RANK_MASK << Long.numberOfTrailingZeros(fourOfKind);
            CardSet kickers = new CardSet(value & ~mask);
            List<Card> hand = (new CardSet(mask)).toList();
            hand.add(kickers.toList().get(0));
            return handValue(Category.FOUR_OF_A_KIND, hand);
        }

        // Triples & Pairs
        long triples = (clubs & diamonds & hearts) |
                (clubs & diamonds & spades) |
                (clubs & hearts & spades) |
                (diamonds & hearts & spades);
        long sets = (clubs & diamonds) |
                (clubs & hearts) |
                (clubs & spades) |
                (diamonds & hearts) |
                (diamonds & spades) |
                (hearts & spades);
        if (triples != 0) {
            mask = RANK_MASK << Long.numberOfTrailingZeros(triples);
            test = value & mask;
            threeOfKind = new CardSet(test);
            sets &= ~Long.lowestOneBit(triples); // Remove triple from sets
        }
        if (sets != 0) {
            mask = RANK_MASK << Long.numberOfTrailingZeros(sets);
            test = value & mask;
            topPair = new CardSet(test);
            sets &= ~Long.lowestOneBit(sets); // Remove top pair from sets
        }
        if (sets != 0) {
            mask = RANK_MASK << Long.numberOfTrailingZeros(sets);
            test = value & mask;
            secondPair = new CardSet(test);
        }

        if (threeOfKind != null && topPair != null) {
            List<Card> hand = threeOfKind.toList();
            hand.addAll(topPair.toList());
            return handValue(Category.FULLHOUSE, hand);
        }

        // Search for flush
        for (int i = 0, n = Card.Suit.getSize(); i < n; ++i) {
            mask = SUIT_MASK << i;
            test = value & mask;
            int cardCount = Long.bitCount(test);
            if (cardCount >= 5) {
                return handValue(Category.FLUSH, new CardSet(test));
            }
        }

        // Search for straight
        int straightLength = 0;
        long straight = 0;
        for (int i = 0, n = Card.Rank.getSize(); i < n; ++i) {
            mask = RANK_MASK << (i * Card.Suit.getSize());
            test = value & mask;
            if (test != 0) {
                straightLength++;
                straight |= Long.lowestOneBit(test);
            } else {
                straightLength = 0;
                straight = 0;
            }
            if (straightLength == 5) {
                return handValue(Category.STRAIGHT, new CardSet(straight));
            }
        }
        // Test for ace low straight
        if (straightLength == 4) {
            test = value & RANK_MASK;
            if (test != 0) {
                straight |= Long.lowestOneBit(test);
                CardSet handSet = new CardSet(straight);
                List<Card> cardList = handSet.toList();
                cardList.add(cardList.remove(0)); // Make the ace low
                return handValue(Category.STRAIGHT, cardList);
            }
        }

        if (threeOfKind != null) {
            List<Card> hand = threeOfKind.toList();
            CardSet ks = new CardSet(cs);
            ks.subtract(threeOfKind);
            hand.addAll(ks.subList(2));
            return handValue(Category.THREE_OF_A_KIND, hand);
        }

        if (topPair != null && secondPair != null) {
            List<Card> hand = topPair.toList();
            hand.addAll(secondPair.toList());
            CardSet ks = new CardSet(cs);
            ks.subtract(topPair);
            ks.subtract(secondPair);
            hand.addAll(ks.subList(1));
            return handValue(Category.TWO_PAIR, hand);
        }

        if (topPair != null) {
            List<Card> hand = topPair.toList();
            CardSet ks = new CardSet(cs);
            ks.subtract(topPair);
            hand.addAll(ks.subList(3));
            return handValue(Category.PAIR, hand);
        }

        // High card
        return handValue(Category.HIGH_CARD, cs.subList(5));
    }

    static public Hand2 eval(Collection<Card> hand, Collection<Card> board) {
        CardSet cs = new CardSet();
        cs.addAll(hand);
        cs.addAll(board);
        return eval(cs);
    }

    static public Hand2 eval(Collection<Card> cards) {
        return eval(new CardSet(cards));
    }

    static private final int HIGH_CARD = Category.HIGH_CARD.ordinal() << 24;
    static private final int PAIR = Category.PAIR.ordinal() << 24;
    static private final int TWO_PAIR = Category.TWO_PAIR.ordinal() << 24;
    static private final int THREE_OF_A_KIND = Category.THREE_OF_A_KIND.ordinal() << 24;
    static private final int STRAIGHT = Category.STRAIGHT.ordinal() << 24;
    static private final int FLUSH = Category.FLUSH.ordinal() << 24;
    static private final int FULLHOUSE = Category.FULLHOUSE.ordinal() << 24;
    static private final int FOUR_OF_A_KIND = Category.FOUR_OF_A_KIND.ordinal() << 24;
    static private final int STRAIGHT_FLUSH = Category.STRAIGHT_FLUSH.ordinal() << 24;
    static private final int ACE_LOW_STRAIGHT = 0x5432E;

    static private int nextSetBit(long num, int fromIndex) {
        long mask = 0xFFFFFFFFFFFFFFFFL << fromIndex;
        long word = num & mask;
        if (word == 0) {
            return -1;
        }
        return Long.numberOfTrailingZeros(word);
    }

    static private int encodeRanks(long rankMask, int n) {
        int value = 0;
        for (int i = nextSetBit(rankMask, 0), c = 0; i >= 0 && c < n; i = nextSetBit(rankMask, i + 1), c++) {
            value <<= 4;
            value |= 14 - (i >> 2);
        }
        return value;
    }

    static public int fastEval(CardSet cardSet) {
        long cardMask = cardSet.longValue();
        long spades = cardMask & SUIT_MASK;
        long hearts = (cardMask >> 1) & SUIT_MASK;
        long diamonds = (cardMask >> 2) & SUIT_MASK;
        long clubs = (cardMask >> 3) & SUIT_MASK;
        long ranks = (spades | hearts | diamonds | clubs);

        // Straight flush
        for (int i = 0; i <= 8; ++i) {
            long handMask = (STRAIGHT_FLUSH_MASK << (i << 2));
            if ((spades & handMask) == handMask) {
                return STRAIGHT_FLUSH | encodeRanks(handMask, 5);
            }
            if ((hearts & handMask) == handMask) {
                return STRAIGHT_FLUSH | encodeRanks(handMask, 5);
            }
            if ((diamonds & handMask) == handMask) {
                return STRAIGHT_FLUSH | encodeRanks(handMask, 5);
            }
            if ((clubs & handMask) == handMask) {
                return STRAIGHT_FLUSH | encodeRanks(handMask, 5);
            }
        }

        // Ace low straight flush
        if ((spades & ACE_LOW_STRAIGHT_FLUSH_MASK) == ACE_LOW_STRAIGHT_FLUSH_MASK) {
            return STRAIGHT_FLUSH | ACE_LOW_STRAIGHT;
        }
        if ((hearts & ACE_LOW_STRAIGHT_FLUSH_MASK) == ACE_LOW_STRAIGHT_FLUSH_MASK) {
            return STRAIGHT_FLUSH | ACE_LOW_STRAIGHT;
        }
        if ((diamonds & ACE_LOW_STRAIGHT_FLUSH_MASK) == ACE_LOW_STRAIGHT_FLUSH_MASK) {
            return STRAIGHT_FLUSH | ACE_LOW_STRAIGHT;
        }
        if ((clubs & ACE_LOW_STRAIGHT_FLUSH_MASK) == ACE_LOW_STRAIGHT_FLUSH_MASK) {
            return STRAIGHT_FLUSH | ACE_LOW_STRAIGHT;
        }

        // Four of a kind
        long fourOfAKind = spades & hearts & diamonds & clubs;
        if (fourOfAKind != 0) {
            int kicker = encodeRanks(ranks & ~fourOfAKind, 1);
            int fourOfAKindRank = encodeRanks(fourOfAKind, 1);
            return FOUR_OF_A_KIND |
                    (fourOfAKindRank << 16) |
                    (fourOfAKindRank << 12) |
                    (fourOfAKindRank << 8) |
                    (fourOfAKindRank << 4) |
                    kicker;
        }

        // Fullhouse
        long triples = (clubs & diamonds & hearts) |
                (clubs & diamonds & spades) |
                (clubs & hearts & spades) |
                (diamonds & hearts & spades);
        long triple = Long.lowestOneBit(triples);
        int tripleRank = triple == 0 ? 0 : encodeRanks(triple, 1);
        long sets = (clubs & diamonds) |
                (clubs & hearts) |
                (clubs & spades) |
                (diamonds & hearts) |
                (diamonds & spades) |
                (hearts & spades);
        int setCount = Long.bitCount(sets);
        if (triple != 0 && setCount >= 2) {
            int topPairRank = encodeRanks(sets & ~triple, 1);
            return FULLHOUSE |
                    (tripleRank << 16) |
                    (tripleRank << 12) |
                    (tripleRank << 8) |
                    (topPairRank << 4) |
                    topPairRank;
        }

        // Flush
        if (Long.bitCount(spades) >= 5) {
            return FLUSH | encodeRanks(spades, 5);
        }
        if (Long.bitCount(hearts) >= 5) {
            return FLUSH | encodeRanks(hearts, 5);
        }
        if (Long.bitCount(diamonds) >= 5) {
            return FLUSH | encodeRanks(diamonds, 5);
        }
        if (Long.bitCount(clubs) >= 5) {
            return FLUSH | encodeRanks(clubs, 5);
        }

        // Straight
        for (int i = 0; i <= 8; ++i) {
            long handMask = (STRAIGHT_FLUSH_MASK << (i << 2));
            if ((ranks & handMask) == handMask) {
                return STRAIGHT | encodeRanks(handMask, 5);
            }
        }
        if ((ranks & ACE_LOW_STRAIGHT_FLUSH_MASK) == ACE_LOW_STRAIGHT_FLUSH_MASK) {
            return STRAIGHT | ACE_LOW_STRAIGHT;
        }

        // Three of kind
        if (triple != 0) {
            long kickers = ranks & ~triple;
            return THREE_OF_A_KIND |
                    (tripleRank << 16) |
                    (tripleRank << 12) |
                    (tripleRank << 8) |
                    encodeRanks(kickers, 2);
        }

        // Two pair
        if (setCount > 1) {
            int pairs = encodeRanks(sets, 2);
            int topPairRank = pairs >> 4;
            int secondPairRank = pairs & 0xF;
            long topPair = Long.lowestOneBit(sets);
            sets &= ~topPair;
            long secondPair = Long.lowestOneBit(sets);
            long kickers = ranks & ~topPair & ~secondPair;
            return TWO_PAIR |
                    (topPairRank << 16) |
                    (topPairRank << 12) |
                    (secondPairRank << 8) |
                    (secondPairRank << 4) |
                    encodeRanks(kickers, 1);
        }

        // Pair
        if (setCount == 1) {
            int pair = encodeRanks(sets, 1);
            long kickers = ranks & ~sets;
            return PAIR | (pair << 16) | (pair << 12) | encodeRanks(kickers, 3);
        }

        // High Card
        return HIGH_CARD | encodeRanks(ranks, 5);
    }
}

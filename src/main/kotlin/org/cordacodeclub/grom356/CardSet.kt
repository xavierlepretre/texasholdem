/*
 * Copyright 2012 Cameron Zemek <grom358@gmail.com>.
 */
package org.cordacodeclub.grom356

/**
 * A set of poker cards
 *
 * @author Cameron Zemek <grom358@gmail.com>
 */
class CardSet : Set<Card> {
    constructor() {
        bitset = 0
    }

    constructor(bitset: Long) {
        this.bitset = bitset
    }

    constructor(cs: CardSet) {
        bitset = cs.bitset
    }

    constructor(cards: Collection<Card>) {
        bitset = 0
        addAll(cards)
    }

    var bitset: Long
        private set

    @Deprecated("Use bitset")
    fun longValue() = bitset

    fun add(card: Card) {
        bitset = bitset or card.longValue
    }

    fun addAll(cards: Collection<Card>) = cards.forEach { add(it) }

    fun intersect(cardSet: CardSet) {
        bitset = bitset and cardSet.bitset
    }

    fun union(cardSet: CardSet) {
        bitset = bitset or cardSet.bitset
    }

    fun subtract(cardSet: CardSet) {
        bitset = bitset and cardSet.bitset.inv()
    }

    internal fun nextSetBit(fromIndex: Int): Int {
        val mask = -0x1L shl fromIndex
        val word = bitset and mask
        return when (word) {
            0L -> -1
            else -> java.lang.Long.numberOfTrailingZeros(word)
        }
    }

    override val size: Int
        get() = java.lang.Long.bitCount(bitset)

    override fun contains(element: Card) = bitset and element.longValue != 0L
    override fun containsAll(elements: Collection<Card>) = elements.all { contains(it) }
    override fun isEmpty() = size == 0
    override fun iterator(): Iterator<Card> = CardIterator(0)

    fun toList() = subList(size)

    fun subList(max: Int) = CardIterator(0, max).asSequence()
        .toList()

    override fun toString() = CardIterator(0).asSequence()
        .joinTo(buffer = StringBuilder(), prefix = "[", separator = ",", postfix = "]")
        .toString()

    inner class CardIterator(initial: Int, private val maxSize: Int = -1) : Iterator<Card> {

        init {
            if (initial < 0) throw IllegalArgumentException("initial is $initial, it cannot be < 0")
        }

        private var nextIndex = nextSetBit(initial)
        private var currentSize: Int = 0

        override fun hasNext() = nextIndex >= 0 && (maxSize < 0 || currentSize < maxSize)
        override fun next() = nextIndex
            .also {
                nextIndex = nextSetBit(it + 1)
                currentSize++
            }
            .let { Card.valueOf(it) }
    }
}
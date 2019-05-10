/*
 * Copyright 2012 Cameron Zemek <grom358@gmail.com>.
 */
package org.cordacodeclub.grom356;

/**
 * Pocket cards in Texas Holdem
 *
 * @author Cameron Zemek <grom358@gmail.com>
 */
class Pocket {

    val first: Card
    val second: Card

    constructor(first: Card, second: Card) {
        if (first.rankValue < second.rankValue) {
            this.first = second;
            this.second = first;
        } else {
            this.first = first;
            this.second = second;
        }
    }

    constructor(cards: Collection<Card>) {
        if (cards.size < 2) {
            throw IllegalArgumentException("Cards is too small to create pocket with");
        }
        val it = cards.iterator();
        val first = it.next();
        val second = it.next();
        if (first.rankValue < second.rankValue) {
            this.first = second;
            this.second = first;
        } else {
            this.first = first;
            this.second = second;
        }
    }

    fun isPair() = first.rank == second.rank
    fun isSuited() = first.suit == second.suit
    fun getGap() = first.rank.value - second.rank.value;
    fun isConnected() = getGap() == 1

    fun toList() = ArrayList<Card>().also {
        it.add(first)
        it.add(second)
    }

    override fun toString() =
        with(StringBuilder()) {
            append(first.rank.letter)
            append(second.rank.letter)
            if (first.suit == second.suit) {
                append('s');
            }
            this
        }.toString()
}

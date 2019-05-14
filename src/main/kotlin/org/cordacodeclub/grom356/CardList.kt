/*
 * Copyright 2012 Cameron Zemek <grom358@gmail.com>.
 */
package org.cordacodeclub.grom356

import java.util.regex.Pattern

/**
 * A list of poker cards
 *
 * @author Cameron Zemek <grom358@gmail.com>
 */
class CardList {

    companion object {

        fun Sequence<Card>.toString() =
            joinTo(buffer = StringBuilder(), prefix = "[", separator = ",", postfix = "]")
                .toString()

        private val listPattern = Pattern.compile("[0-9TJQKA][cdhs]")

        @JvmStatic
        fun valueOf(str: String): List<Card> {
            val matcher = listPattern.matcher(str)
            val cardList = mutableListOf<Card>()
            while (matcher.find()) {
                val group = matcher.group()
                val card = Card.valueOf(group)
                cardList.add(card)
            }
            return cardList
        }

    }
}
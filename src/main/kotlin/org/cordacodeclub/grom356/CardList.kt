/*
 * Copyright 2012 Cameron Zemek <grom358@gmail.com>.
 */
package org.cordacodeclub.grom356

/**
 * A list of poker cards
 *
 * @author Cameron Zemek <grom358@gmail.com>
 */
class CardList {

    companion object {

        fun Iterator<Card>.toString() = with(StringBuilder()) {
            append('[')
            if (hasNext()) {
                append("${next()}")
                forEachRemaining { append(",$it") }
            }
            append(']')
            toString()
        }

        private val listPattern = Regex.fromLiteral("[0-9TJQKA][cdhs]")

        fun valueOf(str: String) = listPattern.findAll(str).map {
            Card.valueOf(it.groupValues[0])
        }.toList()
    }
}
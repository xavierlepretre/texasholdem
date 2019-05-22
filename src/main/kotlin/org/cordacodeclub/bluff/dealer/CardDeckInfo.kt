package org.cordacodeclub.bluff.dealer

import net.corda.core.contracts.requireThat
import net.corda.core.crypto.SecureHash
import org.cordacodeclub.grom356.Card
import org.cordacodeclub.grom356.CardList
import java.util.regex.Pattern

data class CardDeckInfo(
    val id: Long,
    val cards: List<Card>,
    val salts: List<String>,
    val rootHash: SecureHash
) {

    constructor(id: Long, cards: String, salts: String, rootHash: ByteArray) : this(
        id,
        CardList.valueOf(cards),
        valueOf(salts),
        SecureHash.Companion.sha256(rootHash)
    )

    init {
        requireThat {
            "There should be exactly 52 cards" using (cards.size == DECK_SIZE)
            "There should be exactly 52 salts" using (salts.size == DECK_SIZE)
        }
    }

    companion object {

        const val DECK_SIZE = 52

        fun toString(salts: Sequence<String>) =
            salts.joinTo(buffer = StringBuilder(), prefix = "[", separator = ",", postfix = "]")
                .toString()

        private val listPattern = Pattern.compile("[0-9a-zA-Z]{50}")

        @JvmStatic
        fun valueOf(str: String): List<String> {
            val matcher = listPattern.matcher(str)
            val cardList = mutableListOf<String>()
            while (matcher.find()) {
                cardList.add(matcher.group()!!)
            }
            return cardList
        }
    }
}
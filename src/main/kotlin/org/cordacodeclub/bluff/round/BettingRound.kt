package org.cordacodeclub.bluff.round

import net.corda.core.serialization.CordaSerializable
import org.cordacodeclub.bluff.dealer.HashedCardDeckInfo

@CordaSerializable
/**
 * @param isPlay Whether the round accepts multiple transactions.
 */
enum class BettingRound(val playerCardsCount: Int, val communityCardsCount: Int, val isPlay: Boolean) {

    BLIND_BET_1(0, 0, false), // First blind bet setup
    BLIND_BET_2(0, 0, false), // Second blind bet setup
    PRE_FLOP(2, 0, true), // First round of betting with player cards
    FLOP(2, 3, true), // Three community cards placed in the middle face up
    TURN(2, 4, true), // Fourth community card revealed
    RIVER(2, 5, true), // Fifth community card revealed - final round
    END(2, 5, false); // Evaluation round to select the winner

    init {
        require(playerCardsCount <= HashedCardDeckInfo.CARDS_PER_PLAYER) {
            "You cannot set a higher count of player cards than the maximum"
        }
        require(communityCardsCount <= HashedCardDeckInfo.COMMUNITY_CARDS_COUNT) {
            "You cannot set a higher count of community cards than the maximum"
        }
    }

    //To return next element
    fun next(): BettingRound {
        return (ordinal + 1).let { next ->
            require(next < values().size) { "The last one has no next" }
            values()[next]
        }
    }
}
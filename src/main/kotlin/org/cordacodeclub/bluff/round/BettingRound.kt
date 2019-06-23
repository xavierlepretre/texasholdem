package org.cordacodeclub.bluff.round

import net.corda.core.serialization.CordaSerializable
import org.cordacodeclub.bluff.dealer.HashedCardDeckInfo

@CordaSerializable
enum class BettingRound(val communityCardsCount: Int) {

    BLIND_BET_1(0), // First blind bet setup
    BLIND_BET_2(0), // Second blind bet setup
    PRE_FLOP(0), // First round of betting with player cards
    FLOP(3), // Three community cards placed in the middle face up
    TURN(4), // Fourth community card revealed
    RIVER(5), // Fifth community card revealed - final round
    END(5); // Evaluation round to select the winner

    init {
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
package org.cordacodeclub.bluff.state

import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.LinearState
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.AbstractParty
import net.corda.core.serialization.CordaSerializable
import org.cordacodeclub.bluff.contract.GameContract

/**
 * TODO make it a [LinearState]
 */
@BelongsToContract(GameContract::class)
data class GameState(
    val players: List<ActivePlayer>,
    // We anonymise cards like that. The list is 52 long.
    val hashedCards: List<SecureHash>,
    // At some point the cards that can be disclosed will replace the nulls. The list is 52 long.
    val cards: List<AssignedCard?>,
    val bettingRound: BettingRound,
    // The index of the player to bet last in the current round.
    val lastBettor: Int,
    override val participants: List<AbstractParty>
) : ContractState {

    init {
//        require(hashedCards.foldIndexed(true) { index, allOk, hash ->
//            allOk && (cards[index] == null || cards[index]!!.hash == hash)
//        }) { "Non null cards need to be in the hashed list" }
    }
}

@CordaSerializable
enum class BettingRound(val communityCardsCount: Int) {
    BLIND_BET(0), // Initial blind bet setup
    PRE_FLOP(0), // First round of betting with player cards
    FLOP(3), // Three community cards placed in the middle face up
    TURN(4), // Fourth community card revealed
    RIVER(5), // Fifth community card revealed - final round
    END(5); // Evaluation round to select the winner

    //To return next element
    fun next(): BettingRound {
        return (ordinal + 1).let { next ->
            require(next < values().size) { "The last one has no next" }
            values()[next]
        }
    }
}


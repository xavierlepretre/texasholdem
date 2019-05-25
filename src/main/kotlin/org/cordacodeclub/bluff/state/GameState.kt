package org.cordacodeclub.bluff.state

import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.requireThat
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.AbstractParty
import net.corda.core.serialization.CordaSerializable
import org.cordacodeclub.bluff.contract.GameContract

@BelongsToContract(GameContract::class)
data class GameState(
    // We anonymise cards like that
    val hashedCards: List<SecureHash>,
    // At some point the cards that can be disclosed will replace the nulls.
    val cards: List<AssignedCard?>,
    val bettingRound: BettingRound,
    val lastBettor: Int,
    override val participants: List<AbstractParty>
) : ContractState {

    init {
        requireThat {
            "Non null cards need to be in the hashed list" using
                    (hashedCards.foldIndexed(true) { index, accum, hash ->
                        accum && (cards[index] == null || cards[index]!!.hash == hash)
                    })
        }
    }
}

@CordaSerializable
enum class BettingRound {
    BLIND_BET, //Initial Blind bet setup
    PRE_FLOP,  //First round of betting
    FLOP,      //Three community cards places in the middle face up
    TURN,      //Fourth community card revealed
    RIVER;    //Fifth community card reveled - final round
    private val array = values()
    //To return next element
    fun next() : BettingRound {
        return array[(this.ordinal + 1) % array.size]
    }
}


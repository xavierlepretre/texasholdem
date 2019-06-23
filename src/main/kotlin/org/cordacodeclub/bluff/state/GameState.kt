package org.cordacodeclub.bluff.state

import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.LinearState
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.AbstractParty
import org.cordacodeclub.bluff.contract.GameContract
import org.cordacodeclub.bluff.round.BettingRound

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


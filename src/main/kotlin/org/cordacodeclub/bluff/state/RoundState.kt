package org.cordacodeclub.bluff.state

import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import org.cordacodeclub.bluff.contract.OneStepContract
import org.cordacodeclub.bluff.player.PlayerAction
import org.cordacodeclub.bluff.round.BettingRound

@CordaSerializable
@BelongsToContract(OneStepContract::class)
data class RoundState(
    val minter: Party,
    val dealer: Party,
    val deckRootHash: SecureHash,
    val roundType: BettingRound,
    val currentPlayerIndex: Int,
    val players: List<PlayedAction>
) : ContractState {

    init {
        require(0 <= currentPlayerIndex) { "The currentPlayerIndex should be positive" }
        require(currentPlayerIndex < players.size) { "The currentPlayerIndex should be less than players size" }
        require(MIN_PLAYER_COUNT <= players.size) { "There should be at least $MIN_PLAYER_COUNT players" }
        require((players.map { it.player }.toSet().size == players.size)) { "There should be no duplicate player" }
        require(!players.all { it.action == PlayerAction.Fold }) { "At least 1 player must not be folded" }
        // TODO there can be only one block of Missing actions.
    }

    companion object {
        val MIN_PLAYER_COUNT = 3;
    }

    override val participants: List<AbstractParty>
        // TODO we could decide that folded players are no longer informed on the progress of the game
        get() = players.map { it.player }.plus(dealer)

    val currentPlayer = players[currentPlayerIndex].player
    val nextActivePlayerIndex by lazy {
        var i = currentPlayerIndex
        do i = (i + 1) % players.size
        while (players[i].action == PlayerAction.Fold)
        i
    }
    val nextActivePlayer by lazy { players[nextActivePlayerIndex].player }
    val activePlayerCount by lazy { players.filter { it.action != PlayerAction.Fold }.size }
    val isRoundDone by lazy {
        activePlayerCount == 1 ||
                roundType == BettingRound.BLIND_BET_1 ||
                roundType == BettingRound.BLIND_BET_2 ||
                players.all { it.action == PlayerAction.Call || it.action == PlayerAction.Fold }
    }
    val nextRoundTypeOrNull by lazy {
        if (roundType == BettingRound.END) null
        else if (isRoundDone) roundType.next()
        else roundType
    }
}
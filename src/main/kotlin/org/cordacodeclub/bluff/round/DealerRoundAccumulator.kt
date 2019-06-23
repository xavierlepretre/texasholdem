package org.cordacodeclub.bluff.round

import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.requireThat
import net.corda.core.identity.Party
import net.corda.core.internal.toMultiMap
import net.corda.core.transactions.SignedTransaction
import org.cordacodeclub.bluff.state.ActivePlayer
import org.cordacodeclub.bluff.state.TokenState

// This object is passed around after each player has acted
class DealerRoundAccumulator(
    val round: BettingRound,
    val minter: Party,
    // Keeps track of which player has folded
    val players: List<ActivePlayer>,
    // The player that has to play now
    val currentPlayerIndex: Int,
    // They come from a previous transaction
    val committedPotSums: Map<Party, Long>,
    // They are being added to after each player
    val newBets: Map<Party, List<StateAndRef<TokenState>>>,
    val newTransactions: Set<SignedTransaction>,
    val lastRaiseIndex: Int,
    // When the previous player raised, this gets reset to 0
    val playerCountSinceLastRaise: Int
) {
    init {
        requireThat {
            "Current player must be active" using (!players[currentPlayerIndex].folded)
            "All bettors must be in the list of players"
                .using(
                    players.map { it.party }.containsAll(committedPotSums.keys.plus(newBets.keys))
                )
            committedPotSums.forEach {
                "Pot sum for ${it.key} must be positive" using (it.value >= 0)
            }
            "We need at least existing pot sums" using (committedPotSums.values.sum() > 0)
            "All transactions must be relevant to the states and vice versa" using
                    (newBets.flatMap { entry -> entry.value.map { it.ref.txhash } }.toSet()
                            == newTransactions.map { it.id }.toSet())
            "lastRaiseIndex must be positive, not $lastRaiseIndex" using (lastRaiseIndex >= 0)
            "playerCountSinceLastRaise myst be positive, not $playerCountSinceLastRaise"
                .using(playerCountSinceLastRaise >= 0)
        }
    }

    val currentPlayer = players[currentPlayerIndex].party
    val currentPlayerSum = (newBets[currentPlayer] ?: listOf())
        .map { it.state.data.amount }
        .sum() + (committedPotSums[currentPlayer] ?: 0)
    val currentLevel = newBets.map { entry ->
        entry.key to entry.value.map { it.state.data.amount }.sum()
    }.plus(committedPotSums.toList())
        .toMultiMap()
        .mapValues { it.value.sum() }
        .values
        .max()!!
    val nextActivePlayerIndex = currentPlayerIndex.let {
        var i = it
        do i = (i + 1) % players.size
        while (players[i].folded)
        i
    }
    val activePlayerCount = players.filter { !it.folded }.size
    val isRoundDone = activePlayerCount == 1 || activePlayerCount == playerCountSinceLastRaise

    fun stepForwardWhenCurrentPlayerSent(response: CallOrRaiseResponse): DealerRoundAccumulator {
        requireThat {
            "We cannot move forward if the round is done" using (!isRoundDone)
        }
        // We should have received from the expected minter
        val isCorrectMinter = response.moreBets.map { it.state.data.minter }.toSet().let { minters ->
            minters.size <= 1 && minters.singleOrNull().let { it == null || it == minter }
        }
        // We should have received from the expected player
        val isCorrectOwner = response.moreBets.map { it.state.data.owner }.toSet().let { owners ->
            owners.size <= 1 && owners.singleOrNull().let { it == null || it == currentPlayer }
        }
        // We should have received enough, if not folded
        val newSum = currentPlayerSum + response.moreBets.map { it.state.data.amount }.sum()
        val isAtLeastCall = newSum >= currentLevel
        val isRaise = newSum > currentLevel

        // We punish with a fold a player that sent wrong info
        val isFolded = response.isFold || !isCorrectMinter || !isCorrectOwner || !isAtLeastCall
        val updatedPlayers = players.mapIndexed { index, player ->
            if (index != currentPlayerIndex) player
            else player.copy(folded = isFolded)
        }

        val updatedNewBets =
            if (isFolded) newBets
            else newBets.toList().plus(currentPlayer to response.moreBets)
                .toMultiMap()
                .mapValues { it.value.flatten() }
        val updatedNewTransactions =
            if (isFolded) newTransactions
            else newTransactions.plus(response.transactions)

        val updatedLastRaiseIndex = if (isRaise) currentPlayerIndex else lastRaiseIndex
        val updatedPlayerCountSinceLastRaise =
            when {
                isRaise -> 0
                isFolded -> playerCountSinceLastRaise
                else -> playerCountSinceLastRaise + 1
            }
        return DealerRoundAccumulator(
            round = round,
            minter = minter,
            players = updatedPlayers,
            currentPlayerIndex = nextActivePlayerIndex,
            committedPotSums = committedPotSums,
            newBets = updatedNewBets,
            newTransactions = updatedNewTransactions,
            lastRaiseIndex = updatedLastRaiseIndex,
            playerCountSinceLastRaise = updatedPlayerCountSinceLastRaise
        )
    }
}

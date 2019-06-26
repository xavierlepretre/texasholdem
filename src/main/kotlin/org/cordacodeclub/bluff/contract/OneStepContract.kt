package org.cordacodeclub.bluff.contract

import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.requireSingleCommand
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.LedgerTransaction
import org.cordacodeclub.bluff.player.PlayerAction
import org.cordacodeclub.bluff.round.BettingRound
import org.cordacodeclub.bluff.state.RoundState
import org.cordacodeclub.bluff.state.TokenState
import org.cordacodeclub.bluff.state.foldedParties
import org.cordacodeclub.bluff.state.mapPartyToSum

class OneStepContract : Contract {
    companion object {
        val ID = "org.cordacodeclub.bluff.contract.OneStepContract"
    }

    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Commands>()
        val inputRounds = tx.inputsOfType<RoundState>()
        val outputRounds = tx.outputsOfType<RoundState>()
        val inputTokens = tx.inputsOfType<TokenState>().filter { !it.isPot }.mapPartyToSum()
        val inputPots = tx.inputsOfType<TokenState>().filter { it.isPot }.mapPartyToSum()
        val outputPots = tx.outputsOfType<TokenState>().filter { it.isPot }.mapPartyToSum()

        when (command.value) {
            is Commands.BetBlind1 -> requireThat {
                "There should be no input round state" using (inputRounds.isEmpty())
                "There should be a single output round state" using (outputRounds.size == 1)
                val outputRound = outputRounds.single()
                "The round bet status should be ${BettingRound.BLIND_BET_1}, but it was ${outputRound.roundType}" using
                        (outputRound.roundType == BettingRound.BLIND_BET_1)
                "Only the player should bet tokens" using (inputTokens.keys == setOf(outputRound.currentPlayer))
                "The currentPlayerIndex should have a raise action" using
                        (outputRound.players[outputRound.currentPlayerIndex].action == PlayerAction.Raise)
                "The other players should have a missing action" using (outputRound.players.all {
                    it.player == outputRound.currentPlayer || it.action == PlayerAction.Missing
                })
                "There should be no input pot tokens" using (inputPots.isEmpty())
                "There should be output pot tokens for the player" using (outputPots[outputRound.currentPlayer].let {
                    it != null && it > 0L
                })
                "There should be no other output pot tokens" using (outputPots.size == 1)
                "The dealer should sign off the deckRootHash" using
                        (command.signers.contains(outputRound.dealer.owningKey))
                "The currentPlayer should sign off the played action" using
                        (command.signers.contains(outputRound.currentPlayer.owningKey))
            }

            is Commands.BetBlind2 -> requireThat {
                "There should be one input round state" using (inputRounds.size == 1)
                "There should be one output round state" using (outputRounds.size == 1)
                val inputRound = inputRounds.single()
                val outputRound = outputRounds.single()
                areConstantsConserved(inputRound, outputRound)
                isProgressionValid(inputRound, outputRound)
                "The previous round bet status should be ${BettingRound.BLIND_BET_1}, but it is ${inputRound.roundType}" using
                        (inputRound.roundType == BettingRound.BLIND_BET_1)
                "The round bet status should be ${BettingRound.BLIND_BET_2}, but it is ${outputRound.roundType}" using
                        (outputRound.roundType == BettingRound.BLIND_BET_2)
                "Only the player should bet tokens" using (inputTokens.keys == setOf(outputRound.currentPlayer))
                "The other players should have a missing action" using (outputRound.players.all {
                    it.player == inputRound.currentPlayer ||
                            it.player == outputRound.currentPlayer ||
                            it.action == PlayerAction.Missing
                })
                val firstPlayerSum = inputPots[inputRound.currentPlayer]
                    ?: throw IllegalArgumentException("The first player should have bet a sum")
                // TODO do we allow a fold here? What would this mean?
                val secondPlayerSum = outputPots[outputRound.currentPlayer]
                    ?: throw IllegalArgumentException("The second player should have bet a sum")
                // TODO what are the rules of the second blind bet?
                "There should be no other output pot tokens" using (outputPots.keys == setOf(
                    inputRound.currentPlayer,
                    outputRound.currentPlayer
                ))
                "The second blind bet should be at or above the first" using (firstPlayerSum <= secondPlayerSum)
                "The played action should reflect whether there was a raise" using
                        (outputRound.players[outputRound.currentPlayerIndex].action ==
                                if (firstPlayerSum == secondPlayerSum) PlayerAction.Call
                                else PlayerAction.Raise
                                )
                // TODO this does double duty with the required token signature
                "The currentPlayer should sign off the played action" using
                        (command.signers.contains(outputRound.currentPlayer.owningKey))
            }

            is Commands.Play -> requireThat {
                "There should be one input round state" using (inputRounds.size == 1)
                "There should be one output round state" using (outputRounds.size == 1)
                val inputRound = inputRounds.single()
                val outputRound = outputRounds.single()
                areConstantsConserved(inputRound, outputRound)
                isProgressionValid(inputRound, outputRound)
                "The round bet status should be a play one" using (outputRound.roundType.isPlay)
                "Only the player should bet tokens" using (inputTokens.keys.let {
                    it.size == 0 || it == setOf(outputRound.currentPlayer)
                })
                val lastRaiseSum = inputPots.values.max()
                    ?: throw IllegalArgumentException("There should have been sums bet")
                when (val action = outputRound.players[outputRound.currentPlayerIndex].action) {
                    PlayerAction.Missing -> throw IllegalArgumentException(
                        "The currentPlayer should not have Missing action"
                    )
                    PlayerAction.Fold -> "A folding player should not add tokens" using
                            (inputTokens[outputRound.currentPlayer] == null)
                    PlayerAction.Call, PlayerAction.Raise -> {
                        val currentPlayerSum = outputPots[outputRound.currentPlayer]
                            ?: throw IllegalArgumentException("The currentPlayer should have bet a sum")
                        "The sum should be at least the required one on Call or Raise" using
                                (lastRaiseSum <= currentPlayerSum)
                        "The played action should reflect whether there was a raise" using
                                (action ==
                                        if (lastRaiseSum == currentPlayerSum) PlayerAction.Call
                                        else PlayerAction.Raise)

                    }
                }
                "The currentPlayer should sign off the played action" using
                        (command.signers.contains(outputRound.currentPlayer.owningKey))
            }

            else -> IllegalArgumentException("Unrecognised command $command")
        }
    }

    fun areConstantsConserved(input: RoundState, output: RoundState) =
        requireThat {
            "The minter should not change" using (input.minter == output.minter)
            "The dealer should not change" using (input.dealer == output.dealer)
            "The deckRootHash should not change" using (input.deckRootHash == output.deckRootHash)
            "The player parties should not change" using
                    (input.players.map { it.player } == output.players.map { it.player })
            "The folded players should stay folded" using
                    (output.players.foldedParties().containsAll(input.players.foldedParties()))
            "The expected next round type is not there" using (input.nextRoundTypeOrNull == output.roundType)
        }

    fun isProgressionValid(input: RoundState, output: RoundState) = requireThat {
        "The roundType should not change or increase" using
                (input.roundType == output.roundType || input.roundType.next() == output.roundType)
        "The roundType increases only when previous round is complete" using
                (input.roundType == output.roundType || input.isRoundDone)
        "The currentPlayerIndex should not fold in input" using
                (input.players[output.currentPlayerIndex].action != PlayerAction.Fold)
        "The new currentPlayerIndex should be the previously known as next" using
                (input.nextActivePlayerIndex == output.currentPlayerIndex)
        val isStartingNewPlayRound = input.roundType != output.roundType && output.roundType.isPlay
        "Only the currentPlayer should have a new move, unless it is the same roundType" using
                (!isStartingNewPlayRound || output.players.all { played ->
                    if (played.player == output.currentPlayer) played.action != PlayerAction.Missing
                    else played.action.let { it == PlayerAction.Missing || it == PlayerAction.Fold }
                })
    }

    interface Commands : CommandData {
        class BetBlind1 : Commands
        class BetBlind2 : Commands
        class Play : Commands
    }
}
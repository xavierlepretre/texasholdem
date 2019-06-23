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
import org.cordacodeclub.bluff.state.mapPartyToSum

class OneStepContract : Contract {
    companion object {
        val ID = "org.cordacodeclub.bluff.contract.OneStepContract"
    }

    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Commands>()
        val inputRounds = tx.inputsOfType<RoundState>()
        val outputRounds = tx.outputsOfType<RoundState>()
        val inputPots = tx.inputsOfType<TokenState>().filter { it.isPot }.mapPartyToSum()
        val outputPots = tx.outputsOfType<TokenState>().filter { it.isPot }.mapPartyToSum()

        when (command.value) {
            is Commands.BetBlind1 -> requireThat {
                "There should be no input round state" using (inputRounds.isEmpty())
                "There should be a single output round state" using (outputRounds.size == 1)
                val outputRound = outputRounds.single()
                "The round bet status should be ${BettingRound.BLIND_BET_1}, but it was ${outputRound.roundType}" using
                        (outputRound.roundType == BettingRound.BLIND_BET_1)
                "The currentPlayerIndex should be the lastRaiseIndex" using
                        (outputRound.currentPlayerIndex == outputRound.lastRaiseIndex)
                "The currentPlayerIndex should have a raise action, others missing" using (outputRound.players.all {
                    (it.player == outputRound.currentPlayer && it.action == PlayerAction.Raise) ||
                            (it.player != outputRound.currentPlayer && it.action == PlayerAction.Missing)
                })
                "There should be no input pot tokens" using (inputPots.isEmpty())
                "There should be output pot tokens for the player" using (outputPots[outputRound.currentPlayer].let {
                    it != null && it > 0L
                })
                "There should be no other output pot tokens" using (outputPots.size == 1)
                "The dealer should sign off the deckRootHash" using
                        (command.signers.contains(outputRound.dealer.owningKey))
            }

            else -> IllegalArgumentException("Unrecognised command $command")
        }
    }

    interface Commands : CommandData {
        class BetBlind1 : Commands
    }
}
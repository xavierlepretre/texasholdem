package org.cordacodeclub.bluff.contract

import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.requireSingleCommand
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.LedgerTransaction
import org.cordacodeclub.bluff.state.GameState

class GameContract : Contract {
    companion object {
        val ID = "org.cordacodeclub.bluff.contract.GameContract"
    }

    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Commands>()
        val inputGames = tx.inputsOfType<GameState>()
        val outputGames = tx.outputsOfType<GameState>()

        when (command.value) {
            is Commands.Create -> requireThat {
                "There should be no input games" using (inputGames.isEmpty())
                "There should be a single output game" using (outputGames.size == 1)
            }

            is Commands.CarryOn -> requireThat {
                "There should be one input game" using (inputGames.size == 1)
                "There should be one output game" using (outputGames.size == 1)
                // TODO check that the cards that have been decrypted match with its previously encrypted form
//                "The games should be the same" using (inputGames.single() == outputGames.single())
            }

            is Commands.Close -> requireThat {
                "There should be a single input game" using (inputGames.size == 1)
                "There should be no output games" using (outputGames.isEmpty())
                // TODO check there is a win
            }

            else -> IllegalArgumentException("Unrecognised command $command")
        }
    }

    interface Commands : CommandData {
        class Create : Commands
        class CarryOn : Commands
        class Close : Commands
    }
}
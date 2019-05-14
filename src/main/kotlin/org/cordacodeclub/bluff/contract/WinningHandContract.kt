package org.cordacodeclub.bluff.contract

import net.corda.core.contracts.Contract
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.LedgerTransaction
import org.cordacodeclub.bluff.state.GameState
import org.cordacodeclub.bluff.state.PlayerHandState

class WinningHandContract: Contract {
    companion object {
        val ID = "org.cordacodeclub.bluff.contract.WinningHandContract"
    }

    override fun verify(tx: LedgerTransaction) {
        val inputHands = tx.inputsOfType<PlayerHandState>()
        val inputGameState = tx.inputsOfType<GameState>().single()
        val inputCards = inputGameState.cards
        //val outputGames = tx.outputsOfType<PlayerHandState>()


        requireThat {
            inputHands.mapNotNull {
                "There must be 5 cards for player ${it.owner}" using (it.cardIndexes.size == 5)
                "The cards must belong to ${it.owner}" using
                        (inputCards[it.cardIndexes.first()].owner == it.owner)
            }

            //sortedWith
            //compareTo(Hand)
        }
    }




}
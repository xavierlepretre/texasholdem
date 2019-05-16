package org.cordacodeclub.bluff.contract

import net.corda.core.contracts.Contract
import net.corda.core.contracts.requireThat
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.transactions.LedgerTransaction
import net.corda.testing.core.TestIdentity
import org.bouncycastle.jcajce.provider.asymmetric.dh.BCDHPublicKey
import org.cordacodeclub.bluff.state.AssignedCard
import org.cordacodeclub.bluff.state.ClearCard
import org.cordacodeclub.bluff.state.GameState
import org.cordacodeclub.bluff.state.PlayerHandState
import org.cordacodeclub.grom356.Card
import org.cordacodeclub.grom356.CardSet
import org.cordacodeclub.grom356.Hand
import java.security.PublicKey

class WinningHandContract: Contract {
    companion object {
        val ID = "org.cordacodeclub.bluff.contract.WinningHandContract"
    }

    override fun verify(tx: LedgerTransaction) {
        val inputHands = tx.inputsOfType<PlayerHandState>()
        val inputGameState = tx.inputsOfType<GameState>().single()
        val inputCards = inputGameState.cards
        val outputHands = tx.outputsOfType<PlayerHandState>()


        requireThat {
            inputHands.map {
                "There must be 5 cards for player ${it.owner}" using (it.cardIndexes.size == 5)
                "The cards must belong to ${it.owner}, but found ${inputCards[it.cardIndexes.first()].owner}" using
                        (inputCards[it.cardIndexes.first()].owner == it.owner)
            }
            "There must be one input game" using (tx.inputsOfType<GameState>().size == 1)
            "There must be no output gme" using (tx.outputsOfType<GameState>().isEmpty())

            //sortedWith
            //compareTo(Hand)
        }
    }

}

fun sortedHands(gameHandStates: List<PlayerHandState>, gameCards: List<AssignedCard>) : List<Hand> {
    val handsList = gameHandStates.map {
        val cardList: kotlin.collections.MutableList<Card> = java.util.ArrayList()
        val playerCards = it.cardIndexes.map {
            val card = gameCards[it].card!!
            cardList.add(card)
            card
        }.toCollection(cardList)
        Hand.eval(CardSet(playerCards))
    }
    return handsList.sortedWith(compareBy { it.compareTo(it) })
}
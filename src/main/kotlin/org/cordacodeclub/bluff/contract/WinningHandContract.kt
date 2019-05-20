package org.cordacodeclub.bluff.contract

import net.corda.core.contracts.Contract
import net.corda.core.contracts.requireThat
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.transactions.LedgerTransaction
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
        val outputHand = getPlayerHands(tx.outputsOfType<PlayerHandState>().single(), inputGameState.cards).first
        val winningHand = winningHand(inputHands, inputGameState.cards)

        requireThat {
            inputHands.map {
                "There must be 5 cards for player ${it.owner}" using (it.cardIndexes.size == 5)
                "The cards must belong to ${it.owner}, but found ${inputCards[it.cardIndexes.first()].owner}" using
                        (inputCards[it.cardIndexes.first()].owner == it.owner) }
            "There must be 52 cards in a game" using (inputGameState.cards.size == 52)
            "There must be one input game" using (tx.inputsOfType<GameState>().size == 1)
            "There must be no output gme" using (tx.outputsOfType<GameState>().isEmpty())
            "There must be one output hand" using (outputHand != null)
            "The owner of the winning hands must be one of the players" using (tx.inputsOfType<PlayerHandState>()
                    .map { it.owner }.contains(winningHand.second))
            "The winning hand must be the same as the output hand" using (winningHand.first == outputHand)
        }
    }
}

fun winningHand(gameHandStates: List<PlayerHandState>, gameCards: List<AssignedCard>) : Pair<Hand, Party> {
    val handsList = gameHandStates.map {
        val result = getPlayerHands(it, gameCards)
        println("The hand for ${it.owner.name.organisation} is: " + result)
        result
    }
    return handsList.sortedByDescending { it.first.value }.first()
}

fun getPlayerHands(gameHandState: PlayerHandState, gameCards: List<AssignedCard>) : Pair<Hand, Party> {
    val cardList: kotlin.collections.MutableList<Card> = java.util.ArrayList()
    val playerCards = gameHandState.cardIndexes.map {
        val card = gameCards[it].card!!
        cardList.add(card)
        card
    }.toCollection(cardList)
    return Pair(Hand.eval(CardSet(playerCards)), gameHandState.owner)
}
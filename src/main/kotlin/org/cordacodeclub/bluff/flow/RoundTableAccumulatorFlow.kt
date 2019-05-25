package org.cordacodeclub.bluff.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.utilities.unwrap
import org.cordacodeclub.bluff.dealer.CardDeckInfo

class RoundTableAccumulatorFlow(
    val deckInfo: CardDeckInfo,
    val playerFlows: List<FlowSession>,
    private var accumulator: RoundTableAccumulator,
    val depth: Int = 0
) : FlowLogic<RoundTableAccumulator>() {

    @Suspendable
    override fun call(): RoundTableAccumulator {
        if (accumulator.isRoundDone) {
            return accumulator
        }
        val request = CallOrRaiseRequest(
            minter = accumulator.minter,
            lastRaise = accumulator.currentLevel,
            yourWager = accumulator.currentPlayerSum,
            yourCards = deckInfo.cards
                .drop(accumulator.currentPlayerIndex * CardDeckInfo.CARDS_PER_PLAYER)
                .take(CardDeckInfo.CARDS_PER_PLAYER),
            communityCards = listOf()
        )
        val response = playerFlows[accumulator.currentPlayerIndex]
            .sendAndReceive<CallOrRaiseResponse>(request).unwrap { it }
        return subFlow(
            RoundTableAccumulatorFlow(
                deckInfo = deckInfo,
                playerFlows = playerFlows,
                accumulator = accumulator.stepForwardWhenCurrentPlayerSent(response),
                depth = depth + 1
            )
        )
    }
}
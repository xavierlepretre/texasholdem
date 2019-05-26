package org.cordacodeclub.bluff.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.utilities.unwrap
import org.cordacodeclub.bluff.dealer.CardDeckInfo
import org.cordacodeclub.bluff.round.CallOrRaiseRequest
import org.cordacodeclub.bluff.round.CallOrRaiseResponse
import org.cordacodeclub.bluff.round.DealerRoundAccumulator
import org.cordacodeclub.bluff.round.RoundTableDone

class RoundTableAccumulatorFlow(
    val deckInfo: CardDeckInfo,
    val playerFlows: List<FlowSession>,
    val accumulator: DealerRoundAccumulator,
    val depth: Int = 0
) : FlowLogic<DealerRoundAccumulator>() {

    @Suspendable
    override fun call(): DealerRoundAccumulator {
        if (accumulator.isRoundDone) {
            // Notify all players that the round is done. We need to do this because the responder flow has to move on
            with(RoundTableDone(accumulator.newBets.flatMap { it.value })) {
                playerFlows.forEach { it.send(this) }
            }
            return accumulator
        }
        val request = CallOrRaiseRequest(
            minter = accumulator.minter,
            lastRaise = accumulator.currentLevel,
            yourWager = accumulator.currentPlayerSum,
            yourCards = deckInfo.getPlayerCards(accumulator.currentPlayerIndex),
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
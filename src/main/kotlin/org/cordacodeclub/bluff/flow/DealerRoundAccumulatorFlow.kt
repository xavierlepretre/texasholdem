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

class DealerRoundAccumulatorFlow(
    val deckInfo: CardDeckInfo,
    val playerFlows: List<FlowSession>,
    val accumulator: DealerRoundAccumulator,
    val depth: Int = 0
) : FlowLogic<DealerRoundAccumulator>() {

    @Suspendable
    override fun call(): DealerRoundAccumulator {
        if (accumulator.isRoundDone) {
            // Notify all players that the round is done. We need to do this because the responder flow has to move on
            val roundTableDone = RoundTableDone(accumulator.newBets.flatMap { it.value })
            playerFlows.forEach { it.send(roundTableDone) }
            return accumulator
        }
        val request = CallOrRaiseRequest(
            minter = accumulator.minter,
            lastRaise = accumulator.currentLevel,
            yourWager = accumulator.currentPlayerSum,
            cardHashes = deckInfo.hashedCards,
            yourCards = deckInfo.getPlayerCards(accumulator.currentPlayerIndex),
            communityCards = deckInfo.getCommunityCards(accumulator.players.size)
                .take(accumulator.round.communityCardsCount)
        )
        playerFlows[accumulator.currentPlayerIndex].send(request)
        val response = playerFlows[accumulator.currentPlayerIndex]
            .receive<CallOrRaiseResponse>().unwrap { it }
        return try {
            subFlow(
                DealerRoundAccumulatorFlow(
                    deckInfo = deckInfo,
                    playerFlows = playerFlows,
                    accumulator = accumulator.stepForwardWhenCurrentPlayerSent(response),
                    depth = depth + 1
                )
            )
        } catch (e: Throwable) {
            println(e)
            throw e
        }
    }
}
package org.cordacodeclub.bluff.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.requireThat
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.utilities.unwrap
import org.cordacodeclub.bluff.player.PlayerAction
import org.cordacodeclub.bluff.round.*
import org.cordacodeclub.bluff.state.TokenState

open class PlayerSideResponseAccumulatorFlow(
    val otherPartySession: FlowSession,
    private var accumulator: PlayerSideResponseAccumulator,
    val playerResponseCollectingFlow: (CallOrRaiseRequest) -> PlayerResponseCollectingFlow,
    val depth: Int = 0
) : FlowLogic<PlayerSideResponseAccumulator>() {

    @Suspendable
    override fun call(): PlayerSideResponseAccumulator {
        val me = serviceHub.myInfo.legalIdentities.first()
        if (accumulator.isDone) {
            return accumulator
        }

        val request = otherPartySession.receive<RoundTableRequest>().unwrap { it }
        val nextAccumulator = when (request) {
            is RoundTableDone -> {
                accumulator.stepForwardWhenIsDone(request = request)
            }
            is CallOrRaiseRequest -> {
                // The initiating flow expects a response
                requireThat {
                    "We should be starting with no card or be sent the same cards again"
                        .using(accumulator.myCards.isEmpty() || request.yourCards == accumulator.myCards)
                    "Card should be assigned to me" using (request.yourCards.map { it.owner }.toSet().single() == me.name)
                    // We cannot test the below because, for instance, player1 after blind bet has a !=0 wager
                    // but no new bets
//                    "My wager should match my new bets" using (myNewBets.map { it.state.data.amount }.sum() == request.yourWager)
                }
                logger.info("About to ask user from $request")
                val userResponse = subFlow(playerResponseCollectingFlow(request))
                val desiredAmount = userResponse.addAmount + request.lastRaise - request.yourWager
                val response = when (userResponse.playerAction!!) {
                    PlayerAction.Fold -> CallOrRaiseResponse()
                    PlayerAction.Call, PlayerAction.Raise -> desiredAmount.let { amount ->
                        if (amount == 0L) CallOrRaiseResponse(listOf(), serviceHub)
                        else CallOrRaiseResponse(
                            subFlow(
                                CollectOwnTokenStateFlow(
                                    TokenState(
                                        minter = request.minter,
                                        owner = me,
                                        amount = amount,
                                        isPot = false
                                    )
                                )
                            ),
                            serviceHub
                        )
                    }
                }
                accumulator.stepForwardWhenSending(request, response).also {
                    otherPartySession.send(response)
                }
            }
            else -> throw IllegalArgumentException("Unknown type $request")
        }
        return subFlow(
            PlayerSideResponseAccumulatorFlow(
                otherPartySession = otherPartySession,
                accumulator = nextAccumulator,
                playerResponseCollectingFlow = playerResponseCollectingFlow,
                depth = depth + 1
            )
        )
    }
}
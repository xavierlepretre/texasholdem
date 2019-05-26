package org.cordacodeclub.bluff.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.requireThat
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.identity.Party
import net.corda.core.utilities.unwrap
import org.cordacodeclub.bluff.state.TokenState
import org.cordacodeclub.bluff.user.UserResponder
import org.cordacodeclub.bluff.user.UserResponderI

class ResponseAccumulatorFlow(
    val otherPartySession: FlowSession,
    val userResponder: UserResponderI,
    private var accumulator: ResponseAccumulator,
    val depth: Int = 0
) : FlowLogic<ResponseAccumulator>() {

    @Suspendable
    override fun call(): ResponseAccumulator {
        if (accumulator.isDone) {
            return accumulator
        }

        val me = serviceHub.myInfo.legalIdentities.first()
        val responseBuilder: ResponseAccumulator.(CallOrRaiseRequest) -> CallOrRaiseResponse =
            { request ->
                // The initiating flow expects a response
                requireThat {
                    "We should be starting with no card or be sent the same cards again"
                        .using(myCards.isEmpty() || request.yourCards == myCards)
                    "Card should be assigned to me" using (request.yourCards.map { it.owner }.toSet().single() == me.name)
                    // We cannot test the below because, for instance, player1 after blind bet has a !=0 wager
                    // but no new bets
//                    "My wager should match my new bets" using (myNewBets.map { it.state.data.amount }.sum() == request.yourWager)
                }
                logger.info("About to ask user from $request")
                val userResponse = userResponder.getAction(request)
                val desiredAmount = userResponse.addAmount + request.lastRaise - request.yourWager
                when (userResponse.action!!) {
                    Action.Fold -> CallOrRaiseResponse()
                    Action.Call, Action.Raise -> desiredAmount.let { amount ->
                        if (amount == 0L) CallOrRaiseResponse(listOf(), serviceHub)
                        else CallOrRaiseResponse(
                            subFlow(
                                TokenStateCollectorFlow(
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
            }

        val nextAccumulator = otherPartySession.receive<RoundTableRequest>().unwrap { it }
            .let { request ->
                when (request) {
                    is RoundTableDone -> accumulator.stepForwardWhenIsDone(request = request)
                    is CallOrRaiseRequest -> accumulator.responseBuilder(request)
                        .let { response ->
                            accumulator.stepForwardWhenSending(request, response)
                                .also {
                                    otherPartySession.send(response)
                                }
                        }
                    else -> throw IllegalArgumentException("Unknown type $request")
                }
            }
        return subFlow(ResponseAccumulatorFlow(
            otherPartySession = otherPartySession,
            userResponder = userResponder,
            accumulator = nextAccumulator,
            depth = depth + 1
        ))
    }
}
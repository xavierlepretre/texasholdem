package org.cordacodeclub.bluff.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.requireThat
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.utilities.unwrap
import org.cordacodeclub.bluff.player.ActionRequest
import org.cordacodeclub.bluff.player.PlayerAction
import org.cordacodeclub.bluff.round.*
import org.cordacodeclub.bluff.state.TokenState

abstract class PlayerSideResponseAccumulatorFlow(val otherPartySession: FlowSession) :
    FlowLogic<PlayerSideResponseAccumulator>() {

    var accumulator = PlayerSideResponseAccumulator()
    var depth = 0

    @Suspendable
    abstract fun getActionRequest(request: CallOrRaiseRequest): ActionRequest

    @Suspendable
    abstract fun createOwn(otherPartySession: FlowSession): PlayerSideResponseAccumulatorFlow

    @Suspendable
    override fun call(): PlayerSideResponseAccumulator {
        val me = serviceHub.myInfo.legalIdentities.first()
        if (accumulator.isDone) {
            return accumulator
        }

        val request = otherPartySession.receive<RoundTableRequest>().unwrap { it }
        val nextAccumulator = try {
            when (request) {
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
                    val userResponse = getActionRequest(request)
                    val desiredAmount = userResponse.addAmount + request.lastRaise - request.yourWager
                    val response = when (userResponse.playerAction!!) {
                        PlayerAction.Fold -> CallOrRaiseResponse()
                        PlayerAction.Call, PlayerAction.Raise -> {
                            if (desiredAmount == 0L) CallOrRaiseResponse(listOf(), serviceHub)
                            else {
                                val tokenStates = try {
                                    subFlow(
                                        CollectOwnTokenStateFlow(
                                            TokenState(
                                                minter = request.minter,
                                                owner = me,
                                                amount = desiredAmount,
                                                isPot = false
                                            )
                                        )
                                    )
                                } catch (e: Throwable) {
                                    println(e)
                                    throw e
                                }
                                CallOrRaiseResponse(tokenStates, serviceHub)
                            }
                        }
                        else -> throw NotImplementedError("Should not have ${userResponse.playerAction}")
                    }
                    val newAccumulator = accumulator.stepForwardWhenSending(request, response)
                    otherPartySession.send(response)
                    newAccumulator
                }
                else -> throw IllegalArgumentException("Unknown type $request")
            }
        } catch (e: Throwable) {
            println(e)
            throw e
        }
        val deeper = createOwn(otherPartySession = otherPartySession)
        deeper.accumulator = nextAccumulator
        deeper.depth = depth + 1
        return try {
            subFlow(deeper)
        } catch (e: Throwable) {
            println(e)
            throw e
        }
    }
}
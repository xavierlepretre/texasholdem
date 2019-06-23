package org.cordacodeclub.bluff.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.identity.Party
import net.corda.core.utilities.unwrap
import org.cordacodeclub.bluff.dealer.CardDeckInfo
import org.cordacodeclub.bluff.player.DesiredAction
import org.cordacodeclub.bluff.player.PlayerAction
import org.cordacodeclub.bluff.round.CallOrRaiseRequest
import org.cordacodeclub.bluff.round.CallOrRaiseResponse
import org.cordacodeclub.bluff.round.DealerRoundAccumulator
import org.cordacodeclub.bluff.round.RoundTableDone
import org.cordacodeclub.bluff.state.TokenState

object ControlledDealerAccumulatorFlow {

    @InitiatingFlow
    class Initiator(
        val deckInfo: CardDeckInfo,
        val players: List<Party>,
        val accumulator: DealerRoundAccumulator,
        val responderActions: Map<Party, List<DesiredAction>> = mapOf()
    ) : FlowLogic<DealerRoundAccumulator>() {

        @Suspendable
        override fun call(): DealerRoundAccumulator {
            val playerFlows = players.map { player ->
                initiateFlow(player).also {
                    it.send(responderActions[player] ?: listOf(
                        DesiredAction(
                            PlayerAction.Fold,
                            0L
                        )
                    ))
                }
            }
            return subFlow(
                DealerRoundAccumulatorFlow(
                    deckInfo = deckInfo,
                    playerFlows = playerFlows,
                    accumulator = accumulator
                )
            )
        }
    }

    @InitiatedBy(Initiator::class)
    class RemoteControlledResponder(val otherPartySession: FlowSession) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            val desiredActions = otherPartySession.receive<List<DesiredAction>>().unwrap { it }
            desiredActions.forEach { desiredAction ->
                val request = otherPartySession.receive<CallOrRaiseRequest>().unwrap { it }
                val desiredAmount = desiredAction.raiseBy + request.lastRaise - request.yourWager
                val response = when (desiredAction.playerAction) {
                    PlayerAction.Fold -> CallOrRaiseResponse()
                    PlayerAction.Call, PlayerAction.Raise -> desiredAmount.let { amount ->
                        if (amount == 0L) CallOrRaiseResponse(listOf(), serviceHub)
                        else CallOrRaiseResponse(
                            subFlow(
                                CollectOwnTokenStateFlow(
                                    TokenState(
                                        minter = request.minter,
                                        owner = serviceHub.myInfo.legalIdentities.first(),
                                        amount = amount,
                                        isPot = false
                                    )
                                )
                            ),
                            serviceHub
                        )
                    }
                    else -> throw NotImplementedError("Cannot be ${desiredAction.playerAction}")
                }
                otherPartySession.send(response)
            }
            otherPartySession.receive<RoundTableDone>()
        }
    }
}
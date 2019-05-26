package org.cordacodeclub.bluff.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.identity.Party
import net.corda.core.utilities.unwrap
import org.cordacodeclub.bluff.dealer.CardDeckInfo
import org.cordacodeclub.bluff.round.CallOrRaiseRequest
import org.cordacodeclub.bluff.round.PlayerSideResponseAccumulator
import org.cordacodeclub.bluff.round.DealerRoundAccumulator
import org.cordacodeclub.bluff.player.ActionRequest
import org.cordacodeclub.bluff.player.DesiredAction
import org.cordacodeclub.bluff.player.PlayerResponder

object ResponseAccumulatorActorFlow {

    class PlayerResponderPrepared(
        val desiredActions: List<DesiredAction>,
        val me: Party
    ) : PlayerResponder {

        private var index = 0

        override fun getAction(request: CallOrRaiseRequest): ActionRequest {
            return ActionRequest(
                id = 0,
                player = me.name,
                cards = request.yourCards.map { it.card },
                youBet = request.yourWager,
                lastRaise = request.lastRaise,
                playerAction = desiredActions[index].playerAction,
                addAmount = desiredActions[index].raiseBy
            ).also {
                index++
            }
        }
    }

    @InitiatingFlow
    class Initiator(
        val deckInfo: CardDeckInfo,
        val players: List<Party>,
        val accumulator: DealerRoundAccumulator,
        val responderActions: Map<Party, List<DesiredAction>> = mapOf()
    ) : FlowLogic<Map<Party, PlayerSideResponseAccumulator>>() {

        @Suspendable
        override fun call(): Map<Party, PlayerSideResponseAccumulator> {
            val playerFlows = players.map { player ->
                initiateFlow(player).also {
                    it.send(responderActions[player]!!)
                }
            }
            val accumulated = subFlow(
                RoundTableAccumulatorFlow(
                    deckInfo = deckInfo,
                    playerFlows = playerFlows,
                    accumulator = accumulator
                )
            )
            return players.mapIndexed { index, player ->
                player to playerFlows[index].receive<PlayerSideResponseAccumulator>().unwrap { it }
            }.toMap()
        }
    }

    @InitiatedBy(Initiator::class)
    class RemoteControlledResponder(val otherPartySession: FlowSession) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            val desiredActions = otherPartySession.receive<List<DesiredAction>>().unwrap { it }
            val accumulated = subFlow(
                ResponseAccumulatorFlow(
                    otherPartySession = otherPartySession,
                    playerResponder = PlayerResponderPrepared(desiredActions, serviceHub.myInfo.legalIdentities.first()),
                    accumulator = PlayerSideResponseAccumulator()
                )
            )
            otherPartySession.send(accumulated)
        }
    }
}
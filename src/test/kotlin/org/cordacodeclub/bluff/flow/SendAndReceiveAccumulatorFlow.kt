package org.cordacodeclub.bluff.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.identity.Party
import net.corda.core.utilities.unwrap
import org.cordacodeclub.bluff.dealer.CardDeckInfo

object SendAndReceiveAccumulatorFlow {

    @InitiatingFlow
    class Initiator(
        val deckInfo: CardDeckInfo,
        val players: List<Party>,
        private var accumulator: RoundTableAccumulator
    ) : FlowLogic<RoundTableAccumulator>() {

        @Suspendable
        override fun call(): RoundTableAccumulator {
            return subFlow(
                RoundTableAccumulatorFlow(
                    deckInfo = deckInfo,
                    playerFlows = players.map { initiateFlow(it) },
                    accumulator = accumulator
                )
            )
        }
    }

    @InitiatedBy(Initiator::class)
    class FoldResponder(val otherPartySession: FlowSession) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            val request = otherPartySession.receive<CallOrRaiseRequest>().unwrap { it }
            otherPartySession.send(CallOrRaiseResponse())
        }
    }
}
package org.cordacodeclub.bluff.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.identity.Party
import net.corda.core.utilities.unwrap
import org.cordacodeclub.bluff.round.CallOrRaiseRequest
import org.cordacodeclub.bluff.round.CallOrRaiseResponse

object SingleRequestFlow {

    @InitiatingFlow
    class Initiator(
        val player: Party,
        val request: CallOrRaiseRequest
    ) : FlowLogic<CallOrRaiseResponse>() {

        @Suspendable
        override fun call(): CallOrRaiseResponse {
            return initiateFlow(player).sendAndReceive<CallOrRaiseResponse>(request).unwrap { it }
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
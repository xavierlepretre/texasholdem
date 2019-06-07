package org.cordacodeclub.bluff.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.identity.Party
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap

object SlowBounceFlow {

    @InitiatingFlow
    /**
     * This flow is started by a player party to free the flows on the player node, while the human makes a decision.
     * @param bouncer party off which to bounce the request
     * @param duration the desired duration in milliseconds
     */
    class Initiator(val bouncer: Party, val duration: Long) : FlowLogic<Unit>() {

        init {
            require(duration <= MAX_DURATION) { "Duration should not exceed $MAX_DURATION" }
        }

        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object SENDING : ProgressTracker.Step("Sending a message to bounce.")
            object WAITING : ProgressTracker.Step("Waiting for a return.")
            object DONE : ProgressTracker.Step("Moving on.")

            val MAX_DURATION = 1000 * 3600L // 1 hour
        }

        private fun tracker() = ProgressTracker(SENDING, WAITING, DONE)

        override val progressTracker = tracker()

        /**
         * The flow logic is encapsulated within the call() method.
         */
        @Suspendable
        override fun call() {
            initiateFlow(bouncer).also {
                progressTracker.currentStep = SENDING
                it.send(duration)
                progressTracker.currentStep = WAITING
                it.receive<Unit>()
                progressTracker.currentStep = DONE
            }
        }
    }

    @InitiatedBy(Initiator::class)
    class Responder(val otherPartySession: FlowSession) : FlowLogic<Unit>() {

        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object AWAKEN : ProgressTracker.Step("Received remote instruction.")
            object RECEIVED : ProgressTracker.Step("Received duration to wait, waiting.")
            object RETURNING : ProgressTracker.Step("Returning info.")
            object DONE : ProgressTracker.Step("Done sending.")

            fun tracker() = ProgressTracker(AWAKEN, RECEIVED, RETURNING, DONE)
        }

        override val progressTracker: ProgressTracker = tracker()

        @Suspendable
        override fun call() {
            progressTracker.currentStep = AWAKEN
            val duration = otherPartySession.receive<Long>().unwrap { it }
            progressTracker.currentStep = RECEIVED
//            Thread.sleep(duration)
            progressTracker.currentStep = RETURNING
            otherPartySession.send(Unit)
            progressTracker.currentStep = DONE
        }
    }
}
package org.cordacodeclub.bluff.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.utilities.ProgressTracker
import org.cordacodeclub.bluff.player.PlayerAction
import org.cordacodeclub.bluff.player.PlayerDatabaseService

@StartableByRPC
/**
 * This flow is started by a player's API to inform of the human's decision.
 * @param requestId the request id to inform about
 * @param action the action chosen by the human
 * @param addAmount the amount chosen by the human
 */
class PlayerActionResponseFlow(
    val requestId: Long,
    val action: PlayerAction,
    val addAmount: Long
) : FlowLogic<Int>() {

    /**
     * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
     * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
     */
    companion object {
        object SAVING : ProgressTracker.Step("Saving action.")
        object DONE : ProgressTracker.Step("Returning.")

        val TYPICAL_DURATION = 5000L
    }

    private fun tracker() = ProgressTracker(SAVING, DONE)

    override val progressTracker = tracker()

    /**
     * The flow logic is encapsulated within the call() method.
     */
    @Suspendable
    override fun call(): Int {
        val playerDatabaseService = serviceHub.cordaService(PlayerDatabaseService::class.java)
        val me = serviceHub.myInfo.legalIdentities.first()
        progressTracker.currentStep = SAVING
        return playerDatabaseService.updateActionRequest(requestId, action, addAmount)
            .also {
                require(it == 1) { "The request should have been entered" }
                progressTracker.currentStep = DONE
            }
    }
}
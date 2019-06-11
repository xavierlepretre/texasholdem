package org.cordacodeclub.bluff.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.crypto.MerkleTree
import net.corda.core.identity.Party
import net.corda.core.utilities.ProgressTracker
import org.cordacodeclub.bluff.player.ActionRequest
import org.cordacodeclub.bluff.player.PlayerDatabaseService
import org.cordacodeclub.bluff.round.CallOrRaiseRequest

/**
 * This flow is started by a player party to collect the human's decision. It is polling the database at intervals,
 * bouncing off to another party to give time.
 * @param request the request made to the player
 * @param bouncer party off which to bounce the waiting part
 * @param duration the desired duration in milliseconds
 */
class PlayerResponseCollectingByPollerFlow(
    request: CallOrRaiseRequest,
    val bouncer: Party,
    val duration: Long = TYPICAL_DURATION
) : PlayerResponseCollectingFlow(request) {

    /**
     * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
     * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
     */
    companion object {
        object SAVING : ProgressTracker.Step("Saving request.")
        object WAITING : ProgressTracker.Step("Waiting for answer.")
        object ANSWERING : ProgressTracker.Step("Returning human answer.")

        val TYPICAL_DURATION = 5000L
    }

    private fun tracker() = ProgressTracker(SAVING, WAITING, ANSWERING)

    override val progressTracker = tracker()
    var insertedRequest: Long = -1
        private set

    @Suspendable
    override fun call(): ActionRequest {
        val playerDatabaseService = serviceHub.cordaService(PlayerDatabaseService::class.java)
        val me = serviceHub.myInfo.legalIdentities.first()
        progressTracker.currentStep = SAVING
        insertedRequest = playerDatabaseService.addActionRequest(
            ActionRequest(
                id = 0L,
                player = me.name,
                cards = request.yourCards.map { it.card },
                cardHashes = MerkleTree.getMerkleTree(request.cardHashes).hash,
                youBet = request.yourWager,
                lastRaise = request.lastRaise,
                playerAction = null,
                addAmount = 0L
            )
        ).also {
            require(it.first == 1) { "The request should have been entered" }
        }.second.id

        progressTracker.currentStep = WAITING
        do {
            subFlow(SlowBounceFlow.Initiator(bouncer, duration))
        } while (playerDatabaseService.getActionRequest(insertedRequest)!!.playerAction == null)

        progressTracker.currentStep = ANSWERING
        return playerDatabaseService.getActionRequest(insertedRequest)!!
            .also {
                playerDatabaseService.deleteActionRequest(it.id)
            }
    }
}
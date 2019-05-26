package org.cordacodeclub.bluff.player

import net.corda.core.identity.Party
import org.cordacodeclub.bluff.round.CallOrRaiseRequest

class PlayerResponderPoller(val me: Party, val playerDatabaseService: PlayerDatabaseService) :
    PlayerResponder {

    companion object {
        const val pollingInterval: Long = 5000 // 5 seconds
    }

    override fun getAction(request: CallOrRaiseRequest): ActionRequest {
        // Save the request to db
        var actedRequest = playerDatabaseService.addActionRequest(
            ActionRequest(
                id = 0L,
                player = me.name,
                cards = request.yourCards.map { it.card },
                youBet = request.yourWager,
                lastRaise = request.lastRaise,
                playerAction = null,
                addAmount = 0L
            )
        ).second
        // HACK blocking until player has responded
        do {
            Thread.sleep(pollingInterval)
            actedRequest = playerDatabaseService.getActionRequest(actedRequest.id)!!
        } while (actedRequest.playerAction == null)
        return actedRequest
    }
}
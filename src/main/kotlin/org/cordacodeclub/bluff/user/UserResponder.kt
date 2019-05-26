package org.cordacodeclub.bluff.user

import net.corda.core.identity.Party
import org.cordacodeclub.bluff.flow.CallOrRaiseRequest

interface UserResponderI {
    fun getAction(request: CallOrRaiseRequest): ActionRequest
}

class UserResponder(val me: Party, val playerDatabaseService: PlayerDatabaseService) : UserResponderI {

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
                action = null,
                addAmount = 0L
            )
        ).second
        // HACK blocking until player has responded
        do {
            Thread.sleep(pollingInterval)
            actedRequest = playerDatabaseService.getActionRequest(actedRequest.id)!!
        } while (actedRequest.action == null)
        return actedRequest
    }
}
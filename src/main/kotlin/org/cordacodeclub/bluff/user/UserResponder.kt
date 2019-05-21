package org.cordacodeclub.bluff.user

import net.corda.core.identity.Party
import org.cordacodeclub.bluff.flow.CallOrRaiseRequest

class UserResponder(val me: Party, val playerDatabaseService: PlayerDatabaseService) {

    companion object {
        const val pollingInterval: Long = 5000 // 5 seconds
    }

    fun getAction(request: CallOrRaiseRequest): ActionRequest {
        // Save the request to db
        var actedRequest = playerDatabaseService.addActionRequest(
            ActionRequest(
                id = 0L,
                party = me.toString(),
                card1 = request.yourCards[0].card!!.toString(),
                card2 = request.yourCards[1].card!!.toString(),
                card3 = "",
                card4 = "",
                card5 = "",
                card6 = "",
                card7 = "",
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
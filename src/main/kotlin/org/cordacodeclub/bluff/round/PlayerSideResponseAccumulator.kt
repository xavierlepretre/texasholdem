package org.cordacodeclub.bluff.round

import net.corda.core.contracts.StateAndRef
import net.corda.core.serialization.CordaSerializable
import org.cordacodeclub.bluff.state.AssignedCard
import org.cordacodeclub.bluff.state.TokenState


@CordaSerializable
data class PlayerSideResponseAccumulator(
    val myCards: List<AssignedCard>,
    val myNewBets: List<StateAndRef<TokenState>>,
    val allNewBets: List<StateAndRef<TokenState>>,
    val isDone: Boolean
) {

    constructor() : this(listOf(), listOf(), listOf(), false)

    fun stepForwardWhenSending(request: RoundTableRequest, response: CallOrRaiseResponse): PlayerSideResponseAccumulator {
        return when (response.isFold) {
            true -> this
            false -> this.copy(
                myCards = when (request) {
                    is CallOrRaiseRequest -> request.yourCards
                    else -> myCards
                },
                myNewBets = myNewBets.plus(response.moreBets)
            )
        }
    }

    fun stepForwardWhenIsDone(request: RoundTableDone) =
        this.copy(isDone = true, allNewBets = request.allNewTokens)
}
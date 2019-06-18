package org.cordacodeclub.bluff.round

import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.requireThat
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import org.cordacodeclub.bluff.dealer.HashedCardDeckInfo
import org.cordacodeclub.bluff.state.AssignedCard
import org.cordacodeclub.bluff.state.TokenState

@CordaSerializable
// Marker interface that is sent to the responder flow when going round the table
interface RoundTableRequest

@CordaSerializable
data class RoundTableDone(val allNewTokens: List<StateAndRef<TokenState>>) : RoundTableRequest

@CordaSerializable
data class CallOrRaiseRequest(
    val minter: Party,
    val lastRaise: Long,
    val yourWager: Long,
    val cardHashes: List<SecureHash>,
    val yourCards: List<AssignedCard>,
    val communityCards: List<AssignedCard>
) : RoundTableRequest {
    init {
        requireThat {
            "yourCards must be in the hashes" using (yourCards.all { cardHashes.contains(it.hash) })
            "communityCards must be in the hashes" using (communityCards.all { cardHashes.contains(it.hash) })
            "There must be at least ${HashedCardDeckInfo.CARDS_PER_PLAYER} cards"
                .using(yourCards.size >= HashedCardDeckInfo.CARDS_PER_PLAYER)
            "Your wager cannot be higher than the last raise" using (yourWager <= lastRaise)
            "Cards must be of the same assignee" using (yourCards.map { it.owner }.toSet().size == 1)
        }
    }
}
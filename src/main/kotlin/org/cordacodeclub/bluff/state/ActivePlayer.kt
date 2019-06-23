package org.cordacodeclub.bluff.state

import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable

@CordaSerializable
data class ActivePlayer(val party: Party, val folded: Boolean) {

    constructor(party: Party) : this(party, false)

    companion object {
        fun from(parties: List<Party>) = parties.map { ActivePlayer(it) }
    }
}

fun List<ActivePlayer>.foldedParties() = filter { it.folded }.map { it.party }
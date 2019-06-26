package org.cordacodeclub.bluff.state

import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import org.cordacodeclub.bluff.player.PlayerAction

@CordaSerializable
data class PlayedAction(val player: Party, val action: PlayerAction) {

    constructor(party: Party) : this(party, PlayerAction.Missing)

    companion object {
        fun from(parties: List<Party>) = parties.map { PlayedAction(it) }
    }
}

fun List<PlayedAction>.foldedParties() = filter { it.action == PlayerAction.Fold }.map { it.player }.toSet()
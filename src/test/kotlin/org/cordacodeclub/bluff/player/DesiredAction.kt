package org.cordacodeclub.bluff.player

import net.corda.core.serialization.CordaSerializable
import org.cordacodeclub.bluff.player.PlayerAction

@CordaSerializable
data class DesiredAction(val playerAction: PlayerAction, val raiseBy: Long) {

    constructor(playerAction: PlayerAction) : this(playerAction, 0)
}
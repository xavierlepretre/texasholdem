package org.cordacodeclub.bluff.player

import net.corda.core.serialization.CordaSerializable

@CordaSerializable
enum class PlayerAction {
    Missing,
    Call,
    Raise,
    Fold
}
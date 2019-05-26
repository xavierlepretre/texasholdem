package org.cordacodeclub.bluff.flow

import net.corda.core.serialization.CordaSerializable

@CordaSerializable
data class DesiredAction(val action: Action, val raiseBy: Long) {

    constructor(action: Action) : this(action, 0)
}
package org.cordacodeclub.bluff.player

import org.cordacodeclub.bluff.round.CallOrRaiseRequest

interface PlayerResponder {
    fun getAction(request: CallOrRaiseRequest): ActionRequest
}
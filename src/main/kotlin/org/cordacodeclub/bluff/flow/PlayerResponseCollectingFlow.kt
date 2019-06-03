package org.cordacodeclub.bluff.flow

import net.corda.core.flows.FlowLogic
import org.cordacodeclub.bluff.player.ActionRequest
import org.cordacodeclub.bluff.round.CallOrRaiseRequest

/**
 * This flow is started by a player party to collect the human's decision.
 * @param request the request made to the player
 */
abstract class PlayerResponseCollectingFlow(val request: CallOrRaiseRequest) : FlowLogic<ActionRequest>()
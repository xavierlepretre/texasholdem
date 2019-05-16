package org.cordacodeclub.bluff.state

import net.corda.core.identity.Party

data class ActivePlayer(val party: Party, val folded: Boolean)
package org.cordacodeclub.bluff.state

import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import org.cordacodeclub.grom356.Card

@CordaSerializable
interface AssignedCard {
    val card: Card?
    val encrytedCard : String?
    val owner: Party
}
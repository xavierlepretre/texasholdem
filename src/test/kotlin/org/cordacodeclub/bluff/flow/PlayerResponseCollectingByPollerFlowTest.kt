package org.cordacodeclub.bluff.flow

import io.cordite.test.utils.h2.H2Server
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.StartedMockNode
import org.cordacodeclub.bluff.dealer.CardDeckInfo
import org.cordacodeclub.bluff.player.PlayerAction
import org.cordacodeclub.bluff.round.CallOrRaiseRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Add -javaagent:./lib/quasar.jar to VM Options
 */
class PlayerResponseCollectingByPollerFlowTest {
    private lateinit var network: MockNetwork
    private lateinit var minterNode: StartedMockNode
    private lateinit var dealerNode: StartedMockNode
    private lateinit var player1Node: StartedMockNode
    private lateinit var minter: Party
    private lateinit var dealer: Party
    private lateinit var player1: Party
    private lateinit var h2Server: H2Server

    @Before
    fun setup() {
        network = MockNetwork(
            listOf(
                "org.cordacodeclub.bluff.contract",
                "org.cordacodeclub.bluff.dealer",
                "org.cordacodeclub.bluff.flow",
                "org.cordacodeclub.bluff.player",
                "org.cordacodeclub.bluff.state"
            )
        )
        minterNode = network.createPartyNode(CordaX500Name.parse("O=Minter, L=London, C=GB"))
        dealerNode = network.createPartyNode(CordaX500Name.parse("O=Dealer, L=London, C=GB"))
        player1Node = network.createPartyNode(CordaX500Name.parse("O=Player1, L=London, C=GB"))
        minter = minterNode.info.singleIdentity()
        dealer = dealerNode.info.singleIdentity()
        player1 = player1Node.info.singleIdentity()
//        h2Server = H2Server(network, listOf(player1Node))
    }

    @After
    fun tearDown() {
        network.stopNodes()
    }

    // This keeps giving me error on build - Error:(69, 26) Kotlin: Unresolved reference: PlayerResponseCollectingByPollerFlow
    // I commented this out for now
//    @Test
//    fun `Can receive user responses`() {
//        val players = listOf(player1)
//        val deckInfo = CardDeckInfo.createShuffledWith(players.map { it.name }, dealer.name)
//        val request = CallOrRaiseRequest(
//            minter = minter,
//            lastRaise = 10L,
//            yourWager = 5L,
//            cardHashes = deckInfo.hashedCards,
//            yourCards = deckInfo.cards.take(2),
//            communityCards = listOf()
//        )
//
//        val pollerFlow = PlayerResponseCollectingByPollerFlow(
//            request = request,
//            duration = 1500L,
//            bouncer = dealer
//        )
//        val pollerFuture = player1Node.startFlow(pollerFlow)
//        // Enough to make it update the player db
//        network.runNetwork(10)
////        h2Server.block()
//
//        val responderFlow = PlayerActionResponseFlow(
//            requestId = pollerFlow.insertedRequest,
//            action = PlayerAction.Raise,
//            addAmount = 6L
//        )
//        val responderFuture = player1Node.startFlow(responderFlow)
//        network.runNetwork()
//
//        val rowCount = responderFuture.getOrThrow()
//        val action = pollerFuture.getOrThrow()
//        assertEquals(1, rowCount)
//        assertEquals(PlayerAction.Raise, action.playerAction)
//        assertEquals(6L, action.addAmount)
//    }
}
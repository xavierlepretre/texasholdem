package org.cordacodeclub.bluff.flow

import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.StartedMockNode
import org.cordacodeclub.bluff.round.CallOrRaiseRequest
import org.cordacodeclub.bluff.state.AssignedCard
import org.cordacodeclub.grom356.Card
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Add -javaagent:./lib/quasar.jar to VM Options
 */
class SingleRequestFlowTest {
    private lateinit var network: MockNetwork
    private lateinit var player1Node: StartedMockNode
    private lateinit var player2Node: StartedMockNode
    private lateinit var player1: Party
    private lateinit var player2: Party

    @Before
    fun setup() {
        network = MockNetwork(
            listOf(
                "org.cordacodeclub.bluff.contract",
                "org.cordacodeclub.bluff.dealer",
                "org.cordacodeclub.bluff.flow",
                "org.cordacodeclub.bluff.state"
            )
        )
        player1Node = network.createPartyNode(CordaX500Name.parse("O=Player1, L=London, C=GB"))
        player2Node = network.createPartyNode(CordaX500Name.parse("O=Player2, L=London, C=GB"))
        player1 = player1Node.info.singleIdentity()
        player2 = player2Node.info.singleIdentity()

        // For real nodes this happens automatically, but we have to manually register the flow for tests.
        listOf(player1Node, player2Node).forEach {
            it.registerInitiatedFlow(SingleRequestFlow.FoldResponder::class.java)
        }
        network.runNetwork()
    }

    @After
    fun tearDown() {
        network.stopNodes()
    }

    @Test
    fun `Can receive response`() {
        val flow = SingleRequestFlow.Initiator(
            player2,
            CallOrRaiseRequest(
                minter = player1,
                lastRaise = 100L,
                yourWager = 0L,
                yourCards = listOf(
                    AssignedCard(Card.valueOf("2h"), "Hello1".toByteArray(), player2.name),
                    AssignedCard(Card.valueOf("2s"), "Hello2".toByteArray(), player2.name)
                ),
                communityCards = listOf()
            )
        )
        val future = player1Node.startFlow(flow)
        network.runNetwork()

        val response = future.getOrThrow()
        assertTrue(response.isFold)
    }
}
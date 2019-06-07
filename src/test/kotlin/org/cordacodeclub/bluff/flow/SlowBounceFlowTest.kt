package org.cordacodeclub.bluff.flow

import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.StartedMockNode
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Add -javaagent:./lib/quasar.jar to VM Options
 */
class SlowBounceFlowTest {
    private lateinit var network: MockNetwork
    private lateinit var minterNode: StartedMockNode
    private lateinit var player1Node: StartedMockNode
    private lateinit var player2Node: StartedMockNode
    private lateinit var minter: Party
    private lateinit var player1: Party
    private lateinit var player2: Party

    @Before
    fun setup() {
        network = MockNetwork(
            listOf(
                "org.cordacodeclub.bluff.contract",
                "org.cordacodeclub.bluff.flow",
                "org.cordacodeclub.bluff.state"
            )
        )
        minterNode = network.createPartyNode(CordaX500Name.parse("O=Minter, L=London, C=GB"))
        player1Node = network.createPartyNode(CordaX500Name.parse("O=Player 1, L=London, C=GB"))
        player2Node = network.createPartyNode(CordaX500Name.parse("O=Player 2, L=London, C=GB"))
        minter = minterNode.info.singleIdentity()
        player1 = player1Node.info.singleIdentity()
        player2 = player2Node.info.singleIdentity()

        // For real nodes this happens automatically, but we have to manually register the flow for tests.
        listOf(minterNode, player1Node, player2Node).forEach {
            it.registerInitiatedFlow(MintTokenFlow.Recipient::class.java)
        }
        network.runNetwork()
    }

    @After
    fun tearDown() {
        network.stopNodes()
    }

    @Test
    fun `It takes a second to receive a response`() {
        val flow = SlowBounceFlow.Initiator(player1, 1000L)
        val future = minterNode.startFlow(flow)
        network.runNetwork()

        future.getOrThrow()
    }

    @Test
    fun `It is possible for the initiator to run another process while bounced off`() {
        val bounceFlow = SlowBounceFlow.Initiator(player1, 2000L)
        val mintFlow = MintTokenFlow.Minter(listOf(player2), 10, 1)
        val bounceFuture = minterNode.startFlow(bounceFlow)
        network.runNetwork()
        val mintFuture = minterNode.startFlow(mintFlow)
        network.runNetwork()

        mintFuture.getOrThrow()
        bounceFuture.getOrThrow()
    }
}
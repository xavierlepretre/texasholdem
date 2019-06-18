package org.cordacodeclub.bluff.flow

import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.StartedMockNode
import org.cordacodeclub.bluff.state.TokenState
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Add -javaagent:./lib/quasar.jar to VM Options
 */
class CollectOwnTokenStateFlowTest {
    private lateinit var network: MockNetwork
    private lateinit var minterNode: StartedMockNode
    private lateinit var player1Node: StartedMockNode
    private lateinit var player2Node: StartedMockNode
    private lateinit var minter: Party
    private lateinit var player1: Party
    private lateinit var mintTx: SignedTransaction

    @Before
    fun setup() {
        network = MockNetwork(
            listOf(
                "org.cordacodeclub.bluff.contract",
                "org.cordacodeclub.bluff.flow",
                "org.cordacodeclub.bluff.state"
            )
        )
        minterNode = network.createPartyNode()
        player1Node = network.createPartyNode()
        player2Node = network.createPartyNode()
        minter = minterNode.info.singleIdentity()
        player1 = player1Node.info.singleIdentity()

        // For real nodes this happens automatically, but we have to manually register the flow for tests.
        listOf(minterNode, player1Node, player2Node).forEach {
            it.registerInitiatedFlow(MintTokenFlow.Recipient::class.java)
        }
        network.runNetwork()
        val mintFlow = MintTokenFlow.Minter(listOf(player1), 10, 10)
        val mintFuture = minterNode.startFlow(mintFlow)
        network.runNetwork()
        mintTx = mintFuture.getOrThrow()

    }

    @After
    fun tearDown() {
        network.stopNodes()
    }

    @Test
    fun `Easy collection of 6 states`() {
        val flow = CollectOwnTokenStateFlow(TokenState(minter, player1, 60, false))
        val future = player1Node.startFlow(flow)
        network.runNetwork()

        val states = future.getOrThrow()
        assertEquals(6, states.size)
        assertTrue(states.all { it.state.data.amount == 10L })
    }

    @Test
    fun `Collection with split states`() {
        val flow = CollectOwnTokenStateFlow(TokenState(minter, player1, 46, false))
        val future = player1Node.startFlow(flow)
        network.runNetwork()

        val states = future.getOrThrow()
        assertEquals(5, states.size)
        assertEquals(46, states.map { it.state.data.amount }.sum())
        assertEquals(2, states.map { it.ref.txhash }.toSet().size)
        assertEquals(states.last().state.data.amount, 6L)
    }
}
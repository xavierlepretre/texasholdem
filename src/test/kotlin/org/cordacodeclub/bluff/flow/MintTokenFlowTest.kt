package org.cordacodeclub.bluff.flow

import net.corda.core.identity.Party
import net.corda.core.internal.toMultiMap
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.StartedMockNode
import org.cordacodeclub.bluff.state.TokenState
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Add -javaagent:./lib/quasar.jar to VM Options
 */
class MintTokenFlowTest {
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
        minterNode = network.createPartyNode()
        player1Node = network.createPartyNode()
        player2Node = network.createPartyNode()
        minter = minterNode.info.singleIdentity()
        player1 = player1Node.info.singleIdentity()
        player2 = player2Node.info.singleIdentity()

        // For real nodes this happens automatically, but we have to manually register the flow for tests.
        listOf(minterNode, player2Node, player1Node).forEach {
            it.registerInitiatedFlow(MintTokenFlow.Recipient::class.java)
        }
        network.runNetwork()
    }

    @After
    fun tearDown() {
        network.stopNodes()
    }

    @Test
    fun `SignedTransaction is signed only by minter`() {
        val flow = MintTokenFlow.Minter(listOf(player1, player2), 100)
        val future = minterNode.startFlow(flow)
        network.runNetwork()

        val signedTx = future.getOrThrow()
        assertEquals(minter.owningKey, signedTx.sigs.single().by)
    }

    @Test
    fun `SignedTransaction is received by both players`() {
        val flow = MintTokenFlow.Minter(listOf(player1, player2), 100)
        val future = minterNode.startFlow(flow)
        network.runNetwork()

        val signedTx = future.getOrThrow()
        for (node in listOf(player1Node, player2Node)) {
            assertEquals(signedTx, node.services.validatedTransactions.getTransaction(signedTx.id))
        }
    }

    @Test
    fun `SignedTransaction has 100 outputs of 1 per player`() {
        val flow = MintTokenFlow.Minter(listOf(player1, player2), 100)
        val future = minterNode.startFlow(flow)
        network.runNetwork()

        val signedTx = future.getOrThrow()
        assertTrue(signedTx.inputs.isEmpty())
        val outputs = signedTx.tx.outputs
        assertTrue(outputs.all { it.data is TokenState })
        outputs.map { it.data as TokenState }.map { it.owner to it }.toMultiMap()
            .forEach { owner, states ->
                assertEquals(100, states.size)
                states.forEach {
                    assertEquals(1, it.amount)
                    assertEquals(minter, it.minter)
                    assertFalse(it.isPot)
                }
            }
    }
}
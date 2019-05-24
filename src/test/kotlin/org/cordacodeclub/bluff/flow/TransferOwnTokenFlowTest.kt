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
class TransferOwnTokenFlowTest {
    private lateinit var network: MockNetwork
    private lateinit var minterNode: StartedMockNode
    private lateinit var player1Node: StartedMockNode
    private lateinit var player2Node: StartedMockNode
    private lateinit var minter: Party
    private lateinit var player1: Party
    private lateinit var player2: Party
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
        player2 = player2Node.info.singleIdentity()

        // For real nodes this happens automatically, but we have to manually register the flow for tests.
        listOf(minterNode, player1Node, player2Node).forEach {
            it.registerInitiatedFlow(MintTokenFlow.Recipient::class.java)
        }
        network.runNetwork()
        val mintFlow = MintTokenFlow.Minter(listOf(player1, player2), 10, 10)
        val mintFuture = minterNode.startFlow(mintFlow)
        network.runNetwork()
        mintTx = mintFuture.getOrThrow()

    }

    @After
    fun tearDown() {
        network.stopNodes()
    }

    @Test
    fun `SignedTransaction is signed only by owner and notary`() {
        val player1States = mintTx.tx.outRefsOfType<TokenState>().take(3)
        val flow = TransferOwnTokenFlow(player1States, listOf(5, 8, 17))
        val future = player1Node.startFlow(flow)
        network.runNetwork()

        val signedTx = future.getOrThrow()
        assertEquals(2, signedTx.sigs.size)
        assertTrue(signedTx.sigs.map { it.by }.contains(player1.owningKey))
    }

    @Test
    fun `SignedTransaction has 3 outputs of 5, 8, and 17`() {
        val player1States = mintTx.tx.outRefsOfType<TokenState>().take(3)
        val flow = TransferOwnTokenFlow(player1States, listOf(5, 8, 17))
        val future = player1Node.startFlow(flow)
        network.runNetwork()

        val signedTx = future.getOrThrow()
        assertEquals(3, signedTx.inputs.size)
        val outputs = signedTx.tx.outputs
        assertEquals(3, outputs.size)
        assertTrue(outputs.all { it.data is TokenState })
        val newStates = outputs.map { it.data as TokenState }
        assertEquals(minter, newStates.map { it.minter }.toSet().single())
        assertEquals(player1, newStates.map { it.owner }.toSet().single())
        assertTrue(newStates.all { !it.isPot })
        println(newStates.map { it.amount }.toSet())
        assertTrue { setOf(5L, 8L, 17L) == newStates.map { it.amount }.toSet() }
    }
}
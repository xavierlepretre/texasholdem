package org.cordacodeclub.bluff.flow

import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.StartedMockNode
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Add -javaagent:./lib/quasar.jar to VM Options
 */
class EndGameFlowTest {
    private lateinit var network: MockNetwork
    private lateinit var minterNode: StartedMockNode
    private lateinit var dealerNode: StartedMockNode
    private lateinit var player1Node: StartedMockNode
    private lateinit var player2Node: StartedMockNode
    private lateinit var player3Node: StartedMockNode
    private lateinit var player4Node: StartedMockNode
    private lateinit var minter: Party
    private lateinit var dealer: Party
    private lateinit var player1: Party
    private lateinit var player2: Party
    private lateinit var player3: Party
    private lateinit var player4: Party
    private lateinit var mintTx: SignedTransaction

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
        minterNode = network.createPartyNode(CordaX500Name.parse("O=Minter, L=London, C=GB"))
        dealerNode = network.createPartyNode(CordaX500Name.parse("O=Dealer, L=London, C=GB"))
        player1Node = network.createPartyNode(CordaX500Name.parse("O=Player1, L=London, C=GB"))
        player2Node = network.createPartyNode(CordaX500Name.parse("O=Player2, L=London, C=GB"))
        player3Node = network.createPartyNode(CordaX500Name.parse("O=Player3, L=London, C=GB"))
        player4Node = network.createPartyNode(CordaX500Name.parse("O=Player4, L=London, C=GB"))
        minter = minterNode.info.singleIdentity()
        dealer = dealerNode.info.singleIdentity()
        player1 = player1Node.info.singleIdentity()
        player2 = player2Node.info.singleIdentity()
        player3 = player3Node.info.singleIdentity()
        player4 = player4Node.info.singleIdentity()

        // For real nodes this happens automatically, but we have to manually register the flow for tests.
        listOf(minterNode, player1Node, player2Node, player3Node, player4Node).forEach {
            it.registerInitiatedFlow(MintTokenFlow.Recipient::class.java)
            it.registerInitiatedFlow(BlindBetFlow.CollectorAndSigner::class.java)
        }
        val mintFlow = MintTokenFlow.Minter(listOf(player1, player2, player3, player4), 100, 1)
        val future = minterNode.startFlow(mintFlow)
        network.runNetwork()
        mintTx = future.getOrThrow()
    }
    @After
    fun tearDown() {
        network.stopNodes()
    }

    @Test
    fun `Can run flow`() {

        val blindBetflow = BlindBetFlow.Initiator(
                listOf(player1, player2, player3, player4), minter,4)
        val blindBetFuture = dealerNode.startFlow(blindBetflow)
        network.runNetwork()
        val signedTx = blindBetFuture.getOrThrow()

        val endGameFlow = EndGameFlow.Initiator(listOf(player1, player2, player3, player4), signedTx.tx.id)
        val endGameFuture = dealerNode.startFlow(endGameFlow)
        network.runNetwork()

        val finalSignedTx = endGameFuture.getOrThrow()
        finalSignedTx.sigs.map { it.by }.toSet().also {
            assertTrue(it.contains(player1.owningKey))
            assertTrue(it.contains(player2.owningKey))
            assertFalse(it.contains(player3.owningKey))
            assertFalse(it.contains(player4.owningKey))
        }
    }

}
package org.cordacodeclub.bluff.flow

import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.StartedMockNode
import org.cordacodeclub.bluff.player.PlayerAction
import org.cordacodeclub.bluff.player.PlayerDatabaseService
import org.cordacodeclub.bluff.state.BettingRound
import org.cordacodeclub.bluff.state.GameState
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Add -javaagent:./lib/quasar.jar to VM Options
 */
class RoundGameFlowTest {
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
    private lateinit var blindBetTx: SignedTransaction

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
        val mintFuture = minterNode.startFlow(mintFlow)
        val blindBetFlow = BlindBetFlow.Initiator(listOf(player1, player2, player3, player4), minter, 4)
        val blindBetFuture = dealerNode.startFlow(blindBetFlow)
        network.runNetwork()
        mintTx = mintFuture.getOrThrow()
        blindBetTx = blindBetFuture.getOrThrow()
    }

    @After
    fun tearDown() {
        // TODO remove this try catch
        try {
            network.stopNodes()
        } catch (e: Throwable) {
            println(e)
        }
    }

    private fun replyWith(node: StartedMockNode, playerAction: PlayerAction, addAmount: Long) {
        val playerService = node.services.cordaService(PlayerDatabaseService::class.java)
        var done = false
        do {
            Thread.sleep(1000)
            println("replyWith 1 ${node.info.singleIdentity()}")
            val request = node.transaction { playerService.getTopActionRequest() }
            if (request != null) {
                println("replyWith 2 ${node.info.singleIdentity()}")
                node.transaction { playerService.updateActionRequest(request.id, playerAction, addAmount) }
                done = true
            }
            println("replyWith 3 ${node.info.singleIdentity()}")
        } while (!done)
    }

    @Test
    fun `Round where all call`() {
        val flow = RoundGameFlow.GameCreator(blindBetTx.id)
        val future = dealerNode.startFlow(flow)
        network.runNetwork(10)

        // Update on players
        replyWith(player3Node, PlayerAction.Call, 0)
        network.runNetwork(10)
        replyWith(player4Node, PlayerAction.Call, 0)
        network.runNetwork(10)
        replyWith(player1Node, PlayerAction.Call, 0)
        network.runNetwork(10)
        replyWith(player2Node, PlayerAction.Call, 0)

        network.runNetwork(30)
        val signedTx = future.getOrThrow()!!
        signedTx.sigs.map { it.by }.toSet().also {
            assertTrue(it.contains(player1.owningKey))
            assertFalse(it.contains(player2.owningKey))
            assertTrue(it.contains(player3.owningKey))
            assertTrue(it.contains(player4.owningKey))
        }
        val gameState = signedTx.coreTransaction.outputsOfType<GameState>().single()
        assertEquals(BettingRound.PRE_FLOP, gameState.bettingRound)
        assertEquals(1, gameState.lastBettor)
    }

    @Test
    fun `Second round where all call`() {
        val preFlopFlow = RoundGameFlow.GameCreator(blindBetTx.id)
        val preFlopFuture = dealerNode.startFlow(preFlopFlow)
        network.runNetwork(10)

        // Update on players
        replyWith(player3Node, PlayerAction.Call, 0)
        network.runNetwork(10)
        replyWith(player4Node, PlayerAction.Call, 0)
        network.runNetwork(10)
        replyWith(player1Node, PlayerAction.Call, 0)
        network.runNetwork(10)
        replyWith(player2Node, PlayerAction.Call, 0)

        network.runNetwork(30)
        val preFlopSignedTx = preFlopFuture.getOrThrow()!!

        val flopFlow = RoundGameFlow.GameCreator(preFlopSignedTx.id)
        val flopFuture = dealerNode.startFlow(flopFlow)
        network.runNetwork(30)

        // Update on players
        replyWith(player3Node, PlayerAction.Raise, 10)
        network.runNetwork(30)
        replyWith(player4Node, PlayerAction.Call, 0)
        network.runNetwork(30)
        replyWith(player1Node, PlayerAction.Call, 0)
        network.runNetwork(30)
        replyWith(player2Node, PlayerAction.Call, 0)

        network.runNetwork(30)
        val flopSignedTx = flopFuture.getOrThrow()!!

        flopSignedTx.sigs.map { it.by }.toSet().also {
            assertTrue(it.contains(player1.owningKey))
            assertTrue(it.contains(player2.owningKey))
            assertTrue(it.contains(player3.owningKey))
            assertTrue(it.contains(player4.owningKey))
        }
        val gameState = flopSignedTx.coreTransaction.outputsOfType<GameState>().single()
        assertEquals(BettingRound.FLOP, gameState.bettingRound)
    }
}
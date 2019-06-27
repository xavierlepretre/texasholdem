package org.cordacodeclub.bluff.flow

import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowException
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.internal.toMultiMap
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.StartedMockNode
import org.cordacodeclub.bluff.player.PlayerAction
import org.cordacodeclub.bluff.state.AssignedCard
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Add -javaagent:./lib/quasar.jar to VM Options
 */
class CardRevealFlowTest {
    private lateinit var network: MockNetwork
    private lateinit var minterNode: StartedMockNode
    private lateinit var dealerNode: StartedMockNode
    private lateinit var player0Node: StartedMockNode
    private lateinit var player1Node: StartedMockNode
    private lateinit var player2Node: StartedMockNode
    private lateinit var player3Node: StartedMockNode
    private lateinit var minter: Party
    private lateinit var dealer: Party
    private lateinit var player0: Party
    private lateinit var player1: Party
    private lateinit var player2: Party
    private lateinit var player3: Party
    private lateinit var mintTx: SignedTransaction
    private lateinit var blindBet1Tx: SignedTransaction
    private lateinit var blindBet2Tx: SignedTransaction

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
        player0Node = network.createPartyNode(CordaX500Name.parse("O=Player0, L=London, C=GB"))
        player1Node = network.createPartyNode(CordaX500Name.parse("O=Player1, L=London, C=GB"))
        player2Node = network.createPartyNode(CordaX500Name.parse("O=Player2, L=London, C=GB"))
        player3Node = network.createPartyNode(CordaX500Name.parse("O=Player3, L=London, C=GB"))
        minter = minterNode.info.singleIdentity()
        dealer = dealerNode.info.singleIdentity()
        player0 = player0Node.info.singleIdentity()
        player1 = player1Node.info.singleIdentity()
        player2 = player2Node.info.singleIdentity()
        player3 = player3Node.info.singleIdentity()

        val players = listOf(player0, player1, player2, player3)
        val mintFlow = MintTokenFlow.Minter(players, 100, 1)
        val mintFuture = minterNode.startFlow(mintFlow)
        network.runNetwork()
        mintTx = mintFuture.getOrThrow()
        val blindBet1Flow = BlindBet1OneStepFlow.Initiator(players, minter, dealer, 4)
        val blindBet1Future = player0Node.startFlow(blindBet1Flow)
        network.runNetwork()
        blindBet1Tx = blindBet1Future.getOrThrow()
        val blindBet2Flow = BlindBet2OneStepFlow.Initiator(blindBet1Tx.id, 4)
        val blindBet2Future = player1Node.startFlow(blindBet2Flow)
        network.runNetwork()
        blindBet2Tx = blindBet2Future.getOrThrow()
    }

    @After
    fun tearDown() {
        network.stopNodes()
    }

    private fun SignedTransaction.thenPlay(
        who: StartedMockNode,
        action: PlayerAction,
        addAmount: Long
    ): SignedTransaction {
        val flow = PlayOneStepFlow.Initiator(this.id, action, addAmount)
        val future = who.startFlow(flow)
        network.runNetwork()
        return future.getOrThrow()
    }

    @Test
    fun `CardRevealFlow fails with unknown transaction hash`() {
        val flow = CardRevealFlow.Initiator(SecureHash.zeroHash)
        val future = player2Node.startFlow(flow)
        network.runNetwork()
        assertFailsWith<FlowException>("Unknown transaction hash") {
            future.getOrThrow()
        }
    }

    @Test
    fun `CardRevealFlow fails with wrong transaction type`() {
        val flow = CardRevealFlow.Initiator(mintTx.id)
        val future = player2Node.startFlow(flow)
        network.runNetwork()
        assertFailsWith<FlowException>("Transaction does not contain a single RoundState") {
            future.getOrThrow()
        }
    }

    @Test
    fun `Deck is returned with 0 cards when blind bet 1 from any player`() {
        listOf(player0Node, player1Node, player2Node, player3Node).forEach {
            val flow = CardRevealFlow.Initiator(blindBet1Tx.id)
            val future = it.startFlow(flow)
            network.runNetwork()
            assertTrue(future.getOrThrow().isEmpty)
        }
    }

    @Test
    fun `Deck is returned with only player cards when blind bet 2 from any player`() {
        listOf(player0Node, player1Node, player2Node, player3Node).forEach { node ->
            val flow = CardRevealFlow.Initiator(blindBet2Tx.id)
            val future = node.startFlow(flow)
            network.runNetwork()
            val deck = future.getOrThrow()
            assertEquals(2, deck.cards.count { it != null })
            deck.cards
                .filter { it != null }
                .also { assertEquals(2, it.size) }
                .map { it as AssignedCard }
                .forEach { assertEquals(it.owner, node.info.singleIdentity().name) }
        }
    }

    @Test
    fun `Deck is returned with only player cards when first Pre_flop from any player`() {
        val signedTx = blindBet2Tx.thenPlay(player2Node, PlayerAction.Call, 0)
        listOf(player0Node, player1Node, player2Node, player3Node).forEach { node ->
            val flow = CardRevealFlow.Initiator(signedTx.id)
            val future = node.startFlow(flow)
            network.runNetwork()
            val deck = future.getOrThrow()
            deck.cards
                .filter { it != null }
                .also { assertEquals(2, it.size) }
                .map { it as AssignedCard }
                .forEach { assertEquals(it.owner, node.info.singleIdentity().name) }
        }
    }

    @Test
    fun `Deck is returned with only player cards when last Pre_flop from any player`() {
        val signedTx = blindBet2Tx
            // Entering Pre_flop
            .thenPlay(player2Node, PlayerAction.Call, 0)
            .thenPlay(player3Node, PlayerAction.Call, 0)
        listOf(player0Node, player1Node, player2Node, player3Node).forEach { node ->
            val flow = CardRevealFlow.Initiator(signedTx.id)
            val future = node.startFlow(flow)
            network.runNetwork()
            val deck = future.getOrThrow()
            deck.cards
                .filter { it != null }
                .also { assertEquals(2, it.size) }
                .map { it as AssignedCard }
                .forEach { assertEquals(it.owner, node.info.singleIdentity().name) }
        }
    }

    @Test
    fun `Deck is returned with player cards and 3 community cards when first Flop from any player`() {
        val signedTx = blindBet2Tx
            // Entering Pre_flop
            .thenPlay(player2Node, PlayerAction.Call, 0)
            .thenPlay(player3Node, PlayerAction.Call, 0)
            .thenPlay(player0Node, PlayerAction.Call, 0)
            .thenPlay(player1Node, PlayerAction.Call, 0)
        // Next one would enter Flop
        listOf(player0Node, player1Node, player2Node, player3Node).forEach { node ->
            val flow = CardRevealFlow.Initiator(signedTx.id)
            val future = node.startFlow(flow)
            network.runNetwork()
            val deck = future.getOrThrow()
            deck.cards
                .filter { it != null }
                .also { assertEquals(5, it.size) }
                .map { it as AssignedCard }
                .map { it.owner to it }
                .toMultiMap()
                .forEach {
                    if (it.key == node.info.singleIdentity().name) assertEquals(2, it.value.size)
                    else assertEquals(dealer.name, it.key)
                }
        }
    }

    @Test
    fun `Deck is returned with player cards and 3 community cards when last Flop from any player`() {
        val signedTx = blindBet2Tx
            // Entering Pre_flop
            .thenPlay(player2Node, PlayerAction.Call, 0)
            .thenPlay(player3Node, PlayerAction.Call, 0)
            .thenPlay(player0Node, PlayerAction.Call, 0)
            .thenPlay(player1Node, PlayerAction.Call, 0)
            // Entering Flop
            .thenPlay(player2Node, PlayerAction.Call, 0)
            .thenPlay(player3Node, PlayerAction.Call, 0)
            .thenPlay(player0Node, PlayerAction.Call, 0)
        listOf(player0Node, player1Node, player2Node, player3Node).forEach { node ->
            val flow = CardRevealFlow.Initiator(signedTx.id)
            val future = node.startFlow(flow)
            network.runNetwork()
            val deck = future.getOrThrow()
            deck.cards
                .filter { it != null }
                .also { assertEquals(5, it.size) }
                .map { it as AssignedCard }
                .map { it.owner to it }
                .toMultiMap()
                .forEach {
                    if (it.key == node.info.singleIdentity().name) assertEquals(2, it.value.size)
                    else assertEquals(dealer.name, it.key)
                }
        }
    }

    @Test
    fun `Deck is returned with player cards and 3 community cards when first Turn from any player`() {
        val signedTx = blindBet2Tx
            // Entering Pre_flop
            .thenPlay(player2Node, PlayerAction.Call, 0)
            .thenPlay(player3Node, PlayerAction.Call, 0)
            .thenPlay(player0Node, PlayerAction.Call, 0)
            .thenPlay(player1Node, PlayerAction.Call, 0)
            // Entering Flop
            .thenPlay(player2Node, PlayerAction.Call, 0)
            .thenPlay(player3Node, PlayerAction.Call, 0)
            .thenPlay(player0Node, PlayerAction.Call, 0)
            .thenPlay(player1Node, PlayerAction.Call, 0)
        // Next one would enter Turn
        listOf(player0Node, player1Node, player2Node, player3Node).forEach { node ->
            val flow = CardRevealFlow.Initiator(signedTx.id)
            val future = node.startFlow(flow)
            network.runNetwork()
            val deck = future.getOrThrow()
            deck.cards
                .filter { it != null }
                .also { assertEquals(6, it.size) }
                .map { it as AssignedCard }
                .map { it.owner to it }
                .toMultiMap()
                .forEach {
                    if (it.key == node.info.singleIdentity().name) assertEquals(2, it.value.size)
                    else assertEquals(dealer.name, it.key)
                }
        }
    }

    @Test
    fun `Deck is returned with player cards and 3 community cards when last Turn from any player`() {
        val signedTx = blindBet2Tx
            // Entering Pre_flop
            .thenPlay(player2Node, PlayerAction.Call, 0)
            .thenPlay(player3Node, PlayerAction.Call, 0)
            .thenPlay(player0Node, PlayerAction.Call, 0)
            .thenPlay(player1Node, PlayerAction.Call, 0)
            // Entering Flop
            .thenPlay(player2Node, PlayerAction.Call, 0)
            .thenPlay(player3Node, PlayerAction.Call, 0)
            .thenPlay(player0Node, PlayerAction.Call, 0)
            .thenPlay(player1Node, PlayerAction.Call, 0)
            // Entering Turn
            .thenPlay(player2Node, PlayerAction.Call, 0)
            .thenPlay(player3Node, PlayerAction.Call, 0)
            .thenPlay(player0Node, PlayerAction.Call, 0)
        listOf(player0Node, player1Node, player2Node, player3Node).forEach { node ->
            val flow = CardRevealFlow.Initiator(signedTx.id)
            val future = node.startFlow(flow)
            network.runNetwork()
            val deck = future.getOrThrow()
            deck.cards
                .filter { it != null }
                .also { assertEquals(6, it.size) }
                .map { it as AssignedCard }
                .map { it.owner to it }
                .toMultiMap()
                .forEach {
                    if (it.key == node.info.singleIdentity().name) assertEquals(2, it.value.size)
                    else assertEquals(dealer.name, it.key)
                }
        }
    }

    @Test
    fun `Deck is returned with player cards and 3 community cards when first River from any player`() {
        val signedTx = blindBet2Tx
            // Entering Pre_flop
            .thenPlay(player2Node, PlayerAction.Call, 0)
            .thenPlay(player3Node, PlayerAction.Call, 0)
            .thenPlay(player0Node, PlayerAction.Call, 0)
            .thenPlay(player1Node, PlayerAction.Call, 0)
            // Entering Flop
            .thenPlay(player2Node, PlayerAction.Call, 0)
            .thenPlay(player3Node, PlayerAction.Call, 0)
            .thenPlay(player0Node, PlayerAction.Call, 0)
            .thenPlay(player1Node, PlayerAction.Call, 0)
            // Entering Turn
            .thenPlay(player2Node, PlayerAction.Call, 0)
            .thenPlay(player3Node, PlayerAction.Call, 0)
            .thenPlay(player0Node, PlayerAction.Call, 0)
            .thenPlay(player1Node, PlayerAction.Call, 0)
        // Next one would enter River
        listOf(player0Node, player1Node, player2Node, player3Node).forEach { node ->
            val flow = CardRevealFlow.Initiator(signedTx.id)
            val future = node.startFlow(flow)
            network.runNetwork()
            val deck = future.getOrThrow()
            deck.cards
                .filter { it != null }
                .also { assertEquals(7, it.size) }
                .map { it as AssignedCard }
                .map { it.owner to it }
                .toMultiMap()
                .forEach {
                    if (it.key == node.info.singleIdentity().name) assertEquals(2, it.value.size)
                    else assertEquals(dealer.name, it.key)
                }
        }
    }

    @Test
    fun `Deck is returned with player cards and 3 community cards when last River from any player`() {
        val signedTx = blindBet2Tx
            // Entering Pre_flop
            .thenPlay(player2Node, PlayerAction.Call, 0)
            .thenPlay(player3Node, PlayerAction.Call, 0)
            .thenPlay(player0Node, PlayerAction.Call, 0)
            .thenPlay(player1Node, PlayerAction.Call, 0)
            // Entering Flop
            .thenPlay(player2Node, PlayerAction.Call, 0)
            .thenPlay(player3Node, PlayerAction.Call, 0)
            .thenPlay(player0Node, PlayerAction.Call, 0)
            .thenPlay(player1Node, PlayerAction.Call, 0)
            // Entering Turn
            .thenPlay(player2Node, PlayerAction.Call, 0)
            .thenPlay(player3Node, PlayerAction.Call, 0)
            .thenPlay(player0Node, PlayerAction.Call, 0)
            .thenPlay(player1Node, PlayerAction.Call, 0)
            // Entering River
            .thenPlay(player2Node, PlayerAction.Call, 0)
            .thenPlay(player3Node, PlayerAction.Call, 0)
            .thenPlay(player0Node, PlayerAction.Call, 0)
        listOf(player0Node, player1Node, player2Node, player3Node).forEach { node ->
            val flow = CardRevealFlow.Initiator(signedTx.id)
            val future = node.startFlow(flow)
            network.runNetwork()
            val deck = future.getOrThrow()
            deck.cards
                .filter { it != null }
                .also { assertEquals(7, it.size) }
                .map { it as AssignedCard }
                .map { it.owner to it }
                .toMultiMap()
                .forEach {
                    if (it.key == node.info.singleIdentity().name) assertEquals(2, it.value.size)
                    else assertEquals(dealer.name, it.key)
                }
        }
    }

    @Test
    fun `Deck is returned with player cards and 3 community cards when first End from any player`() {
        val signedTx = blindBet2Tx
            // Entering Pre_flop
            .thenPlay(player2Node, PlayerAction.Call, 0)
            .thenPlay(player3Node, PlayerAction.Call, 0)
            .thenPlay(player0Node, PlayerAction.Call, 0)
            .thenPlay(player1Node, PlayerAction.Call, 0)
            // Entering Flop
            .thenPlay(player2Node, PlayerAction.Call, 0)
            .thenPlay(player3Node, PlayerAction.Call, 0)
            .thenPlay(player0Node, PlayerAction.Call, 0)
            .thenPlay(player1Node, PlayerAction.Call, 0)
            // Entering Turn
            .thenPlay(player2Node, PlayerAction.Call, 0)
            .thenPlay(player3Node, PlayerAction.Call, 0)
            .thenPlay(player0Node, PlayerAction.Call, 0)
            .thenPlay(player1Node, PlayerAction.Call, 0)
            // Entering River
            .thenPlay(player2Node, PlayerAction.Call, 0)
            .thenPlay(player3Node, PlayerAction.Call, 0)
            .thenPlay(player0Node, PlayerAction.Call, 0)
            .thenPlay(player1Node, PlayerAction.Call, 0)
        // Next one would enter End
        listOf(player0Node, player1Node, player2Node, player3Node).forEach { node ->
            val flow = CardRevealFlow.Initiator(signedTx.id)
            val future = node.startFlow(flow)
            network.runNetwork()
            val deck = future.getOrThrow()
            deck.cards
                .filter { it != null }
                .also { assertEquals(7, it.size) }
                .map { it as AssignedCard }
                .map { it.owner to it }
                .toMultiMap()
                .forEach {
                    if (it.key == node.info.singleIdentity().name) assertEquals(2, it.value.size)
                    else assertEquals(dealer.name, it.key)
                }
        }
    }
}

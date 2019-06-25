package org.cordacodeclub.bluff.flow

import net.corda.core.contracts.ContractState
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.StartedMockNode
import org.cordacodeclub.bluff.dealer.CardDeckDatabaseService
import org.cordacodeclub.bluff.player.PlayerAction
import org.cordacodeclub.bluff.round.BettingRound
import org.cordacodeclub.bluff.state.PlayedAction
import org.cordacodeclub.bluff.state.RoundState
import org.cordacodeclub.bluff.state.TokenState
import org.cordacodeclub.bluff.state.mapPartyToSum
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Add -javaagent:./lib/quasar.jar to VM Options
 */
class BlindBet2OneStepFlowTest {
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
    }

    @After
    fun tearDown() {
        network.stopNodes()
    }

    @Test
    fun `SignedTransaction is signed by blind bet player`() {
        val flow = BlindBet2OneStepFlow.Initiator(blindBet1Tx.id, 8)
        val future = player1Node.startFlow(flow)
        network.runNetwork()

        val signedTx = future.getOrThrow()
        signedTx.sigs.map { it.by }.toSet().also {
            assertFalse(it.contains(dealer.owningKey))
            assertFalse(it.contains(player0.owningKey))
            assertTrue(it.contains(player1.owningKey))
            assertFalse(it.contains(player2.owningKey))
            assertFalse(it.contains(player3.owningKey))
        }
    }

    @Test
    fun `SignedTransaction has token inputs from current player only`() {
        val flow = BlindBet2OneStepFlow.Initiator(blindBet1Tx.id, 7)
        val future = player1Node.startFlow(flow)
        network.runNetwork()

        val signedTx = future.getOrThrow()
        val sum = signedTx.tx.inputs.map {
            player1Node.services.toStateAndRef<ContractState>(it).state.data
        }.filter {
            it is TokenState && !it.isPot
        }.map {
            it as TokenState
        }.map {
            assertEquals(minter, it.minter)
            assertEquals(player1, it.owner)
            it.amount
        }.sum()
        assertEquals(7, sum)
    }

    @Test
    fun `SignedTransaction has 2 outputs of pot TokenState`() {
        val flow = BlindBet2OneStepFlow.Initiator(blindBet1Tx.id, 6)
        val future = player1Node.startFlow(flow)
        network.runNetwork()

        val signedTx = future.getOrThrow()
        val pots = signedTx.tx.outputsOfType<TokenState>().onEach {
            assertEquals(minter, it.minter)
        }.mapPartyToSum()
        assertEquals(2, pots.size)
        assertEquals(4L, pots[player0])
        assertEquals(6L, pots[player1])
    }

    @Test
    fun `SignedTransaction is received by all players and dealer`() {
        val flow = BlindBet2OneStepFlow.Initiator(blindBet1Tx.id, 5)
        val future = player1Node.startFlow(flow)
        network.runNetwork()

        val signedTx = future.getOrThrow()
        for (node in listOf(dealerNode, player0Node, player2Node, player3Node, player1Node)) {
            assertEquals(signedTx, node.services.validatedTransactions.getTransaction(signedTx.id))
        }
    }

    @Test
    fun `SignedTransaction has expected output of RoundState on Call`() {
        val flow = BlindBet2OneStepFlow.Initiator(blindBet1Tx.id, 4)
        val future = player1Node.startFlow(flow)
        network.runNetwork()

        val signedTx = future.getOrThrow()
        val outputs = signedTx.tx.outputsOfType<RoundState>()
        assertEquals(1, outputs.size)
        val deckRootHash = dealerNode.transaction {
            dealerNode.services.cordaService(CardDeckDatabaseService::class.java).getTopDeckRootHashes(1)
        }.single()
        assertEquals(
            RoundState(
                minter = minter,
                dealer = dealer,
                deckRootHash = deckRootHash,
                roundType = BettingRound.BLIND_BET_2,
                currentPlayerIndex = 1,
                players = listOf(
                    PlayedAction(player0, PlayerAction.Raise),
                    PlayedAction(player1, PlayerAction.Call),
                    PlayedAction(player2, PlayerAction.Missing),
                    PlayedAction(player3, PlayerAction.Missing)
                )
            ),
            outputs.single()
        )
    }

    @Test
    fun `SignedTransaction has expected output of RoundState on Raise`() {
        val flow = BlindBet2OneStepFlow.Initiator(blindBet1Tx.id, 5)
        val future = player1Node.startFlow(flow)
        network.runNetwork()

        val signedTx = future.getOrThrow()
        val outputs = signedTx.tx.outputsOfType<RoundState>()
        assertEquals(1, outputs.size)
        val deckRootHash = dealerNode.transaction {
            dealerNode.services.cordaService(CardDeckDatabaseService::class.java).getTopDeckRootHashes(1)
        }.single()
        assertEquals(
            RoundState(
                minter = minter,
                dealer = dealer,
                deckRootHash = deckRootHash,
                roundType = BettingRound.BLIND_BET_2,
                currentPlayerIndex = 1,
                players = listOf(
                    PlayedAction(player0, PlayerAction.Raise),
                    PlayedAction(player1, PlayerAction.Raise),
                    PlayedAction(player2, PlayerAction.Missing),
                    PlayedAction(player3, PlayerAction.Missing)
                )
            ),
            outputs.single()
        )
    }
}

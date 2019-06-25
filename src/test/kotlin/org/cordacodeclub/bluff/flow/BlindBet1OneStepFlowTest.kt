package org.cordacodeclub.bluff.flow

import net.corda.core.contracts.ContractState
import net.corda.core.flows.FlowException
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
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Add -javaagent:./lib/quasar.jar to VM Options
 */
class BlindBet1OneStepFlowTest {
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

        val mintFlow = MintTokenFlow.Minter(listOf(player0, player1, player2, player3), 100, 1)
        val future = minterNode.startFlow(mintFlow)
        network.runNetwork()
        mintTx = future.getOrThrow()
    }

    @After
    fun tearDown() {
        network.stopNodes()
    }

    @Test
    fun `Cannot create BlindBet1OneStepFlow with smallBet of 0`() {
        assertFailsWith<FlowException>("SmallBet should be strictly positive") {
            BlindBet1OneStepFlow.Initiator(
                players = listOf(player0, player1, player2, player3),
                minter = minter, dealer = dealer, smallBet = 0
            )
        }
    }

    @Test
    fun `Cannot create BlindBet1OneStepFlow with too few players`() {
        assertFailsWith<FlowException>("should be at least ${RoundState.MIN_PLAYER_COUNT} players") {
            BlindBet1OneStepFlow.Initiator(
                players = listOf(player0, player1),
                minter = minter, dealer = dealer, smallBet = 4
            )
        }
    }

    @Test
    fun `Cannot create BlindBet1OneStepFlow with dealer as a player`() {
        assertFailsWith<FlowException>("The dealer cannot play") {
            BlindBet1OneStepFlow.Initiator(
                players = listOf(player0, player1, dealer),
                minter = minter, dealer = dealer, smallBet = 4
            )
        }
    }

    @Test
    fun `SignedTransaction is signed by blind bet player and dealer`() {
        val flow = BlindBet1OneStepFlow.Initiator(
            players = listOf(player0, player1, player2, player3),
            minter = minter, dealer = dealer, smallBet = 4
        )
        val future = player1Node.startFlow(flow)
        network.runNetwork()

        val signedTx = future.getOrThrow()
        signedTx.sigs.map { it.by }.toSet().also {
            assertTrue(it.contains(dealer.owningKey))
            assertFalse(it.contains(player0.owningKey))
            assertTrue(it.contains(player1.owningKey))
            assertFalse(it.contains(player2.owningKey))
            assertFalse(it.contains(player3.owningKey))
        }
    }

    @Test
    fun `Card deck is saved by dealer only on signing`() {
        val flow = BlindBet1OneStepFlow.Initiator(
            players = listOf(player0, player1, player2, player3),
            minter = minter, dealer = dealer, smallBet = 4
        )
        val future = player1Node.startFlow(flow)
        network.runNetwork(2)
        val dealerFlow = dealerNode.findStateMachines<BlindBet1OneStepFlow.Responder>(
            BlindBet1OneStepFlow.Responder::class.java
        ).single()
        // Are we past sending the deck back?
        assertEquals(
            BlindBet1OneStepFlow.Responder.Companion.SIGNING_TRANSACTION,
            dealerFlow.first.progressTracker.currentStep
        )
        val deckRootHashesBefore = dealerNode.transaction {
            dealerNode.services.cordaService(CardDeckDatabaseService::class.java).getTopDeckRootHashes(1)
        }
        // No deck saved yet
        assertTrue(deckRootHashesBefore.isEmpty())
        network.runNetwork()
        val deckRootHashesAfter = dealerNode.transaction {
            dealerNode.services.cordaService(CardDeckDatabaseService::class.java).getTopDeckRootHashes(1)
        }
        // Deck is now saved
        assertTrue(deckRootHashesAfter.isNotEmpty())
    }

    @Test
    fun `SignedTransaction has inputs from first player only`() {
        val flow = BlindBet1OneStepFlow.Initiator(
            players = listOf(player0, player1, player2, player3),
            minter = minter, dealer = dealer, smallBet = 4
        )
        val future = player1Node.startFlow(flow)
        network.runNetwork()

        val signedTx = future.getOrThrow()
        val sum = signedTx.tx.inputs.map {
            minterNode.services.toStateAndRef<ContractState>(it).state.data
        }.filter {
            it is TokenState
        }.map {
            it as TokenState
        }.map {
            assertEquals(minter, it.minter)
            assertEquals(player1, it.owner)
            it.amount
        }.sum()
        assertEquals(4, sum)
    }

    @Test
    fun `SignedTransaction has 1 output of pot TokenState`() {
        val flow = BlindBet1OneStepFlow.Initiator(
            players = listOf(player0, player1, player2, player3),
            minter = minter, dealer = dealer, smallBet = 4
        )
        val future = player1Node.startFlow(flow)
        network.runNetwork()

        val signedTx = future.getOrThrow()
        val pots = signedTx.tx.outputsOfType<TokenState>().onEach {
            assertEquals(minter, it.minter)
            assertEquals(player1, it.owner)
        }
        assertEquals(1, pots.size)
        assertEquals(4, pots[0].amount)
    }

    @Test
    fun `SignedTransaction is received by all players and dealer`() {
        val flow = BlindBet1OneStepFlow.Initiator(
            players = listOf(player0, player1, player2, player3),
            minter = minter, dealer = dealer, smallBet = 4
        )
        val future = player1Node.startFlow(flow)
        network.runNetwork()

        val signedTx = future.getOrThrow()
        for (node in listOf(dealerNode, player0Node, player2Node, player3Node, player1Node)) {
            assertEquals(signedTx, node.services.validatedTransactions.getTransaction(signedTx.id))
        }
    }

    @Test
    fun `SignedTransaction has expected output of RoundState`() {
        val flow = BlindBet1OneStepFlow.Initiator(
            players = listOf(player0, player1, player2, player3),
            minter = minter, dealer = dealer, smallBet = 4
        )
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
                roundType = BettingRound.BLIND_BET_1,
                currentPlayerIndex = 1,
                players = listOf(
                    PlayedAction(player0, PlayerAction.Missing),
                    PlayedAction(player1, PlayerAction.Raise),
                    PlayedAction(player2, PlayerAction.Missing),
                    PlayedAction(player3, PlayerAction.Missing)
                )
            ),
            outputs.single()
        )
    }
}

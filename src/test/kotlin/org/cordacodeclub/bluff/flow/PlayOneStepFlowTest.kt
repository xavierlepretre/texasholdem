package org.cordacodeclub.bluff.flow

import net.corda.core.contracts.ContractState
import net.corda.core.crypto.SecureHash
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
import org.cordacodeclub.bluff.state.mapPartyToSum
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
class PlayOneStepFlowTest {
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
    fun `Cannot create PlayOneStepFlow with a Missing action`() {
        assertFailsWith<FlowException>("You cannot miss an action") {
            PlayOneStepFlow.Initiator(blindBet2Tx.id, PlayerAction.Missing, 0)
        }
    }

    @Test
    fun `Cannot create PlayOneStepFlow with a negative addAmount`() {
        assertFailsWith<FlowException>("addAmount must be positive") {
            PlayOneStepFlow.Initiator(blindBet2Tx.id, PlayerAction.Fold, -1)
        }
    }

    @Test
    fun `Cannot create PlayOneStepFlow with a Raise and no addAmount`() {
        assertFailsWith<FlowException>("You either raise or give a 0 addAmount") {
            PlayOneStepFlow.Initiator(blindBet2Tx.id, PlayerAction.Raise, 0)
        }
    }

    @Test
    fun `Cannot create PlayOneStepFlow with a Call and addAmount`() {
        assertFailsWith<FlowException>("You either raise or give a 0 addAmount") {
            PlayOneStepFlow.Initiator(blindBet2Tx.id, PlayerAction.Call, 10)
        }
    }

    @Test
    fun `Cannot create PlayOneStepFlow with a Fold and addAmount`() {
        assertFailsWith<FlowException>("You either raise or give a 0 addAmount") {
            PlayOneStepFlow.Initiator(blindBet2Tx.id, PlayerAction.Fold, 10)
        }
    }

    @Test
    fun `PlayOneStepFlow fails with unknown transaction id`() {
        val flow = PlayOneStepFlow.Initiator(SecureHash.zeroHash, PlayerAction.Fold, 0)
        val future = player2Node.startFlow(flow)
        network.runNetwork()
        assertFailsWith<FlowException>("Transaction cannot be found") {
            future.getOrThrow()
        }
    }

    @Test
    fun `PlayOneStepFlow fails with wrong transaction type`() {
        val flow = PlayOneStepFlow.Initiator(mintTx.id, PlayerAction.Raise, 4)
        val future = player2Node.startFlow(flow)
        network.runNetwork()
        assertFailsWith<FlowException>("RoundState not found or double in transaction") {
            future.getOrThrow()
        }
    }

    @Test
    fun `PlayOneStepFlow fails when started from wrong player`() {
        val flow = PlayOneStepFlow.Initiator(blindBet2Tx.id, PlayerAction.Raise, 4)
        val future = player1Node.startFlow(flow)
        network.runNetwork()
        assertFailsWith<FlowException>("Next player is 2, not me") {
            future.getOrThrow()
        }
    }

    @Test
    fun `PlayOneStepFlow fails if there is no next roundType`() {
        val signedTx = blindBet2Tx
            // Entering Pre_Flop
            .thenPlay(player2Node, PlayerAction.Fold, 0)
            .thenPlay(player3Node, PlayerAction.Fold, 0)
            .thenPlay(player0Node, PlayerAction.Call, 0)
            .thenPlay(player1Node, PlayerAction.Call, 0)
            // Entering Flop
            .thenPlay(player0Node, PlayerAction.Call, 0)
            .thenPlay(player1Node, PlayerAction.Call, 0)
            // Entering Turn
            .thenPlay(player0Node, PlayerAction.Call, 0)
            .thenPlay(player1Node, PlayerAction.Call, 0)
            // Entering River
            .thenPlay(player0Node, PlayerAction.Call, 0)
            .thenPlay(player1Node, PlayerAction.Call, 0)

        assertFailsWith<FlowException>("It is not possible to play this round") {
            signedTx.thenPlay(player0Node, PlayerAction.Call, 0)
        }
    }

    @Test
    fun `PlayOneStepFlow fails with wrong roundType in previous transaction`() {
        val flow = PlayOneStepFlow.Initiator(blindBet1Tx.id, PlayerAction.Raise, 8)
        val future = player1Node.startFlow(flow)
        network.runNetwork()
        assertFailsWith<FlowException>("This flow does not work for ${BettingRound.BLIND_BET_2}") {
            future.getOrThrow()
        }
    }

    @Test
    fun `SignedTransaction is signed by player`() {
        val signedTx = blindBet2Tx.thenPlay(player2Node, PlayerAction.Raise, 8)

        signedTx.sigs.map { it.by }.toSet().also {
            assertFalse(it.contains(dealer.owningKey))
            assertFalse(it.contains(player0.owningKey))
            assertFalse(it.contains(player1.owningKey))
            assertTrue(it.contains(player2.owningKey))
            assertFalse(it.contains(player3.owningKey))
        }
    }

    @Test
    fun `SignedTransaction has token inputs from current player only`() {
        val signedTx = blindBet2Tx.thenPlay(player2Node, PlayerAction.Raise, 7)

        val sum = signedTx.tx.inputs.map {
            player2Node.services.toStateAndRef<ContractState>(it).state.data
        }.filter {
            it is TokenState && !it.isPot
        }.map {
            it as TokenState
        }.map {
            assertEquals(minter, it.minter)
            assertEquals(player2, it.owner)
            it.amount
        }.sum()
        assertEquals(11, sum)
    }

    @Test
    fun `SignedTransaction has 3 outputs of pot TokenState`() {
        val signedTx = blindBet2Tx.thenPlay(player2Node, PlayerAction.Raise, 6)

        val pots = signedTx.tx.outputsOfType<TokenState>().onEach {
            assertEquals(minter, it.minter)
        }.mapPartyToSum()
        assertEquals(3, pots.size)
        assertEquals(4L, pots[player0])
        assertEquals(4L, pots[player1])
        assertEquals(10L, pots[player2])
    }

    @Test
    fun `SignedTransaction is received by all players and dealer`() {
        val signedTx = blindBet2Tx.thenPlay(player2Node, PlayerAction.Call, 0)

        for (node in listOf(dealerNode, player0Node, player2Node, player3Node, player1Node)) {
            assertEquals(signedTx, node.services.validatedTransactions.getTransaction(signedTx.id))
        }
    }

    @Test
    fun `SignedTransaction has expected output of RoundState and TokenState on Fold`() {
        val signedTx = blindBet2Tx.thenPlay(player2Node, PlayerAction.Fold, 0)

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
                roundType = BettingRound.PRE_FLOP,
                currentPlayerIndex = 2,
                players = listOf(
                    PlayedAction(player0, PlayerAction.Missing),
                    PlayedAction(player1, PlayerAction.Missing),
                    PlayedAction(player2, PlayerAction.Fold),
                    PlayedAction(player3, PlayerAction.Missing)
                )
            ),
            outputs.single()
        )
        val tokensIn = signedTx.tx.inputs
            .map { player2Node.services.toStateAndRef<ContractState>(it).state.data }
            .filter { it is TokenState }
            .map { it as TokenState }
        assertEquals(2, tokensIn.filter { it.isPot }.size)
        val myTokens = tokensIn.filter { !it.isPot }
        assertEquals(0, myTokens.size)
        assertTrue(signedTx.tx.outputsOfType<TokenState>().all { it.minter == minter && it.isPot })
    }

    @Test
    fun `SignedTransaction has expected output of RoundState and TokenState on Call`() {
        val signedTx = blindBet2Tx.thenPlay(player2Node, PlayerAction.Call, 0)

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
                roundType = BettingRound.PRE_FLOP,
                currentPlayerIndex = 2,
                players = listOf(
                    PlayedAction(player0, PlayerAction.Missing),
                    PlayedAction(player1, PlayerAction.Missing),
                    PlayedAction(player2, PlayerAction.Call),
                    PlayedAction(player3, PlayerAction.Missing)
                )
            ),
            outputs.single()
        )
        val tokensIn = signedTx.tx.inputs
            .map { player2Node.services.toStateAndRef<ContractState>(it).state.data }
            .filter { it is TokenState }
            .map { it as TokenState }
        assertEquals(2, tokensIn.filter { it.isPot }.size)
        val myTokens = tokensIn.filter { !it.isPot }
        assertTrue(myTokens.all { it.minter == minter && it.owner == player2 })
        assertEquals(4L, myTokens.map { it.amount }.sum())
        assertTrue(signedTx.tx.outputsOfType<TokenState>().all { it.minter == minter && it.isPot })
    }

    @Test
    fun `SignedTransaction has expected output of RoundState on Raise`() {
        val signedTx = blindBet2Tx.thenPlay(player2Node, PlayerAction.Raise, 8)

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
                roundType = BettingRound.PRE_FLOP,
                currentPlayerIndex = 2,
                players = listOf(
                    PlayedAction(player0, PlayerAction.Missing),
                    PlayedAction(player1, PlayerAction.Missing),
                    PlayedAction(player2, PlayerAction.Raise),
                    PlayedAction(player3, PlayerAction.Missing)
                )
            ),
            outputs.single()
        )
        val tokensIn = signedTx.tx.inputs
            .map { player2Node.services.toStateAndRef<ContractState>(it).state.data }
            .filter { it is TokenState }
            .map { it as TokenState }
        assertEquals(2, tokensIn.filter { it.isPot }.size)
        val myTokens = tokensIn.filter { !it.isPot }
        assertTrue(myTokens.all { it.minter == minter && it.owner == player2 })
        assertEquals(12L, myTokens.map { it.amount }.sum())
        assertTrue(signedTx.tx.outputsOfType<TokenState>().all { it.minter == minter && it.isPot })
    }

    @Test
    fun `SignedTransaction has expected output of RoundState on Fold by small blind bettor`() {
        val signedTx = blindBet2Tx
            .thenPlay(player2Node, PlayerAction.Fold, 0)
            .thenPlay(player3Node, PlayerAction.Call, 0)
            .thenPlay(player0Node, PlayerAction.Fold, 0)

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
                roundType = BettingRound.PRE_FLOP,
                currentPlayerIndex = 0,
                players = listOf(
                    PlayedAction(player0, PlayerAction.Fold),
                    PlayedAction(player1, PlayerAction.Missing),
                    PlayedAction(player2, PlayerAction.Fold),
                    PlayedAction(player3, PlayerAction.Call)
                )
            ),
            outputs.single()
        )
        val tokensIn = signedTx.tx.inputs
            .map { player2Node.services.toStateAndRef<ContractState>(it).state.data }
            .filter { it is TokenState }
            .map { it as TokenState }
        assertEquals(3, tokensIn.filter { it.isPot }.size)
        val myTokens = tokensIn.filter { !it.isPot }
        assertEquals(0, myTokens.size)
        assertTrue(signedTx.tx.outputsOfType<TokenState>().all { it.minter == minter && it.isPot })
    }

    @Test
    fun `SignedTransaction has expected output of RoundState on Call by small blind bettor`() {
        val signedTx = blindBet2Tx
            .thenPlay(player2Node, PlayerAction.Fold, 0)
            .thenPlay(player3Node, PlayerAction.Call, 0)
            .thenPlay(player0Node, PlayerAction.Call, 0)

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
                roundType = BettingRound.PRE_FLOP,
                currentPlayerIndex = 0,
                players = listOf(
                    PlayedAction(player0, PlayerAction.Call),
                    PlayedAction(player1, PlayerAction.Missing),
                    PlayedAction(player2, PlayerAction.Fold),
                    PlayedAction(player3, PlayerAction.Call)
                )
            ),
            outputs.single()
        )
        val tokensIn = signedTx.tx.inputs
            .map { player2Node.services.toStateAndRef<ContractState>(it).state.data }
            .filter { it is TokenState }
            .map { it as TokenState }
        assertEquals(3, tokensIn.filter { it.isPot }.size)
        val myTokens = tokensIn.filter { !it.isPot }
        assertEquals(0, myTokens.size)
        assertTrue(signedTx.tx.outputsOfType<TokenState>().all { it.minter == minter && it.isPot })
    }

    @Test
    fun `SignedTransaction has expected output of RoundState when landing on Flop`() {
        val signedTx = blindBet2Tx
            .thenPlay(player2Node, PlayerAction.Fold, 0).also { println("xav 1") }
            .thenPlay(player3Node, PlayerAction.Raise, 2).also { println("xav 2") }
            .thenPlay(player0Node, PlayerAction.Call, 0).also { println("xav 3") }
            .thenPlay(player1Node, PlayerAction.Call, 0).also { println("xav 4") }
            .thenPlay(player3Node, PlayerAction.Call, 0).also { println("xav 5") }
            // Entering Flop
            .thenPlay(player0Node, PlayerAction.Raise, 1).also { println("xav 6") }

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
                roundType = BettingRound.FLOP,
                currentPlayerIndex = 0,
                players = listOf(
                    PlayedAction(player0, PlayerAction.Raise),
                    PlayedAction(player1, PlayerAction.Missing),
                    PlayedAction(player2, PlayerAction.Fold),
                    PlayedAction(player3, PlayerAction.Missing)
                )
            ),
            outputs.single()
        )
        val tokensIn = signedTx.tx.inputs
            .map { player0Node.services.toStateAndRef<ContractState>(it).state.data }
            .filter { it is TokenState }
            .map { it as TokenState }
        assertEquals(3, tokensIn.filter { it.isPot }.size)
        val myTokens = tokensIn.filter { !it.isPot }
        assertTrue(myTokens.all { it.minter == minter && it.owner == player0 })
        assertEquals(1L, myTokens.map { it.amount }.sum())
        assertTrue(signedTx.tx.outputsOfType<TokenState>().all { it.minter == minter && it.isPot })
    }
}

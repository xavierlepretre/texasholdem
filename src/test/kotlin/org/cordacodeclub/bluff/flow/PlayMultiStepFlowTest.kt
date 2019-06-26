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
import org.cordacodeclub.bluff.flow.PlayOneStepFlow.Initiator.Companion.MIN_PLAYER_COUNT_TO_PLAY
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
import kotlin.test.assertTrue

/**
 * Add -javaagent:./lib/quasar.jar to VM Options
 */
class PlayMultiStepFlowTest {
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
    fun `PlayOneStepFlow needs at least 2 players to play`() {
        val signedTx = blindBet2Tx
            .thenPlay(player2Node, PlayerAction.Fold, 0)
            .thenPlay(player3Node, PlayerAction.Call, 0)
            .thenPlay(player0Node, PlayerAction.Fold, 0)
            .thenPlay(player1Node, PlayerAction.Fold, 0)

        assertFailsWith<FlowException>("You need at least $MIN_PLAYER_COUNT_TO_PLAY to play") {
            signedTx.thenPlay(player3Node, PlayerAction.Raise, 2)
        }
    }

    @Test
    fun `PlayOneStepFlow a couple times`() {
        val signedTx = blindBet2Tx
            .thenPlay(player2Node, PlayerAction.Fold, 0)
            .thenPlay(player3Node, PlayerAction.Raise, 2)
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
                    PlayedAction(player3, PlayerAction.Raise)
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
        assertEquals(2L, myTokens.map { it.amount }.sum())
        assertTrue(signedTx.tx.outputsOfType<TokenState>().all { it.minter == minter && it.isPot })
    }

    @Test
    fun `PlayOneStepFlow a very long Pre_flop`() {
        val signedTx = blindBet2Tx
            .thenPlay(player2Node, PlayerAction.Fold, 0)
            .thenPlay(player3Node, PlayerAction.Raise, 2)
            .thenPlay(player0Node, PlayerAction.Call, 0)
            .thenPlay(player1Node, PlayerAction.Raise, 1)
            .thenPlay(player3Node, PlayerAction.Call, 0)
            .thenPlay(player0Node, PlayerAction.Call, 0)
            .thenPlay(player1Node, PlayerAction.Raise, 3)
            .thenPlay(player3Node, PlayerAction.Call, 0)

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
                currentPlayerIndex = 3,
                players = listOf(
                    PlayedAction(player0, PlayerAction.Call),
                    PlayedAction(player1, PlayerAction.Raise),
                    PlayedAction(player2, PlayerAction.Fold),
                    PlayedAction(player3, PlayerAction.Call)
                )
            ),
            outputs.single()
        )
        val tokensIn = signedTx.tx.inputs
            .map { player3Node.services.toStateAndRef<ContractState>(it).state.data }
            .filter { it is TokenState }
            .map { it as TokenState }
        assertEquals(3, tokensIn.filter { it.isPot }.size)
        val myTokens = tokensIn.filter { !it.isPot }
        assertTrue(myTokens.all { it.minter == minter && it.owner == player3 })
        assertEquals(3L, myTokens.map { it.amount }.sum())
        assertTrue(signedTx.tx.outputsOfType<TokenState>().all { it.minter == minter && it.isPot })
    }

    @Test
    fun `PlayOneStepFlow until Turn`() {
        val signedTx = blindBet2Tx
            .thenPlay(player2Node, PlayerAction.Call, 0)
            .thenPlay(player3Node, PlayerAction.Raise, 2)
            .thenPlay(player0Node, PlayerAction.Fold, 0)
            .thenPlay(player1Node, PlayerAction.Call, 0)
            .thenPlay(player2Node, PlayerAction.Call, 0)
            .thenPlay(player3Node, PlayerAction.Call, 0)
            // Entering Flop
            .thenPlay(player1Node, PlayerAction.Call, 0)
            .thenPlay(player2Node, PlayerAction.Call, 0)
            .thenPlay(player3Node, PlayerAction.Call, 0)
            // Entering Turn
            .thenPlay(player1Node, PlayerAction.Raise, 1)

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
                roundType = BettingRound.TURN,
                currentPlayerIndex = 1,
                players = listOf(
                    PlayedAction(player0, PlayerAction.Fold),
                    PlayedAction(player1, PlayerAction.Raise),
                    PlayedAction(player2, PlayerAction.Missing),
                    PlayedAction(player3, PlayerAction.Missing)
                )
            ),
            outputs.single()
        )
        val tokensIn = signedTx.tx.inputs
            .map { player1Node.services.toStateAndRef<ContractState>(it).state.data }
            .filter { it is TokenState }
            .map { it as TokenState }
        assertEquals(4, tokensIn.filter { it.isPot }.size)
        val myTokens = tokensIn.filter { !it.isPot }
        assertTrue(myTokens.all { it.minter == minter && it.owner == player1 })
        assertEquals(1L, myTokens.map { it.amount }.sum())
        assertTrue(signedTx.tx.outputsOfType<TokenState>().all { it.minter == minter && it.isPot })
    }

    @Test
    fun `PlayOneStepFlow fast game to River`() {
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
                roundType = BettingRound.RIVER,
                currentPlayerIndex = 1,
                players = listOf(
                    PlayedAction(player0, PlayerAction.Call),
                    PlayedAction(player1, PlayerAction.Call),
                    PlayedAction(player2, PlayerAction.Fold),
                    PlayedAction(player3, PlayerAction.Fold)
                )
            ),
            outputs.single()
        )
        val tokensIn = signedTx.tx.inputs
            .map { player1Node.services.toStateAndRef<ContractState>(it).state.data }
            .filter { it is TokenState }
            .map { it as TokenState }
        assertEquals(2, tokensIn.filter { it.isPot }.size)
        val myTokens = tokensIn.filter { !it.isPot }
        assertTrue(myTokens.all { it.minter == minter && it.owner == player1 })
        assertEquals(0L, myTokens.map { it.amount }.sum())
        assertTrue(signedTx.tx.outputsOfType<TokenState>().all { it.minter == minter && it.isPot })
    }
}

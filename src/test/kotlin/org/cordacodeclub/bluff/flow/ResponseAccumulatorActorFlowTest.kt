package org.cordacodeclub.bluff.flow

import net.corda.core.contracts.StateAndRef
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.internal.toMultiMap
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.StartedMockNode
import org.cordacodeclub.bluff.dealer.CardDeckInfo
import org.cordacodeclub.bluff.state.ActivePlayer
import org.cordacodeclub.bluff.state.TokenState
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Add -javaagent:./lib/quasar.jar to VM Options
 */
class ResponseAccumulatorActorFlowTest {
    private lateinit var network: MockNetwork
    private lateinit var minterNode: StartedMockNode
    private lateinit var dealerNode: StartedMockNode
    private lateinit var player1Node: StartedMockNode
    private lateinit var player2Node: StartedMockNode
    private lateinit var player3Node: StartedMockNode
    private lateinit var minter: Party
    private lateinit var dealer: Party
    private lateinit var player1: Party
    private lateinit var player2: Party
    private lateinit var player3: Party
    private lateinit var mintTx: SignedTransaction
    private lateinit var blindBetTx: SignedTransaction
    private lateinit var potTokens: Map<Party, List<StateAndRef<TokenState>>>

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
        minter = minterNode.info.singleIdentity()
        dealer = dealerNode.info.singleIdentity()
        player1 = player1Node.info.singleIdentity()
        player2 = player2Node.info.singleIdentity()
        player3 = player3Node.info.singleIdentity()

        // For real nodes this happens automatically, but we have to manually register the flow for tests.
        listOf(minterNode, dealerNode, player1Node, player2Node, player3Node).forEach {
            it.registerInitiatedFlow(MintTokenFlow.Recipient::class.java)
            it.registerInitiatedFlow(BlindBetFlow.CollectorAndSigner::class.java)
            it.registerInitiatedFlow(ResponseAccumulatorActorFlow.RemoteControlledResponder::class.java)
        }
        val mintFlow = MintTokenFlow.Minter(listOf(player1, player2, player3), 100, 1)
        val mintFuture = minterNode.startFlow(mintFlow)
        val blindBetFlow = BlindBetFlow.Initiator(listOf(player1, player2, player3), minter, 4)
        val blindBetFuture = dealerNode.startFlow(blindBetFlow)
        network.runNetwork()
        mintTx = mintFuture.getOrThrow()
        blindBetTx = blindBetFuture.getOrThrow()
        potTokens = blindBetTx.tx.outRefsOfType<TokenState>()
            .map { it.state.data.owner to it }
            .toMultiMap()
    }

    @After
    fun tearDown() {
        network.stopNodes()
    }

    @Test
    fun `Can receive fold responses accumulated`() {
        val players = listOf(player1, player2, player3)
        val deckInfo = CardDeckInfo.createShuffledWith(players.map { it.name }, dealer.name)
        val flow = ResponseAccumulatorActorFlow.Initiator(
            deckInfo = deckInfo,
            players = players,
            accumulator = RoundTableAccumulator(
                minter = minter,
                players = players.map { ActivePlayer(it, false) },
                currentPlayerIndex = 2,
                committedPotSums = potTokens.mapValues { it.value.map { it.state.data.amount }.sum() },
                newBets = mapOf(),
                newTransactions = setOf(),
                lastRaiseIndex = 1,
                playerCountSinceLastRaise = 0
            ),
            responderActions = mapOf(
                player3 to listOf(DesiredAction(Action.Fold)),
                player1 to listOf(DesiredAction(Action.Fold)),
                player2 to listOf()
            )
        )
        val future = dealerNode.startFlow(flow)
        network.runNetwork()

        val accumulated = future.getOrThrow()
        assertEquals(listOf(), accumulated[player1]!!.myNewBets)
        assertEquals(listOf(), accumulated[player2]!!.myNewBets)
        assertEquals(listOf(), accumulated[player3]!!.myNewBets)
    }

    @Test
    fun `Can receive call responses accumulated`() {
        val players = listOf(player1, player2, player3)
        val deckInfo = CardDeckInfo.createShuffledWith(players.map { it.name }, dealer.name)
        val flow = ResponseAccumulatorActorFlow.Initiator(
            deckInfo = deckInfo,
            players = players,
            accumulator = RoundTableAccumulator(
                minter = minter,
                players = players.map { ActivePlayer(it, false) },
                currentPlayerIndex = 2,
                committedPotSums = potTokens.mapValues { it.value.map { it.state.data.amount }.sum() },
                newBets = mapOf(),
                newTransactions = setOf(),
                lastRaiseIndex = 1,
                playerCountSinceLastRaise = 0
            ),
            responderActions = mapOf(
                player3 to listOf(DesiredAction(Action.Call)),
                player1 to listOf(DesiredAction(Action.Call)),
                player2 to listOf(DesiredAction(Action.Call))
            )
        )
        val future = dealerNode.startFlow(flow)
        network.runNetwork()

        val accumulated = future.getOrThrow()
        assertEquals(4, accumulated[player1]!!.myNewBets.size)
        assertEquals(listOf(), accumulated[player2]!!.myNewBets)
        assertEquals(8, accumulated[player3]!!.myNewBets.size)
    }
}
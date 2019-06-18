package org.cordacodeclub.bluff.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.StateAndRef
import net.corda.core.crypto.MerkleTree
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatingFlow
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.internal.toMultiMap
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.unwrap
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.StartedMockNode
import org.cordacodeclub.bluff.dealer.CardDeckInfo
import org.cordacodeclub.bluff.player.ActionRequest
import org.cordacodeclub.bluff.player.DesiredAction
import org.cordacodeclub.bluff.player.PlayerAction
import org.cordacodeclub.bluff.round.CallOrRaiseRequest
import org.cordacodeclub.bluff.round.DealerRoundAccumulator
import org.cordacodeclub.bluff.round.PlayerSideResponseAccumulator
import org.cordacodeclub.bluff.state.ActivePlayer
import org.cordacodeclub.bluff.state.BettingRound
import org.cordacodeclub.bluff.state.TokenState
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Add -javaagent:./lib/quasar.jar to VM Options
 */
class PlayerSideResponseAccumulatorFlowTest {
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

    companion object {
        private lateinit var responderActions: Map<Party, List<DesiredAction>>
    }

    @InitiatingFlow
    private class Initiator(
        val deckInfo: CardDeckInfo,
        val players: List<Party>,
        val accumulator: DealerRoundAccumulator
    ) : FlowLogic<Map<Party, PlayerSideResponseAccumulator>>() {

        @Suspendable
        override fun call(): Map<Party, PlayerSideResponseAccumulator> {
            val playerFlows = players.map { initiateFlow(it) }
            val accumulated = try {
                subFlow(
                    DealerRoundAccumulatorFlow(
                        deckInfo = deckInfo,
                        playerFlows = playerFlows,
                        accumulator = accumulator
                    )
                )
            } catch (e: Throwable) {
                println(e)
                throw e
            }
            return players.mapIndexed { index, player ->
                player to playerFlows[index].receive<PlayerSideResponseAccumulator>().unwrap { it }
            }.toMap()
        }
    }

    class SendBackPlayerSideResponseAccumulatorFlow(otherPartySession: FlowSession) :
        PlayerSideResponseAccumulatorFlow(otherPartySession) {

        @Suspendable
        override fun getActionRequest(request: CallOrRaiseRequest): ActionRequest {
            return try {
                subFlow(PlayerResponseCollectingPreparedFlow(request))
            } catch (e: Throwable) {
                println(e)
                throw e
            }
        }

        @Suspendable
        override fun createOwn(otherPartySession: FlowSession): PlayerSideResponseAccumulatorFlow {
            return SendBackPlayerSideResponseAccumulatorFlow(otherPartySession)
        }

        @Suspendable
        override fun call() = super.call().also {
            val me = serviceHub.myInfo.legalIdentities.first()
            otherPartySession.send(it)
        }
    }

    private class PlayerResponseCollectingPreparedFlow(
        request: CallOrRaiseRequest,
        private var index: Int = 0
    ) : PlayerResponseCollectingFlow(request) {

        @Suspendable
        override fun call(): ActionRequest {
            val me = serviceHub.myInfo.legalIdentities.first()
            val desiredAction = responderActions[me]!![index]
            return ActionRequest(
                id = 0,
                player = me.name,
                cards = request.yourCards.map { it.card },
                cardHashes = MerkleTree.getMerkleTree(request.cardHashes).hash,
                youBet = request.yourWager,
                lastRaise = request.lastRaise,
                playerAction = desiredAction.playerAction,
                addAmount = desiredAction.raiseBy
            ).also {
                index++
            }
        }
    }

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
            it.registerInitiatedFlow(
                Initiator::class.java,
                SendBackPlayerSideResponseAccumulatorFlow::class.java
            )
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
        responderActions = mapOf(
            player3 to listOf(DesiredAction(PlayerAction.Fold)),
            player1 to listOf(DesiredAction(PlayerAction.Fold)),
            player2 to listOf()
        )
        val flow = Initiator(
            deckInfo = deckInfo,
            players = players,
            accumulator = DealerRoundAccumulator(
                round = BettingRound.PRE_FLOP,
                minter = minter,
                players = players.map { ActivePlayer(it, false) },
                currentPlayerIndex = 2,
                committedPotSums = potTokens.mapValues { it.value.map { it.state.data.amount }.sum() },
                newBets = mapOf(),
                newTransactions = setOf(),
                lastRaiseIndex = 1,
                playerCountSinceLastRaise = 0
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
        responderActions = mapOf(
            player3 to listOf(DesiredAction(PlayerAction.Call)),
            player1 to listOf(DesiredAction(PlayerAction.Call)),
            player2 to listOf(DesiredAction(PlayerAction.Call))
        )
        val flow = Initiator(
            deckInfo = deckInfo,
            players = players,
            accumulator = DealerRoundAccumulator(
                round = BettingRound.PRE_FLOP,
                minter = minter,
                players = players.map { ActivePlayer(it, false) },
                currentPlayerIndex = 2,
                committedPotSums = potTokens.mapValues { it.value.map { it.state.data.amount }.sum() },
                newBets = mapOf(),
                newTransactions = setOf(),
                lastRaiseIndex = 1,
                playerCountSinceLastRaise = 0
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
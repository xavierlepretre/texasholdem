//
//
//import co.paralleluniverse.fibers.Suspendable
//import junit.framework.Assert.assertEquals
//import net.corda.testing.node.StartedMockNode
//import net.corda.testing.node.MockNetwork
//import org.junit.Before
//import net.corda.core.flows.FlowSession
//import net.corda.core.flows.InitiatedBy
//import net.corda.core.identity.CordaX500Name
//import net.corda.core.identity.Party
//import org.cordacodeclub.bluff.flow.EndGameFlow
//import org.cordacodeclub.bluff.player.PlayerDatabaseService
//import org.junit.After
//import org.junit.Test
//
//
//class FlowTests {
//    private var network: MockNetwork? = null
//    private lateinit var player1Node: StartedMockNode
//    private lateinit var player2Node: StartedMockNode
//    private lateinit var player1: Party
//    private lateinit var player2: Party
//    private lateinit var mockPlayerDatabaseService: PlayerDatabaseService
//
//    @InitiatedBy(EndGameFlow.Initiator::class)
//    private inner class CustomResponderFlow(otherPartySession: FlowSession) :
//            EndGameFlow.Responder(otherPartySession) {
//        override var playerDatabaseService: PlayerDatabaseService? = mockPlayerDatabaseService
//
//        @Suspendable
//        override fun call() = super.call().also { otherPartySession.send(it) }
//    }
//
//    @Before
//    fun setup() {
//        network = MockNetwork(listOf("com.example.contract"))
//        player1Node = network!!.createPartyNode(CordaX500Name.parse("O=Player1, L=London, C=GB"))
//        player2Node = network!!.createPartyNode(CordaX500Name.parse("O=Player2, L=London, C=GB"))
//        player1 = player1Node.info.singleIdentity()
//        player2 = player2Node.info.singleIdentity()
//        // For real nodes this happens automatically, but we have to manually register the flow for tests.
//        for (node in listOf(player1Node, player2Node)) {
//            node.registerInitiatedFlow(CustomResponderFlow::class.java)
//        }
//        network!!.runNetwork()
//    }
//
//    @After
//    fun tearDown() {
//        network!!.stopNodes()
//    }
//
//    @Test
//    fun flowUsesDummyResponder() {
//        val flow = EndGameFlow.Initiator(-1, b!!.info.legalIdentities[0])
//        val future = a!!.startFlow<Boolean>(flow)
//        network!!.runNetwork()
//        val bool = future.get()
//        assertEquals(true, bool)
//    }
//}
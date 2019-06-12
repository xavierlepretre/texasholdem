package learn.net.corda.core.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.StartedMockNode
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Add -javaagent:./lib/quasar.jar to VM Options
 */
class SubFlowDepthTest {
    private lateinit var network: MockNetwork
    private lateinit var simpleNode: StartedMockNode
    private lateinit var simpleParty: Party

    @Before
    fun setup() {
        network = MockNetwork(
            listOf(
            )
        )
        simpleNode = network.createPartyNode(CordaX500Name.parse("O=Simple, L=London, C=GB"))
        simpleParty = simpleNode.info.singleIdentity()

        network.runNetwork()
    }

    @After
    fun tearDown() {
        network.stopNodes()
    }

    class Digger(val currentDepth: Long, val maxDepth: Long) : FlowLogic<Unit>() {

        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object DIGGING : ProgressTracker.Step("Digging one more.")
            object RETURNING : ProgressTracker.Step("Now returning.")
        }

        private fun tracker() = ProgressTracker(DIGGING, RETURNING)

        override val progressTracker = tracker()

        @Suspendable
        override fun call() {
            if (currentDepth < maxDepth) subFlow(Digger(currentDepth + 1, maxDepth))
        }
    }

    @Test
    fun `Can dig 50 deep`() {
        val flow = Digger(0, 10)
        val future = simpleNode.startFlow(flow)
        network.runNetwork()

        future.getOrThrow()
    }
}
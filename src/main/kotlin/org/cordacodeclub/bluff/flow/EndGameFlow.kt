//package org.cordacodeclub.bluff.flow
//
//import net.corda.core.contracts.StateAndRef
//import net.corda.core.contracts.requireThat
//import net.corda.core.flows.FlowLogic
//import net.corda.core.utilities.ProgressTracker
//import org.cordacodeclub.bluff.state.GameState
//import org.cordacodeclub.bluff.state.PlayerHandState
//
//class EndGameFlow(
//        val roundTableAccumulator: RoundTableAccumulator,
//        val gameState: GameState,
//        override val progressTracker: ProgressTracker = TokenStateCollectorFlow.tracker()
//) : FlowLogic<List<StateAndRef<PlayerHandState>>>() {
//
//    init {
//        requireThat {
//            // TODO checks
//        }
//    }
//
//    companion object {
//        object PREPARING_HANDS : ProgressTracker.Step("Preparing player hands") {
//            override fun childProgressTracker() = TransferOwnTokenFlow.tracker()
//
//            @JvmStatic
//            fun tracker() = ProgressTracker(
//
//            )
//        }
//
//
//    }
//}
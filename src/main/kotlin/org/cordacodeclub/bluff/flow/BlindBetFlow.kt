package org.cordacodeclub.bluff.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.ProgressTracker
import org.cordacodeclub.bluff.state.BlindBetState
import org.cordacodeclub.bluff.state.BlindBetStatus
import org.cordacodeclub.bluff.state.TokenState

//Initial flow
object BlindBetFlow {

    @InitiatingFlow
    @StartableByRPC

    /**
     * This flow is startable by the dealer party.
     * And it has to be signed by the players and delear.
     * @param players list of parties starting the game
     */

    class Initiator(val players: List<Party>) {

        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object GENERATING_BLINDBET_STATES : ProgressTracker.Step("Generating blind based on the players.")
            object GENERATING_TOKEN_STATES : ProgressTracker.Step("Generating tokens based on the players.")
            object GENERATING_CARD_STATES : ProgressTracker.Step("Generating cards based on the players.")
            object GENERATING_POT_STATES : ProgressTracker.Step("Generating betting pot.")
            object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction based on new IOU.")
            object VERIFYING_TRANSACTION : ProgressTracker.Step("Verifying contract constraints.")
            object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")

            object GATHERING_SIGS : ProgressTracker.Step("Gathering the counterparty's signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }

            object FINALISING_TRANSACTION : ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }
        }

            fun tracker() = ProgressTracker(
                    GENERATING_BLINDBET_STATES,
                    GENERATING_TOKEN_STATES,
                    GENERATING_CARD_STATES,
                    GENERATING_POT_STATES,
                    GENERATING_TRANSACTION,
                    VERIFYING_TRANSACTION,
                    SIGNING_TRANSACTION,
                    GATHERING_SIGS,
                    FINALISING_TRANSACTION)

            override val progressTracker = tracker()


            /**
             * The flow logic is encapsulated within the call() method.
             */
            @Suspendable
            override fun call(): SignedTransaction {

                // Obtain a reference to the notary we want to use.
                val notary = serviceHub.networkMapCache.notaryIdentities[0]

                progressTracker.currentStep = GENERATING_BLINDBET_STATES


                val tokenStates =  players.map { BlindBetState(sb = 10, owner = it, status = BlindBetStatus.PASS) }
                val tokenState =  players.map { TokenState() }
            }

        }
    }

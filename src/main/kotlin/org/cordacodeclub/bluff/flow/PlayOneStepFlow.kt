package org.cordacodeclub.bluff.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndRef
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import org.cordacodeclub.bluff.contract.OneStepContract
import org.cordacodeclub.bluff.contract.TokenContract
import org.cordacodeclub.bluff.player.PlayerAction
import org.cordacodeclub.bluff.state.PlayedAction
import org.cordacodeclub.bluff.state.RoundState
import org.cordacodeclub.bluff.state.TokenState
import org.cordacodeclub.bluff.state.mapPartyToSum

object PlayOneStepFlow {

    @InitiatingFlow
    @StartableByRPC
    /**
     * This flow is started by the player doing the first blind bet. Tx has to be signed by the dealer.
     * @param previousTxHash the Blind Bet 1 transaction that this one follows
     * @param myAction the action chosen by the player
     * @param addAmount the amount to raise on top of call if relevant
     * @param progressTracker the overridden progress tracker
     */
    class Initiator(
        private val previousTxHash: SecureHash,
        private val myAction: PlayerAction,
        private val addAmount: Long,
        override val progressTracker: ProgressTracker = tracker()
    ) : FlowLogic<SignedTransaction>() {

        init {
            if (myAction == PlayerAction.Missing) throw FlowException("You cannot miss an action")
            if (addAmount < 0) throw FlowException("addAmount must be positive")
            if ((myAction == PlayerAction.Raise) == (addAmount == 0L))
                throw FlowException("You either raise or give a 0 addAmount")
        }

        companion object {
            val MIN_PLAYER_COUNT_TO_PLAY = 2

            object FETCHING_PREV_TX : ProgressTracker.Step("Fetching and confirming previous transaction.")
            object COLLECTING_TOKENS : ProgressTracker.Step("Collecting own tokens for blind bet.") {
                override fun childProgressTracker(): ProgressTracker {
                    return CollectOwnTokenStateFlow.tracker()
                }
            }

            object GENERATING_POT_STATES : ProgressTracker.Step("Generating betting pot.")
            object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction.")
            object VERIFYING_TRANSACTION : ProgressTracker.Step("Verifying contract constraints.")
            object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")
            object FINALISING_TRANSACTION :
                ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                FETCHING_PREV_TX,
                COLLECTING_TOKENS,
                GENERATING_POT_STATES,
                GENERATING_TRANSACTION,
                VERIFYING_TRANSACTION,
                SIGNING_TRANSACTION,
                FINALISING_TRANSACTION
            )
        }

        @Suspendable
        override fun call(): SignedTransaction {

            progressTracker.currentStep = FETCHING_PREV_TX

            val me = serviceHub.myInfo.legalIdentities.first()
            // Player asks cards from dealer
            val previousStepTx = serviceHub.validatedTransactions.getTransaction(previousTxHash)
                ?: throw FlowException("Transaction cannot be found $previousTxHash")
            val prevStateRef = previousStepTx.coreTransaction.outRefsOfType<RoundState>().singleOrNull()
                ?: throw FlowException("RoundState not found or double in transaction")
            val prevState = prevStateRef.state.data
            if (me != prevState.nextActivePlayer) {
                throw FlowException("Next player is ${prevState.nextActivePlayer}, not $me")
            }
            val potTokens = previousStepTx.coreTransaction
                .outputsOfType<TokenState>().filter { it.isPot }
                .mapPartyToSum()
            val thisRoundType = prevState.nextRoundTypeOrNull
                ?: throw FlowException("It is not possible to play this round")
            if (!thisRoundType.isPlay) throw FlowException("This flow does not work for $thisRoundType")
            if (prevState.activePlayerCount < MIN_PLAYER_COUNT_TO_PLAY)
                throw FlowException("You need at least $MIN_PLAYER_COUNT_TO_PLAY to play")
            val lastRaise = potTokens.values.max()
                ?: throw FlowException("Last raise player has no committed pots")
            val yourWager = potTokens[me] ?: 0
            val desiredAmount = addAmount + lastRaise - yourWager

            progressTracker.currentStep = COLLECTING_TOKENS
            val tokenStates: List<StateAndRef<TokenState>> = when (myAction) {
                PlayerAction.Fold -> listOf()
                PlayerAction.Raise, PlayerAction.Call -> {
                    if (desiredAmount == 0L) listOf()
                    else if (0L < desiredAmount) subFlow(
                        CollectOwnTokenStateFlow(
                            TokenState(
                                minter = prevState.minter,
                                owner = me,
                                amount = desiredAmount,
                                isPot = false
                            )
                        )
                    )
                    else throw FlowException("desiredAmount should never have been negative")
                }
                else -> throw FlowException("Should never arrive here $myAction")
            }
            val tokenNotaries = tokenStates.map { it.state.notary }.toSet()
            if (tokenNotaries.size > 1) throw FlowException("Did not collect states from a single notary")

            progressTracker.currentStep = GENERATING_POT_STATES
            val potStates = potTokens
                .let {
                    val myAmount = (potTokens[me] ?: 0) + tokenStates.map { it.state.data.amount }.sum()
                    if (0 < myAmount) it.plus(me to myAmount)
                    else it
                }
                .map {
                    TokenState(minter = prevState.minter, owner = it.key, amount = it.value, isPot = true)
                }
            val myPlayerIndex = prevState.players.indexOfFirst { it.player == me }
            val playedActions = prevState.players.map {
                if (it.player == me) PlayedAction(me, myAction)
                else if (it.action == PlayerAction.Fold) it
                else if (prevState.isRoundDone) it.copy(action = PlayerAction.Missing)
                else it
            }

            progressTracker.currentStep = GENERATING_TRANSACTION
            val notary = previousStepTx.notary
                ?: throw FlowException("Previous step should have a notary")
            if (tokenNotaries.firstOrNull().let { it != null && it != notary })
                throw FlowException("Tokens and previousTx do not have the same notary")
            val txBuilder = TransactionBuilder(notary = notary)

            txBuilder.addCommand(Command(TokenContract.Commands.BetToPot(), me.owningKey))
            previousStepTx.coreTransaction.outRefsOfType<TokenState>().forEach { txBuilder.addInputState(it) }
            tokenStates.forEach { txBuilder.addInputState(it) }
            potStates.forEach { txBuilder.addOutputState(it, TokenContract.ID) }

            txBuilder.addCommand(Command(OneStepContract.Commands.Play(), me.owningKey))
            txBuilder.addInputState(prevStateRef)
            txBuilder.addOutputState(
                prevState.copy(
                    roundType = thisRoundType,
                    currentPlayerIndex = myPlayerIndex,
                    players = playedActions
                ),
                OneStepContract.ID
            )

            progressTracker.currentStep = VERIFYING_TRANSACTION
            txBuilder.verify(serviceHub)

            progressTracker.currentStep = SIGNING_TRANSACTION
            val signedTx = serviceHub.signInitialTransaction(txBuilder)

            progressTracker.currentStep = FINALISING_TRANSACTION
            val participantFlows = prevState.players
                .map { it.player }
                .minus(me).plus(prevState.dealer)
                .map { initiateFlow(it) }
            return subFlow(
                FinalityFlow(
                    signedTx,
                    participantFlows,
                    FINALISING_TRANSACTION.childProgressTracker()
                )
            )
        }
    }

    @InitiatedBy(Initiator::class)
    class Responder(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {

        companion object {
            object RECEIVING_FINALISED_TRANSACTION : ProgressTracker.Step("Receiving finalised transaction.")

            fun tracker() = ProgressTracker(
                RECEIVING_FINALISED_TRANSACTION
            )
        }

        override val progressTracker: ProgressTracker = tracker()

        @Suspendable
        override fun call(): SignedTransaction {

            progressTracker.currentStep = RECEIVING_FINALISED_TRANSACTION
            return subFlow(ReceiveFinalityFlow(otherPartySession))
        }
    }
}
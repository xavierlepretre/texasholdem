package org.cordacodeclub.bluff.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import org.cordacodeclub.bluff.contract.OneStepContract
import org.cordacodeclub.bluff.contract.TokenContract
import org.cordacodeclub.bluff.player.PlayerAction
import org.cordacodeclub.bluff.round.BettingRound
import org.cordacodeclub.bluff.state.PlayedAction
import org.cordacodeclub.bluff.state.RoundState
import org.cordacodeclub.bluff.state.TokenState
import org.cordacodeclub.bluff.state.mapPartyToSum

object BlindBet2OneStepFlow {

    @InitiatingFlow
    @StartableByRPC
    /**
     * This flow is started by the player doing the first blind bet. Tx has to be signed by the dealer.
     * @param previousTxHash the Blind Bet 1 transaction that this one follows
     * @param bigBet the big bet selected
     * @param progressTracker the overridden progress tracker
     */
    class Initiator(
        val previousTxHash: SecureHash,
        val bigBet: Long,
        override val progressTracker: ProgressTracker = tracker()
    ) :
        FlowLogic<SignedTransaction>() {

        init {
            if (bigBet <= 0) throw FlowException("BigBet should be strictly positive")
        }

        companion object {
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
            val blindBet1Tx = serviceHub.validatedTransactions.getTransaction(previousTxHash)
                ?: throw FlowException("Transaction cannot be found $previousTxHash")
            val prevStateRef = blindBet1Tx.coreTransaction.outRefsOfType<RoundState>().singleOrNull()
                ?: throw FlowException("RoundState not found or double in transaction")
            val prevState = prevStateRef.state.data
            if (me != prevState.nextActivePlayer) {
                throw FlowException("Next player index is ${prevState.nextActivePlayer}, not me")
            }
            val potTokens = blindBet1Tx.coreTransaction
                .outputsOfType<TokenState>().filter { it.isPot }
                .mapPartyToSum()
            if (prevState.roundType != BettingRound.BLIND_BET_1) throw FlowException(
                "Previous roundType should be ${BettingRound.BLIND_BET_1} not ${prevState.roundType}"
            )

            val smallBet = potTokens.values.singleOrNull()
                ?: throw FlowException("There should be a single bettor of tokens")
            if (bigBet < smallBet) throw FlowException("bigBet should be at least $smallBet")

            progressTracker.currentStep = COLLECTING_TOKENS
            val tokenStates = subFlow(
                CollectOwnTokenStateFlow(
                    TokenState(prevState.minter, me, bigBet, false),
                    COLLECTING_TOKENS.childProgressTracker()
                )
            )
            val tokenNotary = tokenStates.map { it.state.notary }.toSet().singleOrNull()
                ?: throw FlowException("Did not collect states from a single notary")

            progressTracker.currentStep = GENERATING_POT_STATES
            val potStates = potTokens
                .map {
                    TokenState(minter = prevState.minter, owner = it.key, amount = it.value, isPot = true)
                }.plus(tokenStates.map { it.state.data.amount }.sum()
                    .let { sum ->
                        tokenStates.first().state.data.copy(amount = sum, isPot = true)
                    })

            val myAction =
                if (smallBet < bigBet) PlayerAction.Raise
                else PlayerAction.Call
            val myPlayerIndex = prevState.players.indexOfFirst { it.player == me }
            val playedActions = prevState.players.map {
                if (it.player == me) PlayedAction(me, myAction)
                else it
            }

            progressTracker.currentStep = GENERATING_TRANSACTION
            val notary = blindBet1Tx.notary ?: tokenNotary
            if (notary != tokenNotary) throw FlowException("Tokens and blindBet1Tx do not have the same notary")
            val txBuilder = TransactionBuilder(notary = notary)

            txBuilder.addCommand(Command(TokenContract.Commands.BetToPot(), me.owningKey))
            blindBet1Tx.coreTransaction.outRefsOfType<TokenState>().forEach { txBuilder.addInputState(it) }
            tokenStates.forEach { txBuilder.addInputState(it) }
            potStates.forEach { txBuilder.addOutputState(it, TokenContract.ID) }

            txBuilder.addCommand(Command(OneStepContract.Commands.BetBlind2(), me.owningKey))
            txBuilder.addInputState(prevStateRef)
            txBuilder.addOutputState(
                prevState.copy(
                    roundType = BettingRound.BLIND_BET_2,
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
package org.cordacodeclub.bluff.contract

import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.requireSingleCommand
import net.corda.core.contracts.requireThat
import net.corda.core.internal.toMultiMap
import net.corda.core.transactions.LedgerTransaction
import org.cordacodeclub.bluff.state.TokenState

class TokenContract : Contract {
    companion object {
        val ID = "org.cordacodeclub.bluff.contract.TokenContract"
    }

    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Commands>()
        val inputTokens = tx.inputsOfType<TokenState>()
        val sortedInputs = inputTokens.partition { it.isPot }
        val outputTokens = tx.outputsOfType<TokenState>()
        val sortedOutputs = outputTokens.partition { it.isPot }
        val inputTokenCount = sortedInputs.second.count()
        val inputPotCount = sortedInputs.first.count()
        val outputTokenCount = sortedOutputs.second.count()
        val outputPotCount = sortedOutputs.first.count()

        val summing = fun(sum: Long, token: TokenState) = sum + token.amount

        val inAmount = inputTokens.fold(0L, summing)
        val outAmount = outputTokens.fold(0L, summing)
        val inOwners = inputTokens.map { it.owner }.toSet()
        val outOwners = outputTokens.map { it.owner }.toSet()
        val inputSigners = sortedInputs.second.map { it.owner.owningKey }

        val inAmountsPerOwner = inputTokens
            .map { it.owner to it.amount }
            .toMultiMap()
            .mapValues { it.value.sum() }
        val outAmountsPerOwner = outputTokens
            .map { it.owner to it.amount }
            .toMultiMap()
            .mapValues { it.value.sum() }

        val minters = inputTokens
            .plus(outputTokens)
            .map { it.minter }
            .toSet()
        requireThat {
            // We could improve and check that the amounts per minter are conserved. We don't care.
            "There should be a single minter" using (minters.size == 1)
        }

        when (command.value) {
            is Commands.Mint -> requireThat {
                "There should be no inputs when Minting" using (inputTokens.isEmpty())
                "There should be at least one output TokenState when Minting" using (outputTokenCount > 0)
                "There should be no output Pot State when Minting" using (outputPotCount == 0)
                "The minter should sign when Minting" using (command.signers.contains(minters.single().owningKey))
            }

            is Commands.Transfer -> requireThat {
                "There should be at least one input TokenState when Transferring" using (inputTokenCount > 0)
                "There should be no input Pot State when Transferring" using (inputPotCount == 0)
                "There should be at least one output TokenState when Transferring" using (outputTokenCount > 0)
                "There should be no output Pot State when Transferring" using (outputPotCount == 0)
                "There should be the same amount in and out when Transferring" using (inAmount == outAmount)
                "Input owners should sign when Transferring" using (command.signers.containsAll(inputSigners))
            }

            is Commands.BetToPot -> requireThat {
                "There should be at least one input TokenState when Betting" using (inputTokenCount > 0)
                // There can be pot states in inputs if we are in a next round
                "There should be no output TokenState when Betting" using (outputTokenCount == 0)
                "There should be at least one output PotState when Betting" using (outputPotCount > 0)
                val sameAmountsPerOwner = inOwners.plus(outOwners).fold(true) { result, key ->
                    result && inAmountsPerOwner[key] == outAmountsPerOwner[key]
                }
                "There should be the same amount in and out per owner when Betting" using (sameAmountsPerOwner)
                "Input token owners should sign when Betting" using (command.signers.containsAll(inputSigners))
            }

            is Commands.Win -> requireThat {
                "There should be no input TokenState when Winning" using (inputTokenCount == 0)
                "There should be at least one input PotState when Winning" using (inputPotCount > 0)
                "There should be at least one output TokenState when Winning" using (outputTokenCount > 0)
                "There should be no output PotState when Winning" using (outputPotCount == 0)
                "There should be the same amount in and out when Winning" using (inAmount == outAmount)
                // There should be something more to make sure it is only on a real win.
            }

            is Commands.Burn -> requireThat {
                "There should be at least one input TokenState when Burning" using (inputTokenCount > 0)
                "There should be no input PotState when Burning" using (inputPotCount == 0)
                "There should be no output TokenState when Burning" using (outputTokenCount == 0)
                "There should be no output PotState when Burning" using (outputPotCount == 0)
                "Input owners should sign when Burning" using (command.signers.containsAll(inputSigners))
                "The minter should sign when Burning" using (command.signers.contains(minters.single().owningKey))
            }

            else -> throw IllegalArgumentException("Unrecognised command $command")
        }
    }

    interface Commands : CommandData {
        class Mint : Commands
        class Transfer : Commands
        class BetToPot : Commands
        class Win : Commands
        class Burn : Commands
    }
}
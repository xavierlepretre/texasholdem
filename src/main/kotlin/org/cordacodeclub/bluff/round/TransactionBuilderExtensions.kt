package org.cordacodeclub.bluff.round

import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndRef
import net.corda.core.identity.Party
import net.corda.core.internal.toMultiMap
import net.corda.core.transactions.TransactionBuilder
import org.cordacodeclub.bluff.contract.GameContract
import org.cordacodeclub.bluff.contract.TokenContract
import org.cordacodeclub.bluff.state.TokenState

fun TransactionBuilder.addElementsOf(
    inputPotTokens: Map<Party, List<StateAndRef<TokenState>>>,
    accumulated: DealerRoundAccumulator
) {
    addCommand(
        Command(
            TokenContract.Commands.BetToPot(),
            accumulated.newBets.toList().filter { it.second.size > 0 }.map { it.first.owningKey })
    )

    // Add existing pot tokens
    inputPotTokens.forEach { entry ->
        entry.value.forEach { addInputState(it) }
    }

    // Add new bet tokens as inputs
    accumulated.newBets.forEach { entry ->
        entry.value.forEach { addInputState(it) }
    }

    val minter = inputPotTokens.flatMap { entry ->
        entry.value.map { it.state.data.minter }
    }.toSet().single()

    // Create and add new Pot token summaries in outputs
    accumulated.newBets
        .mapValues { entry ->
            entry.value.map { it.state.data.amount }.sum()
        }
        .toList()
        .plus(
            inputPotTokens.mapValues { entry ->
                entry.value.map { it.state.data.amount }.sum()
            }.toList()
        )
        .toMultiMap()
        .forEach { entry ->
            addOutputState(
                TokenState(
                    minter = minter, owner = entry.key,
                    amount = entry.value.sum(), isPot = true
                ),
                GameContract.ID
            )
        }
}

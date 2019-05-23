package org.cordacodeclub.bluff.state

import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.requireThat
import net.corda.core.crypto.MerkleTree
import net.corda.core.identity.AbstractParty
import org.cordacodeclub.bluff.contract.GameContract
import org.cordacodeclub.bluff.dealer.containsAll

@BelongsToContract(GameContract::class)
data class GameState(
    // We anonymise cards like that
    val tree: MerkleTree,
    // At some point the cards that can be disclosed will be added
    val cards: List<AssignedCard?>,
    override val participants: List<AbstractParty>
) : ContractState {

    init {
        requireThat {
            "Non null cards need to be in the tree" using (tree.containsAll(cards.filterNotNull()))
        }
    }
}
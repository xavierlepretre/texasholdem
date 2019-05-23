package org.cordacodeclub.bluff.flow

import net.corda.core.contracts.StateAndRef
import net.corda.core.identity.Party
import net.corda.core.node.services.Vault
import net.corda.core.node.services.VaultService
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.builder
import org.cordacodeclub.bluff.state.TokenSchemaV1
import org.cordacodeclub.bluff.state.TokenState

fun VaultService.collectTokenStatesUntil(
    minter: Party,
    owner: Party,
    amount: Long): List<StateAndRef<TokenState>> {
    if (amount == 0L) return listOf()
    var remainingAmount = amount
    return builder {
        val forMinter = TokenSchemaV1.PersistentToken::minter.equal(minter.toString())
        val forOwner =
            TokenSchemaV1.PersistentToken::owner.equal(owner.toString())
        val forIsNotPot = TokenSchemaV1.PersistentToken::isPot.equal(false)
        val minterCriteria = QueryCriteria.VaultCustomQueryCriteria(forMinter)
        val ownerCriteria = QueryCriteria.VaultCustomQueryCriteria(forOwner)
        val isNotPotCriteria = QueryCriteria.VaultCustomQueryCriteria(forIsNotPot)
        val unconsumedCriteria: QueryCriteria =
            QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.UNCONSUMED)
        val criteria = unconsumedCriteria.and(minterCriteria).and(ownerCriteria).and(isNotPotCriteria)
        this@collectTokenStatesUntil.queryBy<TokenState>(criteria).states
    }.takeWhile {
        // TODO avoid paying more than necessary
        // TODO soft lock the unconsumed states?
        val beforeAmount = remainingAmount
        remainingAmount -= it.state.data.amount
        beforeAmount > 0
    }
}

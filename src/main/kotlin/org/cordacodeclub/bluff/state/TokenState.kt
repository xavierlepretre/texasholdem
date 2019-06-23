package org.cordacodeclub.bluff.state

import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.requireThat
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.internal.toMultiMap
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState
import org.cordacodeclub.bluff.contract.TokenContract

@BelongsToContract(TokenContract::class)
data class TokenState(
    val minter: Party,
    val owner: Party,
    val amount: Long,
    val isPot: Boolean // Signature is required if isPot == false
) : ContractState, QueryableState {
    init {
        requireThat {
            "The value should be positive" using (amount > 0L)
        }
    }

    override val participants: List<AbstractParty>
        get() = listOf(owner)

    override fun supportedSchemas(): List<MappedSchema> = listOf(TokenSchemaV1)

    override fun generateMappedObject(schema: MappedSchema): PersistentState {
        return when (schema) {
            is TokenSchemaV1 -> TokenSchemaV1.PersistentToken(
                this.minter.toString(),
                this.owner.toString(),
                this.amount,
                this.isPot
            )
            else -> throw IllegalArgumentException("Unrecognised schema $schema")
        }
    }
}

fun List<TokenState>.mapPartyToSum() = map { it.owner to it }
    // List of tokens per owner
    .toMultiMap()
    // Reduce to the sum
    .map { entry -> entry.key to entry.value.map { it.amount }.sum() }
    .toMap()
package org.cordacodeclub.bluff.state

import net.corda.core.contracts.ContractState
import net.corda.core.contracts.requireThat
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState

data class TokenState(
    override val minter: Party,
    val owner: Party,
    override val amount: Long
) : ContractState, QueryableState, PokerToken {
    init {
        requireThat {
            "The value should be positive" using (amount > 0L)
        }
    }

    override val participants: List<AbstractParty>
        get() = listOf(owner)

    override fun supportedSchemas(): List<MappedSchema> = listOf(TokenSchemaV1)

    override fun generateMappedObject(schema: MappedSchema): PersistentState {
        return when(schema) {
            is TokenSchemaV1 -> TokenSchemaV1.PersistentToken(
                this.minter.toString(),
                this.owner.toString(),
                this.amount
            )
            else -> throw IllegalArgumentException("Unrecognised schema $schema")
        }
    }
}

interface PokerToken {
    val minter: Party
    val amount: Long
}

class PotState(override val minter: Party, override val amount: Long) : ContractState, PokerToken {

    init {
        requireThat {
            "The value should be positive" using (amount > 0L)
        }
    }

    override val participants: List<AbstractParty>
        get() = listOf()
}
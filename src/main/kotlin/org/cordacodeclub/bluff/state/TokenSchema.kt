package org.cordacodeclub.bluff.state

import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Table

/**
 * The family of schemas for TokenState.
 */
object TokenSchema

/**
 * A TokenState schema.
 */
object TokenSchemaV1 : MappedSchema(
    schemaFamily = TokenSchema.javaClass,
    version = 1,
    mappedTypes = listOf(PersistentToken::class.java)
) {

    const val TABLE_NAME = "token_states"
    const val MINTER_NAME = "minter"
    const val OWNER_NAME = "owner"
    const val AMOUNT = "amount"

    @Entity
    @Table(name = TABLE_NAME)
    class PersistentToken(
        @Column(name = MINTER_NAME)
        var minter: String,

        @Column(name = OWNER_NAME)
        var owner: String,

        @Column(name = AMOUNT)
        var amount: Long
    ) : PersistentState() {
        @Suppress("unused")
        // Default constructor required by hibernate.
        constructor() : this("", "", 0)
    }
}
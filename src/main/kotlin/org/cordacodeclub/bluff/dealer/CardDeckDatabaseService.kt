package org.cordacodeclub.bluff.dealer

import net.corda.core.crypto.SecureHash
import net.corda.core.node.ServiceHub
import net.corda.core.node.services.CordaService
import org.cordacodeclub.bluff.db.DatabaseService
import org.cordacodeclub.bluff.state.AssignedCard

/**
 * A database service subclass for handling a table of salted decks of assigned cards. There is one such deck
 * per game. Each deck is identified by its Merkle root hash.
 * @param services The node's service hub.
 */
@CordaService
class CardDeckDatabaseService(services: ServiceHub) : DatabaseService(services) {
    init {
        setUpStorage()
    }

    companion object {
        val tableName = "ASSIGNED_POKER_CARD"
    }

    fun addDeck(deck: CardDeckInfo) {
        val query = "insert into ${tableName} (rootHash, index, card, salt, owner) values(?, ?, ?, ?, ?);"

        val rowCount = deck.cards.mapIndexed { index, card ->
            executeUpdate(
                query, mapOf(
                    1 to deck.rootHash,
                    2 to index,
                    3 to card.card.toString(),
                    4 to card.salt,
                    5 to card.owner.toString()
                )
            )
        }.sum()
        // Number may be smaller than 52 if rows already exist.
        log.info("CardDeckInfo $deck added $rowCount rows to $tableName table.")
    }

    fun getCardDeck(rootHash: SecureHash): CardDeckInfo? {
        val query = "select index, card, salt, owner from $tableName where rootHash = ?"

        val params = mapOf(1 to rootHash.bytes)
        val cards = executeQuery(query, params) {
            AssignedCard(
                card = it.getString("card"),
                salt = it.getString("salt"),
                owner = it.getString("owner")
            )
        }

        log.info("Selected ${cards.size} cards from $tableName table.")
        return when (cards.size) {
            0 -> null
            52 -> CardDeckInfo(cards)
            else -> throw IllegalArgumentException("Collected wrong count of ${cards.size}")
        }
    }

    fun getTopDeckRootHashes(n: Int) =
        "select distinct rootHash from $tableName limit ?".let { query ->
            executeQuery(query, mapOf(1 to n)) {
                SecureHash.SHA256(it.getBytes("rootHash"))
            }.also { results ->
                log.info("Selected ${results.size} card decks from $tableName table.")
            }
        }

    private fun setUpStorage() {
        val createTable = """
            create table if not exists $tableName(
                rootHash binary(32) not null,
                index int not null,
                card char(2) not null,
                salt char(50) not null,
                owner varchar(256) not null
            );
            alter table $tableName
                add primary key (rootHash, index)
        """.trimIndent()
        executeUpdate(createTable, emptyMap())

        log.info("Created $tableName table.")
    }
}
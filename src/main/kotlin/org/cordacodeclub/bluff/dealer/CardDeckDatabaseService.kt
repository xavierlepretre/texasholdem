package org.cordacodeclub.bluff.dealer

import net.corda.core.crypto.SecureHash
import net.corda.core.node.ServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.CordaSerializable
import org.cordacodeclub.bluff.db.DatabaseService
import org.cordacodeclub.bluff.state.AssignedCard

/**
 * A database service subclass for handling a table of salted decks of assigned cards. There is one such deck
 * per game. Each deck is identified by its Merkle root hash.
 * @param services The node's service hub.
 */
class CardDeckDatabaseService(services: ServiceHub) : DatabaseService(services) {
    init {
        setUpStorage()
    }

    companion object {
        val tableName = "ASSIGNED_POKER_CARD"
    }

    fun getIncompleteAssignedCard(rootHash: SecureHash, index: Int): IncompleteAssignedCard? {
        val query = "select rootHash, index, hash, card, salt, owner from $tableName " +
                "where rootHash = ? and index = ?"
        val params = mapOf(
            1 to rootHash.bytes,
            2 to index
        )
        val existing = executeQuery(query, params) {
            // Check values are null or did not change
            IncompleteAssignedCard(
                card = it.getString("card"),
                salt = it.getBytes("salt"),
                owner = it.getString("owner"),
                hash = it.getBytes("hash")!!
            )
        }
        return when (existing.size) {
            0 -> null
            1 -> existing[0]
            else -> throw IllegalArgumentException("Wrong number of cards found: ${existing.size}")
        }
    }

    fun safeAddHashedDeck(deck: HashedCardDeckInfo) = deck.hashedCards
        .mapIndexed { index, _ ->
            safeAddHashedCard(deck, index)
        }.sum()


    fun safeAddHashedCard(deck: HashedCardDeckInfo, index: Int) =
        when (val currentCard = getIncompleteAssignedCard(deck.rootHash, index)) {
            // Insert when missing
            null -> insertCard(deck.rootHash, index, IncompleteAssignedCard(deck.hashedCards[index]))
            // Be idempotent if it exists already
            else -> 1.also {
                // Early warning of future foul play
                require(currentCard.hash.bytes.contentEquals(deck.hashedCards[index].bytes)) {
                    "You should not overwrite an existing card's hash"
                }
            }
        }

    fun safeAddIncompleteDeck(deck: IncompleteCardDeckInfo) = deck.cards
        .mapIndexed { index, _ ->
            safeAddIncompleteCard(deck, index)
        }.sum()

    fun safeAddIncompleteCard(deck: IncompleteCardDeckInfo, index: Int): Int {
        val currentCard = getIncompleteAssignedCard(deck.rootHash, index)
        val incompleteCard = IncompleteAssignedCard(deck.cards[index], deck.hashedCards[index])

        return if (currentCard == null) insertCard(deck.rootHash, index, incompleteCard)
        else if (incompleteCard.addsTo(currentCard)) updateCard(deck.rootHash, index, incompleteCard.toAssignedCard()!!)
        // Be idempotent
        else 1

    }

    private fun insertCard(rootHash: SecureHash, index: Int, card: IncompleteAssignedCard): Int {
        val query = "insert into $tableName (rootHash, index, hash" +
                "${if (card.hasNullable) "" else ", card, salt, owner"}) " +
                "values(?, ?, ?${if (card.hasNullable) "" else ", ?, ?, ?"});"
        val params = mapOf(
            1 to rootHash.bytes,
            2 to index,
            3 to card.hash.bytes
        ).let { params ->
            if (card.hasNullable) params
            else params
                .plus(4 to card.card!!.toString())
                .plus(5 to card.salt!!)
                .plus(6 to card.owner!!.toString())
        }
        return executeUpdate(query, params)
    }

    private fun updateCard(rootHash: SecureHash, index: Int, card: AssignedCard): Int {
        val query = "update $tableName set card = ?, salt = ?, owner = ? where rootHash = ? and index = ?"
        val params = mapOf(
            // Never revert to null, only augment the information
            1 to card.card.toString(),
            2 to card.salt,
            3 to card.owner.toString(),
            4 to rootHash.bytes,
            5 to index
        )
        return executeUpdate(query, params)
    }

    @Deprecated("Use safe version")
    fun addHashedDeck(deck: HashedCardDeckInfo) {
        val query = "insert into ${tableName} (rootHash, index, hash) values(?, ?, ?);"

        val rowCount = deck.hashedCards.mapIndexed { index, hash ->
            executeUpdate(
                query, mapOf(
                    1 to deck.rootHash.bytes,
                    2 to index,
                    3 to hash.bytes
                )
            )
        }.sum()
        // Number may be smaller than 52 if rows already exist.
        log.info("HashedCardDeckInfo $deck added $rowCount rows to $tableName table.")
    }

    @Deprecated("Use safe version")
    fun addDeck(deck: CardDeckInfo) {
        val query = "insert into ${tableName} (rootHash, index, hash, card, salt, owner) values(?, ?, ?, ?, ?, ?);"

        val rowCount = deck.cards.mapIndexed { index, card ->
            executeUpdate(
                query, mapOf(
                    1 to deck.rootHash.bytes,
                    2 to index,
                    3 to card.hash.bytes,
                    4 to card.card.toString(),
                    5 to card.salt,
                    6 to card.owner.toString()
                )
            )
        }.sum()
        // Number may be smaller than 52 if rows already exist.
        log.info("CardDeckInfo $deck added $rowCount rows to $tableName table.")
    }

    fun getCardDeck(rootHash: SecureHash): CardDeckInfo? {
        val query = "select index, card, salt, owner from $tableName where rootHash = ? order by index asc"

        val params = mapOf(1 to rootHash.bytes)
        val cards = executeQuery(query, params) {
            AssignedCard(
                card = it.getString("card"),
                salt = it.getBytes("salt"),
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

    //Possibly save the hash leaves of the deck to do internal checks for card verification
    private fun setUpStorage() {
        val createTable = """
            create table if not exists $tableName(
                rootHash binary(32) not null,
                index int not null,
                hash binary(32) not null,
                card char(2),
                salt binary(50) unique,
                owner varchar(256),
                constraint unique_rootHash_card unique (rootHash, card)
            );
            alter table $tableName
                add primary key (rootHash, index)
        """.trimIndent()
        executeUpdate(createTable, emptyMap())

        log.info("Created $tableName table.")
    }
}
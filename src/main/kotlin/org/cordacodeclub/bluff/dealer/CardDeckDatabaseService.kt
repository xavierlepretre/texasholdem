package org.cordacodeclub.bluff.dealer

import net.corda.core.node.ServiceHub
import net.corda.core.node.services.CordaService
import org.cordacodeclub.bluff.db.DatabaseService
import org.cordacodeclub.grom356.CardList

/**
 * A database service subclass for handling a table of salted decks of cards. There is one such deck
 * per game.
 * @param services The node's service hub.
 */
@CordaService
class CardDeckDatabaseService(services: ServiceHub) : DatabaseService(services) {
    init {
        setUpStorage()
    }

    companion object {
        val tableName = "POKER_CARD_DECK"
    }

    /**
     */
    fun addCardDeck(deck: CardDeckInfo): Pair<Int, CardDeckInfo> {
        val query = "insert into ${tableName} (cards, salts, rootHash) values(?, ?, ?);"
        val params = mapOf(
            1 to CardList.toString(deck.cards.asSequence()),
            2 to CardDeckInfo.toString(deck.salts.asSequence()),
            3 to deck.rootHash.bytes)

        val rowCount = executeUpdate(query, params)
        log.info("CardDeckInfo $deck added to $tableName table.")
        val lastIdQuery = "select last_insert_id();"
        val lastId = executeQuery(lastIdQuery, mapOf()) {
            it.getLong("last_insert_id")
        }.single()
        return rowCount to deck.copy(id = lastId)
    }

    /**
     */
    fun getCardDeck(id: Long): CardDeckInfo? {
        val query = "select id, cards, salts from $tableName where id = ?"

        val params = mapOf(1 to id)
        val results = executeQuery(query, params) {
            CardDeckInfo(
                id,
                it.getString("cards"),
                it.getString("salts"),
                it.getBytes("rootHash")
            )
        }

        log.info("Selected ${results.size} card decks from $tableName table.")
        return results.firstOrNull()
    }

    /**
     */
    fun getTopCardDeck(): CardDeckInfo? {
        val query = "select id, cards from $tableName limit 1"

        val results = executeQuery(query, emptyMap()) {
            CardDeckInfo(
                it.getLong("id"),
                it.getString("cards"),
                it.getString("salts"),
                it.getBytes("rootHash")
            )
        }

        log.info("Selected ${results.size} card decks from $tableName table.")
        return results.firstOrNull()
    }

    /**
     * each card is 2 characters long. 2 * 52 + 51 commans + 2 square brackets = 157
     * each salt is 50 characters long. 50 * 52 + 51 commas + 2 square brackets = 2,653
     */
    private fun setUpStorage() {
        val query = """
            create table if not exists $tableName(
                id int not null auto_increment,
                cards char(157) not null,
                salts char(2653) not null,
                rootHash binary(32) not null
            );
            alter table $tableName add primary key (id)"""

        executeUpdate(query, emptyMap())
        log.info("Created $tableName table.")
    }
}
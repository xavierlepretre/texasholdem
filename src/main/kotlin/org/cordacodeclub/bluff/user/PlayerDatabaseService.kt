package org.cordacodeclub.bluff.user

import net.corda.core.node.ServiceHub
import net.corda.core.node.services.CordaService
import org.cordacodeclub.bluff.db.DatabaseService
import org.cordacodeclub.bluff.flow.Action

/**
 * A database service subclass for handling a table of requests to player.
 *
 * @param services The node's service hub.
 */
@CordaService
class PlayerDatabaseService(services: ServiceHub) : DatabaseService(services) {
    init {
        setUpStorage()
    }

    companion object {
        val tableName = "POKER_PLAYER_ACTION"
    }

    /**
     */
    fun addActionRequest(actionRequest: ActionRequest): Pair<Int, ActionRequest> {
        val query = "insert into ${tableName} " +
                "(party, cards, youBet, lastRaise, action, addAmount) " +
                "values(?, ?, ?, ?, ?, ?);"

        val params = mapOf(
            1 to actionRequest.party,
            2 to actionRequest.cards,
            9 to actionRequest.youBet,
            10 to actionRequest.lastRaise,
            11 to "",
            12 to 0
        )

        val rowCount = executeUpdate(query, params)
        log.info("ActionRequest $actionRequest added to $tableName table.")
        val lastIdQuery = "select last_insert_id();"
        val lastId = executeQuery(lastIdQuery, mapOf()) {
            it.getLong("last_insert_id")
        }.single()
        return rowCount to actionRequest.copy(id = lastId)
    }

    /**
     */
    fun updateActionRequest(actionRequest: ActionRequest): Int {
        val query = "update $tableName set action = ?, addAmount =? where id = ?"

        val params = mapOf(
            1 to actionRequest.action!!.name,
            2 to actionRequest.addAmount,
            3 to actionRequest.id
        )

        val rowCount = executeUpdate(query, params)
        log.info("Request ${actionRequest.id} updated in $tableName table. rowCount: $rowCount")
        return rowCount
    }

    /**
     */
    fun getActionRequest(id: Long): ActionRequest? {
        val query = "select party, cards, youBet, " +
                "lastRaise, action, addAmount from $tableName where id = ?"

        val params = mapOf(1 to id)
        val results = executeQuery(query, params) {
            ActionRequest(
                id,
                it.getString("party"),
                it.getString("cards"),
                it.getLong("youBet"),
                it.getLong("lastRaise"),
                Action.valueOf(it.getString("action")),
                it.getLong("addAmount")
            )
        }

        log.info("Selected ${results.size} requests from $tableName table.")
        return results.firstOrNull()
    }

    /**
     */
    fun getTopActionRequest(): ActionRequest? {
        val query = "select id, party, cards, youBet, " +
                "lastRaise, action, addAmount from $tableName limit 1"

        val results = executeQuery(query, emptyMap()) {
            ActionRequest(
                it.getLong("id"),
                it.getString("party"),
                it.getString("cards"),
                it.getLong("youBet"),
                it.getLong("lastRaise"),
                Action.valueOf(it.getString("action")),
                it.getLong("addAmount")
            )
        }

        log.info("Selected ${results.size} requests from $tableName table.")
        return results.firstOrNull()
    }

    /**
     * Each card is 2 characters. There are at most 7 cards: 7 * 2 + 6 commas + 2 square brackets = 22
     */
    private fun setUpStorage() {
        val query = """
            create table if not exists $tableName(
                id int not null auto_increment,
                party varchar(64) not null,
                cards varchar(22) not null,
                youBet int not null,
                lastRaise int not null,
                action varchar(10) not null,
                addAmount int not null
            );
            alter table $tableName add primary key (id)"""

        executeUpdate(query, emptyMap())
        log.info("Created $tableName table.")
    }
}
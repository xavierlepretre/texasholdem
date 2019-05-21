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
        val query = "insert into ${tableName} values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);"

        val params = mapOf(
            1 to actionRequest.party,
            2 to actionRequest.card1,
            3 to actionRequest.card2,
            4 to actionRequest.card3,
            5 to actionRequest.card4,
            6 to actionRequest.card5,
            7 to actionRequest.card6,
            8 to actionRequest.card7,
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
        val query = "select party, card1, card2, card3, card4, card5, card6, card7, youBet, " +
                "lastRaise, action, addAmount from $tableName where id = ?"

        val params = mapOf(1 to id)
        val results = executeQuery(query, params) {
            ActionRequest(
                id,
                it.getString("party"),
                it.getString("card1"),
                it.getString("card2"),
                it.getString("card3"),
                it.getString("card4"),
                it.getString("card5"),
                it.getString("card6"),
                it.getString("card7"),
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
        val query = "select id, party, card1, card2, card3, card4, card5, card6, card7, youBet, " +
                "lastRaise, action, addAmount from $tableName limit 1"

        val results = executeQuery(query, emptyMap()) {
            ActionRequest(
                it.getLong("id"),
                it.getString("party"),
                it.getString("card1"),
                it.getString("card2"),
                it.getString("card3"),
                it.getString("card4"),
                it.getString("card5"),
                it.getString("card6"),
                it.getString("card7"),
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
    private fun setUpStorage() {
        val query = """
            create table if not exists $tableName(
                id int not null auto_increment,
                party varchar(64) not null,
                card1 varchar(2) not null,
                card2 varchar(2) not null,
                card3 varchar(2) not null,
                card4 varchar(2) not null,
                card5 varchar(2) not null,
                card6 varchar(2) not null,
                card7 varchar(2) not null,
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
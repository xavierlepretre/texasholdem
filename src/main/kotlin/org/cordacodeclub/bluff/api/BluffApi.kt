package org.cordacodeclub.bluff.api

import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.startFlow
import net.corda.core.messaging.startTrackedFlow
import net.corda.core.node.services.IdentityService
import net.corda.core.utilities.loggerFor
import org.cordacodeclub.bluff.flow.BlindBetFlow
import org.slf4j.Logger
import sun.security.timestamp.TSResponse.BAD_REQUEST
import javax.ws.rs.*
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response

val SERVICE_NAMES = listOf("Network Map Service")

@Path("bluff")
class BluffApi(private val rpcOps: CordaRPCOps) {
    private val myLegalName: CordaX500Name = rpcOps.nodeInfo().legalIdentities.first().name

    companion object {
        private val logger: Logger = loggerFor<BluffApi>()
    }

    /**
     * Returns the node's name.
     */
    @GET
    @Path("me")
    @Produces(MediaType.APPLICATION_JSON)
    fun whoami() = mapOf("me" to myLegalName)

    /**
     * Returns all parties registered with the [NetworkMapService]. These names can be used to look up identities
     * using the [IdentityService].
     */
    @GET
    @Path("players")
    @Produces(MediaType.APPLICATION_JSON)
    fun getPeers(): Map<String, List<CordaX500Name>> {
        val nodeInfo = rpcOps.networkMapSnapshot()
        return mapOf("players" to nodeInfo
                .map { it.legalIdentities.first().name }
                //filter out myself and eventual network map started by driver
                //add dealer later
                .filter { it.organisation !in (SERVICE_NAMES + myLegalName.organisation) })
    }


    /**
     * Initial flow that is called by dealer to create blind bets, tokens and betting pot for the players
     *
     *
     */
    @PUT
    @Path("go-direct-agreement")
    fun startInitialBetting(
            @QueryParam("players") players: List<String>,
            @QueryParam("minter") minter: String,
            @QueryParam("smallBet") smallBet: Long
    ): Response {
        return try {
            val playerParties = players.map { rpcOps.partiesFromName(it, true).single() }
            val minterParty = rpcOps.partiesFromName(minter, true).single()
            //val signedTx = rpcOps.startTrackedFlow(::BlindBetFlow(playerParties, minterParty, smallBet))


        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            Response.status(BAD_REQUEST).entity(ex.message!!).build()
        }
    }
}

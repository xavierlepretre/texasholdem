package org.cordacodeclub.bluff.api

import net.corda.core.crypto.SecureHash
import org.cordacodeclub.bluff.flow.BlindBetFlow
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.startFlow
import net.corda.core.node.services.IdentityService
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.loggerFor
import org.cordacodeclub.bluff.flow.BlindBetFlow.Initiator
import org.cordacodeclub.bluff.flow.CreateGameFlow
import org.cordacodeclub.bluff.flow.MintTokenFlow
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
     * Flow that is called by minter to create tokens for players
     */
    @PUT
    @Path("create-tokens")
    fun createTokens(
            @QueryParam("players") players: List<String>,
            @QueryParam("countPerPlayer") amountPerPlayer: Long,
            @QueryParam("amountPerState") amountPerState: Long
    ): Response {
        if (players.isEmpty()) {
            return Response.status(BAD_REQUEST).entity("Query parameter 'players' missing or has wrong format.\n").build()
        }
        if (amountPerPlayer == null) {
            return Response.status(BAD_REQUEST).entity("Query parameter 'countPerPlayer' missing or has wrong format.\n").build()
        }
        if (amountPerState == null) {
            return Response.status(BAD_REQUEST).entity("Query parameter 'amountPerState' missing or has wrong format.\n").build()
        }
        val playerParties = players.map { rpcOps.partiesFromName(it, true).single() }
        return try {
            val signedTransaction = rpcOps.startFlow(MintTokenFlow::Minter, playerParties, amountPerPlayer, amountPerState)
                    .returnValue.getOrThrow()
            Response.ok(signedTransaction).build()
        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            Response.status(BAD_REQUEST).entity(ex.message!!).build()
        }
    }

    /**
     * Initial flow that is called by dealer to create blind bets, tokens and betting pot for the players
     */
    @PUT
    @Path("create-blind-bets")
    fun createInitialBet(
            @QueryParam("players") players: List<String>,
            @QueryParam("minter") minter: String,
            @QueryParam("smallBet") smallBet: Long
    ): Response {
        if (players.isEmpty()) {
            return Response.status(BAD_REQUEST).entity("Query parameter 'players' missing or has wrong format.\n").build()
        }
        if (minter == null) {
            return Response.status(BAD_REQUEST).entity("Query parameter 'minter' missing or has wrong format.\n").build()
        }
        if (smallBet == null) {
            return Response.status(BAD_REQUEST).entity("Query parameter 'smallBet' missing or has wrong format.\n").build()
        }
        val playerParties = players.map { rpcOps.partiesFromName(it, true).single() }
        val minterParty = rpcOps.partiesFromName(minter, true).single()
        return try {
            val signedTransaction = rpcOps.startFlow(BlindBetFlow::Initiator, playerParties, minterParty, smallBet)
                    .returnValue.getOrThrow()
            Response.ok(signedTransaction).build()
        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            Response.status(BAD_REQUEST).entity(ex.message!!).build()
        }
    }

    /**
     * Flow that is called by dealer to create player cards and collect bets
     */
    @PUT
    @Path("create-game")
    fun createGame(
            @QueryParam("players") players: List<String>,
            @QueryParam("lastRound") previousRoundId: SecureHash
            ): Response {
        if (players == null) {
            return Response.status(BAD_REQUEST).entity("Query parameter 'players' missing or has wrong format.\n").build()
        }
        if (previousRoundId == null) {
            return Response.status(BAD_REQUEST).entity("Query parameter 'previousRoundId' missing or has wrong format.\n").build()
        }
        return try{
            val playerParties = players.map { rpcOps.partiesFromName(it, true).single() }
            val signedTransaction = rpcOps.startFlow(CreateGameFlow::GameCreator, previousRoundId)
                    .returnValue.getOrThrow()
            Response.ok(signedTransaction).build()
        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            Response.status(BAD_REQUEST).entity(ex.message!!).build()
        }
    }
}
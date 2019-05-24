package org.cordacodeclub.bluff.flow

import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.whenever
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.TransactionState
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.testing.core.TestIdentity
import org.cordacodeclub.bluff.state.ActivePlayer
import org.cordacodeclub.bluff.state.TokenState
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoundTableAccumulatorTest {

    private val minter = TestIdentity(CordaX500Name("Minter", "London", "GB"))
    private val player0 = TestIdentity(CordaX500Name("Owner0", "Madrid", "ES"))
    private val player1 = TestIdentity(CordaX500Name("Owner1", "Berlin", "DE"))
    private val player2 = TestIdentity(CordaX500Name("Owner2", "Bern", "CH"))

    private fun createToken(owner: Party, amount: Long) = StateAndRef(
        TransactionState(
            TokenState(minter = minter.party, owner = owner, amount = amount, isPot = false), notary = minter.party
        ),
        StateRef(SecureHash.zeroHash, 0)
    )

    private fun create0TxSet(): Set<SignedTransaction> {
        val mocked = mock<SignedTransaction>()
        whenever(mocked.id).then { SecureHash.zeroHash }
        return setOf(mocked)
    }

    @Test
    fun `player2 raised in pot - player0 folds`() {
        val players = listOf(
            ActivePlayer(player0.party, false),
            ActivePlayer(player1.party, false),
            ActivePlayer(player2.party, false)
        )
        val resultRound = RoundTableAccumulator(
            minter = minter.party,
            players = players,
            currentPlayerIndex = 0,
            committedPotSums = mapOf(player2.party to 10L),
            newBets = emptyMap(),
            newTransactions = setOf(),
            lastRaiseIndex = 2,
            playerCountSinceLastRaise = 0
        ).stepForwardWhenCurrentPlayerSent(CallOrRaiseResponse())

        assertEquals(minter.party, resultRound.minter)
        assertEquals(
            listOf(
                ActivePlayer(player0.party, true),
                ActivePlayer(player1.party, false),
                ActivePlayer(player2.party, false)
            ),
            resultRound.players
        )
        assertEquals(1, resultRound.currentPlayerIndex)
        assertEquals(mapOf(player2.party to 10L), resultRound.committedPotSums)
        assertEquals(emptyMap<Party, List<StateAndRef<TokenState>>>(), resultRound.newBets)
        assertEquals(2, resultRound.lastRaiseIndex)
        assertEquals(0, resultRound.playerCountSinceLastRaise)
        assertEquals(player1.party, resultRound.currentPlayer)
        assertEquals(0, resultRound.currentPlayerSum)
        assertEquals(10L, resultRound.currentLevel)
        assertEquals(2, resultRound.nextActivePlayerIndex)
        assertEquals(2, resultRound.activePlayerCount)
        assertFalse(resultRound.isRoundDone)
    }

    @Test
    fun `player2 raised in pot - player0 calls`() {
        val players = listOf(
            ActivePlayer(player0.party, false),
            ActivePlayer(player1.party, false),
            ActivePlayer(player2.party, false)
        )
        val resultRound = RoundTableAccumulator(
            minter = minter.party,
            players = players,
            currentPlayerIndex = 0,
            committedPotSums = mapOf(player2.party to 10L),
            newBets = emptyMap(),
            newTransactions = setOf(),
            lastRaiseIndex = 2,
            playerCountSinceLastRaise = 0
        ).stepForwardWhenCurrentPlayerSent(
            CallOrRaiseResponse(
                listOf(createToken(player0.party, 10L)),
                create0TxSet()
            )
        )

        assertEquals(minter.party, resultRound.minter)
        assertEquals(players, resultRound.players)
        assertEquals(1, resultRound.currentPlayerIndex)
        assertEquals(mapOf(player2.party to 10L), resultRound.committedPotSums)
        assertEquals(
            mapOf(player0.party to listOf(createToken(player0.party, 10L))),
            resultRound.newBets
        )
        assertEquals(2, resultRound.lastRaiseIndex)
        assertEquals(1, resultRound.playerCountSinceLastRaise)
        assertEquals(player1.party, resultRound.currentPlayer)
        assertEquals(0, resultRound.currentPlayerSum)
        assertEquals(10L, resultRound.currentLevel)
        assertEquals(2, resultRound.nextActivePlayerIndex)
        assertEquals(3, resultRound.activePlayerCount)
        assertFalse(resultRound.isRoundDone)
    }

    @Test
    fun `player2 raised in pot - player0 raises`() {
        val players = listOf(
            ActivePlayer(player0.party, false),
            ActivePlayer(player1.party, false),
            ActivePlayer(player2.party, false)
        )
        val resultRound = RoundTableAccumulator(
            minter = minter.party,
            players = players,
            currentPlayerIndex = 0,
            committedPotSums = mapOf(player2.party to 10L),
            newBets = emptyMap(),
            newTransactions = setOf(),
            lastRaiseIndex = 2,
            playerCountSinceLastRaise = 0
        ).stepForwardWhenCurrentPlayerSent(
            CallOrRaiseResponse(
                listOf(createToken(player0.party, 20L)),
                create0TxSet()
            )
        )

        assertEquals(minter.party, resultRound.minter)
        assertEquals(players, resultRound.players)
        assertEquals(1, resultRound.currentPlayerIndex)
        assertEquals(mapOf(player2.party to 10L), resultRound.committedPotSums)
        assertEquals(
            mapOf(player0.party to listOf(createToken(player0.party, 20L))),
            resultRound.newBets
        )
        assertEquals(0, resultRound.lastRaiseIndex)
        assertEquals(0, resultRound.playerCountSinceLastRaise)
        assertEquals(player1.party, resultRound.currentPlayer)
        assertEquals(0, resultRound.currentPlayerSum)
        assertEquals(20L, resultRound.currentLevel)
        assertEquals(2, resultRound.nextActivePlayerIndex)
        assertEquals(3, resultRound.activePlayerCount)
        assertFalse(resultRound.isRoundDone)
    }

    @Test
    fun `player1 raised in pot - player2 calls - player0 raises`() {
        val players = listOf(
            ActivePlayer(player0.party, false),
            ActivePlayer(player1.party, false),
            ActivePlayer(player2.party, false)
        )
        val resultRound = RoundTableAccumulator(
            minter = minter.party,
            players = players,
            currentPlayerIndex = 2,
            committedPotSums = mapOf(player1.party to 10L),
            newBets = emptyMap(),
            newTransactions = setOf(),
            lastRaiseIndex = 1,
            playerCountSinceLastRaise = 0
        ).stepForwardWhenCurrentPlayerSent(
            CallOrRaiseResponse(
                listOf(createToken(player2.party, 10L)),
                create0TxSet()
            )
        ).stepForwardWhenCurrentPlayerSent(
            CallOrRaiseResponse(
                listOf(createToken(player0.party, 15L)),
                create0TxSet()
            )
        )

        assertEquals(minter.party, resultRound.minter)
        assertEquals(players, resultRound.players)
        assertEquals(1, resultRound.currentPlayerIndex)
        assertEquals(mapOf(player1.party to 10L), resultRound.committedPotSums)
        assertEquals(
            mapOf(
                player2.party to listOf(createToken(player2.party, 10L)),
                player0.party to listOf(createToken(player0.party, 15L))
            ),
            resultRound.newBets
        )
        assertEquals(0, resultRound.lastRaiseIndex)
        assertEquals(0, resultRound.playerCountSinceLastRaise)
        assertEquals(player1.party, resultRound.currentPlayer)
        assertEquals(10L, resultRound.currentPlayerSum)
        assertEquals(15L, resultRound.currentLevel)
        assertEquals(2, resultRound.nextActivePlayerIndex)
        assertEquals(3, resultRound.activePlayerCount)
        assertFalse(resultRound.isRoundDone)
    }

    @Test
    fun `player1 raised in pot - player2 calls - player0 calls - player1 calls`() {
        val players = listOf(
            ActivePlayer(player0.party, false),
            ActivePlayer(player1.party, false),
            ActivePlayer(player2.party, false)
        )
        val resultRound = RoundTableAccumulator(
            minter = minter.party,
            players = players,
            currentPlayerIndex = 2,
            committedPotSums = mapOf(player1.party to 10L),
            newBets = emptyMap(),
            newTransactions = setOf(),
            lastRaiseIndex = 1,
            playerCountSinceLastRaise = 0
        ).stepForwardWhenCurrentPlayerSent(
            CallOrRaiseResponse(
                listOf(createToken(player2.party, 10L)),
                create0TxSet()
            )
        ).stepForwardWhenCurrentPlayerSent(
            CallOrRaiseResponse(
                listOf(createToken(player0.party, 10L)),
                create0TxSet()
            )
        ).stepForwardWhenCurrentPlayerSent(
            CallOrRaiseResponse(listOf(), setOf())
        )

        assertEquals(minter.party, resultRound.minter)
        assertEquals(players, resultRound.players)
        assertEquals(2, resultRound.currentPlayerIndex)
        assertEquals(mapOf(player1.party to 10L), resultRound.committedPotSums)
        assertEquals(
            mapOf(
                player2.party to listOf(createToken(player2.party, 10L)),
                player0.party to listOf(createToken(player0.party, 10L)),
                player1.party to listOf()
            ),
            resultRound.newBets
        )
        assertEquals(1, resultRound.lastRaiseIndex)
        assertEquals(3, resultRound.playerCountSinceLastRaise)
        assertEquals(player2.party, resultRound.currentPlayer)
        assertEquals(10L, resultRound.currentPlayerSum)
        assertEquals(10L, resultRound.currentLevel)
        assertEquals(0, resultRound.nextActivePlayerIndex)
        assertEquals(3, resultRound.activePlayerCount)
        assertTrue(resultRound.isRoundDone)
    }

    @Test
    fun `player1 raised in pot - player2 folds - player0 calls - player1 calls`() {
        val players = listOf(
            ActivePlayer(player0.party, false),
            ActivePlayer(player1.party, false),
            ActivePlayer(player2.party, false)
        )
        val resultRound = RoundTableAccumulator(
            minter = minter.party,
            players = players,
            currentPlayerIndex = 2,
            committedPotSums = mapOf(player1.party to 10L),
            newBets = emptyMap(),
            newTransactions = setOf(),
            lastRaiseIndex = 1,
            playerCountSinceLastRaise = 0
        ).stepForwardWhenCurrentPlayerSent(
            CallOrRaiseResponse()
        ).stepForwardWhenCurrentPlayerSent(
            CallOrRaiseResponse(
                listOf(createToken(player0.party, 10L)),
                create0TxSet()
            )
        ).stepForwardWhenCurrentPlayerSent(
            CallOrRaiseResponse(listOf(), setOf())
        )

        assertEquals(minter.party, resultRound.minter)
        assertEquals(
            listOf(
                ActivePlayer(player0.party, false),
                ActivePlayer(player1.party, false),
                ActivePlayer(player2.party, true)
            ),
            resultRound.players
        )
        assertEquals(0, resultRound.currentPlayerIndex)
        assertEquals(mapOf(player1.party to 10L), resultRound.committedPotSums)
        assertEquals(
            mapOf(
                player0.party to listOf(createToken(player0.party, 10L)),
                player1.party to listOf()
            ),
            resultRound.newBets
        )
        assertEquals(1, resultRound.lastRaiseIndex)
        assertEquals(2, resultRound.playerCountSinceLastRaise)
        assertEquals(player0.party, resultRound.currentPlayer)
        assertEquals(10L, resultRound.currentPlayerSum)
        assertEquals(10L, resultRound.currentLevel)
        assertEquals(1, resultRound.nextActivePlayerIndex)
        assertEquals(2, resultRound.activePlayerCount)
        assertTrue(resultRound.isRoundDone)
    }

    @Test
    fun `player1 raised in pot - player2 raises - player0 folds - player1 folds`() {
        val players = listOf(
            ActivePlayer(player0.party, false),
            ActivePlayer(player1.party, false),
            ActivePlayer(player2.party, false)
        )
        val resultRound = RoundTableAccumulator(
            minter = minter.party,
            players = players,
            currentPlayerIndex = 2,
            committedPotSums = mapOf(player1.party to 10L),
            newBets = emptyMap(),
            newTransactions = setOf(),
            lastRaiseIndex = 1,
            playerCountSinceLastRaise = 0
        ).stepForwardWhenCurrentPlayerSent(
            CallOrRaiseResponse(
                listOf(createToken(player2.party, 15L)),
                create0TxSet()
            )
        ).stepForwardWhenCurrentPlayerSent(
            CallOrRaiseResponse()
        ).stepForwardWhenCurrentPlayerSent(CallOrRaiseResponse())

        assertEquals(minter.party, resultRound.minter)
        assertEquals(
            listOf(
                ActivePlayer(player0.party, true),
                ActivePlayer(player1.party, true),
                ActivePlayer(player2.party, false)
            ),
            resultRound.players
        )
        assertEquals(2, resultRound.currentPlayerIndex)
        assertEquals(mapOf(player1.party to 10L), resultRound.committedPotSums)
        assertEquals(
            mapOf(player2.party to listOf(createToken(player2.party, 15L))),
            resultRound.newBets
        )
        assertEquals(2, resultRound.lastRaiseIndex)
        assertEquals(0, resultRound.playerCountSinceLastRaise)
        assertEquals(player2.party, resultRound.currentPlayer)
        assertEquals(15L, resultRound.currentPlayerSum)
        assertEquals(15L, resultRound.currentLevel)
        assertEquals(2, resultRound.nextActivePlayerIndex)
        assertEquals(1, resultRound.activePlayerCount)
        assertTrue(resultRound.isRoundDone)
    }
}

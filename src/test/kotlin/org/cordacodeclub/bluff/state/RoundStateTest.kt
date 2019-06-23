package org.cordacodeclub.bluff.state

import net.corda.core.crypto.SecureHash
import net.corda.core.identity.CordaX500Name
import net.corda.testing.core.TestIdentity
import net.corda.testing.node.MockServices
import org.cordacodeclub.bluff.player.PlayerAction
import org.cordacodeclub.bluff.round.BettingRound
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoundStateTest {

    private val ledgerServices = MockServices()
    private val minter0 = TestIdentity(CordaX500Name("Minter0", "London", "GB"))
    private val minter1 = TestIdentity(CordaX500Name("Minter1", "Paris", "FR"))
    private val dealer0 = TestIdentity(CordaX500Name("Dealer0", "Madrid", "ES"))
    private val dealer1 = TestIdentity(CordaX500Name("Dealer1", "Berlin", "DE"))
    private val player0 = TestIdentity(CordaX500Name("Player0", "Madrid", "ES"))
    private val player1 = TestIdentity(CordaX500Name("Player1", "Berlin", "DE"))
    private val player2 = TestIdentity(CordaX500Name("Player2", "Berlin", "DE"))

    private val players = listOf(player0.party, player1.party, player2.party)
    private val validState = RoundState(
        minter = minter0.party, dealer = dealer0.party,
        deckRootHash = SecureHash.zeroHash, roundType = BettingRound.BLIND_BET_1, currentPlayerIndex = 0,
        players = withActions(PlayerAction.Missing, PlayerAction.Missing, PlayerAction.Missing),
        lastRaiseIndex = 0, playerCountSinceLastRaise = 0
    )

    private fun withActions(action0: PlayerAction, action1: PlayerAction, action2: PlayerAction) =
        listOf(
            PlayedAction(player0.party, action0),
            PlayedAction(player1.party, action1),
            PlayedAction(player2.party, action2)
        )

    @Test
    fun `does not accept negative currentPlayerIndex`() {
        assertFailsWith(IllegalArgumentException::class, "currentPlayerIndex should be positive") {
            validState.copy(currentPlayerIndex = -1)
        }
    }

    @Test
    fun `does too large currentPlayerIndex`() {
        assertFailsWith(
            IllegalArgumentException::class,
            "currentPlayerIndex should be less than players size"
        ) {
            validState.copy(currentPlayerIndex = 3)
        }
    }

    @Test
    fun `does not accept negative lastRaiseIndex`() {
        assertFailsWith(IllegalArgumentException::class, "lastRaiseIndex should be positive") {
            validState.copy(lastRaiseIndex = -1)
        }
    }

    @Test
    fun `does not accept too small player count`() {
        assertFailsWith(IllegalArgumentException::class, "There should be at least 3 players") {
            validState.copy(players = listOf(PlayedAction(player0.party, PlayerAction.Call)))
        }
    }

    @Test
    fun `does too large lastRaiseIndex`() {
        assertFailsWith(
            IllegalArgumentException::class,
            "lastRaiseIndex should be less than players size"
        ) {
            validState.copy(lastRaiseIndex = 3)
        }
    }

    @Test
    fun `does not accept last raise index folded`() {
        assertFailsWith(IllegalArgumentException::class, "last raise player cannot be folded") {
            validState.copy(
                players = listOf(
                    PlayedAction(player0.party, PlayerAction.Fold),
                    PlayedAction(player1.party, PlayerAction.Call),
                    PlayedAction(player2.party, PlayerAction.Call)
                )
            )
        }
    }

    @Test
    fun `does not accept if all players are folded`() {
        assertFailsWith(IllegalArgumentException::class, "At least 1 player must not be folded") {
            validState.copy(players = listOf(player0.party, player1.party)
                .map { PlayedAction(it, PlayerAction.Fold) })
        }
    }

    @Test
    fun `does not accept negative playerCountSinceLastRaise`() {
        assertFailsWith(IllegalArgumentException::class, "playerCountSinceLastRaise should be positive") {
            validState.copy(playerCountSinceLastRaise = -1)
        }
    }

    @Test
    fun `does too large playerCountSinceLastRaise`() {
        assertFailsWith(
            IllegalArgumentException::class,
            "playerCountSinceLastRaise should be less than players size"
        ) {
            validState.copy(playerCountSinceLastRaise = 4)
        }
    }

    @Test
    fun `participants also include dealer`() {
        assertEquals(
            setOf(dealer0.party, player0.party, player1.party, player2.party),
            validState.participants.toSet()
        )
    }

    @Test
    fun `currentPlayer is the correct one`() {
        assertEquals(player0.party, validState.currentPlayer)
        assertEquals(player1.party, validState.copy(currentPlayerIndex = 1).currentPlayer)
        assertEquals(player2.party, validState.copy(currentPlayerIndex = 2).currentPlayer)
    }

    @Test
    fun `lastRaisePlayer is the correct one`() {
        assertEquals(player0.party, validState.lastRaisePlayer)
        assertEquals(player1.party, validState.copy(lastRaiseIndex = 1).lastRaisePlayer)
        assertEquals(player2.party, validState.copy(lastRaiseIndex = 2).lastRaisePlayer)
    }

    @Test
    fun `nextActivePlayerIndex when no folded is the correct one`() {
        assertEquals(1, validState.nextActivePlayerIndex)
        assertEquals(2, validState.copy(currentPlayerIndex = 1).nextActivePlayerIndex)
        assertEquals(0, validState.copy(currentPlayerIndex = 2).nextActivePlayerIndex)
    }

    @Test
    fun `nextActivePlayerIndex when current is folded is the correct one`() {
        val folded0 = validState.copy(
            lastRaiseIndex = 1,
            players = listOf(
                PlayedAction(player0.party, PlayerAction.Fold),
                PlayedAction(player1.party, PlayerAction.Call),
                PlayedAction(player2.party, PlayerAction.Call)
            )
        )
        assertEquals(1, folded0.nextActivePlayerIndex)
        assertEquals(2, folded0.copy(currentPlayerIndex = 1).nextActivePlayerIndex)
        assertEquals(1, folded0.copy(currentPlayerIndex = 2).nextActivePlayerIndex)
    }

    @Test
    fun `nextActivePlayerIndex when next is folded is the correct one`() {
        val folded0 = validState.copy(
            players = listOf(
                PlayedAction(player0.party, PlayerAction.Call),
                PlayedAction(player1.party, PlayerAction.Fold),
                PlayedAction(player2.party, PlayerAction.Call)
            )
        )
        assertEquals(2, folded0.nextActivePlayerIndex)
        assertEquals(2, folded0.copy(currentPlayerIndex = 1).nextActivePlayerIndex)
        assertEquals(0, folded0.copy(currentPlayerIndex = 2).nextActivePlayerIndex)
    }

    @Test
    fun `nextActivePlayerIndex when 2 are folded is the correct one`() {
        val folded0 = validState.copy(
            lastRaiseIndex = 2,
            players = listOf(
                PlayedAction(player0.party, PlayerAction.Fold),
                PlayedAction(player1.party, PlayerAction.Fold),
                PlayedAction(player2.party, PlayerAction.Call)
            )
        )
        assertEquals(2, folded0.nextActivePlayerIndex)
        assertEquals(2, folded0.copy(currentPlayerIndex = 1).nextActivePlayerIndex)
        assertEquals(2, folded0.copy(currentPlayerIndex = 2).nextActivePlayerIndex)
    }

    @Test
    fun `nextActivePlayer when no folded is the correct one`() {
        assertEquals(player1.party, validState.nextActivePlayer)
        assertEquals(player2.party, validState.copy(currentPlayerIndex = 1).nextActivePlayer)
        assertEquals(player0.party, validState.copy(currentPlayerIndex = 2).nextActivePlayer)
    }

    @Test
    fun `nextActivePlayer when current is folded is the correct one`() {
        val folded0 = validState.copy(
            lastRaiseIndex = 1,
            players = listOf(
                PlayedAction(player0.party, PlayerAction.Fold),
                PlayedAction(player1.party, PlayerAction.Call),
                PlayedAction(player2.party, PlayerAction.Call)
            )
        )
        assertEquals(player1.party, folded0.nextActivePlayer)
        assertEquals(player2.party, folded0.copy(currentPlayerIndex = 1).nextActivePlayer)
        assertEquals(player1.party, folded0.copy(currentPlayerIndex = 2).nextActivePlayer)
    }

    @Test
    fun `nextActivePlayer when next is folded is the correct one`() {
        val folded0 = validState.copy(
            players = listOf(
                PlayedAction(player0.party, PlayerAction.Call),
                PlayedAction(player1.party, PlayerAction.Fold),
                PlayedAction(player2.party, PlayerAction.Call)
            )
        )
        assertEquals(player2.party, folded0.nextActivePlayer)
        assertEquals(player2.party, folded0.copy(currentPlayerIndex = 1).nextActivePlayer)
        assertEquals(player0.party, folded0.copy(currentPlayerIndex = 2).nextActivePlayer)
    }

    @Test
    fun `nextActivePlayer when 2 are folded is the correct one`() {
        val folded0 = validState.copy(
            lastRaiseIndex = 2,
            players = listOf(
                PlayedAction(player0.party, PlayerAction.Fold),
                PlayedAction(player1.party, PlayerAction.Fold),
                PlayedAction(player2.party, PlayerAction.Call)
            )
        )
        assertEquals(player2.party, folded0.nextActivePlayer)
        assertEquals(player2.party, folded0.copy(currentPlayerIndex = 1).nextActivePlayer)
        assertEquals(player2.party, folded0.copy(currentPlayerIndex = 2).nextActivePlayer)
    }

    @Test
    fun `activePlayerCount is correct`() {
        assertEquals(3, validState.activePlayerCount)
        assertEquals(
            2, validState.copy(
                players = listOf(
                    PlayedAction(player0.party, PlayerAction.Call),
                    PlayedAction(player1.party, PlayerAction.Fold),
                    PlayedAction(player2.party, PlayerAction.Call)
                )
            ).activePlayerCount
        )
        assertEquals(
            1, validState.copy(
                players = listOf(
                    PlayedAction(player0.party, PlayerAction.Call),
                    PlayedAction(player1.party, PlayerAction.Fold),
                    PlayedAction(player2.party, PlayerAction.Fold)
                )
            ).activePlayerCount
        )
    }

    @Test
    fun `isRoundDone is correct`() {
        assertFalse(validState.isRoundDone)
        assertFalse(
            validState.copy(players = withActions(PlayerAction.Missing, PlayerAction.Call, PlayerAction.Call))
                .isRoundDone
        )
        assertFalse(
            validState.copy(players = withActions(PlayerAction.Missing, PlayerAction.Raise, PlayerAction.Call))
                .isRoundDone
        )
        assertFalse(
            validState.copy(players = withActions(PlayerAction.Missing, PlayerAction.Call, PlayerAction.Missing))
                .isRoundDone
        )
        assertFalse(
            validState.copy(players = withActions(PlayerAction.Missing, PlayerAction.Raise, PlayerAction.Missing))
                .isRoundDone
        )
        assertFalse(
            validState.copy(players = withActions(PlayerAction.Raise, PlayerAction.Call, PlayerAction.Call))
                .isRoundDone
        )
        assertFalse(
            validState.copy(players = withActions(PlayerAction.Raise, PlayerAction.Fold, PlayerAction.Call))
                .isRoundDone
        )

        assertTrue(
            validState.copy(players = withActions(PlayerAction.Call, PlayerAction.Fold, PlayerAction.Call))
                .isRoundDone
        )
        assertTrue(
            validState.copy(
                players = withActions(PlayerAction.Fold, PlayerAction.Fold, PlayerAction.Call),
                lastRaiseIndex = 2
            )
                .isRoundDone
        )
        assertTrue(
            validState.copy(players = withActions(PlayerAction.Missing, PlayerAction.Fold, PlayerAction.Fold))
                .isRoundDone
        )
        assertTrue(
            validState.copy(players = withActions(PlayerAction.Call, PlayerAction.Fold, PlayerAction.Fold))
                .isRoundDone
        )
        assertTrue(
            validState.copy(
                players = withActions(PlayerAction.Fold, PlayerAction.Raise, PlayerAction.Fold),
                lastRaiseIndex = 1
            )
                .isRoundDone
        )
    }

}
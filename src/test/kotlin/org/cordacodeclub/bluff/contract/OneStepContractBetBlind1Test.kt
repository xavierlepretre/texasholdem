package org.cordacodeclub.bluff.contract

import net.corda.core.crypto.SecureHash
import net.corda.core.identity.CordaX500Name
import net.corda.testing.core.TestIdentity
import net.corda.testing.node.MockServices
import net.corda.testing.node.ledger
import org.cordacodeclub.bluff.player.PlayerAction
import org.cordacodeclub.bluff.round.BettingRound
import org.cordacodeclub.bluff.state.PlayedAction
import org.cordacodeclub.bluff.state.RoundState
import org.cordacodeclub.bluff.state.TokenState
import org.junit.Test

class OneStepContractBetBlind1Test {

    private val ledgerServices = MockServices()
    private val minter0 = TestIdentity(CordaX500Name("Minter0", "London", "GB"))
    private val dealer0 = TestIdentity(CordaX500Name("Dealer0", "London", "GB"))
    private val player0 = TestIdentity(CordaX500Name("Player0", "Madrid", "ES"))
    private val player1 = TestIdentity(CordaX500Name("Player1", "Berlin", "DE"))
    private val player2 = TestIdentity(CordaX500Name("Player2", "Bern", "CH"))
    private val player3 = TestIdentity(CordaX500Name("Player3", "New York City", "US"))

    private val tokenState0 = TokenState(minter = minter0.party, owner = player0.party, amount = 10, isPot = false)
    private val tokenState1 = TokenState(minter = minter0.party, owner = player1.party, amount = 20, isPot = false)
    private val tokenState2 = TokenState(minter = minter0.party, owner = player2.party, amount = 30, isPot = false)
    private val tokenState3 = TokenState(minter = minter0.party, owner = player3.party, amount = 30, isPot = false)
    private val potState0 = TokenState(minter = minter0.party, owner = player0.party, amount = 10, isPot = true)
    private val potState1 = TokenState(minter = minter0.party, owner = player1.party, amount = 20, isPot = true)
    private val potState2 = TokenState(minter = minter0.party, owner = player2.party, amount = 30, isPot = true)
    private val potState3 = TokenState(minter = minter0.party, owner = player3.party, amount = 30, isPot = true)

    private val validBlind1State = RoundState(
        minter = minter0.party,
        dealer = dealer0.party,
        deckRootHash = SecureHash.zeroHash,
        roundType = BettingRound.BLIND_BET_1,
        currentPlayerIndex = 1,
        // We take the middle player, = 0 would be too standard
        players = withActions(PlayerAction.Missing, PlayerAction.Raise, PlayerAction.Missing)
    )

    private fun withActions(action0: PlayerAction, action1: PlayerAction, action2: PlayerAction) =
        listOf(
            PlayedAction(player0.party, action0),
            PlayedAction(player1.party, action1),
            PlayedAction(player2.party, action2)
        )

    @Test
    fun `BetBlind1 transaction passes with proper parameters`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, tokenState1)
                output(TokenContract.ID, potState1)
                command(listOf(player1.publicKey), TokenContract.Commands.BetToPot())
                output(OneStepContract.ID, validBlind1State)
                command(listOf(dealer0.publicKey, player1.publicKey), OneStepContract.Commands.BetBlind1())
                verifies()
            }
        }
    }

    @Test
    fun `BetBlind1 transaction fails when there is an input RoundState`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, tokenState1)
                output(TokenContract.ID, potState1)
                command(listOf(player1.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind1State)
                output(OneStepContract.ID, validBlind1State)
                command(listOf(dealer0.publicKey, player1.publicKey), OneStepContract.Commands.BetBlind1())
                failsWith("should be no input round state")
            }
        }
    }

    @Test
    fun `BetBlind1 transaction fails when there is more than one output RoundState`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, tokenState1)
                output(TokenContract.ID, potState1)
                command(listOf(player1.publicKey), TokenContract.Commands.BetToPot())
                output(OneStepContract.ID, validBlind1State)
                output(OneStepContract.ID, validBlind1State)
                command(listOf(dealer0.publicKey, player1.publicKey), OneStepContract.Commands.BetBlind1())
                failsWith("should be a single output round state")
            }
        }
    }

    @Test
    fun `BetBlind1 transaction fails when the minter does not match`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, tokenState1)
                output(TokenContract.ID, potState1)
                command(listOf(player1.publicKey), TokenContract.Commands.BetToPot())
                output(OneStepContract.ID, validBlind1State.copy(minter = dealer0.party))
                command(listOf(dealer0.publicKey, player1.publicKey), OneStepContract.Commands.BetBlind1())
                failsWith("minter should be the same across the board")
            }
        }
    }

    @Test
    fun `BetBlind1 transaction fails when the BettingRound is not BlindBet1`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, tokenState1)
                output(TokenContract.ID, potState1)
                command(listOf(player1.publicKey), TokenContract.Commands.BetToPot())
                output(OneStepContract.ID, validBlind1State.copy(roundType = BettingRound.BLIND_BET_2))
                command(listOf(dealer0.publicKey, player1.publicKey), OneStepContract.Commands.BetBlind1())
                failsWith("round bet status should be ${BettingRound.BLIND_BET_1}")
            }
        }
    }

    @Test
    fun `BetBlind1 transaction fails when there are output pot tokens for other than the bettor`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, tokenState1)
                input(TokenContract.ID, tokenState2)
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2)
                command(listOf(player1.publicKey, player2.publicKey), TokenContract.Commands.BetToPot())
                output(OneStepContract.ID, validBlind1State)
                command(listOf(dealer0.publicKey, player1.publicKey), OneStepContract.Commands.BetBlind1())
                failsWith("Only the player should bet tokens")
            }
        }
    }

    @Test
    fun `BetBlind1 transaction fails when there are no output pot tokens for the bettor`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, tokenState0)
                output(TokenContract.ID, potState0)
                command(listOf(player0.publicKey), TokenContract.Commands.BetToPot())
                output(OneStepContract.ID, validBlind1State)
                command(listOf(dealer0.publicKey, player1.publicKey), OneStepContract.Commands.BetBlind1())
                failsWith("Only the player should bet tokens")
            }
        }
    }

    @Test
    fun `BetBlind1 transaction fails when the currentPlayerIndex does not have a Raise action`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, tokenState1)
                output(TokenContract.ID, potState1)
                command(listOf(player1.publicKey), TokenContract.Commands.BetToPot())
                output(
                    OneStepContract.ID,
                    validBlind1State.copy(
                        players = withActions(PlayerAction.Missing, PlayerAction.Missing, PlayerAction.Missing)
                    )
                )
                command(listOf(dealer0.publicKey, player1.publicKey), OneStepContract.Commands.BetBlind1())
                failsWith("currentPlayerIndex should have a raise action")
            }
        }
    }

    @Test
    fun `BetBlind1 transaction fails when the other players do not have a Missing action`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, tokenState1)
                output(TokenContract.ID, potState1)
                command(listOf(player1.publicKey), TokenContract.Commands.BetToPot())
                output(
                    OneStepContract.ID,
                    validBlind1State.copy(
                        players = withActions(PlayerAction.Call, PlayerAction.Raise, PlayerAction.Missing)
                    )
                )
                command(listOf(dealer0.publicKey, player1.publicKey), OneStepContract.Commands.BetBlind1())
                failsWith("other players should have a missing action")
            }
        }
    }

    @Test
    fun `BetBlind1 transaction fails when there are input pot tokens`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, tokenState1)
                input(TokenContract.ID, potState1)
                output(TokenContract.ID, potState1.copy(amount = 40L))
                command(listOf(player1.publicKey), TokenContract.Commands.BetToPot())
                output(OneStepContract.ID, validBlind1State)
                command(listOf(dealer0.publicKey, player1.publicKey), OneStepContract.Commands.BetBlind1())
                failsWith("should be no input pot tokens")
            }
        }
    }

    @Test
    fun `BetBlind1 transaction fails when the dealer did not sign off`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, tokenState1)
                output(TokenContract.ID, potState1)
                command(listOf(player1.publicKey), TokenContract.Commands.BetToPot())
                output(OneStepContract.ID, validBlind1State)
                command(listOf(player0.publicKey, player1.publicKey), OneStepContract.Commands.BetBlind1())
                failsWith("dealer should sign off the deckRootHash")
            }
        }
    }

    @Test
    fun `BetBlind1 transaction fails when the player did not sign off`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, tokenState1)
                output(TokenContract.ID, potState1)
                command(listOf(player1.publicKey), TokenContract.Commands.BetToPot())
                output(OneStepContract.ID, validBlind1State)
                command(listOf(dealer0.publicKey), OneStepContract.Commands.BetBlind1())
                failsWith("currentPlayer should sign off the played action")
            }
        }
    }
}
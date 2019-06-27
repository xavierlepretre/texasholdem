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

class OneStepContractBetBlind2Test {

    private val ledgerServices = MockServices()
    private val minter0 = TestIdentity(CordaX500Name("Minter0", "London", "GB"))
    private val dealer0 = TestIdentity(CordaX500Name("Dealer0", "London", "GB"))
    private val player0 = TestIdentity(CordaX500Name("Player0", "Madrid", "ES"))
    private val player1 = TestIdentity(CordaX500Name("Player1", "Berlin", "DE"))
    private val player2 = TestIdentity(CordaX500Name("Player2", "Bern", "CH"))
    private val player3 = TestIdentity(CordaX500Name("Player3", "New York City", "US"))
    private val notPlayer0 = TestIdentity(CordaX500Name("NotPlayer0", "New York City", "US"))

    private val tokenState0 = TokenState(minter = minter0.party, owner = player0.party, amount = 10, isPot = false)
    private val tokenState1 = TokenState(minter = minter0.party, owner = player1.party, amount = 20, isPot = false)
    private val tokenState2 = TokenState(minter = minter0.party, owner = player2.party, amount = 30, isPot = false)
    private val tokenState3 = TokenState(minter = minter0.party, owner = player3.party, amount = 40, isPot = false)
    private val potState0 = TokenState(minter = minter0.party, owner = player0.party, amount = 10, isPot = true)
    private val potState1 = TokenState(minter = minter0.party, owner = player1.party, amount = 20, isPot = true)
    private val potState2 = TokenState(minter = minter0.party, owner = player2.party, amount = 30, isPot = true)
    private val potState3 = TokenState(minter = minter0.party, owner = player3.party, amount = 40, isPot = true)

    private val validBlind1State = RoundState(
        minter = minter0.party,
        dealer = dealer0.party,
        deckRootHash = SecureHash.zeroHash,
        roundType = BettingRound.BLIND_BET_1,
        currentPlayerIndex = 1,
        // We take the middle player, = 0 would be too standard
        players = withActions(PlayerAction.Missing, PlayerAction.Raise, PlayerAction.Missing)
    )
    private val validBlind2State = RoundState(
        minter = minter0.party,
        dealer = dealer0.party,
        deckRootHash = SecureHash.zeroHash,
        roundType = BettingRound.BLIND_BET_2,
        currentPlayerIndex = 2,
        players = withActions(PlayerAction.Missing, PlayerAction.Raise, PlayerAction.Call)
    )

    private fun withActions(action0: PlayerAction, action1: PlayerAction, action2: PlayerAction) =
        listOf(
            PlayedAction(player0.party, action0),
            PlayedAction(player1.party, action1),
            PlayedAction(player2.party, action2)
        )

    @Test
    fun `BetBlind2 transaction passes with proper parameters`() {
        // Call
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2.copy(amount = 20))
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2.copy(amount = 20))
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind1State)
                output(OneStepContract.ID, validBlind2State)
                command(listOf(player2.publicKey), OneStepContract.Commands.BetBlind2())
                verifies()
            }
        }

        // Raise
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2)
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2)
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind1State)
                output(
                    OneStepContract.ID, validBlind2State
                        .copy(players = withActions(PlayerAction.Missing, PlayerAction.Raise, PlayerAction.Raise))
                )
                command(listOf(player2.publicKey), OneStepContract.Commands.BetBlind2())
                verifies()
            }
        }
    }

    @Test
    fun `BetBlind2 transaction fails when there is no input RoundState`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2.copy(amount = 20))
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2.copy(amount = 20))
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                output(OneStepContract.ID, validBlind2State)
                command(listOf(player2.publicKey), OneStepContract.Commands.BetBlind2())
                failsWith("should be one input round state")
            }
        }
    }

    @Test
    fun `BetBlind2 transaction fails when there is more than one output RoundState`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2.copy(amount = 20))
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2.copy(amount = 20))
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind1State)
                output(OneStepContract.ID, validBlind2State)
                output(OneStepContract.ID, validBlind2State)
                command(listOf(dealer0.publicKey), OneStepContract.Commands.BetBlind2())
                failsWith("should be one output round state")
            }
        }
    }

    @Test
    fun `BetBlind2 transaction fails when the minter changes`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2.copy(amount = 20))
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2.copy(amount = 20))
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind1State)
                output(OneStepContract.ID, validBlind2State.copy(minter = dealer0.party))
                command(listOf(player2.publicKey), OneStepContract.Commands.BetBlind2())
                failsWith("minter should not change")
            }
        }
    }

    @Test
    fun `BetBlind2 transaction fails when the dealer changes`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2.copy(amount = 20))
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2.copy(amount = 20))
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind1State)
                output(OneStepContract.ID, validBlind2State.copy(dealer = minter0.party))
                command(listOf(player2.publicKey), OneStepContract.Commands.BetBlind2())
                failsWith("dealer should not change")
            }
        }
    }

    @Test
    fun `BetBlind2 transaction fails when the deckRootHash changes`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2.copy(amount = 20))
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2.copy(amount = 20))
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind1State)
                output(OneStepContract.ID, validBlind2State.copy(deckRootHash = SecureHash.allOnesHash))
                command(listOf(player2.publicKey), OneStepContract.Commands.BetBlind2())
                failsWith("deckRootHash should not change")
            }
        }
    }

    @Test
    fun `BetBlind2 transaction fails when the players change`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2.copy(amount = 20))
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2.copy(amount = 20))
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind1State)
                output(
                    OneStepContract.ID, validBlind2State.copy(
                        players = listOf(
                            PlayedAction(player0.party, PlayerAction.Missing),
                            PlayedAction(player1.party, PlayerAction.Raise),
                            PlayedAction(notPlayer0.party, PlayerAction.Call)
                        )
                    )
                )
                command(listOf(player2.publicKey), OneStepContract.Commands.BetBlind2())
                failsWith("player parties should not change")
            }
        }
    }

    @Test
    fun `BetBlind2 transaction fails when folded player unfolds`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2.copy(amount = 20))
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2.copy(amount = 20))
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind1State.copy(
                    players = withActions(PlayerAction.Fold, PlayerAction.Raise, PlayerAction.Missing)
                ))
                output(
                    OneStepContract.ID, validBlind2State.copy(
                        players = listOf(
                            PlayedAction(player0.party, PlayerAction.Missing),
                            PlayedAction(player1.party, PlayerAction.Raise),
                            PlayedAction(player2.party, PlayerAction.Call)
                        )
                    )
                )
                command(listOf(player2.publicKey), OneStepContract.Commands.BetBlind2())
                failsWith("folded players should stay folded")
            }
        }
    }

    @Test
    fun `BetBlind2 transaction fails when the BettingRound is not BlindBet2`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2.copy(amount = 20))
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2.copy(amount = 20))
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind1State)
                output(OneStepContract.ID, validBlind2State.copy(roundType = BettingRound.BLIND_BET_1))
                command(listOf(player2.publicKey), OneStepContract.Commands.BetBlind2())
                failsWith("The expected next round type is not there")
            }
        }
    }

    @Test
    fun `BetBlind2 transaction fails when the wrong current player bets`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState0.copy(amount = 20))
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState0.copy(amount = 20))
                command(listOf(player0.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind1State)
                output(
                    OneStepContract.ID, validBlind2State.copy(
                        currentPlayerIndex = 0,
                        players = withActions(PlayerAction.Call, PlayerAction.Raise, PlayerAction.Missing)
                    )
                )
                command(listOf(player0.publicKey), OneStepContract.Commands.BetBlind2())
                failsWith("new currentPlayerIndex should be the previously known as next")
            }
        }
    }

    @Test
    fun `BetBlind2 transaction fails when there are no output pot tokens for the bettor`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState1)
                output(TokenContract.ID, potState1)
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind1State)
                output(OneStepContract.ID, validBlind2State)
                command(listOf(player2.publicKey), OneStepContract.Commands.BetBlind2())
                failsWith("Only the player should bet tokens")
            }
        }
    }

    @Test
    fun `BetBlind2 transaction fails when there are output pot tokens for other than the bettor`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2.copy(amount = 20))
                input(TokenContract.ID, tokenState3)
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2.copy(amount = 20))
                output(TokenContract.ID, potState3)
                command(listOf(player2.publicKey, player3.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind1State)
                output(OneStepContract.ID, validBlind2State)
                command(listOf(player2.publicKey), OneStepContract.Commands.BetBlind2())
                failsWith("Only the player should bet tokens")
            }
        }
    }

    @Test
    fun `BetBlind2 transaction fails when the other players do not have a Missing action`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2.copy(amount = 20))
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2.copy(amount = 20))
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind1State)
                output(
                    OneStepContract.ID, validBlind2State.copy(
                        players = withActions(PlayerAction.Call, PlayerAction.Raise, PlayerAction.Call)
                    )
                )
                command(listOf(player2.publicKey), OneStepContract.Commands.BetBlind2())
                failsWith("other players should have a missing action")
            }
        }
    }

    @Test
    fun `BetBlind2 transaction fails when the second player bet less than the first`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2.copy(amount = 19))
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2.copy(amount = 19))
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind1State)
                output(OneStepContract.ID, validBlind2State)
                command(listOf(player2.publicKey), OneStepContract.Commands.BetBlind2())
                failsWith("second blind bet should be at or above the first")
            }
        }
    }

    @Test
    fun `BetBlind2 transaction fails when the currentPlayerIndex does not have a Call or Raise action`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2.copy(amount = 20))
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2.copy(amount = 20))
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind1State)
                output(
                    OneStepContract.ID, validBlind2State.copy(
                        players = withActions(PlayerAction.Missing, PlayerAction.Raise, PlayerAction.Missing)
                    )
                )
                command(listOf(player2.publicKey), OneStepContract.Commands.BetBlind2())
                failsWith("played action should reflect whether there was a raise")
            }
        }
    }

    @Test
    fun `BetBlind2 transaction fails when the currentPlayerIndex raised but does not have a Raise action`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2)
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2)
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind1State)
                output(OneStepContract.ID, validBlind2State)
                command(listOf(player2.publicKey), OneStepContract.Commands.BetBlind2())
                failsWith("played action should reflect whether there was a raise")
            }
        }
    }

    @Test
    fun `BetBlind2 transaction fails when the currentPlayerIndex called but does not have a Call action`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2.copy(amount = 20))
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2.copy(amount = 20))
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind1State)
                output(
                    OneStepContract.ID,
                    validBlind2State.copy(
                        players = withActions(PlayerAction.Missing, PlayerAction.Raise, PlayerAction.Raise)
                    )
                )
                command(listOf(player2.publicKey), OneStepContract.Commands.BetBlind2())
                failsWith("played action should reflect whether there was a raise")
            }
        }
    }

    @Test
    fun `BetBlind2 transaction fails when the player did not sign off`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2.copy(amount = 20))
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2.copy(amount = 20))
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind1State)
                output(OneStepContract.ID, validBlind2State)
                command(listOf(player1.publicKey), OneStepContract.Commands.BetBlind2())
                failsWith("currentPlayer should sign off the played action")
            }
        }
    }
}
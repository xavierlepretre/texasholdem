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

class OneStepContractPlayTest {

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

    private val validBlind2State = RoundState(
        minter = minter0.party,
        dealer = dealer0.party,
        deckRootHash = SecureHash.zeroHash,
        roundType = BettingRound.BLIND_BET_2,
        currentPlayerIndex = 1,
        players = withActions(PlayerAction.Raise, PlayerAction.Call, PlayerAction.Missing)
    )
    private val validPlayState = RoundState(
        minter = minter0.party,
        dealer = dealer0.party,
        deckRootHash = SecureHash.zeroHash,
        roundType = BettingRound.PRE_FLOP,
        currentPlayerIndex = 2,
        players = withActions(PlayerAction.Missing, PlayerAction.Missing, PlayerAction.Call)
    )

    private fun withActions(action0: PlayerAction, action1: PlayerAction, action2: PlayerAction) =
        listOf(
            PlayedAction(player0.party, action0),
            PlayedAction(player1.party, action1),
            PlayedAction(player2.party, action2)
        )

    @Test
    fun `Play transaction passes with proper parameters`() {
        // Call
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState0)
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2.copy(amount = 20))
                output(TokenContract.ID, potState0)
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2.copy(amount = 20))
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind2State)
                output(OneStepContract.ID, validPlayState)
                command(listOf(player2.publicKey), OneStepContract.Commands.Play())
                verifies()
            }
        }

        // Raise
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState0)
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2)
                output(TokenContract.ID, potState0)
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2)
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind2State)
                output(
                    OneStepContract.ID, validPlayState
                        .copy(players = withActions(PlayerAction.Missing, PlayerAction.Missing, PlayerAction.Raise))
                )
                command(listOf(player2.publicKey), OneStepContract.Commands.Play())
                verifies()
            }
        }
    }

    @Test
    fun `Play transaction fails when the minter changes`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState0)
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2)
                output(TokenContract.ID, potState0)
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2)
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind2State)
                output(OneStepContract.ID, validPlayState.copy(minter = dealer0.party))
                command(listOf(player2.publicKey), OneStepContract.Commands.Play())
                failsWith("minter should not change")
            }
        }
    }

    @Test
    fun `Play transaction fails when the dealer changes`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState0)
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2)
                output(TokenContract.ID, potState0)
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2)
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind2State)
                output(OneStepContract.ID, validPlayState.copy(dealer = minter0.party))
                command(listOf(player2.publicKey), OneStepContract.Commands.Play())
                failsWith("dealer should not change")
            }
        }
    }

    @Test
    fun `Play transaction fails when the deckRootHash changes`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState0)
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2)
                output(TokenContract.ID, potState0)
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2)
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind2State)
                output(OneStepContract.ID, validPlayState.copy(deckRootHash = SecureHash.allOnesHash))
                command(listOf(player2.publicKey), OneStepContract.Commands.Play())
                failsWith("deckRootHash should not change")
            }
        }
    }

    @Test
    fun `Play transaction fails when the players change`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState0)
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2)
                output(TokenContract.ID, potState0)
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2)
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind2State)
                output(
                    OneStepContract.ID, validPlayState.copy(
                        players = listOf(
                            PlayedAction(player0.party, PlayerAction.Missing),
                            PlayedAction(player1.party, PlayerAction.Raise),
                            PlayedAction(notPlayer0.party, PlayerAction.Call)
                        )
                    )
                )
                command(listOf(player2.publicKey), OneStepContract.Commands.Play())
                failsWith("player parties should not change")
            }
        }
    }

    @Test
    fun `Play transaction fails when the wrong current player bets`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState0)
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2)
                output(TokenContract.ID, potState0)
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2)
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind2State)
                output(
                    OneStepContract.ID, validPlayState.copy(
                        currentPlayerIndex = 0,
                        players = withActions(PlayerAction.Call, PlayerAction.Missing, PlayerAction.Missing)
                    )
                )
                command(listOf(player0.publicKey), OneStepContract.Commands.Play())
                failsWith("new currentPlayerIndex should be the previously known as next")
            }
        }
    }

    @Test
    fun `Play transaction fails when there is no input RoundState`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState0)
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2)
                output(TokenContract.ID, potState0)
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2)
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                output(OneStepContract.ID, validPlayState)
                command(listOf(player2.publicKey), OneStepContract.Commands.Play())
                failsWith("should be one input round state")
            }
        }
    }

    @Test
    fun `Play transaction fails when there is more than one output RoundState`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState0)
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2)
                output(TokenContract.ID, potState0)
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2)
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind2State)
                output(OneStepContract.ID, validPlayState)
                output(OneStepContract.ID, validPlayState)
                command(listOf(dealer0.publicKey), OneStepContract.Commands.Play())
                failsWith("should be one output round state")
            }
        }
    }

    @Test
    fun `Play transaction fails when the BettingRound is not an isPlay`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState0)
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2)
                output(TokenContract.ID, potState0)
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2)
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind2State)
                output(OneStepContract.ID, validPlayState.copy(roundType = BettingRound.BLIND_BET_2))
                command(listOf(player2.publicKey), OneStepContract.Commands.Play())
                failsWith("ound bet status should be a play one")
            }
        }
    }

    @Test
    fun `Play transaction fails when the currentPlayerIndex does not have a Fold, Call or Raise action`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState0)
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2)
                output(TokenContract.ID, potState0)
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2)
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind2State)
                output(
                    OneStepContract.ID, validPlayState.copy(
                        players = withActions(PlayerAction.Missing, PlayerAction.Call, PlayerAction.Missing)
                    )
                )
                command(listOf(player2.publicKey), OneStepContract.Commands.Play())
                failsWith("Only the currentPlayer should have a new move, unless it is a recurring roundType")
            }
        }
    }

    @Test
    fun `Play transaction fails when the currentPlayerIndex has a Fold action but added tokens`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState0)
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2)
                output(TokenContract.ID, potState0)
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2)
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind2State)
                output(
                    OneStepContract.ID,
                    validPlayState.copy(
                        players = withActions(PlayerAction.Missing, PlayerAction.Missing, PlayerAction.Fold)
                    )
                )
                command(listOf(player2.publicKey), OneStepContract.Commands.Play())
                failsWith("A folding player should not add tokens")
            }
        }
    }

    @Test
    fun `Play transaction fails when the currentPlayerIndex has a Call action but raised tokens`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState0)
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2)
                output(TokenContract.ID, potState0)
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2)
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind2State)
                output(
                    OneStepContract.ID,
                    validPlayState.copy(
                        players = withActions(PlayerAction.Missing, PlayerAction.Missing, PlayerAction.Call)
                    )
                )
                command(listOf(player2.publicKey), OneStepContract.Commands.Play())
                failsWith("played action should reflect whether there was a raise")
            }
        }
    }

    @Test
    fun `Play transaction fails when the currentPlayerIndex has a Raise action but called tokens`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState0)
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2.copy(amount = 20))
                output(TokenContract.ID, potState0)
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2.copy(amount = 20))
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind2State)
                output(
                    OneStepContract.ID,
                    validPlayState.copy(
                        players = withActions(PlayerAction.Missing, PlayerAction.Missing, PlayerAction.Raise)
                    )
                )
                command(listOf(player2.publicKey), OneStepContract.Commands.Play())
                failsWith("played action should reflect whether there was a raise")
            }
        }
    }

    @Test
    fun `Play transaction fails when the other players do not have a Missing action`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState0)
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2)
                output(TokenContract.ID, potState0)
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2)
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind2State)
                output(
                    OneStepContract.ID, validPlayState.copy(
                        players = withActions(PlayerAction.Call, PlayerAction.Missing, PlayerAction.Raise)
                    )
                )
                command(listOf(player2.publicKey), OneStepContract.Commands.Play())
                failsWith("Only the currentPlayer should have a new move, unless it is a recurring roundType")
            }
        }
    }

    @Test
    fun `Play transaction fails when the player bets less than necessary`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState0)
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2.copy(amount = 19))
                output(TokenContract.ID, potState0)
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2.copy(amount = 19))
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind2State)
                output(OneStepContract.ID, validPlayState)
                command(listOf(player2.publicKey), OneStepContract.Commands.Play())
                failsWith("The sum should be at least the required one on Call or Raise")
            }
        }
    }

    @Test
    fun `Play transaction fails when there are no output pot tokens for the bettor`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState0)
                input(TokenContract.ID, potState1)
                output(TokenContract.ID, potState0)
                output(TokenContract.ID, potState1)
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind2State)
                output(OneStepContract.ID, validPlayState)
                command(listOf(player2.publicKey), OneStepContract.Commands.Play())
                failsWith("currentPlayer should have bet a sum")
            }
        }
    }

    @Test
    fun `Play transaction fails when there are output pot tokens for other than the bettor`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState0)
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2.copy(amount = 20))
                input(TokenContract.ID, tokenState3)
                output(TokenContract.ID, potState0)
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2.copy(amount = 20))
                output(TokenContract.ID, potState3)
                command(listOf(player2.publicKey, player3.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind2State)
                output(OneStepContract.ID, validPlayState)
                command(listOf(player2.publicKey), OneStepContract.Commands.Play())
                failsWith("Only the player should bet tokens")
            }
        }
    }

    @Test
    fun `Play transaction fails when the player did not sign off`() {
        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState0)
                input(TokenContract.ID, potState1)
                input(TokenContract.ID, tokenState2.copy(amount = 20))
                output(TokenContract.ID, potState0)
                output(TokenContract.ID, potState1)
                output(TokenContract.ID, potState2.copy(amount = 20))
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind2State)
                output(OneStepContract.ID, validPlayState)
                command(listOf(player1.publicKey), OneStepContract.Commands.Play())
                failsWith("currentPlayer should sign off the played action")
            }
        }

        ledgerServices.ledger {
            transaction {
                input(TokenContract.ID, potState0)
                input(TokenContract.ID, potState1)
                output(TokenContract.ID, potState0)
                output(TokenContract.ID, potState1)
                command(listOf(player2.publicKey), TokenContract.Commands.BetToPot())
                input(OneStepContract.ID, validBlind2State)
                output(OneStepContract.ID, validPlayState.copy(
                    players = withActions(PlayerAction.Missing, PlayerAction.Missing, PlayerAction.Fold)
                ))
                command(listOf(player1.publicKey), OneStepContract.Commands.Play())
                failsWith("currentPlayer should sign off the played action")
            }
        }
    }
}
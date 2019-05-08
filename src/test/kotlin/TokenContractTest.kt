import net.corda.core.identity.CordaX500Name
import net.corda.testing.contracts.DummyState
import net.corda.testing.core.TestIdentity
import net.corda.testing.node.MockServices
import net.corda.testing.node.ledger
import org.cordacodeclub.bluff.contract.TokenContract
import org.cordacodeclub.bluff.contract.TokenContract.Companion.ID
import org.cordacodeclub.bluff.state.PotState
import org.cordacodeclub.bluff.state.TokenState
import org.junit.Ignore
import org.junit.Test

class TokenContractTest {

    private val ledgerServices = MockServices()
    private val minter1 = TestIdentity(CordaX500Name("Minter1", "London", "GB"))
    private val minter2 = TestIdentity(CordaX500Name("Minter2", "Paris", "FR"))
    private val owner1 = TestIdentity(CordaX500Name("Owner1", "Madrid", "ES"))
    private val owner2 = TestIdentity(CordaX500Name("Owner2", "Berlin", "DE"))
    private val owner3 = TestIdentity(CordaX500Name("Owner3", "Bern", "CH"))

    private val tokenState1 = TokenState(minter = minter1.party, owner = owner1.party, amount = 10)
    private val tokenState2 = TokenState(minter = minter1.party, owner = owner2.party, amount = 20)
    private val tokenState3 = TokenState(minter = minter1.party, owner = owner3.party, amount = 30)
    private val potState1 = PotState(minter = minter1.party, amount = 10)
    private val potState2 = PotState(minter = minter1.party, amount = 20)
    private val potState3 = PotState(minter = minter1.party, amount = 30)

    /// Mint

    @Test
    fun `Mint transaction can pass with 1 or 2 token outputs`() {
        ledgerServices.ledger {
            transaction {
                output(ID, tokenState1)
                command(
                    listOf(minter1.publicKey),
                    TokenContract.Commands.Mint()
                )
                verifies()
            }

            transaction {
                output(ID, tokenState1)
                output(ID, tokenState2)
                command(
                    listOf(minter1.publicKey),
                    TokenContract.Commands.Mint()
                )
                verifies()
            }
        }
    }

    @Test
    fun `Mint transaction fails with 2 commands`() {
        ledgerServices.ledger {
            transaction {
                output(ID, tokenState1)
                command(
                    listOf(minter1.publicKey),
                    TokenContract.Commands.Mint()
                )
                command(
                    listOf(minter1.publicKey),
                    TokenContract.Commands.Transfer()
                )
                failsWith("List has more than one element")
            }
        }
    }

    @Test
    fun `Mint transaction must have no token or pot input`() {
        ledgerServices.ledger {
            transaction {
                input(ID, tokenState1)
                output(ID, tokenState2)
                command(
                    listOf(owner1.publicKey, minter1.publicKey),
                    TokenContract.Commands.Mint()
                )
                failsWith("should be no input TokenState when Minting")
            }

            transaction {
                input(ID, potState1)
                output(ID, tokenState2)
                command(
                    listOf(minter1.publicKey),
                    TokenContract.Commands.Mint()
                )
                failsWith("should be no input PotState when Minting")
            }
        }
    }

    @Test
    fun `Mint transaction must have no pot output`() {
        ledgerServices.ledger {
            transaction {
                output(ID, tokenState1)
                output(ID, potState2)
                command(
                    listOf(minter1.publicKey),
                    TokenContract.Commands.Mint()
                )
                failsWith("should be no output PotState when Minting")
            }
        }
    }

    @Test
    fun `Mint transaction must have at least one token output`() {
        ledgerServices.ledger {
            transaction {
                output(ID, DummyState())
                command(
                    listOf(minter1.publicKey),
                    TokenContract.Commands.Mint()
                )
                // The error message is not the one we would like but it is the first one that is hit
                failsWith("should be a single minter")
            }
        }
    }

    @Test
    fun `Mint transaction must have single minter`() {
        val badState2 = TokenState(minter = minter2.party, owner = owner2.party, amount = 20)
        ledgerServices.ledger {
            transaction {
                output(ID, tokenState1)
                output(ID, badState2)
                command(
                    listOf(minter1.publicKey, minter2.publicKey),
                    TokenContract.Commands.Mint()
                )
                failsWith("should be a single minter")
            }
        }
    }

    @Test
    fun `Mint transaction must have minter signature`() {
        ledgerServices.ledger {
            transaction {
                output(ID, tokenState1)
                command(
                    listOf(owner1.publicKey),
                    TokenContract.Commands.Mint()
                )
                failsWith("minter should sign when Minting")
            }
        }
    }

    /// Transfer

    @Test
    fun `Transfer transaction can pass with 2 inputs to 1 output or reverse`() {
        ledgerServices.ledger {
            transaction {
                input(ID, tokenState1)
                input(ID, tokenState2)
                output(ID, tokenState3)
                command(
                    listOf(owner1.publicKey, owner2.publicKey),
                    TokenContract.Commands.Transfer()
                )
                verifies()
            }

            transaction {
                input(ID, tokenState3)
                output(ID, tokenState1)
                output(ID, tokenState2)
                command(
                    listOf(owner3.publicKey),
                    TokenContract.Commands.Transfer()
                )
                verifies()
            }
        }
    }

    @Test
    fun `Transfer transaction fails with 2 commands`() {
        ledgerServices.ledger {
            transaction {
                input(ID, tokenState1)
                output(ID, tokenState1)
                command(
                    listOf(owner1.publicKey),
                    TokenContract.Commands.Transfer()
                )
                command(
                    listOf(minter1.publicKey),
                    TokenContract.Commands.Mint()
                )
                failsWith("List has more than one element")
            }
        }
    }

    @Test
    fun `Transfer transaction must have no pot input`() {
        ledgerServices.ledger {
            transaction {
                input(ID, tokenState1)
                input(ID, potState1)
                output(ID, tokenState2)
                command(
                    listOf(owner1.publicKey, minter1.publicKey),
                    TokenContract.Commands.Transfer()
                )
                failsWith("should be no input PotState when Transferring")
            }
        }
    }

    @Test
    fun `Transfer transaction must have no pot output`() {
        ledgerServices.ledger {
            transaction {
                input(ID, tokenState2)
                output(ID, tokenState1)
                output(ID, potState1)
                command(
                    listOf(minter1.publicKey),
                    TokenContract.Commands.Transfer()
                )
                failsWith("should be no output PotState when Transferring")
            }
        }
    }

    @Test
    fun `Transfer transaction must have at least one token input`() {
        ledgerServices.ledger {
            transaction {
                input(ID, DummyState())
                output(ID, tokenState1)
                command(
                    listOf(owner1.publicKey),
                    TokenContract.Commands.Transfer()
                )
                failsWith("should be at least one input TokenState when Transferring")
            }
        }
    }

    @Test
    fun `Transfer transaction must have at least one token output`() {
        ledgerServices.ledger {
            transaction {
                input(ID, tokenState1)
                output(ID, DummyState())
                command(
                    listOf(owner1.publicKey),
                    TokenContract.Commands.Transfer()
                )
                failsWith("should be at least one output TokenState when Transferring")
            }
        }
    }

    @Test
    fun `Transfer transaction must have same amount in and out`() {
        ledgerServices.ledger {
            transaction {
                input(ID, tokenState1)
                input(ID, tokenState2)
                output(ID, tokenState2)
                command(
                    listOf(owner1.publicKey, owner2.publicKey),
                    TokenContract.Commands.Transfer()
                )
                failsWith("should be the same amount in and out when Transferring")
            }

            transaction {
                input(ID, tokenState2)
                output(ID, tokenState1)
                output(ID, tokenState2)
                command(
                    listOf(owner2.publicKey),
                    TokenContract.Commands.Transfer()
                )
                failsWith("should be the same amount in and out when Transferring")
            }
        }
    }

    @Test
    fun `Transfer transaction must have single minter`() {
        val badState1 = TokenState(minter = minter2.party, owner = owner2.party, amount = 10)
        ledgerServices.ledger {
            transaction {
                input(ID, tokenState1)
                output(ID, badState1)
                command(
                    listOf(owner1.publicKey),
                    TokenContract.Commands.Transfer()
                )
                failsWith("should be a single minter")
            }
        }
    }

    @Test
    fun `Transfer transaction must have all input owner signatures`() {
        ledgerServices.ledger {
            transaction {
                input(ID, tokenState1)
                input(ID, tokenState2)
                output(ID, tokenState3)
                command(
                    listOf(owner1.publicKey),
                    TokenContract.Commands.Transfer()
                )
                failsWith("Input owners should sign when Transferring")
            }
        }
    }

    /// BetToPot

    @Test
    fun `BetToPot transaction can pass with 2 inputs to 1 output or reverse`() {
        ledgerServices.ledger {
            transaction {
                input(ID, tokenState1)
                input(ID, tokenState2)
                output(ID, potState3)
                command(
                    listOf(owner1.publicKey, owner2.publicKey),
                    TokenContract.Commands.BetToPot()
                )
                verifies()
            }

            transaction {
                input(ID, tokenState3)
                output(ID, potState1)
                output(ID, potState2)
                command(
                    listOf(owner3.publicKey),
                    TokenContract.Commands.BetToPot()
                )
                verifies()
            }
        }
    }

    @Test
    fun `BetToPot transaction fails with 2 commands`() {
        ledgerServices.ledger {
            transaction {
                input(ID, tokenState1)
                output(ID, potState1)
                command(
                    listOf(owner1.publicKey),
                    TokenContract.Commands.BetToPot()
                )
                command(
                    listOf(minter1.publicKey),
                    TokenContract.Commands.Mint()
                )
                failsWith("List has more than one element")
            }
        }
    }

    @Test
    fun `BetToPot transaction must have no pot input`() {
        ledgerServices.ledger {
            transaction {
                input(ID, potState1)
                input(ID, tokenState1)
                output(ID, potState2)
                command(
                    listOf(owner1.publicKey, minter1.publicKey),
                    TokenContract.Commands.BetToPot()
                )
                failsWith("should be no input PotState when Betting")
            }
        }
    }

    @Test
    fun `BetToPot transaction must have no token output`() {
        ledgerServices.ledger {
            transaction {
                input(ID, tokenState2)
                output(ID, tokenState1)
                output(ID, potState1)
                command(
                    listOf(owner2.publicKey),
                    TokenContract.Commands.BetToPot()
                )
                failsWith("should be no output TokenState when Betting")
            }
        }
    }

    @Test
    fun `BetToPot transaction must have at least one token input`() {
        ledgerServices.ledger {
            transaction {
                input(ID, DummyState())
                output(ID, potState1)
                command(
                    listOf(owner1.publicKey),
                    TokenContract.Commands.BetToPot()
                )
                failsWith("should be at least one input TokenState when Betting")
            }
        }
    }

    @Test
    fun `BetToPot transaction must have at least one pot output`() {
        ledgerServices.ledger {
            transaction {
                input(ID, tokenState1)
                output(ID, DummyState())
                command(
                    listOf(owner1.publicKey),
                    TokenContract.Commands.BetToPot()
                )
                failsWith("should be at least one output PotState when Betting")
            }
        }
    }

    @Test
    fun `BetToPot transaction must have same amount in and out`() {
        ledgerServices.ledger {
            transaction {
                input(ID, tokenState1)
                input(ID, tokenState2)
                output(ID, potState2)
                command(
                    listOf(owner1.publicKey, owner2.publicKey),
                    TokenContract.Commands.BetToPot()
                )
                failsWith("should be the same amount in and out when Betting")
            }
        }
    }

    @Test
    fun `BetToPot transaction must have single minter`() {
        val badTokenState1 = TokenState(minter = minter2.party, owner = owner1.party, amount = 10)
        val badPotState1 = PotState(minter = minter2.party, amount = 10)
        ledgerServices.ledger {
            transaction {
                input(ID, tokenState1)
                output(ID, badPotState1)
                command(
                    listOf(owner1.publicKey),
                    TokenContract.Commands.BetToPot()
                )
                failsWith("should be a single minter")
            }

            transaction {
                input(ID, badTokenState1)
                output(ID, potState1)
                command(
                    listOf(owner1.publicKey),
                    TokenContract.Commands.BetToPot()
                )
                failsWith("should be a single minter")
            }
        }
    }

    @Test
    fun `BetToPot transaction must have input owner signatures`() {
        ledgerServices.ledger {
            transaction {
                input(ID, tokenState1)
                input(ID, tokenState2)
                output(ID, potState3)
                command(
                    listOf(owner1.publicKey),
                    TokenContract.Commands.BetToPot()
                )
                failsWith("Input owners should sign when Betting")
            }
        }
    }

    /// Win

    @Test
    fun `Win transaction can pass with 2 inputs to 1 output or reverse`() {
        ledgerServices.ledger {
            transaction {
                input(ID, potState1)
                input(ID, potState2)
                output(ID, tokenState3)
                command(
                    listOf(owner1.publicKey),
                    TokenContract.Commands.Win()
                )
                verifies()
            }

            transaction {
                input(ID, potState3)
                output(ID, tokenState1)
                output(ID, tokenState2)
                command(
                    listOf(owner1.publicKey),
                    TokenContract.Commands.Win()
                )
                verifies()
            }
        }
    }

    @Test
    fun `Win transaction fails with 2 commands`() {
        ledgerServices.ledger {
            transaction {
                input(ID, potState1)
                output(ID, tokenState1)
                command(
                    listOf(owner1.publicKey),
                    TokenContract.Commands.Win()
                )
                command(
                    listOf(minter1.publicKey),
                    TokenContract.Commands.Mint()
                )
                failsWith("List has more than one element")
            }
        }
    }

    @Test
    fun `Win transaction must have no token input`() {
        ledgerServices.ledger {
            transaction {
                input(ID, tokenState1)
                output(ID, tokenState1)
                command(
                    listOf(owner1.publicKey),
                    TokenContract.Commands.Win()
                )
                failsWith("should be no input TokenState when Winning")
            }
        }
    }

    @Test
    fun `Win transaction must have no pot output`() {
        ledgerServices.ledger {
            transaction {
                input(ID, potState2)
                output(ID, tokenState1)
                output(ID, potState1)
                command(
                    listOf(owner1.publicKey),
                    TokenContract.Commands.Win()
                )
                failsWith("should be no output PotState when Winning")
            }
        }
    }

    @Test
    fun `Win transaction must have at least one pot input`() {
        ledgerServices.ledger {
            transaction {
                input(ID, DummyState())
                output(ID, tokenState1)
                command(
                    listOf(owner1.publicKey),
                    TokenContract.Commands.Win()
                )
                failsWith("should be at least one input PotState when Winning")
            }
        }
    }

    @Test
    fun `Win transaction must have at least one token output`() {
        ledgerServices.ledger {
            transaction {
                input(ID, potState1)
                output(ID, DummyState())
                command(
                    listOf(owner1.publicKey),
                    TokenContract.Commands.Win()
                )
                failsWith("should be at least one output TokenState when Winning")
            }
        }
    }

    @Test
    fun `Win transaction must have same amount in and out`() {
        ledgerServices.ledger {
            transaction {
                input(ID, potState1)
                input(ID, potState2)
                output(ID, tokenState2)
                command(
                    listOf(owner1.publicKey),
                    TokenContract.Commands.Win()
                )
                failsWith("should be the same amount in and out when Winning")
            }
        }
    }

    @Test
    fun `Win transaction must have single minter`() {
        val badTokenState1 = TokenState(minter = minter2.party, owner = owner1.party, amount = 10)
        val badPotState1 = PotState(minter = minter2.party, amount = 10)
        ledgerServices.ledger {
            transaction {
                input(ID, potState1)
                output(ID, badTokenState1)
                command(
                    listOf(owner1.publicKey),
                    TokenContract.Commands.Win()
                )
                failsWith("should be a single minter")
            }

            transaction {
                input(ID, badPotState1)
                output(ID, tokenState1)
                command(
                    listOf(owner1.publicKey),
                    TokenContract.Commands.Win()
                )
                failsWith("should be a single minter")
            }
        }
    }

    @Test
    @Ignore
    fun `Win transaction must have a proof of a win`() {
    }

    /// Burn

    @Test
    fun `Burn transaction can pass with 1 or 2 inputs`() {
        ledgerServices.ledger {
            transaction {
                input(ID, tokenState1)
                command(
                    listOf(owner1.publicKey, minter1.publicKey),
                    TokenContract.Commands.Burn()
                )
                verifies()
            }

            transaction {
                input(ID, tokenState1)
                input(ID, tokenState2)
                command(
                    listOf(owner1.publicKey, owner2.publicKey, minter1.publicKey),
                    TokenContract.Commands.Burn()
                )
                verifies()
            }
        }
    }

    @Test
    fun `Burn transaction fails with 2 commands`() {
        ledgerServices.ledger {
            transaction {
                input(ID, tokenState1)
                command(
                    listOf(owner1.publicKey),
                    TokenContract.Commands.Burn()
                )
                command(
                    listOf(minter1.publicKey),
                    TokenContract.Commands.Mint()
                )
                failsWith("List has more than one element")
            }
        }
    }

    @Test
    fun `Burn transaction must have no pot input`() {
        ledgerServices.ledger {
            transaction {
                input(ID, tokenState1)
                input(ID, potState1)
                command(
                    listOf(owner1.publicKey),
                    TokenContract.Commands.Burn()
                )
                failsWith("should be no input PotState when Burning")
            }
        }
    }

    @Test
    fun `Burn transaction must have no token or pot output`() {
        ledgerServices.ledger {
            transaction {
                input(ID, tokenState1)
                output(ID, potState1)
                command(
                    listOf(owner1.publicKey),
                    TokenContract.Commands.Burn()
                )
                failsWith("should be no output PotState when Burning")
            }

            transaction {
                input(ID, tokenState1)
                output(ID, tokenState1)
                command(
                    listOf(owner1.publicKey),
                    TokenContract.Commands.Burn()
                )
                failsWith("should be no output TokenState when Burning")
            }
        }
    }

    @Test
    fun `Burn transaction must have at least one token input`() {
        ledgerServices.ledger {
            transaction {
                input(ID, DummyState())
                output(ID, tokenState1)
                command(
                    listOf(owner1.publicKey),
                    TokenContract.Commands.Burn()
                )
                failsWith("should be at least one input TokenState when Burning")
            }
        }
    }

    @Test
    fun `Burn transaction must have single minter`() {
        val badTokenState1 = TokenState(minter = minter2.party, owner = owner1.party, amount = 10)
        ledgerServices.ledger {
            transaction {
                input(ID, potState1)
                input(ID, badTokenState1)
                command(
                    listOf(owner1.publicKey),
                    TokenContract.Commands.Burn()
                )
                failsWith("should be a single minter")
            }
        }
    }

    @Test
    fun `Burn transaction must input owner and minter signatures`() {
        ledgerServices.ledger {
            transaction {
                input(ID, tokenState1)
                input(ID, tokenState2)
                command(
                    listOf(owner1.publicKey, minter1.publicKey),
                    TokenContract.Commands.Burn()
                )
                failsWith("Input owners should sign when Burning")
            }

            transaction {
                input(ID, tokenState1)
                input(ID, tokenState2)
                command(
                    listOf(owner1.publicKey, owner2.publicKey),
                    TokenContract.Commands.Burn()
                )
                failsWith("minter should sign when Burning")
            }
        }
    }
}
import net.corda.core.identity.CordaX500Name
import net.corda.testing.core.TestIdentity
import net.corda.testing.node.MockServices
import net.corda.testing.node.ledger
import org.cordacodeclub.bluff.contract.TokenContract
import org.cordacodeclub.bluff.contract.TokenContract.Companion.ID
import org.cordacodeclub.bluff.state.PotState
import org.cordacodeclub.bluff.state.TokenState
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

    @Test
    fun `Mint transaction can pass with 1 or 2 outputs`() {
        ledgerServices.ledger {
            transaction {
                output(ID, tokenState1)
                command(
                    listOf(minter1.publicKey),
                    TokenContract.Commands.Mint())
                verifies()
            }

            transaction {
                output(ID, tokenState1)
                output(ID, tokenState2)
                command(
                    listOf(minter1.publicKey),
                    TokenContract.Commands.Mint())
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
                    TokenContract.Commands.Mint())
                command(
                    listOf(minter1.publicKey),
                    TokenContract.Commands.Transfer())
                failsWith("List has more than one element")
            }
        }
    }

    @Test
    fun `Mint transaction must have no poker input`() {
        ledgerServices.ledger {
            transaction {
                input(ID, tokenState1)
                output(ID, tokenState2)
                command(
                    listOf(owner1.publicKey, minter1.publicKey),
                    TokenContract.Commands.Mint())
                failsWith("should be no input TokenState when Minting")
            }

            transaction {
                input(ID, potState1)
                output(ID, tokenState2)
                command(
                    listOf(minter1.publicKey),
                    TokenContract.Commands.Mint())
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
                    TokenContract.Commands.Mint())
                failsWith("should be no output PotState when Minting")
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
                    TokenContract.Commands.Mint())
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
                    TokenContract.Commands.Mint())
                failsWith("minter should sign when Minting")
            }
        }
    }

    @Test
    fun `Transfer transaction can pass with 2 inputs to 1 output or reverse`() {
        ledgerServices.ledger {
            transaction {
                input(ID, tokenState1)
                input(ID, tokenState2)
                output(ID, tokenState3)
                command(
                    listOf(owner1.publicKey, owner2.publicKey),
                    TokenContract.Commands.Transfer())
                verifies()
            }

            transaction {
                input(ID, tokenState3)
                output(ID, tokenState1)
                output(ID, tokenState2)
                command(
                    listOf(owner3.publicKey),
                    TokenContract.Commands.Transfer())
                verifies()
            }
        }

    }

}
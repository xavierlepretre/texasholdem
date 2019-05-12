package org.cordacodeclub.bluff.contract

import net.corda.core.contracts.Contract
import net.corda.core.transactions.LedgerTransaction

class CardStateContract : Contract {
    companion object {
        val ID = "org.cordacodeclub.bluff.contract.CardStateContract"
    }

    override fun verify(tx: LedgerTransaction) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}
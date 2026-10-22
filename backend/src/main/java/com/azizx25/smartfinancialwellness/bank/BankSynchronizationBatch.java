package com.azizx25.smartfinancialwellness.bank;

import java.util.List;

import com.azizx25.smartfinancialwellness.bank.model.ExternalBankAccount;
import com.azizx25.smartfinancialwellness.bank.model.ExternalBankBalance;
import com.azizx25.smartfinancialwellness.bank.model.ExternalBankTransaction;

record BankSynchronizationBatch(List<AccountSnapshot> accounts) {

    BankSynchronizationBatch {
        accounts = List.copyOf(accounts);
    }

    int transactionCount() {
        return accounts.stream().mapToInt(account -> account.transactions().size()).sum();
    }

    record AccountSnapshot(
            ExternalBankAccount account,
            ExternalBankBalance balance,
            List<ExternalBankTransaction> transactions) {

        AccountSnapshot {
            transactions = List.copyOf(transactions);
        }
    }
}

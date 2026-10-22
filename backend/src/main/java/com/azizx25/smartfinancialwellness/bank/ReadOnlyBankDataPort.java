package com.azizx25.smartfinancialwellness.bank;

import java.time.LocalDate;
import java.util.List;

import com.azizx25.smartfinancialwellness.bank.model.ExternalBankAccount;
import com.azizx25.smartfinancialwellness.bank.model.ExternalBankBalance;
import com.azizx25.smartfinancialwellness.bank.model.ExternalBankTransactionPage;

public interface ReadOnlyBankDataPort {

    List<ExternalBankAccount> listAccounts(BankAccessCredentials credentials);

    ExternalBankAccount getAccount(BankAccessCredentials credentials, String accountId);

    ExternalBankBalance getBalance(BankAccessCredentials credentials, String accountId);

    ExternalBankTransactionPage listTransactions(
            BankAccessCredentials credentials,
            String accountId,
            LocalDate from,
            LocalDate to,
            int page,
            int size);
}

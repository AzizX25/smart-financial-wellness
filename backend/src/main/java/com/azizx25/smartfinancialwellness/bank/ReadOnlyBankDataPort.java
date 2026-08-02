package com.azizx25.smartfinancialwellness.bank;

import java.time.LocalDate;
import java.util.List;

import com.azizx25.smartfinancialwellness.bank.model.ExternalBankAccount;
import com.azizx25.smartfinancialwellness.bank.model.ExternalBankBalance;
import com.azizx25.smartfinancialwellness.bank.model.ExternalBankTransactionPage;

public interface ReadOnlyBankDataPort {

    List<ExternalBankAccount> listAccounts(String customerId);

    ExternalBankAccount getAccount(String customerId, String accountId);

    ExternalBankBalance getBalance(String customerId, String accountId);

    ExternalBankTransactionPage listTransactions(
            String customerId,
            String accountId,
            LocalDate from,
            LocalDate to,
            int page,
            int size);
}

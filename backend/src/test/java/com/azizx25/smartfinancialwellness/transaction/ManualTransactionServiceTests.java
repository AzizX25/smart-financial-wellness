package com.azizx25.smartfinancialwellness.transaction;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import com.azizx25.smartfinancialwellness.audit.AuditService;
import com.azizx25.smartfinancialwellness.bank.persistence.*;
import com.azizx25.smartfinancialwellness.category.CategoryService;
import com.azizx25.smartfinancialwellness.category.persistence.CategoryRepository;
import com.azizx25.smartfinancialwellness.transaction.api.ManualTransactionDtos.UpdateRequest;
import com.azizx25.smartfinancialwellness.user.persistence.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManualTransactionServiceTests {
    @Test
    void bankRowsRetainOwnershipAndProtectedLinkChecks() {
        UUID user = UUID.randomUUID(), id = UUID.randomUUID();
        var transactions = mock(TransactionRecordRepository.class);
        var users = mock(AppUserRepository.class);
        var accounts = mock(FinancialAccountRepository.class);
        var categories = mock(CategoryService.class);
        var preferences = mock(InterfacePreferenceRepository.class);
        var bank = mock(TransactionRecord.class);
        when(users.lockById(user)).thenReturn(Optional.of(mock(AppUser.class)));
        when(transactions.findOwnedByIdForUpdate(id, user)).thenReturn(Optional.of(bank));
        when(bank.getSourceType()).thenReturn(TransactionRecord.SourceType.BANK_SYNC);
        UUID accountId = UUID.randomUUID();
        when(bank.getAccountId()).thenReturn(accountId);
        when(accounts.findByIdAndUser_Id(accountId, user)).thenReturn(Optional.of(mock(FinancialAccount.class)));
        when(transactions.hasProtectedLinks(id)).thenReturn(true);
        when(preferences.findById(user)).thenReturn(Optional.empty());
        var service = new ManualTransactionService(transactions, accounts, mock(CategoryRepository.class), categories,
                users, preferences, mock(AuditService.class), Clock.systemUTC());
        var request = new UpdateRequest(0, LocalDate.now().minusDays(1), "Synthetic", new BigDecimal("1.0000"), TransactionRecord.Direction.DEBIT, null);
        assertThatThrownBy(() -> service.update(user, id, request)).isInstanceOf(ManualTransactionException.class)
                .hasMessageContaining("doit être conservée");
        verify(accounts).findByIdAndUser_Id(accountId, user);
        verifyNoInteractions(categories);
        verify(bank, never()).updateManual(any(), any(), any(), any(), any(), any());
        verify(bank, never()).updateLocally(any(), any(), any(), any(), any(), any());
    }
}

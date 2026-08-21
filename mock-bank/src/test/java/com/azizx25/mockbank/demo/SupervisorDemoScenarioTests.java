package com.azizx25.mockbank.demo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import com.azizx25.mockbank.demo.SupervisorDemoScenario.Account;
import com.azizx25.mockbank.demo.SupervisorDemoScenario.Scenario;
import com.azizx25.mockbank.demo.SupervisorDemoScenario.TransactionSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SupervisorDemoScenarioTests {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 21);

    @Test
    void generationIsDeterministicRelativeAndFinanciallyValid() {
        Scenario first = SupervisorDemoScenario.generate(SupervisorDemoScenario.VERSION, AS_OF);
        Scenario second = SupervisorDemoScenario.generate(SupervisorDemoScenario.VERSION, AS_OF);

        assertThat(first).isEqualTo(second);
        assertThat(first.firstCompletedMonth()).isEqualTo(YearMonth.of(2025, 12));
        assertThat(first.currentMonth()).isEqualTo(YearMonth.of(2026, 8));
        assertThat(first.transactions()).hasSize(165)
                .allSatisfy(transaction -> {
                    assertThat(transaction.externalReference())
                            .startsWith(SupervisorDemoScenario.REFERENCE_PREFIX);
                    assertThat(transaction.amount().scale()).isEqualTo(4);
                    assertThat(transaction.amount()).isPositive();
                    if ("POSTED".equals(transaction.status())) {
                        assertThat(transaction.bookingDate()).isBeforeOrEqualTo(AS_OF);
                        assertThat(transaction.valueDate()).isEqualTo(transaction.bookingDate());
                    }
                });
        assertThat(first.transactions().stream().map(TransactionSpec::externalReference))
                .doesNotHaveDuplicates();

        long completedMonths = first.transactions().stream()
                .filter(transaction -> "POSTED".equals(transaction.status()))
                .map(transaction -> YearMonth.from(transaction.bookingDate()))
                .filter(month -> month.isBefore(first.currentMonth()))
                .distinct()
                .count();
        assertThat(completedMonths).isEqualTo(8);
        assertThat(first.transactions()).anySatisfy(transaction -> {
            assertThat(transaction.status()).isEqualTo("PENDING");
            assertThat(YearMonth.from(transaction.bookingDate())).isEqualTo(first.currentMonth());
        });
        assertThat(first.transactions()).anySatisfy(transaction -> {
            assertThat(transaction.description()).contains("MYTEK");
            assertThat(transaction.amount()).isGreaterThanOrEqualTo(new BigDecimal("1000.0000"));
            assertThat(YearMonth.from(transaction.bookingDate())).isBefore(first.currentMonth());
        });
    }

    @Test
    void everyMonthlySavingsTransferHasAnEqualOppositeAccountLeg() {
        Scenario scenario = SupervisorDemoScenario.generate(SupervisorDemoScenario.VERSION, AS_OF);
        List<TransactionSpec> outgoing = scenario.transactions().stream()
                .filter(transaction -> transaction.externalReference().endsWith("SAVINGS-OUT"))
                .toList();
        List<TransactionSpec> incoming = scenario.transactions().stream()
                .filter(transaction -> transaction.externalReference().endsWith("SAVINGS-IN"))
                .toList();

        assertThat(outgoing).hasSameSizeAs(incoming).hasSize(9);
        for (TransactionSpec debit : outgoing) {
            TransactionSpec credit = incoming.stream()
                    .filter(item -> item.bookingDate().equals(debit.bookingDate()))
                    .findFirst()
                    .orElseThrow();
            assertThat(debit.account()).isEqualTo(Account.CURRENT);
            assertThat(debit.direction()).isEqualTo("DEBIT");
            assertThat(credit.account()).isEqualTo(Account.SAVINGS);
            assertThat(credit.direction()).isEqualTo("CREDIT");
            assertThat(credit.amount()).isEqualByComparingTo(debit.amount());
        }
    }

    @Test
    void reRunningLaterInTheSameMonthNeverChangesAnExistingExternalReference() {
        Scenario earlier = SupervisorDemoScenario.generate(
                SupervisorDemoScenario.VERSION, LocalDate.of(2026, 8, 5));
        Scenario later = SupervisorDemoScenario.generate(
                SupervisorDemoScenario.VERSION, LocalDate.of(2026, 8, 21));

        for (TransactionSpec existing : earlier.transactions()) {
            TransactionSpec repeated = later.transactions().stream()
                    .filter(candidate -> candidate.account() == existing.account())
                    .filter(candidate -> candidate.externalReference().equals(existing.externalReference()))
                    .findFirst()
                    .orElseThrow();
            assertThat(repeated).isEqualTo(existing);
        }
    }
}

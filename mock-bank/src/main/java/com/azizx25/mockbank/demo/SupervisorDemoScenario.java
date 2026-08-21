package com.azizx25.mockbank.demo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.azizx25.mockbank.exception.InvalidAdminRequestException;

public final class SupervisorDemoScenario {

    public static final String VERSION = "supervisor-demo-v1";
    public static final String CUSTOMER_ID = "DEMO-CUST-AMINE-BEN-SALEM-V1";
    public static final String USERNAME = "amine.bensalem.demo";
    public static final String DISPLAY_NAME = "Amine Ben Salem";
    public static final String COUNTRY_CODE = "TN";
    public static final String CURRENT_ACCOUNT_ID = "DEMO-TND-CURRENT-V1";
    public static final String SAVINGS_ACCOUNT_ID = "DEMO-TND-SAVINGS-V1";
    public static final String REFERENCE_PREFIX = "SFWA-DEMO-V1-";
    public static final int COMPLETED_MONTH_COUNT = 8;

    private SupervisorDemoScenario() {
    }

    public static Scenario generate(String version, LocalDate asOfDate) {
        if (!VERSION.equals(version)) {
            throw new InvalidAdminRequestException("Unsupported supervisor demo scenario version");
        }
        if (asOfDate == null) {
            throw new InvalidAdminRequestException("An as-of date is required");
        }

        YearMonth asOfMonth = YearMonth.from(asOfDate);
        YearMonth firstMonth = asOfMonth.minusMonths(COMPLETED_MONTH_COUNT);
        List<TransactionSpec> transactions = new ArrayList<>();
        transactions.add(posted(
                Account.CURRENT,
                firstMonth,
                1,
                "OPENING-CURRENT",
                "4900.0000",
                "CREDIT",
                "SOLDE INITIAL COMPTE COURANT DEMO",
                "Fixture synthetique"));
        transactions.add(posted(
                Account.SAVINGS,
                firstMonth,
                1,
                "OPENING-SAVINGS",
                "3900.0000",
                "CREDIT",
                "SOLDE INITIAL COMPTE EPARGNE DEMO",
                "Fixture synthetique"));

        for (int offset = 0; offset <= COMPLETED_MONTH_COUNT; offset++) {
            YearMonth month = firstMonth.plusMonths(offset);
            addMonthlyTransactions(transactions, month, offset);
        }

        YearMonth memoryMonth = asOfMonth.minusMonths(2);
        transactions.add(posted(
                Account.CURRENT,
                memoryMonth,
                17,
                "MARCHE-EL-WARD-EARLIER",
                "96.4000",
                "DEBIT",
                "PAIEMENT CB MARCHE EL WARD MENZAH",
                "Marche El Ward"));
        transactions.add(posted(
                Account.CURRENT,
                asOfMonth,
                1,
                "MARCHE-EL-WARD-CURRENT",
                "102.8000",
                "DEBIT",
                "PAIEMENT CB MARCHE EL WARD MENZAH",
                "Marche El Ward"));
        transactions.add(posted(
                Account.CURRENT,
                asOfMonth,
                1,
                "ATELIER-MEDINA-CURRENT",
                "84.6000",
                "DEBIT",
                "PAIEMENT CB ATELIER MEDINA MENZAH",
                "Atelier Medina"));
        transactions.add(new TransactionSpec(
                Account.CURRENT,
                reference(asOfMonth, "PENDING-BOOKSTORE"),
                asOfMonth.atDay(1),
                null,
                money("64.9000"),
                "DEBIT",
                "PAIEMENT CB LIBRAIRIE EL KITAB EN ATTENTE",
                "Librairie El Kitab",
                "PENDING"));

        List<TransactionSpec> visible = transactions.stream()
                .filter(item -> !item.bookingDate().isAfter(asOfDate))
                .sorted(Comparator.comparing(TransactionSpec::bookingDate)
                        .thenComparing(TransactionSpec::externalReference))
                .toList();
        return new Scenario(VERSION, asOfDate, firstMonth, asOfMonth, visible);
    }

    private static void addMonthlyTransactions(
            List<TransactionSpec> transactions,
            YearMonth month,
            int offset) {
        transactions.add(posted(Account.CURRENT, month, 2, "SALARY",
                varied("3650.0000", offset, "25.0000"), "CREDIT",
                "VIREMENT SALAIRE CARTHAGE DIGITAL SERVICES", "Carthage Digital Services"));
        transactions.add(posted(Account.CURRENT, month, 3, "RENT",
                varied("1240.0000", offset, "10.0000"), "DEBIT",
                "VIREMENT LOYER RESIDENCE ENNASR", "Residence Ennasr"));
        transactions.add(posted(Account.CURRENT, month, 5, "CARREFOUR",
                varied("315.0000", offset, "8.0000"), "DEBIT",
                "PAIEMENT CB CARREFOUR LA MARSA", "Carrefour"));
        transactions.add(posted(Account.CURRENT, month, 6, "SAVINGS-OUT",
                "475.0000", "DEBIT", "VIREMENT VERS EPARGNE", "Compte epargne personnel"));
        transactions.add(posted(Account.SAVINGS, month, 6, "SAVINGS-IN",
                "475.0000", "CREDIT", "VIREMENT DEPUIS COURANT", "Compte courant personnel"));
        transactions.add(posted(Account.CURRENT, month, 8, "TELECOM",
                varied("72.0000", offset, "2.0000"), "DEBIT",
                "PRELEVEMENT TUNISIE TELECOM", "Tunisie Telecom"));
        transactions.add(posted(Account.CURRENT, month, 9, "STREAMING",
                "32.9000", "DEBIT", "PRELEVEMENT ABONNEMENT STREAMING", "Streaming Demo"));
        transactions.add(posted(Account.CURRENT, month, 10, "STEG",
                varied("112.0000", offset, "9.0000"), "DEBIT",
                "PRELEVEMENT STEG TUNIS", "STEG"));
        transactions.add(posted(Account.CURRENT, month, 11, "SHELL",
                varied("205.0000", offset, "7.0000"), "DEBIT",
                "PAIEMENT CB SHELL TUNIS", "Shell"));
        transactions.add(posted(Account.CURRENT, month, 12, "SONEDE",
                varied("38.0000", offset, "3.0000"), "DEBIT",
                "PRELEVEMENT SONEDE", "SONEDE"));
        transactions.add(posted(Account.CURRENT, month, 14, "MONOPRIX",
                varied("218.0000", offset, "6.0000"), "DEBIT",
                "PAIEMENT CB MONOPRIX MENZAH", "Monoprix"));
        transactions.add(posted(Account.CURRENT, month, 16, "BOLT",
                varied("58.0000", offset, "4.0000"), "DEBIT",
                "PAIEMENT CB BOLT TUNIS", "Bolt"));
        transactions.add(posted(Account.CURRENT, month, 18, "PHARMACY",
                varied("68.0000", offset, "5.0000"), "DEBIT",
                "PAIEMENT CB PHARMACIE EL AMEN", "Pharmacie El Amen"));
        transactions.add(posted(Account.CURRENT, month, 20, "GOURMANDISE",
                varied("43.0000", offset, "3.0000"), "DEBIT",
                "PAIEMENT CB GOURMANDISE LAC", "Gourmandise"));
        transactions.add(posted(Account.CURRENT, month, 21, "AZIZA",
                varied("158.0000", offset, "5.0000"), "DEBIT",
                "PAIEMENT CB AZIZA ARIANA", "Aziza"));
        transactions.add(posted(Account.CURRENT, month, 22, "RESTAURANT",
                varied("142.0000", offset, "8.0000"), "DEBIT",
                "PAIEMENT CB RESTAURANT DAR ZARROUK", "Restaurant Dar Zarrouk"));
        transactions.add(posted(Account.CURRENT, month, 24, "HAMADI-ABID",
                varied("215.0000", offset, "10.0000"), "DEBIT",
                "PAIEMENT CB HAMADI ABID ARIANA", "Hamadi Abid"));
        String mytekAmount = offset == 4 ? "1450.0000" : varied("235.0000", offset, "12.0000");
        transactions.add(posted(Account.CURRENT, month, 27, "MYTEK",
                mytekAmount, "DEBIT", "PAIEMENT CB MYTEK CHARGUIA", "Mytek"));
    }

    private static TransactionSpec posted(
            Account account,
            YearMonth month,
            int day,
            String type,
            String amount,
            String direction,
            String description,
            String counterparty) {
        LocalDate bookingDate = month.atDay(Math.min(day, month.lengthOfMonth()));
        return new TransactionSpec(
                account,
                reference(month, type),
                bookingDate,
                bookingDate,
                money(amount),
                direction,
                description,
                counterparty,
                "POSTED");
    }

    private static String reference(YearMonth month, String type) {
        return REFERENCE_PREFIX + month + '-' + type;
    }

    private static String varied(String base, int offset, String step) {
        int factor = Math.floorMod(offset * 7, 5) - 2;
        return money(base).add(money(step).multiply(BigDecimal.valueOf(factor))).setScale(4).toPlainString();
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(4);
    }

    public enum Account {
        CURRENT,
        SAVINGS
    }

    public record Scenario(
            String version,
            LocalDate asOfDate,
            YearMonth firstCompletedMonth,
            YearMonth currentMonth,
            List<TransactionSpec> transactions) {

        public Scenario {
            transactions = List.copyOf(transactions);
        }
    }

    public record TransactionSpec(
            Account account,
            String externalReference,
            LocalDate bookingDate,
            LocalDate valueDate,
            BigDecimal amount,
            String direction,
            String description,
            String counterparty,
            String status) {
    }
}

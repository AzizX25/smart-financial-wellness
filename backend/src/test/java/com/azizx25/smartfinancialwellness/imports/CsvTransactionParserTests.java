package com.azizx25.smartfinancialwellness.imports;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.imports.api.CsvImportDtos.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvTransactionParserTests {
    private final CsvTransactionParser parser = new CsvTransactionParser();
    private final LocalDate today = LocalDate.of(2026, 10, 10);
    private final ImportRequest defaults = new ImportRequest(UUID.randomUUID(), null, null, null, null);

    @Test
    void parsesBomQuotesAndRepeatedContentAsDistinctLogicalRowsWithPositiveScaleFourAmounts() {
        var csv = parse("\uFEFFdate;libellé;montant;devise\n2026-10-01;\"École; livres\";-15.1250;TND\n"
                + "2026-10-01;\"École; livres\";-15.1250;TND\n", defaults);
        assertThat(csv.invalidCount()).isZero();
        assertThat(csv.delimiter()).isEqualTo(Delimiter.SEMICOLON);
        assertThat(csv.validRows()).hasSize(2);
        assertThat(csv.validRows().getFirst().amount()).hasToString("15.1250");
        assertThat(csv.validRows().getFirst().direction()).isEqualTo("DEBIT");
        assertThat(csv.validRows().getFirst().description()).isEqualTo("École; livres");
        assertThat(csv.validRows().getLast().rowNumber()).isEqualTo(3);
        assertThat(csv.hash()).matches("[0-9a-f]{64}");
    }

    @Test
    void supportsExplicitMappingFrenchDatesDecimalCommaAndDirectionColumn() {
        var request = new ImportRequest(UUID.randomUUID(), new Mapping("jour", "opération", "valeur", "sens", null),
                Delimiter.SEMICOLON, DateFormat.DAY_MONTH_YEAR, DecimalSeparator.COMMA);
        var csv = parse("jour;opération;valeur;sens\n02/10/2026;Achat;15,5000;DEBIT\n03/10/2026;Revenu;3500,00;CREDIT", request);
        assertThat(csv.invalidCount()).isZero();
        assertThat(csv.validRows().getFirst().bookingDate()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(csv.validRows().getLast().amount()).hasToString("3500.0000");
    }

    @Test
    void reportsScaleCurrencyZeroNegativeCreditAndFutureErrorsWithoutEchoingInput() {
        var csv = parse("date,description,amount,direction,currency\n"
                + "2026-10-01,private-text,1.00001,DEBIT,TND\n"
                + "2026-10-01,private-text,1,CREDIT,EUR\n"
                + "2026-10-01,private-text,0,DEBIT,TND\n"
                + "2026-10-01,private-text,-1,CREDIT,TND\n"
                + "2026-10-11,private-text,1,DEBIT,TND\n", defaults);
        assertThat(csv.invalidCount()).isEqualTo(5);
        assertThat(csv.validRows()).isEmpty();
        assertThat(csv.errors().toString()).doesNotContain("private-text", "1.00001");
    }

    @Test
    void requiresUserMappingWhenHeadersAreUnknownOrColumnsAreReused() {
        var csv = parse("a;b;c\n2026-10-01;achat;-2", defaults);
        assertThat(csv.invalidCount()).isEqualTo(1);
        assertThat(csv.errors().getFirst().code()).isEqualTo("MAPPING_REQUIRED");
        var invalid = new ImportRequest(UUID.randomUUID(), new Mapping("a", "a", "c", null, null), null, null, null);
        assertThat(parse("a;b;c\n2026-10-01;achat;-2", invalid).invalidCount()).isEqualTo(1);
    }

    @Test
    void supportsTabAndQuotedMultilineRecordsWithoutChangingRecordIdentity() {
        var csv = parse("date\tdescription\tamount\n2026-10-01\t\"Achat\nLivres\"\t-2\n", defaults);
        assertThat(csv.invalidCount()).isZero();
        assertThat(csv.rowCount()).isEqualTo(1);
        assertThat(csv.validRows().getFirst().rowNumber()).isEqualTo(2);
    }

    @Test
    void rejectsMalformedUtf8BinaryDuplicateHeadersWrongFileTypesAndOversizeFiles() {
        assertThatThrownBy(() -> parser.parse(new byte[] {(byte) 0xc3, 0x28}, "file.csv", defaults, "TND", today))
                .isInstanceOf(CsvImportException.class).hasMessageContaining("UTF-8");
        assertThatThrownBy(() -> parse("date;date;amount\n2026-10-01;achat;1", defaults)).isInstanceOf(CsvImportException.class);
        assertThatThrownBy(() -> parse("date;description;amount\n2026-10-01;\"unfinished;1", defaults)).isInstanceOf(CsvImportException.class);
        assertThatThrownBy(() -> parse("date;description;amount\n2026-10-01;\u0000;1", defaults)).isInstanceOf(CsvImportException.class);
        assertThatThrownBy(() -> parser.parse("date,amount".getBytes(StandardCharsets.UTF_8), "file.xlsx", defaults, "TND", today))
                .isInstanceOf(CsvImportException.class);
        assertThatThrownBy(() -> parser.parse(new byte[CsvTransactionParser.MAX_BYTES + 1], "file.csv", defaults, "TND", today))
                .isInstanceOf(CsvImportException.class);
    }

    @Test
    void rejectsExcessRowsAndStrictlyValidatesFrenchCalendarDates() {
        assertThatThrownBy(() -> parse("date;description;amount\n" + "2026-10-01;Achat;-1\n".repeat(2001), defaults))
                .isInstanceOf(CsvImportException.class).hasMessageContaining("2 000");
        var request = new ImportRequest(UUID.randomUUID(), null, null, DateFormat.DAY_MONTH_YEAR, null);
        assertThat(parse("date;description;amount\n31/02/2026;Achat;-1", request).invalidCount()).isEqualTo(1);
    }

    private CsvTransactionParser.ParsedCsv parse(String csv, ImportRequest request) {
        return parser.parse(csv.getBytes(StandardCharsets.UTF_8), "file.csv", request, "TND", today);
    }
}

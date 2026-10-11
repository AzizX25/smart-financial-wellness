package com.azizx25.smartfinancialwellness.imports.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class CsvImportDtos {
    private CsvImportDtos() { }

    public enum Delimiter { AUTO, COMMA, SEMICOLON, TAB }
    public enum DateFormat { ISO, DAY_MONTH_YEAR }
    public enum DecimalSeparator { DOT, COMMA }

    public record Mapping(
            @Size(max = 255) String dateColumn,
            @Size(max = 255) String descriptionColumn,
            @Size(max = 255) String amountColumn,
            @Size(max = 255) String directionColumn,
            @Size(max = 255) String currencyColumn) { }

    public record ImportRequest(
            @NotNull UUID accountId,
            @Valid Mapping mapping,
            Delimiter delimiter,
            DateFormat dateFormat,
            DecimalSeparator decimalSeparator) { }

    public record AccountRequest(
            @NotBlank @Size(max = 160) String name,
            @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotNull @Digits(integer = 15, fraction = 4) BigDecimal currentBalance) { }

    public record ImportAccountResponse(
            UUID id, String name, String currency, BigDecimal currentBalance, boolean reused) { }

    public record PreviewRow(
            int rowNumber, LocalDate bookingDate, String description,
            BigDecimal amount, String direction, String currency) { }

    public record RowError(int rowNumber, String code, String message) { }

    public record PreviewResponse(
            UUID jobId, UUID accountId, String currency, List<String> columns,
            Mapping mapping, Delimiter delimiter, DateFormat dateFormat,
            DecimalSeparator decimalSeparator, int rowCount, int validCount,
            int invalidCount, int duplicateCount, boolean canImport,
            boolean alreadyImported, List<PreviewRow> rows, List<RowError> errors) { }

    public record ImportResponse(
            UUID jobId, UUID accountId, String status, int rowCount,
            int importedCount, int duplicateCount, boolean alreadyImported) { }
}

package com.azizx25.smartfinancialwellness.imports;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.azizx25.smartfinancialwellness.imports.api.CsvImportDtos.*;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

@Component
public class CsvTransactionParser {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_ROWS = 2000;
    private static final DateTimeFormatter FRENCH_DATE = DateTimeFormatter
            .ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);

    public ParsedCsv parse(byte[] bytes, String filename, ImportRequest request,
            String currency, LocalDate today) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw CsvImportException.invalid("Le fichier doit contenir entre 1 octet et 2 Mo.");
        }
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw CsvImportException.invalid("Sélectionnez un fichier CSV.");
        }
        String text = decode(bytes);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        if (text.chars().anyMatch(c -> c < 32 && c != '\r' && c != '\n' && c != '\t')) {
            throw CsvImportException.invalid("Le fichier contient des caractères non autorisés.");
        }
        Delimiter delimiter = request.delimiter() == null ? Delimiter.AUTO : request.delimiter();
        if (delimiter == Delimiter.AUTO) delimiter = detectDelimiter(text);
        DateFormat dateFormat = request.dateFormat() == null ? DateFormat.ISO : request.dateFormat();
        DecimalSeparator decimal = request.decimalSeparator() == null ? DecimalSeparator.DOT : request.decimalSeparator();
        List<CSVRecord> records = records(text, delimiter);
        List<String> columns = new ArrayList<>();
        records.getFirst().forEach(columns::add);
        if (columns.size() < 2 || columns.size() > 50
                || columns.stream().anyMatch(c -> c.isBlank() || c.length() > 255)
                || new HashSet<>(columns).size() != columns.size()) {
            throw CsvImportException.invalid("Les en-têtes doivent être uniques et non vides (2 à 50 colonnes).");
        }
        if (records.size() < 2) throw CsvImportException.invalid("Le fichier ne contient aucune opération.");
        Mapping mapping = request.mapping() == null ? proposeMapping(columns) : request.mapping();
        boolean mapped = validMapping(mapping, columns);
        List<PreviewRow> valid = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        int invalid = 0;
        for (int index = 1; index < records.size(); index++) {
            CSVRecord row = records.get(index);
            int number = index + 1; // Logical CSV record number, header included.
            try {
                if (!mapped) throw CsvImportException.invalid("Choisissez des colonnes distinctes pour la date, le libellé et le montant.");
                if (row.size() != columns.size()) throw CsvImportException.invalid("Le nombre de colonnes est incorrect.");
                String description = cell(row, columns, mapping.descriptionColumn());
                if (description.isBlank() || description.length() > 1000 || normalize(description).isBlank()) {
                    throw CsvImportException.invalid("Le libellé doit contenir entre 1 et 1 000 caractères.");
                }
                LocalDate date = LocalDate.parse(cell(row, columns, mapping.dateColumn()),
                        dateFormat == DateFormat.ISO ? DateTimeFormatter.ISO_LOCAL_DATE : FRENCH_DATE);
                if (date.isAfter(today) || date.getYear() < 1900) throw CsvImportException.invalid("La date doit être comprise entre 1900 et aujourd'hui.");
                String rawAmount = cell(row, columns, mapping.amountColumn());
                String separator = decimal == DecimalSeparator.COMMA ? "," : "\\.";
                if (!rawAmount.matches("[+-]?\\d{1,15}(?:" + separator + "\\d{1,4})?")) {
                    throw CsvImportException.invalid("Le montant doit avoir au plus quatre décimales, sans séparateur de milliers.");
                }
                BigDecimal signed = new BigDecimal(rawAmount.replace(',', '.'));
                if (signed.signum() == 0 || signed.abs().compareTo(new BigDecimal("999999999999999.9999")) > 0) {
                    throw CsvImportException.invalid("Le montant doit être non nul et respecter la précision autorisée.");
                }
                String direction = mapping.directionColumn() == null ? (signed.signum() < 0 ? "DEBIT" : "CREDIT")
                        : cell(row, columns, mapping.directionColumn()).toUpperCase(Locale.ROOT);
                if (!Set.of("DEBIT", "CREDIT").contains(direction) || signed.signum() < 0 && !"DEBIT".equals(direction)) {
                    throw CsvImportException.invalid("Le sens doit être DEBIT ou CREDIT et cohérent avec le montant.");
                }
                String rowCurrency = mapping.currencyColumn() == null ? currency : cell(row, columns, mapping.currencyColumn());
                if (!currency.equals(rowCurrency)) throw CsvImportException.invalid("La devise doit correspondre exactement au compte.");
                valid.add(new PreviewRow(number, date, description,
                        signed.abs().setScale(4, RoundingMode.UNNECESSARY), direction, currency));
            } catch (RuntimeException exception) {
                invalid++;
                if (errors.size() < 20) errors.add(new RowError(number, mapped ? "INVALID_ROW" : "MAPPING_REQUIRED",
                        exception instanceof CsvImportException ? exception.getMessage() : "La date ou une valeur est invalide."));
            }
        }
        return new ParsedCsv(sha256(bytes), columns, mapping, delimiter, dateFormat, decimal,
                records.size() - 1, valid, invalid, errors);
    }

    private String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw CsvImportException.invalid("Le fichier doit être encodé en UTF-8.");
        }
    }

    private List<CSVRecord> records(String text, Delimiter delimiter) {
        try (CSVParser parser = CSVParser.builder().setReader(new StringReader(text))
                .setFormat(CSVFormat.RFC4180.builder().setDelimiter(character(delimiter))
                        .setIgnoreEmptyLines(false).setMaxRows(MAX_ROWS + 2L).get()).get()) {
            List<CSVRecord> records = parser.getRecords();
            if (records.isEmpty()) throw CsvImportException.invalid("Le fichier est vide.");
            if (records.size() > MAX_ROWS + 1) throw CsvImportException.invalid("Limite : 2 000 opérations par fichier.");
            return records;
        } catch (IOException | java.io.UncheckedIOException | IllegalArgumentException exception) {
            throw CsvImportException.invalid("Le CSV est mal formé. Vérifiez les guillemets et le séparateur.");
        }
    }

    private Delimiter detectDelimiter(String text) {
        Delimiter best = null;
        int bestCount = 1;
        boolean tied = false;
        for (Delimiter candidate : List.of(Delimiter.COMMA, Delimiter.SEMICOLON, Delimiter.TAB)) {
            try (CSVParser parser = CSVParser.builder().setReader(new StringReader(text))
                    .setFormat(CSVFormat.RFC4180.builder().setDelimiter(character(candidate)).setMaxRows(1).get()).get()) {
                List<CSVRecord> first = parser.getRecords();
                int count = first.isEmpty() ? 0 : first.getFirst().size();
                if (count > bestCount) { best = candidate; bestCount = count; tied = false; }
                else if (count == bestCount && count > 1) tied = true;
            } catch (IOException | java.io.UncheckedIOException | IllegalArgumentException exception) { /* Try the remaining supported separators. */ }
        }
        if (best == null || tied) throw CsvImportException.invalid("Choisissez explicitement le séparateur du CSV.");
        return best;
    }

    private char character(Delimiter delimiter) {
        return switch (delimiter) {
            case COMMA -> ',';
            case SEMICOLON -> ';';
            case TAB -> '\t';
            case AUTO -> throw new IllegalArgumentException("delimiter must be resolved");
        };
    }

    private Mapping proposeMapping(List<String> columns) {
        return new Mapping(match(columns, Set.of("date", "booking_date", "date_operation")),
                match(columns, Set.of("description", "libelle", "label")),
                match(columns, Set.of("amount", "montant")),
                match(columns, Set.of("direction", "sens")),
                match(columns, Set.of("currency", "devise")));
    }

    private String match(List<String> columns, Set<String> aliases) {
        List<String> matches = columns.stream().filter(c -> aliases.contains(normalize(c).replace(' ', '_'))).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private boolean validMapping(Mapping mapping, List<String> columns) {
        List<String> selected = new ArrayList<>();
        if (mapping.dateColumn() == null || mapping.descriptionColumn() == null || mapping.amountColumn() == null) return false;
        selected.add(mapping.dateColumn()); selected.add(mapping.descriptionColumn()); selected.add(mapping.amountColumn());
        if (mapping.directionColumn() != null) selected.add(mapping.directionColumn());
        if (mapping.currencyColumn() != null) selected.add(mapping.currencyColumn());
        return columns.containsAll(selected) && new HashSet<>(selected).size() == selected.size();
    }

    private String cell(CSVRecord row, List<String> columns, String column) { return row.get(columns.indexOf(column)).trim(); }

    public static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }

    public record ParsedCsv(String hash, List<String> columns, Mapping mapping, Delimiter delimiter,
            DateFormat dateFormat, DecimalSeparator decimalSeparator, int rowCount,
            List<PreviewRow> validRows, int invalidCount, List<RowError> errors) { }
}

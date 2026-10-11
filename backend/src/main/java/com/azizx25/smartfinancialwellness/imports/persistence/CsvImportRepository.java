package com.azizx25.smartfinancialwellness.imports.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.azizx25.smartfinancialwellness.imports.CsvImportException;
import com.azizx25.smartfinancialwellness.imports.CsvTransactionParser.ParsedCsv;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Parameterized persistence for the existing V6 import aggregate. No raw file is stored. */
@Repository
public class CsvImportRepository {
    private static final String S = "smart_financial_wellness.";
    private final JdbcTemplate jdbc;

    public CsvImportRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void lockOwner(UUID userId) {
        if (jdbc.queryForList("SELECT id FROM " + S + "app_user WHERE id = ? FOR UPDATE", UUID.class, userId).isEmpty()) {
            throw CsvImportException.notFound();
        }
    }

    public UUID sourceFile(UUID userId, String filename, int size, ParsedCsv parsed, Instant now) {
        return jdbc.queryForObject("INSERT INTO " + S + "source_file "
                + "(id,user_id,file_type,original_filename,mime_type,size_bytes,sha256,storage_reference,file_metadata,created_at) "
                + "VALUES (?,?,'CSV',?,'text/csv',?,?,?,?::jsonb,?) "
                + "ON CONFLICT (user_id,sha256) DO UPDATE SET sha256 = EXCLUDED.sha256 RETURNING id",
                UUID.class, UUID.randomUUID(), userId, filename, size, parsed.hash(),
                "not-retained:sha256:" + parsed.hash(),
                "{\"encoding\":\"UTF-8\",\"delimiter\":\"" + parsed.delimiter().name() + "\",\"retained\":false}",
                Timestamp.from(now));
    }

    public Job previewJob(UUID userId, UUID accountId, UUID fileId, ParsedCsv parsed, Instant now) {
        List<Job> existing = jdbc.query("SELECT job.*, file.sha256 FROM " + S + "import_job job JOIN "
                + S + "source_file file ON file.id=job.source_file_id "
                + "WHERE job.user_id=? AND job.financial_account_id=? AND job.source_file_id=? "
                + "ORDER BY job.created_at DESC,job.id DESC LIMIT 1 FOR UPDATE OF job",
                (rs, index) -> job(rs), userId, accountId, fileId);
        Job result = existing.isEmpty() ? null : existing.getFirst();
        if (result != null && "COMPLETED".equals(result.status())) return result;
        if (result == null || result.createdAt().plusSeconds(1800).isBefore(now)) {
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO " + S + "import_job (id,user_id,financial_account_id,source_file_id,created_at,updated_at) "
                    + "VALUES (?,?,?,?,?,?)", id, userId, accountId, fileId, Timestamp.from(now), Timestamp.from(now));
            result = new Job(id, accountId, fileId, parsed.hash(), "UPLOADED", 0, 0, 0, now);
        }
        jdbc.update("UPDATE " + S + "import_job SET status=?,row_count=?,rejected_count=? WHERE id=?",
                parsed.invalidCount() == 0 ? "READY" : "MAPPING_REQUIRED", parsed.rowCount(), parsed.invalidCount(), result.id());
        saveMapping(result.id(), parsed, now);
        jdbc.update("DELETE FROM " + S + "rejected_import_row WHERE import_job_id=?", result.id());
        for (var error : parsed.errors()) {
            jdbc.update("INSERT INTO " + S + "rejected_import_row "
                    + "(import_job_id,source_row_number,error_code,safe_error_message) VALUES (?,?,?,?)",
                    result.id(), error.rowNumber(), error.code(), error.message());
        }
        return result;
    }

    private void saveMapping(UUID jobId, ParsedCsv parsed, Instant now) {
        UUID mappingId = jdbc.queryForObject("INSERT INTO " + S + "import_mapping "
                + "(id,import_job_id,detection_method,created_at,updated_at) VALUES (?,?,'USER',?,?) "
                + "ON CONFLICT (import_job_id) DO UPDATE SET user_confirmed=false,confirmed_at=NULL RETURNING id",
                UUID.class, UUID.randomUUID(), jobId, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("DELETE FROM " + S + "column_mapping WHERE import_mapping_id=?", mappingId);
        Set<String> selected = new java.util.HashSet<>();
        configuration(parsed).forEach((field, column) -> {
            if (parsed.columns().contains(column.column()) && selected.add(column.column())) {
                jdbc.update("INSERT INTO " + S + "column_mapping "
                        + "(import_mapping_id,canonical_field,source_column,detected_format) VALUES (?,?,?,?)",
                        mappingId, field, column.column(), column.format());
            }
        });
    }

    public boolean mappingMatches(UUID jobId, ParsedCsv parsed) {
        Map<String, Column> saved = new LinkedHashMap<>();
        jdbc.query("SELECT canonical_field,source_column,detected_format FROM " + S + "column_mapping col JOIN "
                + S + "import_mapping mapping ON mapping.id=col.import_mapping_id WHERE mapping.import_job_id=?",
                rs -> { saved.put(rs.getString(1), new Column(rs.getString(2), rs.getString(3))); }, jobId);
        return saved.equals(configuration(parsed));
    }

    private Map<String, Column> configuration(ParsedCsv parsed) {
        Map<String, Column> result = new LinkedHashMap<>();
        var m = parsed.mapping();
        if (m.dateColumn() != null) result.put("booking_date", new Column(m.dateColumn(), parsed.dateFormat() + ":" + parsed.delimiter()));
        if (m.descriptionColumn() != null) result.put("description", new Column(m.descriptionColumn(), null));
        if (m.amountColumn() != null) result.put("amount", new Column(m.amountColumn(), parsed.decimalSeparator().name()));
        if (m.directionColumn() != null) result.put("direction", new Column(m.directionColumn(), null));
        if (m.currencyColumn() != null) result.put("currency", new Column(m.currencyColumn(), null));
        return result;
    }

    public Job ownedJob(UUID jobId, UUID userId) {
        return jdbc.query("SELECT job.*,file.sha256 FROM " + S + "import_job job JOIN " + S
                + "source_file file ON file.id=job.source_file_id WHERE job.id=? AND job.user_id=? FOR UPDATE OF job",
                (rs, index) -> job(rs), jobId, userId).stream().findFirst().orElseThrow(CsvImportException::notFound);
    }

    public Set<Integer> importedRows(UUID accountId, UUID fileId) {
        return jdbc.queryForList("SELECT source_row_number FROM " + S
                + "transaction_record WHERE financial_account_id=? AND source_file_id=? AND source_type='FILE_IMPORT'",
                Integer.class, accountId, fileId).stream().collect(Collectors.toSet());
    }

    public void complete(UUID jobId, int imported, int duplicates, Instant now) {
        jdbc.update("UPDATE " + S + "import_job SET status='COMPLETED',imported_count=?,duplicate_count=?,"
                + "rejected_count=0,started_at=?,completed_at=? WHERE id=?", imported, duplicates,
                Timestamp.from(now), Timestamp.from(now), jobId);
        jdbc.update("UPDATE " + S + "import_mapping SET user_confirmed=true,confirmed_at=? WHERE import_job_id=?",
                Timestamp.from(now), jobId);
        jdbc.update("UPDATE " + S + "column_mapping SET confirmed=true WHERE import_mapping_id="
                + "(SELECT id FROM " + S + "import_mapping WHERE import_job_id=?)", jobId);
    }

    private Job job(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Job(rs.getObject("id", UUID.class), rs.getObject("financial_account_id", UUID.class),
                rs.getObject("source_file_id", UUID.class), rs.getString("sha256").trim(), rs.getString("status"),
                rs.getInt("row_count"), rs.getInt("imported_count"), rs.getInt("duplicate_count"),
                rs.getTimestamp("created_at").toInstant());
    }

    private record Column(String column, String format) { }
    public record Job(UUID id, UUID accountId, UUID fileId, String hash, String status,
            int rowCount, int importedCount, int duplicateCount, Instant createdAt) { }
}

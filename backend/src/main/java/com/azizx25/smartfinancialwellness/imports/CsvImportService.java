package com.azizx25.smartfinancialwellness.imports;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Set;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.audit.AuditService;
import com.azizx25.smartfinancialwellness.bank.persistence.BalanceSnapshot;
import com.azizx25.smartfinancialwellness.bank.persistence.FinancialAccount;
import com.azizx25.smartfinancialwellness.bank.persistence.FinancialAccountRepository;
import com.azizx25.smartfinancialwellness.bank.persistence.TransactionRecord;
import com.azizx25.smartfinancialwellness.category.persistence.Category;
import com.azizx25.smartfinancialwellness.imports.api.CsvImportDtos.*;
import com.azizx25.smartfinancialwellness.imports.persistence.CsvImportRepository;
import com.azizx25.smartfinancialwellness.user.persistence.AppUser;
import com.azizx25.smartfinancialwellness.user.persistence.InterfacePreferenceRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class CsvImportService {
    private final CsvTransactionParser parser;
    private final CsvImportRepository imports;
    private final FinancialAccountRepository accounts;
    private final InterfacePreferenceRepository preferences;
    private final EntityManager entities;
    private final AuditService audit;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public CsvImportService(CsvTransactionParser parser, CsvImportRepository imports,
            FinancialAccountRepository accounts, InterfacePreferenceRepository preferences,
            EntityManager entities, AuditService audit, Clock clock, PlatformTransactionManager transactionManager) {
        this.parser = parser; this.imports = imports; this.accounts = accounts; this.preferences = preferences;
        this.entities = entities; this.audit = audit; this.clock = clock;
        transactions = new TransactionTemplate(transactionManager);
    }

    public ImportAccountResponse createAccount(UUID userId, AccountRequest request) {
        String name = request.name().trim();
        if (name.isBlank() || name.chars().anyMatch(Character::isISOControl)) {
            throw CsvImportException.invalid("Le nom du compte est invalide.");
        }
        try { Currency.getInstance(request.currency()); }
        catch (IllegalArgumentException exception) { throw CsvImportException.invalid("La devise est invalide."); }
        return transactions.execute(status -> {
            imports.lockOwner(userId);
            var existing = accounts.findByUser_IdAndAccountOriginAndNameIgnoreCaseAndCurrency(
                    userId, FinancialAccount.Origin.MANUAL, name, request.currency());
            if (existing.isPresent()) {
                var account = existing.get();
                if (account.getStatus() != FinancialAccount.Status.ACTIVE) {
                    throw CsvImportException.conflict("Ce compte est archivé. Choisissez un autre nom.");
                }
                return new ImportAccountResponse(account.getId(), account.getName(), account.getCurrency(), account.getCurrentBalance(), true);
            }
            Instant now = clock.instant();
            FinancialAccount account = FinancialAccount.manual(entities.getReference(AppUser.class, userId),
                    name, request.currency(), request.currentBalance(), now);
            entities.persist(account);
            entities.persist(BalanceSnapshot.declaredManually(account, now));
            audit.record(userId, "IMPORT_ACCOUNT_CREATED", "FINANCIAL_ACCOUNT", account.getId());
            return new ImportAccountResponse(account.getId(), name, account.getCurrency(), account.getCurrentBalance(), false);
        });
    }

    public PreviewResponse preview(UUID userId, MultipartFile file, ImportRequest request) {
        // Parse before the write transaction. No external service is contacted by this workflow.
        FinancialAccount account = ownedAccount(userId, request.accountId());
        byte[] bytes = read(file);
        var parsed = parser.parse(bytes, file.getOriginalFilename(), request, account.getCurrency(), today(userId));
        return transactions.execute(status -> {
            imports.lockOwner(userId);
            if (!ownedAccount(userId, request.accountId()).getCurrency().equals(account.getCurrency())) {
                throw CsvImportException.conflict("La devise du compte a changé. Prévisualisez à nouveau le fichier.");
            }
            Instant now = clock.instant();
            UUID sourceId = imports.sourceFile(userId, safeFilename(file.getOriginalFilename()), bytes.length, parsed, now);
            var job = imports.previewJob(userId, request.accountId(), sourceId, parsed, now);
            boolean completed = "COMPLETED".equals(job.status());
            if (completed && !imports.mappingMatches(job.id(), parsed)) {
                throw CsvImportException.conflict("Ce fichier a déjà été importé avec un autre mapping.");
            }
            Set<Integer> previous = imports.importedRows(job.accountId(), job.fileId());
            int duplicates = (int) parsed.validRows().stream().filter(row -> previous.contains(row.rowNumber())).count();
            if (!completed) audit.record(userId, "CSV_IMPORT_PREVIEWED", "IMPORT_JOB", job.id());
            return new PreviewResponse(job.id(), request.accountId(), account.getCurrency(), parsed.columns(),
                    parsed.mapping(), parsed.delimiter(), parsed.dateFormat(), parsed.decimalSeparator(),
                    parsed.rowCount(), parsed.validRows().size(), parsed.invalidCount(), duplicates,
                    parsed.invalidCount() == 0 && !completed, completed,
                    parsed.validRows().stream().limit(10).toList(), parsed.errors());
        });
    }

    public ImportResponse confirm(UUID userId, UUID jobId, MultipartFile file, ImportRequest request) {
        FinancialAccount selectedAccount = ownedAccount(userId, request.accountId());
        var parsed = parser.parse(read(file), file.getOriginalFilename(), request, selectedAccount.getCurrency(), today(userId));
        return transactions.execute(status -> {
            imports.lockOwner(userId);
            var job = imports.ownedJob(jobId, userId);
            FinancialAccount account = ownedAccount(userId, job.accountId());
            if (!job.accountId().equals(request.accountId()) || !job.hash().equals(parsed.hash())
                    || !account.getCurrency().equals(selectedAccount.getCurrency())) {
                throw CsvImportException.conflict("Le fichier ou le compte ne correspond pas à la prévisualisation.");
            }
            if (!imports.mappingMatches(jobId, parsed)) {
                throw CsvImportException.conflict("Le mapping a changé. Prévisualisez à nouveau le fichier.");
            }
            if ("COMPLETED".equals(job.status())) {
                return new ImportResponse(jobId, job.accountId(), "COMPLETED", job.rowCount(), 0, job.importedCount() + job.duplicateCount(), true);
            }
            if (!"READY".equals(job.status()) || parsed.invalidCount() != 0) {
                throw CsvImportException.invalid("Corrigez toutes les lignes avant de confirmer l'import.");
            }
            Instant now = clock.instant();
            if (job.createdAt().plusSeconds(1800).isBefore(now)) {
                throw CsvImportException.conflict("La prévisualisation a expiré. Prévisualisez à nouveau le fichier.");
            }
            Set<Integer> previous = imports.importedRows(job.accountId(), job.fileId());
            AppUser owner = entities.getReference(AppUser.class, userId);
            Category initial = entities.getReference(Category.class, TransactionRecord.UNCATEGORIZED_CATEGORY_ID);
            int imported = 0;
            for (var row : parsed.validRows()) {
                if (previous.contains(row.rowNumber())) continue;
                String normalized = CsvTransactionParser.normalize(row.description());
                String fingerprint = CsvTransactionParser.sha256((row.bookingDate() + "|" + row.amount().toPlainString()
                        + "|" + row.direction() + "|" + row.currency() + "|" + normalized).getBytes(StandardCharsets.UTF_8));
                entities.persist(TransactionRecord.imported(owner, account, initial, jobId, job.fileId(), row.rowNumber(),
                        row.bookingDate(), row.amount(), TransactionRecord.Direction.valueOf(row.direction()),
                        row.description(), normalized, fingerprint, now));
                imported++;
            }
            entities.flush();
            int duplicates = parsed.rowCount() - imported;
            imports.complete(jobId, imported, duplicates, now);
            audit.record(userId, "CSV_IMPORT_COMPLETED", "IMPORT_JOB", jobId);
            return new ImportResponse(jobId, job.accountId(), "COMPLETED", parsed.rowCount(), imported, duplicates, false);
        });
    }

    private FinancialAccount ownedAccount(UUID userId, UUID accountId) {
        return accounts.findByIdAndUser_Id(accountId, userId)
                .filter(account -> account.getStatus() == FinancialAccount.Status.ACTIVE)
                .orElseThrow(CsvImportException::notFound);
    }

    private LocalDate today(UUID userId) {
        String zone = preferences.findById(userId).map(preference -> preference.getTimeZone()).orElse("Africa/Tunis");
        try { return LocalDate.now(clock.withZone(ZoneId.of(zone))); }
        catch (java.time.DateTimeException exception) { return LocalDate.now(clock.withZone(ZoneId.of("Africa/Tunis"))); }
    }

    private byte[] read(MultipartFile file) {
        if (file.isEmpty() || file.getSize() > CsvTransactionParser.MAX_BYTES) {
            throw CsvImportException.invalid("Le fichier doit contenir entre 1 octet et 2 Mo.");
        }
        try { return file.getBytes(); }
        catch (IOException exception) { throw CsvImportException.invalid("Le fichier n'a pas pu être lu."); }
    }

    private String safeFilename(String filename) {
        String leaf = filename.replace('\\', '/');
        leaf = leaf.substring(leaf.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        return leaf.length() > 255 ? leaf.substring(leaf.length() - 255) : leaf;
    }
}

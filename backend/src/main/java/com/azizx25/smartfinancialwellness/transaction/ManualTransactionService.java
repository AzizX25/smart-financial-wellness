package com.azizx25.smartfinancialwellness.transaction;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import com.azizx25.smartfinancialwellness.audit.AuditService;
import com.azizx25.smartfinancialwellness.bank.persistence.*;
import com.azizx25.smartfinancialwellness.category.CategoryService;
import com.azizx25.smartfinancialwellness.category.persistence.Category;
import com.azizx25.smartfinancialwellness.category.persistence.CategoryRepository;
import com.azizx25.smartfinancialwellness.imports.CsvTransactionParser;
import com.azizx25.smartfinancialwellness.transaction.api.ManualTransactionDtos.*;
import com.azizx25.smartfinancialwellness.user.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ManualTransactionService {
    private final TransactionRecordRepository transactions;
    private final FinancialAccountRepository accounts;
    private final CategoryRepository categories;
    private final CategoryService categoryService;
    private final AppUserRepository users;
    private final InterfacePreferenceRepository preferences;
    private final AuditService audit;
    private final Clock clock;

    public ManualTransactionService(TransactionRecordRepository transactions, FinancialAccountRepository accounts,
            CategoryRepository categories, CategoryService categoryService, AppUserRepository users,
            InterfacePreferenceRepository preferences, AuditService audit, Clock clock) {
        this.transactions = transactions; this.accounts = accounts; this.categories = categories;
        this.categoryService = categoryService; this.users = users; this.preferences = preferences;
        this.audit = audit; this.clock = clock;
    }

    @Transactional
    public Response create(UUID userId, CreateRequest request) {
        validate(userId, request.bookingDate(), request.description(), request.amount());
        var owner = users.lockById(userId).orElseThrow(ManualTransactionException::notFound);
        FinancialAccount account = manualAccount(userId, request.accountId());
        validateCategory(userId, request.categoryId(), request.direction());
        String description = request.description().trim();
        var previousId = transactions.findManualIdempotencyId(userId, request.clientIdempotencyKey().toString());
        if (previousId.isPresent()) {
            var previous = transactions.findOwnedByIdForUpdate(previousId.get(), userId)
                    .orElseThrow(() -> ManualTransactionException.conflict("Cette opération a déjà été supprimée."));
            if (!previous.getAccountId().equals(request.accountId())
                    || !previous.getBookingDate().equals(request.bookingDate())
                    || previous.getAmount().compareTo(request.amount()) != 0
                    || previous.getDirection() != request.direction()
                    || !previous.getDescription().equals(description)
                    || !previous.getCategory().getId().equals(categoryId(request.categoryId()))) {
                throw ManualTransactionException.conflict("Cette clé de création a déjà été utilisée avec d'autres données.");
            }
            return response(previous);
        }
        var initial = categories.getReferenceById(TransactionRecord.UNCATEGORIZED_CATEGORY_ID);
        var transaction = transactions.saveAndFlush(TransactionRecord.manual(owner, account, initial,
                request.clientIdempotencyKey().toString(), request.bookingDate(), request.amount().setScale(4),
                request.direction(), description, CsvTransactionParser.normalize(description), clock.instant()));
        if (request.categoryId() != null) categoryService.assign(userId, transaction.getId(), request.categoryId());
        transactions.flush();
        audit.record(userId, "MANUAL_TRANSACTION_CREATED", "TRANSACTION", transaction.getId());
        return response(transaction);
    }

    @Transactional
    public Response update(UUID userId, UUID id, UpdateRequest request) {
        validate(userId, request.bookingDate(), request.description(), request.amount());
        var transaction = ownedEditable(userId, id, request.version());
        validateCategory(userId, request.categoryId(), request.direction());
        // Invalidate previous suggestions and category provenance before changing the descriptor/direction.
        // The normal category service retains append-only history and updates private memory explicitly.
        categoryService.prepareForManualEdit(userId, id);
        transactions.flush();
        String description = request.description().trim();
        transaction.updateLocally(request.bookingDate(), request.amount().setScale(4), request.direction(),
                description, CsvTransactionParser.normalize(description), clock.instant());
        transactions.flush();
        categoryService.assign(userId, id, request.categoryId());
        transactions.flush();
        audit.record(userId, transaction.getSourceType() == TransactionRecord.SourceType.MANUAL
                ? "MANUAL_TRANSACTION_UPDATED" : "TRANSACTION_LOCAL_CORRECTION", "TRANSACTION", id);
        return response(transaction);
    }

    private TransactionRecord ownedEditable(UUID userId, UUID id, int version) {
        // Serialize this user's local mutations and idempotent creates before locking the row.
        users.lockById(userId).orElseThrow(ManualTransactionException::notFound);
        var transaction = transactions.findOwnedByIdForUpdate(id, userId)
                .orElseThrow(ManualTransactionException::notFound);
        accounts.findByIdAndUser_Id(transaction.getAccountId(), userId)
                .orElseThrow(ManualTransactionException::notFound);
        if (transaction.getVersion() != version) {
            throw ManualTransactionException.conflict("Cette opération a changé. Actualisez la liste avant de réessayer.");
        }
        if (transactions.hasProtectedLinks(id)) {
            throw ManualTransactionException.conflict("Cette opération est liée à un objectif ou un transfert et doit être conservée.");
        }
        return transaction;
    }

    private FinancialAccount manualAccount(UUID userId, UUID accountId) {
        var account = accounts.findByIdAndUser_Id(accountId, userId).orElseThrow(ManualTransactionException::notFound);
        if (account.getAccountOrigin() != FinancialAccount.Origin.MANUAL || account.getStatus() != FinancialAccount.Status.ACTIVE) {
            throw ManualTransactionException.conflict("Choisissez un compte personnel actif, pas un compte bancaire synchronisé.");
        }
        return account;
    }

    private void validate(UUID userId, LocalDate date, String description, BigDecimal amount) {
        String zone = preferences.findById(userId).map(InterfacePreference::getTimeZone).orElse("Africa/Tunis");
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(zone)));
        if (date.getYear() < 1900 || date.isAfter(today)) throw ManualTransactionException.invalid("La date doit être comprise entre 1900 et aujourd'hui.");
        if (description.trim().isEmpty() || CsvTransactionParser.normalize(description).isBlank()
                || description.chars().anyMatch(Character::isISOControl)) {
            throw ManualTransactionException.invalid("Le libellé ne doit pas contenir de caractère de contrôle.");
        }
        if (amount.scale() > 4 || amount.signum() <= 0 || amount.precision() - amount.scale() > 15) {
            throw ManualTransactionException.invalid("Le montant doit être positif, avec quatre décimales au maximum.");
        }
    }

    private void validateCategory(UUID userId, UUID id, TransactionRecord.Direction direction) {
        if (id == null || id.equals(TransactionRecord.UNCATEGORIZED_CATEGORY_ID)) return;
        Category category = categories.findVisibleActiveById(id, userId).orElseThrow(ManualTransactionException::notFound);
        if (category.getDirection() != Category.Direction.BOTH && !category.getDirection().name().equals(direction.name())) {
            throw ManualTransactionException.invalid("La catégorie ne correspond pas au sens de l'opération.");
        }
    }

    private UUID categoryId(UUID id) { return id == null ? TransactionRecord.UNCATEGORIZED_CATEGORY_ID : id; }

    private Response response(TransactionRecord transaction) {
        UUID category = transaction.getCategory().getId();
        return new Response(transaction.getId(), transaction.getAccountId(), transaction.getSourceType().name(), transaction.getBookingDate(),
                transaction.getDescription(), transaction.getAmount().toPlainString(), transaction.getDirection().name(), transaction.getCurrency(),
                category.equals(TransactionRecord.UNCATEGORIZED_CATEGORY_ID) ? null : category, transaction.getVersion());
    }
}

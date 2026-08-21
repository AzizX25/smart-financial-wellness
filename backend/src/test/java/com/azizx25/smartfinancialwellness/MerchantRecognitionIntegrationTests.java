package com.azizx25.smartfinancialwellness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.category.infrastructure.MerchantAiExceptions.Unavailable;
import com.azizx25.smartfinancialwellness.category.infrastructure.MerchantRecognitionAiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, MerchantRecognitionIntegrationTests.AiTestConfiguration.class})
class MerchantRecognitionIntegrationTests {

    private static final String SCHEMA = "smart_financial_wellness.";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StubMerchantRecognitionAiClient aiClient;

    @BeforeEach
    void resetAiClient() {
        aiClient.reset();
    }

    @Test
    void rulesRunFirstAndOnlyEligibleSanitizedDebitsReachAiOutsideTransactions() throws Exception {
        AppRegistration user = registerAndActivate();
        UUID tndAccount = createManualAccount(user.userId(), "TND");
        UUID eurAccount = createManualAccount(user.userId(), "EUR");
        UUID deterministic = insertManualTransaction(user.userId(), tndAccount, "DEBIT", "TND", "STEG", "Facture electricite");
        UUID eligible = insertManualTransaction(user.userId(), tndAccount, "DEBIT", "TND", "Carrefour La Marsa", "contact@example.test 4111111111111111");
        UUID credit = insertManualTransaction(user.userId(), tndAccount, "CREDIT", "TND", "Prime", "Credit sans regle");
        UUID euro = insertManualTransaction(user.userId(), eurAccount, "DEBIT", "EUR", "Carrefour", "Paiement EUR");
        UUID outgoing = insertManualTransaction(user.userId(), tndAccount, "DEBIT", "TND", "Carrefour", "Virement interne");
        UUID secondAccount = createManualAccount(user.userId(), "TND");
        UUID incoming = insertManualTransaction(user.userId(), secondAccount, "CREDIT", "TND", null, "Virement interne");
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "internal_transfer "
                        + "(id, outgoing_transaction_id, incoming_transaction_id, detection_method, confirmed_at) "
                        + "VALUES (?, ?, ?, 'USER', CURRENT_TIMESTAMP)",
                UUID.randomUUID(), outgoing, incoming);

        mockMvc.perform(post("/api/categorization-runs/merchant-recognition")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analyzedCount").value(1))
                .andExpect(jsonPath("$.suggestedCount").value(1))
                .andExpect(jsonPath("$.unsupportedCurrencyCount").value(1))
                .andExpect(jsonPath("$.excludedCount").value(4))
                .andExpect(jsonPath("$.outcome").value("SUGGESTED"));

        assertThat(aiClient.transactionActiveDuringCall).isFalse();
        assertThat(aiClient.requests).singleElement().satisfies(request -> {
            assertThat(request.requestKey()).isEqualTo("merchant-1");
            assertThat(request.sanitizedMerchantText()).isEqualTo("carrefour la marsa");
            assertThat(request.direction()).isEqualTo("DEBIT");
            assertThat(request.currency()).isEqualTo("TND");
            assertThat(request.featureVersion()).isEqualTo("merchant-recognition-features-v1");
            assertThat(request.toString()).doesNotContain(
                    user.userId().toString(), eligible.toString(), "4111111111111111", "example.test");
        });
        assertThat(categorySource(deterministic)).isEqualTo("RULES");
        assertThat(categorySource(outgoing)).isEqualTo("UNCATEGORIZED");
        assertThat(categorySource(eligible)).isEqualTo("UNCATEGORIZED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT review_status FROM " + SCHEMA + "category_prediction WHERE transaction_id = ?",
                String.class, eligible)).isEqualTo("PENDING");

        JsonNode reviews = reviews(user);
        assertReview(reviews, eligible, "SUGGESTION_PENDING", "RELIABLE_SUGGESTION");
        assertReview(reviews, credit, "EXCLUDED", "CREDIT_NOT_SUPPORTED");
        assertReview(reviews, euro, "EXCLUDED", "UNSUPPORTED_CURRENCY");
        assertReview(reviews, outgoing, "EXCLUDED", "INTERNAL_TRANSFER");
        assertReview(reviews, incoming, "EXCLUDED", "INTERNAL_TRANSFER");
        String reviewJson = reviews.toString();
        assertThat(reviewJson).doesNotContain(
                user.userId().toString(), tndAccount.toString(), eurAccount.toString(),
                "confidence", "score", "salaryPlan", "forecast");
    }

    @Test
    void pendingSuggestionCanBeAcceptedOrRejectedOnlyByItsOwner() throws Exception {
        AppRegistration owner = registerAndActivate();
        AppRegistration other = registerAndActivate();
        UUID transactionId = insertManualTransaction(
                owner.userId(), createManualAccount(owner.userId(), "TND"),
                "DEBIT", "TND", "Carrefour", "Paiement marchand");

        runRecognition(owner);
        JsonNode suggestion = pendingSuggestion(owner);
        UUID predictionId = UUID.fromString(suggestion.get("predictionId").asText());
        assertThat(suggestion.get("canonicalMerchant").asText()).isEqualTo("Carrefour");
        assertThat(suggestion.get("categoryCode").asText()).isEqualTo("FOOD");
        assertThat(categorySource(transactionId)).isEqualTo("UNCATEGORIZED");

        mockMvc.perform(post("/api/transactions/{transactionId}/merchant-suggestions/{predictionId}/accept",
                        transactionId, predictionId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(other.token())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MERCHANT_SUGGESTION_NOT_FOUND"));

        mockMvc.perform(post("/api/transactions/{transactionId}/merchant-suggestions/{predictionId}/accept",
                        transactionId, predictionId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignmentSource").value("ML"))
                .andExpect(jsonPath("$.categoryName").value("Alimentation et courses"));

        assertThat(categorySource(transactionId)).isEqualTo("ML");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT review_status FROM " + SCHEMA + "category_prediction WHERE id = ?",
                String.class, predictionId)).isEqualTo("ACCEPTED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "transaction_category_history "
                        + "WHERE transaction_id = ? AND new_source = 'ML'",
                Integer.class, transactionId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "audit_log_entry "
                        + "WHERE actor_id = ? AND action = 'MERCHANT_CATEGORY_SUGGESTION_ACCEPTED'",
                Integer.class, owner.userId())).isEqualTo(1);

        UUID rejectedTransaction = insertManualTransaction(
                owner.userId(), createManualAccount(owner.userId(), "TND"),
                "DEBIT", "TND", "Carrefour", "Autre paiement");
        runRecognition(owner);
        JsonNode rejected = pendingSuggestion(owner, rejectedTransaction);
        mockMvc.perform(post("/api/transactions/{transactionId}/merchant-suggestions/{predictionId}/reject",
                        rejectedTransaction, rejected.get("predictionId").asText())
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token())))
                .andExpect(status().isNoContent());
        assertThat(categorySource(rejectedTransaction)).isEqualTo("UNCATEGORIZED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT review_status FROM " + SCHEMA + "category_prediction WHERE id = ?",
                String.class, UUID.fromString(rejected.get("predictionId").asText()))).isEqualTo("REJECTED");
        assertReview(reviews(owner), rejectedTransaction, "NEEDS_REVIEW", "SUGGESTION_REJECTED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "user_merchant_memory WHERE user_id = ? "
                        + "AND normalized_merchant_key = 'carrefour'",
                Integer.class, owner.userId())).isEqualTo(1);
    }

    @Test
    void manualChoiceDuringAiCallWinsAndServiceFailureIsControlled() throws Exception {
        AppRegistration user = registerAndActivate();
        UUID transactionId = insertManualTransaction(
                user.userId(), createManualAccount(user.userId(), "TND"),
                "DEBIT", "TND", "Carrefour", "Paiement marchand");
        UUID food = category("FOOD");
        aiClient.duringCall = () -> jdbcTemplate.update(
                "UPDATE " + SCHEMA + "transaction_record SET category_id = ?, category_source = 'USER', "
                        + "categorized_at = CURRENT_TIMESTAMP WHERE id = ?",
                food, transactionId);

        mockMvc.perform(post("/api/categorization-runs/merchant-recognition")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestedCount").value(0));
        assertThat(categorySource(transactionId)).isEqualTo("USER");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "category_prediction WHERE transaction_id = ?",
                Integer.class, transactionId)).isZero();

        UUID unavailableTransaction = insertManualTransaction(
                user.userId(), createManualAccount(user.userId(), "TND"),
                "DEBIT", "TND", "Carrefour", "Paiement indisponible");
        aiClient.reset();
        aiClient.unavailable = true;
        mockMvc.perform(post("/api/categorization-runs/merchant-recognition")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.reviewRequiredCount").value(1));
        assertThat(categorySource(unavailableTransaction)).isEqualTo("UNCATEGORIZED");
        assertReview(reviews(user), unavailableTransaction, "NEEDS_REVIEW", "SERVICE_UNAVAILABLE");
    }

    @Test
    void invalidAiBatchDoesNotPersistAnyPartialSuggestion() throws Exception {
        AppRegistration user = registerAndActivate();
        UUID accountId = createManualAccount(user.userId(), "TND");
        UUID first = insertManualTransaction(
                user.userId(), accountId, "DEBIT", "TND", "Carrefour", "Premier paiement");
        UUID second = insertManualTransaction(
                user.userId(), accountId, "DEBIT", "TND", "Bolt", "Deuxieme paiement");
        aiClient.invalidSecondPrediction = true;

        mockMvc.perform(post("/api/categorization-runs/merchant-recognition")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analyzedCount").value(2))
                .andExpect(jsonPath("$.suggestedCount").value(0))
                .andExpect(jsonPath("$.outcome").value("INVALID_RESPONSE"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "category_prediction WHERE transaction_id IN (?, ?)",
                Integer.class, first, second)).isZero();
        assertThat(categorySource(first)).isEqualTo("UNCATEGORIZED");
        assertThat(categorySource(second)).isEqualTo("UNCATEGORIZED");
        assertReview(reviews(user), first, "NEEDS_REVIEW", "INVALID_RESPONSE");
        assertReview(reviews(user), second, "NEEDS_REVIEW", "INVALID_RESPONSE");
    }

    @Test
    void webEnrichedSuggestionIsPendingOwnerScopedImmutableAndAcceptedAsLlm() throws Exception {
        AppRegistration owner = registerAndActivate();
        AppRegistration other = registerAndActivate();
        UUID transactionId = insertManualTransaction(
                owner.userId(), createManualAccount(owner.userId(), "TND"),
                "DEBIT", "TND", "Commerce Exemple", "Paiement marchand inconnu");
        aiClient.webEnriched = true;

        mockMvc.perform(post("/api/categorization-runs/merchant-recognition")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestedCount").value(1))
                .andExpect(jsonPath("$.outcome").value("SUGGESTED"));

        MvcResult otherResult = mockMvc.perform(get("/api/merchant-suggestions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(other.token())))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(otherResult.getResponse().getContentAsString()).size()).isZero();

        JsonNode suggestion = pendingSuggestion(owner, transactionId);
        UUID predictionId = UUID.fromString(suggestion.get("predictionId").asText());
        assertThat(suggestion.get("matchMethod").asText()).isEqualTo("WEB_LLM");
        assertThat(suggestion.get("source").asText()).isEqualTo("WEB_LLM");
        assertThat(suggestion.get("rationale").asText()).isEqualTo(
                "Le resultat public decrit un commerce de detail.");
        assertThat(suggestion.get("evidenceUrls").get(0).asText()).isEqualTo(
                "https://example.test/merchant");
        assertThat(suggestion.get("experimental").asBoolean()).isTrue();
        assertThat(suggestion.get("guaranteed").asBoolean()).isFalse();
        assertThat(categorySource(transactionId)).isEqualTo("UNCATEGORIZED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT prediction_method || '|' || review_status FROM " + SCHEMA
                        + "category_prediction WHERE id = ?",
                String.class, predictionId)).isEqualTo("LLM|PENDING");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE " + SCHEMA + "category_prediction_evidence "
                        + "SET evidence_url = 'https://example.test/changed' WHERE prediction_id = ?",
                predictionId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM " + SCHEMA + "category_prediction_evidence WHERE prediction_id = ?",
                predictionId)).isInstanceOf(DataAccessException.class);

        mockMvc.perform(post("/api/transactions/{transactionId}/merchant-suggestions/{predictionId}/accept",
                        transactionId, predictionId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignmentSource").value("LLM"));
        assertThat(categorySource(transactionId)).isEqualTo("LLM");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "transaction_category_history "
                        + "WHERE transaction_id = ? AND new_source = 'LLM'",
                Integer.class, transactionId)).isEqualTo(1);
    }

    @Test
    void webEnrichmentAbstentionAndRejectionLeaveTransactionsUncategorized() throws Exception {
        AppRegistration user = registerAndActivate();
        UUID accountId = createManualAccount(user.userId(), "TND");
        UUID abstained = insertManualTransaction(
                user.userId(), accountId, "DEBIT", "TND", "Commerce Inconnu", "Paiement inconnu");
        aiClient.enrichmentAbstained = true;

        mockMvc.perform(post("/api/categorization-runs/merchant-recognition")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("UNRECOGNIZED"))
                .andExpect(jsonPath("$.suggestedCount").value(0));
        assertThat(categorySource(abstained)).isEqualTo("UNCATEGORIZED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "category_prediction WHERE transaction_id = ?",
                Integer.class, abstained)).isZero();

        AppRegistration reviewer = registerAndActivate();
        UUID rejected = insertManualTransaction(
                reviewer.userId(), createManualAccount(reviewer.userId(), "TND"),
                "DEBIT", "TND", "Commerce Exemple", "Autre paiement inconnu");
        aiClient.reset();
        aiClient.webEnriched = true;
        runRecognition(reviewer);
        JsonNode suggestion = pendingSuggestion(reviewer, rejected);
        mockMvc.perform(post("/api/transactions/{transactionId}/merchant-suggestions/{predictionId}/reject",
                        rejected, suggestion.get("predictionId").asText())
                        .header(HttpHeaders.AUTHORIZATION, bearer(reviewer.token())))
                .andExpect(status().isNoContent());
        assertThat(categorySource(rejected)).isEqualTo("UNCATEGORIZED");
    }

    @Test
    void modelAbstentionPersistsThreeOrderedCandidatesWithoutScores() throws Exception {
        AppRegistration user = registerAndActivate();
        UUID transactionId = insertManualTransaction(
                user.userId(), createManualAccount(user.userId(), "TND"),
                "DEBIT", "TND", "Marchand nouveau", "Paiement inconnu");
        aiClient.modelAbstainedWithCandidates = true;

        mockMvc.perform(post("/api/categorization-runs/merchant-recognition")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewRequiredCount").value(1))
                .andExpect(jsonPath("$.suggestedCount").value(0));

        JsonNode item = assertReview(
                reviews(user), transactionId, "NEEDS_REVIEW", "MODEL_ABSTAINED");
        List<String> candidateCodes = new ArrayList<>();
        item.get("candidateCategories").forEach(
                candidate -> candidateCodes.add(candidate.get("categoryCode").asText()));
        assertThat(candidateCodes).containsExactly("FOOD", "SHOPPING", "TRANSPORT");
        assertThat(item.toString()).doesNotContain("confidence", "score");
        assertThat(item.get("manualCategorizationAllowed").asBoolean()).isTrue();
    }

    @Test
    void pendingAndReversedBankTransactionsHaveExplicitExcludedReviews() throws Exception {
        AppRegistration user = registerAndActivate();
        BankSyncFixture bank = createBankSyncFixture(user.userId());
        UUID pending = insertBankTransaction(bank, "PENDING");
        UUID reversed = insertBankTransaction(bank, "REVERSED");

        mockMvc.perform(post("/api/categorization-runs/merchant-recognition")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analyzedCount").value(0))
                .andExpect(jsonPath("$.excludedCount").value(2));

        assertThat(aiClient.requests).isEmpty();
        assertReview(reviews(user), pending, "EXCLUDED", "NOT_POSTED");
        assertReview(reviews(user), reversed, "EXCLUDED", "REVERSED");
    }

    @Test
    void explicitChoicesCreatePrivateMemoryExactMatchAvoidsAiAndOwnerCanForgetIt() throws Exception {
        AppRegistration owner = registerAndActivate();
        AppRegistration other = registerAndActivate();
        UUID ownerAccount = createManualAccount(owner.userId(), "TND");
        UUID original = insertManualTransaction(
                owner.userId(), ownerAccount, "DEBIT", "TND", "Boutique Privee", "Premier achat");

        mockMvc.perform(patch("/api/transactions/{transactionId}/category", original)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"" + category("FOOD") + "\"}"))
                .andExpect(status().isOk());
        JsonNode memories = memories(owner);
        assertThat(memories).hasSize(1);
        UUID memoryId = UUID.fromString(memories.get(0).get("id").asText());
        assertThat(memories.get(0).get("normalizedMerchant").asText()).isEqualTo("boutique privee");

        UUID repeated = insertManualTransaction(
                owner.userId(), ownerAccount, "DEBIT", "TND", "Boutique Privee", "Deuxieme achat");
        aiClient.reset();
        runRecognition(owner);
        assertThat(aiClient.requests).isEmpty();
        JsonNode memorySuggestion = pendingSuggestion(owner, repeated);
        assertThat(memorySuggestion.get("matchMethod").asText()).isEqualTo("USER_MEMORY");
        mockMvc.perform(post("/api/transactions/{transactionId}/merchant-suggestions/{predictionId}/accept",
                        repeated, memorySuggestion.get("predictionId").asText())
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignmentSource").value("USER_MEMORY"));

        mockMvc.perform(patch("/api/transactions/{transactionId}/category", original)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"" + category("SHOPPING") + "\"}"))
                .andExpect(status().isOk());
        assertThat(memories(owner).get(0).get("categoryName").asText()).isEqualTo("Shopping");

        UUID foreignTransaction = insertManualTransaction(
                other.userId(), createManualAccount(other.userId(), "TND"),
                "DEBIT", "TND", "Boutique Privee", "Achat autre utilisateur");
        aiClient.reset();
        runRecognition(other);
        assertThat(aiClient.requests).hasSize(1);
        assertThat(memories(other)).isEmpty();
        assertThat(categorySource(foreignTransaction)).isEqualTo("UNCATEGORIZED");
        mockMvc.perform(delete("/api/merchant-memories/{memoryId}", memoryId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(other.token())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MERCHANT_MEMORY_NOT_FOUND"));

        mockMvc.perform(delete("/api/merchant-memories/{memoryId}", memoryId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.token())))
                .andExpect(status().isNoContent());
        assertThat(memories(owner)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + "audit_log_entry WHERE actor_id = ? "
                        + "AND action IN ('MERCHANT_MEMORY_CREATED','MERCHANT_MEMORY_UPDATED','MERCHANT_MEMORY_DELETED')",
                Integer.class, owner.userId())).isEqualTo(3);
    }

    @Test
    void migrationRejectsIncompatibleMlEvidenceAndProtectsPendingEvidence() throws Exception {
        AppRegistration user = registerAndActivate();
        UUID transactionId = insertManualTransaction(
                user.userId(), createManualAccount(user.userId(), "TND"),
                "DEBIT", "TND", "Carrefour", "Paiement marchand");

        assertThatThrownBy(() -> insertPrediction(transactionId, category("INCOME_SALARY")))
                .isInstanceOf(DataAccessException.class);

        UUID predictionId = insertPrediction(transactionId, category("FOOD"));
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE " + SCHEMA + "transaction_record SET category_id = ?, category_source = 'ML', "
                        + "applied_category_prediction_id = ?, categorized_at = CURRENT_TIMESTAMP WHERE id = ?",
                category("FOOD"), predictionId, transactionId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM " + SCHEMA + "category_prediction WHERE id = ?", predictionId))
                .isInstanceOf(DataAccessException.class);
    }

    private UUID insertPrediction(UUID transactionId, UUID categoryId) {
        UUID predictionId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "category_prediction "
                        + "(id, transaction_id, category_id, prediction_method, canonical_merchant, "
                        + "prediction_score, model_id, model_version, feature_version, review_status) "
                        + "VALUES (?, ?, ?, 'ML', 'Carrefour', 0.9000, "
                        + "'merchant-recognition-synthetic-v1', '1.0.0', "
                        + "'merchant-recognition-features-v1', 'PENDING')",
                predictionId, transactionId, categoryId);
        return predictionId;
    }

    private void runRecognition(AppRegistration user) throws Exception {
        mockMvc.perform(post("/api/categorization-runs/merchant-recognition")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.suggestedCount").value(1));
    }

    private JsonNode pendingSuggestion(AppRegistration user) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/merchant-suggestions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk()).andReturn();
        JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(items.size()).isEqualTo(1);
        return items.get(0);
    }

    private JsonNode pendingSuggestion(AppRegistration user, UUID transactionId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/merchant-suggestions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk()).andReturn();
        for (JsonNode item : objectMapper.readTree(result.getResponse().getContentAsString())) {
            if (transactionId.toString().equals(item.get("transactionId").asText())) return item;
        }
        throw new AssertionError("No pending suggestion for transaction " + transactionId);
    }

    private JsonNode reviews(AppRegistration user) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/categorization-reviews?page=0&size=100")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("items");
    }

    private JsonNode assertReview(JsonNode reviews, UUID transactionId, String state, String reason) {
        for (JsonNode review : reviews) {
            if (transactionId.toString().equals(review.get("transactionId").asText())) {
                assertThat(review.get("reviewStatus").asText()).isEqualTo(state);
                assertThat(review.get("reasonCode").asText()).isEqualTo(reason);
                return review;
            }
        }
        throw new AssertionError("No categorization review for transaction " + transactionId);
    }

    private JsonNode memories(AppRegistration user) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/merchant-memories")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private AppRegistration registerAndActivate() throws Exception {
        String email = "merchant-" + UUID.randomUUID() + "@example.test";
        MvcResult result = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"email\":\"%s\",\"password\":\"StrongPass123!\","
                                + "\"firstName\":\"Merchant\",\"lastName\":\"Tester\","
                                + "\"displayName\":\"Merchant Tester\"}").formatted(email)))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID userId = UUID.fromString(body.get("user").get("id").asText());
        jdbcTemplate.update("UPDATE " + SCHEMA + "app_user SET status = 'ACTIVE' WHERE id = ?", userId);
        jdbcTemplate.update("UPDATE " + SCHEMA + "authentication_identity "
                + "SET status = 'ACTIVE', verified_at = CURRENT_TIMESTAMP WHERE user_id = ?", userId);
        return new AppRegistration(userId, body.get("accessToken").asText());
    }

    private UUID createManualAccount(UUID userId, String currency) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "financial_account "
                        + "(id, user_id, account_origin, name, masked_identifier, account_type, currency, status) "
                        + "VALUES (?, ?, 'MANUAL', 'Merchant test account', '****9999', 'OTHER', ?, 'ACTIVE')",
                id, userId, currency);
        return id;
    }

    private UUID insertManualTransaction(
            UUID userId, UUID accountId, String direction, String currency,
            String recognizedMerchant, String description) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "transaction_record "
                        + "(id, user_id, financial_account_id, category_id, source_type, client_idempotency_key, "
                        + "booking_date, amount, direction, currency, raw_description, normalized_description, "
                        + "recognized_merchant, category_source) "
                        + "VALUES (?, ?, ?, ?, 'MANUAL', ?, ?, 25.0000, ?, ?, ?, ?, ?, 'UNCATEGORIZED')",
                id, userId, accountId, category("UNCATEGORIZED"), UUID.randomUUID().toString(),
                LocalDate.of(2026, 8, 19), direction, currency, description,
                description.toLowerCase(), recognizedMerchant);
        return id;
    }

    private BankSyncFixture createBankSyncFixture(UUID userId) {
        UUID connectionId = UUID.randomUUID();
        UUID authorizationId = UUID.randomUUID();
        UUID syncJobId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        String providerCode = "REVIEW_" + connectionId.toString().substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "bank_connection "
                        + "(id, user_id, provider_code, external_customer_id, status) "
                        + "VALUES (?, ?, ?, ?, 'ACTIVE')",
                connectionId, userId, providerCode, "CUSTOMER-" + connectionId);
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "bank_connection_authorization "
                        + "(id, bank_connection_id, status, secret_reference, granted_scopes, consented_at, expires_at) "
                        + "VALUES (?, ?, 'ACTIVE', ?, "
                        + "ARRAY['ACCOUNTS_READ','BALANCES_READ','TRANSACTIONS_READ']::VARCHAR(40)[], "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 day')",
                authorizationId, connectionId, "test://review/" + authorizationId);
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "sync_job "
                        + "(id, bank_connection_id, authorization_id, status, started_at) "
                        + "VALUES (?, ?, ?, 'RUNNING', CURRENT_TIMESTAMP)",
                syncJobId, connectionId, authorizationId);
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "financial_account "
                        + "(id, user_id, bank_connection_id, account_origin, external_account_id, name, "
                        + "account_type, currency, provider_code) "
                        + "VALUES (?, ?, ?, 'BANK_CONNECTION', ?, 'Review bank account', 'CHECKING', 'TND', ?)",
                accountId, userId, connectionId, "ACCOUNT-" + accountId, providerCode);
        return new BankSyncFixture(userId, accountId, syncJobId);
    }

    private UUID insertBankTransaction(BankSyncFixture fixture, String providerStatus) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + SCHEMA + "transaction_record "
                        + "(id, user_id, financial_account_id, category_id, source_type, sync_job_id, "
                        + "external_transaction_id, booking_date, amount, direction, currency, raw_description, "
                        + "normalized_description, recognized_merchant, category_source, provider_status, "
                        + "first_imported_at, last_synchronized_at) "
                        + "VALUES (?, ?, ?, ?, 'BANK_SYNC', ?, ?, DATE '2026-08-20', 10.0000, 'DEBIT', 'TND', "
                        + "'Provider transaction', 'provider transaction', 'Provider merchant', "
                        + "'UNCATEGORIZED', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                id, fixture.userId(), fixture.accountId(), category("UNCATEGORIZED"), fixture.syncJobId(),
                "TX-" + id, providerStatus);
        return id;
    }

    private UUID category(String code) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM " + SCHEMA + "category WHERE code = ? AND user_id IS NULL",
                UUID.class, code);
    }

    private String categorySource(UUID transactionId) {
        return jdbcTemplate.queryForObject(
                "SELECT category_source FROM " + SCHEMA + "transaction_record WHERE id = ?",
                String.class, transactionId);
    }

    private String bearer(String token) { return "Bearer " + token; }

    private record AppRegistration(UUID userId, String token) { }
    private record BankSyncFixture(UUID userId, UUID accountId, UUID syncJobId) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class AiTestConfiguration {
        @Bean
        @Primary
        StubMerchantRecognitionAiClient testMerchantRecognitionAiClient() {
            return new StubMerchantRecognitionAiClient();
        }
    }

    static final class StubMerchantRecognitionAiClient implements MerchantRecognitionAiClient {
        private final List<RequestItem> requests = new ArrayList<>();
        private Runnable duringCall;
        private boolean unavailable;
        private boolean invalidSecondPrediction;
        private boolean webEnriched;
        private boolean enrichmentAbstained;
        private boolean modelAbstainedWithCandidates;
        private boolean transactionActiveDuringCall;

        @Override
        public List<Prediction> predict(List<RequestItem> items) {
            transactionActiveDuringCall = TransactionSynchronizationManager.isActualTransactionActive();
            requests.addAll(items);
            if (duringCall != null) duringCall.run();
            if (unavailable) throw new Unavailable(new IllegalStateException("test unavailable"));
            if (enrichmentAbstained) {
                return items.stream().map(item -> new Prediction(
                        item.requestKey(), "UNRECOGNIZED", null, null, null, null,
                        "merchant-recognition-synthetic-v1", "1.0.0",
                        "merchant-recognition-features-v1", true, true, false,
                        "ENRICHMENT_UNAVAILABLE", null, List.of(), null, null, null, List.of())).toList();
            }
            if (modelAbstainedWithCandidates) {
                return items.stream().map(item -> new Prediction(
                        item.requestKey(), "UNRECOGNIZED", null, null, null, null,
                        "merchant-recognition-synthetic-v1", "1.0.0",
                        "merchant-recognition-features-v1", true, true, false,
                        "MODEL_ABSTAINED", null, List.of(), null, null, null,
                        List.of("FOOD", "SHOPPING", "TRANSPORT"))).toList();
            }
            if (webEnriched) {
                return items.stream().map(item -> new Prediction(
                        item.requestKey(), "RECOGNIZED", "Commerce Exemple", "SHOPPING", "WEB_LLM", null,
                        "qwen3:4b", "ollama-api-v1", "merchant-enrichment-features-v1",
                        true, false, false, null,
                        "Le resultat public decrit un commerce de detail.",
                        List.of("https://example.test/merchant"), "ollama",
                        "merchant-catalog-v1", "system-taxonomy-v2", List.of())).toList();
            }
            List<Prediction> predictions = new ArrayList<>(items.stream().map(item -> new Prediction(
                    item.requestKey(), "RECOGNIZED", "Carrefour", "FOOD", "EXACT_ALIAS",
                    new BigDecimal("1.0000"), "merchant-recognition-synthetic-v1", "1.0.0",
                    "merchant-recognition-features-v1", true, true, false,
                    null, null, List.of(), null, null, null, List.of())).toList());
            if (invalidSecondPrediction && predictions.size() > 1) {
                Prediction original = predictions.get(1);
                predictions.set(1, new Prediction(
                        original.requestKey(), original.status(), original.canonicalMerchant(),
                        "INCOME_SALARY", original.matchMethod(), original.matchScore(),
                        original.modelId(), original.modelVersion(), original.featureVersion(),
                        original.experimental(), original.syntheticTrainingData(), original.guaranteed(),
                        original.fallbackReason(), original.rationale(), original.evidenceUrls(),
                        original.providerId(), original.catalogVersion(), original.taxonomyVersion(),
                        original.candidateCategoryCodes()));
            }
            return predictions;
        }

        void reset() {
            requests.clear();
            duringCall = null;
            unavailable = false;
            invalidSecondPrediction = false;
            webEnriched = false;
            enrichmentAbstained = false;
            modelAbstainedWithCandidates = false;
            transactionActiveDuringCall = true;
        }
    }
}

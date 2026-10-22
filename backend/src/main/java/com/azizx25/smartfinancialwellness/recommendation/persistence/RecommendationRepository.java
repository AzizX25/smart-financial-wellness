package com.azizx25.smartfinancialwellness.recommendation.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.azizx25.smartfinancialwellness.recommendation.RecommendationException;
import com.azizx25.smartfinancialwellness.recommendation.RecommendationRules;
import com.azizx25.smartfinancialwellness.recommendation.RecommendationRules.Proposal;
import com.azizx25.smartfinancialwellness.recommendation.api.RecommendationDtos;
import com.azizx25.smartfinancialwellness.recommendation.api.RecommendationDtos.Fact;
import com.azizx25.smartfinancialwellness.recommendation.api.RecommendationDtos.RecommendationResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RecommendationRepository {
    private static final String S = "smart_financial_wellness.";
    private static final String SELECT = "SELECT r.*, e.currency, e.period_start, e.period_end, "
            + "d.dimension_type,d.raw_value,d.unit,d.score,j.factual_message FROM " + S
            + "personalized_recommendation r JOIN " + S + "wellness_evaluation e ON e.id=r.wellness_evaluation_id "
            + "JOIN " + S + "recommendation_justification j ON j.recommendation_id=r.id JOIN " + S
            + "evaluation_dimension d ON d.id=j.evaluation_dimension_id WHERE r.user_id=? "
            + "AND r.generation_method='RULES' AND r.rules_version=? ";
    private static final String ORDER = " ORDER BY CASE r.priority WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END, "
            + "r.created_at DESC,r.recommendation_type,r.id";
    private final JdbcTemplate jdbc;

    public RecommendationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void lockOwner(UUID userId) {
        if (jdbc.queryForList("SELECT id FROM " + S + "app_user WHERE id=? FOR UPDATE",
                UUID.class, userId).isEmpty()) throw RecommendationException.notFound();
    }

    public boolean create(UUID userId, UUID evaluationId, Proposal proposal, Instant now) {
        boolean exists = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + S
                + "personalized_recommendation WHERE wellness_evaluation_id=? AND recommendation_type=? "
                + "AND rules_version=? AND generation_method='RULES')", Boolean.class,
                evaluationId, proposal.type(), RecommendationRules.VERSION));
        if (exists) return false;
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + S + "personalized_recommendation "
                + "(id,user_id,wellness_evaluation_id,recommendation_type,title,message,informational_disclaimer,"
                + "priority,status,proposed_action_type,generation_method,rules_version,created_at,updated_at) "
                + "VALUES (?,?,?,?,?,?,?,?,'DRAFT','NAVIGATE','RULES',?,?,?)", id, userId, evaluationId,
                proposal.type(), proposal.title(), proposal.message(), RecommendationDtos.DISCLAIMER,
                proposal.priority(), RecommendationRules.VERSION, Timestamp.from(now), Timestamp.from(now));
        int inserted = jdbc.update("INSERT INTO " + S + "recommendation_justification "
                + "(recommendation_id,evaluation_dimension_id,justification_type,factual_message) "
                + "SELECT ?,id,'SCORE_FACTOR',explanation FROM " + S
                + "evaluation_dimension WHERE wellness_evaluation_id=? AND dimension_type=? AND available",
                id, evaluationId, proposal.factorCode());
        if (inserted != 1) throw new IllegalStateException("Available recommendation factor is required");
        jdbc.update("UPDATE " + S + "personalized_recommendation SET status='ACTIVE' WHERE id=?", id);
        return true;
    }

    public void obsoletePrevious(UUID userId, UUID evaluationId, String currency) {
        jdbc.update("UPDATE " + S + "personalized_recommendation r SET status='OBSOLETE' FROM " + S
                + "wellness_evaluation e WHERE e.id=r.wellness_evaluation_id AND r.user_id=? "
                + "AND e.currency=? AND r.wellness_evaluation_id<>? AND r.status='ACTIVE' "
                + "AND r.generation_method='RULES' AND r.rules_version=?",
                userId, currency, evaluationId, RecommendationRules.VERSION);
    }

    public List<RecommendationResponse> forEvaluation(UUID userId, UUID evaluationId) {
        return jdbc.query(SELECT + "AND r.wellness_evaluation_id=?" + ORDER,
                (rs, row) -> response(rs), userId, RecommendationRules.VERSION, evaluationId);
    }

    public List<RecommendationResponse> list(UUID userId, String currency, int page, int size) {
        return jdbc.query(SELECT + "AND e.currency=? AND r.status<>'OBSOLETE'" + ORDER + " LIMIT ? OFFSET ?",
                (rs, row) -> response(rs), userId, RecommendationRules.VERSION, currency, size, (long) page * size);
    }

    public long count(UUID userId, String currency) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + S + "personalized_recommendation r JOIN " + S
                + "wellness_evaluation e ON e.id=r.wellness_evaluation_id WHERE r.user_id=? AND e.currency=? "
                + "AND r.generation_method='RULES' AND r.rules_version=? AND r.status<>'OBSOLETE'",
                Long.class, userId, currency, RecommendationRules.VERSION);
    }

    public RecommendationResponse owned(UUID userId, UUID id) {
        return jdbc.query(SELECT + "AND r.id=?", (rs, row) -> response(rs),
                userId, RecommendationRules.VERSION, id).stream().findFirst()
                .orElseThrow(RecommendationException::notFound);
    }

    public void feedback(UUID id, String status, Instant now) {
        jdbc.update("UPDATE " + S + "personalized_recommendation SET status=?,closed_at=? WHERE id=?",
                status, Timestamp.from(now), id);
    }

    private RecommendationResponse response(ResultSet rs) throws SQLException {
        String type = rs.getString("recommendation_type");
        String actionPath = "GOAL_PACE".equals(type) ? "/objectifs" : "/planification-salaire";
        String actionLabel = "GOAL_PACE".equals(type) ? "Voir mes objectifs" : "Voir mon budget";
        return new RecommendationResponse(rs.getObject("id", UUID.class),
                rs.getObject("wellness_evaluation_id", UUID.class), type, rs.getString("priority"),
                rs.getString("status"), rs.getString("title"), rs.getString("message"),
                rs.getString("currency").trim(), rs.getDate("period_start").toLocalDate(),
                rs.getDate("period_end").toLocalDate(), rs.getString("rules_version"),
                new Fact(rs.getString("dimension_type"), rs.getBigDecimal("raw_value"),
                        rs.getString("unit"), rs.getBigDecimal("score"), rs.getString("factual_message")),
                actionPath, actionLabel, rs.getString("informational_disclaimer"),
                rs.getTimestamp("created_at").toInstant());
    }
}

package com.azizx25.smartfinancialwellness.score;

import com.azizx25.smartfinancialwellness.common.web.ApiException;
import org.springframework.http.HttpStatus;

public final class FinancialScoreException extends ApiException {
    private FinancialScoreException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public static FinancialScoreException notFound() {
        return new FinancialScoreException(
                HttpStatus.NOT_FOUND, "FINANCIAL_SCORE_NOT_FOUND", "Financial score not found");
    }

    public static FinancialScoreException invalid(String message) {
        return new FinancialScoreException(
                HttpStatus.BAD_REQUEST, "INVALID_FINANCIAL_SCORE_REQUEST", message);
    }
}

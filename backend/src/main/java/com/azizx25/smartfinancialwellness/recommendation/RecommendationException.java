package com.azizx25.smartfinancialwellness.recommendation;

import com.azizx25.smartfinancialwellness.common.web.ApiException;
import org.springframework.http.HttpStatus;

public final class RecommendationException extends ApiException {
    private RecommendationException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public static RecommendationException notFound() {
        return new RecommendationException(HttpStatus.NOT_FOUND,
                "RECOMMENDATION_NOT_FOUND", "Recommendation not found");
    }

    public static RecommendationException closed() {
        return new RecommendationException(HttpStatus.CONFLICT,
                "RECOMMENDATION_CLOSED", "This recommendation is no longer active");
    }
}

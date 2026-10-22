package com.azizx25.smartfinancialwellness.forecast;

import com.azizx25.smartfinancialwellness.common.web.ApiException;
import org.springframework.http.HttpStatus;

public final class ForecastException extends ApiException {
    private ForecastException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public static ForecastException notFound() {
        return new ForecastException(HttpStatus.NOT_FOUND, "FORECAST_NOT_FOUND", "Forecast not found");
    }

    public static ForecastException invalid(String message) {
        return new ForecastException(HttpStatus.BAD_REQUEST, "INVALID_FORECAST_REQUEST", message);
    }
}

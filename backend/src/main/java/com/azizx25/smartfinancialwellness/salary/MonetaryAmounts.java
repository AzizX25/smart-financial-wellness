package com.azizx25.smartfinancialwellness.salary;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;

public final class MonetaryAmounts {

    public static final int SCALE = 4;

    private static final Pattern CURRENCY_PATTERN = Pattern.compile("[A-Z]{3}");

    private MonetaryAmounts() {
    }

    public static BigDecimal requireNonNegativeInput(BigDecimal value, String field) {
        Objects.requireNonNull(value, field + " is required");
        if (value.scale() > SCALE) {
            throw new IllegalArgumentException(field + " must have at most four fractional digits");
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
        return value.setScale(SCALE);
    }

    public static BigDecimal calculated(BigDecimal value) {
        return Objects.requireNonNull(value).setScale(SCALE);
    }

    public static String requireCurrency(String currency) {
        if (currency == null || !CURRENCY_PATTERN.matcher(currency).matches()) {
            throw new IllegalArgumentException("currency must be an uppercase ISO 4217 code");
        }
        return currency;
    }
}

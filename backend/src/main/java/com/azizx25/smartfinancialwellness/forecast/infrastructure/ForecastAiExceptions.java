package com.azizx25.smartfinancialwellness.forecast.infrastructure;

public final class ForecastAiExceptions {
    private ForecastAiExceptions() {
    }

    public static final class Unavailable extends RuntimeException {
        public Unavailable(Throwable cause) { super("AI forecast service unavailable", cause); }
    }

    public static final class InvalidResponse extends RuntimeException {
        public InvalidResponse(String message) { super(message); }
        public InvalidResponse(Throwable cause) { super("Invalid AI forecast response", cause); }
    }
}

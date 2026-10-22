package com.azizx25.smartfinancialwellness.category.infrastructure;

public final class MerchantAiExceptions {
    private MerchantAiExceptions() { }

    public static class Unavailable extends RuntimeException {
        public Unavailable(Throwable cause) { super(cause); }
    }

    public static class InvalidResponse extends RuntimeException {
        public InvalidResponse(Throwable cause) { super(cause); }
        public InvalidResponse(String message) { super(message); }
    }
}

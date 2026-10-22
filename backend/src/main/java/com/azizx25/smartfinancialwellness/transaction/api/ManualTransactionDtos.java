package com.azizx25.smartfinancialwellness.transaction.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import com.azizx25.smartfinancialwellness.bank.persistence.TransactionRecord.Direction;
import jakarta.validation.constraints.*;

public final class ManualTransactionDtos {
    private ManualTransactionDtos() { }

    public record CreateRequest(
            @NotNull UUID accountId,
            @NotNull UUID clientIdempotencyKey,
            @NotNull LocalDate bookingDate,
            @NotBlank @Size(max = 1000) String description,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 15, fraction = 4) BigDecimal amount,
            @NotNull Direction direction,
            UUID categoryId) { }

    public record UpdateRequest(
            @NotNull @Min(0) Integer version,
            @NotNull LocalDate bookingDate,
            @NotBlank @Size(max = 1000) String description,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 15, fraction = 4) BigDecimal amount,
            @NotNull Direction direction,
            UUID categoryId) { }

    public record Response(UUID id, UUID accountId, String sourceType, LocalDate bookingDate,
            String description, String amount, String direction, String currency,
            UUID categoryId, int version) { }
}

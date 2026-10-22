package com.azizx25.smartfinancialwellness.assistance;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

import com.azizx25.smartfinancialwellness.common.web.ApiException;
import com.azizx25.smartfinancialwellness.recommendation.RecommendationService;
import com.azizx25.smartfinancialwellness.user.persistence.InterfacePreferenceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssistanceService {
    private final AssistanceClient client;
    private final RecommendationService recommendations;
    private final InterfacePreferenceRepository preferences;
    private final Clock clock;

    public AssistanceService(AssistanceClient client, RecommendationService recommendations,
            InterfacePreferenceRepository preferences, Clock clock) {
        this.client = client;
        this.recommendations = recommendations;
        this.preferences = preferences;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Explanation explain(UUID userId, UUID recommendationId) {
        var item = recommendations.get(userId, recommendationId);
        var result = client.explain(item.type(), item.fact().code());
        boolean explained = "EXPLAINED".equals(result.status());
        return new Explanation(explained ? "LOCAL_LLM" : "RULES",
                explained ? result.text() : item.message(), item.fact().code(), item.rulesVersion(),
                explained ? null : result.status());
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ReceiptPreview receipt(UUID userId, byte[] bytes) {
        validateImage(bytes);
        ZoneId zone = preferences.findById(userId).map(p -> ZoneId.of(p.getTimeZone()))
                .orElse(ZoneId.of("Africa/Tunis"));
        LocalDate today = LocalDate.now(clock.withZone(zone));
        var result = client.receipt(bytes);
        if (!List.of("EXTRACTED", "PARTIAL").contains(result.status())) {
            return new ReceiptPreview(result.status(), null, null, null, null, today);
        }
        try {
            String reviewedDate = result.date();
            if (result.merchant() != null && (result.merchant().isBlank() || result.merchant().length() > 160
                    || result.merchant().chars().anyMatch(c -> c < 32))) throw new IllegalArgumentException();
            if (result.date() != null) {
                LocalDate date = LocalDate.parse(result.date());
                // Do not guess or swap an out-of-range date. Preserve the other validated fields
                // for human review; export still requires a valid, non-future date.
                if (date.isAfter(today) || date.getYear() < 1900) reviewedDate = null;
            }
            if (result.amount() != null && (!result.amount().matches("\\d{1,15}(?:\\.\\d{1,4})?")
                    || new BigDecimal(result.amount()).signum() <= 0)) throw new IllegalArgumentException();
            if (result.currency() != null) {
                if (!result.currency().matches("[A-Z]{3}")) throw new IllegalArgumentException();
                Currency.getInstance(result.currency());
            }
            boolean partial = result.merchant() == null || result.date() == null
                    || result.amount() == null || result.currency() == null;
            if (partial != "PARTIAL".equals(result.status())) throw new IllegalArgumentException();
            return new ReceiptPreview(partial || reviewedDate == null ? "PARTIAL" : "EXTRACTED",
                    result.merchant(), reviewedDate, result.amount(), result.currency(), today);
        } catch (RuntimeException invalid) {
            return new ReceiptPreview("INVALID_RESPONSE", null, null, null, null, today);
        }
    }

    public static void validateImage(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > 2 * 1024 * 1024) throw invalidImage();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalidImage();
            var reader = readers.next();
            try {
                String format = reader.getFormatName();
                if (!"PNG".equalsIgnoreCase(format) && !"JPEG".equalsIgnoreCase(format)) throw invalidImage();
                reader.setInput(input);
                if ((long) reader.getWidth(0) * reader.getHeight(0) > 4_000_000 || reader.read(0) == null) throw invalidImage();
            } finally { reader.dispose(); }
        } catch (IOException invalid) { throw invalidImage(); }
        catch (RuntimeException invalid) {
            if (invalid instanceof ApiException safe) throw safe;
            throw invalidImage();
        }
    }

    private static ApiException invalidImage() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RECEIPT_IMAGE",
                "Use one valid JPEG or PNG receipt up to 2 MiB and 4 million pixels");
    }

    public record Explanation(String source, String text, String factCode, String rulesVersion, String fallbackReason) { }
    public record ReceiptPreview(String status, String merchant, String date, String amount,
            String currency, LocalDate asOfDate) { }
}

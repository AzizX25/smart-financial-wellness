package com.azizx25.smartfinancialwellness.assistance;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import javax.imageio.ImageIO;

import com.azizx25.smartfinancialwellness.common.web.ApiException;
import com.azizx25.smartfinancialwellness.recommendation.RecommendationService;
import com.azizx25.smartfinancialwellness.user.persistence.InterfacePreferenceRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssistanceServiceTests {
    @Test
    void validatesPixelsAndRejectsMalformedImagesAndUnsafeExtractedFields() throws Exception {
        byte[] image = image(20, 20);
        AssistanceService.validateImage(image);
        assertThatThrownBy(() -> AssistanceService.validateImage(new byte[]{1, 2, 3})).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> AssistanceService.validateImage(image(2001, 2000))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> AssistanceService.validateImage(new byte[2 * 1024 * 1024 + 1])).isInstanceOf(ApiException.class);
        var client = mock(AssistanceClient.class);
        var preferences = mock(InterfacePreferenceRepository.class);
        when(preferences.findById(any())).thenReturn(Optional.empty());
        var service = new AssistanceService(client, mock(RecommendationService.class), preferences,
                Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"), ZoneOffset.UTC));
        when(client.receipt(any())).thenReturn(new AssistanceClient.ReceiptResult(AssistanceClient.VERSION,
                "EXTRACTED", "Synthetic café", "2026-10-01", "12.3456", "TND"));
        assertThat(service.receipt(UUID.randomUUID(), image).amount()).isEqualTo("12.3456");
        for (String amount : new String[]{"-1", "0", "1.12345", "NaN"}) {
            when(client.receipt(any())).thenReturn(new AssistanceClient.ReceiptResult(AssistanceClient.VERSION,
                    "EXTRACTED", "Synthetic café", "2026-10-01", amount, "TND"));
            assertThat(service.receipt(UUID.randomUUID(), image).status()).isEqualTo("INVALID_RESPONSE");
        }
        when(client.receipt(any())).thenReturn(new AssistanceClient.ReceiptResult(AssistanceClient.VERSION,
                "EXTRACTED", "Synthetic café", "2026-10-12", "12", "TND"));
        assertThat(service.receipt(UUID.randomUUID(), image).status()).isEqualTo("PARTIAL");
        assertThat(service.receipt(UUID.randomUUID(), image).date()).isNull();
        when(client.receipt(any())).thenReturn(new AssistanceClient.ReceiptResult(AssistanceClient.VERSION,
                "PARTIAL", "Synthetic café", null, "12", null));
        assertThat(service.receipt(UUID.randomUUID(), image).currency()).isNull();
    }

    @Test
    void reversedFrenchDateIsDiscardedWithoutGuessingOrLosingValidReceiptFields() throws Exception {
        var client = mock(AssistanceClient.class);
        var preferences = mock(InterfacePreferenceRepository.class);
        when(preferences.findById(any())).thenReturn(Optional.empty());
        var service = new AssistanceService(client, mock(RecommendationService.class), preferences,
                Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC));
        for (String date : new String[]{"2026-11-09", "1899-09-11"}) {
            when(client.receipt(any())).thenReturn(new AssistanceClient.ReceiptResult(AssistanceClient.VERSION,
                    "EXTRACTED", "Lefties", date, "309.700", "TND"));
            var result = service.receipt(UUID.randomUUID(), image(20, 20));
            assertThat(result.status()).isEqualTo("PARTIAL");
            assertThat(result.date()).isNull();
            assertThat(result.merchant()).isEqualTo("Lefties");
            assertThat(result.amount()).isEqualTo("309.700");
            assertThat(result.currency()).isEqualTo("TND");
            assertThat(result.asOfDate()).isEqualTo(java.time.LocalDate.of(2026, 10, 14));
        }
        when(client.receipt(any())).thenReturn(new AssistanceClient.ReceiptResult(AssistanceClient.VERSION,
                "EXTRACTED", "Lefties", "2026-09-11", "309.700", "TND"));
        assertThat(service.receipt(UUID.randomUUID(), image(20, 20)).date()).isEqualTo("2026-09-11");
    }

    @Test
    void partialDateRecoveryDoesNotAcceptMalformedFieldsOrIncoherentStatuses() throws Exception {
        var client = mock(AssistanceClient.class);
        var preferences = mock(InterfacePreferenceRepository.class);
        when(preferences.findById(any())).thenReturn(Optional.empty());
        var service = new AssistanceService(client, mock(RecommendationService.class), preferences,
                Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC));
        for (var response : java.util.List.of(
                new AssistanceClient.ReceiptResult(AssistanceClient.VERSION, "EXTRACTED", "Lefties", "2026-11-09", "1.12345", "TND"),
                new AssistanceClient.ReceiptResult(AssistanceClient.VERSION, "EXTRACTED", "Lefties", "2026-11-09", "309.700", "XXXINVALID"),
                new AssistanceClient.ReceiptResult(AssistanceClient.VERSION, "EXTRACTED", "Lefties", "2026-02-30", "309.700", "TND"),
                new AssistanceClient.ReceiptResult(AssistanceClient.VERSION, "EXTRACTED", "Lefties", null, "309.700", "TND"))) {
            when(client.receipt(any())).thenReturn(response);
            var result = service.receipt(UUID.randomUUID(), image(20, 20));
            assertThat(result.status()).isEqualTo("INVALID_RESPONSE");
            assertThat(result.amount()).isNull();
        }
    }

    private byte[] image(int width, int height) throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return bytes.toByteArray();
    }
}

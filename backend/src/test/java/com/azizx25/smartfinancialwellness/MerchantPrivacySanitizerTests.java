package com.azizx25.smartfinancialwellness;

import com.azizx25.smartfinancialwellness.category.MerchantPrivacySanitizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MerchantPrivacySanitizerTests {

    private final MerchantPrivacySanitizer sanitizer = new MerchantPrivacySanitizer();

    @Test
    void prefersMerchantEvidenceAndNormalizesIt() {
        assertThat(sanitizer.sanitize("  Carrefour La Marsa  ", "account 123456789"))
                .isEqualTo("carrefour la marsa");
    }

    @Test
    void removesTunisianAndInternationalSensitiveFormats() {
        String raw = "Paiement contact@example.test +216 22 333 444 "
                + "TN59 1000 6035 1835 9847 8831 reference ABC123456789 19/08/2026";

        String sanitized = sanitizer.sanitize(null, raw);

        assertThat(sanitized).isEqualTo("paiement");
        assertThat(sanitized).doesNotContain("example", "216", "123456789", "2026", "tn59");
    }

    @Test
    void removesLongCardAndAccountNumbersAndCollapsesPunctuation() {
        assertThat(sanitizer.sanitize(null, "Netflix -- carte 4111 1111 1111 1111 !!!"))
                .isEqualTo("netflix carte");
    }
}

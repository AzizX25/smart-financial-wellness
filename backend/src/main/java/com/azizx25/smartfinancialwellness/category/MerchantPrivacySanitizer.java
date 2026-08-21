package com.azizx25.smartfinancialwellness.category;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class MerchantPrivacySanitizer {

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}");
    private static final Pattern IBAN = Pattern.compile("(?i)\\b[A-Z]{2}\\d{2}(?:[ -]?[A-Z0-9]){10,30}\\b");
    private static final Pattern PHONE = Pattern.compile("(?<!\\w)(?:\\+?216[ .-]?)?(?:\\d[ .-]?){8}(?!\\w)");
    private static final Pattern DATE = Pattern.compile("\\b(?:\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}|\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4})\\b");
    private static final Pattern LONG_REFERENCE = Pattern.compile("(?i)\\b(?=[A-Z0-9-]{8,}\\b)(?=[A-Z0-9-]*\\d{5,})[A-Z0-9-]+\\b");
    private static final Pattern LONG_NUMBER = Pattern.compile("(?<!\\d)(?:\\d[ -]?){6,}(?!\\d)");

    public String sanitize(String recognizedMerchant, String normalizedDescription) {
        String source = recognizedMerchant == null || recognizedMerchant.isBlank()
                ? normalizedDescription : recognizedMerchant;
        if (source == null) return "";
        String value = Normalizer.normalize(source, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        value = EMAIL.matcher(value).replaceAll(" ");
        value = IBAN.matcher(value).replaceAll(" ");
        value = PHONE.matcher(value).replaceAll(" ");
        value = DATE.matcher(value).replaceAll(" ");
        value = LONG_REFERENCE.matcher(value).replaceAll(" ");
        value = LONG_NUMBER.matcher(value).replaceAll(" ");
        return value.replaceAll("[^a-z0-9&]+", " ").trim().replaceAll("\\s+", " ");
    }
}

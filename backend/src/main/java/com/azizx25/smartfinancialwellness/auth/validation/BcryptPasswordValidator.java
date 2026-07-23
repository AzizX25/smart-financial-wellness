package com.azizx25.smartfinancialwellness.auth.validation;

import java.nio.charset.StandardCharsets;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class BcryptPasswordValidator implements ConstraintValidator<BcryptPassword, String> {

    private int minBytes;
    private int maxBytes;

    @Override
    public void initialize(BcryptPassword annotation) {
        minBytes = annotation.minBytes();
        maxBytes = annotation.maxBytes();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return false;
        }
        int utf8Length = value.getBytes(StandardCharsets.UTF_8).length;
        return utf8Length >= minBytes && utf8Length <= maxBytes;
    }
}

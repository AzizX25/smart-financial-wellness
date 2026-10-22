package com.azizx25.smartfinancialwellness.auth.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

@Documented
@Constraint(validatedBy = BcryptPasswordValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface BcryptPassword {

    String message() default "password length is outside the allowed UTF-8 byte range";

    int minBytes() default 12;

    int maxBytes() default 72;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

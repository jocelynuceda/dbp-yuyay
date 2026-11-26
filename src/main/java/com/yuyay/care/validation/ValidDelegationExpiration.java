package com.yuyay.care.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import lombok.RequiredArgsConstructor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidDelegationExpiration.Validator.class)
public @interface ValidDelegationExpiration {
    String message() default "El vencimiento no puede superar los 365 días";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    @RequiredArgsConstructor
    class Validator implements ConstraintValidator<ValidDelegationExpiration, Instant> {
        private static final Duration MAX_DURATION = Duration.ofDays(365);
        private final Clock clock;

        @Override
        public boolean isValid(Instant validUntil, ConstraintValidatorContext context) {
            if (validUntil == null) {
                return true;
            }
            Instant now = clock.instant();
            if (!validUntil.isAfter(now)) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate("El vencimiento de la delegación debe estar en el futuro")
                        .addConstraintViolation();
                return false;
            }

            return !validUntil.isAfter(now.plus(MAX_DURATION));
        }
    }
}

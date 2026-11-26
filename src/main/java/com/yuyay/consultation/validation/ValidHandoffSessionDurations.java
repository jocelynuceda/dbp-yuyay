package com.yuyay.consultation.validation;

import com.yuyay.consultation.dto.CreateHandoffSessionDTO;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidHandoffSessionDurations.Validator.class)
public @interface ValidHandoffSessionDurations {
    String message() default "Indica exactamente una vigencia del enlace, en horas o en minutos";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ValidHandoffSessionDurations, CreateHandoffSessionDTO> {
        @Override
        public boolean isValid(CreateHandoffSessionDTO request, ConstraintValidatorContext context) {
            if ((request.expiresInHours() == null) == (request.expiresInMinutes() == null)) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                        .addPropertyNode("expiresInMinutes")
                        .addConstraintViolation();
                return false;
            }
            if (request.sessionWindowMinutes() != null
                    && request.sessionWindowMinutes() > request.linkDurationMinutes()) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate("La ventana de lectura no puede superar la vigencia del enlace")
                        .addPropertyNode("sessionWindowMinutes")
                        .addConstraintViolation();
                return false;
            }
            return true;
        }
    }
}

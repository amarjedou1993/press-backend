package com.presscard.press_accreditation.validation;

import com.presscard.press_accreditation.config.AppProperties;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.*;
import java.util.regex.Pattern;

/**
 * Password policy validation against app.security.password-regex.
 * Default policy: minimum 8 characters, at least one letter and one digit —
 * length over composition (NIST guidance); if HAPA mandates stricter rules,
 * it's one YAML line, zero code.
 *
 * APPLIES TO: registration and account-creation DTOs ONLY.
 * NEVER apply to LoginRequest — existing passwords predating a policy
 * change must always be able to log in.
 */
@Documented
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidPassword.PasswordValidator.class)
public @interface ValidPassword {

    String message() default "Le mot de passe doit contenir au moins 8 caractères, dont une lettre et un chiffre";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class PasswordValidator implements ConstraintValidator<ValidPassword, String> {

        private final Pattern pattern;

        public PasswordValidator(AppProperties props) {
            this.pattern = Pattern.compile(props.security().passwordRegex());
        }

        @Override
        public boolean isValid(String value, ConstraintValidatorContext ctx) {
            if (value == null || value.isBlank()) {
                return true; // presence is @NotBlank's job
            }
            return pattern.matcher(value).matches();
        }
    }
}

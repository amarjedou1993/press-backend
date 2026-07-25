package com.presscard.press_accreditation.validation;

import com.presscard.press_accreditation.config.AppProperties;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.*;
import java.util.regex.Pattern;

/**
 * Mauritanian national identity number.
 *
 * Null/blank passes: the profile requires NNI *or* passport, and that
 * either/or rule belongs to the controller, not to a field validator.
 *
 * The pattern is configuration (app.identity.nni-regex), like the phone
 * pattern — so a format change is a deployment setting, not a code change.
 */
@Documented
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidNni.NniValidator.class)
public @interface ValidNni {

    String message() default "Numéro national d'identité invalide (10 chiffres attendus).";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class NniValidator implements ConstraintValidator<ValidNni, String> {

        private final Pattern pattern;

        public NniValidator(AppProperties props) {
            this.pattern = Pattern.compile(props.identity().nniRegex());
        }

        @Override
        public boolean isValid(String value, ConstraintValidatorContext ctx) {
            if (value == null || value.isBlank()) {
                return true;   // the either/or rule is enforced elsewhere
            }
            return pattern.matcher(value.trim()).matches();
        }
    }
}

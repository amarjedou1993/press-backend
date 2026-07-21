package com.presscard.press_accreditation.validation;

import com.presscard.press_accreditation.config.AppProperties;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.*;
import java.util.regex.Pattern;

/**
 * Phone number validation against the configurable national pattern
 * (app.identity.phone-regex). Null/blank passes — compose with @NotBlank
 * where the field is mandatory (candidate registration) and leave it off
 * where optional (staff accounts).
 *
 * Why an annotation instead of @Pattern: @Pattern needs a compile-time
 * constant; our pattern is configuration, changeable per deployment
 * without touching code — same design as @ValidNni.
 */
@Documented
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidPhone.PhoneValidator.class)
public @interface ValidPhone {

    String message() default "Numéro de téléphone invalide (format attendu : 8 chiffres, ex. 22123456, préfixe +222 optionnel)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class PhoneValidator implements ConstraintValidator<ValidPhone, String> {

        private final Pattern pattern;

        // Spring injects config into ConstraintValidator beans.
        public PhoneValidator(AppProperties props) {
            this.pattern = Pattern.compile(props.identity().phoneRegex());
        }

        @Override
        public boolean isValid(String value, ConstraintValidatorContext ctx) {
            if (value == null || value.isBlank()) {
                return true; // mandatory-ness is @NotBlank's job
            }
            return pattern.matcher(value.trim()).matches();
        }
    }
}

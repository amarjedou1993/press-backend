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
 * ───────────────────────────────────────────────────────────────────────
 * TWO CHECKS, AND THEY ANSWER DIFFERENT QUESTIONS.
 *
 * The PATTERN asks whether this is the right shape — ten digits. It is
 * configuration (app.identity.nni-regex), so a format change is a deployment
 * setting rather than a code change.
 *
 * The CHECKSUM asks whether these particular ten digits form a real number.
 * A transposed pair — 1234567890 typed as 1234657890 — passes the pattern and
 * fails this.
 *
 * ⚠️ THE SECOND ONE WAS MISSING UNTIL NOW, while CandidateProfile's comment
 * said it was there. Any ten digits were accepted, which is why a wrong NNI
 * went through — and why a card could be signed over a number nobody holds.
 * Correcting that after printing means revoking.
 * ───────────────────────────────────────────────────────────────────────
 *
 * ⚠️ AND THE CHECKSUM IS SWITCHABLE, deliberately.
 *
 * app.identity.nni-checksum decides whether it runs. The modulo-97 rule is
 * what this project has recorded, and if it turns out to be wrong, it would
 * reject legitimate candidates AT THE DOOR — before they can apply at all.
 * That failure must be reversible without a deployment, for the same reason
 * the pattern is.
 *
 * VERIFY IT AGAINST REAL NUMBERS BEFORE THE FIRST SESSION OPENS. A handful of
 * NNIs from actual cards is enough, and it is the only way to know.
 */
@Documented
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidNni.NniValidator.class)
public @interface ValidNni {

    /**
     * ⚠️ A KEY, not a sentence.
     *
     * MethodArgumentNotValidException puts this into the `errors` map, and
     * FieldError on the client resolves it against the reader's catalogue —
     * passing through unchanged anything it does not recognise. A French
     * sentence here would appear in French under an Arabic label, silently.
     *
     * The validator replaces it per case: shape and checksum are different
     * failures, and telling someone "ten digits expected" when they typed ten
     * digits helps nobody.
     */
    String message() default "validation.nniInvalid";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class NniValidator implements ConstraintValidator<ValidNni, String> {

        private final Pattern pattern;
        private final boolean checksumEnabled;

        public NniValidator(AppProperties props) {
            this.pattern = Pattern.compile(props.identity().nniRegex());
            this.checksumEnabled = props.identity().nniChecksum();
        }

        @Override
        public boolean isValid(String value, ConstraintValidatorContext ctx) {
            if (value == null || value.isBlank()) {
                return true;   // the either/or rule is enforced elsewhere
            }

            // Spaces tolerated on the way in: people group digits when they
            // read a number off a card, and refusing that teaches nothing.
            String canonical = value.replaceAll("\\s", "");

            if (!pattern.matcher(canonical).matches()) {
                return fail(ctx, "validation.nniLength");
            }

            if (checksumEnabled && !checksumHolds(canonical)) {
                // ⚠️ A DIFFERENT MESSAGE, and the distinction matters. Someone
                // who typed ten digits and is told "ten digits expected" will
                // count them, find ten, and conclude the system is broken.
                return fail(ctx, "validation.nniChecksum");
            }

            return true;
        }

        /**
         * The modulo-97 check.
         *
         * ⚠️ Long, not int: ten digits reach 9,999,999,999 and overflow an int
         * at 2,147,483,647 — which would not throw, it would wrap, and a
         * wrapped value would pass or fail at random.
         */
        private static boolean checksumHolds(String canonical) {
            try {
                long n = Long.parseLong(canonical);
                return (n - 1) % 97 == 0;
            } catch (NumberFormatException e) {
                // Unreachable: the pattern already established these are
                // digits. Kept so a loosened pattern cannot turn a validation
                // failure into a 500.
                return false;
            }
        }

        private static boolean fail(ConstraintValidatorContext ctx, String key) {
            ctx.disableDefaultConstraintViolation();
            ctx.buildConstraintViolationWithTemplate(key).addConstraintViolation();
            return false;
        }
    }
}

package com.presscard.press_accreditation.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @NotNull Jwt jwt,
        @NotNull Storage storage,
        @NotNull Identity identity,
        @NotNull Card card,
        @NotNull Application application,
        @NotNull Session session,
        @NotNull Email email,
        @NotNull Locale locale,
        @NotNull Admin admin,
        @NotNull Security security,
        @NotNull Cors cors,
        @NotNull Revalidation revalidation,
        @NotNull Review review
) {

    public record Jwt(
            String issuer,
            Resource privateKeyLocation,
            Resource publicKeyLocation,
            Duration accessTokenTtl
    ) {}

    public record Storage(
            String rootDirectory,
            long maxFileSizeBytes,
            List<String> allowedMimeTypes
    ) {}

    /**
     * Identity formats — patterns are deployment configuration, not code.
     */
    public record Identity(
            String nniRegex,
            String phoneRegex,

            /**
             * Whether the NNI's modulo-97 checksum is enforced.
             *
             * ⚠️ SWITCHABLE, AND THE REASON MATTERS.
             *
             * The pattern asks whether a number has the right shape; the
             * checksum asks whether these particular ten digits form a real
             * one. Only the second catches a transposed pair — and a card
             * signed over a number nobody holds can only be corrected by
             * revoking it.
             *
             * But the rule is what this project has recorded rather than what
             * anyone has verified against real cards. If it is wrong, it
             * refuses legitimate candidates AT THE DOOR, before they can
             * apply at all — and that failure has to be reversible without a
             * deployment, exactly as a wrong pattern would be.
             *
             * VERIFY AGAINST REAL NUMBERS BEFORE THE FIRST SESSION OPENS.
             */
            boolean nniChecksum
    ) {}

    /**
     * Card issuance.
     *
     * The signing key is SEPARATE from app.jwt on purpose: a JWT key should
     * rotate, and a card signature must stay verifiable for the card's whole
     * life. Rotating one must never break the other.
     */
    public record Card(
            int validityDays,
            /**
             * The series letter, as in "A - 0001 / 26".
             *
             * Configurable because its meaning is still open with HAPA: it may
             * denote a series per year, or per category.
             *
             * ⚠️ THE HONOUR SERIES IS NOT HERE. "B" is fixed in
             * HonourCardService, because it is not a setting — it is what
             * distinguishes a card the commission never saw, and an
             * installation that could rename it could make the two
             * indistinguishable.
             */
            String numberSeries,
            Resource signingPrivateKeyLocation,
            Resource signingPublicKeyLocation,
            String signingKeyId,
            String verificationBaseUrl,
            String contactLine
    ) {}

    public record Application(String numberPrefix, int maxCorrectionRounds) {}

    public record Session(String correctionDeadlineCron, int minimumGapDays) {}

    public record Email(boolean enabled, String from, String commissionInbox) {}

    public record Locale(String defaultLanguage, List<String> supportedLanguages) {}

    /** Bootstrap credentials for the very first SUPER_ADMIN. */
    public record Admin(String email, String initialPassword) {}

    /** Brute-force limits + password policy (both validators read here). */
    public record Security(int authRequestsPerMinute, String passwordRegex) {}

    /** CORS is configuration, not code: prod origin is an env var. */
    public record Cors(List<String> allowedOrigins) {}

    /**
     * On-demand purge of the frontend's cached public pages. Optional:
     * disabled in tests and wherever no frontend is reachable.
     */
    public record Revalidation(boolean enabled, String url, String token) {}

    /**
     * Commission settings. claimExpiryDays bounds how long one reviewer may
     * hold a dossier: a claim is a lock, and every lock needs a way out.
     */
    public record Review(int claimExpiryDays) {}
}
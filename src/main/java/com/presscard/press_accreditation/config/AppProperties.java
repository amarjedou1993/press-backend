package com.presscard.press_accreditation.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Single source of truth for all app.* configuration.
 * @Validated + @NotNull: a missing YAML block fails the BOOT with the
 * property's name — never a NullPointerException at runtime.
 */
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
        @NotNull Cors cors
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

    /** Identity formats — patterns are deployment configuration, not code. */
    public record Identity(String nniRegex, String phoneRegex) {}

    public record Card(Duration validity, String numberPrefix) {}

    public record Application(String numberPrefix, int maxCorrectionRounds) {}

    public record Session(String correctionDeadlineCron) {}

    public record Email(boolean enabled, String from, String commissionInbox) {}

    public record Locale(String defaultLanguage, List<String> supportedLanguages) {}

    /** Bootstrap credentials for the very first SUPER_ADMIN. */
    public record Admin(String email, String initialPassword) {}

    /** Brute-force limits + password policy (both validators read here). */
    public record Security(int authRequestsPerMinute, String passwordRegex) {}

    /** CORS is configuration, not code: prod origin is an env var. */
    public record Cors(List<String> allowedOrigins) {}
}

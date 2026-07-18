package com.presscard.press_accreditation.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Single source of truth for all app.* configuration.
 *
 * Best practices applied:
 * - Records: immutable, constructor-bound, no setters to mutate at runtime.
 * - Types, not Strings: Duration ("24h", "365d") and Resource (classpath:/file:)
 *   are parsed by Spring at startup — a bad value fails the boot, loudly.
 * - One nested record per domain, mirroring the YAML structure exactly.
 *
 * Requires @ConfigurationPropertiesScan on the main application class.
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
        @NotNull Admin admin
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

    public record Identity(String nniRegex) {}

    public record Card(Duration validity, String numberPrefix) {}

    public record Application(String numberPrefix, int maxCorrectionRounds) {}

    public record Session(String correctionDeadlineCron) {}

    public record Email(boolean enabled, String from, String commissionInbox) {}

    public record Locale(String defaultLanguage, List<String> supportedLanguages) {}

    /**
     * Bootstrap credentials for the very first SUPER_ADMIN.
     * Dev: harmless defaults in application-dev.yaml.
     * Prod: injected via environment variables — never committed.
     */
    public record Admin(String email, String initialPassword) {}
}

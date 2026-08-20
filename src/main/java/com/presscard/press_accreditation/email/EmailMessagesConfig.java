package com.presscard.press_accreditation.email;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.nio.charset.StandardCharsets;

/**
 * The e-mail bundle, declared rather than auto-configured.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ WHY THIS EXISTS AT ALL.
 *
 * `spring.messages.basename` configures the AUTO-CONFIGURED MessageSource —
 * and Spring Boot only creates that bean when no MessageSource already exists
 * in the context. Declare one anywhere, for validation messages or anything
 * else, and the property is silently ignored: every lookup then goes to that
 * bean's basename, and every e-mail key comes back "No message found".
 *
 * Silently is the problem. Nothing warns you; the setting simply stops
 * applying.
 *
 * So the e-mail bundle is its own bean with its own name, injected by
 * qualifier. It cannot be displaced by anything else the application
 * declares, now or later.
 * ───────────────────────────────────────────────────────────────────────
 */
@Configuration
public class EmailMessagesConfig {

    @Bean("emailMessageSource")
    public MessageSource emailMessageSource() {
        var source = new ReloadableResourceBundleMessageSource();

        // ⚠️ "classpath:" is required. Without the prefix this resolves
        // against the filesystem relative to the working directory, which
        // works when you run from the project root and fails in the jar.
        source.setBasename("classpath:messages/email");

        // ⚠️ Arabic is not ISO-8859-1. Java's ResourceBundle default is, and
        // without this every Arabic message arrives as mojibake — which is
        // worse than an error, because it sends.
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());

        // A missing key throws instead of echoing the key back. On an
        // official notice, "VERIFY_EMAIL.body" appearing in the body would be
        // worse than a failed send: the failure at least shows in the logs.
        source.setUseCodeAsDefaultMessage(false);

        // ⚠️ NO FALLBACK TO THE SYSTEM LOCALE. Left on, a missing Arabic key
        // would quietly serve the French one — and a French paragraph inside
        // an Arabic notice is exactly what this whole exercise exists to
        // prevent. Better a loud failure than a quiet mixture.
        source.setFallbackToSystemLocale(false);

        // Reload on every lookup in development; cached in production.
        // A wording change should appear on the next send, not an hour later.
        source.setCacheSeconds(-1);

        return source;
    }
}

package com.presscard.press_accreditation.email;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a queued row into a subject and two bodies.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ THIS IS WHERE ISO DATES BECOME READABLE ONES.
 *
 * EmailService deliberately queues "2026-03-15" rather than "15 mars 2026",
 * because at queue time nobody knows which language the row will be rendered
 * in. Here we do — it is on the row — so the formatting happens now.
 *
 * ⚠️ AND WHERE CODES BECOME LABELS. `status`, `reasonCode` and `groundCode`
 * travel as constants and resolve against the same bundle as everything else.
 * ───────────────────────────────────────────────────────────────────────
 */
@Service
public class EmailRenderer {

    /** Keys whose value is an ISO date to be formatted in the row's locale. */
    private static final java.util.Set<String> DATE_KEYS =
            java.util.Set.of("deadline", "expiresAt", "issuedAt");

    /** Keys whose value is an enum constant to be resolved against the bundle. */
    private static final java.util.Set<String> CODE_KEYS =
            java.util.Set.of("status", "reasonCode", "groundCode");

    private final MessageSource messages;

    public EmailRenderer(@Qualifier("emailMessageSource") MessageSource messages) {
        this.messages = messages;
    }

    public String subject(EmailTemplate template, String locale, Map<String, Object> payload) {
        return substitute(message(template.subjectKey(), locale), prepare(payload, locale), false);
    }

    /** The plain-text alternative. */
    public String text(EmailTemplate template, String locale, Map<String, Object> payload) {
        Map<String, String> values = prepare(payload, locale);
        StringBuilder out = new StringBuilder(substitute(message(template.bodyKey(), locale), values, false));

        if (template.hasAction() && values.containsKey("link")) {
            out.append("\n\n")
               .append(message(template.actionKey(), locale))
               .append(" : ")
               .append(values.get("link"));
        }
        out.append("\n\n--\n")
           .append(message("common.signature", locale))
           .append("\n")
           .append(message("common.languageNote", locale));
        return out.toString();
    }

    /**
     * The HTML part, which is what almost everyone will see.
     *
     * ⚠️ Built by hand rather than by Thymeleaf. An e-mail body needs its
     * styles INLINE — mail clients strip <style> blocks and ignore external
     * sheets — so a template engine buys nothing here but indirection.
     */
    public String html(EmailTemplate template, String locale, Map<String, Object> payload) {
        boolean rtl = "ar".equals(locale);
        Map<String, String> values = prepare(payload, locale);

        String dir = rtl ? "rtl" : "ltr";
        String align = rtl ? "right" : "left";

        // ⚠️ Cairo does not exist on the recipient's machine and @font-face is
        // ignored by most clients. Arabic falls back to a system stack that
        // every platform actually has.
        String font = rtl
                ? "'Segoe UI', Tahoma, 'Traditional Arabic', 'Geeza Pro', sans-serif"
                : "'Segoe UI', Roboto, Helvetica, Arial, sans-serif";

        StringBuilder out = new StringBuilder(2048);
        out.append("<!DOCTYPE html><html dir=\"").append(dir)
           .append("\" lang=\"").append(locale).append("\">")
           .append("<head><meta charset=\"UTF-8\">")
           .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
           .append("</head>")
           .append("<body style=\"margin:0;padding:0;background:#f4f5f3;\">")
           .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
           .append("style=\"background:#f4f5f3;padding:24px 12px;\"><tr><td align=\"center\">")
           .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
           .append("style=\"max-width:560px;background:#ffffff;border-radius:12px;overflow:hidden;\">");

        /* ── the national rule, as on every screen ── */
        out.append("<tr><td style=\"height:6px;line-height:6px;font-size:0;\">")
           .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>")
           .append("<td width=\"33%\" style=\"background:#00a95c;height:6px;\"></td>")
           .append("<td width=\"34%\" style=\"background:#ffd700;height:6px;\"></td>")
           .append("<td width=\"33%\" style=\"background:#d01c1f;height:6px;\"></td>")
           .append("</tr></table></td></tr>");

        /* ── the authority ── */
        out.append("<tr><td dir=\"").append(dir)
           .append("\" style=\"padding:24px 28px 8px;text-align:").append(align)
           .append(";font-family:").append(font)
           .append(";font-size:11px;font-weight:bold;letter-spacing:1px;")
           .append("text-transform:uppercase;color:#0b3524;\">")
           .append(esc(message("common.ministry", locale)))
           .append("</td></tr>");

        /* ── the body, paragraph by paragraph ──
             ⚠️ EACH BLOCK carries its own dir and text-align. Mail clients do
             not reliably inherit either from <html>, and half of them ignore
             dir on nested elements — so it goes on every one. */
        String body = substitute(message(template.bodyKey(), locale), values, true);
        for (String paragraph : body.split("\\n\\s*\\n")) {
            if (paragraph.isBlank()) continue;
            out.append("<tr><td dir=\"").append(dir)
               .append("\" style=\"padding:6px 28px;text-align:").append(align)
               .append(";font-family:").append(font)
               .append(";font-size:15px;line-height:1.7;color:#1a1a1a;\">")
               .append(paragraph.trim().replace("\n", "<br>"))
               .append("</td></tr>");
        }

        /* ── the action ── */
        if (template.hasAction() && values.containsKey("link")) {
            out.append("<tr><td dir=\"").append(dir)
               .append("\" style=\"padding:20px 28px 8px;text-align:").append(align).append(";\">")
               .append("<a href=\"").append(esc(values.get("link")))
               .append("\" style=\"display:inline-block;background:#0b3524;color:#ffffff;")
               .append("text-decoration:none;padding:12px 26px;border-radius:8px;")
               .append("font-family:").append(font)
               .append(";font-size:14px;font-weight:bold;\">")
               .append(esc(message(template.actionKey(), locale)))
               .append("</a></td></tr>");

            /* ⚠️ THE URL IN FULL, AND IN dir="ltr".
                 Not everyone can click a button — some clients strip them, and
                 some people forward the message. And a Latin URL inside an
                 Arabic paragraph REORDERS: dir="ltr" on the span is what keeps
                 the token attached to the end of the address. */
            out.append("<tr><td dir=\"ltr\" style=\"padding:0 28px 16px;text-align:left;")
               .append("font-family:monospace;font-size:11px;color:#6b7280;word-break:break-all;\">")
               .append(esc(values.get("link")))
               .append("</td></tr>");
        }

        /* ── signature and the language note ── */
        out.append("<tr><td dir=\"").append(dir)
           .append("\" style=\"padding:18px 28px 24px;border-top:1px solid #e5e7eb;text-align:")
           .append(align).append(";font-family:").append(font)
           .append(";font-size:12px;line-height:1.6;color:#6b7280;\">")
           .append(esc(message("common.signature", locale)).replace("\n", "<br>"))
           .append("<br><br>")
           .append(esc(message("common.languageNote", locale)))
           .append("</td></tr>");

        out.append("</table></td></tr></table></body></html>");
        return out.toString();
    }

    /* ══ internals ══ */

    /**
     * ⚠️ args = null, and that is load-bearing.
     *
     * Spring applies MessageFormat only when arguments are supplied. With
     * null it returns the raw string — which matters because MessageFormat
     * treats a single quote as an escape character, and FRENCH IS FULL OF
     * APOSTROPHES. "l'autorité" would come back as "lautorité", and
     * "n'a pas" as "na pas", silently, in every message.
     */
    private String message(String key, String locale) {
        return messages.getMessage(key, null, Locale.forLanguageTag(locale));
    }

    /** Format dates, resolve codes, stringify the rest. */
    private Map<String, String> prepare(Map<String, Object> payload, String locale) {
        Map<String, String> out = new java.util.HashMap<>();
        Locale loc = Locale.forLanguageTag(locale);

        for (var entry : payload.entrySet()) {
            String key = entry.getKey();
            Object raw = entry.getValue();

            if (raw == null) {
                // A reinstatement carries no reason; an empty string reads
                // better than the word "null" in an official notice.
                out.put(key, "");
                continue;
            }

            if (DATE_KEYS.contains(key)) {
                out.put(key, formatDate(String.valueOf(raw), loc));
            } else if (CODE_KEYS.contains(key)) {
                out.put(key, resolveCode(String.valueOf(raw), locale));
            } else {
                out.put(key, String.valueOf(raw));
            }
        }
        return out;
    }

    /**
     * ⚠️ Locale.forLanguageTag("ar"), never "ar-MR".
     *
     * The country tag pulls in eastern digits ٢٠٢٦ on some JDKs. The printed
     * card uses Western digits, and a date a holder reads against their card
     * must match it.
     */
    private String formatDate(String iso, Locale locale) {
        try {
            return LocalDate.parse(iso)
                    .format(DateTimeFormatter.ofPattern("d MMMM yyyy", locale));
        } catch (DateTimeParseException e) {
            // Already formatted, or not a date at all. Better an odd-looking
            // value than a message that fails to send.
            return iso;
        }
    }

    /** "SUSPENDED" → "suspendue" / "موقوفة". */
    private String resolveCode(String code, String locale) {
        try {
            return message("code." + code, locale);
        } catch (Exception e) {
            return code;
        }
    }

    private String substitute(String template, Map<String, String> values, boolean escape) {
        String result = template;
        for (var entry : values.entrySet()) {
            String value = escape ? esc(entry.getValue()) : entry.getValue();
            result = result.replace("{" + entry.getKey() + "}", value);
        }
        return result;
    }

    /**
     * ⚠️ Free text goes through here before it reaches HTML.
     *
     * A commission member's justification is typed by a person, and it lands
     * in a message body. Without escaping, a stray angle bracket breaks the
     * layout — and a deliberate one is an injection into somebody's inbox.
     */
    private static String esc(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value);
    }
}

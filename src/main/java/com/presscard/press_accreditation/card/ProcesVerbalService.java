package com.presscard.press_accreditation.card;

import com.presscard.press_accreditation.error.CardNotIssuableException;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * The procès-verbal — a signed record of who was accredited, and when.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ WORD, NOT PDF, AND THAT IS THE POINT.
 *
 * A procès-verbal is amended before it is signed: a reference number, a
 * preamble the commission dictated, a member absent that day. A document
 * nobody can amend is a document that gets retyped — and the retyped version
 * is the one that gets signed, while this one is filed unread.
 *
 * PDF is one click away in Word. The reverse is not.
 *
 * ⚠️ WHO SIGNS DEPENDS ON THE SERIES, AND IT IS NOT DECORATION.
 *
 * The commission decides a candidature: it examined the dossiers, and its
 * members sign. It never saw an honour card or an institutional filing — the
 * Ministry grants those directly. A PV of honour cards carrying a commission
 * signature block would be a false document, and the signature line is where
 * that falsehood would live.
 *
 * ⚠️ AND THE PERIOD IS PRINTED IN THE HEADING.
 *
 * A PV that does not say what it covers is a list. Anyone holding this one
 * can run the same query over the same range and get the same names — which
 * is the property that makes it evidence rather than a printout.
 * ───────────────────────────────────────────────────────────────────────
 */
@Service
public class ProcesVerbalService {

    private static final Logger log = LoggerFactory.getLogger("PV_AUDIT");

    private static final DateTimeFormatter DATE_FR =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH);
    private static final DateTimeFormatter SHORT_FR =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRENCH);

    private static final String GREEN = "0B2E1F";
    private static final String SLATE = "4A5A52";

    /** Which series a procès-verbal covers, and who signs it. */
    public enum Kind {
        /**
         * ⚠️ THE ONLY ONE THE COMMISSION SIGNS.
         *
         * It examined these dossiers and decided them. Its signature is the
         * record of that act.
         */
        CANDIDACY("Procès-verbal des cartes de presse délivrées",
                  "Cartes de presse professionnelles", true),

        /**
         * Granted by the Ministry without examination — the holder is a figure
         * the Ministry chose to honour, and no commission sat.
         */
        HONOUR("Procès-verbal des cartes d'honneur octroyées",
               "Cartes d'honneur", false),

        /**
         * Filed by an institution, granted by the Ministry. The body vouching
         * for its staff is itself a press authority; no commission examines.
         */
        INSTITUTIONAL("Procès-verbal des cartes institutionnelles octroyées",
                      "Cartes institutionnelles", false);

        final String title;
        final String shortLabel;
        /** Whether the commission signs — see the class javadoc. */
        final boolean commissionSigns;

        Kind(String title, String shortLabel, boolean commissionSigns) {
            this.title = title;
            this.shortLabel = shortLabel;
            this.commissionSigns = commissionSigns;
        }
    }

    /**
     * What the document covers, in words.
     *
     * @param scope       "Session du 12 mars 2026" or "du 1er janvier au 30 juin 2026"
     * @param commissioners names of the members who sat — empty for a Ministry grant
     */
    public record Context(String scope, List<String> commissioners) {}

    /**
     * Build the document.
     *
     * ⚠️ AN EMPTY LIST IS REFUSED.
     *
     * A procès-verbal recording nothing is a document that says a session
     * produced no cards — which may be true, and is not something to discover
     * from a blank table after signing. If the range is genuinely empty, the
     * screen says so and no file is produced.
     */
    @Transactional(readOnly = true)
    public byte[] build(Kind kind, Context context, List<CardRegistryRow> rows) {
        if (rows.isEmpty()) {
            throw new CardNotIssuableException(
                    "Aucune carte sur la période retenue : il n'y a pas de procès-verbal à établir.");
        }

        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            margins(doc);

            /* ── the State's own heading ── */
            centred(doc, "RÉPUBLIQUE ISLAMIQUE DE MAURITANIE", 10, true, GREEN, 40);
            centred(doc, "Honneur — Fraternité — Justice", 9, false, SLATE, 40);
            centred(doc, "Ministère de la Culture, des Arts, de la Communication", 9, false, SLATE, 20);
            centred(doc, "et des Relations avec le Parlement", 9, false, SLATE, 300);

            centred(doc, kind.title.toUpperCase(Locale.FRENCH), 15, true, GREEN, 60);

            /*
             * ⚠️ THE SCOPE, IMMEDIATELY UNDER THE TITLE.
             *
             * "Session du 12 mars 2026" or "du 1er janvier au 30 juin 2026".
             * Without it the reader cannot tell what was excluded, and the
             * document cannot be checked against the register.
             */
            centred(doc, context.scope(), 11, false, SLATE, 60);
            centred(doc, rows.size() + (rows.size() > 1 ? " titulaires" : " titulaire"),
                    10, true, GREEN, 320);

            /* ── the preamble ── */
            body(doc, kind.commissionSigns
                    ? "La commission d'examen des demandes de carte de presse professionnelle, "
                      + "réunie au titre de la session visée ci-dessus, a examiné les dossiers "
                      + "qui lui ont été soumis. Les personnes dont les noms suivent ont été "
                      + "reconnues journalistes professionnels, et les cartes de presse "
                      + "correspondantes leur ont été délivrées."
                    : "Les personnes dont les noms suivent se sont vu octroyer par le Ministère "
                      + "les cartes désignées ci-après, dans les conditions prévues par la "
                      + "réglementation en vigueur.", 200);

            table(doc, rows, kind);

            paragraph(doc, "", 200);

            /*
             * ⚠️ THE GENERATION DATE, IN THE DOCUMENT AND NOT ONLY IN THE FILE
             * NAME.
             *
             * A PV regenerated a year later from the same range produces the
             * same names and a different date. Both facts belong on the page:
             * one says what it covers, the other says when it was drawn.
             */
            right(doc, "Fait à Nouakchott, le " + LocalDate.now().format(DATE_FR), 10, 400);

            signatures(doc, kind, context);

            doc.write(out);
            log.info("PV_BUILT kind={} scope={} rows={}", kind, context.scope(), rows.size());
            return out.toByteArray();

        } catch (IOException e) {
            log.error("PV build failed kind={}", kind, e);
            throw new IllegalStateException("Le procès-verbal n'a pas pu être établi.", e);
        }
    }

    /* ══ the table ══ */

    private void table(XWPFDocument doc, List<CardRegistryRow> rows, Kind kind) {
        /*
         * ⚠️ THE INSTITUTION COLUMN ONLY WHERE IT MEANS SOMETHING.
         *
         * An honour card records no outlet — the distinction is personal. A
         * column of dashes would suggest the information was expected and
         * missing, rather than inapplicable.
         */
        boolean showInstitution = kind != Kind.HONOUR;

        String[] headers = showInstitution
                ? new String[] { "N°", "Nom et prénom", "NNI / Passeport", "Catégorie",
                                 kind == Kind.INSTITUTIONAL ? "Institution" : "Organe de presse",
                                 "N° de carte", "Expire le" }
                : new String[] { "N°", "Nom et prénom", "NNI / Passeport", "Catégorie",
                                 "N° de carte", "Expire le" };

        XWPFTable table = doc.createTable(rows.size() + 1, headers.length);
        table.setWidth("100%");

        XWPFTableRow head = table.getRow(0);
        for (int i = 0; i < headers.length; i++) {
            shade(head.getCell(i), "F2F5F3");
            cellText(head.getCell(i), headers[i], 9, true, GREEN);
        }

        int n = 1;
        for (CardRegistryRow row : rows) {
            // ⚠️ withoutContact(): the omission is an explicit act. A column
            // added to the record later cannot reach a signed document by
            // accident.
            CardRegistryRow r = row.withoutContact();
            XWPFTableRow tr = table.getRow(n);

            int c = 0;
            cellText(tr.getCell(c++), String.valueOf(n), 9, false, null);
            cellText(tr.getCell(c++), r.fullName(), 9, true, null);
            cellText(tr.getCell(c++), r.identityNumber(), 9, false, null);
            cellText(tr.getCell(c++), r.categoryLabelFr(), 9, false, null);
            if (showInstitution) {
                cellText(tr.getCell(c++), blank(r.institution()), 9, false, null);
            }
            cellText(tr.getCell(c++), r.cardNumber(), 9, true, null);
            cellText(tr.getCell(c), r.expiresAt() == null ? "—"
                    : r.expiresAt().format(SHORT_FR), 9, false, null);
            n++;
        }
    }

    /* ══ the signatures ══ */

    private void signatures(XWPFDocument doc, Kind kind, Context context) {
        if (kind.commissionSigns) {
            /*
             * ⚠️ EACH MEMBER SIGNS SEPARATELY.
             *
             * A single line for "la commission" would let one signature stand
             * for a body that decides collectively — and the objection right
             * rests on a decision having been taken by named people.
             */
            paragraph(doc, "Les membres de la commission", 11, true, GREEN, 160);

            List<String> members = context.commissioners();
            if (members == null || members.isEmpty()) {
                // ⚠️ Blank lines rather than nothing: the PV is signed on paper,
                // and a commission whose members were not recorded still signs.
                for (int i = 0; i < 3; i++) {
                    paragraph(doc, "………………………………………………………      ………………………………………", 10, false, SLATE, 240);
                }
            } else {
                for (String member : members) {
                    paragraph(doc, member + "      ………………………………………………………", 10, false, null, 240);
                }
            }
            paragraph(doc, "", 300);
        }

        right(doc, "Pour le Ministère", 11, 60);
        right(doc, "<Fonction du signataire>", 10, 400);
        right(doc, "Nom, cachet et signature", 9, 0);
    }

    /* ══ formatting helpers ══ */

    private static void margins(XWPFDocument doc) {
        CTSectPr sect = doc.getDocument().getBody().addNewSectPr();
        CTPageMar mar = sect.addNewPgMar();
        mar.setTop(BigInteger.valueOf(1250));
        mar.setBottom(BigInteger.valueOf(1250));
        mar.setLeft(BigInteger.valueOf(1150));
        mar.setRight(BigInteger.valueOf(1150));
    }

    private static void centred(XWPFDocument doc, String text, int points,
                                boolean bold, String colour, int after) {
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(ParagraphAlignment.CENTER);
        p.setSpacingAfter(after);
        run(p, text, points, bold, colour);
    }

    private static void right(XWPFDocument doc, String text, int points, int after) {
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(ParagraphAlignment.RIGHT);
        p.setSpacingAfter(after);
        run(p, text, points, false, null);
    }

    private static void body(XWPFDocument doc, String text, int after) {
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(ParagraphAlignment.BOTH);
        p.setSpacingAfter(after);
        run(p, text, 11, false, null);
    }

    private static void paragraph(XWPFDocument doc, String text, int after) {
        paragraph(doc, text, 11, false, null, after);
    }

    private static void paragraph(XWPFDocument doc, String text, int points,
                                  boolean bold, String colour, int after) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingAfter(after);
        run(p, text, points, bold, colour);
    }

    private static void run(XWPFParagraph p, String text, int points,
                            boolean bold, String colour) {
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setFontFamily("Calibri");
        r.setFontSize(points);
        r.setBold(bold);
        if (colour != null) {
            r.setColor(colour);
        }
    }

    private static void cellText(XWPFTableCell cell, String text, int points,
                                 boolean bold, String colour) {
        XWPFParagraph p = cell.getParagraphs().get(0);
        p.setSpacingAfter(0);
        run(p, text == null ? "—" : text, points, bold, colour);
    }

    private static void shade(XWPFTableCell cell, String hex) {
        cell.getCTTc().addNewTcPr().addNewShd().setFill(hex);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}

//package com.presscard.press_accreditation.card;
//
//import com.openhtmltopdf.bidi.support.ICUBidiReorderer;
//import com.openhtmltopdf.bidi.support.ICUBidiSplitter;
//import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
//import com.presscard.press_accreditation.application.Application;
//import com.presscard.press_accreditation.application.ApplicationRepository;
//import com.presscard.press_accreditation.category.PressCategory;
//import com.presscard.press_accreditation.category.PressCategoryRepository;
//import com.presscard.press_accreditation.config.AppProperties;
//import com.presscard.press_accreditation.profile.CandidateProfile;
//import com.presscard.press_accreditation.profile.CandidateProfileRepository;
//import com.presscard.press_accreditation.storage.PhotoStorageService;
//import com.presscard.press_accreditation.user.User;
//import com.presscard.press_accreditation.user.UserRepository;
//import org.apache.pdfbox.Loader;
//import org.apache.pdfbox.io.RandomAccessReadBuffer;
//import org.apache.pdfbox.multipdf.PDFMergerUtility;
//import org.apache.pdfbox.pdmodel.PDDocument;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.core.io.ClassPathResource;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;
//import org.thymeleaf.TemplateEngine;
//import org.thymeleaf.context.Context;
//
//import java.io.ByteArrayOutputStream;
//import java.io.IOException;
//import java.nio.charset.StandardCharsets;
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.time.format.DateTimeFormatter;
//import java.util.ArrayList;
//import java.util.Base64;
//import java.util.List;
//import java.util.Locale;
//
///**
// * Rendering a card to PDF, laid out from the printed original.
// *
// * SIX THINGS THAT DECIDE WHETHER THE OUTPUT IS PRINTABLE.
// *
// * 1. FONTS ARE REGISTERED IN JAVA, not with @font-face. openhtmltopdf resolves
// *    classpath resources far more reliably that way — and a missing Arabic
// *    font produces EMPTY BOXES with no error, which looks like a design choice
// *    until someone reads it.
// *
// * 2. BIDI IS WIRED EXPLICITLY. This card is ARABIC-PRIMARY: without the ICU
// *    splitter and reorderer its letters render unjoined and in reverse — the
// *    text present, and unreadable to everyone it is written for.
// *
// * 3. EVERY IMAGE IS A DATA URI. A file path would make the card depend on a
// *    working directory, which differs between IntelliJ, the jar, and the
// *    server.
// *
// * 4. THE EMBLEM AND SIGNATURE ARE LOADED ONCE, at startup, not per card. A
// *    batch of two hundred would otherwise re-read and re-encode them two
// *    hundred times.
// *
// * 5. THE PAGE IS THE CARD — 85.6 × 54 mm, zero margin, so the green band
// *    bleeds to the die line rather than sitting in a white frame.
// *
// * 6. A BATCH IS ONE FILE, with the page order chosen for the printer at hand.
// */
//@Service
//public class CardPdfService {
//
//    private static final Logger log = LoggerFactory.getLogger(CardPdfService.class);
//
//    /** The printed card writes dates as yyyy/MM/dd. */
//    private static final DateTimeFormatter EXPIRY_PRINTED =
//            DateTimeFormatter.ofPattern("yyyy/MM/dd");
//
//    private static final String FONT_DIR = "fonts/";
//    private static final String IMAGE_DIR = "images/";
//
//    private final TemplateEngine templateEngine;
//    private final CardRepository cardRepository;
//    private final ApplicationRepository applicationRepository;
//    private final CandidateProfileRepository profileRepository;
//    private final PressCategoryRepository categoryRepository;
//    private final UserRepository userRepository;
//    private final PhotoStorageService photoStorage;
//    private final QrCodeService qrCodeService;
//    private final AppProperties props;
//
//    /** Property 4 — read once, encoded once. */
//    private final String emblemDataUri;
//    private final String signatureDataUri;
//    private final String webDataUri;
//
//    public CardPdfService(TemplateEngine templateEngine,
//                          CardRepository cardRepository,
//                          ApplicationRepository applicationRepository,
//                          CandidateProfileRepository profileRepository,
//                          PressCategoryRepository categoryRepository,
//                          UserRepository userRepository,
//                          PhotoStorageService photoStorage,
//                          QrCodeService qrCodeService,
//                          AppProperties props) {
//        this.templateEngine = templateEngine;
//        this.cardRepository = cardRepository;
//        this.applicationRepository = applicationRepository;
//        this.profileRepository = profileRepository;
//        this.categoryRepository = categoryRepository;
//        this.userRepository = userRepository;
//        this.photoStorage = photoStorage;
//        this.qrCodeService = qrCodeService;
//        this.props = props;
//
//        this.emblemDataUri = loadImage("logo_rim.png", "image/png");
//        this.signatureDataUri = loadImage("minister-signature.svg", "image/svg+xml");
//        this.webDataUri = svgDataUri(webPattern());
//    }
//
//    /** How the pages of a batch are ordered. */
//    public enum PageLayout {
//        /** F,B,F,B — an office duplex printer's natural order. */
//        INTERLEAVED,
//        /** All fronts, then all backs — what most card printers want. */
//        SEQUENTIAL
//    }
//
//    /* ══ one card ══════════════════════════════════════════════ */
//
//    /** Two pages: front, then back. */
//    @Transactional(readOnly = true)
//    public byte[] render(Long cardId) {
//        Card card = cardRepository.findById(cardId)
//                .orElseThrow(() -> new IllegalArgumentException("Carte introuvable: " + cardId));
//
//        String html = templateEngine.process("card", buildContext(card));
//
//        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
//            PdfRendererBuilder builder = new PdfRendererBuilder();
//            builder.useFastMode();
//
//            // Property 2 — an Arabic-primary card without these is unreadable.
//            builder.useUnicodeBidiSplitter(new ICUBidiSplitter.ICUBidiSplitterFactory());
//            builder.useUnicodeBidiReorderer(new ICUBidiReorderer());
//            builder.defaultTextDirection(PdfRendererBuilder.TextDirection.RTL);
//
//            registerFonts(builder);
//
//            builder.withHtmlContent(html, null);
//            builder.toStream(out);
//            builder.run();
//
//            return out.toByteArray();
//
//        } catch (Exception e) {
//            log.error("Card PDF rendering failed for card {}", cardId, e);
//            throw new IllegalStateException(
//                    "La carte n'a pas pu être générée (n° " + card.getCardNumber() + ")", e);
//        }
//    }
//
//    /* ══ a batch ═══════════════════════════════════════════════ */
//
//    /**
//     * Many cards, one file.
//     *
//     * A failure on one card is recorded and skipped rather than aborting: 199
//     * printable cards and a named failure beats nothing at all.
//     */
//    @Transactional(readOnly = true)
//    public byte[] renderBatch(List<Long> cardIds, PageLayout layout) {
//        List<byte[]> rendered = new ArrayList<>();
//
//        for (Long cardId : cardIds) {
//            try {
//                rendered.add(render(cardId));
//            } catch (RuntimeException e) {
//                log.error("CARD_PDF_SKIPPED card={} reason={}", cardId, e.getMessage());
//            }
//        }
//        if (rendered.isEmpty()) {
//            throw new IllegalStateException("Aucune carte n'a pu être générée.");
//        }
//
//        byte[] merged = merge(rendered);
//        return layout == PageLayout.SEQUENTIAL ? separateFaces(merged) : merged;
//    }
//
//    private byte[] merge(List<byte[]> documents) {
//        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
//            PDFMergerUtility merger = new PDFMergerUtility();
//            merger.setDestinationStream(out);
//            for (byte[] document : documents) {
//                merger.addSource(new RandomAccessReadBuffer(document));
//            }
//            merger.mergeDocuments(null);
//            return out.toByteArray();
//        } catch (IOException e) {
//            throw new IllegalStateException("Les cartes n'ont pas pu être assemblées", e);
//        }
//    }
//
//    /**
//     * Reorder F,B,F,B into all-fronts-then-all-backs.
//     *
//     * Card printers usually run one side of a whole batch, then the other. The
//     * two runs MUST stay in the same sequence — split by parity, never sorted,
//     * or every card gets someone else's back.
//     */
//    private byte[] separateFaces(byte[] interleaved) {
//        try (PDDocument source = Loader.loadPDF(interleaved);
//             PDDocument target = new PDDocument();
//             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
//
//            int pageCount = source.getNumberOfPages();
//            for (int i = 0; i < pageCount; i += 2) target.addPage(source.getPage(i));
//            for (int i = 1; i < pageCount; i += 2) target.addPage(source.getPage(i));
//
//            target.save(out);
//            return out.toByteArray();
//
//        } catch (IOException e) {
//            throw new IllegalStateException("Les faces n'ont pas pu être séparées", e);
//        }
//    }
//
//    /* ══ the model ═════════════════════════════════════════════ */
//
//    private Context buildContext(Card card) {
//        Application application = applicationRepository.findById(card.getApplicationId())
//                .orElseThrow();
//        User holder = userRepository.findById(application.getCandidateId()).orElseThrow();
//        CandidateProfile profile = profileRepository.findById(holder.getId()).orElse(null);
//        PressCategory category = categoryRepository.findById(application.getCategoryId())
//                .orElse(null);
//
//        String identity = profile == null ? "—"
//                : (profile.getNni() != null ? profile.getNni() : profile.getPassportNo());
//
//        Context context = new Context(Locale.forLanguageTag("ar"));
//
//        context.setVariable("cardNumber", card.getCardNumber());
//        context.setVariable("holderNameAr", holder.getFullName());
//        context.setVariable("categoryAr", category == null ? "—" : category.getLabelAr());
//
//        // The SNAPSHOT, not the live application: the holder may have changed
//        // outlet since, and the card does not change with them.
//        context.setVariable("specialisationAr",
//                card.getSpecialisationAr() == null ? "—" : card.getSpecialisationAr());
//        context.setVariable("institution",
//                card.getInstitution() == null ? "—" : card.getInstitution());
//
//        context.setVariable("identityNumber", identity == null ? "—" : identity);
//        context.setVariable("expiryPrinted", card.getExpiresAt().format(EXPIRY_PRINTED));
//
//        context.setVariable("photoDataUri", photoDataUri(card));
//        context.setVariable("qrDataUri", qrCodeService.qrDataUri(card.getVerificationToken()));
//        context.setVariable("emblemDataUri", emblemDataUri);
//        context.setVariable("signatureDataUri", signatureDataUri);
//        context.setVariable("webDataUri", webDataUri);
//
//        // The back's notice, from the printed original.
//        context.setVariable("noticeAr",
//                "يرجى من كافة السلطات المدنية والعسكرية تسهيل مهمة حامل هذه البطاقة");
//        context.setVariable("noticeFr",
//                "Les autorités civiles et militaires sont priées<br/>"
//              + "de faciliter la Tâche du titulaire de cette carte");
//
//        return context;
//    }
//
//    private String photoDataUri(Card card) {
//        if (card.getPhotoPath() == null) {
//            return TRANSPARENT_PIXEL;
//        }
//        try {
//            Path path = photoStorage.resolve(card.getPhotoPath());
//            if (!Files.exists(path)) {
//                log.warn("Card {} references a missing photograph: {}",
//                        card.getCardNumber(), card.getPhotoPath());
//                return TRANSPARENT_PIXEL;
//            }
//            String mime = path.toString().toLowerCase(Locale.ROOT).endsWith(".png")
//                    ? "image/png" : "image/jpeg";
//            return "data:" + mime + ";base64,"
//                    + Base64.getEncoder().encodeToString(Files.readAllBytes(path));
//
//        } catch (Exception e) {
//            log.warn("Photograph unreadable for card {}: {}",
//                    card.getCardNumber(), e.getMessage());
//            return TRANSPARENT_PIXEL;
//        }
//    }
//
//    /* ══ assets ════════════════════════════════════════════════ */
//
//    /** Property 4 — read once at startup, not per card. */
//    private String loadImage(String file, String mime) {
//        try {
//            ClassPathResource resource = new ClassPathResource(IMAGE_DIR + file);
//            if (!resource.exists()) {
//                log.error("""
//
//                        ╔══════════════════════════════════════════════════════════╗
//                        ║  MISSING CARD ASSET: {}
//                        ╠══════════════════════════════════════════════════════════╣
//                        ║  Expected at src/main/resources/images/                   ║
//                        ║  The card will print with a gap where it should be.       ║
//                        ╚══════════════════════════════════════════════════════════╝
//                        """, file);
//                return TRANSPARENT_PIXEL;
//            }
//            byte[] bytes = resource.getInputStream().readAllBytes();
//            return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
//
//        } catch (IOException e) {
//            log.error("Card asset {} could not be read: {}", file, e.getMessage());
//            return TRANSPARENT_PIXEL;
//        }
//    }
//
//    /* ══ fonts ═════════════════════════════════════════════════ */
//
//    /**
//     * Property 1 — on an Arabic-primary card the font is not optional.
//     *
//     * Without it EVERY field is an empty box, silently. The error below is the
//     * only signal anyone gets before the cards are printed.
//     */
//    private void registerFonts(PdfRendererBuilder builder) {
//        boolean arabic =
//                addFont(builder, "Louguiya.ttf", "Louguiya", 400)
//              | addFont(builder, "Louguiya-Bold.ttf", "Louguiya", 700);
//
//        addFont(builder, "LouguiyaFR.ttf", "LouguiyaFR", 400);
//        addFont(builder, "LouguiyaFR-Bold.ttf", "LouguiyaFR", 700);
//
//        if (!arabic) {
//            log.error("""
//
//                    ╔══════════════════════════════════════════════════════════╗
//                    ║  NO ARABIC FONT — THE CARD WILL BE EMPTY BOXES           ║
//                    ╠══════════════════════════════════════════════════════════╣
//                    ║  This card is Arabic-primary: the name, category,        ║
//                    ║  specialisation and institution are ALL Arabic.          ║
//                    ║                                                          ║
//                    ║  Place NotoNaskhArabic-Regular.ttf in                    ║
//                    ║  src/main/resources/fonts/ and restart. See FONTS.md.    ║
//                    ╚══════════════════════════════════════════════════════════╝
//                    """);
//        }
//    }
//
//    private boolean addFont(PdfRendererBuilder builder, String file,
//                            String family, int weight) {
//        try {
//            if (!new ClassPathResource(FONT_DIR + file).exists()) {
//                return false;
//            }
//            // A supplier, not a stream: the font may be opened more than once.
//            builder.useFont(() -> {
//                try {
//                    return new ClassPathResource(FONT_DIR + file).getInputStream();
//                } catch (IOException e) {
//                    throw new IllegalStateException("Font unreadable: " + file, e);
//                }
//            }, family, weight, PdfRendererBuilder.FontStyle.NORMAL, true);
//            return true;
//
//        } catch (Exception e) {
//            log.warn("Font {} could not be registered: {}", file, e.getMessage());
//            return false;
//        }
//    }
//
//    /* ══ the security web ══════════════════════════════════════ */
//
//    /**
//     * The radiating web with nodes, as on the printed card.
//     *
//     * Generated rather than shipped as an asset: a vector pattern stays crisp
//     * at any print resolution, and a raster one would band visibly at 300 DPI.
//     */
//    private static String webPattern() {
//        StringBuilder rays = new StringBuilder();
//        StringBuilder arcs = new StringBuilder();
//        StringBuilder nodes = new StringBuilder();
//
//        // Rays from an off-centre origin, as the original does.
//        for (int i = 0; i < 40; i++) {
//            double angle = Math.toRadians(i * 9.0);
//            double x = 500 + Math.cos(angle) * 900;
//            double y = 260 + Math.sin(angle) * 900;
//            rays.append("<line x1=\"500\" y1=\"260\" x2=\"%.0f\" y2=\"%.0f\"/>"
//                    .formatted(x, y));
//        }
//        // Concentric arcs crossing them.
//        for (int r = 60; r <= 900; r += 60) {
//            arcs.append("<circle cx=\"500\" cy=\"260\" r=\"%d\"/>".formatted(r));
//        }
//        // Nodes where a few rays meet an arc — the detail that reads as
//        // engraved rather than drawn.
//        for (int i = 0; i < 40; i += 3) {
//            double angle = Math.toRadians(i * 9.0);
//            for (int r = 120; r <= 780; r += 180) {
//                nodes.append("<circle cx=\"%.0f\" cy=\"%.0f\" r=\"4\" fill=\"#8fa89b\" stroke=\"none\"/>"
//                        .formatted(500 + Math.cos(angle) * r, 260 + Math.sin(angle) * r));
//            }
//        }
//
//        return """
//                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 630">
//                  <g stroke="#8fa89b" stroke-width="1" fill="none" opacity="0.55">
//                    %s%s
//                  </g>
//                  %s
//                </svg>
//                """.formatted(rays, arcs, nodes);
//    }
//
//    private static String svgDataUri(String svg) {
//        return "data:image/svg+xml;base64,"
//                + Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
//    }
//
//    /** Stands in for a missing image, so the layout never collapses. */
//    private static final String TRANSPARENT_PIXEL =
//            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";
//}


package com.presscard.press_accreditation.card;

import com.openhtmltopdf.bidi.support.ICUBidiReorderer;
import com.openhtmltopdf.bidi.support.ICUBidiSplitter;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.presscard.press_accreditation.application.Application;
import com.presscard.press_accreditation.application.ApplicationRepository;
import com.presscard.press_accreditation.category.PressCategory;
import com.presscard.press_accreditation.category.PressCategoryRepository;
import com.presscard.press_accreditation.config.AppProperties;
import com.presscard.press_accreditation.profile.CandidateProfile;
import com.presscard.press_accreditation.profile.CandidateProfileRepository;
import com.presscard.press_accreditation.storage.PhotoStorageService;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Generates the HAPA press card as an ISO/IEC 7810 ID-1 PDF:
 * 85.6 mm x 54 mm, front and back.
 *
 * Fonts are registered in Java because OpenHTMLToPDF resolves classpath TTF
 * resources more reliably this way than through CSS @font-face.
 */
@Service
public class CardPdfService {

    private static final Logger log = LoggerFactory.getLogger(CardPdfService.class);

    private static final DateTimeFormatter EXPIRY_PRINTED =
            DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private static final String FONT_DIR = "fonts/";
    private static final String IMAGE_DIR = "images/";

    /* Output size used for the photograph embedded in the PDF. */
    private static final int PHOTO_WIDTH_PX = 450;
    private static final int PHOTO_HEIGHT_PX = 600;

    private static final String LOGO_FILE = "logo_rim.png";

    /*
     * This is the filename supplied during development. Before production,
     * replace it with the real, authorised signature and preferably rename it
     * minister-signature.svg.
     */
    private static final String SIGNATURE_FILE =
            "minister-signature.png";

    private final TemplateEngine templateEngine;
    private final CardRepository cardRepository;
    private final ApplicationRepository applicationRepository;
    private final CandidateProfileRepository profileRepository;
    private final PressCategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final PhotoStorageService photoStorage;
    private final QrCodeService qrCodeService;
    private final AppProperties props;

    private final String logoDataUri;
    private final String signatureDataUri;
    private final String watermarkDataUri;
    private final String securityPatternDataUri;

    public CardPdfService(
            TemplateEngine templateEngine,
            CardRepository cardRepository,
            ApplicationRepository applicationRepository,
            CandidateProfileRepository profileRepository,
            PressCategoryRepository categoryRepository,
            UserRepository userRepository,
            PhotoStorageService photoStorage,
            QrCodeService qrCodeService,
            AppProperties props
    ) {
        this.templateEngine = templateEngine;
        this.cardRepository = cardRepository;
        this.applicationRepository = applicationRepository;
        this.profileRepository = profileRepository;
        this.categoryRepository = categoryRepository;
        this.userRepository = userRepository;
        this.photoStorage = photoStorage;
        this.qrCodeService = qrCodeService;
        this.props = props;

        this.logoDataUri = loadRequiredImage(LOGO_FILE, "image/png");
        this.signatureDataUri = loadRequiredImage(
                SIGNATURE_FILE,
                "image/svg+xml"
        );
        this.watermarkDataUri = createWatermarkDataUri(LOGO_FILE, 0.075f);
        this.securityPatternDataUri = svgDataUri(securityPatternSvg());
    }

    public enum PageLayout {
        /** Front, back, front, back: suitable for ordinary duplex printing. */
        INTERLEAVED,

        /** All fronts followed by all backs: suitable for many card printers. */
        SEQUENTIAL
    }

    /* ====================================================================== */
    /* One card                                                               */
    /* ====================================================================== */

    /** Returns a two-page PDF: front, then back. */
    @Transactional(readOnly = true)
    public byte[] render(Long cardId) {
        return renderCard(cardId);
    }

    private byte[] renderCard(Long cardId) {
        Card card = cardRepository.findById(cardId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Carte introuvable : " + cardId
                ));

        String html = templateEngine.process("card", buildContext(card));

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();

            /*
             * The document is mixed Arabic/French. Keep the document LTR and
             * explicitly mark only Arabic elements RTL in CSS.
             */
            builder.useUnicodeBidiSplitter(
                    new ICUBidiSplitter.ICUBidiSplitterFactory()
            );
            builder.useUnicodeBidiReorderer(new ICUBidiReorderer());
            builder.defaultTextDirection(PdfRendererBuilder.TextDirection.LTR);

            registerFonts(builder);

            /* Every image is a data URI, so no external base URI is required. */
            builder.withHtmlContent(html, null);
            builder.toStream(output);
            builder.run();

            return output.toByteArray();

        } catch (Exception exception) {
            log.error("Card PDF rendering failed for card {}", cardId, exception);
            throw new IllegalStateException(
                    "La carte n'a pas pu être générée (n° "
                            + card.getCardNumber() + ")",
                    exception
            );
        }
    }

    /* ====================================================================== */
    /* Batch                                                                  */
    /* ====================================================================== */

    @Transactional(readOnly = true)
    public byte[] renderBatch(List<Long> cardIds, PageLayout layout) {
        if (cardIds == null || cardIds.isEmpty()) {
            throw new IllegalArgumentException("Aucune carte sélectionnée.");
        }

        PageLayout effectiveLayout = layout == null
                ? PageLayout.INTERLEAVED
                : layout;

        List<byte[]> renderedCards = new ArrayList<>();

        for (Long cardId : cardIds) {
            try {
                renderedCards.add(renderCard(cardId));
            } catch (RuntimeException exception) {
                log.error(
                        "CARD_PDF_SKIPPED card={} reason={}",
                        cardId,
                        exception.getMessage(),
                        exception
                );
            }
        }

        if (renderedCards.isEmpty()) {
            throw new IllegalStateException("Aucune carte n'a pu être générée.");
        }

        byte[] merged = merge(renderedCards);

        return effectiveLayout == PageLayout.SEQUENTIAL
                ? separateFaces(merged)
                : merged;
    }

    private byte[] merge(List<byte[]> documents) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDFMergerUtility merger = new PDFMergerUtility();
            merger.setDestinationStream(output);

            for (byte[] document : documents) {
                merger.addSource(new RandomAccessReadBuffer(document));
            }

            merger.mergeDocuments(null);
            return output.toByteArray();

        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Les cartes n'ont pas pu être assemblées.",
                    exception
            );
        }
    }

    /** Reorders F,B,F,B into F,F,B,B while preserving card order. */
    private byte[] separateFaces(byte[] interleavedPdf) {
        try (PDDocument source = Loader.loadPDF(interleavedPdf);
             PDDocument target = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            int pageCount = source.getNumberOfPages();

            for (int page = 0; page < pageCount; page += 2) {
                target.importPage(source.getPage(page));
            }

            for (int page = 1; page < pageCount; page += 2) {
                target.importPage(source.getPage(page));
            }

            target.save(output);
            return output.toByteArray();

        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Les faces des cartes n'ont pas pu être séparées.",
                    exception
            );
        }
    }

    /* ====================================================================== */
    /* Thymeleaf model                                                        */
    /* ====================================================================== */

    private Context buildContext(Card card) {
        Application application = applicationRepository
                .findById(card.getApplicationId())
                .orElseThrow(() -> new IllegalStateException(
                        "Candidature introuvable pour la carte "
                                + card.getCardNumber()
                ));

        User holder = userRepository
                .findById(application.getCandidateId())
                .orElseThrow(() -> new IllegalStateException(
                        "Titulaire introuvable pour la carte "
                                + card.getCardNumber()
                ));

        CandidateProfile profile = profileRepository
                .findById(holder.getId())
                .orElse(null);

        PressCategory category = categoryRepository
                .findById(application.getCategoryId())
                .orElse(null);

        String identityNumber = identityNumber(profile);

        Context context = new Context(Locale.forLanguageTag("ar"));

        context.setVariable("cardNumber", safe(card.getCardNumber()));

        /*
         * This prints the stored name. If your User entity has a dedicated
         * Arabic-name property, replace holder.getFullName() with that field.
         */
        context.setVariable("holderNameAr", safe(holder.getFullName()));

        context.setVariable(
                "categoryAr",
                category == null ? "—" : safe(category.getLabelAr())
        );

        /* Immutable issuance snapshots, not live application values. */
        context.setVariable(
                "specialisationAr",
                safeOrDash(card.getSpecialisationAr())
        );
        context.setVariable(
                "institution",
                safeOrDash(card.getInstitution())
        );

        context.setVariable("identityNumber", safeOrDash(identityNumber));
        context.setVariable(
                "expiryPrinted",
                card.getExpiresAt().format(EXPIRY_PRINTED)
        );

        context.setVariable("photoDataUri", photoDataUri(card));
        context.setVariable(
                "qrDataUri",
                qrCodeService.qrDataUri(card.getVerificationToken())
        );
        context.setVariable("logoDataUri", logoDataUri);
        context.setVariable("signatureDataUri", signatureDataUri);
        context.setVariable("watermarkDataUri", watermarkDataUri);
        context.setVariable("securityPatternDataUri", securityPatternDataUri);

        context.setVariable(
                "noticeAr",
                "يرجى من كافة السلطات المدنية والعسكرية تسهيل مهمة حامل هذه البطاقة"
        );
        context.setVariable(
                "noticeFr",
                "Les autorités civiles et militaires sont priées<br/>"
                        + "de faciliter la tâche du titulaire de cette carte"
        );

        context.setVariable(
                "contactLine",
                props.card().contactLine() == null
                        ? ""
                        : props.card().contactLine()
        );

        return context;
    }

    private String identityNumber(CandidateProfile profile) {
        if (profile == null) {
            return null;
        }

        if (profile.getNni() != null && !profile.getNni().isBlank()) {
            return profile.getNni();
        }

        return profile.getPassportNo();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String safeOrDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    /* ====================================================================== */
    /* Photograph                                                             */
    /* ====================================================================== */

    /**
     * Produces a card-ready 3:4 portrait. The crop is done in Java because
     * OpenHTMLToPDF does not reliably support CSS object-fit.
     */
    private String photoDataUri(Card card) {
        if (card.getPhotoPath() == null || card.getPhotoPath().isBlank()) {
            return TRANSPARENT_PIXEL;
        }

        try {
            Path path = photoStorage.resolve(card.getPhotoPath());

            if (!Files.exists(path)) {
                log.warn(
                        "Card {} references a missing photograph: {}",
                        card.getCardNumber(),
                        card.getPhotoPath()
                );
                return TRANSPARENT_PIXEL;
            }

            BufferedImage source = ImageIO.read(path.toFile());
            if (source == null) {
                throw new IOException("Unsupported photograph format");
            }

            BufferedImage prepared = cropAndScalePortrait(
                    source,
                    PHOTO_WIDTH_PX,
                    PHOTO_HEIGHT_PX
            );

            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                ImageIO.write(prepared, "png", output);
                return "data:image/png;base64,"
                        + Base64.getEncoder().encodeToString(output.toByteArray());
            }

        } catch (Exception exception) {
            log.warn(
                    "Photograph unreadable for card {}: {}",
                    card.getCardNumber(),
                    exception.getMessage()
            );
            return TRANSPARENT_PIXEL;
        }
    }

    private BufferedImage cropAndScalePortrait(
            BufferedImage source,
            int targetWidth,
            int targetHeight
    ) {
        double targetRatio = (double) targetWidth / targetHeight;
        double sourceRatio = (double) source.getWidth() / source.getHeight();

        int cropX = 0;
        int cropY = 0;
        int cropWidth = source.getWidth();
        int cropHeight = source.getHeight();

        if (sourceRatio > targetRatio) {
            cropWidth = (int) Math.round(source.getHeight() * targetRatio);
            cropX = (source.getWidth() - cropWidth) / 2;
        } else if (sourceRatio < targetRatio) {
            cropHeight = (int) Math.round(source.getWidth() / targetRatio);
            cropY = Math.max(0, (source.getHeight() - cropHeight) / 3);
        }

        BufferedImage output = new BufferedImage(
                targetWidth,
                targetHeight,
                BufferedImage.TYPE_INT_RGB
        );

        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, targetWidth, targetHeight);

            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC
            );
            graphics.setRenderingHint(
                    RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY
            );
            graphics.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );

            graphics.drawImage(
                    source,
                    0,
                    0,
                    targetWidth,
                    targetHeight,
                    cropX,
                    cropY,
                    cropX + cropWidth,
                    cropY + cropHeight,
                    null
            );
        } finally {
            graphics.dispose();
        }

        return output;
    }

    /* ====================================================================== */
    /* Assets                                                                 */
    /* ====================================================================== */

    private String loadRequiredImage(String filename, String mimeType) {
        ClassPathResource resource = new ClassPathResource(IMAGE_DIR + filename);

        if (!resource.exists()) {
            throw new IllegalStateException(
                    "Missing card image: src/main/resources/"
                            + IMAGE_DIR + filename
            );
        }

        try (InputStream input = resource.getInputStream()) {
            return "data:" + mimeType + ";base64,"
                    + Base64.getEncoder().encodeToString(input.readAllBytes());
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Card image cannot be read: " + filename,
                    exception
            );
        }
    }

    /** Creates a watermark with transparency baked into the PNG itself. */
    private String createWatermarkDataUri(String filename, float opacity) {
        ClassPathResource resource = new ClassPathResource(IMAGE_DIR + filename);

        try (InputStream input = resource.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            BufferedImage source = ImageIO.read(input);
            if (source == null) {
                throw new IOException("Unsupported watermark source image");
            }

            BufferedImage transparent = new BufferedImage(
                    source.getWidth(),
                    source.getHeight(),
                    BufferedImage.TYPE_INT_ARGB
            );

            Graphics2D graphics = transparent.createGraphics();
            try {
                graphics.setComposite(
                        AlphaComposite.getInstance(
                                AlphaComposite.SRC_OVER,
                                opacity
                        )
                );
                graphics.drawImage(source, 0, 0, null);
            } finally {
                graphics.dispose();
            }

            ImageIO.write(transparent, "png", output);

            return "data:image/png;base64,"
                    + Base64.getEncoder().encodeToString(output.toByteArray());

        } catch (IOException exception) {
            throw new IllegalStateException(
                    "The card watermark could not be generated.",
                    exception
            );
        }
    }

    /* ====================================================================== */
    /* Fonts                                                                  */
    /* ====================================================================== */

    private void registerFonts(PdfRendererBuilder builder) {
        String arabicRegular = firstExistingFont(
                "Louguiya.ttf",
                "Louguiya-Regular.ttf",
                "louguiya.ttf",
                "NotoNaskhArabic-Regular.ttf"
        );

        if (arabicRegular == null) {
            throw new IllegalStateException(
                    "No Arabic font found in src/main/resources/fonts/. "
                            + "Expected Louguiya.ttf or NotoNaskhArabic-Regular.ttf."
            );
        }

        String arabicBold = firstExistingFont(
                "Louguiya-Bold.ttf",
                "LouguiyaBold.ttf",
                "louguiya-bold.ttf",
                "NotoNaskhArabic-Bold.ttf"
        );

        String latinRegular = firstExistingFont(
                "LouguiyaFR.ttf",
                "LouguiyaFR-Regular.ttf",
                "Louguiya-Fr.ttf",
                "NotoSans-Regular.ttf"
        );

        if (latinRegular == null) {
            throw new IllegalStateException(
                    "No French/Latin font found in src/main/resources/fonts/. "
                            + "Expected LouguiyaFR.ttf or NotoSans-Regular.ttf."
            );
        }

        String latinBold = firstExistingFont(
                "LouguiyaFR-Bold.ttf",
                "LouguiyaFRBold.ttf",
                "Louguiya-Fr-Bold.ttf",
                "NotoSans-Bold.ttf"
        );

        registerFont(builder, arabicRegular, "Louguiya", 400);
        registerFont(
                builder,
                arabicBold == null ? arabicRegular : arabicBold,
                "Louguiya",
                700
        );

        registerFont(builder, latinRegular, "LouguiyaFR", 400);
        registerFont(
                builder,
                latinBold == null ? latinRegular : latinBold,
                "LouguiyaFR",
                700
        );
    }

    private String firstExistingFont(String... candidates) {
        for (String candidate : candidates) {
            if (new ClassPathResource(FONT_DIR + candidate).exists()) {
                return candidate;
            }
        }
        return null;
    }

    private void registerFont(
            PdfRendererBuilder builder,
            String filename,
            String family,
            int weight
    ) {
        builder.useFont(
                () -> {
                    try {
                        return new ClassPathResource(
                                FONT_DIR + filename
                        ).getInputStream();
                    } catch (IOException exception) {
                        throw new IllegalStateException(
                                "Font unreadable: " + filename,
                                exception
                        );
                    }
                },
                family,
                weight,
                PdfRendererBuilder.FontStyle.NORMAL,
                true
        );
    }

    /* ====================================================================== */
    /* Security background                                                    */
    /* ====================================================================== */

    /**
     * A light geometric security pattern inspired by the adopted card. It is
     * intentionally subtle so it does not interfere with fields or the QR.
     */
    private static String securityPatternSvg() {
        return """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 856 540">
                  <rect width="856" height="540" fill="#f8fbf8"/>

                  <g fill="none" stroke="#aec1b7" stroke-width="1.4" opacity="0.34">
                    <path d="M0 106 L90 72 L178 116 L264 74 L350 120 L438 78 L526 126 L616 82 L704 128 L792 88 L856 116"/>
                    <path d="M0 170 L82 130 L170 178 L258 132 L346 182 L434 136 L524 184 L614 140 L704 188 L794 146 L856 176"/>
                    <path d="M0 236 L86 194 L174 242 L262 198 L350 246 L438 202 L528 250 L618 206 L706 254 L796 212 L856 242"/>
                    <path d="M0 302 L84 260 L172 308 L260 264 L348 312 L436 268 L526 316 L616 272 L704 320 L794 278 L856 308"/>
                    <path d="M0 368 L88 326 L176 374 L264 330 L352 378 L440 334 L530 382 L620 338 L708 386 L798 344 L856 374"/>
                    <path d="M0 434 L84 392 L172 440 L260 396 L348 444 L436 400 L526 448 L616 404 L704 452 L794 410 L856 440"/>

                    <path d="M90 72 L82 130 L86 194 L84 260 L88 326 L84 392"/>
                    <path d="M178 116 L170 178 L174 242 L172 308 L176 374 L172 440"/>
                    <path d="M264 74 L258 132 L262 198 L260 264 L264 330 L260 396"/>
                    <path d="M350 120 L346 182 L350 246 L348 312 L352 378 L348 444"/>
                    <path d="M438 78 L434 136 L438 202 L436 268 L440 334 L436 400"/>
                    <path d="M526 126 L524 184 L528 250 L526 316 L530 382 L526 448"/>
                    <path d="M616 82 L614 140 L618 206 L616 272 L620 338 L616 404"/>
                    <path d="M704 128 L704 188 L706 254 L704 320 L708 386 L704 452"/>
                    <path d="M792 88 L794 146 L796 212 L794 278 L798 344 L794 410"/>
                  </g>

                  <g fill="#a9bdb3" opacity="0.28">
                    <circle cx="178" cy="116" r="4"/>
                    <circle cx="350" cy="120" r="4"/>
                    <circle cx="526" cy="126" r="4"/>
                    <circle cx="704" cy="128" r="4"/>
                    <circle cx="170" cy="178" r="4"/>
                    <circle cx="346" cy="182" r="4"/>
                    <circle cx="524" cy="184" r="4"/>
                    <circle cx="704" cy="188" r="4"/>
                    <circle cx="174" cy="242" r="4"/>
                    <circle cx="350" cy="246" r="4"/>
                    <circle cx="528" cy="250" r="4"/>
                    <circle cx="706" cy="254" r="4"/>
                  </g>

                  <g fill="none" stroke="#b8c9c0" stroke-width="1.2" opacity="0.23">
                    <path d="M-120 600 C20 330 210 180 470 108"/>
                    <path d="M-70 610 C72 350 250 214 494 140"/>
                    <path d="M-10 620 C126 386 286 252 520 178"/>
                    <path d="M55 624 C178 418 326 294 554 218"/>
                    <path d="M120 630 C230 456 372 340 590 266"/>
                    <path d="M190 634 C286 494 420 388 628 320"/>
                  </g>
                </svg>
                """;
    }

    private static String svgDataUri(String svg) {
        return "data:image/svg+xml;base64,"
                + Base64.getEncoder().encodeToString(
                svg.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static final String TRANSPARENT_PIXEL =
            "data:image/png;base64,"
                    + "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJ"
                    + "AAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";
}
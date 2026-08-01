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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Rendering a card to PDF, laid out from the printed original.
 *
 * SIX THINGS THAT DECIDE WHETHER THE OUTPUT IS PRINTABLE.
 *
 * 1. FONTS ARE REGISTERED IN JAVA, not with @font-face. openhtmltopdf resolves
 *    classpath resources far more reliably that way — and a missing Arabic
 *    font produces EMPTY BOXES with no error, which looks like a design choice
 *    until someone reads it.
 *
 * 2. BIDI IS WIRED EXPLICITLY. This card is ARABIC-PRIMARY: without the ICU
 *    splitter and reorderer its letters render unjoined and in reverse — the
 *    text present, and unreadable to everyone it is written for.
 *
 * 3. EVERY IMAGE IS A DATA URI. A file path would make the card depend on a
 *    working directory, which differs between IntelliJ, the jar, and the
 *    server.
 *
 * 4. THE EMBLEM AND SIGNATURE ARE LOADED ONCE, at startup, not per card. A
 *    batch of two hundred would otherwise re-read and re-encode them two
 *    hundred times.
 *
 * 5. THE PAGE IS THE CARD — 85.6 × 54 mm, zero margin, so the green band
 *    bleeds to the die line rather than sitting in a white frame.
 *
 * 6. A BATCH IS ONE FILE, with the page order chosen for the printer at hand.
 */
@Service
public class CardPdfService {

    private static final Logger log = LoggerFactory.getLogger(CardPdfService.class);

    /** The printed card writes dates as yyyy/MM/dd. */
    private static final DateTimeFormatter EXPIRY_PRINTED =
            DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private static final String FONT_DIR = "fonts/";
    private static final String IMAGE_DIR = "images/";

    private final TemplateEngine templateEngine;
    private final CardRepository cardRepository;
    private final ApplicationRepository applicationRepository;
    private final CandidateProfileRepository profileRepository;
    private final PressCategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final PhotoStorageService photoStorage;
    private final QrCodeService qrCodeService;
    private final AppProperties props;

    /** Property 4 — read once, encoded once. */
    private final String emblemDataUri;
    private final String signatureDataUri;
    private final String webDataUri;

    public CardPdfService(TemplateEngine templateEngine,
                          CardRepository cardRepository,
                          ApplicationRepository applicationRepository,
                          CandidateProfileRepository profileRepository,
                          PressCategoryRepository categoryRepository,
                          UserRepository userRepository,
                          PhotoStorageService photoStorage,
                          QrCodeService qrCodeService,
                          AppProperties props) {
        this.templateEngine = templateEngine;
        this.cardRepository = cardRepository;
        this.applicationRepository = applicationRepository;
        this.profileRepository = profileRepository;
        this.categoryRepository = categoryRepository;
        this.userRepository = userRepository;
        this.photoStorage = photoStorage;
        this.qrCodeService = qrCodeService;
        this.props = props;

        this.emblemDataUri = loadImage("logo_rim.png", "image/png");
        this.signatureDataUri = loadImage("minister-signature.svg", "image/svg+xml");
        this.webDataUri = svgDataUri(webPattern());
    }

    /** How the pages of a batch are ordered. */
    public enum PageLayout {
        /** F,B,F,B — an office duplex printer's natural order. */
        INTERLEAVED,
        /** All fronts, then all backs — what most card printers want. */
        SEQUENTIAL
    }

    /* ══ one card ══════════════════════════════════════════════ */

    /** Two pages: front, then back. */
    @Transactional(readOnly = true)
    public byte[] render(Long cardId) {
        Card card = cardRepository.findById(cardId)
                .orElseThrow(() -> new IllegalArgumentException("Carte introuvable: " + cardId));

        String html = templateEngine.process("card", buildContext(card));

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();

            // Property 2 — an Arabic-primary card without these is unreadable.
            builder.useUnicodeBidiSplitter(new ICUBidiSplitter.ICUBidiSplitterFactory());
            builder.useUnicodeBidiReorderer(new ICUBidiReorderer());
            builder.defaultTextDirection(PdfRendererBuilder.TextDirection.RTL);

            registerFonts(builder);

            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();

            return out.toByteArray();

        } catch (Exception e) {
            log.error("Card PDF rendering failed for card {}", cardId, e);
            throw new IllegalStateException(
                    "La carte n'a pas pu être générée (n° " + card.getCardNumber() + ")", e);
        }
    }

    /* ══ a batch ═══════════════════════════════════════════════ */

    /**
     * Many cards, one file.
     *
     * A failure on one card is recorded and skipped rather than aborting: 199
     * printable cards and a named failure beats nothing at all.
     */
    @Transactional(readOnly = true)
    public byte[] renderBatch(List<Long> cardIds, PageLayout layout) {
        List<byte[]> rendered = new ArrayList<>();

        for (Long cardId : cardIds) {
            try {
                rendered.add(render(cardId));
            } catch (RuntimeException e) {
                log.error("CARD_PDF_SKIPPED card={} reason={}", cardId, e.getMessage());
            }
        }
        if (rendered.isEmpty()) {
            throw new IllegalStateException("Aucune carte n'a pu être générée.");
        }

        byte[] merged = merge(rendered);
        return layout == PageLayout.SEQUENTIAL ? separateFaces(merged) : merged;
    }

    private byte[] merge(List<byte[]> documents) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDFMergerUtility merger = new PDFMergerUtility();
            merger.setDestinationStream(out);
            for (byte[] document : documents) {
                merger.addSource(new RandomAccessReadBuffer(document));
            }
            merger.mergeDocuments(null);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Les cartes n'ont pas pu être assemblées", e);
        }
    }

    /**
     * Reorder F,B,F,B into all-fronts-then-all-backs.
     *
     * Card printers usually run one side of a whole batch, then the other. The
     * two runs MUST stay in the same sequence — split by parity, never sorted,
     * or every card gets someone else's back.
     */
    private byte[] separateFaces(byte[] interleaved) {
        try (PDDocument source = Loader.loadPDF(interleaved);
             PDDocument target = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            int pageCount = source.getNumberOfPages();
            for (int i = 0; i < pageCount; i += 2) target.addPage(source.getPage(i));
            for (int i = 1; i < pageCount; i += 2) target.addPage(source.getPage(i));

            target.save(out);
            return out.toByteArray();

        } catch (IOException e) {
            throw new IllegalStateException("Les faces n'ont pas pu être séparées", e);
        }
    }

    /* ══ the model ═════════════════════════════════════════════ */

    private Context buildContext(Card card) {
        Application application = applicationRepository.findById(card.getApplicationId())
                .orElseThrow();
        User holder = userRepository.findById(application.getCandidateId()).orElseThrow();
        CandidateProfile profile = profileRepository.findById(holder.getId()).orElse(null);
        PressCategory category = categoryRepository.findById(application.getCategoryId())
                .orElse(null);

        String identity = profile == null ? "—"
                : (profile.getNni() != null ? profile.getNni() : profile.getPassportNo());

        Context context = new Context(Locale.forLanguageTag("ar"));

        context.setVariable("cardNumber", card.getCardNumber());
        context.setVariable("holderNameAr", holder.getFullName());
        context.setVariable("categoryAr", category == null ? "—" : category.getLabelAr());

        // The SNAPSHOT, not the live application: the holder may have changed
        // outlet since, and the card does not change with them.
        context.setVariable("specialisationAr",
                card.getSpecialisationAr() == null ? "—" : card.getSpecialisationAr());
        context.setVariable("institution",
                card.getInstitution() == null ? "—" : card.getInstitution());

        context.setVariable("identityNumber", identity == null ? "—" : identity);
        context.setVariable("expiryPrinted", card.getExpiresAt().format(EXPIRY_PRINTED));

        context.setVariable("photoDataUri", photoDataUri(card));
        context.setVariable("qrDataUri", qrCodeService.qrDataUri(card.getVerificationToken()));
        context.setVariable("emblemDataUri", emblemDataUri);
        context.setVariable("signatureDataUri", signatureDataUri);
        context.setVariable("webDataUri", webDataUri);

        // The back's notice, from the printed original.
        context.setVariable("noticeAr",
                "يرجى من كافة السلطات المدنية والعسكرية تسهيل مهمة حامل هذه البطاقة");
        context.setVariable("noticeFr",
                "Les autorités civiles et militaires sont priées<br/>"
              + "de faciliter la Tâche du titulaire de cette carte");

        return context;
    }

    private String photoDataUri(Card card) {
        if (card.getPhotoPath() == null) {
            return TRANSPARENT_PIXEL;
        }
        try {
            Path path = photoStorage.resolve(card.getPhotoPath());
            if (!Files.exists(path)) {
                log.warn("Card {} references a missing photograph: {}",
                        card.getCardNumber(), card.getPhotoPath());
                return TRANSPARENT_PIXEL;
            }
            String mime = path.toString().toLowerCase(Locale.ROOT).endsWith(".png")
                    ? "image/png" : "image/jpeg";
            return "data:" + mime + ";base64,"
                    + Base64.getEncoder().encodeToString(Files.readAllBytes(path));

        } catch (Exception e) {
            log.warn("Photograph unreadable for card {}: {}",
                    card.getCardNumber(), e.getMessage());
            return TRANSPARENT_PIXEL;
        }
    }

    /* ══ assets ════════════════════════════════════════════════ */

    /** Property 4 — read once at startup, not per card. */
    private String loadImage(String file, String mime) {
        try {
            ClassPathResource resource = new ClassPathResource(IMAGE_DIR + file);
            if (!resource.exists()) {
                log.error("""

                        ╔══════════════════════════════════════════════════════════╗
                        ║  MISSING CARD ASSET: {}
                        ╠══════════════════════════════════════════════════════════╣
                        ║  Expected at src/main/resources/images/                   ║
                        ║  The card will print with a gap where it should be.       ║
                        ╚══════════════════════════════════════════════════════════╝
                        """, file);
                return TRANSPARENT_PIXEL;
            }
            byte[] bytes = resource.getInputStream().readAllBytes();
            return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);

        } catch (IOException e) {
            log.error("Card asset {} could not be read: {}", file, e.getMessage());
            return TRANSPARENT_PIXEL;
        }
    }

    /* ══ fonts ═════════════════════════════════════════════════ */

    /**
     * Property 1 — on an Arabic-primary card the font is not optional.
     *
     * Without it EVERY field is an empty box, silently. The error below is the
     * only signal anyone gets before the cards are printed.
     */
    private void registerFonts(PdfRendererBuilder builder) {
        boolean arabic =
                addFont(builder, "NotoNaskhArabic-Regular.ttf", "Noto Naskh Arabic", 400)
              | addFont(builder, "NotoNaskhArabic-Bold.ttf", "Noto Naskh Arabic", 700);

        addFont(builder, "NotoSans-Regular.ttf", "Noto Sans", 400);
        addFont(builder, "NotoSans-Bold.ttf", "Noto Sans", 700);

        if (!arabic) {
            log.error("""

                    ╔══════════════════════════════════════════════════════════╗
                    ║  NO ARABIC FONT — THE CARD WILL BE EMPTY BOXES           ║
                    ╠══════════════════════════════════════════════════════════╣
                    ║  This card is Arabic-primary: the name, category,        ║
                    ║  specialisation and institution are ALL Arabic.          ║
                    ║                                                          ║
                    ║  Place NotoNaskhArabic-Regular.ttf in                    ║
                    ║  src/main/resources/fonts/ and restart. See FONTS.md.    ║
                    ╚══════════════════════════════════════════════════════════╝
                    """);
        }
    }

    private boolean addFont(PdfRendererBuilder builder, String file,
                            String family, int weight) {
        try {
            if (!new ClassPathResource(FONT_DIR + file).exists()) {
                return false;
            }
            // A supplier, not a stream: the font may be opened more than once.
            builder.useFont(() -> {
                try {
                    return new ClassPathResource(FONT_DIR + file).getInputStream();
                } catch (IOException e) {
                    throw new IllegalStateException("Font unreadable: " + file, e);
                }
            }, family, weight, PdfRendererBuilder.FontStyle.NORMAL, true);
            return true;

        } catch (Exception e) {
            log.warn("Font {} could not be registered: {}", file, e.getMessage());
            return false;
        }
    }

    /* ══ the security web ══════════════════════════════════════ */

    /**
     * The radiating web with nodes, as on the printed card.
     *
     * Generated rather than shipped as an asset: a vector pattern stays crisp
     * at any print resolution, and a raster one would band visibly at 300 DPI.
     */
    private static String webPattern() {
        StringBuilder rays = new StringBuilder();
        StringBuilder arcs = new StringBuilder();
        StringBuilder nodes = new StringBuilder();

        // Rays from an off-centre origin, as the original does.
        for (int i = 0; i < 40; i++) {
            double angle = Math.toRadians(i * 9.0);
            double x = 500 + Math.cos(angle) * 900;
            double y = 260 + Math.sin(angle) * 900;
            rays.append("<line x1=\"500\" y1=\"260\" x2=\"%.0f\" y2=\"%.0f\"/>"
                    .formatted(x, y));
        }
        // Concentric arcs crossing them.
        for (int r = 60; r <= 900; r += 60) {
            arcs.append("<circle cx=\"500\" cy=\"260\" r=\"%d\"/>".formatted(r));
        }
        // Nodes where a few rays meet an arc — the detail that reads as
        // engraved rather than drawn.
        for (int i = 0; i < 40; i += 3) {
            double angle = Math.toRadians(i * 9.0);
            for (int r = 120; r <= 780; r += 180) {
                nodes.append("<circle cx=\"%.0f\" cy=\"%.0f\" r=\"4\" fill=\"#8fa89b\" stroke=\"none\"/>"
                        .formatted(500 + Math.cos(angle) * r, 260 + Math.sin(angle) * r));
            }
        }

        return """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 630">
                  <g stroke="#8fa89b" stroke-width="1" fill="none" opacity="0.55">
                    %s%s
                  </g>
                  %s
                </svg>
                """.formatted(rays, arcs, nodes);
    }

    private static String svgDataUri(String svg) {
        return "data:image/svg+xml;base64,"
                + Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
    }

    /** Stands in for a missing image, so the layout never collapses. */
    private static final String TRANSPARENT_PIXEL =
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";
}

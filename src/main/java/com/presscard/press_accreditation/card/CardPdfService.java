package com.presscard.press_accreditation.card;

import com.openhtmltopdf.bidi.support.ICUBidiReorderer;
import com.openhtmltopdf.bidi.support.ICUBidiSplitter;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.presscard.press_accreditation.application.Application;
import com.presscard.press_accreditation.application.ApplicationRepository;
import com.presscard.press_accreditation.category.PressCategory;
import com.presscard.press_accreditation.category.PressCategoryRepository;
import com.presscard.press_accreditation.config.AppProperties;
import com.presscard.press_accreditation.error.IncompatibleBatchException;
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
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import java.awt.BasicStroke;


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

    private static final int PATTERN_WIDTH_PX = 1712;   // 85.6mm × 20
    private static final int PATTERN_HEIGHT_PX = 1080;  // 54.0mm × 20

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
                "image/png"
        );
        this.watermarkDataUri = createWatermarkDataUri(LOGO_FILE, 0.075f);
//        this.securityPatternDataUri = svgDataUri(securityPatternSvg());
        this.securityPatternDataUri = createSecurityPatternDataUri();
    }

    public enum PageLayout {
        /** Front, back, front, back: suitable for ordinary duplex printing. */
        INTERLEAVED,

        /** All fronts followed by all backs: suitable for many card printers. */
        SEQUENTIAL,

        /**
         * All fronts, then ONE back.
         *
         * Every back is identical now that the QR sits on the front — the
         * ministry lockup, صحافة/PRESSE and the notice never vary. A card
         * printer runs that single back across the whole batch, halving the
         * ribbon and the pass count.
         *
         * ⚠️ EXCEPT THE EXPIRY DATE. Cards from one session share it, but a
         * selection spanning two sessions does not — so the layout is REFUSED
         * rather than silently producing wrong credentials.
         */
        SHARED_BACK
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

        if (effectiveLayout == PageLayout.SHARED_BACK) {
            requireOneExpiryDate(cardIds);
        }

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

//        byte[] merged = merge(renderedCards);
//
//        return effectiveLayout == PageLayout.SEQUENTIAL
//                ? separateFaces(merged)
//                : merged;
        byte[] merged = merge(renderedCards);

        return switch (effectiveLayout) {
            case SEQUENTIAL -> separateFaces(merged);
            case SHARED_BACK -> frontsWithOneBack(merged);
            case INTERLEAVED -> merged;
        };
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

    /**
     * All fronts, then a single back.
     *
     * Keeping the FIRST card's back is safe only because requireOneExpiryDate
     * has already established they are all identical.
     */
    private byte[] frontsWithOneBack(byte[] interleavedPdf) {
        try (PDDocument source = Loader.loadPDF(interleavedPdf);
             PDDocument target = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            int pageCount = source.getNumberOfPages();

            for (int page = 0; page < pageCount; page += 2) {
                target.importPage(source.getPage(page));
            }
            if (pageCount > 1) {
                target.importPage(source.getPage(1));
            }

            target.save(output);
            return output.toByteArray();

        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Les cartes n'ont pas pu être assemblées.",
                    exception
            );
        }
    }

    /**
     * The guard that makes a shared back safe.
     *
     * Refused rather than silently degraded: falling back to SEQUENTIAL would
     * quietly produce a different document from the one requested, and the
     * administrator would discover it at the printer with the batch already
     * fed.
     */
    private void requireOneExpiryDate(List<Long> cardIds) {
        List<LocalDate> distinct = cardRepository.findAllById(cardIds).stream()
                .map(Card::getExpiresAt)
                .distinct()
                .sorted()
                .toList();

        if (distinct.size() > 1) {
            throw new IncompatibleBatchException(
                    ("Ces cartes n'ont pas toutes la même date d'expiration (%s). "
                            + "Un verso commun imprimerait une date erronée sur une partie "
                            + "du lot : choisissez une autre disposition, ou n'imprimez "
                            + "qu'une session à la fois.")
                            .formatted(distinct.stream()
                                    .map(d -> d.format(EXPIRY_PRINTED))
                                    .collect(java.util.stream.Collectors.joining(", "))));
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
    private String createSecurityPatternDataUri() {
        final double originX = 1310;
        final double originY = 450;
        final int rayCount = 44;
        final double arcRatio = 1.26;
        final double innerRadius = 84;
        final double maxRadius = 2360;

        BufferedImage image = new BufferedImage(
                PATTERN_WIDTH_PX, PATTERN_HEIGHT_PX, BufferedImage.TYPE_INT_RGB);

        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(
                    RenderingHints.KEY_STROKE_CONTROL,
                    RenderingHints.VALUE_STROKE_PURE);

            // The card's field colour — the pattern IS the background, so the
            // two must match or a seam shows at the edge.
            graphics.setColor(new Color(0xF8, 0xFB, 0xF8));
            graphics.fillRect(0, 0, PATTERN_WIDTH_PX, PATTERN_HEIGHT_PX);

            // ── rays ──
            graphics.setColor(new Color(0xB3, 0xC5, 0xBB, 140));
            graphics.setStroke(new BasicStroke(1.7f));
            for (int i = 0; i < rayCount; i++) {
                double angle = 2 * Math.PI * i / rayCount;
                graphics.draw(new Line2D.Double(
                        originX + Math.cos(angle) * innerRadius,
                        originY + Math.sin(angle) * innerRadius,
                        originX + Math.cos(angle) * maxRadius,
                        originY + Math.sin(angle) * maxRadius));
            }

            // ── arcs, tightening toward the origin ──
            graphics.setColor(new Color(0xB3, 0xC5, 0xBB, 115));
            graphics.setStroke(new BasicStroke(1.5f));
            for (double r = innerRadius; r <= maxRadius; r *= arcRatio) {
                graphics.draw(new Ellipse2D.Double(
                        originX - r, originY - r, r * 2, r * 2));
            }

            // ── nodes, on every third ray ──
            graphics.setColor(new Color(0xA4, 0xB9, 0xAE, 150));
            final double nodeRadius = 7;
            for (int i = 0; i < rayCount; i += 3) {
                double angle = 2 * Math.PI * i / rayCount;
                for (double r = innerRadius; r <= maxRadius; r *= arcRatio) {
                    double x = originX + Math.cos(angle) * r;
                    double y = originY + Math.sin(angle) * r;
                    if (x > -40 && x < PATTERN_WIDTH_PX + 40
                            && y > -40 && y < PATTERN_HEIGHT_PX + 40) {
                        graphics.fill(new Ellipse2D.Double(
                                x - nodeRadius, y - nodeRadius,
                                nodeRadius * 2, nodeRadius * 2));
                    }
                }
            }
        } finally {
            graphics.dispose();
        }

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return "data:image/png;base64,"
                    + Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "The card security pattern could not be generated.",
                    exception);
        }
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
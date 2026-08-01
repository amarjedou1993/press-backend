package com.presscard.press_accreditation.card;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.presscard.press_accreditation.config.AppProperties;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;

/**
 * The QR on the card's back face.
 *
 * THREE CHOICES THAT DECIDE WHETHER IT SCANS.
 *
 * · ERROR CORRECTION Q (25%). A press card lives in a wallet and gets
 *   scratched; L would look identical when printed and fail after six months
 *   of use. H would be more robust still, but makes the symbol denser than a
 *   phone camera reads reliably at 15 mm.
 *
 * · THE PAYLOAD IS SHORT. A 22-character token in a URL keeps the symbol at
 *   version 3–4, whose modules stay large enough to read at card size. This
 *   is the practical reason verification is online-only: embedding the
 *   holder's details would double the density for a feature that cannot
 *   reflect revocation anyway.
 *
 * · GENEROUS QUIET ZONE. Four modules of white, non-negotiable — a QR printed
 *   flush against a border is a QR that does not scan.
 */
@Service
public class QrCodeService {

    /** Rendered large; the PDF scales it down. Never the reverse. */
    private static final int RENDER_SIZE_PX = 600;
    private static final int QUIET_ZONE_MODULES = 4;

    private final AppProperties props;

    public QrCodeService(AppProperties props) {
        this.props = props;
    }

    /** The URL a scan resolves to. */
    public String verificationUrl(String verificationToken) {
        String base = props.card().verificationBaseUrl();
        return base.endsWith("/")
                ? base + verificationToken
                : base + "/" + verificationToken;
    }

    /**
     * A PNG data URI, ready for the Thymeleaf template's <img src>.
     *
     * Embedded rather than referenced: openhtmltopdf would otherwise need to
     * resolve a file path at render time, which fails differently on every
     * machine and would make the card depend on a working directory.
     */
    public String qrDataUri(String verificationToken) {
        return "data:image/png;base64," + Base64.getEncoder()
                .encodeToString(qrPng(verificationUrl(verificationToken)));
    }

    public byte[] qrPng(String content) {
        try {
            Map<EncodeHintType, Object> hints = Map.of(
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.Q,
                    EncodeHintType.CHARACTER_SET, "UTF-8",
                    EncodeHintType.MARGIN, QUIET_ZONE_MODULES);

            BitMatrix matrix = new QRCodeWriter().encode(
                    content, BarcodeFormat.QR_CODE, RENDER_SIZE_PX, RENDER_SIZE_PX, hints);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();

        } catch (Exception e) {
            throw new IllegalStateException(
                    "QR generation failed for a card — the back face cannot be printed "
                  + "without it", e);
        }
    }
}

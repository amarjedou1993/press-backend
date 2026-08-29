//package com.presscard.press_accreditation.storage;
//
//import com.presscard.press_accreditation.error.InvalidFileException;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.stereotype.Service;
//import org.springframework.web.multipart.MultipartFile;
//
//import javax.imageio.ImageIO;
//import java.awt.image.BufferedImage;
//import java.io.IOException;
//import java.io.InputStream;
//import java.nio.file.*;
//import java.util.Locale;
//import java.util.UUID;
//
///**
// * Stores identity photographs.
// *
// * Separate from FileStorageService because the rules are genuinely different:
// * a supporting document may be a PDF of any shape, whereas a photograph must
// * be an IMAGE of usable resolution and roughly portrait proportions, or the
// * printed card is unusable — and a card is only discovered to be unusable
// * when someone tries to verify its holder.
// *
// * Constraints follow ICAO passport-photo conventions, which is what every
// * credential authority uses and what applicants will already have from a
// * passport application:
// *   · JPEG or PNG only (a photo is not a document)
// *   · at least 600 x 800 px — below that, print at card size is visibly poor
// *   · portrait, roughly 3:4, tolerated between 0.65 and 0.85
// *   · 5 MB maximum
// *
// * What CANNOT be checked mechanically — plain background, face forward,
// * no sunglasses — is stated as guidance in the UI and verified by the
// * commission, which is why a reviewer can flag the photo for correction.
// */
//@Service
//public class PhotoStorageService {
//
//    private static final Logger log = LoggerFactory.getLogger(PhotoStorageService.class);
//
//    private static final long MAX_BYTES = 5L * 1024 * 1024;      // 5 MB
//    private static final int MIN_WIDTH = 600;
//    private static final int MIN_HEIGHT = 800;
//    private static final double MIN_RATIO = 0.65;                // w/h
//    private static final double MAX_RATIO = 0.85;
//
//    private final Path root;
//
//    public PhotoStorageService(com.presscard.press_accreditation.config.AppProperties props) {
//        // Photographs live beside documents but in their own subtree, so a
//        // future retention rule can treat them separately.
//        this.root = Paths.get(props.storage().rootDirectory())
//                .resolve("photos").toAbsolutePath().normalize();
//        try {
//            Files.createDirectories(root);
//            log.info("Photo storage root: {}", root);
//        } catch (IOException e) {
//            throw new IllegalStateException("Cannot create photo storage root: " + root, e);
//        }
//    }
//
//    /**
//     * Validate and store a candidate's photograph, replacing any previous one.
//     * @return the RELATIVE path to persist on candidate_profiles.photo_path
//     */
//    public String store(MultipartFile file, Long userId, String previousPath) {
//        BufferedImage image = validate(file);
//        String extension = extensionFor(file.getContentType());
//
//        // One directory per candidate; a UUID name so nothing is guessable
//        // and a replacement never collides with its predecessor.
//        String relative = "photos/%d/%s%s".formatted(userId, UUID.randomUUID(), extension);
//        Path target = root.getParent().resolve(relative).normalize();
//        if (!target.startsWith(root)) {
//            throw new InvalidFileException("Chemin de destination invalide.");
//        }
//
//        try {
//            Files.createDirectories(target.getParent());
//            try (InputStream in = file.getInputStream()) {
//                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
//            }
//        } catch (IOException e) {
//            log.error("Failed to store photo for user {}", userId, e);
//            throw new InvalidFileException("La photo n'a pas pu être enregistrée.");
//        }
//
//        // Remove the superseded file — but only AFTER the new one is safely
//        // written, so a failure never leaves the candidate with no photo.
//        if (previousPath != null && !previousPath.isBlank()) {
//            delete(previousPath);
//        }
//
//        log.info("PHOTO_STORED user={} path={} size={}x{}",
//                userId, relative, image.getWidth(), image.getHeight());
//        return relative;
//    }
//
//    public Path resolve(String relativePath) {
//        Path target = root.getParent().resolve(relativePath).normalize();
//        if (!target.startsWith(root)) {
//            throw new InvalidFileException("Chemin de photo invalide.");
//        }
//        return target;
//    }
//
//    /** Copy the current photo into the card's own immutable slot (week 6). */
//    public String snapshotForCard(String profilePhotoPath, Long cardId) {
//        Path source = resolve(profilePhotoPath);
//        String extension = profilePhotoPath.substring(profilePhotoPath.lastIndexOf('.'));
//        String relative = "photos/cards/%d%s".formatted(cardId, extension);
//        Path target = root.getParent().resolve(relative).normalize();
//
//        try {
//            Files.createDirectories(target.getParent());
//            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
//        } catch (IOException e) {
//            throw new IllegalStateException(
//                    "Could not snapshot photo for card " + cardId, e);
//        }
//        log.info("PHOTO_SNAPSHOT card={} from={} to={}", cardId, profilePhotoPath, relative);
//        return relative;
//    }
//
//    public void delete(String relativePath) {
//        try {
//            Files.deleteIfExists(resolve(relativePath));
//        } catch (IOException e) {
//            // Non-fatal: an orphaned file beats a failed request.
//            log.warn("Could not delete photo {}: {}", relativePath, e.getMessage());
//        }
//    }
//
//    /* ── validation ── */
//
//    private BufferedImage validate(MultipartFile file) {
//        if (file == null || file.isEmpty()) {
//            throw new InvalidFileException("Aucune photo sélectionnée.");
//        }
//        if (file.getSize() > MAX_BYTES) {
//            throw new InvalidFileException("La photo dépasse 5 Mo.");
//        }
//
//        String contentType = file.getContentType();
//        if (contentType == null
//                || !(contentType.equalsIgnoreCase("image/jpeg")
//                  || contentType.equalsIgnoreCase("image/png"))) {
//            throw new InvalidFileException(
//                    "Format non accepté. Utilisez une photo JPEG ou PNG.");
//        }
//
//        BufferedImage image;
//        try (InputStream in = file.getInputStream()) {
//            image = ImageIO.read(in);
//        } catch (IOException e) {
//            throw new InvalidFileException("La photo n'a pas pu être lue.");
//        }
//        // A declared content-type proves nothing; only decoding does.
//        if (image == null) {
//            throw new InvalidFileException(
//                    "Ce fichier n'est pas une image valide.");
//        }
//
//        int w = image.getWidth();
//        int h = image.getHeight();
//
//        if (w < MIN_WIDTH || h < MIN_HEIGHT) {
//            throw new InvalidFileException(
//                    ("Photo trop petite (%d×%d). Une résolution d'au moins %d×%d pixels "
//                   + "est nécessaire pour l'impression de la carte.")
//                            .formatted(w, h, MIN_WIDTH, MIN_HEIGHT));
//        }
//
//        double ratio = (double) w / h;
//        if (ratio < MIN_RATIO || ratio > MAX_RATIO) {
//            throw new InvalidFileException(
//                    "La photo doit être au format portrait (proportions type photo "
//                  + "d'identité, environ 3:4). Recadrez-la avant de l'envoyer.");
//        }
//
//        return image;
//    }
//
//    private String extensionFor(String contentType) {
//        return contentType.toLowerCase(Locale.ROOT).equals("image/png") ? ".png" : ".jpg";
//    }
//}


package com.presscard.press_accreditation.storage;

import com.presscard.press_accreditation.error.InvalidFileException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.Locale;
import java.util.UUID;

/**
 * Stores identity photographs.
 *
 * Separate from FileStorageService because the rules are genuinely different:
 * a supporting document may be a PDF of any shape, whereas a photograph must
 * be an IMAGE of usable resolution and roughly portrait proportions, or the
 * printed card is unusable — and a card is only discovered to be unusable
 * when someone tries to verify its holder.
 *
 * Constraints follow ICAO passport-photo conventions, which is what every
 * credential authority uses and what applicants will already have from a
 * passport application:
 *   · JPEG or PNG only (a photo is not a document)
 *   · at least 600 x 800 px — below that, print at card size is visibly poor
 *   · portrait, roughly 3:4, tolerated between 0.65 and 0.85
 *   · 5 MB maximum
 *
 * What CANNOT be checked mechanically — plain background, face forward,
 * no sunglasses — is stated as guidance in the UI and verified by the
 * commission, which is why a reviewer can flag the photo for correction.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ KNOWN GAP: THE MESSAGES BELOW ARE FRENCH SENTENCES, NOT KEYS.
 *
 * GlobalExceptionHandler passes InvalidFileException's message through as
 * ProblemDetail.detail, and PhotoUpload resolves that detail with
 * useFieldError — which lets any unrecognised string through unchanged.
 *
 * So a candidate reading Arabic who uploads a photograph that is too small
 * receives a French sentence. Nothing fails; the message simply appears in
 * the wrong language, under an Arabic label.
 *
 * The fix is keys with the dimensions as arguments, the way SubmissionGate's
 * deadline travels as yyyy-MM-dd. Six messages, plus catalogue entries. Not
 * done here because this file works and a rewrite of an upload path days
 * before a deployment is a poor trade — but it is a real hole in the
 * bilingual work, and it is on the candidate's path rather than the
 * Ministry's.
 * ───────────────────────────────────────────────────────────────────────
 */
@Service
public class PhotoStorageService {

    private static final Logger log = LoggerFactory.getLogger(PhotoStorageService.class);

    private static final long MAX_BYTES = 5L * 1024 * 1024;      // 5 MB
    private static final int MIN_WIDTH = 600;
    private static final int MIN_HEIGHT = 800;
    private static final double MIN_RATIO = 0.65;                // w/h
    private static final double MAX_RATIO = 0.85;

    private final Path root;

    public PhotoStorageService(com.presscard.press_accreditation.config.AppProperties props) {
        // Photographs live beside documents but in their own subtree, so a
        // future retention rule can treat them separately.
        this.root = Paths.get(props.storage().rootDirectory())
                .resolve("photos").toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            log.info("Photo storage root: {}", root);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create photo storage root: " + root, e);
        }
    }

    /**
     * Validate and store a candidate's photograph, replacing any previous one.
     * @return the RELATIVE path to persist on candidate_profiles.photo_path
     */
    public String store(MultipartFile file, Long userId, String previousPath) {
        BufferedImage image = validate(file);
        String extension = extensionFor(file.getContentType());

        // One directory per candidate; a UUID name so nothing is guessable
        // and a replacement never collides with its predecessor.
        String relative = "photos/%d/%s%s".formatted(userId, UUID.randomUUID(), extension);
        Path target = root.getParent().resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new InvalidFileException("Chemin de destination invalide.");
        }

        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("Failed to store photo for user {}", userId, e);
            throw new InvalidFileException("La photo n'a pas pu être enregistrée.");
        }

        // Remove the superseded file — but only AFTER the new one is safely
        // written, so a failure never leaves the candidate with no photo.
        if (previousPath != null && !previousPath.isBlank()) {
            delete(previousPath);
        }

        log.info("PHOTO_STORED user={} path={} size={}x{}",
                userId, relative, image.getWidth(), image.getHeight());
        return relative;
    }

    public Path resolve(String relativePath) {
        Path target = root.getParent().resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            throw new InvalidFileException("Chemin de photo invalide.");
        }
        return target;
    }

    /** Copy the current photo into the card's own immutable slot (week 6). */
    public String snapshotForCard(String profilePhotoPath, Long cardId) {
        Path source = resolve(profilePhotoPath);
        String extension = profilePhotoPath.substring(profilePhotoPath.lastIndexOf('.'));
        String relative = "photos/cards/%d%s".formatted(cardId, extension);
        Path target = root.getParent().resolve(relative).normalize();

        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not snapshot photo for card " + cardId, e);
        }
        log.info("PHOTO_SNAPSHOT card={} from={} to={}", cardId, profilePhotoPath, relative);
        return relative;
    }

    /**
     * Validate and store an honour card's photograph.
     *
     * ⚠️ NOT snapshotForCard, AND THE DIFFERENCE MATTERS.
     *
     * That method COPIES a photograph already stored against a candidate
     * profile — already validated, already the right shape. An honour card has
     * no profile: the Ministry uploads the file directly, so it arrives
     * unchecked and must go through validate() like a candidate's own.
     *
     * The same rules apply, deliberately. A photograph too small or too wide
     * produces a card that is unusable at a checkpoint — and an honour card is
     * exactly the credential nobody will think to check twice.
     *
     * @return the RELATIVE path to persist on honour_cards.photo_path
     */
    public String storeForHonourCard(MultipartFile file, Long honourCardId,
                                     String previousPath) {
        BufferedImage image = validate(file);
        String extension = extensionFor(file.getContentType());

        // A UUID name rather than the card id: replacing a photograph must not
        // reuse a path a browser or a proxy may have cached.
        String relative = "photos/honour/%d/%s%s"
                .formatted(honourCardId, UUID.randomUUID(), extension);
        Path target = root.getParent().resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new InvalidFileException("Chemin de destination invalide.");
        }

        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("Failed to store honour card photo {}", honourCardId, e);
            throw new InvalidFileException("La photo n'a pas pu être enregistrée.");
        }

        // Removed AFTER the new one is written, so a failure never leaves the
        // card with no photograph at all.
        if (previousPath != null && !previousPath.isBlank()) {
            delete(previousPath);
        }

        log.info("HONOUR_PHOTO_STORED card={} path={} size={}x{}",
                honourCardId, relative, image.getWidth(), image.getHeight());
        return relative;
    }

    public void delete(String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException e) {
            // Non-fatal: an orphaned file beats a failed request.
            log.warn("Could not delete photo {}: {}", relativePath, e.getMessage());
        }
    }

    /* ── validation ── */

    private BufferedImage validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidFileException("Aucune photo sélectionnée.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new InvalidFileException("La photo dépasse 5 Mo.");
        }

        String contentType = file.getContentType();
        if (contentType == null
                || !(contentType.equalsIgnoreCase("image/jpeg")
                || contentType.equalsIgnoreCase("image/png"))) {
            throw new InvalidFileException(
                    "Format non accepté. Utilisez une photo JPEG ou PNG.");
        }

        BufferedImage image;
        try (InputStream in = file.getInputStream()) {
            image = ImageIO.read(in);
        } catch (IOException e) {
            throw new InvalidFileException("La photo n'a pas pu être lue.");
        }

        // A declared content-type proves nothing; only decoding does.
        if (image == null) {
            throw new InvalidFileException(
                    "Ce fichier n'est pas une image valide.");
        }

        int w = image.getWidth();
        int h = image.getHeight();

        if (w < MIN_WIDTH || h < MIN_HEIGHT) {
            throw new InvalidFileException(
                    ("Photo trop petite (%d×%d). Une résolution d'au moins %d×%d pixels "
                            + "est nécessaire pour l'impression de la carte.")
                            .formatted(w, h, MIN_WIDTH, MIN_HEIGHT));
        }

        double ratio = (double) w / h;
        if (ratio < MIN_RATIO || ratio > MAX_RATIO) {
            throw new InvalidFileException(
                    "La photo doit être au format portrait (proportions type photo "
                            + "d'identité, environ 3:4). Recadrez-la avant de l'envoyer.");
        }

        return image;
    }

    private String extensionFor(String contentType) {
        return contentType.toLowerCase(Locale.ROOT).equals("image/png") ? ".png" : ".jpg";
    }
}
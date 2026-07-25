package com.presscard.press_accreditation.storage;

import com.presscard.press_accreditation.config.AppProperties;
import com.presscard.press_accreditation.error.InvalidFileException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Stores uploaded supporting documents on disk.
 *
 * ⚠️ DEPLOYMENT REQUIREMENT — app.storage.root-directory MUST point at a
 * PERSISTENT volume. In a container without a mounted volume, every uploaded
 * document is destroyed on restart. These files are the evidence behind
 * accreditation decisions; losing them is not recoverable.
 *
 * Security decisions, each guarding a specific attack:
 *  · the client's filename is NEVER used on disk — a random UUID is. That
 *    defeats path traversal ("../../etc/passwd"), collisions, and unicode
 *    surprises in submitted names.
 *  · the extension comes from the ALLOWED content type, not the submitted
 *    name, so "invoice.pdf.exe" cannot land as an executable.
 *  · MIME type is checked against the configured allow-list; size against the
 *    configured maximum.
 *  · every resolved path is verified to still sit inside the root.
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy/MM");

    private final Path root;
    private final long maxSizeBytes;
    private final List<String> allowedMimeTypes;

    public FileStorageService(AppProperties props) {
        this.root = Paths.get(props.storage().rootDirectory()).toAbsolutePath().normalize();
        this.maxSizeBytes = props.storage().maxFileSizeBytes();
        this.allowedMimeTypes = props.storage().allowedMimeTypes();
        try {
            Files.createDirectories(root);
            log.info("Document storage root: {}", root);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create storage root: " + root, e);
        }
    }

    /**
     * Validate and store one uploaded file.
     * @return the RELATIVE path to persist in application_documents.file_path
     */
    public String store(MultipartFile file, Long applicationId) {
        validate(file);

        String extension = extensionFor(file.getContentType());
        // yyyy/MM/{applicationId}/{uuid}.{ext} — keeps directories small and
        // makes one application's documents easy to locate or purge.
        String relative = "%s/%d/%s%s".formatted(
                LocalDate.now().format(MONTH), applicationId,
                UUID.randomUUID(), extension);

        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new InvalidFileException("Chemin de destination invalide.");
        }

        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("Failed to store upload for application {}", applicationId, e);
            throw new InvalidFileException("Le fichier n'a pas pu être enregistré.");
        }

        log.info("DOCUMENT_STORED application={} path={} size={}",
                applicationId, relative, file.getSize());
        return relative;
    }

    /** Resolve a stored path for reading, refusing anything outside the root. */
    public Path resolve(String relativePath) {
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            throw new InvalidFileException("Chemin de fichier invalide.");
        }
        return target;
    }

    /** Remove a stored file (used when a candidate replaces a document). */
    public void delete(String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException e) {
            // Non-fatal: an orphaned file beats a failed request.
            log.warn("Could not delete stored file {}: {}", relativePath, e.getMessage());
        }
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidFileException("Le fichier est vide.");
        }
        if (file.getSize() > maxSizeBytes) {
            throw new InvalidFileException(
                    "Le fichier dépasse la taille maximale autorisée (%d Mo)."
                            .formatted(maxSizeBytes / (1024 * 1024)));
        }
        String contentType = file.getContentType();
        if (contentType == null
                || !allowedMimeTypes.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new InvalidFileException(
                    "Format non autorisé. Formats acceptés : PDF, JPEG, PNG.");
        }
    }

    private String extensionFor(String contentType) {
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "application/pdf" -> ".pdf";
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            default -> throw new InvalidFileException("Format non autorisé.");
        };
    }
}

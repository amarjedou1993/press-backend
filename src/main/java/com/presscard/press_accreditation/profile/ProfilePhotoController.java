package com.presscard.press_accreditation.profile;

import com.presscard.press_accreditation.error.InvalidFileException;
import com.presscard.press_accreditation.storage.PhotoStorageService;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.time.OffsetDateTime;

/**
 * The candidate's identity photograph.
 *
 * ACCESS is deliberately tighter than for documents. A photograph is
 * biometric-adjacent personal data, so it is served by USER ID through an
 * authorisation check, never by path — and only three parties may see it:
 *   · the owner (this controller)
 *   · a reviewer examining that candidate's dossier (week 4, separate
 *     endpoint under /api/reviewer/**)
 *   · card generation (week 6, server-side only)
 *
 * The stored path never appears in any API response.
 */
@RestController
@RequestMapping("/api/me/photo")
@PreAuthorize("hasRole('CANDIDATE')")
public class ProfilePhotoController {

    private static final Logger log = LoggerFactory.getLogger("PROFILE_AUDIT");

    public record PhotoResponse(boolean hasPhoto, String uploadedAt, boolean ageing) {}

    private final UserRepository userRepository;
    private final CandidateProfileRepository profileRepository;
    private final PhotoStorageService photoStorage;

    public ProfilePhotoController(UserRepository userRepository,
                                  CandidateProfileRepository profileRepository,
                                  PhotoStorageService photoStorage) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.photoStorage = photoStorage;
    }

    /** Upload or replace the photograph. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public PhotoResponse upload(@RequestParam MultipartFile file, Principal principal) {
        User user = currentUser(principal);

        CandidateProfile profile = profileRepository.findById(user.getId())
                .orElseThrow(() -> new InvalidFileException(
                        "Complétez d'abord votre identité avant d'ajouter une photo."));

        String stored = photoStorage.store(file, user.getId(), profile.getPhotoPath());
        profile.setPhotoPath(stored);
        profile.setPhotoUploadedAt(OffsetDateTime.now());
        profileRepository.save(profile);

        log.info("PHOTO_UPDATED user={}", user.getEmail());
        return new PhotoResponse(true, profile.getPhotoUploadedAt().toString(), false);
    }

    /** Stream the candidate's own photograph. */
    @GetMapping
    public ResponseEntity<Resource> download(Principal principal) throws IOException {
        User user = currentUser(principal);
        CandidateProfile profile = profileRepository.findById(user.getId()).orElse(null);

        if (profile == null || profile.getPhotoPath() == null) {
            return ResponseEntity.notFound().build();
        }

        Path path = photoStorage.resolve(profile.getPhotoPath());
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }

        String contentType = Files.probeContentType(path);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        contentType != null ? contentType : "image/jpeg"))
                // Personal data: never cached by a shared proxy.
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(new UrlResource(path.toUri()));
    }

    /** Status without transferring the image — used by the profile page. */
    @GetMapping("/status")
    public PhotoResponse status(Principal principal) {
        User user = currentUser(principal);
        CandidateProfile profile = profileRepository.findById(user.getId()).orElse(null);

        if (profile == null || profile.getPhotoPath() == null) {
            return new PhotoResponse(false, null, false);
        }
        return new PhotoResponse(
                true,
                profile.getPhotoUploadedAt() == null ? null : profile.getPhotoUploadedAt().toString(),
                profile.isPhotoAgeing());
    }

    /**
     * Remove the photograph.
     *
     * The candidate must be able to undo a bad upload — a blurry scan, the
     * wrong person, a photo they no longer want on an official document.
     * Removing it makes the profile incomplete again, so the submit gate
     * blocks until a replacement exists; that is the correct outcome, not a
     * reason to forbid deletion.
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(Principal principal) {
        User user = currentUser(principal);
        CandidateProfile profile = profileRepository.findById(user.getId()).orElse(null);

        if (profile == null || profile.getPhotoPath() == null) {
            return;   // idempotent: nothing to remove is not an error
        }

        String path = profile.getPhotoPath();
        profile.setPhotoPath(null);
        profile.setPhotoUploadedAt(null);
        profileRepository.save(profile);

        // The row is cleared first: an orphaned file is harmless, a profile
        // pointing at a deleted file is not.
        photoStorage.delete(path);

        log.info("PHOTO_DELETED user={}", user.getEmail());
    }

    private User currentUser(Principal principal) {
        return userRepository.findByEmail(principal.getName()).orElseThrow();
    }
}

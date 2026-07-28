package com.presscard.press_accreditation.storage;

import com.presscard.press_accreditation.config.AppProperties;
import com.presscard.press_accreditation.error.InvalidFileException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A card with an unusable photograph fails at the one moment it matters —
 * when someone tries to verify its holder — and cannot be fixed after
 * printing. So every rejection rule gets a test.
 *
 * Pure unit test: real image bytes are generated in memory, no Spring,
 * no Docker.
 */
class PhotoStorageServiceTest {

    @TempDir Path tempDir;

    private PhotoStorageService service() {
        var props = new AppProperties(
                null,
                new AppProperties.Storage(tempDir.toString(), 10_485_760, List.of()),
                null, null, null, null, null, null, null, null, null, null, null);
        return new PhotoStorageService(props);
    }

    /** A real JPEG of the given size — not a fake with an image content-type. */
    private MockMultipartFile image(int width, int height, String type) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, type.equals("image/png") ? "png" : "jpg", out);
        return new MockMultipartFile("file", "photo." + (type.equals("image/png") ? "png" : "jpg"),
                type, out.toByteArray());
    }

    @Test
    void acceptsAPortraitPhotoOfSufficientResolution() throws Exception {
        String path = service().store(image(600, 800, "image/jpeg"), 42L, null);

        assertThat(path).startsWith("photos/42/").endsWith(".jpg");
    }

    @Test
    void acceptsPng() throws Exception {
        String path = service().store(image(900, 1200, "image/png"), 42L, null);
        assertThat(path).endsWith(".png");
    }

    @Test
    void rejectsTooSmall_becausePrintWouldBePoor() throws Exception {
        assertThatThrownBy(() -> service().store(image(300, 400, "image/jpeg"), 42L, null))
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("trop petite");
    }

    @Test
    void rejectsLandscape_becauseACredentialPhotoIsPortrait() throws Exception {
        assertThatThrownBy(() -> service().store(image(1200, 800, "image/jpeg"), 42L, null))
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("portrait");
    }

    @Test
    void rejectsSquare_itIsNotAnIdentityPhotoShape() throws Exception {
        assertThatThrownBy(() -> service().store(image(900, 900, "image/jpeg"), 42L, null))
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("portrait");
    }

    @Test
    void rejectsNonImage_evenWhenItClaimsToBeOne() {
        // A declared content-type proves nothing; only decoding does.
        var liar = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", "this is not an image".getBytes());

        assertThatThrownBy(() -> service().store(liar, 42L, null))
                .isInstanceOf(InvalidFileException.class);
    }

    @Test
    void rejectsPdf_aPhotographIsNotADocument() {
        var pdf = new MockMultipartFile(
                "file", "photo.pdf", "application/pdf", "%PDF-1.4".getBytes());

        assertThatThrownBy(() -> service().store(pdf, 42L, null))
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("JPEG ou PNG");
    }

    @Test
    void replacingAPhoto_removesTheOldFile() throws Exception {
        PhotoStorageService svc = service();

        String first = svc.store(image(600, 800, "image/jpeg"), 42L, null);
        assertThat(svc.resolve(first)).exists();

        String second = svc.store(image(600, 800, "image/jpeg"), 42L, first);

        assertThat(svc.resolve(second)).exists();
        assertThat(svc.resolve(first)).doesNotExist();   // superseded, cleaned up
    }

    @Test
    void snapshotForCard_copiesRatherThanMoves() throws Exception {
        PhotoStorageService svc = service();
        String profilePhoto = svc.store(image(600, 800, "image/jpeg"), 42L, null);

        String cardPhoto = svc.snapshotForCard(profilePhoto, 7L);

        // BOTH exist: the card keeps its own copy so a later profile change
        // cannot alter an already-issued credential.
        assertThat(svc.resolve(profilePhoto)).exists();
        assertThat(svc.resolve(cardPhoto)).exists();
        assertThat(cardPhoto).isEqualTo("photos/cards/7.jpg");
    }
}

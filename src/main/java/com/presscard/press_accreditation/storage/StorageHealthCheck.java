package com.presscard.press_accreditation.storage;

import com.presscard.press_accreditation.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Verifies the document store at STARTUP, and refuses to boot if it is unusable.
 *
 * Why fail hard rather than warn: these files are the evidence behind
 * accreditation decisions. An application that starts happily and accepts
 * uploads into a directory that is read-only, missing, or ephemeral produces
 * the worst failure mode available — silent, and discovered only when a
 * rejected candidate asks to see the document they submitted.
 *
 * Better an administrator sees a clear error at deployment than a journalist
 * loses their file at an appeal.
 */
@Component
public class StorageHealthCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StorageHealthCheck.class);

    /** Below this the store is not failing yet, but needs attention. */
    private static final long LOW_SPACE_THRESHOLD_BYTES = 2L * 1024 * 1024 * 1024;  // 2 GB

    private final Path root;

    public StorageHealthCheck(AppProperties props) {
        this.root = Paths.get(props.storage().rootDirectory()).toAbsolutePath().normalize();
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        ensureExists();
        ensureWritable();
        reportCapacity();
    }

    private void ensureExists() {
        if (Files.exists(root)) {
            if (!Files.isDirectory(root)) {
                throw new IllegalStateException(
                        "app.storage.root-directory points at a file, not a directory: " + root);
            }
            return;
        }
        try {
            Files.createDirectories(root);
            log.info("Created document storage directory: {}", root);
        } catch (IOException e) {
            throw new IllegalStateException("""
                    Cannot create the document storage directory: %s

                    Uploaded documents are the evidence behind accreditation
                    decisions and must live on a persistent, backed-up
                    filesystem. Create the directory and grant the service
                    account write access, or correct app.storage.root-directory.
                    """.formatted(root), e);
        }
    }

    /** Prove writability by actually writing — permission bits can lie. */
    private void ensureWritable() {
        Path probe = root.resolve(".write-probe");
        try {
            Files.writeString(probe, "ok");
            Files.delete(probe);
        } catch (IOException e) {
            throw new IllegalStateException("""
                    The document storage directory is not writable: %s

                    The service account must be able to create files there.
                    On Linux:
                        sudo chown -R <service-user> %s
                        sudo chmod 750 %s
                    """.formatted(root, root, root), e);
        }
    }

    private void reportCapacity() throws IOException {
        FileStore store = Files.getFileStore(root);
        long usable = store.getUsableSpace();
        long total = store.getTotalSpace();
        int usedPercent = total == 0 ? 0 : (int) (100 - (usable * 100 / total));

        String summary = "Document storage: %s (%s, %d%% used, %.1f GB free)"
                .formatted(root, store.type(), usedPercent, usable / 1073741824.0);

        if (usable < LOW_SPACE_THRESHOLD_BYTES) {
            log.error("{} — LOW DISK SPACE. Uploads will start failing. "
                    + "Free space or extend the volume.", summary);
        } else {
            log.info(summary);
        }

        // tmpfs/overlay almost always means a container with no mounted
        // volume: everything written here dies with the container.
        String type = store.type().toLowerCase();
        if (type.contains("tmpfs") || type.contains("overlay") || type.contains("ramfs")) {
            log.error("""

                    ==========================================================
                      WARNING - EPHEMERAL STORAGE DETECTED
                    ==========================================================
                      {} sits on a '{}' filesystem.
                      Documents written here are LOST when the container or
                      machine restarts.

                      Mount a persistent volume at this path BEFORE accepting
                      any real candidature.
                    ==========================================================
                    """, root, store.type());
        }
    }
}

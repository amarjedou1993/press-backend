package com.presscard.press_accreditation.storage;

import com.presscard.press_accreditation.config.AppProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Exposes document-storage health at /actuator/health so the data centre's
 * monitoring can alert on a filling disk BEFORE uploads start failing —
 * rather than after a candidate cannot submit.
 *
 *   UP                    — plenty of room
 *   UP + lowSpace: true   — under 2 GB, act soon
 *   DOWN                  — under 512 MB, or the directory is unwritable
 *
 * The bean name ("documentStorage") is what appears under
 * health.components in the actuator response.
 */
@Component("documentStorage")
public class StorageHealthIndicator implements HealthIndicator {

    private static final long WARN_BYTES = 2L * 1024 * 1024 * 1024;    // 2 GB
    private static final long CRITICAL_BYTES = 512L * 1024 * 1024;     // 512 MB

    private final Path root;

    public StorageHealthIndicator(AppProperties props) {
        this.root = Paths.get(props.storage().rootDirectory()).toAbsolutePath().normalize();
    }

    @Override
    public Health health() {
        try {
            if (!Files.isDirectory(root) || !Files.isWritable(root)) {
                return Health.down()
                        .withDetail("path", root.toString())
                        .withDetail("reason", "not a writable directory")
                        .build();
            }

            FileStore store = Files.getFileStore(root);
            long usable = store.getUsableSpace();
            long total = store.getTotalSpace();

            Health.Builder builder = usable < CRITICAL_BYTES ? Health.down() : Health.up();

            return builder
                    .withDetail("path", root.toString())
                    .withDetail("filesystem", store.type())
                    .withDetail("freeGb", round(usable / 1073741824.0))
                    .withDetail("totalGb", round(total / 1073741824.0))
                    .withDetail("usedPercent",
                            total == 0 ? 0 : (int) (100 - (usable * 100 / total)))
                    .withDetail("lowSpace", usable < WARN_BYTES)
                    .build();

        } catch (IOException e) {
            return Health.down(e).withDetail("path", root.toString()).build();
        }
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}

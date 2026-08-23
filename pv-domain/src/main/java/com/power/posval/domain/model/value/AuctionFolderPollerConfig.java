package com.power.posval.domain.model.value;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for the AuctionFolderPoller service.
 * Defines the directory layout and polling behaviour for the EPEX CSV feed adapter.
 * {@code defaultTenantId} is present in the domain config record so that the
 * folder poller port interface is self-contained; in the simulator (pv-app)
 * this field will be "default". In a production host, tenant routing must be
 * derived from the filename convention or an out-of-band configuration.
 *
 * <p>Note: this record exists in the domain to make the
 * {@code AuctionFolderPoller} port interface fully typeable without referencing
 * pv-app plumbing.
 * Pattern #3, S5.1, DA-VOL-01.
 */
public record AuctionFolderPollerConfig(
    Path inboxDirectory,
    Path processedDirectory,
    Path failedDirectory,
    Duration pollInterval,
    String filePattern,
    String defaultTenantId
) {
    /** Default poll interval: 30 seconds. */
    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(30);

    /** Default file glob pattern. */
    public static final String DEFAULT_FILE_PATTERN = "*.csv";

    public AuctionFolderPollerConfig {
        Objects.requireNonNull(inboxDirectory, "inboxDirectory");
        Objects.requireNonNull(processedDirectory, "processedDirectory");
        Objects.requireNonNull(failedDirectory, "failedDirectory");
        Objects.requireNonNull(pollInterval, "pollInterval");
        Objects.requireNonNull(filePattern, "filePattern");
        Objects.requireNonNull(defaultTenantId, "defaultTenantId");
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive");
        }
        if (filePattern.isBlank()) {
            throw new IllegalArgumentException("filePattern must not be blank");
        }
        if (defaultTenantId.isBlank()) {
            throw new IllegalArgumentException("defaultTenantId must not be blank");
        }
    }
}

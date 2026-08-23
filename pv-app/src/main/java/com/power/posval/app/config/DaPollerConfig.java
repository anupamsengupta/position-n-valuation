package com.power.posval.app.config;

import com.power.posval.domain.port.service.AuctionFolderPoller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Simulator-scope scheduling configuration for the DA auction folder poller (S9.4, D-14).
 *
 * <p>Active only when {@code pv.da.poller.enabled=true} is set in {@code application.yml}.
 * This {@code @Scheduled} annotation is intentionally limited to {@code pv-app}
 * (simulator-scope). The production host will use its own scheduling mechanism
 * (cron, ECS scheduled task, etc.) and call {@link AuctionFolderPoller#pollOnce()}
 * directly — no Spring scheduler dependency required (D-14).
 *
 * <p>{@code @ConditionalOnProperty} is permitted here because this class is simulator-scope
 * ({@code pv-app} only). It must NOT be used inside library modules (D-13 prohibition on
 * {@code @ConditionalOnProperty} inside {@code pv-domain}, {@code pv-persistence},
 * {@code pv-guice}, etc.).
 *
 * <p>S9.4, S6.3, D-14.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "pv.da.poller.enabled", havingValue = "true")
public class DaPollerConfig {

    private static final Logger log = LoggerFactory.getLogger(DaPollerConfig.class);

    private final AuctionFolderPoller auctionFolderPoller;

    public DaPollerConfig(AuctionFolderPoller auctionFolderPoller) {
        this.auctionFolderPoller = auctionFolderPoller;
    }

    /**
     * Scheduled task that invokes one polling cycle (S9.4, S6.3).
     *
     * <p>The fixed delay starts after the previous execution completes, ensuring that
     * a slow import does not cause poll overlap. Default interval: 60,000 ms (60 s).
     * Override with {@code pv.da.poller.interval-ms} in {@code application.yml}.
     */
    @Scheduled(fixedDelayString = "${pv.da.poller.interval-ms:60000}")
    public void pollAuctionFolder() {
        log.debug("DA auction folder poll triggered");
        try {
            var sessions = auctionFolderPoller.pollOnce();
            if (!sessions.isEmpty()) {
                log.info("DA auction folder poll completed: {} session(s) processed",
                    sessions.size());
            }
        } catch (Exception e) {
            log.error("DA auction folder poll failed with unexpected error", e);
        }
    }
}

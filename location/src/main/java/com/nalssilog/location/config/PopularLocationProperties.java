package com.nalssilog.location.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nalssilog.location.popular")
public record PopularLocationProperties(
        Duration window,
        int limit,
        Duration snapshotInterval
) {

    private static final Duration DEFAULT_WINDOW = Duration.ofDays(30);
    private static final int DEFAULT_LIMIT = 20;
    private static final Duration DEFAULT_SNAPSHOT_INTERVAL = Duration.ofMinutes(10);
    private static final int MAX_LIMIT = 20;

    public PopularLocationProperties {
        if (window == null || window.isZero() || window.isNegative()) {
            window = DEFAULT_WINDOW;
        }

        if (limit < 1) {
            limit = DEFAULT_LIMIT;
        }

        if (limit > MAX_LIMIT) {
            throw new IllegalArgumentException("popular location limit must not exceed " + MAX_LIMIT);
        }

        if (snapshotInterval == null || snapshotInterval.isZero() || snapshotInterval.isNegative()) {
            snapshotInterval = DEFAULT_SNAPSHOT_INTERVAL;
        }
    }
}

package org.example.nfs.config;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Immutable server configuration — single source of truth.
 */
public record NfsServerConfig(
        int port,
        Path rootPath,
        String domain,
        int nobodyUid,
        DataSource dataSource,
        Duration cacheExpiry
) {
    public static NfsServerConfig defaults(Path rootPath, DataSource ds) {
        return new NfsServerConfig(2049, rootPath, "domain.local", 65534, ds, Duration.ofSeconds(60));
    }
}

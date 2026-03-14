package org.example.nfs.idmap;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.dcache.nfs.v4.NfsIdMapping;
import org.example.nfs.config.NfsServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Database-backed NfsIdMapping with two Caffeine {@link LoadingCache} instances
 * for bidirectional lookup.
 *
 * <p>Using {@link LoadingCache} instead of a plain {@link com.github.benmanes.caffeine.cache.Cache}
 * means concurrent requests for the same missing key only trigger one database
 * round-trip: the first caller loads the value while subsequent callers wait for
 * the same future rather than each issuing their own query.
 *
 * <p>Falls back to the nobody UID / principal when the user is not found or
 * the database is unreachable.
 */
public final class DbNfsIdMapping implements NfsIdMapping {

    private static final Logger LOG = LoggerFactory.getLogger(DbNfsIdMapping.class);

    private final NfsServerConfig config;

    /** userCode → userId */
    private final LoadingCache<String, Integer> byCode;

    /** userId → userCode@domain */
    private final LoadingCache<Integer, String> byId;

    public DbNfsIdMapping(NfsServerConfig config) {
        this.config = config;

        byCode = Caffeine.newBuilder()
                .expireAfterWrite(config.cacheExpiry())
                .maximumSize(1_000)
                .build((CacheLoader<String, Integer>) this::loadByCode);

        byId = Caffeine.newBuilder()
                .expireAfterWrite(config.cacheExpiry())
                .maximumSize(1_000)
                .build((CacheLoader<Integer, String>) this::loadById);
    }

    @Override
    public int principalToUid(String principal) {
        var code = stripDomain(principal);
        try {
            return byCode.get(code);
        } catch (Exception e) {
            LOG.warn("ID mapping failed for code={}: {}", code, e.getMessage());
            return config.nobodyUid();
        }
    }

    @Override
    public int principalToGid(String principal) {
        return principalToUid(principal);
    }

    @Override
    public String uidToPrincipal(int uid) {
        try {
            return byId.get(uid);
        } catch (Exception e) {
            LOG.warn("ID mapping failed for uid={}: {}", uid, e.getMessage());
            return "nobody@" + config.domain();
        }
    }

    @Override
    public String gidToPrincipal(int gid) {
        return uidToPrincipal(gid);
    }

    // -------------------------------------------------------------------------
    // Cache loaders
    // -------------------------------------------------------------------------

    private int loadByCode(String code) {
        try (Connection conn = config.dataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT user_id FROM sys_user WHERE user_code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            LOG.warn("DB lookup failed for code={}: {}", code, e.getMessage());
        }
        return config.nobodyUid();
    }

    private String loadById(int uid) {
        try (Connection conn = config.dataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT user_code FROM sys_user WHERE user_id = ?")) {
            ps.setInt(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1) + "@" + config.domain();
                }
            }
        } catch (SQLException e) {
            LOG.warn("DB lookup failed for uid={}: {}", uid, e.getMessage());
        }
        return "nobody@" + config.domain();
    }

    private String stripDomain(String principal) {
        int at = principal.indexOf('@');
        return at >= 0 ? principal.substring(0, at) : principal;
    }
}

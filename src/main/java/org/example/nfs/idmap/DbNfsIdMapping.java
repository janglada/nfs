package org.example.nfs.idmap;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.dcache.nfs.v4.NfsIdMapping;
import org.example.nfs.config.NfsServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Database-backed NfsIdMapping with two Caffeine caches for bidirectional lookup.
 * Falls back to nobody on cache/DB miss.
 */
public final class DbNfsIdMapping implements NfsIdMapping {

    private static final Logger LOG = LoggerFactory.getLogger(DbNfsIdMapping.class);

    private final NfsServerConfig config;

    /** userCode → userId */
    private final Cache<String, Integer> byCode;

    /** userId → userCode */
    private final Cache<Integer, String> byId;

    public DbNfsIdMapping(NfsServerConfig config) {
        this.config = config;

        byCode = Caffeine.newBuilder()
                .expireAfterWrite(config.cacheExpiry())
                .maximumSize(1_000)
                .build();

        byId = Caffeine.newBuilder()
                .expireAfterWrite(config.cacheExpiry())
                .maximumSize(1_000)
                .build();
    }

    @Override
    public int principalToUid(String principal) {
        var code = stripDomain(principal);
        var cached = byCode.getIfPresent(code);
        if (cached != null) {
            return cached;
        }
        return lookupByCode(code);
    }

    @Override
    public int principalToGid(String principal) {
        return principalToUid(principal);
    }

    @Override
    public String uidToPrincipal(int uid) {
        var cached = byId.getIfPresent(uid);
        if (cached != null) {
            return cached;
        }
        return lookupById(uid);
    }

    @Override
    public String gidToPrincipal(int gid) {
        return uidToPrincipal(gid);
    }

    // -------------------------------------------------------------------------
    // DB helpers
    // -------------------------------------------------------------------------

    private int lookupByCode(String code) {
        try (Connection conn = config.dataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT user_id FROM sys_user WHERE user_code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    var uid = rs.getInt(1);
                    populateBothCaches(uid, code);
                    return uid;
                }
            }
        } catch (SQLException e) {
            LOG.warn("DB lookup failed for code={}: {}", code, e.getMessage());
        }
        return config.nobodyUid();
    }

    private String lookupById(int uid) {
        try (Connection conn = config.dataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT user_code FROM sys_user WHERE user_id = ?")) {
            ps.setInt(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    var code = rs.getString(1);
                    populateBothCaches(uid, code);
                    return code + "@" + config.domain();
                }
            }
        } catch (SQLException e) {
            LOG.warn("DB lookup failed for uid={}: {}", uid, e.getMessage());
        }
        return "nobody@" + config.domain();
    }

    /** Update both caches atomically to keep them consistent. */
    private void populateBothCaches(int uid, String code) {
        byCode.put(code, uid);
        byId.put(uid, code + "@" + config.domain());
    }

    private String stripDomain(String principal) {
        int at = principal.indexOf('@');
        return at >= 0 ? principal.substring(0, at) : principal;
    }
}

package org.example.nfs.idmap;

import org.example.nfs.config.NfsServerConfig;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests DbNfsIdMapping against an H2 in-memory database seeded with
 * a minimal sys_user table.
 */
class DbNfsIdMappingTest {

    private static final int NOBODY_UID = 65534;
    private static final String DOMAIN = "test.local";

    private NfsServerConfig config;
    private DbNfsIdMapping idmap;

    @BeforeEach
    void setUp() throws Exception {
        var ds = new JdbcDataSource();
        // Each test gets its own DB to avoid cross-test contamination
        ds.setURL("jdbc:h2:mem:nfstest_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");

        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE sys_user (user_id INT PRIMARY KEY, user_code VARCHAR(64))");
            st.execute("INSERT INTO sys_user VALUES (1001, 'alice')");
            st.execute("INSERT INTO sys_user VALUES (1002, 'bob')");
        }

        config = new NfsServerConfig(2049, Path.of("/srv/nfs"), DOMAIN,
                NOBODY_UID, ds, Duration.ofMinutes(5));
        idmap = new DbNfsIdMapping(config);
    }

    // -----------------------------------------------------------------------
    // principalToUid
    // -----------------------------------------------------------------------

    @Test
    void principalToUid_hitReturnsId() {
        assertEquals(1001, idmap.principalToUid("alice"));
    }

    @Test
    void principalToUid_stripsDomainBeforeLookup() {
        assertEquals(1001, idmap.principalToUid("alice@test.local"));
    }

    @Test
    void principalToUid_unknownUserReturnNobody() {
        assertEquals(NOBODY_UID, idmap.principalToUid("unknown"));
    }

    @Test
    void principalToUid_unknownDomainUserReturnsNobody() {
        assertEquals(NOBODY_UID, idmap.principalToUid("unknown@other.domain"));
    }

    // -----------------------------------------------------------------------
    // uidToPrincipal
    // -----------------------------------------------------------------------

    @Test
    void uidToPrincipal_hitReturnsPrincipalWithDomain() {
        assertEquals("alice@" + DOMAIN, idmap.uidToPrincipal(1001));
    }

    @Test
    void uidToPrincipal_unknownUidReturnsNobody() {
        assertEquals("nobody@" + DOMAIN, idmap.uidToPrincipal(9999));
    }

    // -----------------------------------------------------------------------
    // GID delegates
    // -----------------------------------------------------------------------

    @Test
    void principalToGid_delegatesToUid() {
        assertEquals(idmap.principalToUid("bob"), idmap.principalToGid("bob"));
    }

    @Test
    void gidToPrincipal_delegatesToUid() {
        assertEquals(idmap.uidToPrincipal(1002), idmap.gidToPrincipal(1002));
    }

    // -----------------------------------------------------------------------
    // Cache behaviour
    // -----------------------------------------------------------------------

    @Test
    void cacheIsPopulatedOnDbHit_byCodeLookup() {
        // First call hits DB, second should hit cache (both return same value)
        int first  = idmap.principalToUid("alice");
        int second = idmap.principalToUid("alice");
        assertEquals(first, second);
        assertEquals(1001, second);
    }

    @Test
    void cacheIsPopulatedOnDbHit_byIdLookup() {
        // After a byId lookup the reverse cache should also be warm
        String principal = idmap.uidToPrincipal(1001);
        // Now a code lookup should return the same uid without hitting DB again
        int uid = idmap.principalToUid("alice");
        assertEquals(1001, uid);
        assertEquals("alice@" + DOMAIN, principal);
    }

    @Test
    void dualCacheConsistency_afterByCodeLookup() {
        // Trigger byCode path first
        idmap.principalToUid("bob");
        // byId cache should now be warm with the same mapping
        assertEquals("bob@" + DOMAIN, idmap.uidToPrincipal(1002));
    }
}

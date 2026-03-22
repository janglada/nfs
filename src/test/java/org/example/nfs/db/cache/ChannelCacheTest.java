package org.example.nfs.db.cache;

import org.example.nfs.db.blob.BlobByteChannel;
import org.example.nfs.db.blob.JdbcBlobHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ChannelCache} using H2 in-memory database.
 */
class ChannelCacheTest {

    private static final String JDBC_URL = "jdbc:h2:mem:cachetest;DB_CLOSE_DELAY=-1";

    private Connection setupConn;
    private DataSource dataSource;
    private ChannelCache cache;

    @BeforeEach
    void setUp() throws Exception {
        setupConn = DriverManager.getConnection(JDBC_URL);
        try (Statement st = setupConn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS fs_entry (id BIGINT PRIMARY KEY, data BLOB)");
        }
        // Insert test rows for inodes 1, 2, 3
        for (long id = 1; id <= 3; id++) {
            try (PreparedStatement ps = setupConn.prepareStatement(
                    "MERGE INTO fs_entry (id, data) KEY(id) VALUES (?, x'')")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
        }
        setupConn.commit();

        dataSource = new SingleConnectionDataSource(JDBC_URL);
        cache = new ChannelCache(dataSource, Duration.ofMinutes(1), this::createChannel);
    }

    @AfterEach
    void tearDown() throws Exception {
        cache.close();
        try (Statement st = setupConn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS fs_entry");
        }
        setupConn.commit();
        setupConn.close();
    }

    private BlobByteChannel createChannel(Connection conn, long inodeId) throws IOException {
        JdbcBlobHandle handle = new JdbcBlobHandle(conn, "fs_entry", "data", "id", inodeId);
        return new BlobByteChannel(handle);
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void acquireReturnsSameChannel() throws Exception {
        ChannelCache.ChannelEntry e1 = cache.acquire(1L);
        ChannelCache.ChannelEntry e2 = cache.acquire(1L);
        assertSame(e1, e2, "Same entry should be returned for the same inode");
    }

    @Test
    void acquireDifferentInodesReturnsDifferent() throws Exception {
        ChannelCache.ChannelEntry e1 = cache.acquire(1L);
        ChannelCache.ChannelEntry e2 = cache.acquire(2L);
        assertNotSame(e1, e2);
        assertNotSame(e1.channel(), e2.channel());
    }

    @Test
    void releaseClosesChannel() throws Exception {
        ChannelCache.ChannelEntry entry = cache.acquire(1L);
        assertTrue(entry.channel().isOpen());

        cache.release(1L);
        assertFalse(entry.channel().isOpen());
    }

    @Test
    void releaseIdempotent() throws Exception {
        cache.acquire(1L);
        assertDoesNotThrow(() -> cache.release(1L));
        assertDoesNotThrow(() -> cache.release(1L)); // second release should not throw
    }

    @Test
    void acquireAfterReleaseCreatesNew() throws Exception {
        ChannelCache.ChannelEntry first = cache.acquire(1L);
        cache.release(1L);

        ChannelCache.ChannelEntry second = cache.acquire(1L);
        assertNotSame(first, second, "A new entry should be created after release");
        assertTrue(second.channel().isOpen());
    }

    @Test
    void concurrentAcquireSameInode() throws Exception {
        int threadCount = 10;
        Set<ChannelCache.ChannelEntry> entries = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(threadCount)) {
            for (int i = 0; i < threadCount; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        entries.add(cache.acquire(1L));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) f.get();
        }

        assertEquals(1, entries.size(), "All threads should see the same ChannelEntry for the same inode");
    }

    @Test
    void closeInvalidatesAll() throws Exception {
        ChannelCache.ChannelEntry e1 = cache.acquire(1L);
        ChannelCache.ChannelEntry e2 = cache.acquire(2L);

        cache.close();

        assertFalse(e1.channel().isOpen());
        assertFalse(e2.channel().isOpen());
    }

    // -------------------------------------------------------------------------
    // Helper: minimal DataSource backed by individual DriverManager connections
    // -------------------------------------------------------------------------

    private static class SingleConnectionDataSource implements DataSource {
        private final String url;

        SingleConnectionDataSource(String url) {
            this.url = url;
        }

        @Override
        public Connection getConnection() throws java.sql.SQLException {
            return DriverManager.getConnection(url);
        }

        @Override
        public Connection getConnection(String u, String p) throws java.sql.SQLException {
            return DriverManager.getConnection(url, u, p);
        }

        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) {}
        @Override public void setLoginTimeout(int s) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}

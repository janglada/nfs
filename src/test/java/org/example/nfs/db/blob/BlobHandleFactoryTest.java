package org.example.nfs.db.blob;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BlobHandleFactory} using H2 in-memory database.
 */
class BlobHandleFactoryTest {

    private static final String JDBC_URL = "jdbc:h2:mem:factorytest;DB_CLOSE_DELAY=-1";
    private static final long TEST_ID = 42L;

    private Connection setupConn;

    @BeforeEach
    void setUp() throws Exception {
        setupConn = DriverManager.getConnection(JDBC_URL);
        try (Statement st = setupConn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS fs_entry " +
                       "(id BIGINT PRIMARY KEY, data BLOB, lo_oid BIGINT)");
        }
        try (PreparedStatement ps = setupConn.prepareStatement(
                "MERGE INTO fs_entry (id, data, lo_oid) KEY(id) VALUES (?, NULL, NULL)")) {
            ps.setLong(1, TEST_ID);
            ps.executeUpdate();
        }
        setupConn.commit();
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Statement st = setupConn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS fs_entry");
        }
        setupConn.commit();
        setupConn.close();
    }

    @Test
    void detectsUnknownDatabaseAndUsesJdbc() throws Exception {
        try (Connection conn = DriverManager.getConnection(JDBC_URL)) {
            // H2 is not Postgres or Informix — should fall back to JdbcBlobHandle
            try (BlobHandle handle = BlobHandleFactory.open(conn, TEST_ID)) {
                assertInstanceOf(JdbcBlobHandle.class, handle,
                        "H2 should produce a JdbcBlobHandle");
                assertTrue(handle.isOpen());
            }
        }
    }

    @Test
    void openJdbcExplicit() throws Exception {
        try (Connection conn = DriverManager.getConnection(JDBC_URL);
             BlobHandle handle = BlobHandleFactory.openJdbc(conn, "fs_entry", "data", "id", TEST_ID)) {
            assertInstanceOf(JdbcBlobHandle.class, handle);
            assertTrue(handle.isOpen());
            handle.write(new byte[]{1, 2, 3});
            assertEquals(3L, handle.size());
        }
    }

    @Test
    void detectsDatabaseProductReturnsOtherForH2() throws Exception {
        try (Connection conn = DriverManager.getConnection(JDBC_URL)) {
            // Access package-private method via reflection for verification
            var method = BlobHandleFactory.class.getDeclaredMethod("detectDatabase", Connection.class);
            method.setAccessible(true);
            Object result = method.invoke(null, conn);
            assertEquals("OTHER", result.toString(),
                    "H2 should be detected as OTHER database type");
        }
    }

    @Test
    void createReturnsJdbcHandleForH2() throws Exception {
        try (Connection conn = DriverManager.getConnection(JDBC_URL)) {
            try (BlobHandle handle = BlobHandleFactory.create(conn, TEST_ID, "/test/file")) {
                assertInstanceOf(JdbcBlobHandle.class, handle);
                assertTrue(handle.isOpen());
                handle.write("test content".getBytes());
                assertEquals(12L, handle.size());
            }
        }
    }

    @Test
    void openAndWriteThenReadBack() throws Exception {
        byte[] data = "hello world".getBytes();

        try (Connection conn = DriverManager.getConnection(JDBC_URL);
             BlobHandle handle = BlobHandleFactory.open(conn, TEST_ID)) {
            handle.write(data);
        }

        try (Connection conn = DriverManager.getConnection(JDBC_URL);
             BlobHandle handle = BlobHandleFactory.open(conn, TEST_ID)) {
            handle.seek(0);
            byte[] result = handle.read(data.length);
            assertArrayEquals(data, result);
        }
    }
}

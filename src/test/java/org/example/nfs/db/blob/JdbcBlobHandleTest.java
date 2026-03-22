package org.example.nfs.db.blob;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * Runs the {@link BlobHandleContractTest} contract against {@link JdbcBlobHandle}
 * backed by an H2 in-memory database.
 */
class JdbcBlobHandleTest extends BlobHandleContractTest {

    private static final String JDBC_URL = "jdbc:h2:mem:blobtest;DB_CLOSE_DELAY=-1";
    private static final long TEST_ID = 1L;

    private Connection setupConn;

    @BeforeEach
    void setUp() throws Exception {
        setupConn = DriverManager.getConnection(JDBC_URL);
        try (Statement st = setupConn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS fs_entry (id BIGINT PRIMARY KEY, data BLOB)");
        }
        try (PreparedStatement ps = setupConn.prepareStatement(
                "MERGE INTO fs_entry (id, data) KEY(id) VALUES (?, x'')")) {
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

    @Override
    protected BlobHandle createHandle() throws Exception {
        Connection conn = DriverManager.getConnection(JDBC_URL);
        return new JdbcBlobHandle(conn, "fs_entry", "data", "id", TEST_ID);
    }

    @Override
    protected BlobHandle openHandle() throws Exception {
        Connection conn = DriverManager.getConnection(JDBC_URL);
        return new JdbcBlobHandle(conn, "fs_entry", "data", "id", TEST_ID);
    }
}

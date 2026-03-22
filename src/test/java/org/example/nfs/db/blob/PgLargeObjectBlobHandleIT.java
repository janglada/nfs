package org.example.nfs.db.blob;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Integration test for {@link PgLargeObjectBlobHandle} using Testcontainers.
 */
@Testcontainers
class PgLargeObjectBlobHandleIT extends BlobHandleContractTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    private static final long TEST_ID = 1L;

    private Connection writeConn;

    @BeforeAll
    static void createSchema() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS fs_entry (
                        id      BIGSERIAL PRIMARY KEY,
                        lo_oid  OID
                    )
                    """);
            conn.commit();
        }
    }

    @BeforeEach
    void insertRow() throws Exception {
        writeConn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        writeConn.setAutoCommit(false);
        try (var ps = writeConn.prepareStatement(
                "INSERT INTO fs_entry (id, lo_oid) VALUES (?, NULL) ON CONFLICT (id) DO NOTHING")) {
            ps.setLong(1, TEST_ID);
            ps.executeUpdate();
        }
        writeConn.commit();
        writeConn.close();
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement st = conn.createStatement()) {
            st.execute("DELETE FROM fs_entry WHERE id = " + TEST_ID);
            conn.commit();
        }
    }

    @Override
    protected BlobHandle createHandle() throws Exception {
        Connection conn = DriverManager.getConnection(
                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        return PgLargeObjectBlobHandle.create(conn, TEST_ID, "/test");
    }

    @Override
    protected BlobHandle openHandle() throws Exception {
        Connection conn = DriverManager.getConnection(
                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        return new PgLargeObjectBlobHandle(conn, TEST_ID);
    }
}

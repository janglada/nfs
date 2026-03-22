package org.example.nfs.db.blob;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BlobByteChannel} using H2 + {@link JdbcBlobHandle}.
 */
class BlobByteChannelTest {

    private static final String JDBC_URL = "jdbc:h2:mem:byteChannelTest;DB_CLOSE_DELAY=-1";
    private static final long TEST_ID = 1L;

    private Connection setupConn;

    @BeforeEach
    void setUp() throws Exception {
        setupConn = DriverManager.getConnection(JDBC_URL);
        try (Statement st = setupConn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS fs_entry (id BIGINT PRIMARY KEY, data BLOB)");
        }
        try (PreparedStatement ps = setupConn.prepareStatement(
                "MERGE INTO fs_entry (id, data) KEY(id) VALUES (?, NULL)")) {
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

    private BlobByteChannel openChannel() throws Exception {
        Connection conn = DriverManager.getConnection(JDBC_URL);
        JdbcBlobHandle handle = new JdbcBlobHandle(conn, "fs_entry", "data", "id", TEST_ID);
        return new BlobByteChannel(handle);
    }

    @Test
    void writeByteBuffer() throws Exception {
        byte[] data = "hello channel".getBytes();
        try (BlobByteChannel ch = openChannel()) {
            int written = ch.write(ByteBuffer.wrap(data));
            assertEquals(data.length, written);
            assertEquals(data.length, ch.size());
        }

        try (BlobByteChannel ch = openChannel()) {
            ByteBuffer buf = ByteBuffer.allocate(data.length);
            int read = ch.read(buf);
            assertEquals(data.length, read);
            assertArrayEquals(data, buf.array());
        }
    }

    @Test
    void writeDirectByteBuffer() throws Exception {
        byte[] data = "direct buffer".getBytes();
        ByteBuffer direct = ByteBuffer.allocateDirect(data.length);
        direct.put(data);
        direct.flip();

        try (BlobByteChannel ch = openChannel()) {
            int written = ch.write(direct);
            assertEquals(data.length, written);
        }

        try (BlobByteChannel ch = openChannel()) {
            ByteBuffer result = ByteBuffer.allocate(data.length);
            ch.read(result);
            assertArrayEquals(data, result.array());
        }
    }

    @Test
    void readIntoByteBuffer() throws Exception {
        byte[] data = {10, 20, 30, 40, 50};
        try (BlobByteChannel ch = openChannel()) {
            ch.write(ByteBuffer.wrap(data));
        }

        try (BlobByteChannel ch = openChannel()) {
            ByteBuffer buf = ByteBuffer.allocate(5);
            int read = ch.read(buf);
            assertEquals(5, read);
            assertArrayEquals(data, buf.array());
        }
    }

    @Test
    void positionSeek() throws Exception {
        byte[] data = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        try (BlobByteChannel ch = openChannel()) {
            ch.write(ByteBuffer.wrap(data));
        }

        try (BlobByteChannel ch = openChannel()) {
            ch.position(5);
            assertEquals(5L, ch.position());
            ByteBuffer buf = ByteBuffer.allocate(5);
            ch.read(buf);
            assertArrayEquals(new byte[]{6, 7, 8, 9, 10}, buf.array());
        }
    }

    @Test
    void closePropagates() throws Exception {
        Connection conn = DriverManager.getConnection(JDBC_URL);
        JdbcBlobHandle handle = new JdbcBlobHandle(conn, "fs_entry", "data", "id", TEST_ID);
        BlobByteChannel ch = new BlobByteChannel(handle);

        assertTrue(ch.isOpen());
        ch.close();
        assertFalse(ch.isOpen());
        assertFalse(handle.isOpen());
    }

    @Test
    void closedChannelThrows() throws Exception {
        BlobByteChannel ch = openChannel();
        ch.close();

        assertThrows(ClosedChannelException.class, () -> ch.read(ByteBuffer.allocate(1)));
        assertThrows(ClosedChannelException.class, () -> ch.write(ByteBuffer.allocate(1)));
        assertThrows(ClosedChannelException.class, () -> ch.position());
        assertThrows(ClosedChannelException.class, () -> ch.position(0));
        assertThrows(ClosedChannelException.class, () -> ch.size());
        assertThrows(ClosedChannelException.class, () -> ch.truncate(0));
    }

    @Test
    void readReturnsMinusOneAtEof() throws Exception {
        byte[] data = {1, 2, 3};
        try (BlobByteChannel ch = openChannel()) {
            ch.write(ByteBuffer.wrap(data));
        }

        try (BlobByteChannel ch = openChannel()) {
            ch.position(3); // at EOF
            ByteBuffer buf = ByteBuffer.allocate(1);
            int read = ch.read(buf);
            assertEquals(-1, read);
        }
    }

    @Test
    void truncateChannel() throws Exception {
        byte[] data = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        try (BlobByteChannel ch = openChannel()) {
            ch.write(ByteBuffer.wrap(data));
        }

        try (BlobByteChannel ch = openChannel()) {
            ch.truncate(5);
            assertEquals(5L, ch.size());
        }
    }
}

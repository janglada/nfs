package org.example.nfs.db.blob;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * Pure-JDBC {@link BlobHandle} implementation.
 *
 * <p>Uses an in-memory byte buffer for all read/write operations.
 * On {@link #close()}, the buffer is flushed to the database via
 * {@code UPDATE … SET data = ?}, then the connection is committed.
 *
 * <p>This approach is portable across all JDBC drivers (H2, PostgreSQL,
 * Informix, Oracle, …) without relying on driver-specific Blob behaviour.
 */
public final class JdbcBlobHandle implements BlobHandle {

    private static final int INITIAL_CAPACITY = 4096;

    private final Connection conn;
    private final String table;
    private final String blobColumn;
    private final String pkColumn;
    private final Object pkValue;

    /** In-memory buffer holding the current blob contents. */
    private byte[] buf;
    /** Number of valid bytes in {@code buf}. */
    private int size;
    /** Current read/write position. */
    private long position;
    private boolean open;

    /**
     * Opens a BLOB handle by loading the current blob content into memory.
     *
     * @param conn       JDBC connection (auto-commit will be set to false)
     * @param table      table name containing the BLOB column
     * @param blobColumn name of the BLOB column
     * @param pkColumn   name of the primary-key column
     * @param pkValue    primary-key value (used with {@code setObject})
     */
    public JdbcBlobHandle(Connection conn, String table, String blobColumn,
                          String pkColumn, Object pkValue) throws IOException {
        this.conn = conn;
        this.table = table;
        this.blobColumn = blobColumn;
        this.pkColumn = pkColumn;
        this.pkValue = pkValue;
        this.position = 0;
        this.open = true;

        try {
            conn.setAutoCommit(false);
            byte[] existing = fetchBytes();
            if (existing == null) {
                this.buf = new byte[INITIAL_CAPACITY];
                this.size = 0;
            } else {
                this.buf = existing.length > 0 ? existing : new byte[INITIAL_CAPACITY];
                this.size = existing.length;
            }
        } catch (SQLException e) {
            throw new IOException("Failed to open BLOB handle", e);
        }
    }

    // -------------------------------------------------------------------------
    // BlobHandle
    // -------------------------------------------------------------------------

    @Override
    public int write(byte[] data, int offset, int length) throws IOException {
        ensureOpen();
        long end = position + length;
        if (end > Integer.MAX_VALUE) {
            throw new IOException("Blob too large");
        }
        int newSize = (int) end;
        if (newSize > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(newSize, buf.length * 2));
        }
        System.arraycopy(data, offset, buf, (int) position, length);
        position += length;
        if ((int) position > size) {
            size = (int) position;
        }
        return length;
    }

    @Override
    public byte[] read(int len) throws IOException {
        ensureOpen();
        long available = size - position;
        if (available <= 0) {
            return new byte[0];
        }
        int toRead = (int) Math.min(len, available);
        byte[] data = Arrays.copyOfRange(buf, (int) position, (int) position + toRead);
        position += toRead;
        return data;
    }

    @Override
    public void seek(long offset) throws IOException {
        ensureOpen();
        if (offset < 0) {
            throw new IOException("Negative seek offset: " + offset);
        }
        position = offset;
    }

    @Override
    public long tell() throws IOException {
        ensureOpen();
        return position;
    }

    @Override
    public long size() throws IOException {
        ensureOpen();
        return size;
    }

    @Override
    public void truncate(long newSize) throws IOException {
        ensureOpen();
        if (newSize < 0) {
            throw new IOException("Negative truncate size: " + newSize);
        }
        int sz = (int) Math.min(newSize, Integer.MAX_VALUE);
        if (sz < size) {
            // Zero out the truncated portion so reads return 0 bytes
            Arrays.fill(buf, sz, size, (byte) 0);
            size = sz;
        }
        if (position > size) {
            position = size;
        }
    }

    @Override
    public void close() throws IOException {
        if (!open) {
            return;
        }
        open = false;
        try {
            flushToRow();
            conn.commit();
        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException re) {
                e.addSuppressed(re);
            }
            throw new IOException("BLOB close/commit failed", e);
        }
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private byte[] fetchBytes() throws SQLException {
        String sql = "SELECT " + blobColumn + " FROM " + table + " WHERE " + pkColumn + " = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, pkValue);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("No row found for " + pkColumn + " = " + pkValue);
                }
                return rs.getBytes(blobColumn); // returns null if column is NULL
            }
        }
    }

    private void flushToRow() throws SQLException {
        String sql = "UPDATE " + table + " SET " + blobColumn + " = ? WHERE " + pkColumn + " = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBytes(1, Arrays.copyOf(buf, size));
            ps.setObject(2, pkValue);
            ps.executeUpdate();
        }
    }

    private void ensureOpen() throws IOException {
        if (!open) {
            throw new IOException("BlobHandle is closed");
        }
    }
}

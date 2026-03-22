package org.example.nfs.db.blob;

import java.io.Closeable;
import java.io.IOException;

/**
 * Abstraction over database BLOB I/O.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link JdbcBlobHandle} — pure JDBC, works with any driver</li>
 *   <li>{@link IfxSmartBlobHandle} — Informix SmartBlob via MethodHandle</li>
 *   <li>{@link PgLargeObjectBlobHandle} — PostgreSQL Large Objects via MethodHandle</li>
 * </ul>
 */
public sealed interface BlobHandle extends Closeable
        permits JdbcBlobHandle, IfxSmartBlobHandle, PgLargeObjectBlobHandle {

    /**
     * Writes {@code length} bytes from {@code data} starting at {@code offset} at
     * the current position, advancing position by the number of bytes written.
     *
     * @return number of bytes written
     */
    int write(byte[] data, int offset, int length) throws IOException;

    /** Convenience: write all bytes in {@code data}. */
    default int write(byte[] data) throws IOException {
        return write(data, 0, data.length);
    }

    /**
     * Reads up to {@code len} bytes starting at the current position.
     * Returns an empty array at EOF.
     */
    byte[] read(int len) throws IOException;

    /** Seeks to the given 0-based offset. */
    void seek(long offset) throws IOException;

    /** Returns the current 0-based position. */
    long tell() throws IOException;

    /** Returns the total size of the BLOB in bytes. */
    long size() throws IOException;

    /**
     * Truncates the BLOB to the given size. If the current position is past the
     * new end it is clamped to {@code size}.
     */
    void truncate(long size) throws IOException;

    /**
     * Commits and closes this handle. May be called multiple times (idempotent).
     */
    @Override
    void close() throws IOException;

    /** Returns {@code true} if this handle has not been closed. */
    boolean isOpen();
}

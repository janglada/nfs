package org.example.nfs.db.blob;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Informix SmartBlob {@link BlobHandle} implementation.
 *
 * <p>All Informix driver classes are accessed via {@link MethodHandle} to avoid
 * compile-time dependency on {@code ifxjdbc.jar}. If the driver is absent, the
 * static initializer will have set {@code HOLDER} to {@code null} and
 * {@link #isDriverAvailable()} returns {@code false}.
 *
 * <p>Constants:
 * <ul>
 *   <li>{@code LO_RDWR = 0x03}</li>
 *   <li>{@code SEEK_SET = 0}, {@code SEEK_END = 2}</li>
 * </ul>
 */
public final class IfxSmartBlobHandle implements BlobHandle {

    // Informix SmartBlob constants
    private static final int LO_RDWR  = 0x03;
    private static final int SEEK_SET = 0;
    private static final int SEEK_END = 2;

    // -------------------------------------------------------------------------
    // MethodHandle holder — resolved once at class load
    // -------------------------------------------------------------------------

    private record Holder(
            Object   smartBlobInstance,   // not reused — each handle creates its own
            MethodHandle loOpen,          // IfxSmartBlob.IfxLoOpen(byte[], int) → int
            MethodHandle loWrite,         // IfxSmartBlob.IfxLoWrite(int, byte[]) → int
            MethodHandle loRead,          // IfxSmartBlob.IfxLoRead(int, int) → byte[]
            MethodHandle loSeek,          // IfxSmartBlob.IfxLoSeek(int, long, int) → long
            MethodHandle loTell,          // IfxSmartBlob.IfxLoTell(int) → long
            MethodHandle loTruncate,      // IfxSmartBlob.IfxLoTruncate(int, long) → void
            MethodHandle loClose,         // IfxSmartBlob.IfxLoClose(int) → void
            MethodHandle constructor       // new IfxSmartBlob(Connection)
    ) {}

    private static final Holder HOLDER;

    static {
        Holder h = null;
        try {
            Class<?> clazz = Class.forName("com.informix.jdbc.IfxSmartBlob");
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodHandles.Lookup privateLookup = MethodHandles.privateLookupIn(clazz,
                    MethodHandles.lookup());

            MethodHandle ctor = privateLookup.findConstructor(clazz,
                    MethodType.methodType(void.class, Connection.class));
            MethodHandle loOpen = privateLookup.findVirtual(clazz, "IfxLoOpen",
                    MethodType.methodType(int.class, byte[].class, int.class));
            MethodHandle loWrite = privateLookup.findVirtual(clazz, "IfxLoWrite",
                    MethodType.methodType(int.class, int.class, byte[].class));
            MethodHandle loRead = privateLookup.findVirtual(clazz, "IfxLoRead",
                    MethodType.methodType(byte[].class, int.class, int.class));
            MethodHandle loSeek = privateLookup.findVirtual(clazz, "IfxLoSeek",
                    MethodType.methodType(long.class, int.class, long.class, int.class));
            MethodHandle loTell = privateLookup.findVirtual(clazz, "IfxLoTell",
                    MethodType.methodType(long.class, int.class));
            MethodHandle loTruncate = privateLookup.findVirtual(clazz, "IfxLoTruncate",
                    MethodType.methodType(void.class, int.class, long.class));
            MethodHandle loClose = privateLookup.findVirtual(clazz, "IfxLoClose",
                    MethodType.methodType(void.class, int.class));

            h = new Holder(null, loOpen, loWrite, loRead, loSeek, loTell, loTruncate, loClose, ctor);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
            // Driver not available — HOLDER stays null
        }
        HOLDER = h;
    }

    // -------------------------------------------------------------------------
    // Instance state
    // -------------------------------------------------------------------------

    private final Connection conn;
    private final Object ifxSmartBlob;
    private int lofd = -1;
    private boolean open;

    /**
     * Opens a SmartBlob handle for the given file entry.
     *
     * @param conn   Informix JDBC connection
     * @param fileId primary key in {@code fs_entry}
     */
    public IfxSmartBlobHandle(Connection conn, long fileId) throws IOException {
        if (HOLDER == null) {
            throw new IOException("Informix driver not available on classpath");
        }
        this.conn = conn;
        try {
            conn.setAutoCommit(false);
            this.ifxSmartBlob = HOLDER.constructor().invoke(conn);
            byte[] locator = fetchLocator(conn, fileId);
            this.lofd = (int) HOLDER.loOpen().invoke(ifxSmartBlob, locator, LO_RDWR);
            this.open = true;
        } catch (IOException | SQLException e) {
            throw new IOException("Failed to open IfxSmartBlob for fileId=" + fileId, e);
        } catch (Throwable e) {
            throw new IOException("Failed to open IfxSmartBlob for fileId=" + fileId, e);
        }
    }

    // -------------------------------------------------------------------------
    // Factory method for new blobs
    // -------------------------------------------------------------------------

    /**
     * Creates a new SmartBlob entry for the given file.
     */
    public static IfxSmartBlobHandle create(Connection conn, long fileId, String path)
            throws IOException {
        if (HOLDER == null) {
            throw new IOException("Informix driver not available on classpath");
        }
        // For new blobs, insert an empty locator then open it
        return new IfxSmartBlobHandle(conn, fileId);
    }

    // -------------------------------------------------------------------------
    // BlobHandle
    // -------------------------------------------------------------------------

    @Override
    public int write(byte[] data, int offset, int length) throws IOException {
        ensureOpen();
        try {
            // IfxLoWrite doesn't support offset/length — copy slice if needed
            byte[] toWrite = (offset == 0 && length == data.length)
                    ? data
                    : java.util.Arrays.copyOfRange(data, offset, offset + length);
            return (int) HOLDER.loWrite().invoke(ifxSmartBlob, lofd, toWrite);
        } catch (Throwable e) {
            throw new IOException("IfxLoWrite failed", e);
        }
    }

    @Override
    public byte[] read(int len) throws IOException {
        ensureOpen();
        try {
            return (byte[]) HOLDER.loRead().invoke(ifxSmartBlob, lofd, len);
        } catch (Throwable e) {
            throw new IOException("IfxLoRead failed", e);
        }
    }

    @Override
    public void seek(long offset) throws IOException {
        ensureOpen();
        if (offset < 0) {
            throw new IOException("Negative seek offset: " + offset);
        }
        try {
            HOLDER.loSeek().invoke(ifxSmartBlob, lofd, offset, SEEK_SET);
        } catch (Throwable e) {
            throw new IOException("IfxLoSeek failed", e);
        }
    }

    @Override
    public long tell() throws IOException {
        ensureOpen();
        try {
            return (long) HOLDER.loTell().invoke(ifxSmartBlob, lofd);
        } catch (Throwable e) {
            throw new IOException("IfxLoTell failed", e);
        }
    }

    @Override
    public long size() throws IOException {
        ensureOpen();
        try {
            long savedPos = (long) HOLDER.loTell().invoke(ifxSmartBlob, lofd);
            HOLDER.loSeek().invoke(ifxSmartBlob, lofd, 0L, SEEK_END);
            long end = (long) HOLDER.loTell().invoke(ifxSmartBlob, lofd);
            HOLDER.loSeek().invoke(ifxSmartBlob, lofd, savedPos, SEEK_SET);
            return end;
        } catch (Throwable e) {
            throw new IOException("IfxSmartBlob size() failed", e);
        }
    }

    @Override
    public void truncate(long size) throws IOException {
        ensureOpen();
        try {
            HOLDER.loTruncate().invoke(ifxSmartBlob, lofd, size);
            long pos = tell();
            if (pos > size) {
                seek(size);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable e) {
            throw new IOException("IfxLoTruncate failed", e);
        }
    }

    @Override
    public void close() throws IOException {
        if (!open) {
            return;
        }
        open = false;
        try {
            if (lofd >= 0) {
                HOLDER.loClose().invoke(ifxSmartBlob, lofd);
            }
            conn.commit();
        } catch (Throwable e) {
            try {
                conn.rollback();
            } catch (SQLException re) {
                if (e instanceof Exception ex) {
                    ex.addSuppressed(re);
                }
            }
            throw new IOException("IfxSmartBlob close failed", e);
        }
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    // -------------------------------------------------------------------------
    // Public utility
    // -------------------------------------------------------------------------

    /** Returns {@code true} if the Informix driver is available on the classpath. */
    public static boolean isDriverAvailable() {
        return HOLDER != null;
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private static byte[] fetchLocator(Connection conn, long fileId) throws SQLException {
        String sql = "SELECT data FROM fs_entry WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, fileId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("No fs_entry row for id=" + fileId);
                }
                return rs.getBytes("data");
            }
        }
    }

    private void ensureOpen() throws IOException {
        if (!open) {
            throw new IOException("IfxSmartBlobHandle is closed");
        }
    }
}

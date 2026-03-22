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
 * PostgreSQL Large Object {@link BlobHandle} implementation.
 *
 * <p>All PostgreSQL driver classes are accessed via {@link MethodHandle} to avoid
 * compile-time dependency on {@code postgresql.jar}. Falls back gracefully when
 * the driver is absent.
 *
 * <p>PG Large Objects require a transaction — auto-commit is disabled in the
 * constructor and re-enabled (via commit) on close.
 */
public final class PgLargeObjectBlobHandle implements BlobHandle {

    // PostgreSQL LargeObjectManager constants
    private static final int SEEK_SET = 0;
    private static final int SEEK_END = 2;
    private static final int READWRITE = 0x00040000 | 0x00020000;

    // -------------------------------------------------------------------------
    // MethodHandle holder
    // -------------------------------------------------------------------------

    private record Holder(
            Class<?>     pgConnectionClass,
            MethodHandle unwrap,          // Connection.unwrap(Class) — standard JDBC
            MethodHandle getLOM,          // PGConnection.getLargeObjectAPI() → LargeObjectManager
            MethodHandle createLO,        // LargeObjectManager.createLO(int) → long
            MethodHandle openLO,          // LargeObjectManager.open(long, int) → LargeObject
            MethodHandle loWrite,         // LargeObject.write(byte[], int, int) → void
            MethodHandle loRead,          // LargeObject.read(int) → byte[]
            MethodHandle loSeek64,        // LargeObject.seek64(long, int) → void
            MethodHandle loTell64,        // LargeObject.tell64() → long
            MethodHandle loTruncate64,    // LargeObject.truncate64(long) → void
            MethodHandle loClose          // LargeObject.close() → void
    ) {}

    private static final Holder HOLDER;

    static {
        Holder h = null;
        try {
            Class<?> pgConnClass = Class.forName("org.postgresql.PGConnection");
            Class<?> lomClass    = Class.forName("org.postgresql.largeobject.LargeObjectManager");
            Class<?> loClass     = Class.forName("org.postgresql.largeobject.LargeObject");

            MethodHandles.Lookup lookup = MethodHandles.lookup();

            // PGConnection.getLargeObjectAPI()
            MethodHandle getLOM = MethodHandles.privateLookupIn(pgConnClass, lookup)
                    .findVirtual(pgConnClass, "getLargeObjectAPI",
                            MethodType.methodType(lomClass));

            // LargeObjectManager methods
            MethodHandles.Lookup lomLookup = MethodHandles.privateLookupIn(lomClass, lookup);
            MethodHandle createLO = lomLookup.findVirtual(lomClass, "createLO",
                    MethodType.methodType(long.class, int.class));
            MethodHandle openLO = lomLookup.findVirtual(lomClass, "open",
                    MethodType.methodType(loClass, long.class, int.class));

            // LargeObject methods
            MethodHandles.Lookup loLookup = MethodHandles.privateLookupIn(loClass, lookup);
            MethodHandle loWrite = loLookup.findVirtual(loClass, "write",
                    MethodType.methodType(void.class, byte[].class, int.class, int.class));
            MethodHandle loRead = loLookup.findVirtual(loClass, "read",
                    MethodType.methodType(byte[].class, int.class));
            MethodHandle loSeek64 = loLookup.findVirtual(loClass, "seek64",
                    MethodType.methodType(void.class, long.class, int.class));
            MethodHandle loTell64 = loLookup.findVirtual(loClass, "tell64",
                    MethodType.methodType(long.class));
            MethodHandle loTruncate64 = loLookup.findVirtual(loClass, "truncate64",
                    MethodType.methodType(void.class, long.class));
            MethodHandle loClose = loLookup.findVirtual(loClass, "close",
                    MethodType.methodType(void.class));

            h = new Holder(pgConnClass, null, getLOM, createLO, openLO,
                    loWrite, loRead, loSeek64, loTell64, loTruncate64, loClose);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
            // Driver not available — HOLDER stays null
        }
        HOLDER = h;
    }

    // -------------------------------------------------------------------------
    // Instance state
    // -------------------------------------------------------------------------

    private final Connection conn;
    private final long oid;
    private Object largeObject; // org.postgresql.largeobject.LargeObject
    private boolean open;

    /**
     * Opens a Large Object handle for the given file entry.
     *
     * @param conn   PostgreSQL JDBC connection (auto-commit disabled)
     * @param fileId primary key in {@code fs_entry} — the {@code lo_oid} column
     *               holds the OID of the Large Object
     */
    public PgLargeObjectBlobHandle(Connection conn, long fileId) throws IOException {
        if (HOLDER == null) {
            throw new IOException("PostgreSQL driver not available on classpath");
        }
        this.conn = conn;
        try {
            conn.setAutoCommit(false);
            this.oid = fetchOid(conn, fileId);
            Object pgConn = conn.unwrap(HOLDER.pgConnectionClass());
            Object lom = HOLDER.getLOM().invoke(pgConn);
            this.largeObject = HOLDER.openLO().invoke(lom, oid, READWRITE);
            this.open = true;
        } catch (IOException | SQLException e) {
            throw new IOException("Failed to open PG Large Object for fileId=" + fileId, e);
        } catch (Throwable e) {
            throw new IOException("Failed to open PG Large Object for fileId=" + fileId, e);
        }
    }

    /** Private constructor used by {@link #create}. */
    private PgLargeObjectBlobHandle(Connection conn, long oid, Object largeObject) {
        this.conn = conn;
        this.oid = oid;
        this.largeObject = largeObject;
        this.open = true;
    }

    // -------------------------------------------------------------------------
    // Factory method for new Large Objects
    // -------------------------------------------------------------------------

    /**
     * Creates a new PostgreSQL Large Object and records its OID in {@code fs_entry}.
     */
    public static PgLargeObjectBlobHandle create(Connection conn, long fileId, String path)
            throws IOException {
        if (HOLDER == null) {
            throw new IOException("PostgreSQL driver not available on classpath");
        }
        try {
            conn.setAutoCommit(false);
            Object pgConn = conn.unwrap(HOLDER.pgConnectionClass());
            Object lom = HOLDER.getLOM().invoke(pgConn);
            long newOid = (long) HOLDER.createLO().invoke(lom, READWRITE);
            Object lo = HOLDER.openLO().invoke(lom, newOid, READWRITE);

            // Persist the OID
            String sql = "UPDATE fs_entry SET lo_oid = ? WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, newOid);
                ps.setLong(2, fileId);
                ps.executeUpdate();
            }

            return new PgLargeObjectBlobHandle(conn, newOid, lo);
        } catch (IOException | SQLException e) {
            throw new IOException("Failed to create PG Large Object for fileId=" + fileId, e);
        } catch (Throwable e) {
            throw new IOException("Failed to create PG Large Object for fileId=" + fileId, e);
        }
    }

    // -------------------------------------------------------------------------
    // BlobHandle
    // -------------------------------------------------------------------------

    @Override
    public int write(byte[] data, int offset, int length) throws IOException {
        ensureOpen();
        try {
            HOLDER.loWrite().invoke(largeObject, data, offset, length);
            return length;
        } catch (Throwable e) {
            throw new IOException("PG LargeObject write failed", e);
        }
    }

    @Override
    public byte[] read(int len) throws IOException {
        ensureOpen();
        try {
            return (byte[]) HOLDER.loRead().invoke(largeObject, len);
        } catch (Throwable e) {
            throw new IOException("PG LargeObject read failed", e);
        }
    }

    @Override
    public void seek(long offset) throws IOException {
        ensureOpen();
        if (offset < 0) {
            throw new IOException("Negative seek offset: " + offset);
        }
        try {
            HOLDER.loSeek64().invoke(largeObject, offset, SEEK_SET);
        } catch (Throwable e) {
            throw new IOException("PG LargeObject seek failed", e);
        }
    }

    @Override
    public long tell() throws IOException {
        ensureOpen();
        try {
            return (long) HOLDER.loTell64().invoke(largeObject);
        } catch (Throwable e) {
            throw new IOException("PG LargeObject tell failed", e);
        }
    }

    @Override
    public long size() throws IOException {
        ensureOpen();
        try {
            long savedPos = (long) HOLDER.loTell64().invoke(largeObject);
            HOLDER.loSeek64().invoke(largeObject, 0L, SEEK_END);
            long end = (long) HOLDER.loTell64().invoke(largeObject);
            HOLDER.loSeek64().invoke(largeObject, savedPos, SEEK_SET);
            return end;
        } catch (Throwable e) {
            throw new IOException("PG LargeObject size() failed", e);
        }
    }

    @Override
    public void truncate(long size) throws IOException {
        ensureOpen();
        try {
            HOLDER.loTruncate64().invoke(largeObject, size);
            long pos = tell();
            if (pos > size) {
                seek(size);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable e) {
            throw new IOException("PG LargeObject truncate failed", e);
        }
    }

    @Override
    public void close() throws IOException {
        if (!open) {
            return;
        }
        open = false;
        try {
            HOLDER.loClose().invoke(largeObject);
            conn.commit();
        } catch (Throwable e) {
            try {
                conn.rollback();
            } catch (SQLException re) {
                if (e instanceof Exception ex) {
                    ex.addSuppressed(re);
                }
            }
            throw new IOException("PG LargeObject close failed", e);
        }
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    /** Returns the Large Object OID. */
    public long getOid() {
        return oid;
    }

    /** Returns {@code true} if the PostgreSQL driver is available. */
    public static boolean isDriverAvailable() {
        return HOLDER != null;
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private static long fetchOid(Connection conn, long fileId) throws SQLException {
        String sql = "SELECT lo_oid FROM fs_entry WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, fileId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("No fs_entry row for id=" + fileId);
                }
                return rs.getLong("lo_oid");
            }
        }
    }

    private void ensureOpen() throws IOException {
        if (!open) {
            throw new IOException("PgLargeObjectBlobHandle is closed");
        }
    }
}

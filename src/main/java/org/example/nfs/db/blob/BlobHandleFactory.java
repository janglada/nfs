package org.example.nfs.db.blob;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Runtime-detects the database type and creates the appropriate {@link BlobHandle}.
 *
 * <p>Falls back to {@link JdbcBlobHandle} when native driver-specific
 * implementations are unavailable or fail to initialize.
 */
public final class BlobHandleFactory {

    private static final boolean PG_AVAILABLE;
    private static final boolean IFX_AVAILABLE;

    static {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        PG_AVAILABLE  = isPresent("org.postgresql.PGConnection", cl);
        IFX_AVAILABLE = isPresent("com.informix.jdbc.IfxSmartBlob", cl);
    }

    private enum DbType { POSTGRES, INFORMIX, OTHER }

    private BlobHandleFactory() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Opens an existing BLOB for reading and writing.
     *
     * @param conn   JDBC connection to the target database
     * @param fileId primary key in {@code fs_entry}
     */
    public static BlobHandle open(Connection conn, long fileId) throws IOException {
        DbType type = detectDatabase(conn);
        return switch (type) {
            case POSTGRES -> {
                if (PG_AVAILABLE) {
                    try {
                        yield new PgLargeObjectBlobHandle(conn, fileId);
                    } catch (IOException e) {
                        // Fall back to JDBC
                    }
                }
                yield openJdbc(conn, "fs_entry", "data", "id", fileId);
            }
            case INFORMIX -> {
                if (IFX_AVAILABLE) {
                    try {
                        yield new IfxSmartBlobHandle(conn, fileId);
                    } catch (IOException e) {
                        // Fall back to JDBC
                    }
                }
                yield openJdbc(conn, "fs_entry", "data", "id", fileId);
            }
            case OTHER -> openJdbc(conn, "fs_entry", "data", "id", fileId);
        };
    }

    /**
     * Creates a new, empty BLOB entry.
     *
     * @param conn   JDBC connection to the target database
     * @param fileId primary key in {@code fs_entry}
     * @param path   logical path (used for Informix SmartBlob metadata)
     */
    public static BlobHandle create(Connection conn, long fileId, String path) throws IOException {
        DbType type = detectDatabase(conn);
        return switch (type) {
            case POSTGRES -> {
                if (PG_AVAILABLE) {
                    try {
                        yield PgLargeObjectBlobHandle.create(conn, fileId, path);
                    } catch (IOException e) {
                        // Fall back to JDBC
                    }
                }
                yield openJdbc(conn, "fs_entry", "data", "id", fileId);
            }
            case INFORMIX -> {
                if (IFX_AVAILABLE) {
                    try {
                        yield IfxSmartBlobHandle.create(conn, fileId, path);
                    } catch (IOException e) {
                        // Fall back to JDBC
                    }
                }
                yield openJdbc(conn, "fs_entry", "data", "id", fileId);
            }
            case OTHER -> openJdbc(conn, "fs_entry", "data", "id", fileId);
        };
    }

    /**
     * Explicitly opens a {@link JdbcBlobHandle} without any database detection.
     */
    public static BlobHandle openJdbc(Connection conn, String table, String blobCol,
                                       String pkCol, Object pkVal) throws IOException {
        return new JdbcBlobHandle(conn, table, blobCol, pkCol, pkVal);
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    static DbType detectDatabase(Connection conn) {
        try {
            String product = conn.getMetaData().getDatabaseProductName().toLowerCase();
            if (product.contains("postgres")) return DbType.POSTGRES;
            if (product.contains("informix")) return DbType.INFORMIX;
        } catch (SQLException ignored) {
            // Conservative fallback
        }
        return DbType.OTHER;
    }

    private static boolean isPresent(String className, ClassLoader cl) {
        try {
            Class.forName(className, false, cl);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}

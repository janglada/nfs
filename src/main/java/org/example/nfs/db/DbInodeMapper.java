package org.example.nfs.db;

import org.dcache.nfs.vfs.Inode;

/**
 * Bidirectional mapping between nfs4j {@link Inode} file handles and
 * database {@code long} file IDs.
 *
 * <p>Strategy: encode the 64-bit file ID as 8 bytes big-endian and pass them
 * to {@link Inode#forFile(byte[])}. The mapping is deterministic and requires
 * no state.
 */
public final class DbInodeMapper {

    /** The file ID of the root directory in {@code fs_entry}. */
    public static final long ROOT_ID = 1L;

    /** The root {@link Inode} (always ID=1). */
    public static final Inode ROOT = toInode(ROOT_ID);

    private DbInodeMapper() {}

    /**
     * Encodes a database file ID as an NFS inode handle.
     *
     * @param fileId 64-bit file ID (primary key in {@code fs_entry})
     * @return corresponding {@link Inode}
     */
    public static Inode toInode(long fileId) {
        return Inode.forFile(longToBytes(fileId));
    }

    /**
     * Decodes an NFS inode handle back to a database file ID.
     *
     * @param inode the {@link Inode} whose handle was previously produced by {@link #toInode}
     * @return the original file ID
     * @throws IllegalArgumentException if the handle has an unexpected length
     */
    public static long toFileId(Inode inode) {
        byte[] handle = inode.getFileId();
        if (handle.length != Long.BYTES) {
            throw new IllegalArgumentException(
                    "Unexpected inode handle length: " + handle.length + " (expected " + Long.BYTES + ")");
        }
        return bytesToLong(handle);
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private static byte[] longToBytes(long v) {
        byte[] b = new byte[Long.BYTES];
        for (int i = Long.BYTES - 1; i >= 0; i--) {
            b[i] = (byte) (v & 0xFF);
            v >>= 8;
        }
        return b;
    }

    private static long bytesToLong(byte[] b) {
        long v = 0;
        for (int i = 0; i < Long.BYTES; i++) {
            v = (v << 8) | (b[i] & 0xFF);
        }
        return v;
    }
}

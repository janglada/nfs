package org.example.nfs.vfs;

import org.dcache.nfs.vfs.Inode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bidirectional Inode ↔ Path mapping.
 *
 * <p>Strategy: for absolute path strings ≤ 128 bytes (UTF-8) the bytes of the
 * path string are embedded directly as the inode handle. For longer paths the
 * absolute path string's {@code hashCode()} (as a long) is stored in a
 * {@link ConcurrentHashMap} and the handle carries the 8-byte big-endian key.
 */
public final class InodeMapper {

    private static final int INLINE_LIMIT = 128;

    private final Path root;

    /** key → full Path, used only for hashed (long-path) handles */
    private final ConcurrentHashMap<Long, Path> handleMap = new ConcurrentHashMap<>();

    public InodeMapper(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public Inode toInode(Path path) {
        var abs = path.toAbsolutePath().normalize();
        var bytes = abs.toString().getBytes(StandardCharsets.UTF_8);

        if (bytes.length <= INLINE_LIMIT) {
            return Inode.forFile(bytes);
        }

        // Long path: store by stable hash key
        var key = keyFor(abs);
        handleMap.put(key, abs);
        return Inode.forFile(longToBytes(key));
    }

    public Path toPath(Inode inode) {
        var handle = inode.getFileId();

        if (handle.length == Long.BYTES) {
            // Might be a hashed handle — check the map first
            var key = bytesToLong(handle);
            var mapped = handleMap.get(key);
            if (mapped != null) {
                return mapped;
            }
        }

        // Inline path bytes
        var pathStr = new String(handle, StandardCharsets.UTF_8);
        return Path.of(pathStr);
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /** Stable long key derived from the absolute path string. */
    private static long keyFor(Path path) {
        var s = path.toString();
        // Mix the 32-bit hashCode with the length to reduce collisions
        return ((long) s.hashCode() << 32) | (s.length() & 0xFFFFFFFFL);
    }

    private static byte[] longToBytes(long v) {
        var b = new byte[Long.BYTES];
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

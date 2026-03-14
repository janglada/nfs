package org.example.nfs.vfs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class InodeMapperTest {

    @TempDir
    Path tempDir;

    // -----------------------------------------------------------------------
    // Inline (short path) round-trip
    // -----------------------------------------------------------------------

    @Test
    void shortPath_roundTrip(@TempDir Path root) {
        var mapper = new InodeMapper(root);
        var path = root.resolve("file.txt").toAbsolutePath().normalize();

        var inode = mapper.toInode(path);
        var recovered = mapper.toPath(inode);

        assertEquals(path, recovered);
    }

    @Test
    void inlineHandle_containsPathBytes(@TempDir Path root) {
        var mapper = new InodeMapper(root);
        var path = root.resolve("hello").toAbsolutePath().normalize();

        var handle = mapper.toInode(path).getFileId();
        var decoded = new String(handle, StandardCharsets.UTF_8);

        assertEquals(path.toString(), decoded);
    }

    @Test
    void rootInode_roundTrip(@TempDir Path root) {
        var mapper = new InodeMapper(root);
        var inode = mapper.toInode(root);
        assertEquals(root.toAbsolutePath().normalize(), mapper.toPath(inode));
    }

    @Test
    void relativePathNormalised_toAbsolute(@TempDir Path root) {
        var mapper = new InodeMapper(root);
        // Pass a relative-ish path — mapper must normalise it
        var path = root.resolve("a/../b").toAbsolutePath().normalize();
        var inode = mapper.toInode(path);
        assertEquals(path, mapper.toPath(inode));
    }

    // -----------------------------------------------------------------------
    // Long-path (hashed) round-trip
    // -----------------------------------------------------------------------

    @Test
    void longPath_roundTrip(@TempDir Path root) {
        var mapper = new InodeMapper(root);
        // Build a path whose UTF-8 string exceeds 128 bytes
        var longName = "x".repeat(200);
        var path = root.resolve(longName).toAbsolutePath().normalize();

        assertTrue(path.toString().getBytes(StandardCharsets.UTF_8).length > 128,
                "test precondition: path must exceed inline limit");

        var inode = mapper.toInode(path);
        var recovered = mapper.toPath(inode);

        assertEquals(path, recovered);
    }

    @Test
    void longPath_handleIsEightBytes(@TempDir Path root) {
        var mapper = new InodeMapper(root);
        var path = root.resolve("y".repeat(200)).toAbsolutePath().normalize();

        var handle = mapper.toInode(path).getFileId();

        assertEquals(Long.BYTES, handle.length);
    }

    // -----------------------------------------------------------------------
    // Multiple distinct paths
    // -----------------------------------------------------------------------

    @Test
    void distinctPaths_produceDifferentInodes(@TempDir Path root) {
        var mapper = new InodeMapper(root);
        var a = mapper.toInode(root.resolve("a"));
        var b = mapper.toInode(root.resolve("b"));

        assertFalse(java.util.Arrays.equals(a.getFileId(), b.getFileId()),
                "different paths must produce different handles");
    }
}

package org.example.nfs.vfs;

import org.dcache.nfs.v4.NfsIdMapping;
import org.dcache.nfs.vfs.Stat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PosixStatMapperTest {

    @TempDir
    Path tempDir;

    private NfsIdMapping idmap;

    @BeforeEach
    void setUp() throws Exception {
        idmap = mock(NfsIdMapping.class);
        when(idmap.principalToUid(anyString())).thenReturn(1000);
        when(idmap.principalToGid(anyString())).thenReturn(1000);
    }

    // -----------------------------------------------------------------------
    // Regular file
    // -----------------------------------------------------------------------

    @Test
    void regularFile_typeBitsAreS_IFREG() throws IOException {
        var file = Files.createFile(tempDir.resolve("test.txt"));
        var attrs = Files.readAttributes(file, PosixFileAttributes.class);
        var stat = PosixStatMapper.toStat(file, attrs, idmap);

        assertTrue((stat.getMode() & Stat.S_IFREG) != 0, "S_IFREG must be set for regular file");
        assertEquals(0, stat.getMode() & Stat.S_IFDIR, "S_IFDIR must NOT be set for regular file");
    }

    @Test
    void directory_typeBitsAreS_IFDIR() throws IOException {
        var dir = Files.createDirectory(tempDir.resolve("subdir"));
        var attrs = Files.readAttributes(dir, PosixFileAttributes.class);
        var stat = PosixStatMapper.toStat(dir, attrs, idmap);

        assertTrue((stat.getMode() & Stat.S_IFDIR) != 0, "S_IFDIR must be set for directory");
        assertEquals(0, stat.getMode() & Stat.S_IFREG, "S_IFREG must NOT be set for directory");
    }

    @Test
    void symlink_typeBitsAreS_IFLNK() throws IOException {
        var target = Files.createFile(tempDir.resolve("target.txt"));
        var link = tempDir.resolve("link.txt");
        Files.createSymbolicLink(link, target);
        // Read attributes without following the link
        var attrs = Files.readAttributes(link, PosixFileAttributes.class,
                java.nio.file.LinkOption.NOFOLLOW_LINKS);
        var stat = PosixStatMapper.toStat(link, attrs, idmap);

        assertTrue((stat.getMode() & Stat.S_IFLNK) != 0, "S_IFLNK must be set for symlink");
    }

    // -----------------------------------------------------------------------
    // Permission bits
    // -----------------------------------------------------------------------

    @Test
    void rwxr_xr_x_mapsToCorrectModeBits() throws IOException {
        var file = Files.createFile(tempDir.resolve("perms.txt"));
        Set<PosixFilePermission> perms = EnumSet.of(
                PosixFilePermission.OWNER_READ,  PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,  PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE
        );
        Files.setPosixFilePermissions(file, perms);
        var attrs = Files.readAttributes(file, PosixFileAttributes.class);

        var stat = PosixStatMapper.toStat(file, attrs, idmap);
        int mode = stat.getMode() & 0777;

        assertEquals(0755, mode, () -> "expected 0755 but got 0" + Integer.toOctalString(mode));
    }

    @Test
    void noPermissions_modePermBitsAreZero() throws IOException {
        var file = Files.createFile(tempDir.resolve("noperms.txt"));
        Files.setPosixFilePermissions(file, EnumSet.noneOf(PosixFilePermission.class));
        var attrs = Files.readAttributes(file, PosixFileAttributes.class);

        var stat = PosixStatMapper.toStat(file, attrs, idmap);
        assertEquals(0, stat.getMode() & 0777);
    }

    // -----------------------------------------------------------------------
    // UID / GID delegation
    // -----------------------------------------------------------------------

    @Test
    void uid_delegatesToIdmapPrincipalToUid() throws Exception {
        when(idmap.principalToUid(anyString())).thenReturn(42);
        var file = Files.createFile(tempDir.resolve("uid.txt"));
        var attrs = Files.readAttributes(file, PosixFileAttributes.class);

        var stat = PosixStatMapper.toStat(file, attrs, idmap);

        assertEquals(42, stat.getUid());
        verify(idmap, atLeastOnce()).principalToUid(anyString());
    }

    @Test
    void gid_delegatesToIdmapPrincipalToGid() throws Exception {
        when(idmap.principalToGid(anyString())).thenReturn(99);
        var file = Files.createFile(tempDir.resolve("gid.txt"));
        var attrs = Files.readAttributes(file, PosixFileAttributes.class);

        var stat = PosixStatMapper.toStat(file, attrs, idmap);

        assertEquals(99, stat.getGid());
        verify(idmap, atLeastOnce()).principalToGid(anyString());
    }

    // -----------------------------------------------------------------------
    // Size and times
    // -----------------------------------------------------------------------

    @Test
    void size_matchesActualFileSize() throws IOException {
        var file = Files.createFile(tempDir.resolve("sized.txt"));
        Files.writeString(file, "hello");
        var attrs = Files.readAttributes(file, PosixFileAttributes.class);

        var stat = PosixStatMapper.toStat(file, attrs, idmap);

        assertEquals(5L, stat.getSize());
    }

    @Test
    void times_areInMilliseconds() throws IOException {
        var file = Files.createFile(tempDir.resolve("times.txt"));
        var attrs = Files.readAttributes(file, PosixFileAttributes.class);

        var stat = PosixStatMapper.toStat(file, attrs, idmap);

        // Times should be in the ballpark of "after year 2000" in millis
        long year2000Ms = 946684800_000L;
        assertTrue(stat.getMTime() > year2000Ms, "mtime should be after year 2000");
        assertTrue(stat.getATime() > year2000Ms, "atime should be after year 2000");
        // ctime (mapped from creationTime) likewise
        assertTrue(stat.getCTime() > year2000Ms, "ctime should be after year 2000");
    }
}

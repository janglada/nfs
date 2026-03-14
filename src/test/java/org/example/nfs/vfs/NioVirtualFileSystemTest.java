package org.example.nfs.vfs;

import org.dcache.nfs.v4.NfsIdMapping;
import org.dcache.nfs.vfs.Stat;
import org.dcache.nfs.vfs.VirtualFileSystem.StabilityLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Integration-style tests for {@link NioVirtualFileSystem} using a real
 * temp directory on the local POSIX filesystem.
 */
class NioVirtualFileSystemTest {

    @TempDir
    Path root;

    private NfsIdMapping idmap;
    private NioVirtualFileSystem vfs;

    @BeforeEach
    void setUp() throws Exception {
        idmap = mock(NfsIdMapping.class);
        when(idmap.principalToUid(anyString())).thenReturn(1000);
        when(idmap.principalToGid(anyString())).thenReturn(1000);
        when(idmap.uidToPrincipal(anyInt())).thenReturn("testuser");
        when(idmap.gidToPrincipal(anyInt())).thenReturn("testgroup");

        vfs = new NioVirtualFileSystem(root, idmap);
    }

    @AfterEach
    void tearDown() {
        vfs.close();
    }

    // -----------------------------------------------------------------------
    // Root inode
    // -----------------------------------------------------------------------

    @Test
    void getRootInode_returnsInodeForRoot() throws IOException {
        var rootInode = vfs.getRootInode();
        assertNotNull(rootInode);
        var stat = vfs.getattr(rootInode);
        assertTrue((stat.getMode() & Stat.S_IFDIR) != 0, "root must be a directory");
    }

    // -----------------------------------------------------------------------
    // lookup
    // -----------------------------------------------------------------------

    @Test
    void lookup_existingFile() throws IOException {
        Files.createFile(root.resolve("hello.txt"));
        var rootInode = vfs.getRootInode();

        var inode = vfs.lookup(rootInode, "hello.txt");
        assertNotNull(inode);
        var stat = vfs.getattr(inode);
        assertTrue((stat.getMode() & Stat.S_IFREG) != 0);
    }

    @Test
    void lookup_pathEscapeThrows() throws IOException {
        var rootInode = vfs.getRootInode();
        assertThrows(IOException.class, () -> vfs.lookup(rootInode, "../escape"));
    }

    // -----------------------------------------------------------------------
    // getattr
    // -----------------------------------------------------------------------

    @Test
    void getattr_regularFile_hasCorrectSize() throws IOException {
        var file = root.resolve("data.txt");
        Files.writeString(file, "hello world");
        var inode = vfs.lookup(vfs.getRootInode(), "data.txt");

        var stat = vfs.getattr(inode);

        assertEquals(11L, stat.getSize());
        assertTrue((stat.getMode() & Stat.S_IFREG) != 0);
    }

    @Test
    void getattr_directory_hasDirBit() throws IOException {
        Files.createDirectory(root.resolve("subdir"));
        var inode = vfs.lookup(vfs.getRootInode(), "subdir");

        var stat = vfs.getattr(inode);

        assertTrue((stat.getMode() & Stat.S_IFDIR) != 0);
    }

    // -----------------------------------------------------------------------
    // create
    // -----------------------------------------------------------------------

    @Test
    void create_newFile_existsOnDisk() throws IOException {
        var parent = vfs.getRootInode();
        vfs.create(parent, Stat.Type.REGULAR, "newfile.txt", null, 0644);

        assertTrue(Files.exists(root.resolve("newfile.txt")));
    }

    @Test
    void create_newDirectory_existsOnDisk() throws IOException {
        var parent = vfs.getRootInode();
        vfs.create(parent, Stat.Type.DIRECTORY, "newdir", null, 0755);

        assertTrue(Files.isDirectory(root.resolve("newdir")));
    }

    // -----------------------------------------------------------------------
    // mkdir
    // -----------------------------------------------------------------------

    @Test
    void mkdir_createsDirectory() throws IOException {
        var parent = vfs.getRootInode();
        vfs.mkdir(parent, "mydir", null, 0755);

        assertTrue(Files.isDirectory(root.resolve("mydir")));
    }

    @Test
    void mkdir_returnedInodeIsDirectory() throws IOException {
        var parent = vfs.getRootInode();
        var dirInode = vfs.mkdir(parent, "adir", null, 0755);

        var stat = vfs.getattr(dirInode);
        assertTrue((stat.getMode() & Stat.S_IFDIR) != 0);
    }

    // -----------------------------------------------------------------------
    // write / read
    // -----------------------------------------------------------------------

    @Test
    void writeAndRead_roundTrip() throws IOException {
        var file = root.resolve("rw.txt");
        Files.createFile(file);
        var inode = vfs.lookup(vfs.getRootInode(), "rw.txt");

        // Write "hello"
        var writeData = ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8));
        var result = vfs.write(inode, writeData, 0, StabilityLevel.FILE_SYNC);
        assertEquals(5, result.getBytesWritten());

        // Read back
        var readBuf = ByteBuffer.allocate(16);
        int n = vfs.read(inode, readBuf, 0);
        assertEquals(5, n);

        readBuf.flip();
        var content = StandardCharsets.UTF_8.decode(readBuf).toString();
        assertEquals("hello", content);
    }

    @Test
    void write_atOffset_appendsCorrectly() throws IOException {
        var file = root.resolve("offset.txt");
        Files.writeString(file, "hello");
        var inode = vfs.lookup(vfs.getRootInode(), "offset.txt");

        var data = ByteBuffer.wrap(" world".getBytes(StandardCharsets.UTF_8));
        vfs.write(inode, data, 5, StabilityLevel.FILE_SYNC);

        assertEquals("hello world", Files.readString(file));
    }

    // -----------------------------------------------------------------------
    // list
    // -----------------------------------------------------------------------

    @Test
    void list_returnsAllEntries() throws IOException {
        Files.createFile(root.resolve("a.txt"));
        Files.createFile(root.resolve("b.txt"));
        Files.createDirectory(root.resolve("c"));

        var dirStream = vfs.list(vfs.getRootInode(), new byte[8], 0);
        var names = new ArrayList<String>();
        for (var e : dirStream) names.add(e.getName());

        assertTrue(names.contains("a.txt"));
        assertTrue(names.contains("b.txt"));
        assertTrue(names.contains("c"));
    }

    @Test
    void list_cookieSkipsEarlyEntries() throws IOException {
        Files.createFile(root.resolve("a.txt"));
        Files.createFile(root.resolve("b.txt"));
        Files.createFile(root.resolve("c.txt"));

        var full  = vfs.list(vfs.getRootInode(), new byte[8], 0);
        var paged = vfs.list(vfs.getRootInode(), new byte[8], 1);

        long fullCount = 0; for (var ignored : full)  fullCount++;
        long pagedCount = 0; for (var ignored : paged) pagedCount++;
        assertTrue(pagedCount < fullCount);
    }

    // -----------------------------------------------------------------------
    // remove
    // -----------------------------------------------------------------------

    @Test
    void remove_deletesFile() throws IOException {
        Files.createFile(root.resolve("del.txt"));
        var parent = vfs.getRootInode();

        vfs.remove(parent, "del.txt");

        assertFalse(Files.exists(root.resolve("del.txt")));
    }

    // -----------------------------------------------------------------------
    // move / rename
    // -----------------------------------------------------------------------

    @Test
    void move_renamesFile() throws IOException {
        Files.createFile(root.resolve("old.txt"));
        var parent = vfs.getRootInode();

        vfs.move(parent, "old.txt", parent, "new.txt");

        assertFalse(Files.exists(root.resolve("old.txt")));
        assertTrue(Files.exists(root.resolve("new.txt")));
    }

    @Test
    void move_acrossDirs() throws IOException {
        Files.createDirectory(root.resolve("src"));
        Files.createDirectory(root.resolve("dst"));
        Files.createFile(root.resolve("src/file.txt"));

        var srcDir  = vfs.lookup(vfs.getRootInode(), "src");
        var destDir = vfs.lookup(vfs.getRootInode(), "dst");

        vfs.move(srcDir, "file.txt", destDir, "file.txt");

        assertFalse(Files.exists(root.resolve("src/file.txt")));
        assertTrue(Files.exists(root.resolve("dst/file.txt")));
    }

    // -----------------------------------------------------------------------
    // symlink / readlink
    // -----------------------------------------------------------------------

    @Test
    void symlink_createsLink() throws IOException {
        Files.createFile(root.resolve("target.txt"));
        var parent = vfs.getRootInode();

        vfs.symlink(parent, "link.txt", "target.txt", null, 0);

        assertTrue(Files.isSymbolicLink(root.resolve("link.txt")));
    }

    @Test
    void readlink_returnsLinkTarget() throws IOException {
        var target = root.resolve("target.txt");
        Files.createFile(target);
        Files.createSymbolicLink(root.resolve("link.txt"), target);

        var linkInode = vfs.lookup(vfs.getRootInode(), "link.txt");
        var result = vfs.readlink(linkInode);

        assertEquals(target.toString(), result);
    }

    // -----------------------------------------------------------------------
    // getFsStat
    // -----------------------------------------------------------------------

    @Test
    void getFsStat_returnsPositiveValues() throws IOException {
        var fsStat = vfs.getFsStat();

        assertTrue(fsStat.getTotalSpace() > 0);
        assertTrue(fsStat.getTotalSpace() - fsStat.getUsedSpace() > 0);
        assertTrue(fsStat.getTotalFiles() > 0);
    }

    // -----------------------------------------------------------------------
    // getIdMapper / capabilities
    // -----------------------------------------------------------------------

    @Test
    void getIdMapper_returnsSameInstance() {
        assertSame(idmap, vfs.getIdMapper());
    }

    @Test
    void hasIOLayout_alwaysFalse() throws IOException {
        assertFalse(vfs.hasIOLayout(vfs.getRootInode()));
    }
}

package org.example.nfs.db;

import org.dcache.nfs.v4.NfsIdMapping;
import org.dcache.nfs.vfs.DirectoryStream;
import org.dcache.nfs.vfs.Inode;
import org.dcache.nfs.vfs.Stat;
import org.dcache.nfs.vfs.VirtualFileSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DbVirtualFileSystem} using H2 in-memory database.
 */
class DbVirtualFileSystemTest {

    private static final String JDBC_URL = "jdbc:h2:mem:vfstest;DB_CLOSE_DELAY=-1";

    private Connection setupConn;
    private DbVirtualFileSystem vfs;
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        setupConn = DriverManager.getConnection(JDBC_URL);
        createSchema(setupConn);
        dataSource = new SimpleDataSource(JDBC_URL);
        // Use 1-second idle timeout for tests so eviction is fast
        vfs = new DbVirtualFileSystem(dataSource, new PermissiveIdMapping(), Duration.ofSeconds(1));
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Statement st = setupConn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS fs_entry");
        }
        setupConn.commit();
        setupConn.close();
    }

    // =========================================================================
    // Read / Write
    // =========================================================================

    @Test
    void writeAndReadBack() throws Exception {
        Inode file = createTestFile("test.txt");
        byte[] data = new byte[1024];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i & 0xFF);

        VirtualFileSystem.WriteResult result = vfs.write(file, data, 0, data.length,
                VirtualFileSystem.StabilityLevel.UNSTABLE);
        assertEquals(data.length, result.getBytesWritten());

        vfs.commit(file, 0, data.length);

        byte[] readBuf = new byte[data.length];
        int read = vfs.read(file, readBuf, 0, data.length);
        assertEquals(data.length, read);
        assertArrayEquals(data, readBuf);
    }

    @Test
    void writeAtOffset() throws Exception {
        Inode file = createTestFile("offset.txt");
        byte[] initial = {65, 65, 65, 65}; // AAAA
        byte[] patch   = {66, 66};           // BB

        vfs.write(file, initial, 0, initial.length, VirtualFileSystem.StabilityLevel.UNSTABLE);
        vfs.write(file, patch, 2, patch.length, VirtualFileSystem.StabilityLevel.UNSTABLE);

        vfs.commit(file, 0, 0);

        byte[] result = new byte[4];
        vfs.read(file, result, 0, 4);
        assertArrayEquals(new byte[]{65, 65, 66, 66}, result);
    }

    @Test
    void multipleWritesThenCommit() throws Exception {
        Inode file = createTestFile("multi.txt");
        vfs.write(file, "Hello".getBytes(), 0, 5, VirtualFileSystem.StabilityLevel.UNSTABLE);
        vfs.write(file, ", ".getBytes(), 5, 2, VirtualFileSystem.StabilityLevel.UNSTABLE);
        vfs.write(file, "World".getBytes(), 7, 5, VirtualFileSystem.StabilityLevel.UNSTABLE);

        vfs.commit(file, 0, 0);

        byte[] result = new byte[12];
        int read = vfs.read(file, result, 0, 12);
        assertEquals(12, read);
        assertEquals("Hello, World", new String(result));
    }

    @Test
    void readAtOffset() throws Exception {
        Inode file = createTestFile("readoff.txt");
        byte[] data = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};

        vfs.write(file, data, 0, data.length, VirtualFileSystem.StabilityLevel.UNSTABLE);
        vfs.commit(file, 0, 0);

        byte[] result = new byte[5];
        int read = vfs.read(file, result, 5, 5);
        assertEquals(5, read);
        assertArrayEquals(new byte[]{5, 6, 7, 8, 9}, result);
    }

    @Test
    void readBeyondEof() throws Exception {
        Inode file = createTestFile("eof.txt");
        vfs.write(file, new byte[]{1, 2, 3, 4, 5}, 0, 5, VirtualFileSystem.StabilityLevel.UNSTABLE);
        vfs.commit(file, 0, 0);

        byte[] buf = new byte[10];
        int read = vfs.read(file, buf, 0, 10);
        assertEquals(5, read);
    }

    @Test
    void writeByteBuffer() throws Exception {
        Inode file = createTestFile("buf.txt");
        byte[] data = "ByteBuffer test".getBytes();
        ByteBuffer buf = ByteBuffer.wrap(data);

        VirtualFileSystem.WriteResult result = vfs.write(file, buf, 0,
                VirtualFileSystem.StabilityLevel.UNSTABLE);
        assertEquals(data.length, result.getBytesWritten());

        vfs.commit(file, 0, 0);

        byte[] readBuf = new byte[data.length];
        vfs.read(file, readBuf, 0, data.length);
        assertArrayEquals(data, readBuf);
    }

    // =========================================================================
    // Getattr / Setattr
    // =========================================================================

    @Test
    void getattrReturnsCorrectStat() throws Exception {
        Inode file = createTestFile("attr.txt");
        Stat stat = vfs.getattr(file);

        assertEquals((Stat.S_IFREG | 0644), stat.getMode());
        assertEquals(0, stat.getUid());
        assertEquals(0, stat.getGid());
        assertEquals(0L, stat.getSize()); // empty file
        assertTrue(stat.getIno() > 0);
    }

    @Test
    void getattrForRootIsDirectory() throws Exception {
        Stat stat = vfs.getattr(vfs.getRootInode());
        assertEquals(Stat.S_IFDIR, stat.getMode() & Stat.S_TYPE);
    }

    @Test
    void setattrMode() throws Exception {
        Inode file = createTestFile("setmode.txt");
        Stat update = new Stat();
        update.setMode(0600);

        vfs.setattr(file, update);

        Stat after = vfs.getattr(file);
        assertEquals(Stat.S_IFREG | 0600, after.getMode());
    }

    // =========================================================================
    // Directory operations
    // =========================================================================

    @Test
    void lookupExisting() throws Exception {
        Inode file = createTestFile("lookup.txt");
        Inode found = vfs.lookup(vfs.getRootInode(), "lookup.txt");
        assertArrayEquals(file.getFileId(), found.getFileId());
    }

    @Test
    void lookupNonExistent() {
        assertThrows(IOException.class,
                () -> vfs.lookup(vfs.getRootInode(), "nonexistent.txt"));
    }

    @Test
    void listDirectory() throws Exception {
        createTestFile("a.txt");
        createTestFile("b.txt");
        createTestFile("c.txt");

        DirectoryStream stream = vfs.list(vfs.getRootInode(), DirectoryStream.ZERO_VERIFIER, 0);
        long count = StreamSupport.stream(stream.spliterator(), false)
                .filter(e -> !e.getName().equals(".") && !e.getName().equals(".."))
                .count();
        assertEquals(3, count);
    }

    @Test
    void mkdirAndList() throws Exception {
        Inode dir = vfs.mkdir(vfs.getRootInode(), "mydir", null, 0755);
        assertNotNull(dir);

        Stat stat = vfs.getattr(dir);
        assertEquals(Stat.S_IFDIR, stat.getMode() & Stat.S_TYPE);

        DirectoryStream stream = vfs.list(vfs.getRootInode(), DirectoryStream.ZERO_VERIFIER, 0);
        boolean found = StreamSupport.stream(stream.spliterator(), false)
                .anyMatch(e -> "mydir".equals(e.getName()));
        assertTrue(found, "mydir should appear in parent listing");
    }

    @Test
    void removeFile() throws Exception {
        createTestFile("todelete.txt");
        vfs.remove(vfs.getRootInode(), "todelete.txt");
        assertThrows(IOException.class,
                () -> vfs.lookup(vfs.getRootInode(), "todelete.txt"));
    }

    @Test
    void removeNonEmptyDirThrows() throws Exception {
        vfs.mkdir(vfs.getRootInode(), "nonemptydir", null, 0755);
        Inode subdir = vfs.lookup(vfs.getRootInode(), "nonemptydir");
        vfs.create(subdir, Stat.Type.REGULAR, "child.txt", null, 0644);

        assertThrows(IOException.class,
                () -> vfs.remove(vfs.getRootInode(), "nonemptydir"),
                "Should throw when removing non-empty directory");
    }

    @Test
    void rename() throws Exception {
        createTestFile("old.txt");
        vfs.move(vfs.getRootInode(), "old.txt", vfs.getRootInode(), "new.txt");

        assertThrows(IOException.class,
                () -> vfs.lookup(vfs.getRootInode(), "old.txt"),
                "Old name should not exist");
        Inode found = vfs.lookup(vfs.getRootInode(), "new.txt");
        assertNotNull(found);
    }

    @Test
    void renameOverwrite() throws Exception {
        createTestFile("src.txt");
        createTestFile("dst.txt");

        vfs.move(vfs.getRootInode(), "src.txt", vfs.getRootInode(), "dst.txt");

        assertThrows(IOException.class,
                () -> vfs.lookup(vfs.getRootInode(), "src.txt"));
        assertNotNull(vfs.lookup(vfs.getRootInode(), "dst.txt"));
    }

    @Test
    void symlinkAndReadlink() throws Exception {
        Inode link = vfs.symlink(vfs.getRootInode(), "mylink", "/target/path", null, 0777);
        assertNotNull(link);

        String target = vfs.readlink(link);
        assertEquals("/target/path", target);
    }

    @Test
    void parentOf() throws Exception {
        Inode dir = vfs.mkdir(vfs.getRootInode(), "subdir", null, 0755);
        Inode parent = vfs.parentOf(dir);
        assertArrayEquals(vfs.getRootInode().getFileId(), parent.getFileId());
    }

    @Test
    void rootInode() throws IOException {
        Inode root = vfs.getRootInode();
        assertNotNull(root);
        assertEquals(1L, DbInodeMapper.toFileId(root));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Inode createTestFile(String name) throws IOException {
        return vfs.create(vfs.getRootInode(), Stat.Type.REGULAR, name, null, 0644);
    }

    private static void createSchema(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS fs_entry (
                        id          BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                        parent_id   BIGINT REFERENCES fs_entry(id),
                        name        VARCHAR(255) NOT NULL,
                        entry_type  SMALLINT NOT NULL DEFAULT 0,
                        file_size   BIGINT NOT NULL DEFAULT 0,
                        data        BLOB,
                        lo_oid      BIGINT,
                        owner_uid   INT NOT NULL DEFAULT 0,
                        owner_gid   INT NOT NULL DEFAULT 0,
                        mode        INT NOT NULL DEFAULT 420,
                        created_at  BIGINT NOT NULL,
                        modified_at BIGINT NOT NULL,
                        accessed_at BIGINT NOT NULL,
                        generation  BIGINT NOT NULL DEFAULT 0,
                        version     BIGINT NOT NULL DEFAULT 0,
                        UNIQUE (parent_id, name)
                    )
                    """);
            // Insert root directory (id=1)
            st.execute("""
                    MERGE INTO fs_entry
                        (id, parent_id, name, entry_type, file_size, owner_uid, owner_gid,
                         mode, created_at, modified_at, accessed_at)
                    KEY(id)
                    VALUES (1, NULL, '/', 1, 0, 0, 0, 493, 0, 0, 0)
                    """);
            // Advance the identity sequence past id=1 so subsequent inserts get id>=2
            st.execute("ALTER TABLE fs_entry ALTER COLUMN id RESTART WITH 2");
        }
        conn.commit();
    }

    // =========================================================================
    // Minimal helpers
    // =========================================================================

    private static class PermissiveIdMapping implements NfsIdMapping {
        @Override public String uidToPrincipal(int uid) { return "nobody@domain"; }
        @Override public String gidToPrincipal(int gid) { return "nobody@domain"; }
        @Override public int principalToUid(String p)   { return 0; }
        @Override public int principalToGid(String p)   { return 0; }
    }

    private static class SimpleDataSource implements DataSource {
        private final String url;
        SimpleDataSource(String url) { this.url = url; }

        @Override public Connection getConnection() throws java.sql.SQLException {
            return DriverManager.getConnection(url);
        }
        @Override public Connection getConnection(String u, String p) throws java.sql.SQLException {
            return DriverManager.getConnection(url, u, p);
        }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter w) {}
        @Override public void setLoginTimeout(int s) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> i) { return null; }
        @Override public boolean isWrapperFor(Class<?> i) { return false; }
    }
}

package org.example.nfs.db;

import org.dcache.nfs.v4.NfsIdMapping;
import org.dcache.nfs.v4.xdr.nfsace4;
import org.dcache.nfs.vfs.AclCheckable;
import org.dcache.nfs.vfs.DirectoryEntry;
import org.dcache.nfs.vfs.DirectoryStream;
import org.dcache.nfs.vfs.FsStat;
import org.dcache.nfs.vfs.Inode;
import org.dcache.nfs.vfs.Stat;
import org.dcache.nfs.vfs.VirtualFileSystem;
import org.example.nfs.db.blob.BlobByteChannel;
import org.example.nfs.db.blob.BlobHandleFactory;
import org.example.nfs.db.cache.ChannelCache;

import javax.security.auth.Subject;
import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Database-backed {@link VirtualFileSystem} implementation.
 *
 * <p>Files are stored as BLOBs in the {@code fs_entry} table. Write operations
 * are buffered in a {@link ChannelCache} keyed by inode ID, allowing multiple
 * stateless NFS WRITE RPCs to accumulate into a single BLOB transaction before
 * the client issues a COMMIT or the cache entry is evicted.
 */
public final class DbVirtualFileSystem implements VirtualFileSystem {

    // Entry type constants matching schema
    private static final int TYPE_FILE    = 0;
    private static final int TYPE_DIR     = 1;
    private static final int TYPE_SYMLINK = 2;

    private final DataSource dataSource;
    private final ChannelCache channelCache;
    private final NfsIdMapping idmap;

    /**
     * Creates a new DB-backed VFS.
     *
     * @param dataSource      JDBC datasource for {@code fs_entry} queries
     * @param idmap           NFS ID mapping (uid/gid ↔ principal names)
     * @param channelIdleTimeout how long to keep write channels open when idle
     */
    public DbVirtualFileSystem(DataSource dataSource, NfsIdMapping idmap,
                               Duration channelIdleTimeout) {
        this.dataSource   = dataSource;
        this.idmap        = idmap;
        this.channelCache = new ChannelCache(dataSource, channelIdleTimeout,
                (conn, inodeId) -> {
                    var handle = BlobHandleFactory.openJdbc(conn, "fs_entry", "data", "id", inodeId);
                    return new BlobByteChannel(handle);
                });


        try(Connection conn = dataSource.getConnection()) {

            InputStream sqlStream = VirtualFileSystem.class.getResourceAsStream("/sql/schema-common.sql");
            String sql = new String(sqlStream.readAllBytes(), StandardCharsets.UTF_8);
            System.out.println(sql);
            conn.createStatement().execute(sql);


        } catch (SQLException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Constructor with default 30-second idle timeout. */
    public DbVirtualFileSystem(DataSource dataSource, NfsIdMapping idmap) {
        this(dataSource, idmap, Duration.ofSeconds(30));
    }

    // =========================================================================
    // Inode resolution
    // =========================================================================

    @Override
    public Inode getRootInode() throws IOException {
        return DbInodeMapper.ROOT;
    }

    @Override
    public Inode lookup(Inode parent, String name) throws IOException {
        long parentId = DbInodeMapper.toFileId(parent);

        if (".".equals(name)) return parent;
        if ("..".equals(name)) return parentOf(parent);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM fs_entry WHERE parent_id = ? AND name = ?")) {
            ps.setLong(1, parentId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IOException("No such file or directory: " + name);
                }
                return DbInodeMapper.toInode(rs.getLong("id"));
            }
        } catch (SQLException e) {
            throw new IOException("lookup failed for name=" + name, e);
        }
    }

    @Override
    public Inode parentOf(Inode inode) throws IOException {
        long id = DbInodeMapper.toFileId(inode);
        if (id == DbInodeMapper.ROOT_ID) {
            return DbInodeMapper.ROOT; // root's parent is itself
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT parent_id FROM fs_entry WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IOException("Inode not found: " + id);
                }
                long parentId = rs.getLong("parent_id");
                if (rs.wasNull()) {
                    return DbInodeMapper.ROOT;
                }
                return DbInodeMapper.toInode(parentId);
            }
        } catch (SQLException e) {
            throw new IOException("parentOf failed for id=" + id, e);
        }
    }

    // =========================================================================
    // Attributes
    // =========================================================================

    @Override
    public Stat getattr(Inode inode) throws IOException {
        long id = DbInodeMapper.toFileId(inode);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, entry_type, file_size, owner_uid, owner_gid, mode, " +
                     "created_at, modified_at, accessed_at, generation FROM fs_entry WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IOException("Inode not found: " + id);
                }
                return buildStat(rs);
            }
        } catch (SQLException e) {
            throw new IOException("getattr failed for id=" + id, e);
        }
    }

    @Override
    public void setattr(Inode inode, Stat stat) throws IOException {
        long id = DbInodeMapper.toFileId(inode);
        // Build a partial UPDATE based on defined attributes
        var sb = new StringBuilder("UPDATE fs_entry SET version = version + 1");
        var params = new ArrayList<>();

        if (stat.isDefined(Stat.StatAttribute.MODE)) {
            sb.append(", mode = ?");
            params.add(stat.getMode() & 0777);
        }
        if (stat.isDefined(Stat.StatAttribute.OWNER)) {
            sb.append(", owner_uid = ?");
            params.add(stat.getUid());
        }
        if (stat.isDefined(Stat.StatAttribute.GROUP)) {
            sb.append(", owner_gid = ?");
            params.add(stat.getGid());
        }
        if (stat.isDefined(Stat.StatAttribute.SIZE)) {
            sb.append(", file_size = ?");
            params.add(stat.getSize());
        }
        if (stat.isDefined(Stat.StatAttribute.MTIME)) {
            sb.append(", modified_at = ?");
            params.add(stat.getMTime());
        }
        if (stat.isDefined(Stat.StatAttribute.ATIME)) {
            sb.append(", accessed_at = ?");
            params.add(stat.getATime());
        }
        sb.append(" WHERE id = ?");
        params.add(id);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sb.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            throw new IOException("setattr failed for id=" + id, e);
        }
    }

    // =========================================================================
    // Directory operations
    // =========================================================================

    @Override
    public DirectoryStream list(Inode inode, byte[] verifier, long cookie) throws IOException {
        long parentId = DbInodeMapper.toFileId(inode);
        var entries = new ArrayList<DirectoryEntry>();

        // Add "." and ".."
        if (cookie <= 0) {
            Stat dotStat = getattr(inode);
            entries.add(new DirectoryEntry(".", inode, dotStat, 1));
        }
        if (cookie <= 1) {
            Inode parentInode = parentOf(inode);
            Stat dotDotStat = getattr(parentInode);
            entries.add(new DirectoryEntry("..", parentInode, dotDotStat, 2));
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, name, entry_type, file_size, owner_uid, owner_gid, mode, " +
                     "created_at, modified_at, accessed_at, generation " +
                     "FROM fs_entry WHERE parent_id = ? ORDER BY id")) {
            ps.setLong(1, parentId);
            try (ResultSet rs = ps.executeQuery()) {
                long index = 3;
                while (rs.next()) {
                    if (index <= cookie) {
                        index++;
                        continue;
                    }
                    Inode childInode = DbInodeMapper.toInode(rs.getLong("id"));
                    Stat childStat = buildStat(rs);
                    entries.add(new DirectoryEntry(rs.getString("name"), childInode, childStat, index));
                    index++;
                }
            }
        } catch (SQLException e) {
            throw new IOException("list failed for parentId=" + parentId, e);
        }

        return new DirectoryStream(DirectoryStream.ZERO_VERIFIER, entries);
    }

    @Override
    public byte[] directoryVerifier(Inode inode) throws IOException {
        return DirectoryStream.ZERO_VERIFIER;
    }

    @Override
    public Inode create(Inode parent, Stat.Type type, String name, Subject subject, int mode)
            throws IOException {
        int entryType = (type == Stat.Type.DIRECTORY) ? TYPE_DIR : TYPE_FILE;
        return insertEntry(parent, name, entryType, mode & 0777);
    }

    @Override
    public Inode mkdir(Inode parent, String name, Subject subject, int mode) throws IOException {
        return insertEntry(parent, name, TYPE_DIR, mode & 0777);
    }

    @Override
    public void remove(Inode parent, String name) throws IOException {
        long parentId = DbInodeMapper.toFileId(parent);

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            // Find the entry
            long id;
            int entryType;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, entry_type FROM fs_entry WHERE parent_id = ? AND name = ?")) {
                ps.setLong(1, parentId);
                ps.setString(2, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IOException("No such file or directory: " + name);
                    }
                    id = rs.getLong("id");
                    entryType = rs.getInt("entry_type");
                }
            }

            // If directory, check it's empty
            if (entryType == TYPE_DIR) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM fs_entry WHERE parent_id = ?")) {
                    ps.setLong(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        if (rs.getLong(1) > 0) {
                            throw new IOException("Directory not empty: " + name);
                        }
                    }
                }
            }

            // Remove from channel cache if present
            channelCache.release(id);

            // Delete the entry
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM fs_entry WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            conn.commit();
        } catch (IOException e) {
            throw e;
        } catch (SQLException e) {
            throw new IOException("remove failed for name=" + name, e);
        }
    }

    @Override
    public boolean move(Inode src, String oldName, Inode dest, String newName) throws IOException {
        long srcParentId  = DbInodeMapper.toFileId(src);
        long destParentId = DbInodeMapper.toFileId(dest);

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            // Check if target already exists — remove it if so
            Long existingId = findId(conn, destParentId, newName);
            if (existingId != null) {
                channelCache.release(existingId);
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM fs_entry WHERE id = ?")) {
                    ps.setLong(1, existingId);
                    ps.executeUpdate();
                }
            }

            // Move source to destination
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE fs_entry SET parent_id = ?, name = ? " +
                    "WHERE parent_id = ? AND name = ?")) {
                ps.setLong(1, destParentId);
                ps.setString(2, newName);
                ps.setLong(3, srcParentId);
                ps.setString(4, oldName);
                int rows = ps.executeUpdate();
                if (rows == 0) {
                    throw new IOException("Source not found: " + oldName);
                }
            }
            conn.commit();
        } catch (IOException e) {
            throw e;
        } catch (SQLException e) {
            throw new IOException("move failed", e);
        }
        return true;
    }

    // =========================================================================
    // File I/O
    // =========================================================================

    @Override
    public WriteResult write(Inode inode, byte[] data, long offset, int count,
                             StabilityLevel stability) throws IOException {
        long id = DbInodeMapper.toFileId(inode);
        ChannelCache.ChannelEntry entry = channelCache.acquire(id);
        entry.lock().lock();
        try {
            entry.channel().position(offset);
            ByteBuffer buf = ByteBuffer.wrap(data, 0, count);
            int written = entry.channel().write(buf);
            return new WriteResult(StabilityLevel.UNSTABLE, written);
        } finally {
            entry.lock().unlock();
        }
    }

    @Override
    public WriteResult write(Inode inode, ByteBuffer data, long offset,
                             StabilityLevel stability) throws IOException {
        long id = DbInodeMapper.toFileId(inode);
        ChannelCache.ChannelEntry entry = channelCache.acquire(id);
        entry.lock().lock();
        try {
            entry.channel().position(offset);
            int written = entry.channel().write(data);
            return new WriteResult(StabilityLevel.UNSTABLE, written);
        } finally {
            entry.lock().unlock();
        }
    }

    @Override
    public int read(Inode inode, byte[] data, long offset, int count) throws IOException {
        long id = DbInodeMapper.toFileId(inode);
        ChannelCache.ChannelEntry entry = channelCache.acquire(id);
        entry.lock().lock();
        try {
            entry.channel().position(offset);
            ByteBuffer buf = ByteBuffer.wrap(data, 0, count);
            int bytesRead = entry.channel().read(buf);
            return Math.max(0, bytesRead); // return 0 at EOF instead of -1
        } finally {
            entry.lock().unlock();
        }
    }

    @Override
    public int read(Inode inode, ByteBuffer data, long offset) throws IOException {
        long id = DbInodeMapper.toFileId(inode);
        ChannelCache.ChannelEntry entry = channelCache.acquire(id);
        entry.lock().lock();
        try {
            entry.channel().position(offset);
            int bytesRead = entry.channel().read(data);
            return Math.max(0, bytesRead);
        } finally {
            entry.lock().unlock();
        }
    }

    @Override
    public void commit(Inode inode, long offset, int count) throws IOException {
        channelCache.flush(DbInodeMapper.toFileId(inode));
        // Update file_size after commit
        updateFileSizeFromDb(inode);
    }

    // =========================================================================
    // Links & symlinks
    // =========================================================================

    @Override
    public Inode link(Inode parent, Inode existing, String name, Subject subject)
            throws IOException {
        throw new IOException("Hard links are not supported by DB-backed VFS");
    }

    @Override
    public Inode symlink(Inode parent, String name, String link, Subject subject, int mode)
            throws IOException {
        long parentId = DbInodeMapper.toFileId(parent);
        long now = System.currentTimeMillis();

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            long id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO fs_entry (parent_id, name, entry_type, file_size, data, " +
                    "owner_uid, owner_gid, mode, created_at, modified_at, accessed_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    PreparedStatement.RETURN_GENERATED_KEYS)) {
                byte[] linkBytes = link.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                ps.setLong(1, parentId);
                ps.setString(2, name);
                ps.setInt(3, TYPE_SYMLINK);
                ps.setLong(4, linkBytes.length);
                ps.setBytes(5, linkBytes);
                ps.setInt(6, 0);
                ps.setInt(7, 0);
                ps.setInt(8, mode & 0777);
                ps.setLong(9, now);
                ps.setLong(10, now);
                ps.setLong(11, now);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
            }
            conn.commit();
            return DbInodeMapper.toInode(id);
        } catch (SQLException e) {
            throw new IOException("symlink failed for name=" + name, e);
        }
    }

    @Override
    public String readlink(Inode inode) throws IOException {
        long id = DbInodeMapper.toFileId(inode);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT data FROM fs_entry WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IOException("Symlink inode not found: " + id);
                }
                byte[] data = rs.getBytes("data");
                if (data == null) return "";
                return new String(data, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (SQLException e) {
            throw new IOException("readlink failed for id=" + id, e);
        }
    }

    // =========================================================================
    // Filesystem stats
    // =========================================================================

    @Override
    public FsStat getFsStat() throws IOException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) AS cnt, COALESCE(SUM(file_size), 0) AS total_size " +
                     "FROM fs_entry")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long fileCount = rs.getLong("cnt");
                long usedBytes = rs.getLong("total_size");
                long totalBytes = 10L * 1024 * 1024 * 1024; // 10 GB nominal
                return new FsStat(totalBytes, totalBytes / 512,
                                  totalBytes - usedBytes, (totalBytes - usedBytes) / 512);
            }
        } catch (SQLException e) {
            throw new IOException("getFsStat failed", e);
        }
    }

    // =========================================================================
    // Capabilities
    // =========================================================================

    @Override
    public int access(Subject subject, Inode inode, int mode) throws IOException {
        return mode; // allow all
    }

    @Override
    public nfsace4[] getAcl(Inode inode) throws IOException {
        return new nfsace4[0];
    }

    @Override
    public void setAcl(Inode inode, nfsace4[] acl) throws IOException {
        // ACLs not supported
    }

    @Override
    public boolean hasIOLayout(Inode inode) {
        return false;
    }

    @Override
    public boolean getCaseInsensitive() {
        return false;
    }

    @Override
    public boolean getCasePreserving() {
        return true;
    }

    @Override
    public AclCheckable getAclCheckable() {
        return AclCheckable.ALLOW_ALL;
    }

    @Override
    public NfsIdMapping getIdMapper() {
        return idmap;
    }

    // =========================================================================
    // Internals
    // =========================================================================

    private Inode insertEntry(Inode parent, String name, int entryType, int mode)
            throws IOException {
        long parentId = DbInodeMapper.toFileId(parent);
        long now = System.currentTimeMillis();

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            long id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO fs_entry (parent_id, name, entry_type, file_size, data, " +
                    "owner_uid, owner_gid, mode, created_at, modified_at, accessed_at) " +
                    "VALUES (?, ?, ?, 0, NULL, 0, 0, ?, ?, ?, ?)",
                    PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, parentId);
                ps.setString(2, name);
                ps.setInt(3, entryType);
                ps.setInt(4, mode);
                ps.setLong(5, now);
                ps.setLong(6, now);
                ps.setLong(7, now);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
            }
            conn.commit();
            return DbInodeMapper.toInode(id);
        } catch (SQLException e) {
            throw new IOException("insertEntry failed for name=" + name, e);
        }
    }

    private Stat buildStat(ResultSet rs) throws SQLException {
        var stat = new Stat();
        long id   = rs.getLong("id");
        int  type = rs.getInt("entry_type");

        int typeBits = switch (type) {
            case TYPE_DIR     -> Stat.S_IFDIR;
            case TYPE_SYMLINK -> Stat.S_IFLNK;
            default           -> Stat.S_IFREG;
        };
        int permBits = 0777; //rs.getInt("mode") & 0777;
        stat.setMode(typeBits | permBits);
        stat.setUid(1000);
        stat.setGid(1000);
        stat.setSize(rs.getLong("file_size"));
        stat.setCTime(rs.getLong("created_at"));
        stat.setMTime(rs.getLong("modified_at"));
        stat.setATime(rs.getLong("accessed_at"));
        stat.setNlink(1);
        stat.setIno(id);
        stat.setDev(1);
        stat.setRdev(1);
        stat.setGeneration(rs.getLong("generation"));
        return stat;
    }

    private Long findId(Connection conn, long parentId, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM fs_entry WHERE parent_id = ? AND name = ?")) {
            ps.setLong(1, parentId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("id") : null;
            }
        }
    }

    private void updateFileSizeFromDb(Inode inode) throws IOException {
        long id = DbInodeMapper.toFileId(inode);
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            // Read actual blob length and update file_size
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE fs_entry SET file_size = COALESCE(LENGTH(data), 0), " +
                    "modified_at = ?, generation = generation + 1 WHERE id = ?")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IOException("updateFileSizeFromDb failed for id=" + id, e);
        }
    }
}

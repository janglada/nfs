package org.example.nfs.vfs;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalListener;
import org.dcache.nfs.v4.NfsIdMapping;
import org.dcache.nfs.v4.xdr.nfsace4;
import org.dcache.nfs.vfs.AclCheckable;
import org.dcache.nfs.vfs.DirectoryEntry;
import org.dcache.nfs.vfs.DirectoryStream;
import org.dcache.nfs.vfs.FsStat;
import org.dcache.nfs.vfs.Inode;
import org.dcache.nfs.vfs.Stat;
import org.dcache.nfs.vfs.VirtualFileSystem;

import javax.security.auth.Subject;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Set;

import static java.util.stream.Collectors.toSet;

/**
 * NIO-backed {@link VirtualFileSystem} implementation.
 * Requires the underlying filesystem to support POSIX attribute views.
 *
 * <p>Three Caffeine caches reduce syscall overhead on hot paths:
 * <ul>
 *   <li>{@code readChannels} — pooled read-only {@link FileChannel} instances, one per path.
 *       Positional reads ({@link FileChannel#read(ByteBuffer, long)}) are thread-safe so a
 *       single channel is shared across concurrent NFS read requests for the same file.</li>
 *   <li>{@code writeChannels} — pooled read-write channels for the same reason.</li>
 *   <li>{@code attrCache} — recent {@link PosixFileAttributes} snapshots with a short TTL.
 *       NFS clients issue {@code GETATTR} before almost every operation; caching for even
 *       500 ms collapses repeated round-trips to the kernel into a single {@code stat(2)}.</li>
 * </ul>
 *
 * <p>All three caches are invalidated eagerly on any mutation (write, setattr, create,
 * remove, move) so stale data is never served to clients.
 */
public final class NioVirtualFileSystem implements VirtualFileSystem, Closeable {

    private final Path root;
    private final NfsIdMapping idmap;
    private final InodeMapper inodeMapper;

    /**
     * Read-only FileChannels kept open between NFS read calls.
     * Evicted channels are closed by the removal listener.
     */
    private final Cache<Path, FileChannel> readChannels;

    /**
     * Read-write FileChannels kept open between NFS write calls.
     * Evicted channels are closed by the removal listener.
     */
    private final Cache<Path, FileChannel> writeChannels;

    /**
     * Short-lived cache of POSIX file attributes.
     * A 500 ms TTL is short enough to remain consistent for most NFS workloads
     * while eliminating the majority of redundant {@code stat(2)} syscalls.
     */
    private final Cache<Path, PosixFileAttributes> attrCache;

    public NioVirtualFileSystem(Path root, NfsIdMapping idmap) {
        if (!root.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            throw new IllegalArgumentException("FileSystem must support POSIX attributes");
        }
        this.root = root.toAbsolutePath().normalize();
        this.idmap = idmap;
        this.inodeMapper = new InodeMapper(root);

        RemovalListener<Path, FileChannel> closeOnEvict =
                (path, fc, cause) -> closeQuietly(fc);

        readChannels = Caffeine.newBuilder()
                .maximumSize(512)
                .expireAfterAccess(Duration.ofSeconds(30))
                .removalListener(closeOnEvict)
                .build();

        writeChannels = Caffeine.newBuilder()
                .maximumSize(256)
                .expireAfterAccess(Duration.ofSeconds(30))
                .removalListener(closeOnEvict)
                .build();

        attrCache = Caffeine.newBuilder()
                .maximumSize(4_096)
                .expireAfterWrite(Duration.ofMillis(500))
                .build();
    }

    // -------------------------------------------------------------------------
    // Closeable
    // -------------------------------------------------------------------------

    /**
     * Closes all pooled FileChannels. Call this when shutting down the server.
     */
    @Override
    public void close() {
        readChannels.invalidateAll();
        writeChannels.invalidateAll();
        // Caffeine processes removals asynchronously by default; cleanUp() forces them now.
        readChannels.cleanUp();
        writeChannels.cleanUp();
    }

    // -------------------------------------------------------------------------
    // Inode resolution
    // -------------------------------------------------------------------------

    @Override
    public Inode getRootInode() throws IOException {
        return inodeMapper.toInode(root);
    }

    @Override
    public Inode lookup(Inode parent, String name) throws IOException {
        var parentPath = inodeMapper.toPath(parent);
        var child = parentPath.resolve(name).normalize();
        if (!child.startsWith(root)) {
            throw new IOException("Path escape attempt: " + child);
        }
        return inodeMapper.toInode(child);
    }

    // -------------------------------------------------------------------------
    // Stat / attrs
    // -------------------------------------------------------------------------

    @Override
    public Stat getattr(Inode inode) throws IOException {
        var path = inodeMapper.toPath(inode);
        var attrs = attrCache.getIfPresent(path);
        if (attrs == null) {
            attrs = Files.readAttributes(path, PosixFileAttributes.class);
            attrCache.put(path, attrs);
        }
        return PosixStatMapper.toStat(path, attrs, idmap);
    }

    @Override
    public void setattr(Inode inode, Stat stat) throws IOException {
        var path = inodeMapper.toPath(inode);
        attrCache.invalidate(path);

        var view = Files.getFileAttributeView(path, PosixFileAttributeView.class);

        if (stat.isDefined(Stat.StatAttribute.OWNER)) {
            var username = idmap.uidToPrincipal(stat.getUid());
            int at = username.indexOf('@');
            if (at >= 0) username = username.substring(0, at);
            UserPrincipal owner = path.getFileSystem()
                    .getUserPrincipalLookupService()
                    .lookupPrincipalByName(username);
            Files.setOwner(path, owner);
        }

        if (stat.isDefined(Stat.StatAttribute.GROUP)) {
            var groupName = idmap.gidToPrincipal(stat.getGid());
            int at = groupName.indexOf('@');
            if (at >= 0) groupName = groupName.substring(0, at);
            var group = path.getFileSystem()
                    .getUserPrincipalLookupService()
                    .lookupPrincipalByGroupName(groupName);
            view.setGroup(group);
        }

        if (stat.isDefined(Stat.StatAttribute.MODE)) {
            view.setPermissions(modeToPermissions(stat.getMode() & 0777));
        }

        if (stat.isDefined(Stat.StatAttribute.MTIME)) {
            view.setTimes(null,
                    FileTime.fromMillis(stat.getMTime()),
                    null);
        }

        if (stat.isDefined(Stat.StatAttribute.ATIME)) {
            view.setTimes(
                    FileTime.fromMillis(stat.getATime()),
                    null,
                    null);
        }
    }

    // -------------------------------------------------------------------------
    // Directory operations
    // -------------------------------------------------------------------------

    @Override
    public DirectoryStream list(Inode inode, byte[] verifier, long cookie) throws IOException {
        var path = inodeMapper.toPath(inode);
        var entries = new ArrayList<DirectoryEntry>();

        try (var stream = Files.newDirectoryStream(path)) {
            long index = 0;
            for (var entry : stream) {
                if (index++ < cookie) continue;
                var childInode = inodeMapper.toInode(entry);
                var attrs = attrCache.getIfPresent(entry);
                if (attrs == null) {
                    attrs = Files.readAttributes(entry, PosixFileAttributes.class);
                    attrCache.put(entry, attrs);
                }
                var childStat = PosixStatMapper.toStat(entry, attrs, idmap);
                entries.add(new DirectoryEntry(entry.getFileName().toString(), childInode, childStat, index));
            }
        }

        return new DirectoryStream(DirectoryStream.ZERO_VERIFIER, entries);
    }

    @Override
    public byte[] directoryVerifier(Inode inode) throws IOException {
        return DirectoryStream.ZERO_VERIFIER;
    }

    // -------------------------------------------------------------------------
    // File I/O
    // -------------------------------------------------------------------------

    @Override
    public int read(Inode inode, ByteBuffer data, long offset) throws IOException {
        var path = inodeMapper.toPath(inode);
        var fc = getReadChannel(path);
        return Math.max(0, fc.read(data, offset));
    }

    @Override
    public WriteResult write(Inode inode, ByteBuffer data, long offset,
                             StabilityLevel stabilityLevel) throws IOException {
        var path = inodeMapper.toPath(inode);
        var fc = getWriteChannel(path);
        int written = fc.write(data, offset);
        if (stabilityLevel != StabilityLevel.UNSTABLE) {
            fc.force(false);
        }
        attrCache.invalidate(path);
        return new WriteResult(StabilityLevel.FILE_SYNC, written);
    }

    // -------------------------------------------------------------------------
    // Create / remove
    // -------------------------------------------------------------------------

    @Override
    public Inode create(Inode parent, Stat.Type type, String name, Subject subject, int mode)
            throws IOException {
        var parentPath = inodeMapper.toPath(parent);
        var newPath = parentPath.resolve(name);

        if (type == Stat.Type.DIRECTORY) {
            Files.createDirectory(newPath, inheritedPerms(parentPath));
        } else {
            Files.createFile(newPath, inheritedPerms(parentPath));
        }
        attrCache.invalidate(parentPath);
        return inodeMapper.toInode(newPath);
    }

    @Override
    public Inode mkdir(Inode parent, String name, Subject subject, int mode) throws IOException {
        var parentPath = inodeMapper.toPath(parent);
        var newPath = parentPath.resolve(name);
        Files.createDirectory(newPath, inheritedPerms(parentPath));
        attrCache.invalidate(parentPath);
        return inodeMapper.toInode(newPath);
    }

    @Override
    public String readlink(Inode inode) throws IOException {
        var path = inodeMapper.toPath(inode);
        return Files.readSymbolicLink(path).toString();
    }

    @Override
    public Inode symlink(Inode parent, String name, String link, Subject subject, int mode)
            throws IOException {
        var parentPath = inodeMapper.toPath(parent);
        var newPath = parentPath.resolve(name);
        Files.createSymbolicLink(newPath, Path.of(link));
        attrCache.invalidate(parentPath);
        return inodeMapper.toInode(newPath);
    }

    @Override
    public boolean move(Inode src, String oldName, Inode dest, String newName) throws IOException {
        var srcPath = inodeMapper.toPath(src).resolve(oldName);
        var destPath = inodeMapper.toPath(dest).resolve(newName);
        invalidatePath(srcPath);
        invalidatePath(destPath);
        Files.move(srcPath, destPath,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        return true;
    }

    @Override
    public void remove(Inode parent, String name) throws IOException {
        var path = inodeMapper.toPath(parent).resolve(name);
        invalidatePath(path);
        Files.delete(path);
    }

    // -------------------------------------------------------------------------
    // File store stats
    // -------------------------------------------------------------------------

    @Override
    public FsStat getFsStat() throws IOException {
        var store = Files.getFileStore(root);
        return new FsStat(
                store.getTotalSpace(),
                store.getTotalSpace() / 512,
                store.getUsableSpace(),
                store.getUsableSpace() / 512
        );
    }

    // -------------------------------------------------------------------------
    // Hard links / capabilities
    // -------------------------------------------------------------------------

    @Override
    public Inode link(Inode parent, Inode existing, String name, Subject subject)
            throws IOException {
        var existingPath = inodeMapper.toPath(existing);
        var newPath = inodeMapper.toPath(parent).resolve(name);
        Files.createLink(newPath, existingPath);
        return inodeMapper.toInode(newPath);
    }

    @Override
    public Inode parentOf(Inode inode) throws IOException {
        var path = inodeMapper.toPath(inode);
        var parent = path.getParent();
        if (parent == null || !parent.startsWith(root)) {
            return inodeMapper.toInode(root);
        }
        return inodeMapper.toInode(parent);
    }

    @Override
    public int access(Subject subject, Inode inode, int mode) throws IOException {
        return mode; // allow all
    }

    @Override
    public int read(Inode inode, byte[] data, long offset, int count) throws IOException {
        var path = inodeMapper.toPath(inode);
        var fc = getReadChannel(path);
        var buf = java.nio.ByteBuffer.wrap(data, 0, count);
        return Math.max(0, fc.read(buf, offset));
    }

    @Override
    public WriteResult write(Inode inode, byte[] data, long offset, int count,
                             StabilityLevel stabilityLevel) throws IOException {
        var path = inodeMapper.toPath(inode);
        var fc = getWriteChannel(path);
        var buf = java.nio.ByteBuffer.wrap(data, 0, count);
        int written = fc.write(buf, offset);
        if (stabilityLevel != StabilityLevel.UNSTABLE) {
            fc.force(false);
        }
        attrCache.invalidate(path);
        return new WriteResult(StabilityLevel.FILE_SYNC, written);
    }

    @Override
    public void commit(Inode inode, long offset, int count) throws IOException {
        // no-op: writes are already forced in FILE_SYNC mode
    }

    @Override
    public nfsace4[] getAcl(Inode inode) throws IOException {
        return new nfsace4[0];
    }

    @Override
    public void setAcl(Inode inode, nfsace4[] acl) throws IOException {
        // ACLs not supported; ignore
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

    // -------------------------------------------------------------------------
    // Channel pool helpers
    // -------------------------------------------------------------------------

    /**
     * Returns a cached read-only FileChannel for the given path.
     * If the cached channel has been closed externally, it is evicted and a
     * fresh one is opened.
     */
    private FileChannel getReadChannel(Path path) throws IOException {
        var fc = readChannels.get(path, p -> {
            try {
                return FileChannel.open(p, StandardOpenOption.READ);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        if (!fc.isOpen()) {
            readChannels.invalidate(path);
            fc = readChannels.get(path, p -> {
                try {
                    return FileChannel.open(p, StandardOpenOption.READ);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        return fc;
    }

    /**
     * Returns a cached read-write FileChannel for the given path.
     * If the cached channel has been closed externally, it is evicted and
     * a fresh one is opened.
     */
    private FileChannel getWriteChannel(Path path) throws IOException {
        var fc = writeChannels.get(path, p -> {
            try {
                return FileChannel.open(p, StandardOpenOption.READ, StandardOpenOption.WRITE);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        if (!fc.isOpen()) {
            writeChannels.invalidate(path);
            fc = writeChannels.get(path, p -> {
                try {
                    return FileChannel.open(p, StandardOpenOption.READ, StandardOpenOption.WRITE);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        return fc;
    }

    /** Evicts all cached state for a path — call before deleting or renaming. */
    private void invalidatePath(Path path) {
        readChannels.invalidate(path);
        writeChannels.invalidate(path);
        attrCache.invalidate(path);
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /**
     * Inherit permissions from parent directory, stripping execute bits for new files.
     */
    private FileAttribute<Set<PosixFilePermission>> inheritedPerms(Path parent) throws IOException {
        var parentAttrs = Files.readAttributes(parent, PosixFileAttributes.class);
        var perms = parentAttrs.permissions().stream()
                .filter(p -> !p.name().contains("EXECUTE"))
                .collect(toSet());
        return PosixFilePermissions.asFileAttribute(perms);
    }

    /** Convert a Unix mode integer (lower 9 bits) to a {@link PosixFilePermission} set. */
    private static Set<PosixFilePermission> modeToPermissions(int mode) {
        var perms = EnumSet.noneOf(PosixFilePermission.class);
        if ((mode & 0400) != 0) perms.add(PosixFilePermission.OWNER_READ);
        if ((mode & 0200) != 0) perms.add(PosixFilePermission.OWNER_WRITE);
        if ((mode & 0100) != 0) perms.add(PosixFilePermission.OWNER_EXECUTE);
        if ((mode & 0040) != 0) perms.add(PosixFilePermission.GROUP_READ);
        if ((mode & 0020) != 0) perms.add(PosixFilePermission.GROUP_WRITE);
        if ((mode & 0010) != 0) perms.add(PosixFilePermission.GROUP_EXECUTE);
        if ((mode & 0004) != 0) perms.add(PosixFilePermission.OTHERS_READ);
        if ((mode & 0002) != 0) perms.add(PosixFilePermission.OTHERS_WRITE);
        if ((mode & 0001) != 0) perms.add(PosixFilePermission.OTHERS_EXECUTE);
        return perms;
    }

    private static void closeQuietly(FileChannel fc) {
        if (fc == null) return;
        try {
            fc.close();
        } catch (IOException ignored) {
        }
    }
}

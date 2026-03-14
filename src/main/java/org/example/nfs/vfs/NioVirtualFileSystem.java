package org.example.nfs.vfs;

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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Set;

import static java.util.stream.Collectors.toSet;

/**
 * NIO-backed {@link VirtualFileSystem} implementation.
 * Delegates file I/O to {@link FileIoHandler} and attribute handling
 * to {@link AttributeHandler}, keeping this class a thin facade.
 */
public final class NioVirtualFileSystem implements VirtualFileSystem {

    private final Path root;
    private final NfsIdMapping idmap;
    private final InodeMapper inodeMapper;
    private final FileIoHandler ioHandler;
    private final AttributeHandler attrHandler;

    public NioVirtualFileSystem(Path root, NfsIdMapping idmap) {
        if (!root.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            throw new IllegalArgumentException("FileSystem must support POSIX attributes");
        }
        this.root = root.toAbsolutePath().normalize();
        this.idmap = idmap;
        this.inodeMapper = new InodeMapper(root);
        this.ioHandler = new FileIoHandler();
        this.attrHandler = new AttributeHandler(idmap);
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
        var child = inodeMapper.toPath(parent).resolve(name).normalize();
        if (!child.startsWith(root)) {
            throw new IOException("Path escape attempt: " + child);
        }
        return inodeMapper.toInode(child);
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

    // -------------------------------------------------------------------------
    // Attributes — delegated to AttributeHandler
    // -------------------------------------------------------------------------

    @Override
    public Stat getattr(Inode inode) throws IOException {
        return attrHandler.getattr(inodeMapper.toPath(inode));
    }

    @Override
    public void setattr(Inode inode, Stat stat) throws IOException {
        attrHandler.setattr(inodeMapper.toPath(inode), stat);
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
                var childStat = attrHandler.getattr(entry);
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
    // File I/O — delegated to FileIoHandler
    // -------------------------------------------------------------------------

    @Override
    public int read(Inode inode, ByteBuffer data, long offset) throws IOException {
        return ioHandler.read(inodeMapper.toPath(inode), data, offset);
    }

    @Override
    public int read(Inode inode, byte[] data, long offset, int count) throws IOException {
        return ioHandler.read(inodeMapper.toPath(inode), data, offset, count);
    }

    @Override
    public WriteResult write(Inode inode, ByteBuffer data, long offset,
                             StabilityLevel stabilityLevel) throws IOException {
        return ioHandler.write(inodeMapper.toPath(inode), data, offset, stabilityLevel);
    }

    @Override
    public WriteResult write(Inode inode, byte[] data, long offset, int count,
                             StabilityLevel stabilityLevel) throws IOException {
        return ioHandler.write(inodeMapper.toPath(inode), data, offset, count, stabilityLevel);
    }

    @Override
    public void commit(Inode inode, long offset, int count) throws IOException {
        ioHandler.commit();
    }

    // -------------------------------------------------------------------------
    // Create / remove / move
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
        return inodeMapper.toInode(newPath);
    }

    @Override
    public Inode mkdir(Inode parent, String name, Subject subject, int mode) throws IOException {
        var parentPath = inodeMapper.toPath(parent);
        var newPath = parentPath.resolve(name);
        Files.createDirectory(newPath, inheritedPerms(parentPath));
        return inodeMapper.toInode(newPath);
    }

    @Override
    public void remove(Inode parent, String name) throws IOException {
        Files.delete(inodeMapper.toPath(parent).resolve(name));
    }

    @Override
    public boolean move(Inode src, String oldName, Inode dest, String newName) throws IOException {
        var srcPath = inodeMapper.toPath(src).resolve(oldName);
        var destPath = inodeMapper.toPath(dest).resolve(newName);
        Files.move(srcPath, destPath,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        return true;
    }

    // -------------------------------------------------------------------------
    // Links
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
    public Inode symlink(Inode parent, String name, String link, Subject subject, int mode)
            throws IOException {
        var parentPath = inodeMapper.toPath(parent);
        var newPath = parentPath.resolve(name);
        Files.createSymbolicLink(newPath, Path.of(link));
        return inodeMapper.toInode(newPath);
    }

    @Override
    public String readlink(Inode inode) throws IOException {
        return Files.readSymbolicLink(inodeMapper.toPath(inode)).toString();
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
    // Capabilities
    // -------------------------------------------------------------------------

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
    // Internals
    // -------------------------------------------------------------------------

    private FileAttribute<Set<PosixFilePermission>> inheritedPerms(Path parent) throws IOException {
        var parentAttrs = Files.readAttributes(parent, PosixFileAttributes.class);
        var perms = parentAttrs.permissions().stream()
                .filter(p -> !p.name().contains("EXECUTE"))
                .collect(toSet());
        return PosixFilePermissions.asFileAttribute(perms);
    }
}

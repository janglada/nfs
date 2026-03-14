package org.example.nfs.vfs;

import org.dcache.nfs.v4.NfsIdMapping;
import org.dcache.nfs.vfs.Stat;

import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Pure static mapper from {@link PosixFileAttributes} to nfs4j {@link Stat}.
 *
 * <p>Time values in {@link Stat} are milliseconds since epoch; NIO
 * {@link java.nio.file.attribute.FileTime} exposes them via
 * {@link java.nio.file.attribute.FileTime#toMillis()}.
 */
public final class PosixStatMapper {

    private PosixStatMapper() {}

    public static Stat toStat(Path path, PosixFileAttributes attrs, NfsIdMapping idmap) {
        var stat = new Stat();
        stat.setUid(idmap.principalToUid(attrs.owner().getName()));
        stat.setGid(idmap.principalToGid(attrs.group().getName()));
        stat.setMode(toMode(attrs.permissions()) | typeMode(attrs));
        stat.setSize(attrs.size());
        stat.setMTime(attrs.lastModifiedTime().toMillis());
        stat.setCTime(attrs.creationTime().toMillis());
        stat.setATime(attrs.lastAccessTime().toMillis());
        stat.setNlink(1);
        stat.setIno((long) path.toAbsolutePath().toString().hashCode() & 0xFFFFFFFFL);
        stat.setDev(17);
        stat.setRdev(17);
        stat.setGeneration(0);
        return stat;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static int toMode(Set<PosixFilePermission> perms) {
        int mode = 0;
        for (var p : perms) {
            mode |= switch (p) {
                case OWNER_READ     -> 0400;
                case OWNER_WRITE    -> 0200;
                case OWNER_EXECUTE  -> 0100;
                case GROUP_READ     -> 0040;
                case GROUP_WRITE    -> 0020;
                case GROUP_EXECUTE  -> 0010;
                case OTHERS_READ    -> 0004;
                case OTHERS_WRITE   -> 0002;
                case OTHERS_EXECUTE -> 0001;
            };
        }
        return mode;
    }

    /**
     * Returns the file-type bits using a pattern-matching switch (JDK 21+).
     */
    private static int typeMode(PosixFileAttributes attrs) {
        return switch (attrs) {
            case PosixFileAttributes a when a.isDirectory()    -> Stat.S_IFDIR;
            case PosixFileAttributes a when a.isSymbolicLink() -> Stat.S_IFLNK;
            default                                            -> Stat.S_IFREG;
        };
    }
}

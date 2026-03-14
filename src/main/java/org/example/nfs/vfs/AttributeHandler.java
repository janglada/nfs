package org.example.nfs.vfs;

import org.dcache.nfs.v4.NfsIdMapping;
import org.dcache.nfs.vfs.Stat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * Reads and writes POSIX file attributes, bridging NIO and nfs4j {@link Stat}.
 */
final class AttributeHandler {

    private final NfsIdMapping idmap;

    AttributeHandler(NfsIdMapping idmap) {
        this.idmap = idmap;
    }

    Stat getattr(Path path) throws IOException {
        var attrs = Files.readAttributes(path, PosixFileAttributes.class);
        return PosixStatMapper.toStat(path, attrs, idmap);
    }

    void setattr(Path path, Stat stat) throws IOException {
        var view = Files.getFileAttributeView(path, PosixFileAttributeView.class);

        if (stat.isDefined(Stat.StatAttribute.OWNER)) {
            var username = stripDomain(idmap.uidToPrincipal(stat.getUid()));
            var owner = path.getFileSystem()
                    .getUserPrincipalLookupService()
                    .lookupPrincipalByName(username);
            Files.setOwner(path, owner);
        }

        if (stat.isDefined(Stat.StatAttribute.GROUP)) {
            var groupName = stripDomain(idmap.gidToPrincipal(stat.getGid()));
            var group = path.getFileSystem()
                    .getUserPrincipalLookupService()
                    .lookupPrincipalByGroupName(groupName);
            view.setGroup(group);
        }

        if (stat.isDefined(Stat.StatAttribute.MODE)) {
            view.setPermissions(modeToPermissions(stat.getMode() & 0777));
        }

        if (stat.isDefined(Stat.StatAttribute.MTIME)) {
            view.setTimes(null, FileTime.fromMillis(stat.getMTime()), null);
        }

        if (stat.isDefined(Stat.StatAttribute.ATIME)) {
            view.setTimes(FileTime.fromMillis(stat.getATime()), null, null);
        }
    }

    private static String stripDomain(String principal) {
        int at = principal.indexOf('@');
        return at >= 0 ? principal.substring(0, at) : principal;
    }

    static Set<PosixFilePermission> modeToPermissions(int mode) {
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
}

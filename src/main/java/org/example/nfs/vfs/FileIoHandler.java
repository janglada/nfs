package org.example.nfs.vfs;

import org.dcache.nfs.vfs.VirtualFileSystem.StabilityLevel;
import org.dcache.nfs.vfs.VirtualFileSystem.WriteResult;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Handles raw file I/O (read, write, commit) against NIO {@link FileChannel}s.
 */
final class FileIoHandler {

    FileIoHandler() {}

    int read(Path path, ByteBuffer data, long offset) throws IOException {
        try (var fc = FileChannel.open(path, StandardOpenOption.READ)) {
            return Math.max(0, fc.read(data, offset));
        }
    }

    int read(Path path, byte[] data, long offset, int count) throws IOException {
        return read(path, ByteBuffer.wrap(data, 0, count), offset);
    }

    WriteResult write(Path path, ByteBuffer data, long offset,
                      StabilityLevel stabilityLevel) throws IOException {
        try (var fc = FileChannel.open(path, StandardOpenOption.WRITE)) {
            int written = fc.write(data, offset);
            if (stabilityLevel != StabilityLevel.UNSTABLE) {
                fc.force(false);
            }
            return new WriteResult(StabilityLevel.FILE_SYNC, written);
        }
    }

    WriteResult write(Path path, byte[] data, long offset, int count,
                      StabilityLevel stabilityLevel) throws IOException {
        return write(path, ByteBuffer.wrap(data, 0, count), offset, stabilityLevel);
    }

    void commit() {
        // no-op: writes are already forced in FILE_SYNC mode
    }
}

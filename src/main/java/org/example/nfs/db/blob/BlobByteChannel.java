package org.example.nfs.db.blob;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;

/**
 * {@link SeekableByteChannel} adapter that delegates to a {@link BlobHandle}.
 *
 * <p>This allows nfs4j's NFS WRITE/READ RPCs to interact with database BLOBs
 * using standard NIO channel semantics.
 */
public final class BlobByteChannel implements SeekableByteChannel {

    private final BlobHandle handle;

    public BlobByteChannel(BlobHandle handle) {
        this.handle = handle;
    }

    // -------------------------------------------------------------------------
    // SeekableByteChannel
    // -------------------------------------------------------------------------

    @Override
    public int read(ByteBuffer dst) throws IOException {
        ensureOpen();
        int remaining = dst.remaining();
        if (remaining == 0) {
            return 0;
        }
        byte[] data = handle.read(remaining);
        if (data.length == 0) {
            return -1; // EOF
        }
        dst.put(data, 0, data.length);
        return data.length;
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        ensureOpen();
        int count;
        if (src.hasArray()) {
            // Zero-copy path: pass backing array directly
            int offset = src.arrayOffset() + src.position();
            int length = src.remaining();
            count = handle.write(src.array(), offset, length);
            src.position(src.position() + count);
        } else {
            // Direct or read-only buffer: copy to heap first
            byte[] tmp = new byte[src.remaining()];
            src.get(tmp);
            count = handle.write(tmp);
        }
        return count;
    }

    @Override
    public long position() throws IOException {
        ensureOpen();
        return handle.tell();
    }

    @Override
    public SeekableByteChannel position(long newPosition) throws IOException {
        ensureOpen();
        handle.seek(newPosition);
        return this;
    }

    @Override
    public long size() throws IOException {
        ensureOpen();
        return handle.size();
    }

    @Override
    public SeekableByteChannel truncate(long size) throws IOException {
        ensureOpen();
        handle.truncate(size);
        return this;
    }

    @Override
    public boolean isOpen() {
        return handle.isOpen();
    }

    @Override
    public void close() throws IOException {
        handle.close();
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private void ensureOpen() throws IOException {
        if (!handle.isOpen()) {
            throw new ClosedChannelException();
        }
    }
}

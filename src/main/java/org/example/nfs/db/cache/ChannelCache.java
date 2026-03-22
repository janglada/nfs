package org.example.nfs.db.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import org.example.nfs.db.blob.BlobByteChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.Closeable;
import java.io.IOException;
import java.sql.Connection;
import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Caffeine-backed cache that keeps {@link BlobByteChannel} instances alive across
 * stateless NFS operations.
 *
 * <p>Each cache entry holds a {@link BlobByteChannel}, the JDBC {@link Connection}
 * it uses, and a {@link ReentrantLock} for serialising concurrent NFS WRITE RPCs
 * to the same inode.
 *
 * <p>On idle-timeout eviction the channel is closed (committing its BLOB transaction)
 * and the connection is returned / closed.
 */
public final class ChannelCache implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(ChannelCache.class);

    /**
     * Factory for creating new {@link BlobByteChannel} instances.
     */
    @FunctionalInterface
    public interface ChannelFactory {
        BlobByteChannel create(Connection conn, long inodeId) throws IOException;
    }

    /**
     * Cache entry grouping the channel, its connection, and a per-inode lock.
     */
    public record ChannelEntry(BlobByteChannel channel, Connection conn, ReentrantLock lock) {}

    // -------------------------------------------------------------------------

    private final DataSource dataSource;
    private final ChannelFactory factory;
    private final Cache<Long, ChannelEntry> cache;

    public ChannelCache(DataSource dataSource, Duration idleTimeout, ChannelFactory factory) {
        this.dataSource = dataSource;
        this.factory    = factory;
        this.cache = Caffeine.newBuilder()
                .expireAfterAccess(idleTimeout)
                .removalListener(this::onRemoval)
                .build();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns (or creates) the {@link ChannelEntry} for the given inode ID.
     *
     * @throws IOException if a new channel cannot be created
     */
    public ChannelEntry acquire(long inodeId) throws IOException {
        // Caffeine's get() is not declared to throw checked exceptions, so we wrap/unwrap
        RuntimeException[] wrapper = new RuntimeException[1];
        ChannelEntry entry = cache.get(inodeId, id -> {
            try {
                Connection conn = dataSource.getConnection();
                BlobByteChannel channel = factory.create(conn, id);
                return new ChannelEntry(channel, conn, new ReentrantLock());
            } catch (Exception e) {
                wrapper[0] = (e instanceof RuntimeException re) ? re
                        : new RuntimeException("Failed to open channel for inode " + id, e);
                return null;
            }
        });

        if (entry == null) {
            RuntimeException cause = wrapper[0];
            if (cause != null) {
                Throwable c = cause.getCause();
                if (c instanceof IOException ioe) throw ioe;
                throw new IOException("Failed to open channel for inode " + inodeId, cause);
            }
            throw new IOException("Cache loader returned null for inode " + inodeId);
        }
        return entry;
    }

    /**
     * Closes and removes the channel for the given inode.
     * The next call to {@link #acquire} will create a fresh channel.
     */
    public void release(long inodeId) {
        ChannelEntry entry = cache.asMap().remove(inodeId);
        if (entry != null) {
            closeEntry(entry, inodeId, "release");
        }
    }

    /**
     * Flushes (commits) the channel for the given inode by closing it, then
     * removes it from the cache so the next {@link #acquire} re-opens.
     */
    public void flush(long inodeId) throws IOException {
        release(inodeId);
    }

    /**
     * Invalidates and closes all cached channels.
     */
    @Override
    public void close() {
        // Close entries synchronously before invalidating so callers see isOpen() == false immediately
        cache.asMap().forEach((inodeId, entry) -> closeEntry(entry, inodeId, "close"));
        cache.invalidateAll();
        cache.cleanUp();
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private void onRemoval(Long inodeId, ChannelEntry entry, RemovalCause cause) {
        if (entry != null) {
            closeEntry(entry, inodeId, "eviction(" + cause + ")");
        }
    }

    private void closeEntry(ChannelEntry entry, long inodeId, String reason) {
        if (!entry.channel().isOpen()) {
            return; // already closed
        }
        try {
            entry.channel().close();
        } catch (IOException e) {
            log.warn("Failed to close channel for inode {} on {}: {}", inodeId, reason, e.getMessage());
        }
        try {
            if (!entry.conn().isClosed()) {
                entry.conn().close();
            }
        } catch (Exception e) {
            log.warn("Failed to close connection for inode {} on {}: {}", inodeId, reason, e.getMessage());
        }
    }
}

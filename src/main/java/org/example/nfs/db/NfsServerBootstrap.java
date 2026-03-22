package org.example.nfs.db;

import org.dcache.nfs.ExportFile;
import org.dcache.nfs.v4.MDSOperationExecutor;
import org.dcache.nfs.v4.NFSServerV41;
import org.dcache.nfs.v4.NfsIdMapping;
import org.dcache.oncrpc4j.rpc.OncRpcProgram;
import org.dcache.oncrpc4j.rpc.OncRpcSvc;
import org.dcache.oncrpc4j.rpc.OncRpcSvcBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.Closeable;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.time.Duration;

import static org.dcache.nfs.v4.xdr.nfs4_prot.NFS4_PROGRAM;
import static org.dcache.nfs.v4.xdr.nfs4_prot.NFS_V4;

/**
 * Bootstrap class for the DB-backed NFS server.
 *
 * <p>Wires together {@link DataSource}, {@link DbVirtualFileSystem}, and nfs4j's
 * {@link NFSServerV41} into a running NFS service.
 *
 * <p>Usage:
 * <pre>{@code
 * NfsServerBootstrap server = new NfsServerBootstrap.Builder()
 *         .dataSource(hikariDataSource)
 *         .idMapping(new DbNfsIdMapping(...))
 *         .port(2049)
 *         .channelIdleTimeout(Duration.ofSeconds(30))
 *         .build();
 * server.start();
 * // ...
 * server.close(); // graceful shutdown
 * }</pre>
 */
public final class NfsServerBootstrap implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(NfsServerBootstrap.class);

    private final OncRpcSvc rpcSvc;
    private final DbVirtualFileSystem vfs;

    private NfsServerBootstrap(Builder b) throws IOException {
        this.vfs = new DbVirtualFileSystem(b.dataSource, b.idMapping, b.channelIdleTimeout);

        String exportContent = String.format("/\t*(rw,no_root_squash,no_subtree_check)%n");
        ExportFile exports = new ExportFile(new StringReader(exportContent));

        NFSServerV41 nfsServer = new NFSServerV41.Builder()
                .withExportTable(exports)
                .withVfs(vfs)
                .withOperationExecutor(new MDSOperationExecutor())
                .build();

        OncRpcSvcBuilder svcBuilder = new OncRpcSvcBuilder()
                .withPort(b.port)
                .withTCP()
                .withAutoPublish();

        if (b.bindAddress != null) {
            svcBuilder.withBindAddress(b.bindAddress.getHostAddress());
        }

        this.rpcSvc = svcBuilder.build();
        rpcSvc.register(new OncRpcProgram(NFS4_PROGRAM, NFS_V4), nfsServer);
    }

    /** Starts the NFS server and begins accepting connections. */
    public void start() throws IOException {
        rpcSvc.start();
        log.info("DB-backed NFS server started on port {}", getPort());
    }

    /** Returns the port the server is listening on (useful when port=0 was specified). */
    public int getPort() {
        java.net.InetSocketAddress addr = rpcSvc.getInetSocketAddress(1); // 1 = SOCK_STREAM / TCP
        return addr != null ? addr.getPort() : -1;
    }

    /** Gracefully stops the NFS server and closes all cached channels. */
    @Override
    public void close() throws IOException {
        rpcSvc.stop();
    }

    // =========================================================================
    // Builder
    // =========================================================================

    public static final class Builder {
        private DataSource  dataSource;
        private NfsIdMapping idMapping;
        private int         port              = 2049;
        private InetAddress bindAddress       = null;
        private Duration    channelIdleTimeout = Duration.ofSeconds(30);

        public Builder dataSource(DataSource ds) {
            this.dataSource = ds;
            return this;
        }

        public Builder idMapping(NfsIdMapping mapping) {
            this.idMapping = mapping;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder bindAddress(InetAddress addr) {
            this.bindAddress = addr;
            return this;
        }

        public Builder channelIdleTimeout(Duration timeout) {
            this.channelIdleTimeout = timeout;
            return this;
        }

        public NfsServerBootstrap build() throws IOException {
            if (dataSource == null) throw new IllegalStateException("dataSource must be set");
            if (idMapping  == null) throw new IllegalStateException("idMapping must be set");
            return new NfsServerBootstrap(this);
        }
    }
}

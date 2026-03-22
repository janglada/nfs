package org.example.nfs;

import org.dcache.nfs.ExportFile;
import org.dcache.nfs.v4.MDSOperationExecutor;
import org.dcache.nfs.v4.NFSServerV41;
import org.dcache.nfs.v4.NfsIdMapping;
import org.dcache.nfs.vfs.VirtualFileSystem;
import org.dcache.oncrpc4j.rpc.OncRpcProgram;
import org.dcache.oncrpc4j.rpc.OncRpcSvc;
import org.dcache.oncrpc4j.rpc.OncRpcSvcBuilder;
import org.example.nfs.config.NfsServerConfig;
import org.example.nfs.idmap.DbNfsIdMapping;
import org.example.nfs.vfs.NioVirtualFileSystem;

import javax.sql.DataSource;
import java.io.Closeable;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;

import static org.dcache.nfs.v4.xdr.nfs4_prot.NFS4_PROGRAM;
import static org.dcache.nfs.v4.xdr.nfs4_prot.NFS_V4;

/**
 * Plain Java bootstrap for the NFS4J server.
 * Owns the {@link OncRpcSvc} lifecycle; implements {@link Closeable}.
 */
public final class NfsServer implements Closeable {

    private final OncRpcSvc rpcSvc;

    public NfsServer(NfsServerConfig config, VirtualFileSystem vfs) throws IOException {

        // VFS root must be the parent of the exported directory, because
        // nfs4j's PseudoFs requires a non-root export path (exporting "/"
        // triggers a bug where intermediate nodes are never added to the
        // pseudo-fs tree, causing "No exports found").
        var exportDir = config.rootPath().toAbsolutePath().normalize();
        var vfsRoot = exportDir.getParent();

        var exportPath = "/" + vfsRoot.relativize(exportDir);
        var exportContent = exportPath + " *(rw,no_root_squash)\n";
        var exports = new ExportFile(new StringReader(exportContent));

        var nfsServer = new NFSServerV41.Builder()
                .withExportTable(exports)
                .withVfs(vfs)
                .withOperationExecutor(new MDSOperationExecutor())
                .build();

        rpcSvc = new OncRpcSvcBuilder()
                .withPort(config.port())
                .withTCP()
                .withAutoPublish()
                .build();

        rpcSvc.register(new OncRpcProgram(NFS4_PROGRAM, NFS_V4), nfsServer);
    }

    public NfsServer(NfsServerConfig config) throws IOException {
       this(config,  new NioVirtualFileSystem(config.rootPath().toAbsolutePath().normalize().getParent(), new DbNfsIdMapping(config)));
    }

    public void start() throws IOException {
        rpcSvc.start();
    }

    @Override
    public void close() throws IOException {
        rpcSvc.stop();
    }

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        var ds = createDataSource();
        var rootPath = args.length > 0 ? Path.of(args[0]) : Path.of("/srv/nfs");
        var config = NfsServerConfig.defaults(rootPath, ds);

        try (var server = new NfsServer(config)) {
            server.start();
            System.out.printf("NFS4J server started on port %d, serving %s%n",
                    config.port(), config.rootPath());
            // Park main thread — virtual-thread friendly in JDK 25
            Thread.currentThread().join();
        }
    }

    /**
     * Returns a minimal JDBC {@link DataSource}.
     *
     * <p>Replace this with a real production DataSource (HikariCP, etc.) wired
     * to your actual database.
     *
     * <p>Example with HikariCP:
     * <pre>{@code
     * HikariConfig hc = new HikariConfig();
     * hc.setJdbcUrl("jdbc:postgresql://localhost:5432/mydb");
     * hc.setUsername("nfs_user");
     * hc.setPassword("secret");
     * return new HikariDataSource(hc);
     * }</pre>
     */
    private static DataSource createDataSource() {
        throw new UnsupportedOperationException(
                "Replace NfsServer.createDataSource() with a real JDBC DataSource. " +
                "See javadoc for an example using HikariCP.");
    }
}

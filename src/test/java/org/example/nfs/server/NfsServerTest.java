package org.example.nfs.server;

import org.dcache.nfs.status.BadOwnerException;
import org.dcache.nfs.v4.NfsIdMapping;
import org.dcache.nfs.v4.SimpleIdMap;
import org.example.nfs.NfsServer;
import org.example.nfs.config.NfsServerConfig;
import org.example.nfs.db.DbVirtualFileSystem;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGPoolingDataSource;
import org.sqlite.SQLiteDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
@Testcontainers
public class NfsServerTest {


    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");


    @Test
    public void test() throws IOException, SQLException, InterruptedException {

        PGPoolingDataSource source = new PGPoolingDataSource();
        source.setDataSourceName("A Data Source");
        source.setUrl(pg.getJdbcUrl());
        source.setUser(pg.getUsername());
        source.setPassword(pg.getPassword());
        source.setDatabaseName(pg.getDatabaseName());

        source.setMaxConnections(10);

        setup(source);
        NfsServerConfig config = NfsServerConfig.defaults(Path.of("/home/joan/workspace/nfs/src/test/resources/root/"), source);

        NfsIdMapping idMap = new NfsIdMapping() {

            @Override
            public int principalToUid(String s) throws BadOwnerException {
                System.out.println(s);
                return 1000;
            }

            @Override
            public int principalToGid(String s) throws BadOwnerException {
                System.out.println(s);
                return 1000;
            }

            @Override
            public String uidToPrincipal(int i) {
                return "joan";
            }

            @Override
            public String gidToPrincipal(int i) {
                return "joan";
            }
        };


        try(var server = new NfsServer(config, new DbVirtualFileSystem(source, idMap))) {
            server.start();
            Thread.currentThread().join();
        }
    }

    @Disabled("Manual integration test — blocks forever via Thread.join()")
    @Test
    public void testSQLITE() throws IOException, SQLException, InterruptedException {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:sample.db");
        setup(ds);
        NfsServerConfig config = NfsServerConfig.defaults(Path.of("/home/joan/workspace/nfs/src/test/resources/root/"), ds);
        try(var server = new NfsServer(config)) {
            server.start();
            Thread.currentThread().join();
        }
    }


    private void setup(DataSource ds) throws SQLException {



        try (Connection conn = ds.getConnection()) {
            System.out.println("connecting to database..." + conn);
            // Create table
            try (Statement stmt = conn.createStatement()) {
                boolean execute = stmt.execute("""
                            CREATE TABLE IF NOT EXISTS sys_user (
                                user_id   INTEGER PRIMARY KEY,
                                user_code TEXT NOT NULL         
                            )
                        """);

                if(execute) {
                    // Insert
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO sys_user (user_id, user_code) VALUES (?, ?)")) {
                        ps.setInt(1, 54);
                        ps.setString(2, "joan");
                        ps.executeUpdate();  // fails on re-run if sample.db persists
                    }

                }
            }



        }

    }
}
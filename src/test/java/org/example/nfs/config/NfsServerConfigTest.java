package org.example.nfs.config;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class NfsServerConfigTest {

    @Test
    void defaultsPopulatesAllFields() {
        var ds = mock(DataSource.class);
        var root = Path.of("/srv/nfs");

        var cfg = NfsServerConfig.defaults(root, ds);

        assertEquals(2049, cfg.port());
        assertEquals(root, cfg.rootPath());
        assertEquals("domain.local", cfg.domain());
        assertEquals(65534, cfg.nobodyUid());
        assertSame(ds, cfg.dataSource());
        assertEquals(Duration.ofSeconds(60), cfg.cacheExpiry());
    }

    @Test
    void customRecordStoresAllFields() {
        var ds = mock(DataSource.class);
        var root = Path.of("/data");
        var cfg = new NfsServerConfig(2050, root, "mycompany.com", 99, ds, Duration.ofMinutes(5));

        assertEquals(2050, cfg.port());
        assertEquals(root, cfg.rootPath());
        assertEquals("mycompany.com", cfg.domain());
        assertEquals(99, cfg.nobodyUid());
        assertSame(ds, cfg.dataSource());
        assertEquals(Duration.ofMinutes(5), cfg.cacheExpiry());
    }

    @Test
    void equalRecordsAreEqual() {
        var ds = mock(DataSource.class);
        var root = Path.of("/srv/nfs");
        var a = NfsServerConfig.defaults(root, ds);
        var b = NfsServerConfig.defaults(root, ds);
        assertEquals(a, b);
    }
}

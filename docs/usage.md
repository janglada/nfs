# Usage Guide

---

## Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| JDK | 21 or later | JDK 25 works; project targets Java 21 source level |
| Gradle wrapper | 8.x | Included — run `./gradlew` |
| Linux kernel | 4.0+ | For mounting NFSv4.1 clients |
| macOS | 10.15+ | NFS client built-in; see mount syntax below |

A relational database that supports JDBC is required for ID mapping.
The server has been tested with PostgreSQL and H2. Any JDBC-compatible
database will work.

---

## Building

```bash
# Full build including tests
./gradlew build

# Skip tests
./gradlew build -x test

# Run tests only
./gradlew test

# Produce a fat / shadow JAR (if you add the shadow plugin — see below)
./gradlew jar
```

The plain `./gradlew jar` task produces `build/libs/nfs-1.0.0-SNAPSHOT.jar`
containing only the project classes. To run as a self-contained JAR you
must either add the shadow plugin or supply dependencies on the classpath.

---

## Database schema

Create the `sys_user` table in your database:

```sql
CREATE TABLE sys_user (
    user_id   INTEGER     NOT NULL,
    user_code VARCHAR(64) NOT NULL,
    PRIMARY KEY (user_id),
    UNIQUE (user_code)
);

-- Example rows
INSERT INTO sys_user VALUES (1000, 'root');
INSERT INTO sys_user VALUES (1001, 'alice');
INSERT INTO sys_user VALUES (1002, 'bob');
```

`user_id` is the numeric POSIX UID/GID. `user_code` is the login name
(without domain). The NFS domain is appended by the server at runtime.

---

## Wiring a DataSource

`NfsServer.main()` calls `createDataSource()`, which currently throws
`UnsupportedOperationException`. Replace it with a real implementation.

### PostgreSQL with HikariCP

Add HikariCP to `build.gradle`:

```groovy
implementation 'com.zaxxer:HikariCP:5.1.0'
runtimeOnly    'org.postgresql:postgresql:42.7.3'
```

Then implement `createDataSource()`:

```java
private static DataSource createDataSource() {
    var hc = new HikariConfig();
    hc.setJdbcUrl("jdbc:postgresql://localhost:5432/mydb");
    hc.setUsername("nfs_user");
    hc.setPassword("secret");
    hc.setMaximumPoolSize(10);
    return new HikariDataSource(hc);
}
```

### H2 in-memory (for testing)

```java
private static DataSource createDataSource() {
    var ds = new org.h2.jdbcx.JdbcDataSource();
    ds.setURL("jdbc:h2:mem:nfs;DB_CLOSE_DELAY=-1");
    return ds;
}
```

---

## Configuration

`NfsServerConfig` is a Java record. Use the `defaults` factory or the
canonical constructor:

```java
// Using defaults (port 2049, domain "domain.local", nobodyUid 65534, 60s cache)
var config = NfsServerConfig.defaults(Path.of("/srv/nfs"), dataSource);

// Full control
var config = new NfsServerConfig(
    2049,                         // port
    Path.of("/data/exports/nfs"), // rootPath — must exist and be readable
    "corp.example.com",           // NFS domain
    65534,                        // nobody UID
    dataSource,                   // JDBC DataSource
    Duration.ofMinutes(5)         // ID-mapping cache TTL
);
```

### Important: rootPath

The `rootPath` must be a directory on a POSIX filesystem (Linux ext4/xfs/btrfs,
macOS APFS). The server rejects any `lookup` that tries to escape above
`rootPath` with an `IOException("Path escape attempt")`.

---

## Running

```bash
# From source, passing rootPath as first argument
./gradlew run --args="/srv/nfs"

# From a JAR with dependencies on the classpath
java -cp "build/libs/*:lib/*" org.example.nfs.NfsServer /srv/nfs
```

The server prints:

```
NFS4J server started on port 2049, serving /srv/nfs
```

then parks the main thread. Ctrl-C triggers the JVM shutdown hook; the
`try-with-resources` in `main()` calls `NfsServer.close()` → `rpcSvc.stop()`.

---

## Running as root vs. non-root

NFSv4.1 on port 2049 requires binding a port below 1024, which on Linux
requires either:

- Running as `root` (not recommended in production).
- Granting the `CAP_NET_BIND_SERVICE` capability:
  ```bash
  setcap 'cap_net_bind_service=+ep' /path/to/java
  ```
- Using a port above 1024 and configuring the client to use that port
  (`-o port=XXXX` in the mount command).

---

## Mounting on Linux

```bash
# Create mount point
mkdir -p /mnt/nfs

# Mount NFSv4.1 (requires kernel NFS client)
mount -t nfs4 -o vers=4.1,proto=tcp,port=2049 localhost:/ /mnt/nfs

# Or for a non-standard port
mount -t nfs4 -o vers=4.1,proto=tcp,port=3049 localhost:/ /mnt/nfs

# Unmount
umount /mnt/nfs
```

On most Linux distributions `mount.nfs` is provided by the `nfs-utils`
package (`apt install nfs-common` / `dnf install nfs-utils`).

---

## Mounting on macOS

```bash
# macOS uses the BSD nfs client — vers=4 selects NFSv4
mount_nfs -o vers=4,tcp,port=2049 localhost:/ /mnt/nfs

# or using the 'mount' front-end
mount -t nfs -o vers=4,tcp,port=2049 localhost:/ /mnt/nfs
```

macOS Ventura and later include NFSv4 client support. NFSv4.1 session
features may not be fully supported on older macOS versions.

---

## Running tests

```bash
# All tests (JUnit 5, H2 in-memory DB)
./gradlew test

# With full output
./gradlew test --info

# A single test class
./gradlew test --tests "org.example.nfs.vfs.NioVirtualFileSystemTest"
```

Tests use `@TempDir` for filesystem isolation and an H2 in-memory database
for the ID mapping tests. No network access is required.

---

## Logging

The project uses SLF4J with `slf4j-simple` as the runtime binding. Log
level is controlled via system property:

```bash
java -Dorg.slf4j.simpleLogger.defaultLogLevel=DEBUG \
     -cp ... org.example.nfs.NfsServer /srv/nfs
```

Available levels: `trace`, `debug`, `info` (default), `warn`, `error`.

Swap `slf4j-simple` for Logback or Log4j2 in `build.gradle` to get
structured/rolling-file logging in production.

---

## Firewall considerations

| Port | Protocol | Direction | Purpose |
|---|---|---|---|
| 2049 | TCP | Inbound | NFS / ONC-RPC |

NFSv3 required portmapper (111/UDP) and mountd (random port). NFSv4 uses
a single well-known TCP port and needs no additional ports open.

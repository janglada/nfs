# nfs4j-server

A plain Java NFSv4.1 server built on top of [nfs4j](https://github.com/dCache/nfs4j). No Spring, no framework — just a handful of classes wired together manually.

The main use case is serving a local directory over NFS where user identity is resolved against a database rather than the system passwd file. A Caffeine cache sits in front of the database to avoid hammering it on every file access.

## Requirements

- JDK 21 or later
- Maven 3.8+
- A JDBC-compatible database with a `sys_user` table (see schema below)
- A POSIX filesystem for the export root (Linux, macOS)

## Building

```
mvn package
```

This produces `target/nfs4j-server-1.0.0-SNAPSHOT.jar`. Tests run as part of the build. To skip them:

```
mvn package -DskipTests
```

## Running

The server expects a `DataSource` wired up at startup. The `NfsServer.createDataSource()` stub in `NfsServer.java` throws `UnsupportedOperationException` by design — replace it with a real connection pool before running.

Example using HikariCP:

```java
HikariConfig hc = new HikariConfig();
hc.setJdbcUrl("jdbc:postgresql://localhost:5432/mydb");
hc.setUsername("nfs_user");
hc.setPassword("secret");
DataSource ds = new HikariDataSource(hc);

var config = NfsServerConfig.defaults(Path.of("/srv/nfs"), ds);
try (var server = new NfsServer(config)) {
    server.start();
    Thread.currentThread().join();
}
```

Or pass a path on the command line to override the export root:

```
java -jar target/nfs4j-server-1.0.0-SNAPSHOT.jar /data/exports
```

The server listens on port 2049 by default.

## Configuration

All configuration lives in `NfsServerConfig`, which is a plain Java record:

| Field          | Default          | Description                                      |
|----------------|------------------|--------------------------------------------------|
| `port`         | `2049`           | TCP port the RPC service binds to                |
| `rootPath`     | (required)       | Directory to export                              |
| `domain`       | `domain.local`   | NFS ID mapping domain appended to usernames      |
| `nobodyUid`    | `65534`          | UID/GID returned when a user cannot be resolved  |
| `dataSource`   | (required)       | JDBC DataSource for user lookups                 |
| `cacheExpiry`  | `60s`            | How long user mappings are cached                |

Use the `NfsServerConfig` constructor directly if you need values other than the defaults.

## Database schema

The server looks up users from a table called `sys_user`:

```sql
CREATE TABLE sys_user (
    user_id   INTEGER PRIMARY KEY,
    user_code VARCHAR(64) NOT NULL UNIQUE
);
```

`user_code` is the username part of the NFS principal (the part before `@`). `user_id` maps to the Unix UID/GID. If a lookup fails — either because the user is not in the table or the database is unreachable — the server falls back to the nobody UID (default: 65534).

## Project structure

```
src/main/java/org/example/nfs/
  NfsServer.java               -- entry point, wires everything together
  config/
    NfsServerConfig.java       -- immutable config record
  idmap/
    DbNfsIdMapping.java        -- NfsIdMapping backed by a database + Caffeine cache
    UserRecord.java            -- simple record for a user row
  vfs/
    NioVirtualFileSystem.java  -- VirtualFileSystem implementation using java.nio
    PosixStatMapper.java       -- maps PosixFileAttributes to nfs4j Stat objects
    InodeMapper.java           -- bidirectional path <-> Inode mapping
```

## Exports

The export table is hard-coded to export the root path with `rw,no_root_squash,no_subtree_check`. If you need more granular export rules, pass a custom `ExportFile` instance to the `NFSServerV41.Builder` inside `NfsServer`.

## Testing

The test suite uses JUnit 5, Mockito, and H2 (for the database tests). Everything runs against a real temp directory on the local filesystem — no mocking of file operations.

```
mvn test
```

54 tests across 6 test classes covering the VFS, stat mapping, inode mapper, ID mapping, and configuration.

## Notes

- The server only supports NFSv4.1. NFSv3 and NFSv4.0 are not registered.
- ACLs are not supported. All ACL operations are no-ops or return empty lists.
- Write stability is always reported as `FILE_SYNC`. Unstable writes are flushed immediately.
- Symlinks are stored as-is. No canonicalisation is applied to link targets.
- Path traversal outside the export root raises an `IOException` rather than silently resolving to the filesystem root.

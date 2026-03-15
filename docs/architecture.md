# Architecture

---

## Package layout

```
org.example.nfs
├── NfsServer.java                  Bootstrap; owns OncRpcSvc lifecycle
│
├── config/
│   └── NfsServerConfig.java        Immutable configuration record
│
├── idmap/
│   ├── DbNfsIdMapping.java         SQL-backed NfsIdMapping with Caffeine cache
│   └── UserRecord.java             Row projection (userId, userCode)
│
└── vfs/
    ├── NioVirtualFileSystem.java   VirtualFileSystem façade (thin coordinator)
    ├── FileIoHandler.java          Raw FileChannel read / write / commit
    ├── AttributeHandler.java       POSIX attribute read / write via NIO views
    ├── PosixStatMapper.java        Pure conversion: PosixFileAttributes → Stat
    └── InodeMapper.java            Bidirectional Path ↔ inode handle map
```

---

## Component diagram

```
 ┌──────────────────────────────────────────────────────────┐
 │                      NfsServer                           │
 │                                                          │
 │  ┌────────────┐   ┌─────────────────┐   ┌────────────┐  │
 │  │ OncRpcSvc  │──▶│  NFSServerV41   │──▶│  ExportFile│  │
 │  │ (oncrpc4j) │   │  (nfs4j)        │   └────────────┘  │
 │  └────────────┘   └───────┬─────────┘                   │
 │                           │ VirtualFileSystem            │
 │                  ┌────────▼────────────────────────┐     │
 │                  │    NioVirtualFileSystem          │     │
 │                  │                                 │     │
 │                  │  ┌───────────────┐              │     │
 │                  │  │ FileIoHandler │ read/write   │     │
 │                  │  └───────────────┘              │     │
 │                  │  ┌────────────────┐             │     │
 │                  │  │ AttributeHandler│ getattr/   │     │
 │                  │  └────────────────┘  setattr    │     │
 │                  │  ┌──────────────┐               │     │
 │                  │  │ InodeMapper  │ path↔handle   │     │
 │                  │  └──────────────┘               │     │
 │                  └─────────────┬───────────────────┘     │
 │                                │                         │
 │                  ┌─────────────▼──────────┐              │
 │                  │   DbNfsIdMapping        │              │
 │                  │   (Caffeine + SQL)      │              │
 │                  └────────────────────────┘              │
 └──────────────────────────────────────────────────────────┘
                            │              │
               ┌────────────▼───┐  ┌───────▼──────────────┐
               │  POSIX Filesys │  │  JDBC DataSource      │
               │  (local disk)  │  │  (sys_user table)     │
               └────────────────┘  └──────────────────────┘
```

---

## Components in detail

### NfsServer

The entry point and lifecycle owner.

- Creates `DbNfsIdMapping` and `NioVirtualFileSystem`.
- Builds `NFSServerV41` using nfs4j's builder API, passing the export
  table, VFS, and a plain `MDSOperationExecutor`.
- Wraps everything in an `OncRpcSvc` (the oncrpc4j server socket), which
  listens on TCP port 2049.
- Implements `Closeable`: `close()` calls `rpcSvc.stop()` for clean
  shutdown.
- `main()` parks the main thread with `Thread.currentThread().join()` so
  that virtual threads can be serviced indefinitely without spinning.

### NfsServerConfig

A Java `record` — immutable, compact, auto-generates `equals`/`hashCode`/
`toString`. Fields:

| Field | Type | Default | Purpose |
|---|---|---|---|
| `port` | `int` | 2049 | TCP listen port |
| `rootPath` | `Path` | — | Filesystem root to export |
| `domain` | `String` | `"domain.local"` | NFS ID mapping domain |
| `nobodyUid` | `int` | 65534 | UID returned for unknown users |
| `dataSource` | `DataSource` | — | JDBC source for `sys_user` |
| `cacheExpiry` | `Duration` | 60 s | Caffeine TTL for ID mapping |

`NfsServerConfig.defaults(rootPath, ds)` fills in the standard values.

### NioVirtualFileSystem

The thin coordinator that adapts nfs4j's `VirtualFileSystem` interface to
NIO filesystem operations.

It does **not** do I/O itself. Instead, it:
1. Resolves `Inode` handles to `Path` objects via `InodeMapper`.
2. Validates path escapes (`!child.startsWith(root)`).
3. Delegates attribute operations to `AttributeHandler`.
4. Delegates I/O to `FileIoHandler`.
5. Handles directory operations and hard/soft links inline (they are
   one or two NIO calls each, not worth a dedicated class).

### FileIoHandler

Opens a `FileChannel`, performs one positional read or write, then closes
it. No state is kept between calls.

```
read  → FileChannel.open(READ)     → fc.read(buf, offset)   → close
write → FileChannel.open(WRITE)    → fc.write(buf, offset)
      → fc.force(false) if !UNSTABLE → close
```

`commit()` is a no-op because writes are already forced synchronously.

### AttributeHandler

Reads and writes POSIX file attributes through NIO's `PosixFileAttributeView`.

- `getattr(path)`: calls `Files.readAttributes(path, PosixFileAttributes.class)`,
  then delegates to `PosixStatMapper.toStat()`.
- `setattr(path, stat)`: only writes the attributes marked as defined
  (`stat.isDefined(StatAttribute.X)`), so partial updates work correctly.

### PosixStatMapper

A pure static utility class — no instances, no state, no side effects.

Maps `PosixFileAttributes` to nfs4j's `Stat` object. Notable points:

- **File type bits** use a JDK 21+ **pattern-matching `switch`** over
  `PosixFileAttributes` guard conditions.
- **Inode number** is derived from `path.toAbsolutePath().toString().hashCode()`,
  masked to 32 bits. Collisions are theoretically possible for very long
  path strings; see [implementation-decisions.md](implementation-decisions.md#inode-numbers).
- **`dev` and `rdev`** are hardcoded to `17` — a placeholder, since this
  server does not emulate a real block device.

### InodeMapper

Translates between NFS `Inode` handles (opaque byte arrays) and `Path`
objects. Two strategies based on path length:

- **Inline** (path UTF-8 bytes ≤ 128 bytes): the handle _is_ the path bytes.
  No map entry needed; `toPath()` just decodes the bytes.
- **Hashed** (path bytes > 128 bytes): stores the path in a
  `ConcurrentHashMap<Long, Path>`, keyed by a stable long derived from the
  path's hash code and length. The handle contains the 8-byte key.

See [inode-mapping.md](inode-mapping.md) for full details and trade-offs.

### DbNfsIdMapping

Implements nfs4j's `NfsIdMapping` with SQL queries backed by Caffeine caches.

- `principalToUid("alice@domain")` → strips domain → looks up `user_code`
  in `sys_user` → returns `user_id`.
- `uidToPrincipal(1001)` → looks up `user_id` → returns
  `"user_code@domain"`.
- On any miss or SQL error, falls back to `nobodyUid` / `"nobody@domain"`.
- Both directions populate both caches on a DB hit to avoid a second
  round-trip for the reverse lookup.
- GID methods delegate to the UID equivalents (groups and users share
  the same table in this schema).

See [id-mapping.md](id-mapping.md) for the database schema and cache details.

---

## Request flow — a read operation

```
NFS client: READ(filehandle, offset, count)

  1. OncRpcSvc receives TCP frame, oncrpc4j decodes XDR
  2. NFSServerV41.compound() iterates COMPOUND sub-operations
  3. SEQUENCE op: establishes session context
  4. PUTFH op:  sets current filehandle in the compound state
  5. READ op:
       a. nfs4j calls VirtualFileSystem.read(inode, byteBuffer, offset)
       b. NioVirtualFileSystem: inodeMapper.toPath(inode) → Path
       c. NioVirtualFileSystem: ioHandler.read(path, buf, offset)
       d. FileIoHandler: FileChannel.open(READ) → fc.read(buf, offset) → close
       e. Returns byte count
  6. nfs4j XDR-encodes the response
  7. OncRpcSvc sends TCP frame back to client
```

---

## Request flow — a getattr operation

```
  1–3. Same as above
  4. GETATTR op:
       a. nfs4j calls VirtualFileSystem.getattr(inode)
       b. NioVirtualFileSystem: inodeMapper.toPath(inode) → Path
       c. NioVirtualFileSystem: attrHandler.getattr(path)
       d. AttributeHandler: Files.readAttributes(path, PosixFileAttributes)
       e. AttributeHandler: PosixStatMapper.toStat(path, attrs, idmap)
       f. PosixStatMapper: idmap.principalToUid(attrs.owner().getName())
       g. DbNfsIdMapping: Caffeine.getIfPresent(code) or SQL query
       h. Returns nfs4j Stat object
```

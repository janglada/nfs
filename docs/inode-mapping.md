# Inode Mapping

The `InodeMapper` class translates between NFS `Inode` handle objects (opaque
byte arrays) and Java `Path` objects. This translation happens on every single
NFS operation — understanding it is key to understanding the server's behaviour.

---

## What is an NFS filehandle?

An NFS **filehandle** is an opaque byte sequence (≤ 128 bytes in NFSv4) that
the server assigns to each file and directory. Clients treat it as a black
box: they store it, send it back to the server, and never inspect or construct
it themselves.

The server is responsible for:
1. **Assigning** handles: converting a `Path` to a stable byte sequence.
2. **Resolving** handles: converting a byte sequence back to a `Path`.

Handles must be stable across server restarts if the server wants to support
client state that survives a reboot (e.g., open file locks). If handles change
on restart, clients see `NFS4ERR_STALE` and must re-resolve their paths.

---

## The `InodeMapper` API

```java
public Inode toInode(Path path)  // Path → Inode (creates handle)
public Path  toPath(Inode inode) // Inode → Path (resolves handle)
```

Both methods are thread-safe.

---

## Two-strategy encoding

NFSv4 filehandle maximum size: **128 bytes**.

### Strategy 1 — Inline (path bytes ≤ 128)

The UTF-8 bytes of the absolute, normalised path string are used directly
as the filehandle contents:

```
/srv/nfs/data/file.txt  →  [ 2F 73 72 76 2F 6E 66 73 ... ]
                            └──────── path bytes ──────────┘
```

- **No map entry** needed.
- `toPath()` decodes the bytes with `new String(handle, UTF_8)`.
- Fast, allocation-free for common short paths.

### Strategy 2 — Hashed (path bytes > 128)

For paths that exceed the NFS limit, the path is stored in a
`ConcurrentHashMap<Long, Path>` and the handle contains an 8-byte key:

```
/very/long/path/.../file.txt
        │
        ├─ key = ((long) s.hashCode() << 32) | (s.length() & 0xFFFFFFFFL)
        │
        ├─ handleMap.put(key, absPath)
        │
        └─ handle = [ key as 8 big-endian bytes ]
```

`toPath()` detects the 8-byte-handle case, looks up `handleMap.get(key)`,
and returns the stored `Path`.

---

## Handle disambiguation

A 128-byte inline path and an 8-byte hashed handle have different lengths,
but a 8-byte path (e.g., `/a/b.txt`) would produce an 8-byte inline handle
that could be mistaken for a hashed handle. `toPath()` disambiguates by:

1. If `handle.length == 8`, try `handleMap.get(bytesToLong(handle))`.
2. If the map contains the key, return the stored `Path` (hashed case).
3. If the map does not contain the key, decode as an inline path
   (`new String(handle, UTF_8)`).

This works because hashed handles are always present in `handleMap` before
`toPath()` is called (they were put there by `toInode()`). A short inline
path whose bytes happen to decode to the same 8-byte value as a hashed key
would be correctly served by path 3.

---

## Key derivation

```java
long keyFor(Path path) {
    var s = path.toString();
    return ((long) s.hashCode() << 32) | (s.length() & 0xFFFFFFFFL);
}
```

Mixing the 32-bit `hashCode` with the path length in the lower 32 bits
reduces collision probability compared to using `hashCode` alone — two
paths with the same hash but different lengths will have different keys.

**Collision risk**: Two paths could still collide if they have the same
`hashCode` AND the same length. `String.hashCode()` uses a polynomial roll
with multiplier 31, which is generally collision-resistant for human-readable
path strings. In practice, the probability of collision in a filesystem with
even tens of millions of long paths is negligible.

If collision-free handles are required (e.g., for a security-sensitive
deployment), replace `keyFor` with a cryptographic hash
(`MessageDigest.getInstance("SHA-256")`) and truncate to 8 bytes. The
truncation still leaves `2^64` possible values — vastly more than any
real filesystem can hold.

---

## Persistence across restarts

**Current behaviour**: `handleMap` is an in-memory `ConcurrentHashMap`.
It does not persist to disk. On server restart:

- **Short paths** (inline handles): always resolvable, because `toPath()`
  just decodes the bytes. No state needed.
- **Long paths** (hashed handles): the map is empty after restart. Clients
  holding hashed handles will receive `NFS4ERR_STALE`.

**Implication**: In most real-world deployments, nearly all paths are short
enough to use inline handles. A server reachable via `localhost` or a
private network will rarely see paths longer than 128 bytes in typical
NAS use-cases.

**To add persistence**: Serialize `handleMap` to a file on shutdown and
reload on startup. A simple approach:

```java
// On shutdown:
var entries = handleMap.entrySet().stream()
    .collect(toMap(e -> e.getKey().toString(),
                   e -> e.getValue().toString()));
Files.writeString(statePath, new ObjectMapper().writeValueAsString(entries));

// On startup:
var entries = new ObjectMapper().readValue(statePath, Map.class);
entries.forEach((k, v) -> handleMap.put(Long.parseLong(k), Path.of(v)));
```

---

## Thread safety

`ConcurrentHashMap` provides full concurrency for reads and fine-grained
locking for writes. Since `toInode()` only ever adds entries (never removes),
there is no risk of a `toPath()` lookup seeing a partially constructed entry.

The map is never cleared or compacted. Long-running servers on filesystems
with many long paths will accumulate entries indefinitely. Adding a size
cap or a soft-reference `WeakHashMap` would bound memory at the cost of
occasional stale-handle errors for infrequently accessed long paths.

---

## Path normalisation

`InodeMapper` always normalises paths:

```java
var abs = path.toAbsolutePath().normalize();
```

`normalize()` resolves `.` and `..` segments and removes redundant
separators. This ensures that `Path.of("/srv/nfs/../nfs/file.txt")` and
`Path.of("/srv/nfs/file.txt")` produce the same handle, avoiding phantom
duplicates in the map.

---

## Summary table

| Condition | Handle contents | Map entry? | Restart stable? |
|---|---|---|---|
| `path.bytes.length ≤ 128` | UTF-8 path bytes | No | Yes |
| `path.bytes.length > 128` | 8-byte long key | Yes | No |

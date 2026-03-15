# ID Mapping

NFSv4 carries user and group identities as UTF-8 strings of the form
`user@domain` (e.g. `alice@corp.example.com`). The server is responsible for
translating these strings to and from the numeric UID/GID values that POSIX
filesystems use.

---

## Why ID mapping matters

On NFSv3, UIDs and GIDs were sent as raw 32-bit integers. This worked only
when client and server had identical `/etc/passwd` databases — a fragile
assumption in any heterogeneous environment.

NFSv4 solves this with string identities. Every `GETATTR` response for owner
or group encodes a string; every `SETATTR` that sets ownership carries a
string. The NFS server must translate between its filesystem's numeric IDs
and those strings on every attribute read or write.

---

## The `NfsIdMapping` interface

nfs4j defines a four-method interface:

```java
public interface NfsIdMapping {
    int    principalToUid(String principal);
    int    principalToGid(String principal);
    String uidToPrincipal(int uid);
    String gidToPrincipal(int gid);
}
```

`principal` is always `"name@domain"` or just `"name"` (without domain).
Return values for `uidToPrincipal` / `gidToPrincipal` should always be
`"name@domain"`.

---

## Database schema

```sql
CREATE TABLE sys_user (
    user_id   INTEGER     NOT NULL,
    user_code VARCHAR(64) NOT NULL,
    PRIMARY KEY (user_id),
    UNIQUE (user_code)
);
```

`user_id` is the POSIX UID (and GID — see below). `user_code` is the
login name without domain. Both columns are indexed.

---

## DbNfsIdMapping

```
 principalToUid("alice@corp.example.com")
     │
     ├─ stripDomain() → "alice"
     │
     ├─ byCode.getIfPresent("alice") → cache hit? return
     │
     └─ lookupByCode("alice")
            │
            └─ SELECT user_id FROM sys_user WHERE user_code = 'alice'
                   │
                   ├─ found: populateBothCaches(uid=1001, code="alice")
                   │         return 1001
                   │
                   └─ not found / SQL error: return nobodyUid (65534)
```

```
 uidToPrincipal(1001)
     │
     ├─ byId.getIfPresent(1001) → cache hit? return
     │
     └─ lookupById(1001)
            │
            └─ SELECT user_code FROM sys_user WHERE user_id = 1001
                   │
                   ├─ found: populateBothCaches(uid=1001, code="alice")
                   │         return "alice@corp.example.com"
                   │
                   └─ not found / SQL error: return "nobody@corp.example.com"
```

### Bidirectional cache warming

`populateBothCaches(int uid, String code)` writes to both `byCode` and
`byId` after every successful DB hit. This means:

- One `principalToUid("alice")` DB query populates `byId` as well, so
  the immediately following `uidToPrincipal(1001)` is a cache hit.
- One `uidToPrincipal(1001)` DB query populates `byCode`, so the
  immediately following `principalToUid("alice")` is a cache hit.

NFS attribute reads always fetch both owner and group, and directory
listings do the same for every entry. Bidirectional warming means a
directory listing for 1000 files with 10 distinct users triggers at
most 10 DB queries total (one per unique user, filling both caches).

---

## Caffeine caches

Both `byCode` (String → Integer) and `byId` (Integer → String) are
Caffeine `Cache` instances configured with:

| Parameter | Value | Source |
|---|---|---|
| `maximumSize` | 1000 entries | Hard-coded |
| `expireAfterWrite` | `config.cacheExpiry()` | `NfsServerConfig` (default 60 s) |

`expireAfterWrite` ensures stale entries are evicted after a user is
renamed or deleted in the database. The default 60-second TTL is a
conservative value; adjust `NfsServerConfig.cacheExpiry()` to taste.

### Cache miss under concurrency

The current implementation uses `Cache.getIfPresent()` followed by a
DB query if null. Two concurrent threads looking up the same uncached
user will each trigger an independent DB query. For most NFS workloads
this is acceptable — the second query is redundant but harmless, and
after both return, the cache is hot. If DB query fan-out is a concern,
switch to `LoadingCache` (see [implementation-decisions.md](implementation-decisions.md)).

---

## GID == UID

`principalToGid(p)` delegates to `principalToUid(p)`, and
`gidToPrincipal(gid)` delegates to `uidToPrincipal(gid)`. This means
every user's primary GID equals their UID, and there is no separate
group table.

This simplification is correct for deployments where each user has a
personal group (common on Linux: `useradd` creates `alice` with UID 1001
and group `alice` with GID 1001). For environments with shared groups,
add a `sys_group` table and separate lookup logic.

---

## Nobody fallback

Any principal not found in `sys_user` maps to `nobodyUid` (default `65534`,
the traditional `nobody` UID on Linux). This is a safe default — files
owned by unknown users appear owned by `nobody`, and operations attempted
by unknown users are governed by the "others" permission bits on the file.

Adjust `nobodyUid` in `NfsServerConfig` if your environment uses a
different nobody UID (e.g. macOS uses `4294967294 = 0xFFFFFFFE`).

---

## Domain stripping

`principalToUid("alice@corp.example.com")` strips the `@corp.example.com`
suffix before the DB lookup. The NFS domain in the principal string is
advisory — it helps client and server agree that they are in the same
administrative realm, but the lookup key is always the bare username.

This means the server accepts principals from any domain. If domain
validation is needed (reject identities from `othercorp.example.com`),
add a check in `stripDomain()` before the DB call.

---

## Extending to LDAP / Active Directory

The `NfsIdMapping` interface has four methods. Drop in an LDAP
implementation like:

```java
public final class LdapNfsIdMapping implements NfsIdMapping {
    private final DirContext ctx;
    // ...
    @Override
    public int principalToUid(String principal) {
        // Search LDAP for uid= attribute matching principal
    }
}
```

and wire it into `NfsServer` instead of `DbNfsIdMapping`. The rest of the
server is unaffected.

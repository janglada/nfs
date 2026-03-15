# NFS Protocol Background

A concise reference for the protocol concepts that appear throughout this codebase.

---

## NFS versions

| Version | Standard | Transport | Key features |
|---|---|---|---|
| NFSv2 | RFC 1094 (1989) | UDP | Stateless, 8 kB limit |
| NFSv3 | RFC 1813 (1995) | UDP or TCP | Stateless, large transfers, READDIRPLUS |
| NFSv4.0 | RFC 3530 (2003) | TCP only | Stateful, compounds, strong security |
| NFSv4.1 | RFC 5661 (2010) | TCP | Sessions, pNFS layouts, SEQUENCE op |
| NFSv4.2 | RFC 7862 (2016) | TCP | Server-side copy, sparse files, labelled NFS |

This server implements **NFSv4.1**. The library (`nfs4j`) handles all
protocol encoding; this codebase only implements the `VirtualFileSystem`
interface that nfs4j calls to perform actual filesystem operations.

---

## ONC-RPC transport

NFS rides on top of **Open Network Computing Remote Procedure Call** (ONC-RPC,
also called Sun RPC), standardised in RFC 5531.

```
┌─────────────────────────────────┐
│  NFS client (kernel / FUSE)     │
│                                 │
│  NFS4 operations (COMPOUND)     │
│            │                    │
│   XDR serialisation             │
│            │                    │
│   ONC-RPC TCP frame             │
└────────────┼────────────────────┘
             │ TCP port 2049
┌────────────┼────────────────────┐
│   OncRpcSvc (oncrpc4j)          │
│            │                    │
│   XDR deserialisation           │
│            │                    │
│   NFSServerV41 (nfs4j)          │
│            │                    │
│   VirtualFileSystem API         │ ← our code lives here
│            │                    │
│   NIO / POSIX filesystem        │
└─────────────────────────────────┘
```

`OncRpcSvc` is the server socket manager from **oncrpc4j**. It listens on a
TCP port, framing each incoming message as a Record Marking encoded stream
(RFC 5531 §11). One thread handles I/O events; actual dispatch happens on a
Netty pipeline.

---

## NFSv4 compound operations

NFSv4 clients batch operations into **COMPOUND** RPCs. A single RPC can
contain many sub-operations:

```
COMPOUND [
  SEQUENCE
  PUTFH  (set current filehandle)
  GETATTR (read attributes)
  LOOKUP  (resolve name → filehandle)
  READ    (read data)
]
```

`nfs4j`'s `MDSOperationExecutor` dispatches each sub-operation to the
appropriate method on the `VirtualFileSystem` implementation. Our code
never sees the compounding; it only sees individual method calls like
`getattr(inode)` or `read(inode, buf, offset)`.

---

## Filehandles and inodes

An NFS **filehandle** is an opaque byte array (≤ 128 bytes in NFSv4)
that the server assigns to identify a file or directory. Clients treat
it as an opaque cookie — they cannot construct or parse it.

The server must:
1. Persist filehandles across restarts if possible (or all client state
   is invalidated).
2. Map handles back to actual files efficiently on every operation.

How this server does it is described in [inode-mapping.md](inode-mapping.md).

---

## NFS ID mapping

NFSv4 uses **string identities** (`"alice@domain.local"`) rather than raw
UID/GID numbers on the wire. The server is responsible for translating
between those strings and the numeric IDs that POSIX filesystems use.

This server's mapping strategy is described in [id-mapping.md](id-mapping.md).

---

## Exports

The NFS export table controls which clients can mount which paths and with
what options. The standard format (from `/etc/exports`) is:

```
/path   client-spec(option,option,...)
```

This server uses an inline, hard-coded export:

```
/   *(rw,no_root_squash,no_subtree_check)
```

Meaning: the root of the served filesystem is exported to all clients (`*`)
with read-write access. Root squashing is disabled (the server runs without
a special security context anyway). See `NfsServer` for where this is wired.

---

## Stability levels

NFS `WRITE` operations carry a **stability hint**:

| Level | Meaning |
|---|---|
| `UNSTABLE` | Client does not require server to flush to disk |
| `DATA_SYNC` | Server must flush file data (not necessarily metadata) |
| `FILE_SYNC` | Server must flush both data and metadata |

This server always responds with `FILE_SYNC` — it forces
(`FileChannel.force(false)`) on every non-`UNSTABLE` write. See
`FileIoHandler.write()` and the rationale in
[implementation-decisions.md](implementation-decisions.md#stability-level-always-file_sync).

---

## Relevant RFCs

| RFC | Title |
|---|---|
| RFC 5661 | NFSv4.1 |
| RFC 7530 | NFSv4.0 (updated) |
| RFC 5531 | ONC-RPC |
| RFC 4506 | XDR: External Data Representation |
| RFC 2624 | NFS Version 4 Design Considerations |

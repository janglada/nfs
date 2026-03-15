# NFS4J Server — Documentation

A lightweight NFSv4.1 server built on **nfs4j** and **ONC-RPC4J**, with no
application framework dependency. It exports any POSIX filesystem path over the
network and maps OS user identities through a relational database.

---

## Contents

| Document | What it covers |
|---|---|
| [nfs-protocol.md](nfs-protocol.md) | NFSv4.1 fundamentals, RPC transport, protocol concepts |
| [architecture.md](architecture.md) | Package layout, component diagram, data-flow walkthrough |
| [usage.md](usage.md) | Build, configure, run, mount, test |
| [id-mapping.md](id-mapping.md) | UID/GID mapping subsystem (`DbNfsIdMapping`) |
| [inode-mapping.md](inode-mapping.md) | Path ↔ inode handle strategy (`InodeMapper`) |
| [implementation-decisions.md](implementation-decisions.md) | Design rationale and trade-off notes |

---

## Quick-start (30 seconds)

```bash
# 1. Build
./gradlew build

# 2. Supply a real DataSource in NfsServer.createDataSource()
#    (see usage.md for an example with HikariCP)

# 3. Run — exports /srv/nfs on port 2049
java -jar build/libs/nfs-*.jar /srv/nfs

# 4. Mount (Linux)
mount -t nfs4 -o port=2049,proto=tcp localhost:/ /mnt/nfs
```

See [usage.md](usage.md) for the full guide including database schema,
configuration options, and macOS mount syntax.

---

## Key facts

- **Protocol**: NFSv4.1 over TCP (port 2049 by default)
- **Library**: [nfs4j 0.27.1](https://github.com/dCache/nfs4j) + oncrpc4j 3.4.2
- **Build tool**: Gradle 8 with the version-catalogue (`gradle/libs.versions.toml`)
- **Java**: 21+ source compatibility (runs on JDK 21–25+)
- **ID mapping**: SQL table `sys_user(user_id, user_code)` cached with Caffeine
- **No Spring**, no Quarkus, no Micronaut — plain `main()` entry point

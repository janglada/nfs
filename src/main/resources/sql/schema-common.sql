-- Filesystem entries (files and directories)
-- Common SQL dialect (PostgreSQL compatible)
CREATE TABLE IF NOT EXISTS fs_entry (
    id          BIGSERIAL PRIMARY KEY,
    parent_id   BIGINT REFERENCES fs_entry(id),
    name        VARCHAR(255) NOT NULL,
    entry_type  SMALLINT NOT NULL DEFAULT 0,  -- 0=file, 1=directory, 2=symlink
    file_size   BIGINT NOT NULL DEFAULT 0,
    -- BLOB storage: used by JdbcBlobHandle
    data        BYTEA,
    -- PG Large Object OID: used by PgLargeObjectBlobHandle (NULL on Informix)
    lo_oid      BIGINT,
    -- Permissions
    owner_uid   INT NOT NULL DEFAULT 0,
    owner_gid   INT NOT NULL DEFAULT 0,
    mode        INT NOT NULL DEFAULT 420,     -- 0644 octal = 420 decimal
    -- Timestamps (epoch millis for portability)
    created_at  BIGINT NOT NULL,
    modified_at BIGINT NOT NULL,
    accessed_at BIGINT NOT NULL,
    -- NFS generation number (incremented on content change)
    generation  BIGINT NOT NULL DEFAULT 0,
    -- Optimistic locking version
    version     BIGINT NOT NULL DEFAULT 0,
    -- Unique constraint: no duplicate names in same directory
    UNIQUE (parent_id, name)
);

-- Index for parent lookups (directory listing)
CREATE INDEX IF NOT EXISTS idx_fs_entry_parent ON fs_entry(parent_id);

-- Insert root directory entry (id=1)
INSERT INTO fs_entry (id, parent_id, name, entry_type, file_size, owner_uid, owner_gid, mode, created_at, modified_at, accessed_at)
VALUES (1, NULL, '/', 1, 0, 0, 0, 493, 0, 0, 0)
ON CONFLICT (id) DO NOTHING;

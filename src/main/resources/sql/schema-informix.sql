-- Filesystem entries (files and directories)
-- Informix dialect: SERIAL8 instead of BIGSERIAL, BLOB is SmartBlob in sbspace
CREATE TABLE IF NOT EXISTS fs_entry (
    id          SERIAL8 PRIMARY KEY,
    parent_id   INT8 REFERENCES fs_entry(id),
    name        VARCHAR(255) NOT NULL,
    entry_type  SMALLINT NOT NULL DEFAULT 0,  -- 0=file, 1=directory, 2=symlink
    file_size   INT8 NOT NULL DEFAULT 0,
    -- SmartBlob storage: locator for IfxSmartBlobHandle
    data        BLOB,
    -- lo_oid not used on Informix
    lo_oid      INT8,
    -- Permissions
    owner_uid   INTEGER NOT NULL DEFAULT 0,
    owner_gid   INTEGER NOT NULL DEFAULT 0,
    mode        INTEGER NOT NULL DEFAULT 420,  -- 0644 octal = 420 decimal
    -- Timestamps (epoch millis for portability)
    created_at  INT8 NOT NULL,
    modified_at INT8 NOT NULL,
    accessed_at INT8 NOT NULL,
    -- NFS generation number (incremented on content change)
    generation  INT8 NOT NULL DEFAULT 0,
    -- Optimistic locking version
    version     INT8 NOT NULL DEFAULT 0,
    -- Unique constraint: no duplicate names in same directory
    UNIQUE (parent_id, name)
);

-- Index for parent lookups (directory listing)
CREATE INDEX idx_fs_entry_parent ON fs_entry(parent_id);

-- Insert root directory entry (id=1)
INSERT INTO fs_entry (id, parent_id, name, entry_type, file_size, owner_uid, owner_gid, mode, created_at, modified_at, accessed_at)
SELECT 1, NULL, '/', 1, 0, 0, 0, 493, 0, 0, 0
FROM sysmaster:sysdual
WHERE NOT EXISTS (SELECT 1 FROM fs_entry WHERE id = 1);

-- V5: per-clip starring. A starred clip is exempt from the retention sweep — independently of its
-- parent match's star state — so a highlight can be kept indefinitely even after its source VOD is
-- pruned. Forward-only, keyed on PRAGMA user_version; the MigrationRunner applies this in a
-- transaction and stamps user_version=5.

ALTER TABLE clips ADD COLUMN starred INTEGER NOT NULL DEFAULT 0;

PRAGMA user_version = 5;

-- V6: record when a clip entered 'generating' so the periodic stale-row self-heal (ClipQueue.sweep)
-- anchors its wedged-row cutoff on generation start, not on created_at (insert time). A clip can sit
-- 'pending' in a saturated clipExecutor queue well past the cutoff and only then be claimed and
-- rendered; keying the cutoff off created_at would re-pend that live render and double-cut it (two
-- workers writing the same output). claimForGeneration stamps this when it flips pending -> generating;
-- the self-heal query falls back to created_at when it is NULL (legacy rows, and rows still pending).
-- Forward-only, keyed on PRAGMA user_version; the MigrationRunner applies this in a transaction and
-- stamps user_version=6.

ALTER TABLE clips ADD COLUMN generation_started_at INTEGER;

PRAGMA user_version = 6;

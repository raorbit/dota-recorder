-- V4: user/auto highlight clips carved out of a match recording.
-- A clip is a sub-range of a parent match's VOD, generated asynchronously into its own .mp4.

CREATE TABLE clips (
  id               INTEGER PRIMARY KEY,
  parent_match_id  INTEGER NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
  kind             TEXT NOT NULL,
  trigger_reason   TEXT,
  start_offset_s   REAL NOT NULL,
  end_offset_s     REAL NOT NULL,
  label            TEXT,
  video_path       TEXT,
  thumb_path       TEXT,
  file_size_bytes  INTEGER,
  status           TEXT NOT NULL DEFAULT 'pending',
  error            TEXT,
  created_at       INTEGER NOT NULL
);

CREATE INDEX idx_clips_parent ON clips(parent_match_id);

PRAGMA user_version = 4;

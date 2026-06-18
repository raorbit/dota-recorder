-- V1: initial schema for Dota 2 Recorder.
-- Forward-only, keyed on PRAGMA user_version. The MigrationRunner applies this
-- script in a single transaction and stamps user_version=1.
-- Schema rationale (see plan storage model): synthetic `id` surrogate PK so
-- Manual/Clip rows need no Dota match_id; `dota_match_id` is nullable + UNIQUE so
-- the enricher keys deterministically and can never double-insert. `record_kind`
-- discriminates match/manual/clip; `enrichment_state` tracks async API/replay merge.

CREATE TABLE matches (
  id                     INTEGER PRIMARY KEY,
  dota_match_id          INTEGER UNIQUE,
  record_kind            TEXT NOT NULL DEFAULT 'match',
  enrichment_state       TEXT NOT NULL DEFAULT 'pending',
  hero                   TEXT,
  kills                  INTEGER,
  deaths                 INTEGER,
  assists                INTEGER,
  gpm                    INTEGER,
  xpm                    INTEGER,
  net_worth              INTEGER,
  last_hits              INTEGER,
  result                 TEXT,
  lobby_type             INTEGER,
  game_mode              INTEGER,
  rank_tier              INTEGER,
  mmr_delta              INTEGER,
  duration_s             INTEGER,
  record_started_wall_ms INTEGER,
  played_at              INTEGER,
  video_path             TEXT,
  thumb_path             TEXT,
  file_size_bytes        INTEGER,
  starred                INTEGER NOT NULL DEFAULT 0,
  created_at             INTEGER NOT NULL
);

CREATE TABLE markers (
  id             INTEGER PRIMARY KEY,
  match_id       INTEGER NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
  type           TEXT NOT NULL,
  video_offset_s REAL NOT NULL,
  game_clock     INTEGER,
  label          TEXT,
  source         TEXT NOT NULL DEFAULT 'gsi'
);

CREATE INDEX idx_markers_match ON markers(match_id);

CREATE TABLE pauses (
  id         INTEGER PRIMARY KEY,
  match_id   INTEGER NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
  start_wall INTEGER NOT NULL,
  end_wall   INTEGER
);

CREATE INDEX idx_pauses_match ON pauses(match_id);

PRAGMA user_version = 1;

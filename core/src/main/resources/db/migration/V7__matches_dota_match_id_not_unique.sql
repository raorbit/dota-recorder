-- V7: let several recordings share one Dota match id (drop UNIQUE on matches.dota_match_id).
-- One Dota match can legitimately produce several recordings: an OBS self-heal restarts the
-- output mid-game, and an abandon + rejoin re-arms the FSM, each starting a new matches row for
-- the same match. With the V1 UNIQUE, every part after the first had to drop its dota_match_id
-- link to be inserted at all, so it was never enriched and sat in Unsorted forever. Without it,
-- every part keeps the link and the enricher fills each one in.
-- SQLite cannot drop a column constraint in place, so this is the documented table rebuild:
-- create matches_new with the identical columns (V1, then V2's enrich_* pair; V3-V6 touch other
-- tables), copy every row with its id, drop the old table, rename the new one into place.
-- MigrationRunner runs every script with foreign_keys OFF -- otherwise DROP TABLE matches would
-- fire the ON DELETE CASCADE of markers, pauses and clips and wipe them all -- and rolls back if
-- PRAGMA foreign_key_check finds any orphan before commit. The child tables reference matches by
-- name, so they bind to the renamed table with their FKs unchanged. The only index on matches was
-- the UNIQUE's implicit autoindex (no triggers or views reference it), so the one index to create
-- is a plain, non-unique one for the enrichment queue and the per-match-id lookups.

CREATE TABLE matches_new (
  id                     INTEGER PRIMARY KEY,
  dota_match_id          INTEGER,
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
  created_at             INTEGER NOT NULL,
  enrich_attempts        INTEGER NOT NULL DEFAULT 0,
  enrich_next_after_ms   INTEGER
);

INSERT INTO matches_new (
  id, dota_match_id, record_kind, enrichment_state, hero,
  kills, deaths, assists, gpm, xpm, net_worth, last_hits,
  result, lobby_type, game_mode, rank_tier, mmr_delta, duration_s,
  record_started_wall_ms, played_at, video_path, thumb_path, file_size_bytes,
  starred, created_at, enrich_attempts, enrich_next_after_ms
)
SELECT
  id, dota_match_id, record_kind, enrichment_state, hero,
  kills, deaths, assists, gpm, xpm, net_worth, last_hits,
  result, lobby_type, game_mode, rank_tier, mmr_delta, duration_s,
  record_started_wall_ms, played_at, video_path, thumb_path, file_size_bytes,
  starred, created_at, enrich_attempts, enrich_next_after_ms
FROM matches;

DROP TABLE matches;

ALTER TABLE matches_new RENAME TO matches;

CREATE INDEX idx_matches_dota_match_id ON matches(dota_match_id);

PRAGMA user_version = 7;

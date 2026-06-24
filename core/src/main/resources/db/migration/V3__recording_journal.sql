-- V3: in-progress recording journal.
-- Captures enough state to reconcile a recording that was active when the app/core crashed.

CREATE TABLE recording_session (
  session_id               TEXT PRIMARY KEY,
  surrogate_id             TEXT NOT NULL,
  state                    TEXT NOT NULL,
  dota_match_id            INTEGER,
  hero                     TEXT,
  record_confirmed_wall_ms INTEGER NOT NULL,
  record_started_wall_ms   INTEGER NOT NULL,
  last_frame_wall_ms       INTEGER,
  last_game_state          TEXT,
  kills                    INTEGER,
  deaths                   INTEGER,
  assists                  INTEGER,
  video_path               TEXT,
  thumb_path               TEXT,
  opened_at                INTEGER NOT NULL,
  updated_at               INTEGER NOT NULL
);

CREATE INDEX idx_recording_session_state ON recording_session(state);

CREATE TABLE recording_event (
  id          INTEGER PRIMARY KEY,
  session_id  TEXT NOT NULL REFERENCES recording_session(session_id) ON DELETE CASCADE,
  type        TEXT NOT NULL,
  wall_ms     INTEGER NOT NULL,
  game_clock  INTEGER,
  payload_json TEXT,
  created_at  INTEGER NOT NULL
);

CREATE INDEX idx_recording_event_session ON recording_event(session_id, id);

PRAGMA user_version = 3;

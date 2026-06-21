-- V2: enrichment retry bookkeeping.
-- Adds the two columns the EnrichmentQueue eligibility filter and the Enricher
-- backoff logic read/write: an attempt counter and the next-eligible wall clock.
-- SQLite requires one ADD COLUMN per statement (no multi-column ALTER), so this
-- is two statements. enrich_attempts defaults to 0 so in-flight 'pending' rows
-- become immediately eligible; enrich_next_after_ms is nullable (NULL = eligible now).

ALTER TABLE matches ADD COLUMN enrich_attempts INTEGER NOT NULL DEFAULT 0;

ALTER TABLE matches ADD COLUMN enrich_next_after_ms INTEGER;

PRAGMA user_version = 2;

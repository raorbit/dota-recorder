-- Test-only migration for MigrationRunnerTest: a parent/child pair with a cascading FK.
CREATE TABLE parent (id INTEGER PRIMARY KEY);
CREATE TABLE child (
  id        INTEGER PRIMARY KEY,
  parent_id INTEGER NOT NULL REFERENCES parent(id) ON DELETE CASCADE
);
INSERT INTO parent (id) VALUES (1);
INSERT INTO child (id, parent_id) VALUES (10, 1);
PRAGMA user_version = 1;

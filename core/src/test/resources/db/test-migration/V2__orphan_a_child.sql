-- Test-only migration for MigrationRunnerTest: a buggy script. The runner runs every script with
-- foreign_keys OFF, so this delete does not cascade and leaves child 10 dangling -- exactly what
-- the runner's foreign_key_check must catch and roll back.
DELETE FROM parent WHERE id = 1;
PRAGMA user_version = 2;

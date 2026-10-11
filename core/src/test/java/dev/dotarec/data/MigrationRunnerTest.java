package dev.dotarec.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteDataSource;

class MigrationRunnerTest {

    @TempDir Path tempDir;

    @Test
    void preMigrationBackupIncludesCommittedWalPages() throws Exception {
        Path dbFile = tempDir.resolve("test.sqlite");
        SQLiteDataSource ds = walDataSource(dbFile);
        // A genuine V2 schema, not just a stamped user_version: later scripts need their tables
        // (V7 rebuilds matches by copying every row out of it).
        new MigrationRunner(ds).migrateTo(2);

        try (Connection conn = ds.getConnection();
                Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE backup_probe (id INTEGER PRIMARY KEY, name TEXT NOT NULL)");
            st.execute("INSERT INTO backup_probe (name) VALUES ('before')");
        }

        new MigrationRunner(ds).run(null);

        List<Path> backups;
        try (var stream = Files.list(tempDir)) {
            // Only the v2 backup: staging the fresh file may or may not leave a v0 one.
            backups =
                    stream.filter(path -> path.getFileName().toString().endsWith(".bak"))
                            .filter(path -> path.getFileName().toString().contains(".v2."))
                            .toList();
        }
        assertThat(backups).hasSize(1);

        try (Connection backup = DriverManager.getConnection("jdbc:sqlite:" + backups.get(0));
                Statement st = backup.createStatement();
                ResultSet rs = st.executeQuery("SELECT name FROM backup_probe WHERE id = 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("before");
        }
    }

    /**
     * Every script runs with {@code foreign_keys} OFF (a table rebuild's DROP TABLE would otherwise
     * cascade-delete every child row). The pool hands the SAME physical connection to later
     * borrowers, and the pragma lives on that connection -- so the runner must switch enforcement
     * back on before returning it, or every later delete silently stops cascading. A single-slot
     * Hikari pool (production pools too) makes "the next borrower" observable.
     */
    @Test
    void foreignKeysAreEnforcedAgainOnThePooledConnectionAfterMigrating() throws Exception {
        try (HikariDataSource pool = singleConnectionPool(tempDir.resolve("test.sqlite"))) {
            SQLiteConnection physicalBefore = physicalConnection(pool);

            new MigrationRunner(pool).run(null);

            try (Connection conn = pool.getConnection()) {
                assertThat(conn.unwrap(SQLiteConnection.class))
                        .as("must be the connection the migrator used, or this test proves nothing")
                        .isSameAs(physicalBefore);
                assertThat(foreignKeysPragma(conn)).isEqualTo(1);
                try (Statement st = conn.createStatement()) {
                    assertThatThrownBy(() -> st.execute(
                                    "INSERT INTO markers (match_id, type, video_offset_s) VALUES (999, 'death', 1.0)"))
                            .isInstanceOf(SQLException.class)
                            .hasMessageContaining("FOREIGN KEY");
                }
            }
        }
    }

    private static final String TEST_V1 = "db/test-migration/V1__parent_child.sql";
    private static final String TEST_V2_ORPHANS = "db/test-migration/V2__orphan_a_child.sql";

    /**
     * A script that leaves a NEW foreign-key violation behind must roll back (enforcement was off,
     * so {@code PRAGMA foreign_key_check} is the only guard), and the finally must still switch
     * enforcement back on for the pooled connection -- the failure path is exactly where a leak would
     * hide. The test-only V2 deletes a parent row, which with FKs off orphans its child.
     */
    @Test
    void failingMigrationRollsBackAndStillReenablesForeignKeys() throws Exception {
        Path dbFile = tempDir.resolve("test.sqlite");
        try (HikariDataSource pool = singleConnectionPool(dbFile)) {
            new MigrationRunner(pool, TEST_V1, TEST_V2_ORPHANS).migrateTo(1);
            SQLiteConnection physicalBefore = physicalConnection(pool);

            MigrationRunner runner = new MigrationRunner(pool, TEST_V1, TEST_V2_ORPHANS);
            assertThatThrownBy(() -> runner.run(null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("foreign_key_check")
                    .hasMessageContaining("child rowid 10");

            assertThat(runner.isReady()).isFalse();
            try (Connection conn = pool.getConnection();
                    Statement st = conn.createStatement()) {
                assertThat(conn.unwrap(SQLiteConnection.class)).isSameAs(physicalBefore);
                assertThat(foreignKeysPragma(conn)).isEqualTo(1);
                try (ResultSet rs = st.executeQuery("PRAGMA user_version")) {
                    assertThat(rs.getInt(1))
                            .as("the failed script rolled back, user_version stamp included")
                            .isEqualTo(1);
                }
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM parent WHERE id = 1")) {
                    assertThat(rs.getInt(1)).as("the orphaning delete was rolled back").isEqualTo(1);
                }
            }
        }
    }

    /**
     * An orphan that predates the migration (inserted by an FK-unaware connection, e.g. a sqlite3
     * CLI session where foreign keys default to off) is not the migration's doing. Failing on it
     * would roll V7 back on every boot and keep the core from starting at all, so the runner only
     * fails on violations the script itself introduced, and leaves the old orphan as it found it.
     */
    @Test
    void preexistingOrphanDoesNotBlockTheMigration() throws Exception {
        Path dbFile = tempDir.resolve("test.sqlite");
        try (HikariDataSource pool = singleConnectionPool(dbFile)) {
            new MigrationRunner(pool).migrateTo(MigrationRunner.LATEST_VERSION - 1);
            try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
                    Statement st = raw.createStatement()) {
                st.execute("PRAGMA foreign_keys = OFF");
                st.execute("INSERT INTO markers (match_id, type, video_offset_s) VALUES (999, 'death', 1.0)");
            }

            MigrationRunner runner = new MigrationRunner(pool);
            runner.run(null);

            assertThat(runner.isReady()).isTrue();
            try (Connection conn = pool.getConnection();
                    Statement st = conn.createStatement()) {
                assertThat(foreignKeysPragma(conn)).isEqualTo(1);
                try (ResultSet rs = st.executeQuery("PRAGMA user_version")) {
                    assertThat(rs.getInt(1)).isEqualTo(MigrationRunner.LATEST_VERSION);
                }
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM markers WHERE match_id = 999")) {
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
            }
        }
    }

    /**
     * V7 rebuilds {@code matches} (drop + rename) to shed the UNIQUE on dota_match_id. Every match
     * row must survive with its id (markers/pauses/clips point at it), every column value and the
     * exact column layout must carry over, and -- the reason the runner turns FKs off -- not one
     * child row may be lost to the DROP TABLE's ON DELETE CASCADE.
     */
    @Test
    void v7RebuildKeepsEveryMatchIdAndChildRow() throws Exception {
        SQLiteDataSource ds = walDataSource(tempDir.resolve("test.sqlite"));
        new MigrationRunner(ds).migrateTo(6);
        seedV6MatchesWithChildren(ds);

        Snapshot before = Snapshot.of(ds);
        assertThat(before.matches()).hasSize(3);
        assertThat(before.markers()).hasSize(3);
        assertThat(before.pauses()).hasSize(2);
        assertThat(before.clips()).hasSize(1);

        new MigrationRunner(ds).run(null);

        Snapshot after = Snapshot.of(ds);
        assertThat(after.matchColumns()).isEqualTo(before.matchColumns());
        assertThat(after.matches()).isEqualTo(before.matches());
        assertThat(after.markers()).isEqualTo(before.markers());
        assertThat(after.pauses()).isEqualTo(before.pauses());
        assertThat(after.clips()).isEqualTo(before.clips());
        try (Connection conn = ds.getConnection();
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            assertThat(rs.getInt(1)).isEqualTo(7);
        }
    }

    /**
     * The children reference {@code matches} by name, so after the rename they must bind to the
     * rebuilt table with ON DELETE CASCADE intact -- deleting a match still takes its markers, pauses
     * and clips with it, and leaves other matches' children alone.
     */
    @Test
    void childForeignKeysStillCascadeIntoTheRebuiltMatchesTable() throws Exception {
        SQLiteDataSource ds = walDataSource(tempDir.resolve("test.sqlite"));
        new MigrationRunner(ds).migrateTo(6);
        seedV6MatchesWithChildren(ds);
        new MigrationRunner(ds).run(null);

        try (Connection conn = ds.getConnection();
                Statement st = conn.createStatement()) {
            st.execute("DELETE FROM matches WHERE id = 9");

            assertThat(count(st, "SELECT COUNT(*) FROM markers WHERE match_id = 9")).isZero();
            assertThat(count(st, "SELECT COUNT(*) FROM pauses WHERE match_id = 9")).isZero();
            assertThat(count(st, "SELECT COUNT(*) FROM clips WHERE parent_match_id = 9")).isZero();
            assertThat(count(st, "SELECT COUNT(*) FROM markers WHERE match_id = 5")).isEqualTo(2);
            assertThat(count(st, "SELECT COUNT(*) FROM pauses WHERE match_id = 5")).isEqualTo(1);
        }
    }

    /**
     * The point of V7: an OBS heal or an abandon + rejoin splits one Dota match into several
     * recordings, and every part must be able to keep the match id (so each gets enriched) -- the
     * V1 UNIQUE forced every part after the first to drop it.
     */
    @Test
    void severalRecordingsCanShareOneDotaMatchIdAfterV7() throws Exception {
        SQLiteDataSource ds = walDataSource(tempDir.resolve("test.sqlite"));
        new MigrationRunner(ds).migrateTo(6);
        String insertPart =
                "INSERT INTO matches (dota_match_id, record_kind, created_at) VALUES (8100000333, 'match', 1)";
        try (Connection conn = ds.getConnection();
                Statement st = conn.createStatement()) {
            st.execute(insertPart);
            assertThatThrownBy(() -> st.execute(insertPart))
                    .as("precondition: V6 still enforces the UNIQUE")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("UNIQUE");
        }

        new MigrationRunner(ds).run(null);

        try (Connection conn = ds.getConnection();
                Statement st = conn.createStatement()) {
            st.execute(insertPart);
            st.execute(insertPart);
            assertThat(count(st, "SELECT COUNT(*) FROM matches WHERE dota_match_id = 8100000333"))
                    .isEqualTo(3);
        }
    }

    /**
     * The UNIQUE's implicit autoindex goes away with the rebuild; the enrichment queue and the
     * per-match-id lookups still filter on dota_match_id, so V7 replaces it with a plain non-unique
     * index -- and nothing else.
     */
    @Test
    void v7IndexesDotaMatchIdWithoutUniqueness() throws Exception {
        DataSource ds = TestDb.migrated(tempDir);

        try (Connection conn = ds.getConnection();
                Statement st = conn.createStatement()) {
            // index_list columns: seq, name, unique, origin, partial.
            assertThat(rows(st, "SELECT name, \"unique\" FROM pragma_index_list('matches')"))
                    .containsExactly(List.of("idx_matches_dota_match_id", 0));
            assertThat(rows(st, "SELECT name FROM pragma_index_info('idx_matches_dota_match_id')"))
                    .containsExactly(List.of("dota_match_id"));
        }
    }

    /**
     * Seeds a V6 DB with every matches column populated on one row (ids deliberately sparse, so an
     * id-renumbering copy would show), a pending row mid-backoff, a manual row with no match id, and
     * markers/pauses/clips hanging off them.
     */
    private static void seedV6MatchesWithChildren(DataSource ds) throws SQLException {
        try (Connection conn = ds.getConnection();
                Statement st = conn.createStatement()) {
            st.execute(
                    """
                    INSERT INTO matches (
                      id, dota_match_id, record_kind, enrichment_state, hero,
                      kills, deaths, assists, gpm, xpm, net_worth, last_hits,
                      result, lobby_type, game_mode, rank_tier, mmr_delta, duration_s,
                      record_started_wall_ms, played_at, video_path, thumb_path, file_size_bytes,
                      starred, created_at, enrich_attempts, enrich_next_after_ms
                    ) VALUES
                      (5, 8100000001, 'match', 'done', 'npc_dota_hero_puck',
                       7, 3, 12, 540, 610, 18500, 210,
                       'win', 7, 22, 54, 25, 2400,
                       1700000000000, 1700000000, 'C:/vods/a.mp4', 'C:/vods/a.jpg', 123456789,
                       1, 1700000001000, 1, NULL),
                      (9, 8100000002, 'match', 'pending', 'npc_dota_hero_axe',
                       NULL, NULL, NULL, NULL, NULL, NULL, NULL,
                       NULL, NULL, NULL, NULL, NULL, NULL,
                       1700000500000, NULL, 'C:/vods/b.mp4', NULL, NULL,
                       0, 1700000501000, 2, 1700000999000),
                      (12, NULL, 'manual', 'pending', NULL,
                       NULL, NULL, NULL, NULL, NULL, NULL, NULL,
                       NULL, NULL, NULL, NULL, NULL, NULL,
                       1700001000000, NULL, 'C:/vods/c.mp4', NULL, 42,
                       0, 1700001001000, 0, NULL)
                    """);
            st.execute(
                    """
                    INSERT INTO markers (id, match_id, type, video_offset_s, game_clock, label, source) VALUES
                      (100, 5, 'death', 61.5, 120, NULL, 'gsi'),
                      (101, 5, 'kill', 300.25, 360, 'first blood', 'gsi'),
                      (102, 9, 'death', 10.0, NULL, NULL, 'manual')
                    """);
            st.execute(
                    """
                    INSERT INTO pauses (id, match_id, start_wall, end_wall) VALUES
                      (200, 5, 1700000100000, 1700000160000),
                      (201, 9, 1700000600000, NULL)
                    """);
            st.execute(
                    """
                    INSERT INTO clips (
                      id, parent_match_id, kind, trigger_reason, start_offset_s, end_offset_s, label,
                      video_path, thumb_path, file_size_bytes, status, error, created_at, starred,
                      generation_started_at
                    ) VALUES
                      (300, 9, 'auto', 'rampage', 100.0, 130.0, 'Rampage',
                       'C:/clips/r.mp4', NULL, 4567, 'ready', NULL, 1700000700000, 1, 1700000699000)
                    """);
        }
    }

    /** Full contents of matches + its children, plus matches' column layout (name/type/notnull/default/pk). */
    private record Snapshot(
            List<List<Object>> matchColumns,
            List<List<Object>> matches,
            List<List<Object>> markers,
            List<List<Object>> pauses,
            List<List<Object>> clips) {

        static Snapshot of(DataSource ds) throws SQLException {
            try (Connection conn = ds.getConnection();
                    Statement st = conn.createStatement()) {
                return new Snapshot(
                        rows(st, "PRAGMA table_info(matches)"),
                        rows(st, "SELECT * FROM matches ORDER BY id"),
                        rows(st, "SELECT * FROM markers ORDER BY id"),
                        rows(st, "SELECT * FROM pauses ORDER BY id"),
                        rows(st, "SELECT * FROM clips ORDER BY id"));
            }
        }
    }

    private static List<List<Object>> rows(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            int columns = rs.getMetaData().getColumnCount();
            List<List<Object>> out = new ArrayList<>();
            while (rs.next()) {
                List<Object> row = new ArrayList<>(columns);
                for (int i = 1; i <= columns; i++) {
                    row.add(rs.getObject(i));
                }
                out.add(row);
            }
            return out;
        }
    }

    private static int count(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            return rs.getInt(1);
        }
    }

    private SQLiteDataSource walDataSource(Path dbFile) {
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(5000);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.enforceForeignKeys(true);

        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        return ds;
    }

    /** Production-shaped (Hikari over the FK-enforcing SQLite source), but one slot so reuse is certain. */
    private HikariDataSource singleConnectionPool(Path dbFile) {
        HikariConfig hikari = new HikariConfig();
        hikari.setDataSource(walDataSource(dbFile));
        hikari.setMaximumPoolSize(1);
        return new HikariDataSource(hikari);
    }

    private static SQLiteConnection physicalConnection(HikariDataSource pool) throws SQLException {
        try (Connection conn = pool.getConnection()) {
            return conn.unwrap(SQLiteConnection.class);
        }
    }

    private static int foreignKeysPragma(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("PRAGMA foreign_keys")) {
            return rs.getInt(1);
        }
    }
}

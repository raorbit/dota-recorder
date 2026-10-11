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
import java.util.List;
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

        try (Connection conn = ds.getConnection();
                Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE backup_probe (id INTEGER PRIMARY KEY, name TEXT NOT NULL)");
            st.execute("INSERT INTO backup_probe (name) VALUES ('before')");
            st.execute("PRAGMA user_version = 2");
        }

        new MigrationRunner(ds).run(null);

        List<Path> backups;
        try (var stream = Files.list(tempDir)) {
            backups =
                    stream.filter(path -> path.getFileName().toString().endsWith(".bak"))
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

    /**
     * A script that leaves ANY foreign-key violation behind must roll back (enforcement was off, so
     * {@code PRAGMA foreign_key_check} is the only guard), and the finally must still switch
     * enforcement back on for the pooled connection -- the failure path is exactly where a leak would
     * hide. A pre-existing orphan (inserted by an FK-unaware connection, e.g. a sqlite3 CLI session)
     * trips the check for the latest script.
     */
    @Test
    void failingMigrationRollsBackAndStillReenablesForeignKeys() throws Exception {
        Path dbFile = tempDir.resolve("test.sqlite");
        try (HikariDataSource pool = singleConnectionPool(dbFile)) {
            new MigrationRunner(pool).migrateTo(MigrationRunner.LATEST_VERSION - 1);
            try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
                    Statement st = raw.createStatement()) {
                st.execute("PRAGMA foreign_keys = OFF");
                st.execute("INSERT INTO markers (match_id, type, video_offset_s) VALUES (999, 'death', 1.0)");
            }
            SQLiteConnection physicalBefore = physicalConnection(pool);

            MigrationRunner runner = new MigrationRunner(pool);
            assertThatThrownBy(() -> runner.run(null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("foreign_key_check");

            assertThat(runner.isReady()).isFalse();
            try (Connection conn = pool.getConnection();
                    Statement st = conn.createStatement()) {
                assertThat(conn.unwrap(SQLiteConnection.class)).isSameAs(physicalBefore);
                assertThat(foreignKeysPragma(conn)).isEqualTo(1);
                try (ResultSet rs = st.executeQuery("PRAGMA user_version")) {
                    assertThat(rs.getInt(1))
                            .as("the failed script rolled back, user_version stamp included")
                            .isEqualTo(MigrationRunner.LATEST_VERSION - 1);
                }
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM markers WHERE match_id = 999")) {
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
            }
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

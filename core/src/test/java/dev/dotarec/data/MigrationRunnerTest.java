package dev.dotarec.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;
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
}

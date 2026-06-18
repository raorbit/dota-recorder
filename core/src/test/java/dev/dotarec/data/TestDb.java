package dev.dotarec.data;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;

/**
 * Builds a real, file-backed SQLite {@link DataSource} (mirroring production pragmas) over a path in
 * the caller's {@code @TempDir}, then applies the V1 schema via the production {@link MigrationRunner}
 * so tests exercise the exact migration path -- no mocks, no in-memory schema drift.
 */
public final class TestDb {

    private TestDb() {
    }

    /** Creates a temp-file SQLite data source with the V1 schema applied. */
    public static DataSource migrated(Path dir) throws Exception {
        Path dbFile = dir.resolve("test.sqlite");

        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(5000);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.enforceForeignKeys(true);

        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());

        // Reuse the production migrator so the test schema is the real schema.
        new MigrationRunner(ds).run(null);
        return ds;
    }
}

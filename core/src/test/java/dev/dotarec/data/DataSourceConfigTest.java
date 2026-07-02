package dev.dotarec.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;

/**
 * Verifies {@link DataSourceConfig}'s per-connection SQLite settings actually reach the physical
 * connections the Hikari pool hands out — most importantly the IMMEDIATE transaction mode. Every
 * explicit ({@code setAutoCommit(false)}) transaction in the app is read-then-write, and under WAL a
 * driver-default DEFERRED read-&gt;write upgrade fails instantly with SQLITE_BUSY_SNAPSHOT (the busy
 * handler is deliberately not invoked, so busy_timeout never engages) whenever any commit lands
 * between the read and the write — rolling back e.g. an entire finalize. BEGIN IMMEDIATE takes the
 * write lock up front, where busy_timeout does apply.
 */
class DataSourceConfigTest {

    @Test
    void pooledConnectionsBeginTransactionsImmediate(@TempDir Path dir) throws Exception {
        DataSource ds = new DataSourceConfig(dir.resolve("t.sqlite").toString()).dataSource();
        try (Connection conn = ds.getConnection()) {
            SQLiteConnection sqlite = conn.unwrap(SQLiteConnection.class);
            assertThat(sqlite.getConnectionConfig().getTransactionMode())
                    .isEqualTo(SQLiteConfig.TransactionMode.IMMEDIATE);
        } finally {
            ((HikariDataSource) ds).close();
        }
    }
}

package dev.dotarec.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * SQLite {@link DataSource} for the local metadata DB.
 *
 * <p>The four required pragmas are baked into {@link SQLiteConfig} so they are applied on
 * EVERY physical connection (sqlite-jdbc applies the SQLiteConfig per physical connection):
 * busy_timeout=5000, journal_mode=WAL, synchronous=NORMAL, foreign_keys=ON.
 *
 * <p>The {@link SQLiteDataSource} is wrapped in a small {@link HikariDataSource} pool: during a
 * recording the GSI feed drives ~10Hz journal writes, and an unpooled source would open/close a
 * physical SQLite file handle (re-running the pragmas) on every call. A warm 1–2 connection pool
 * reuses connections instead. SQLite is single-writer, so the pool stays intentionally tiny; WAL +
 * busy_timeout already serialize the rare concurrent writer (the scheduled enrich/retention jobs).
 *
 * <p>DB path resolution: the {@code app.db-path} property wins if set (the core-skeleton
 * module's AppPaths can supply it); otherwise it defaults to
 * {@code %APPDATA%/dota-recorder/dota-recorder.sqlite}. The parent directory is created
 * if missing so a fresh install boots cleanly.
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    private final String configuredPath;

    public DataSourceConfig(@Value("${app.db-path:}") String configuredPath) {
        this.configuredPath = configuredPath;
    }

    @Bean
    public DataSource dataSource() {
        Path dbPath = resolveDbPath();
        try {
            Files.createDirectories(dbPath.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create data directory: " + dbPath.getParent(), e);
        }

        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(5000);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.enforceForeignKeys(true);

        SQLiteDataSource sqlite = new SQLiteDataSource(config);
        sqlite.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());

        HikariConfig hikari = new HikariConfig();
        hikari.setDataSource(sqlite);
        hikari.setPoolName("dota-sqlite");
        // SQLite is single-writer; a tiny pool warms a connection for the ~10Hz journal-write hot path
        // without inviting writer contention. The pragmas live in SQLiteConfig, so each pooled physical
        // connection still gets WAL/busy_timeout/foreign_keys when sqlite-jdbc opens it.
        hikari.setMaximumPoolSize(2);
        log.info("SQLite data source at {} (pooled, max 2)", dbPath.toAbsolutePath());
        return new HikariDataSource(hikari);
    }

    private Path resolveDbPath() {
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Paths.get(configuredPath);
        }
        String appData = System.getenv("APPDATA");
        Path base = (appData != null && !appData.isBlank())
                ? Paths.get(appData, "dota-recorder")
                : Paths.get(System.getProperty("user.home"), ".dota-recorder");
        return base.resolve("dota-recorder.sqlite");
    }
}

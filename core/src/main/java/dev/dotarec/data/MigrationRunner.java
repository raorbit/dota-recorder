package dev.dotarec.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Forward-only schema migrator keyed on {@code PRAGMA user_version}.
 *
 * <p>On startup it reads the current user_version; if below {@link #LATEST_VERSION} it copies
 * the existing .sqlite file to a timestamped .bak (skipped when the file is absent/empty on a
 * fresh install), then applies each pending migration script inside a transaction. SQLite DDL is
 * transactional, so a script that fails part-way rolls back whole; the pre-migration file copy is
 * the safety net for what a rollback cannot undo (a script that commits but is wrong, a corrupt file).
 *
 * <p>Every script runs under SQLite's documented 12-step ALTER procedure, because a table rebuild
 * (create new / copy / DROP old / rename -- the only way to drop a column constraint such as a
 * {@code UNIQUE}) is destructive with foreign keys on: DROP TABLE on a parent table performs an
 * implicit DELETE that FIRES the children's {@code ON DELETE CASCADE}, silently wiping every marker,
 * pause and clip of every match. So {@code applyScript}:
 * <ol>
 *   <li>switches {@code foreign_keys} OFF while still in autocommit and reads it back -- the pragma
 *       is a silent no-op inside a transaction, and this connection BEGINs IMMEDIATE the moment
 *       autocommit goes false (see {@link DataSourceConfig});</li>
 *   <li>runs the script's statements in one transaction;</li>
 *   <li>runs {@code PRAGMA foreign_key_check} before commit and rolls back on ANY returned row --
 *       with enforcement off, nothing else would catch a script that orphaned child rows;</li>
 *   <li>in a {@code finally}, back in autocommit, ALWAYS switches {@code foreign_keys} back ON. The
 *       connection is a pooled Hikari one: leaving enforcement off -- including after a failed
 *       migration -- would silently leak unenforced FKs (and thus non-cascading deletes) to every
 *       later borrower of that physical connection.</li>
 * </ol>
 * Scripts that do no rebuild pay only two pragma round-trips for this.
 *
 * <p>TODO: when JOOQ is introduced (plan stack), run codegen against the migrated DB; this runner
 * stays the source of truth for DDL. TODO: add V2+ scripts to {@link #SCRIPTS} as the schema grows.
 */
@Component
@Order(0)
public class MigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrationRunner.class);

    public static final int LATEST_VERSION = 6;
    private static final String[] SCRIPTS = {
        "db/migration/V1__init.sql",
        "db/migration/V2__enrich_retry.sql",
        "db/migration/V3__recording_journal.sql",
        "db/migration/V4__clips.sql",
        "db/migration/V5__clip_starred.sql",
        "db/migration/V6__clip_generation_started.sql",
    };

    private final DataSource dataSource;
    private final AtomicInteger currentVersion = new AtomicInteger(0);
    private volatile boolean ready;

    public MigrationRunner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        migrateTo(LATEST_VERSION);
    }

    /**
     * Applies every pending script up to {@code targetVersion}. Production ({@link #run}) always
     * targets {@link #LATEST_VERSION}; a lower target exists only so tests can stage a DB at an
     * older schema through the real scripts (seed rows at V6, then migrate and assert nothing was
     * lost) instead of hand-copying DDL that could drift from the shipped migrations.
     */
    void migrateTo(int targetVersion) throws SQLException, IOException {
        try (Connection conn = dataSource.getConnection()) {
            int version = readUserVersion(conn);
            currentVersion.set(version);

            if (version >= targetVersion) {
                ready = true;
                log.info("Schema up to date (user_version={})", version);
                return;
            }

            backupIfNeeded(conn, version);

            for (int next = version + 1; next <= targetVersion; next++) {
                applyScript(conn, SCRIPTS[next - 1], next);
                currentVersion.set(next);
            }
            ready = true;
            log.info("Migration complete (user_version={})", currentVersion.get());
        }
    }

    /** Latest applied schema version; surfaced via /health. */
    public int currentSchemaVersion() {
        return currentVersion.get();
    }

    /** True once startup migrations have completed successfully. */
    public boolean isReady() {
        return ready && currentVersion.get() >= LATEST_VERSION;
    }

    private int readUserVersion(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void backupIfNeeded(Connection conn, int fromVersion) {
        String url = jdbcUrl(conn);
        if (url == null || !url.startsWith("jdbc:sqlite:")) {
            return;
        }
        String raw = url.substring("jdbc:sqlite:".length());
        if (raw.isEmpty() || ":memory:".equals(raw)) {
            return;
        }
        Path db = Paths.get(raw);
        try {
            if (!Files.exists(db) || Files.size(db) == 0) {
                return; // fresh DB: nothing worth backing up
            }
            checkpointWal(conn);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path bak = db.resolveSibling(db.getFileName() + ".v" + fromVersion + "." + stamp + ".bak");
            Files.copy(db, bak, StandardCopyOption.COPY_ATTRIBUTES);
            copySidecarIfPresent(db, bak, "-wal");
            copySidecarIfPresent(db, bak, "-shm");
            log.info("Backed up DB before migration: {}", bak.getFileName());
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Pre-migration backup failed for " + db, e);
        }
    }

    private void checkpointWal(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
    }

    private void copySidecarIfPresent(Path db, Path bak, String suffix) throws IOException {
        Path sidecar = db.resolveSibling(db.getFileName() + suffix);
        if (!Files.exists(sidecar) || Files.size(sidecar) == 0) {
            return;
        }
        Path sidecarBak = bak.resolveSibling(bak.getFileName() + suffix);
        Files.copy(sidecar, sidecarBak, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private String jdbcUrl(Connection conn) {
        try {
            return conn.getMetaData().getURL();
        } catch (SQLException e) {
            return null;
        }
    }

    /**
     * Applies one script under the 12-step procedure described on the class: FKs off (autocommit),
     * statements + {@code foreign_key_check} in one transaction, FKs back on (autocommit) no matter
     * what. The runner owns {@code conn} (borrowed in {@link #migrateTo}), so forcing autocommit on
     * here cannot commit a caller's transaction -- there is none.
     */
    private void applyScript(Connection conn, String classpathLocation, int version) throws SQLException, IOException {
        String sql = readClasspath(classpathLocation);
        boolean prevAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(true);
        try {
            setForeignKeys(conn, false);
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                for (String stmt : splitStatements(sql)) {
                    st.execute(stmt);
                }
                assertNoForeignKeyViolations(st, version);
                conn.commit();
                log.info("Applied migration V{}", version);
            } catch (SQLException | RuntimeException e) {
                // RuntimeException too: the finally's setAutoCommit(true) COMMITS an open transaction
                // (JDBC contract), so any escape path that skipped this rollback would persist a
                // half-applied script.
                conn.rollback();
                throw e;
            }
        } finally {
            conn.setAutoCommit(true);
            setForeignKeys(conn, true);
            conn.setAutoCommit(prevAutoCommit);
        }
    }

    /**
     * Sets {@code PRAGMA foreign_keys} and reads it back. The pragma is a SILENT no-op inside an open
     * transaction, so a wrong autocommit ordering would otherwise go unnoticed with enforcement in the
     * wrong state: still ON means a rebuild's DROP TABLE cascade-deletes every child row; still OFF
     * means unenforced FKs leak to the next pool borrower. Failing loudly beats both.
     */
    private static void setForeignKeys(Connection conn, boolean on) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(on ? "PRAGMA foreign_keys = ON" : "PRAGMA foreign_keys = OFF");
            try (ResultSet rs = st.executeQuery("PRAGMA foreign_keys")) {
                int actual = rs.next() ? rs.getInt(1) : -1;
                if (actual != (on ? 1 : 0)) {
                    throw new SQLException("PRAGMA foreign_keys did not switch " + (on ? "ON" : "OFF")
                            + " (reads " + actual + "); refusing to continue with FK enforcement in the wrong state");
                }
            }
        }
    }

    /**
     * Fails the in-flight migration (the caller's catch rolls it back) if the DB holds ANY
     * foreign-key violation. With enforcement off for the script, this is the only thing standing
     * between a buggy rebuild and silently orphaned markers/pauses/clips.
     */
    private static void assertNoForeignKeyViolations(Statement st, int version) throws SQLException {
        try (ResultSet rs = st.executeQuery("PRAGMA foreign_key_check")) {
            if (!rs.next()) {
                return;
            }
            // Columns: child table, child rowid, parent table, fk index. Report the first + a count.
            String first = rs.getString(1) + " rowid " + rs.getString(2) + " -> missing " + rs.getString(3);
            int count = 1;
            while (rs.next()) {
                count++;
            }
            throw new SQLException("Migration V" + version + " rolled back: PRAGMA foreign_key_check found "
                    + count + " violation(s), first: " + first);
        }
    }

    private String readClasspath(String location) throws IOException {
        ClassPathResource resource = new ClassPathResource(location);
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Splits a controlled migration script into executable statements. Strips full-line
     * {@code --} comments and splits on {@code ;}. The migration files are hand-authored and
     * contain no semicolons inside string literals or triggers, so this stays safe.
     */
    private List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : sql.split("\\r?\\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            current.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                String stmt = current.toString().strip();
                stmt = stmt.substring(0, stmt.length() - 1).strip();
                if (!stmt.isEmpty()) {
                    statements.add(stmt);
                }
                current.setLength(0);
            }
        }
        String tail = current.toString().strip();
        if (!tail.isEmpty()) {
            statements.add(tail);
        }
        return statements;
    }
}

package dev.dotarec.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
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
 * fresh install), then applies each pending migration script inside a transaction. SQLite has no
 * transactional DDL rollback, so the pre-migration file copy is the real corruption safety net.
 *
 * <p>TODO: when JOOQ is introduced (plan stack), run codegen against the migrated DB; this runner
 * stays the source of truth for DDL. TODO: add V2+ scripts to {@link #SCRIPTS} as the schema grows.
 */
@Component
public class MigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrationRunner.class);

    private static final int LATEST_VERSION = 1;
    private static final String[] SCRIPTS = {"db/migration/V1__init.sql"};

    private final DataSource dataSource;
    private final AtomicInteger currentVersion = new AtomicInteger(0);

    public MigrationRunner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            int version = readUserVersion(conn);
            currentVersion.set(version);

            if (version >= LATEST_VERSION) {
                log.info("Schema up to date (user_version={})", version);
                return;
            }

            backupIfNeeded(conn, version);

            for (int next = version + 1; next <= LATEST_VERSION; next++) {
                applyScript(conn, SCRIPTS[next - 1], next);
                currentVersion.set(next);
            }
            log.info("Migration complete (user_version={})", currentVersion.get());
        }
    }

    /** Latest applied schema version; surfaced via /health. */
    public int currentSchemaVersion() {
        return currentVersion.get();
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
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path bak = db.resolveSibling(db.getFileName() + ".v" + fromVersion + "." + stamp + ".bak");
            Files.copy(db, bak, StandardCopyOption.COPY_ATTRIBUTES);
            log.info("Backed up DB before migration: {}", bak.getFileName());
        } catch (IOException e) {
            throw new IllegalStateException("Pre-migration backup failed for " + db, e);
        }
    }

    private String jdbcUrl(Connection conn) {
        try {
            return conn.getMetaData().getURL();
        } catch (SQLException e) {
            return null;
        }
    }

    private void applyScript(Connection conn, String classpathLocation, int version) throws SQLException, IOException {
        String sql = readClasspath(classpathLocation);
        boolean prevAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            for (String stmt : splitStatements(sql)) {
                st.execute(stmt);
            }
            conn.commit();
            log.info("Applied migration V{}", version);
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(prevAutoCommit);
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

package dev.dotarec.data;

import com.zaxxer.hikari.HikariDataSource;
import dev.dotarec.data.MatchRepository.NewMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the retention sweep's atomic row operations: {@link MatchRepository#claimForSweep}
 * claims only a still-non-starred row that still points at the snapshotted video (the starred
 * re-check is one UPDATE), runs the unlink only for a row it claimed and with no write lock or
 * pooled connection held, and prunes only once that unlink has removed the file;
 * {@link MatchRepository#reconcileMissingVideo} prunes regardless of starred but only while the
 * row still points at the vanished path.
 */
class MatchRepositorySweepClaimTest {

    private MatchRepository repo;
    private Path dbFile;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        DataSource ds = TestDb.migrated(dir);
        dbFile = dir.resolve("test.sqlite"); // the file TestDb migrated
        repo = new MatchRepository(ds);
    }

    @Test
    void claimForSweepPrunesANonStarredRowExactlyOnce() {
        long id = insert("C:\\vods\\old.mp4", "C:\\vods\\old.jpg", 100L, false);

        assertThat(repo.claimForSweep(id, "C:\\vods\\old.mp4", () -> true)).isTrue();

        MatchSummary claimed = repo.findById(id).orElseThrow();
        assertThat(claimed.videoPath()).isNull();
        assertThat(claimed.thumbPath()).isNull();
        assertThat(claimed.fileSizeBytes()).isNull();
        // A second claim finds no matching video_path: the row was already pruned.
        AtomicBoolean unlinked = new AtomicBoolean();
        assertThat(repo.claimForSweep(id, "C:\\vods\\old.mp4", () -> unlinked.getAndSet(true)))
                .isFalse();
        assertThat(unlinked).as("nothing to unlink for a row already pruned").isFalse();
    }

    @Test
    void claimForSweepRefusesAStarredRow() {
        long id = insert("C:\\vods\\starred.mp4", null, 100L, true);
        AtomicBoolean unlinked = new AtomicBoolean();

        assertThat(repo.claimForSweep(id, "C:\\vods\\starred.mp4", () -> unlinked.getAndSet(true)))
                .isFalse();
        assertThat(unlinked).as("a starred row's file is never unlinked").isFalse();
        assertThat(repo.findById(id).orElseThrow().videoPath()).isEqualTo("C:\\vods\\starred.mp4");
    }

    @Test
    void claimForSweepRefusesARepointedRow() {
        long id = insert("C:\\vods\\old.mp4", null, 100L, false);
        // The archiver moved the file since the sweeper's snapshot.
        repo.updateVideoPath(id, "D:\\archive\\old.mp4", null);
        AtomicBoolean unlinked = new AtomicBoolean();

        assertThat(repo.claimForSweep(id, "C:\\vods\\old.mp4", () -> unlinked.getAndSet(true)))
                .isFalse();
        assertThat(unlinked).isFalse();
        assertThat(repo.findById(id).orElseThrow().videoPath()).isEqualTo("D:\\archive\\old.mp4");
    }

    @Test
    void nothingIsPrunedWhileTheUnlinkRuns() {
        // A process ended mid-unlink must leave the row pointing at its file, never pruned while the
        // file may still be on disk, so the prune runs only once the unlink reports the file gone.
        // Another connection, which is what the next boot reads, still sees the path meanwhile.
        long id = insert("C:\\vods\\old.mp4", "C:\\vods\\old.jpg", 100L, false);
        AtomicReference<MatchSummary> duringUnlink = new AtomicReference<>();

        assertThat(repo.claimForSweep(id, "C:\\vods\\old.mp4", () -> {
            duringUnlink.set(repo.findById(id).orElseThrow()); // another connection's view
            return true;
        })).isTrue();

        assertThat(duringUnlink.get().videoPath())
                .as("the row is not yet pruned while the file is being unlinked")
                .isEqualTo("C:\\vods\\old.mp4");
        assertThat(repo.findById(id).orElseThrow().videoPath()).isNull();
    }

    @Test
    void theUnlinkRunsWithNoWriteLockAndNoPooledConnectionHeld() throws Exception {
        // The unlink can block for seconds (an archive HDD spinning up, a NAS share waking its
        // disks). Through all of it the production pool must still have both its connections to
        // hand out, and another writer must get the write lock at once, not wait on the delete and
        // fail with SQLITE_BUSY once busy_timeout runs out.
        long id = insert("C:\\vods\\old.mp4", "C:\\vods\\old.jpg", 100L, false);
        DataSource production = new DataSourceConfig(dbFile.toString()).dataSource();
        try (HikariDataSource pool = (HikariDataSource) production) {
            MatchRepository pooled = new MatchRepository(pool);
            AtomicInteger connectionsInUse = new AtomicInteger(-1);
            AtomicReference<String> otherWriter = new AtomicReference<>();

            assertThat(pooled.claimForSweep(id, "C:\\vods\\old.mp4", () -> {
                connectionsInUse.set(pool.getHikariPoolMXBean().getActiveConnections());
                otherWriter.set(beginImmediateWithoutWaiting());
                return true;
            })).isTrue();

            assertThat(connectionsInUse)
                    .as("pooled connections in use during the unlink")
                    .hasValue(0);
            assertThat(otherWriter)
                    .as("another writer during the unlink")
                    .hasValue("took the write lock");
        }
        assertThat(repo.findById(id).orElseThrow().videoPath()).isNull();
    }

    @Test
    void aRowStarredDuringTheUnlinkIsStillPruned() {
        // The star lands after the claim, so the video goes all the same. The prune then catches
        // the row up to its file being gone, rather than leave a starred row pointing at nothing.
        long id = insert("C:\\vods\\old.mp4", "C:\\vods\\old.jpg", 100L, false);

        assertThat(repo.claimForSweep(id, "C:\\vods\\old.mp4", () -> {
            repo.setStarred(id, true);
            return true;
        })).isTrue();

        MatchSummary pruned = repo.findById(id).orElseThrow();
        assertThat(pruned.videoPath()).isNull();
        assertThat(pruned.thumbPath()).isNull();
        assertThat(pruned.fileSizeBytes()).isNull();
        assertThat(pruned.starred()).isTrue();
    }

    @Test
    void aRowRepointedDuringTheUnlinkIsNotPruned() {
        // The prune is guarded on the snapshotted video too: a row that points at another file by
        // then keeps it.
        long id = insert("C:\\vods\\old.mp4", "C:\\vods\\old.jpg", 100L, false);

        assertThat(repo.claimForSweep(id, "C:\\vods\\old.mp4", () -> {
            repo.updateVideoPath(id, "D:\\archive\\old.mp4", "D:\\archive\\old.jpg");
            return true;
        })).isFalse();

        MatchSummary kept = repo.findById(id).orElseThrow();
        assertThat(kept.videoPath()).isEqualTo("D:\\archive\\old.mp4");
        assertThat(kept.thumbPath()).isEqualTo("D:\\archive\\old.jpg");
        assertThat(kept.fileSizeBytes()).isEqualTo(100L);
    }

    @Test
    void aFailedUnlinkLeavesTheRowAsItWas() {
        long id = insert("C:\\vods\\locked.mp4", "C:\\vods\\locked.jpg", 100L, false);

        // The file is still on disk (a locked .mp4, say): the row keeps referencing it.
        assertThat(repo.claimForSweep(id, "C:\\vods\\locked.mp4", () -> false)).isFalse();

        MatchSummary kept = repo.findById(id).orElseThrow();
        assertThat(kept.videoPath()).isEqualTo("C:\\vods\\locked.mp4");
        assertThat(kept.thumbPath()).isEqualTo("C:\\vods\\locked.jpg");
        assertThat(kept.fileSizeBytes()).isEqualTo(100L);
    }

    @Test
    void anUnlinkThatThrowsLeavesTheRowAsItWas() {
        long id = insert("C:\\vods\\old.mp4", "C:\\vods\\old.jpg", 100L, false);

        assertThatThrownBy(() -> repo.claimForSweep(id, "C:\\vods\\old.mp4", () -> {
            throw new IllegalStateException("unlink blew up");
        })).hasMessage("unlink blew up");

        MatchSummary kept = repo.findById(id).orElseThrow();
        assertThat(kept.videoPath()).isEqualTo("C:\\vods\\old.mp4");
        assertThat(kept.thumbPath()).isEqualTo("C:\\vods\\old.jpg");
        assertThat(kept.fileSizeBytes()).isEqualTo(100L);
    }

    @Test
    void reconcileMissingVideoPrunesStarredRowsToo() {
        long id = insert("C:\\vods\\gone.mp4", "C:\\vods\\gone.jpg", 100L, true);

        assertThat(repo.reconcileMissingVideo(id, "C:\\vods\\gone.mp4")).isEqualTo(1);

        MatchSummary reconciled = repo.findById(id).orElseThrow();
        assertThat(reconciled.videoPath()).isNull();
        assertThat(reconciled.thumbPath()).isNull();
        assertThat(reconciled.fileSizeBytes()).isNull();
        assertThat(reconciled.starred()).isTrue();
    }

    @Test
    void reconcileMissingVideoDoesNotClobberARepointedRow() {
        long id = insert("C:\\vods\\gone.mp4", null, 100L, false);
        repo.updateVideoPath(id, "D:\\archive\\moved.mp4", null);

        assertThat(repo.reconcileMissingVideo(id, "C:\\vods\\gone.mp4")).isZero();
        assertThat(repo.findById(id).orElseThrow().videoPath()).isEqualTo("D:\\archive\\moved.mp4");
    }

    private long insert(String videoPath, String thumbPath, Long fileSizeBytes, boolean starred) {
        return repo.insert(new NewMatch(
                null, "match", "enriched", "puck",
                1, 2, 3, 400, 500, 10000, 120,
                "win", 7, 22, null, null, 1800,
                1_000L, videoPath, thumbPath, fileSizeBytes, starred, 1_000L, null));
    }

    /** Tries to take the write lock on a connection of its own that never waits for it. */
    private String beginImmediateWithoutWaiting() {
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(0);
        try (Connection conn = config.createConnection("jdbc:sqlite:" + dbFile);
             Statement st = conn.createStatement()) {
            st.execute("BEGIN IMMEDIATE");
            st.execute("ROLLBACK");
            return "took the write lock";
        } catch (SQLException e) {
            return e.getMessage();
        }
    }
}

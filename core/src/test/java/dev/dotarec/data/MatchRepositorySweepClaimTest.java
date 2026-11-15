package dev.dotarec.data;

import dev.dotarec.data.MatchRepository.NewMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the retention sweep's atomic row operations: {@link MatchRepository#claimForSweep} prunes
 * only a still-non-starred row that still points at the snapshotted video (the starred re-check and
 * the prune are one UPDATE), runs the unlink only for a row it claimed, and makes the prune durable
 * only once that unlink has removed the file; {@link MatchRepository#reconcileMissingVideo} prunes
 * regardless of starred but only while the row still points at the vanished path.
 */
class MatchRepositorySweepClaimTest {

    private MatchRepository repo;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        DataSource ds = TestDb.migrated(dir);
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
    void theClaimIsNotDurableWhileItsUnlinkRuns() {
        // A process ended mid-unlink must leave the row pointing at its file, never pruned while the
        // file may still be on disk, so the prune commits only once the unlink reports the file gone.
        // Another connection, which is what the next boot reads, still sees the path meanwhile.
        long id = insert("C:\\vods\\old.mp4", "C:\\vods\\old.jpg", 100L, false);
        AtomicReference<MatchSummary> duringUnlink = new AtomicReference<>();

        assertThat(repo.claimForSweep(id, "C:\\vods\\old.mp4", () -> {
            duringUnlink.set(repo.findById(id).orElseThrow()); // another connection's view
            return true;
        })).isTrue();

        assertThat(duringUnlink.get().videoPath())
                .as("the prune is not yet committed while the file is being unlinked")
                .isEqualTo("C:\\vods\\old.mp4");
        assertThat(repo.findById(id).orElseThrow().videoPath()).isNull();
    }

    @Test
    void aFailedUnlinkRollsTheClaimBack() {
        long id = insert("C:\\vods\\locked.mp4", "C:\\vods\\locked.jpg", 100L, false);

        // The file is still on disk (a locked .mp4, say): the row keeps referencing it.
        assertThat(repo.claimForSweep(id, "C:\\vods\\locked.mp4", () -> false)).isFalse();

        MatchSummary kept = repo.findById(id).orElseThrow();
        assertThat(kept.videoPath()).isEqualTo("C:\\vods\\locked.mp4");
        assertThat(kept.thumbPath()).isEqualTo("C:\\vods\\locked.jpg");
        assertThat(kept.fileSizeBytes()).isEqualTo(100L);
    }

    @Test
    void anUnlinkThatThrowsRollsTheClaimBack() {
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
}

package dev.dotarec.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives {@link ClipRepository} against a real, migrated SQLite DB ({@link TestDb}) — no mocks, so the
 * compare-and-set status transitions run against the actual schema. Focuses on
 * {@link ClipRepository#updateStatusIfGenerating}, the terminal-write CAS that stops a slow render's
 * original worker from resurrecting/overwriting a row that was already re-pended and re-claimed.
 */
class ClipRepositoryTest {

    private DataSource ds;
    private ClipRepository clips;
    private MatchRepository matches;
    private long parentMatchId;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        ds = TestDb.migrated(dir);
        clips = new ClipRepository(ds);
        matches = new MatchRepository(ds);
        parentMatchId = matches.insert(new MatchRepository.NewMatch(
                null, "match", "enriched", "puck",
                null, null, null, null, null, null, null,
                null, null, null, null, null, 1800,
                null, "video.mp4", null, null, false, System.currentTimeMillis(), null));
    }

    @Test
    void updateStatusIfGenerating_onGeneratingRow_writesReady() {
        long clipId = insertClip("generating");

        int updated = clips.updateStatusIfGenerating(clipId, "ready", "out.mp4", 64L, "thumb.jpg", null);

        assertThat(updated).isEqualTo(1);
        ClipRow row = clips.findById(clipId).orElseThrow();
        assertThat(row.status()).isEqualTo("ready");
        assertThat(row.videoPath()).isEqualTo("out.mp4");
        assertThat(row.fileSizeBytes()).isEqualTo(64L);
        assertThat(row.thumbPath()).isEqualTo("thumb.jpg");
    }

    @Test
    void updateStatusIfGenerating_onPendingRow_isNoOp() {
        // A row already re-pended (and possibly re-claimed by a second worker) must not be overwritten
        // by the original worker's terminal write — the CAS on status='generating' fails.
        long clipId = insertClip("pending");

        int readyUpdated =
                clips.updateStatusIfGenerating(clipId, "ready", "out.mp4", 64L, "thumb.jpg", null);
        int failedUpdated =
                clips.updateStatusIfGenerating(clipId, "failed", null, null, null, "boom");

        assertThat(readyUpdated).isZero();
        assertThat(failedUpdated).isZero();
        // The row is untouched: still pending, no outputs, no error.
        ClipRow row = clips.findById(clipId).orElseThrow();
        assertThat(row.status()).isEqualTo("pending");
        assertThat(row.videoPath()).isNull();
        assertThat(row.fileSizeBytes()).isNull();
        assertThat(row.thumbPath()).isNull();
        assertThat(row.error()).isNull();
    }

    @Test
    void updateStatusIfGenerating_onGeneratingRow_writesFailed() {
        long clipId = insertClip("generating");

        int updated = clips.updateStatusIfGenerating(clipId, "failed", null, null, null, "ffmpeg blew up");

        assertThat(updated).isEqualTo(1);
        ClipRow row = clips.findById(clipId).orElseThrow();
        assertThat(row.status()).isEqualTo("failed");
        assertThat(row.error()).isEqualTo("ffmpeg blew up");
    }

    @Test
    void touchGenerationStarted_withOwnClaimStamp_refreshesTheStaleCutoffAnchor() {
        // Claimed two hours ago (a long maintenance-lock wait): the row reads as stale.
        long now = System.currentTimeMillis();
        long claimedAt = now - 2 * 60 * 60_000L;
        long clipId = insertClip("pending");
        assertThat(clips.claimForGeneration(clipId, claimedAt)).isTrue();
        assertThat(clips.findStaleGenerating(now - 60 * 60_000L))
                .extracting(ClipRow::id).contains(clipId);

        // The owner re-stamps right before the cut: the fence matches its own claim stamp.
        assertThat(clips.touchGenerationStarted(clipId, claimedAt, now)).isTrue();

        // Staleness now anchors on the cut start, so the row no longer reads as stale.
        assertThat(clips.findStaleGenerating(now - 60 * 60_000L))
                .extracting(ClipRow::id).doesNotContain(clipId);
    }

    @Test
    void touchGenerationStarted_afterRependAndReclaim_failsTheFence() {
        // The original worker claimed, then was re-pended mid-wait and a second worker re-claimed with
        // a NEW stamp. The original's fenced re-stamp must fail so it never cuts against the new owner.
        long clipId = insertClip("pending");
        long firstClaim = System.currentTimeMillis() - 2 * 60 * 60_000L;
        assertThat(clips.claimForGeneration(clipId, firstClaim)).isTrue();
        assertThat(clips.rependIfOrphaned(clipId, System.currentTimeMillis())).isTrue();
        long secondClaim = System.currentTimeMillis();
        assertThat(clips.claimForGeneration(clipId, secondClaim)).isTrue();

        assertThat(clips.touchGenerationStarted(clipId, firstClaim, System.currentTimeMillis()))
                .isFalse();
        // The new owner's own fence still holds.
        assertThat(clips.touchGenerationStarted(clipId, secondClaim, System.currentTimeMillis()))
                .isTrue();
    }

    @Test
    void touchGenerationStarted_onNonGeneratingRow_fails() {
        long now = System.currentTimeMillis();
        long clipId = insertClip("pending");

        assertThat(clips.touchGenerationStarted(clipId, now, now)).isFalse();
        assertThat(clips.findById(clipId).orElseThrow().status()).isEqualTo("pending");
    }

    @Test
    void rependIfOrphaned_flipsGeneratingRowWithNoClaimStamp() {
        // A prior-run orphan: 'generating' with a NULL stamp. No live worker looks like this —
        // claimForGeneration always stamps — so the boot reconcile may safely reset it.
        long clipId = insertClip("generating");

        assertThat(clips.rependIfOrphaned(clipId, System.currentTimeMillis() - 60 * 60_000L)).isTrue();
        assertThat(clips.findById(clipId).orElseThrow().status()).isEqualTo("pending");
    }

    @Test
    void rependIfOrphaned_flipsGeneratingRowWithStaleClaimStamp() {
        long now = System.currentTimeMillis();
        long clipId = insertClip("pending");
        assertThat(clips.claimForGeneration(clipId, now - 2 * 60 * 60_000L)).isTrue();

        assertThat(clips.rependIfOrphaned(clipId, now - 60 * 60_000L)).isTrue();
        assertThat(clips.findById(clipId).orElseThrow().status()).isEqualTo("pending");
    }

    @Test
    void rependIfOrphaned_leavesLiveRecentClaimAlone() {
        // A claim younger than the stale cutoff is a LIVE worker (dispatched between context refresh
        // and ApplicationReadyEvent); resetting it would re-enable the double-cut.
        long now = System.currentTimeMillis();
        long clipId = insertClip("pending");
        assertThat(clips.claimForGeneration(clipId, now)).isTrue();

        assertThat(clips.rependIfOrphaned(clipId, now - 60 * 60_000L)).isFalse();
        assertThat(clips.findById(clipId).orElseThrow().status()).isEqualTo("generating");
    }

    private long insertClip(String status) {
        return clips.insert(parentMatchId, "manual", null, 30.0, 45.0, null,
                null, null, null, status, null, System.currentTimeMillis());
    }
}

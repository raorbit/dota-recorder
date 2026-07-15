package dev.dotarec.retention;

import dev.dotarec.bridge.EventPublisher;
import dev.dotarec.config.AppPaths;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.data.ClipRepository;
import dev.dotarec.data.MarkerRepository;
import dev.dotarec.data.MatchRepository;
import dev.dotarec.data.MatchRepository.NewMatch;
import dev.dotarec.data.TestDb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import dev.dotarec.config.SettingsStore.StorageLocation;

import javax.sql.DataSource;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Drives the retention sweep against real temp files and a real SQLite DB: over a tiny cap, the
 * oldest non-starred recording's .mp4 + thumbnail are deleted and its row is pruned (video_path
 * nulled) while the row and its markers survive; starred and protected matches are never touched;
 * and a {@code retention.swept} event carries the freed bytes + swept ids.
 */
class RetentionSweeperTest {

    private MatchRepository matches;
    private MarkerRepository markers;
    private ClipRepository clips;
    private SettingsStore settings;
    private EventPublisher events;
    private RetentionSweeper sweeper;
    private Path videoDir;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        DataSource ds = TestDb.migrated(dir);
        matches = new MatchRepository(ds);
        markers = new MarkerRepository(ds);
        clips = new ClipRepository(ds);

        videoDir = Files.createDirectories(dir.resolve("video"));
        // Real SettingsStore over a temp data dir; default cap is 50GB, we shrink it per-test.
        settings = new SettingsStore(
                new AppPaths(dir.resolve("data").toString(), dir.resolve("obs").toString()));
        settings.get().videoDir = videoDir.toString();

        events = mock(EventPublisher.class);
        sweeper = new RetentionSweeper(matches, clips, settings, events);
    }

    /**
     * Sweeper whose miss-confirmation wall clock reads {@code nowMillis} (real probes otherwise),
     * so a test can advance time past the 30-minute confirmation age floor without sleeping.
     */
    private RetentionSweeper sweeperAt(AtomicLong nowMillis) {
        return new RetentionSweeper(matches, clips, settings, events, new StorageMaintenanceLock(),
                dir -> Files.getFileStore(dir).getTotalSpace(),
                dir -> Files.getFileStore(dir).getUsableSpace(),
                Files::size,
                nowMillis::get);
    }

    @Test
    void sweepDeletesOldestNonStarredUntilUnderCap() throws Exception {
        // Cap of 1 GiB; seed 2 GiB so the sweep must prune exactly the oldest.
        settings.get().retentionCapGb = 1;
        long gib = 1024L * 1024 * 1024;

        long oldest = seedWithFiles("old.mp4", "old.jpg", gib, 1_000L, false);
        long newer = seedWithFiles("new.mp4", "new.jpg", gib, 2_000L, false);
        markers.insert(oldest, "kill", 10.0, 30, null, "gsi");

        // total = 2 GiB > 1 GiB cap -> exactly one (the oldest) gets pruned.
        RetentionSweeper.SweepResult result = sweeper.sweep(null);

        assertThat(result.deletedIds()).containsExactly(oldest);
        assertThat(result.freedBytes()).isEqualTo(gib);
        // Oldest files gone; newer files remain.
        assertThat(Files.exists(videoDir.resolve("old.mp4"))).isFalse();
        assertThat(Files.exists(videoDir.resolve("old.jpg"))).isFalse();
        assertThat(Files.exists(videoDir.resolve("new.mp4"))).isTrue();

        // Row survives with nulled paths; markers survive.
        var pruned = matches.findById(oldest).orElseThrow();
        assertThat(pruned.videoPath()).isNull();
        assertThat(pruned.thumbPath()).isNull();
        assertThat(pruned.fileSizeBytes()).isNull();
        assertThat(markers.findByMatchId(oldest)).hasSize(1);

        // Newer row untouched.
        assertThat(matches.findById(newer).orElseThrow().videoPath()).isNotNull();
    }

    @Test
    void clipsAreEvictedAfterVodsAndStarredClipsAreKept() throws Exception {
        // Cap 1 GiB. Seed a non-starred match VOD plus two of its clips (one starred, one not), each
        // 0.6 GiB -> 1.8 GiB total. The VOD is pruned first; still over cap, the NON-STARRED clip is
        // evicted next ("clips last"); the STARRED clip is always kept.
        settings.get().retentionCapGb = 1;
        long gib = 1024L * 1024 * 1024;
        long unit = 6 * gib / 10;

        long match = seedWithFiles("m.mp4", "m.jpg", unit, 1_000L, false);
        long starredClip = seedClip(match, "c-star.mp4", "c-star.jpg", unit, 200L, true);
        long plainClip = seedClip(match, "c-plain.mp4", "c-plain.jpg", unit, 100L, false);

        sweeper.sweep(null);

        // The VOD is pruned first (row kept, paths nulled).
        assertThat(matches.findById(match).orElseThrow().videoPath()).isNull();
        assertThat(Files.exists(videoDir.resolve("m.mp4"))).isFalse();
        // Then the non-starred clip is evicted: its file AND row are gone.
        assertThat(Files.exists(videoDir.resolve("c-plain.mp4"))).isFalse();
        assertThat(clips.findById(plainClip)).isEmpty();
        // The starred clip survives even though it is older and the budget is still tight.
        assertThat(Files.exists(videoDir.resolve("c-star.mp4"))).isTrue();
        assertThat(clips.findById(starredClip)).isPresent();
    }

    @Test
    void clipsAreEvictedOldestFirstAndNewestPlusStarredSurvive() throws Exception {
        // Cap 1 GiB. Seed four NON-starred clips at increasing createdAt plus one starred clip, each
        // 0.4 GiB -> 2.0 GiB total, all over a single parent match VOD with no on-disk file (so the
        // budget is driven purely by the clips). The clip phase evicts oldest-first (created_at ASC):
        // it removes the three oldest non-starred clips (2.0 -> 1.6 -> 1.2 -> 0.8 GiB, now under cap),
        // stops there so the NEWEST non-starred clip survives, and never touches the starred clip.
        settings.get().retentionCapGb = 1;
        long gib = 1024L * 1024 * 1024;
        long unit = 4 * gib / 10;

        // Parent row only, no VOD file: keeps the budget entirely in the clip phase.
        long match = matches.insert(new NewMatch(
                null, "match", "enriched", "puck",
                1, 2, 3, 400, 500, 10000, 120,
                "win", 7, 22, null, null, 1800,
                1_000L, null, null, null, false, 1_000L, null));

        long oldest = seedClip(match, "c-oldest.mp4", "c-oldest.jpg", unit, 100L, false);
        long middle = seedClip(match, "c-middle.mp4", "c-middle.jpg", unit, 200L, false);
        long older = seedClip(match, "c-older.mp4", "c-older.jpg", unit, 300L, false);
        long newest = seedClip(match, "c-newest.mp4", "c-newest.jpg", unit, 400L, false);
        // Starred and the OLDEST of all -> must still survive untouched.
        long starred = seedClip(match, "c-starred.mp4", "c-starred.jpg", unit, 50L, true);

        sweeper.sweep(null);

        // The three oldest non-starred clips are evicted: files AND rows gone.
        assertThat(Files.exists(videoDir.resolve("c-oldest.mp4"))).isFalse();
        assertThat(Files.exists(videoDir.resolve("c-middle.mp4"))).isFalse();
        assertThat(Files.exists(videoDir.resolve("c-older.mp4"))).isFalse();
        assertThat(clips.findById(oldest)).isEmpty();
        assertThat(clips.findById(middle)).isEmpty();
        assertThat(clips.findById(older)).isEmpty();
        // The newest non-starred clip survives: the budget went under cap before reaching it.
        assertThat(Files.exists(videoDir.resolve("c-newest.mp4"))).isTrue();
        assertThat(clips.findById(newest)).isPresent();
        // The starred clip is kept even though it is the oldest of all.
        assertThat(Files.exists(videoDir.resolve("c-starred.mp4"))).isTrue();
        assertThat(clips.findById(starred)).isPresent();
    }

    @Test
    void clipEvictionUsesRealDiskSizeOnBothSidesWhenDatabaseSizeIsStale() throws Exception {
        // Cap 1 GiB. Two non-starred clips over a parent match with no VOD file (budget is all clips).
        // The OLDEST clip's on-disk size (0.6 GiB) is much larger than its stale DB file_size_bytes
        // (0.1 GiB); the newer clip's disk and DB agree at 0.6 GiB. Real total on disk = 1.2 GiB > cap.
        // The seed (measureStoredBytes) and the loop decrement must BOTH use real disk size: only then
        // is the budget seen as over cap and exactly the oldest evicted (0.6 GiB left, under cap). Seeded
        // from the drifted DB size instead, total would read as 0.7 GiB and the sweep would under-evict.
        settings.get().retentionCapGb = 1;
        long gib = 1024L * 1024 * 1024;
        long unit = 6 * gib / 10;

        // Parent row only, no VOD file: keeps the budget entirely in the clip phase.
        long match = matches.insert(new NewMatch(
                null, "match", "enriched", "puck",
                1, 2, 3, 400, 500, 10000, 120,
                "win", 7, 22, null, null, 1800,
                1_000L, null, null, null, false, 1_000L, null));

        // Oldest: 0.6 GiB on disk but a stale 0.1 GiB recorded in the DB.
        long stale = seedClip(match, "c-stale.mp4", "c-stale.jpg", unit, gib / 10, 100L, false);
        // Newer: disk and DB agree at 0.6 GiB.
        long accurate = seedClip(match, "c-ok.mp4", "c-ok.jpg", unit, unit, 200L, false);

        RetentionSweeper.SweepResult result = sweeper.sweep(null);

        // Exactly the oldest is evicted (file AND row), using real disk size on both sides.
        assertThat(result.freedBytes()).isEqualTo(unit);
        assertThat(Files.exists(videoDir.resolve("c-stale.mp4"))).isFalse();
        assertThat(clips.findById(stale)).isEmpty();
        // The newer clip survives: 0.6 GiB remaining is under the 1 GiB cap.
        assertThat(Files.exists(videoDir.resolve("c-ok.mp4"))).isTrue();
        assertThat(clips.findById(accurate)).isPresent();
    }

    @Test
    void oneClipRowDeleteFailingDoesNotAbortOrOverEvictAndLeavesNoFileLeak() throws Exception {
        // Cap 1 GiB. Three non-starred clips over a parent match with no VOD file, 0.6 GiB each
        // -> 1.8 GiB. The DB delete of the OLDEST clip throws (simulating a SQLITE_BUSY) AFTER its .mp4
        // was already unlinked (file-then-row order). That single failure must NOT abort the pass — and
        // must not OVER-evict either: the unlinked bytes are confirmed gone, so the running total is
        // credited for them and only ONE more eviction is needed (1.8 -> 1.2 -> 0.6 GiB, under cap);
        // the newest clip survives. Crucially the failed clip leaks NO FILE (the .mp4 is gone -- a
        // leftover under clips/ could never be reclaimed by the non-recursive orphan scan); its ROW
        // survives untouched (the claim mutates nothing), still holding its now-dangling path, so it
        // re-enters the missing-file flow and a later sweep drops it. freed does not credit the failed
        // clip — its row survived, so nothing was fully evicted.
        settings.get().retentionCapGb = 1;
        long gib = 1024L * 1024 * 1024;
        long unit = 6 * gib / 10;

        long match = matches.insert(new NewMatch(
                null, "match", "enriched", "puck",
                1, 2, 3, 400, 500, 10000, 120,
                "win", 7, 22, null, null, 1800,
                1_000L, null, null, null, false, 1_000L, null));

        long boom = seedClip(match, "c-boom.mp4", "c-boom.jpg", unit, 100L, false);
        long second = seedClip(match, "c-second.mp4", "c-second.jpg", unit, 200L, false);
        long third = seedClip(match, "c-third.mp4", "c-third.jpg", unit, 300L, false);

        // Spy the real repo so delete(boom) throws but everything else delegates to the real DB.
        ClipRepository failingClips = spy(clips);
        doThrow(new IllegalStateException("simulated SQLITE_BUSY"))
                .when(failingClips).delete(boom);
        RetentionSweeper resilient =
                new RetentionSweeper(matches, failingClips, settings, events);

        RetentionSweeper.SweepResult result = resilient.sweep(null);

        // No permanent file leak: the failed clip's .mp4 was unlinked before the row delete threw.
        assertThat(Files.exists(videoDir.resolve("c-boom.mp4"))).isFalse();
        // Its row survives UNTOUCHED, still holding its (now dangling) path, so it re-enters the
        // missing-file flow — a later sweep confirms the miss and drops the row.
        assertThat(clips.findById(boom).orElseThrow().videoPath())
                .as("a failed row delete must leave the row's path in place for the miss flow")
                .isEqualTo(videoDir.resolve("c-boom.mp4").toString());
        // Not aborted AND not over-evicted: crediting boom's confirmed-gone bytes means evicting the
        // second clip already reaches the cap, so the third survives.
        assertThat(clips.findById(second)).isEmpty();
        assertThat(Files.exists(videoDir.resolve("c-second.mp4"))).isFalse();
        assertThat(clips.findById(third))
                .as("the pass must not evict an extra clip to offset already-deleted bytes")
                .isPresent();
        assertThat(Files.exists(videoDir.resolve("c-third.mp4"))).isTrue();
        // freed credits only the fully-evicted clip; total reflects boom's credit, leaving just the
        // surviving third clip's bytes on the books.
        assertThat(result.freedBytes()).isEqualTo(unit);
        assertThat(result.totalAfterBytes()).isEqualTo(unit);
    }

    @Test
    void oneClipFileUndeletableKeepsRowAndCreditsNothingWhileOtherClipsAreEvicted() throws Exception {
        // Cap 1 GiB. Three non-starred clips over a parent match with no VOD file. The OLDEST clip's .mp4
        // is UNDELETABLE (its video_path is a non-empty directory, so deleteFileQuietly returns false) but
        // its DB delete would succeed. The sweep must NOT credit freed/total for that clip and must KEEP
        // its row (so the row still references the intact file, no permanent leak — CrashRecoveryRunner's
        // non-recursive scan never reclaims a clips/ leftover). The failure must not abort the pass: the
        // next real clip is still evicted until under cap. Against the buggy row-before-file code (row
        // deleted first, unlink result ignored, accounting unconditional) the row would be gone and the
        // file leaked — so this test fails there and passes after the fix.
        settings.get().retentionCapGb = 1;
        long gib = 1024L * 1024 * 1024;
        long unit = 6 * gib / 10;

        long match = matches.insert(new NewMatch(
                null, "match", "enriched", "puck",
                1, 2, 3, 400, 500, 10000, 120,
                "win", 7, 22, null, null, 1800,
                1_000L, null, null, null, false, 1_000L, null));

        // Oldest clip: video_path points at a NON-EMPTY directory named like an .mp4, so its parent
        // (videoDir) is reachable but Files.deleteIfExists throws DirectoryNotEmptyException — the exact
        // "undeletable file on a present drive" the fix must survive without leaking or over-crediting.
        long undeletable = seedClipWithUndeletableVideo(match, "c-undeletable.mp4", 100L);
        // Two real, deletable clips, each 0.6 GiB.
        long second = seedClip(match, "c-second.mp4", "c-second.jpg", unit, 200L, false);
        long third = seedClip(match, "c-third.mp4", "c-third.jpg", unit, 300L, false);

        RetentionSweeper.SweepResult result = sweeper.sweep(null);

        // The undeletable clip's row is KEPT untouched — the claim mutates nothing, so the row still
        // references the intact file (not orphaned) — and its undeletable .mp4 directory survives on
        // disk.
        assertThat(clips.findById(undeletable).orElseThrow().videoPath())
                .as("a failed unlink must leave the row referencing its intact file")
                .isEqualTo(videoDir.resolve("c-undeletable.mp4").toString());
        assertThat(Files.isDirectory(videoDir.resolve("c-undeletable.mp4"))).isTrue();
        // The pass was not aborted: the next real clip is evicted (file AND row gone), bringing the 1.2 GiB
        // budget under the 1 GiB cap. The undeletable clip contributed nothing to that budget (its dir
        // reports size 0), so exactly this one real eviction is needed and the newest clip survives.
        assertThat(clips.findById(second)).isEmpty();
        assertThat(Files.exists(videoDir.resolve("c-second.mp4"))).isFalse();
        assertThat(clips.findById(third)).isPresent();
        assertThat(Files.exists(videoDir.resolve("c-third.mp4"))).isTrue();
        // freed counts only the successfully-unlinked clip; the undeletable one is NOT credited.
        assertThat(result.freedBytes()).isEqualTo(unit);
    }

    @Test
    void sweepUsesFilesystemSizeWhenDatabaseSizeIsStale() throws Exception {
        settings.get().retentionCapGb = 1;
        long gib = 1024L * 1024 * 1024;

        long staleSize = seedWithFiles("stale.mp4", "stale.jpg", 2 * gib, 0L, 1_000L, false);

        RetentionSweeper.SweepResult result = sweeper.sweep(null);

        assertThat(result.deletedIds()).containsExactly(staleSize);
        assertThat(result.freedBytes()).isEqualTo(2 * gib);
        assertThat(Files.exists(videoDir.resolve("stale.mp4"))).isFalse();
        assertThat(matches.findById(staleSize).orElseThrow().videoPath()).isNull();
    }

    @Test
    void failedVideoDeleteKeepsRowSoNextSweepCanRetry() {
        settings.get().retentionCapGb = 1;
        long gib = 1024L * 1024 * 1024;

        String undeletablePath = "bad\u0000video.mp4";
        long id = matches.insert(new NewMatch(
                null, "match", "enriched", "puck",
                1, 2, 3, 400, 500, 10000, 120,
                "win", 7, 22, null, null, 1800,
                1_000L, undeletablePath, null, 2 * gib, false, 1_000L, null));

        RetentionSweeper.SweepResult result = sweeper.sweep(null);

        assertThat(result.deletedIds()).isEmpty();
        assertThat(result.freedBytes()).isZero();
        assertThat(matches.findById(id).orElseThrow().videoPath()).isEqualTo(undeletablePath);
    }

    @Test
    void failedVideoDeleteLeavesThumbnailIntactAndDoesNotDangleThumbPath() throws Exception {
        // F17: the oldest match's .mp4 can't be deleted (an open handle / locked file on Windows), but
        // its thumbnail IS a normal deletable file. The thumbnail must be unlinked ONLY AFTER the video
        // delete is confirmed — otherwise the thumb is gone yet restoreVideoPath puts its path back on
        // the row, a permanent dangling thumb_path. A newer real VOD pushes over cap so the undeletable
        // (oldest) match is tried first, then the pass continues to evict the real one.
        settings.get().retentionCapGb = 1;
        long gib = 1024L * 1024 * 1024;

        long undeletable = seedMatchWithUndeletableVideo("u-video.mp4", "u-thumb.jpg", 1_000L);
        long realVod = seedWithFiles("real.mp4", "real.jpg", 2 * gib, 2_000L, false);

        sweeper.sweep(null);

        // The undeletable match's thumbnail survives on disk...
        Path thumb = videoDir.resolve("u-thumb.jpg");
        assertThat(Files.exists(thumb))
                .as("a failed video delete must leave the thumbnail intact, never dangling")
                .isTrue();
        // ...and its row keeps a VALID thumb_path (points at the still-existing file), not a dangling one.
        var row = matches.findById(undeletable).orElseThrow();
        assertThat(row.videoPath()).isEqualTo(videoDir.resolve("u-video.mp4").toString());
        assertThat(row.thumbPath()).isEqualTo(thumb.toString());
        // The pass was not aborted: the newer real VOD is still evicted.
        assertThat(matches.findById(realVod).orElseThrow().videoPath()).isNull();
        assertThat(Files.exists(videoDir.resolve("real.mp4"))).isFalse();
    }

    @Test
    void sweepNeverDeletesStarred() throws Exception {
        settings.get().retentionCapGb = 1;
        long gib = 1024L * 1024 * 1024;

        long starred = seedWithFiles("star.mp4", "star.jpg", 2 * gib, 1_000L, true);

        RetentionSweeper.SweepResult result = sweeper.sweep(null);

        // Over cap but the only candidate is starred -> nothing deleted, file intact.
        assertThat(result.deletedIds()).isEmpty();
        assertThat(Files.exists(videoDir.resolve("star.mp4"))).isTrue();
        assertThat(matches.findById(starred).orElseThrow().videoPath()).isNotNull();
    }

    @Test
    void sweepSkipsProtectedActivelyRecordingMatch() throws Exception {
        settings.get().retentionCapGb = 1;
        long gib = 1024L * 1024 * 1024;

        long oldestProtected = seedWithFiles("rec.mp4", "rec.jpg", gib, 1_000L, false);
        long newer = seedWithFiles("new.mp4", "new.jpg", gib, 2_000L, false);

        // Oldest is the actively-recording match: it must be skipped even though it's the oldest.
        RetentionSweeper.SweepResult result = sweeper.sweep(oldestProtected);

        assertThat(result.deletedIds()).containsExactly(newer);
        assertThat(Files.exists(videoDir.resolve("rec.mp4"))).isTrue();
        assertThat(matches.findById(oldestProtected).orElseThrow().videoPath()).isNotNull();
    }

    @Test
    void sweepUnderCapIsNoOpAndPublishesNothing() throws Exception {
        settings.get().retentionCapGb = 50; // way above the seeded size
        seedWithFiles("small.mp4", "small.jpg", 1024L, 1_000L, false);

        RetentionSweeper.SweepResult result = sweeper.sweep(null);

        assertThat(result.deletedIds()).isEmpty();
        assertThat(result.freedBytes()).isZero();
        assertThat(Files.exists(videoDir.resolve("small.mp4"))).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void sweepPublishesRetentionSweptEvent() throws Exception {
        settings.get().retentionCapGb = 1;
        long gib = 1024L * 1024 * 1024;
        long oldest = seedWithFiles("old.mp4", "old.jpg", 2 * gib, 1_000L, false);

        sweeper.sweep(null);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq("retention.swept"), payload.capture());
        Map<String, Object> body = (Map<String, Object>) payload.getValue();
        assertThat(body.get("freedBytes")).isEqualTo(2 * gib);
        assertThat((List<Long>) body.get("deletedIds")).containsExactly(oldest);
    }

    @Test
    void budgetIsSumOfCapsAndEvictsGloballyOldestAcrossDrives() throws Exception {
        long gib = 1024L * 1024 * 1024;
        // Active cap 1 GiB + one archive drive cap 1 GiB => total budget 2 GiB.
        Path archiveDir = Files.createDirectories(videoDir.getParent().resolve("archive"));
        settings.get().retentionCapGb = 1;
        settings.get().storageLocations =
                new ArrayList<>(List.of(new StorageLocation("a", archiveDir.toString(), 1)));

        // Oldest lives on the ARCHIVE drive; newer lives on the active drive. total = 2.5 GiB > 2 GiB.
        long oldestOnArchive =
                seedWithFilesIn(archiveDir, "old.mp4", "old.jpg", 3 * gib / 2, 1_000L, false);
        long newerOnActive = seedWithFiles("new.mp4", "new.jpg", gib, 2_000L, false);

        RetentionSweeper.SweepResult result = sweeper.sweep(null);

        // Eviction is by global age, not per-drive: the oldest (on the archive drive) is pruned even
        // though the active drive alone is also over its own 1 GiB cap.
        assertThat(result.deletedIds()).containsExactly(oldestOnArchive);
        assertThat(Files.exists(archiveDir.resolve("old.mp4"))).isFalse();
        assertThat(Files.exists(videoDir.resolve("new.mp4"))).isTrue();
        assertThat(matches.findById(newerOnActive).orElseThrow().videoPath()).isNotNull();
        // 1 GiB remaining is now under the 2 GiB budget, so the sweep stops after one deletion.
        assertThat(result.totalAfterBytes()).isEqualTo(gib);
    }

    @Test
    void capLargerThanPhysicalDiskIsClampedSoEvictionStillFires() throws Exception {
        long gib = 1024L * 1024 * 1024;
        // A 500 GiB configured cap on a drive that physically holds only 1 GiB. Without clamping the
        // global budget would be 500 GiB, total stored could never reach it, and eviction would be
        // disabled entirely — the active drive would grow unbounded. capBytes() clamps each location to
        // min(configuredCap, physical total), so the effective budget here is 1 GiB and an over-budget
        // oldest non-starred VOD is still pruned.
        settings.get().retentionCapGb = 500;

        // Inject a deterministic total-space probe: the video drive reports a 1 GiB physical capacity.
        Map<String, Long> totalByDir = new HashMap<>();
        totalByDir.put(videoDir.toAbsolutePath().normalize().toString(), gib);
        RetentionSweeper.TotalSpaceProbe probe =
                d -> {
                    Long t = totalByDir.get(d.toAbsolutePath().normalize().toString());
                    if (t == null) {
                        throw new java.io.IOException("no injected total for " + d);
                    }
                    return t;
                };
        RetentionSweeper clamped =
                new RetentionSweeper(matches, clips, settings, events, new StorageMaintenanceLock(), probe);

        // 2 GiB stored on the 1-GiB-physical drive: 1.5 GiB old + 0.5 GiB new. Clamped budget = 1 GiB,
        // so the oldest is evicted and 0.5 GiB remains (under budget).
        long oldest = seedWithFiles("old.mp4", "old.jpg", 3 * gib / 2, 1_000L, false);
        long newer = seedWithFiles("new.mp4", "new.jpg", gib / 2, 2_000L, false);

        RetentionSweeper.SweepResult result = clamped.sweep(null);

        // Eviction fired despite the 500 GiB configured cap, because the budget was clamped to 1 GiB.
        assertThat(result.capBytes()).isEqualTo(gib);
        assertThat(result.deletedIds()).containsExactly(oldest);
        assertThat(Files.exists(videoDir.resolve("old.mp4"))).isFalse();
        assertThat(matches.findById(oldest).orElseThrow().videoPath()).isNull();
        // The newer VOD survives: 0.5 GiB is under the clamped 1 GiB budget.
        assertThat(Files.exists(videoDir.resolve("new.mp4"))).isTrue();
        assertThat(matches.findById(newer).orElseThrow().videoPath()).isNotNull();
        assertThat(result.totalAfterBytes()).isEqualTo(gib / 2);
    }

    @Test
    void unstattableArchiveDriveContributesZeroHeadroom_soEvictionStillFires() throws Exception {
        long gib = 1024L * 1024 * 1024;
        // Active drive cap 1 GiB (physically 1 GiB, injected). A 500 GiB archive is configured but
        // UNPLUGGED, so its probe throws. The archive must contribute ZERO budget (not its raw 500 GiB),
        // leaving the effective budget at the active 1 GiB so an over-budget active VOD is still pruned.
        settings.get().retentionCapGb = 1;
        Path unpluggedArchive = videoDir.getParent().resolve("unplugged-archive");
        settings.get().storageLocations =
                new ArrayList<>(List.of(new StorageLocation("a", unpluggedArchive.toString(), 500)));

        Map<String, Long> totalByDir = new HashMap<>();
        totalByDir.put(videoDir.toAbsolutePath().normalize().toString(), gib);
        RetentionSweeper.TotalSpaceProbe probe =
                d -> {
                    Long t = totalByDir.get(d.toAbsolutePath().normalize().toString());
                    if (t == null) {
                        // The unplugged archive (never put in the map) fails to stat, just like a real one.
                        throw new java.io.IOException("drive unplugged: " + d);
                    }
                    return t;
                };
        RetentionSweeper sweeper =
                new RetentionSweeper(matches, clips, settings, events, new StorageMaintenanceLock(), probe);

        long oldest = seedWithFiles("old.mp4", "old.jpg", 3 * gib / 2, 1_000L, false);
        long newer = seedWithFiles("new.mp4", "new.jpg", gib / 2, 2_000L, false);

        RetentionSweeper.SweepResult result = sweeper.sweep(null);

        // Budget is the active 1 GiB only — the disconnected archive added no imaginary headroom.
        assertThat(result.capBytes()).as("unplugged archive contributes 0 headroom").isEqualTo(gib);
        assertThat(result.deletedIds()).containsExactly(oldest);
        assertThat(Files.exists(videoDir.resolve("old.mp4"))).isFalse();
        assertThat(matches.findById(newer).orElseThrow().videoPath()).isNotNull();
    }

    @Test
    void offlineArchiveResidentVodIsNotOrphanedWhileItsDriveIsUnreachable() throws Exception {
        // An archived VOD lives on a drive that is currently UNPLUGGED (its directory does not exist),
        // with its size persisted in the DB. A newer, larger VOD on the active drive pushes total over
        // cap. The sweep must evict the reachable active VOD and MUST NOT orphan the offline archive's
        // row (deleteFileQuietly would otherwise treat the unreachable path as "gone" and null it).
        long gib = 1024L * 1024 * 1024;
        settings.get().retentionCapGb = 1;

        // Offline archive VOD: a path under a directory that does NOT exist (no file created), with a DB
        // size snapshot so the old code would have counted it. Older than the active VOD.
        Path offlineArchiveFile = videoDir.getParent().resolve("unplugged-archive").resolve("old.mp4");
        long offlineId = matches.insert(new NewMatch(
                null, "match", "enriched", "puck",
                1, 2, 3, 400, 500, 10000, 120,
                "win", 7, 22, null, null, 1800,
                1_000L, offlineArchiveFile.toString(), null, 2 * gib, false, 1_000L, null));

        // Active-drive VOD: a real 2 GiB file, newer, so eviction must target it.
        long activeId = seedWithFiles("new.mp4", "new.jpg", 2 * gib, 2_000L, false);

        RetentionSweeper.SweepResult result = sweeper.sweep(null);

        // The reachable active VOD is evicted; the offline archive's row is preserved (not orphaned).
        assertThat(result.deletedIds()).containsExactly(activeId);
        assertThat(matches.findById(activeId).orElseThrow().videoPath()).isNull();
        assertThat(matches.findById(offlineId).orElseThrow().videoPath())
                .as("a VOD on an unplugged drive must never be orphaned by the sweep")
                .isEqualTo(offlineArchiveFile.toString());
    }

    @Test
    void offlineClipBytesDoNotDriveEvictionOfReachableVods() throws Exception {
        // The over-cap amount is held ENTIRELY by a clip on an UNPLUGGED drive (its directory does not
        // exist; only a DB size snapshot remains). A reachable, non-starred VOD sits comfortably under
        // cap on its own. The sweep must NOT delete that reachable VOD chasing budget that only the
        // unreclaimable offline clip holds above cap — offline clip bytes must be excluded from total.
        long gib = 1024L * 1024 * 1024;
        settings.get().retentionCapGb = 1;

        // Reachable active-drive VOD, 0.5 GiB: under the 1 GiB cap by itself.
        long reachableVod = seedWithFiles("keep.mp4", "keep.jpg", gib / 2, 2_000L, false);

        // Offline clip, 1 GiB recorded size, on a directory that does NOT exist (drive unplugged). No
        // file created. Older than the VOD so it would be the first clip candidate if reached.
        Path offlineClipFile =
                videoDir.getParent().resolve("unplugged-archive").resolve("offline-clip.mp4");
        long offlineClip = clips.insert(reachableVod, "manual", null, 0.0, 10.0, null,
                offlineClipFile.toString(), null, gib, "ready", null, 100L);

        RetentionSweeper.SweepResult result = sweeper.sweep(null);

        // Nothing evicted: total counts only the reachable 0.5 GiB VOD, which is under the 1 GiB cap.
        assertThat(result.deletedIds()).isEmpty();
        assertThat(result.freedBytes()).isZero();
        assertThat(Files.exists(videoDir.resolve("keep.mp4")))
                .as("a reachable VOD must not be evicted for unreclaimable offline clip bytes")
                .isTrue();
        assertThat(matches.findById(reachableVod).orElseThrow().videoPath()).isNotNull();
        // The offline clip row is untouched.
        assertThat(clips.findById(offlineClip)).isPresent();
    }

    @Test
    void outOfRootVideoPathIsNeitherDeletedNorPrunedAndTheSweepContinues() throws Exception {
        // A row's video_path points at a real, on-disk file OUTSIDE every configured storage root (a
        // tampered/hand-edited path or a `..` escape) — not videoDir, not an archive, not a
        // previousVideoDir. Its parent directory exists (so driveReachable passes and the sweep reaches
        // deleteFileQuietly), but the containment guard must REFUSE the unlink: the file survives on disk
        // and the row keeps its path (never nulled, never credited). The pass must not stop there — a
        // NEWER in-root VOD is still evicted, proving the refusal is a skip, not an abort.
        long gib = 1024L * 1024 * 1024;
        settings.get().retentionCapGb = 1;

        // Out-of-root file: a real 1.5 GiB .mp4 under a sibling dir that is NOT any storage root. Older
        // (played_at 1_000) so it is the FIRST sweep candidate — the guard fires before the in-root one.
        Path outsideDir = Files.createDirectories(videoDir.getParent().resolve("outside-all-roots"));
        Path outsideFile = outsideDir.resolve("tampered.mp4");
        createSparseFile(outsideFile, 3 * gib / 2);
        long outsideId = matches.insert(new NewMatch(
                null, "match", "enriched", "puck",
                1, 2, 3, 400, 500, 10000, 120,
                "win", 7, 22, null, null, 1800,
                1_000L, outsideFile.toString(), null, 3 * gib / 2, false, 1_000L, null));

        // In-root active-drive VOD: a real 1 GiB file, NEWER, so eviction must fall through to it after
        // the out-of-root candidate is refused.
        long inRootId = seedWithFiles("in-root.mp4", "in-root.jpg", gib, 2_000L, false);

        RetentionSweeper.SweepResult result = sweeper.sweep(null);

        // The out-of-root file is neither unlinked on disk nor pruned in the DB, and got no freed credit.
        assertThat(Files.exists(outsideFile))
                .as("a file outside all storage roots must never be unlinked by the sweep")
                .isTrue();
        assertThat(matches.findById(outsideId).orElseThrow().videoPath())
                .as("an out-of-root row must keep its path (not nulled/pruned)")
                .isEqualTo(outsideFile.toString());
        assertThat(result.deletedIds())
                .as("the sweep continues past the refused candidate to the in-root one")
                .containsExactly(inRootId);
        assertThat(result.freedBytes()).isEqualTo(gib);
        assertThat(Files.exists(videoDir.resolve("in-root.mp4"))).isFalse();
        assertThat(matches.findById(inRootId).orElseThrow().videoPath()).isNull();
    }

    @Test
    void missingVideoOnReachableDriveCountsZeroAndReconcilesTheRowOnAnAgedSecondSweep() throws Exception {
        // The oldest row's .mp4 is GONE from the (present) video dir — deleted outside the app — but
        // its stale 2 GiB DB size remains. Counted at DB size those phantom bytes would put the
        // 0.5 GiB real VOD over the 1 GiB cap and evict it to offset bytes that don't exist. The
        // sweep must count the missing file as 0 from the FIRST miss, but the destructive row
        // reconcile waits for a LATER sweep at least 30 minutes after that miss (a
        // transiently-missing file must keep its row): pass 1 leaves the row intact, an aged pass 2
        // catches it up to the post-sweep shape (kept, paths/size nulled, markers intact). Nothing
        // is ever deleted.
        settings.get().retentionCapGb = 1;
        long gib = 1024L * 1024 * 1024;
        AtomicLong now = new AtomicLong(1_000_000L);
        RetentionSweeper timed = sweeperAt(now);

        Path phantomFile = videoDir.resolve("phantom.mp4"); // never created
        long phantomId = matches.insert(new NewMatch(
                null, "match", "enriched", "puck",
                1, 2, 3, 400, 500, 10000, 120,
                "win", 7, 22, null, null, 1800,
                1_000L, phantomFile.toString(), null, 2 * gib, false, 1_000L, null));
        markers.insert(phantomId, "death", 42.0, 60, null, "gsi");
        long realId = seedWithFiles("real.mp4", "real.jpg", gib / 2, 2_000L, false);

        RetentionSweeper.SweepResult first = timed.sweep(null);

        // Nothing evicted: without the phantom bytes the real 0.5 GiB VOD is under the 1 GiB cap.
        assertThat(first.deletedIds()).isEmpty();
        assertThat(first.freedBytes()).isZero();
        assertThat(Files.exists(videoDir.resolve("real.mp4")))
                .as("a real VOD must never be evicted to offset phantom bytes")
                .isTrue();
        // First miss: the row is NOT yet mutated — a transiently-missing file could still return.
        assertThat(matches.findById(phantomId).orElseThrow().videoPath())
                .as("a single miss must not strip the row's path")
                .isEqualTo(phantomFile.toString());

        now.addAndGet(31 * 60_000L); // past the 30-minute confirmation age floor
        RetentionSweeper.SweepResult second = timed.sweep(null);

        // A later, aged miss: the row is reconciled like a swept row — kept, with markers,
        // paths/size nulled. Still nothing evicted.
        assertThat(second.deletedIds()).isEmpty();
        assertThat(second.freedBytes()).isZero();
        assertThat(matches.findById(realId).orElseThrow().videoPath()).isNotNull();
        var reconciled = matches.findById(phantomId).orElseThrow();
        assertThat(reconciled.videoPath()).isNull();
        assertThat(reconciled.thumbPath()).isNull();
        assertThat(reconciled.fileSizeBytes()).isNull();
        assertThat(markers.findByMatchId(phantomId)).hasSize(1);
    }

    @Test
    void missingStarredVideoAlsoReconcilesOnAnAgedSecondSweepWithoutAnyDeletion() throws Exception {
        // A STARRED row whose file is gone is never a sweep candidate, so without reconciliation its
        // stale DB bytes would shrink the budget FOREVER — every sweep would evict real unstarred
        // VODs to offset them. The measurement pass must reconcile it too (the star protects the
        // file, but the file is already gone) while deleting nothing — but only on a LATER miss at
        // least 30 minutes after the first, so a starred VOD the user temporarily moved out keeps
        // its row (and its star) through the confirmation window.
        settings.get().retentionCapGb = 1;
        long gib = 1024L * 1024 * 1024;
        AtomicLong now = new AtomicLong(1_000_000L);
        RetentionSweeper timed = sweeperAt(now);

        Path phantomFile = videoDir.resolve("starred-phantom.mp4"); // never created
        long starredId = matches.insert(new NewMatch(
                null, "match", "enriched", "puck",
                1, 2, 3, 400, 500, 10000, 120,
                "win", 7, 22, null, null, 1800,
                1_000L, phantomFile.toString(), null, 2 * gib, true, 1_000L, null));
        long realId = seedWithFiles("real.mp4", "real.jpg", gib / 2, 2_000L, false);

        RetentionSweeper.SweepResult first = timed.sweep(null);

        assertThat(first.deletedIds()).isEmpty();
        assertThat(first.freedBytes()).isZero();
        // First miss: the starred row keeps its path — its 2 GiB already count 0 in the budget.
        assertThat(matches.findById(starredId).orElseThrow().videoPath())
                .isEqualTo(phantomFile.toString());

        now.addAndGet(31 * 60_000L); // past the 30-minute confirmation age floor
        RetentionSweeper.SweepResult second = timed.sweep(null);

        assertThat(second.deletedIds()).isEmpty();
        assertThat(second.freedBytes()).isZero();
        assertThat(Files.exists(videoDir.resolve("real.mp4"))).isTrue();
        assertThat(matches.findById(realId).orElseThrow().videoPath()).isNotNull();
        // Confirmed on the aged second sweep: the starred row is reconciled (paths nulled) but
        // kept, still starred.
        var reconciled = matches.findById(starredId).orElseThrow();
        assertThat(reconciled.videoPath()).isNull();
        assertThat(reconciled.fileSizeBytes()).isNull();
        assertThat(reconciled.starred()).isTrue();
    }

    @Test
    void missingClipOnReachableDriveCountsZeroAndDropsItsRowOnAnAgedSecondSweep() throws Exception {
        // A clip row whose .mp4 is gone from the (present) video dir but whose stale 2 GiB DB size
        // remains. Phantom clip bytes must not evict the real under-cap VOD from the FIRST miss; the
        // clip row (which has nothing left to reference) is dropped on a LATER miss at least 30
        // minutes after the first, matching the match rows' confirmed-missing rule.
        settings.get().retentionCapGb = 1;
        long gib = 1024L * 1024 * 1024;
        AtomicLong now = new AtomicLong(1_000_000L);
        RetentionSweeper timed = sweeperAt(now);

        long realVod = seedWithFiles("keep.mp4", "keep.jpg", gib / 2, 2_000L, false);
        Path phantomClipFile = videoDir.resolve("phantom-clip.mp4"); // never created
        long phantomClip = clips.insert(realVod, "manual", null, 0.0, 10.0, null,
                phantomClipFile.toString(), null, 2 * gib, "ready", null, 100L);

        RetentionSweeper.SweepResult first = timed.sweep(null);

        assertThat(first.deletedIds()).isEmpty();
        assertThat(first.freedBytes()).isZero();
        assertThat(Files.exists(videoDir.resolve("keep.mp4")))
                .as("a real VOD must not be evicted to offset a phantom clip's bytes")
                .isTrue();
        // First miss: the clip row survives with its path — the file could still come back.
        assertThat(clips.findById(phantomClip).orElseThrow().videoPath())
                .isEqualTo(phantomClipFile.toString());

        now.addAndGet(31 * 60_000L); // past the 30-minute confirmation age floor
        RetentionSweeper.SweepResult second = timed.sweep(null);

        assertThat(second.deletedIds()).isEmpty();
        assertThat(second.freedBytes()).isZero();
        assertThat(matches.findById(realVod).orElseThrow().videoPath()).isNotNull();
        // Confirmed on the aged second sweep: the pointless row is dropped.
        assertThat(clips.findById(phantomClip)).isEmpty();
    }

    @Test
    void matchStarredAfterCandidateSnapshotIsNeverDeleted() throws Exception {
        // The user stars the oldest match in the instant between the sweeper's candidate snapshot and
        // that row's eviction turn (a star PATCH commits outside the maintenance lock). The atomic
        // claim re-checks starred INSIDE the pruning UPDATE, so the claim fails, the file survives,
        // and eviction moves on to the next candidate.
        settings.get().retentionCapGb = 1;
        long gib = 1024L * 1024 * 1024;

        long oldest = seedWithFiles("old.mp4", "old.jpg", 2 * gib, 1_000L, false);
        long newer = seedWithFiles("new.mp4", "new.jpg", gib, 2_000L, false);

        MatchRepository racingMatches = spy(matches);
        doAnswer(inv -> {
            Object candidates = inv.callRealMethod();
            matches.setStarred(oldest, true); // the star lands right after the snapshot
            return candidates;
        }).when(racingMatches).findSweepCandidates();
        RetentionSweeper racy = new RetentionSweeper(racingMatches, clips, settings, events);

        RetentionSweeper.SweepResult result = racy.sweep(null);

        // The freshly-starred oldest match survives: file intact, row untouched, no freed credit.
        assertThat(Files.exists(videoDir.resolve("old.mp4")))
                .as("a match starred after the candidate snapshot must never be deleted")
                .isTrue();
        var survivor = matches.findById(oldest).orElseThrow();
        assertThat(survivor.videoPath()).isNotNull();
        assertThat(survivor.starred()).isTrue();
        // The pass still evicted the next (non-starred) candidate.
        assertThat(result.deletedIds()).containsExactly(newer);
        assertThat(result.freedBytes()).isEqualTo(gib);
        assertThat(Files.exists(videoDir.resolve("new.mp4"))).isFalse();
    }

    @Test
    void clipStarredAfterCandidateSnapshotIsNeverDeleted() throws Exception {
        // Clip twin of the match test above: the user stars the oldest clip in the instant between
        // the sweeper's one-time candidate snapshot and that clip's eviction turn (the star PATCH
        // commits outside the maintenance lock). The clip loop's atomic claim re-checks starred
        // INSIDE the path-nulling UPDATE, so the claim fails, the file survives, and eviction moves
        // on to the next candidate.
        settings.get().retentionCapGb = 1;
        long gib = 1024L * 1024 * 1024;

        long match = matches.insert(new NewMatch(
                null, "match", "enriched", "puck",
                1, 2, 3, 400, 500, 10000, 120,
                "win", 7, 22, null, null, 1800,
                1_000L, null, null, null, false, 1_000L, null));
        long oldest = seedClip(match, "c-old.mp4", "c-old.jpg", 2 * gib, 100L, false);
        long newer = seedClip(match, "c-new.mp4", "c-new.jpg", gib, 200L, false);

        ClipRepository racingClips = spy(clips);
        doAnswer(inv -> {
            Object candidates = inv.callRealMethod();
            clips.setStarred(oldest, true); // the star lands right after the snapshot
            return candidates;
        }).when(racingClips).findSweepCandidates();
        RetentionSweeper racy = new RetentionSweeper(matches, racingClips, settings, events);

        RetentionSweeper.SweepResult result = racy.sweep(null);

        // The freshly-starred oldest clip survives: file intact, row untouched (path kept, starred).
        assertThat(Files.exists(videoDir.resolve("c-old.mp4")))
                .as("a clip starred after the candidate snapshot must never be deleted")
                .isTrue();
        var survivor = clips.findById(oldest).orElseThrow();
        assertThat(survivor.videoPath()).isNotNull();
        assertThat(survivor.starred()).isTrue();
        // The pass still evicted the next (non-starred) clip, and freed credits ONLY that one.
        assertThat(clips.findById(newer)).isEmpty();
        assertThat(Files.exists(videoDir.resolve("c-new.mp4"))).isFalse();
        assertThat(result.freedBytes()).isEqualTo(gib);
    }

    @Test
    void matchCandidateVanishingMidPassCreditsTotalSoTheNextVictimSurvives() throws Exception {
        // The oldest VOD is measured into the budget (2 GiB on disk) but is deleted EXTERNALLY in
        // the instant between the measurement and its eviction turn. The loop must credit those
        // 2 GiB back when it finds the file missing — otherwise it keeps evicting the NEWER VOD to
        // offset bytes that are already gone (over-eviction). With the credit, total drops to the
        // 1 GiB cap and the pass ends with the newer VOD intact. The vanished row keeps its path
        // too: a single miss never mutates the row.
        settings.get().retentionCapGb = 1;
        long gib = 1024L * 1024 * 1024;

        long vanishing = seedWithFiles("vanish.mp4", "vanish.jpg", 2 * gib, 1_000L, false);
        long newer = seedWithFiles("new.mp4", "new.jpg", gib, 2_000L, false);

        MatchRepository racingMatches = spy(matches);
        doAnswer(inv -> {
            Object candidates = inv.callRealMethod();
            Files.delete(videoDir.resolve("vanish.mp4")); // external delete, after measurement
            return candidates;
        }).when(racingMatches).findSweepCandidates();
        RetentionSweeper racy = new RetentionSweeper(racingMatches, clips, settings, events);

        RetentionSweeper.SweepResult result = racy.sweep(null);

        assertThat(result.deletedIds()).isEmpty();
        assertThat(result.freedBytes()).isZero();
        assertThat(Files.exists(videoDir.resolve("new.mp4")))
                .as("the next victim must not be evicted to offset already-gone bytes")
                .isTrue();
        assertThat(matches.findById(newer).orElseThrow().videoPath()).isNotNull();
        // First miss for the vanished row: path kept until a second sweep confirms.
        assertThat(matches.findById(vanishing).orElseThrow().videoPath()).isNotNull();
        // total reflects the refund: only the newer VOD's 1 GiB remains on the books.
        assertThat(result.totalAfterBytes()).isEqualTo(gib);
    }

    @Test
    void clipCandidateVanishingMidPassCreditsTotalSoTheNextVictimSurvives() throws Exception {
        // Clip-loop twin of the test above: the oldest clip's measured 2 GiB vanish externally
        // between the measurement and the clip phase. The missing branch must refund the counted
        // bytes so the newer clip is not evicted to offset them.
        settings.get().retentionCapGb = 1;
        long gib = 1024L * 1024 * 1024;

        long match = matches.insert(new NewMatch(
                null, "match", "enriched", "puck",
                1, 2, 3, 400, 500, 10000, 120,
                "win", 7, 22, null, null, 1800,
                1_000L, null, null, null, false, 1_000L, null));
        long vanishing = seedClip(match, "c-vanish.mp4", "c-vanish.jpg", 2 * gib, 100L, false);
        long survivor = seedClip(match, "c-keep.mp4", "c-keep.jpg", gib, 200L, false);

        ClipRepository racingClips = spy(clips);
        doAnswer(inv -> {
            Object candidates = inv.callRealMethod();
            Files.delete(videoDir.resolve("c-vanish.mp4")); // external delete, after measurement
            return candidates;
        }).when(racingClips).findSweepCandidates();
        RetentionSweeper racy = new RetentionSweeper(matches, racingClips, settings, events);

        RetentionSweeper.SweepResult result = racy.sweep(null);

        assertThat(result.freedBytes()).isZero();
        assertThat(Files.exists(videoDir.resolve("c-keep.mp4")))
                .as("the next clip must not be evicted to offset already-gone bytes")
                .isTrue();
        assertThat(clips.findById(survivor)).isPresent();
        // First miss: the vanished clip's row survives with its path until a second sweep confirms.
        assertThat(clips.findById(vanishing).orElseThrow().videoPath()).isNotNull();
        assertThat(result.totalAfterBytes()).isEqualTo(gib);
    }

    @Test
    void transientlyMissingStarredVideoKeepsItsRowAndReappearanceResetsTheMissClock() throws Exception {
        // The cut-paste round trip: the user moves a STARRED VOD out of videoDir while the app
        // runs, a sweep observes the miss, and the user later moves the file back. The single
        // observation must NOT strip the row (a nulled row can never re-link the returning file —
        // attribution requires the recorded basename — and the orphan scan would re-adopt it as a
        // NEW unstarred row at the front of the eviction queue). A reappearance in between must
        // RESET the miss clock — pass AND age — and only two CONSECUTIVE aged misses reconcile the
        // row. The clock advances past the 30-minute floor between every sweep, so the reset (not
        // the age gate) is what each kept-row assertion proves: an unreset entry would be old
        // enough to confirm.
        settings.get().retentionCapGb = 50; // roomy: no eviction pressure in this test
        Path vod = videoDir.resolve("starred.mp4");
        long starredId = seedWithFiles("starred.mp4", "starred.jpg", 1024L, 1_000L, true);
        Path elsewhere = Files.createDirectories(videoDir.getParent().resolve("elsewhere"));
        AtomicLong now = new AtomicLong(1_000_000L);
        RetentionSweeper timed = sweeperAt(now);

        // Cut-pasted out; one sweep observes the miss but must leave the row alone.
        Files.move(vod, elsewhere.resolve("starred.mp4"));
        timed.sweep(null);
        assertThat(matches.findById(starredId).orElseThrow().videoPath())
                .as("a single miss must not strip the row")
                .isEqualTo(vod.toString());

        // The file comes back; the sweep sees it present and resets the miss clock.
        now.addAndGet(31 * 60_000L);
        Files.move(elsewhere.resolve("starred.mp4"), vod);
        timed.sweep(null);
        assertThat(matches.findById(starredId).orElseThrow().videoPath()).isEqualTo(vod.toString());

        // Gone again: a FIRST miss once more (the round trip reset the clock), so the row still
        // keeps its path — even though more than 30 minutes have passed since the ORIGINAL miss.
        now.addAndGet(31 * 60_000L);
        Files.move(vod, elsewhere.resolve("starred.mp4"));
        timed.sweep(null);
        assertThat(matches.findById(starredId).orElseThrow().videoPath())
                .as("a presence in between must reset the miss-confirmation clock")
                .isEqualTo(vod.toString());

        // ...and only the second CONSECUTIVE aged miss reconciles it: row kept, still starred,
        // paths nulled.
        now.addAndGet(31 * 60_000L);
        timed.sweep(null);
        var reconciled = matches.findById(starredId).orElseThrow();
        assertThat(reconciled.videoPath()).isNull();
        assertThat(reconciled.starred()).isTrue();
    }

    @Test
    void archiverPacedLaterPassesCannotConfirmAMissBeforeTheThirtyMinuteFloor() throws Exception {
        // sweepPass ticks on EVERY sweep(Long) entry — including each archiver pass, every ~2
        // minutes — so "a later pass" alone arrives long before a slow manual move finishes. The
        // destructive reconcile therefore also requires the miss to be at least 30 minutes old: a
        // second pass 2 minutes after the first miss must leave the row alone (this is the starred
        // VOD mid-cut-paste to a slow USB drive), and only a later pass past the floor may
        // reconcile it.
        settings.get().retentionCapGb = 50; // roomy: no eviction pressure in this test
        Path vod = videoDir.resolve("starred.mp4");
        long starredId = seedWithFiles("starred.mp4", "starred.jpg", 1024L, 1_000L, true);
        AtomicLong now = new AtomicLong(1_000_000L);
        RetentionSweeper timed = sweeperAt(now);

        Files.delete(vod); // the cut-paste is mid-move: the file is gone from videoDir
        timed.sweep(null); // first miss arms the registry

        now.addAndGet(2 * 60_000L);
        timed.sweep(null); // a LATER pass (archiver-paced), but the miss is only 2 minutes old
        assertThat(matches.findById(starredId).orElseThrow().videoPath())
                .as("an archiver-paced later pass must not confirm a 2-minute-old miss")
                .isEqualTo(vod.toString());

        now.addAndGet(29 * 60_000L); // 31 minutes since the first miss
        timed.sweep(null); // later pass AND past the age floor -> confirmed
        var reconciled = matches.findById(starredId).orElseThrow();
        assertThat(reconciled.videoPath()).isNull();
        assertThat(reconciled.starred()).isTrue();
    }

    @Test
    void ambiguousStatFailureSkipsEvictionForTheCycle() throws Exception {
        // One reachable file's stat fails for a reason OTHER than the file being gone (e.g. an I/O
        // error), so its size is only the DB snapshot — a guess. Evicting real recordings against a
        // guessed total is worse than sweeping an hour late: the whole eviction pass is skipped and
        // every file survives untouched until a clean measurement.
        settings.get().retentionCapGb = 1;
        long gib = 1024L * 1024 * 1024;

        long flaky = seedWithFiles("flaky.mp4", "flaky.jpg", 2 * gib, 1_000L, false);
        long healthy = seedWithFiles("healthy.mp4", "healthy.jpg", gib, 2_000L, false);

        RetentionSweeper.FileSizeProbe failingStat = file -> {
            if (file.getFileName().toString().equals("flaky.mp4")) {
                throw new java.io.IOException("simulated I/O error");
            }
            return Files.size(file);
        };
        RetentionSweeper cautious = new RetentionSweeper(
                matches, clips, settings, events, new StorageMaintenanceLock(),
                dir -> Files.getFileStore(dir).getTotalSpace(),
                dir -> Files.getFileStore(dir).getUsableSpace(),
                failingStat);

        RetentionSweeper.SweepResult result = cautious.sweep(null);

        // Over cap (3 GiB measured vs 1 GiB) but ambiguous: nothing is deleted this cycle.
        assertThat(result.deletedIds()).isEmpty();
        assertThat(result.freedBytes()).isZero();
        assertThat(Files.exists(videoDir.resolve("flaky.mp4"))).isTrue();
        assertThat(Files.exists(videoDir.resolve("healthy.mp4"))).isTrue();
        assertThat(matches.findById(flaky).orElseThrow().videoPath()).isNotNull();
        assertThat(matches.findById(healthy).orElseThrow().videoPath()).isNotNull();
        verify(events, never()).publish(eq("retention.swept"), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void scheduledSweepPassRunsTheFreeSpaceCheck() {
        long gib = 1024L * 1024 * 1024;
        // Inject a low-disk usable-space probe and drive the SCHEDULED no-arg entry point: the pass
        // must run the free-space check so its {scope:"disk"} warning actually reaches the bridge WS
        // (the check previously had only test callers).
        RetentionSweeper.UsableSpaceProbe lowDisk = dir -> gib;
        RetentionSweeper scheduled = new RetentionSweeper(
                matches, clips, settings, events, new StorageMaintenanceLock(),
                dir -> 100L * gib, lowDisk);

        scheduled.sweep();

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq("error"), payload.capture());
        assertThat(((Map<String, Object>) payload.getValue()).get("scope")).isEqualTo("disk");
    }

    @Test
    void freeSpaceCheckNeverThrowsAndReturnsNullWhenHealthy() {
        // The temp video drive has plenty of space in CI; the check must not throw and (almost
        // always) reports healthy. We only assert it doesn't blow up and returns a String-or-null.
        String warning = sweeper.checkFreeSpaceWarning();
        assertThat(warning == null || warning.contains("Low disk space")).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void freeSpaceCheckWarnsAndPublishesErrorWhenUnderTheLowDiskThreshold() {
        long gib = 1024L * 1024 * 1024;
        // Inject a usable-space probe reporting 1 GiB free — under the 5 GiB low-disk threshold — so the
        // warning branch runs deterministically instead of depending on the host drive's real free space.
        RetentionSweeper.UsableSpaceProbe lowDisk = dir -> gib;
        RetentionSweeper lowSweeper = new RetentionSweeper(
                matches, clips, settings, events, new StorageMaintenanceLock(),
                dir -> 100L * gib, lowDisk);

        String warning = lowSweeper.checkFreeSpaceWarning();

        // Returns a warning (never blocks) and publishes a {scope:"disk"} error frame with the free/
        // threshold bytes so the UI can surface it.
        assertThat(warning).contains("Low disk space").contains(String.valueOf(gib));
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq("error"), payload.capture());
        Map<String, Object> body = (Map<String, Object>) payload.getValue();
        assertThat(body.get("scope")).isEqualTo("disk");
        assertThat(body.get("freeBytes")).isEqualTo(gib);
        assertThat((long) (Long) body.get("thresholdBytes")).isEqualTo(5L * gib);
    }

    /** Seeds a match with on-disk video + thumbnail files of {@code sizeBytes}, returns the id. */
    private long seedWithFiles(String video, String thumb, long sizeBytes, long playedAt,
                               boolean starred) throws Exception {
        return seedWithFiles(video, thumb, sizeBytes, sizeBytes, playedAt, starred);
    }

    private long seedWithFiles(String video, String thumb, long diskSizeBytes, long dbSizeBytes,
                               long playedAt, boolean starred) throws Exception {
        return seedWithFilesIn(videoDir, video, thumb, diskSizeBytes, dbSizeBytes, playedAt, starred);
    }

    private long seedWithFilesIn(Path dir, String video, String thumb, long sizeBytes, long playedAt,
                                 boolean starred) throws Exception {
        return seedWithFilesIn(dir, video, thumb, sizeBytes, sizeBytes, playedAt, starred);
    }

    private long seedWithFilesIn(Path dir, String video, String thumb, long diskSizeBytes,
                                 long dbSizeBytes, long playedAt, boolean starred) throws Exception {
        Path videoPath = dir.resolve(video);
        Path thumbPath = dir.resolve(thumb);
        createSparseFile(videoPath, diskSizeBytes);
        Files.createFile(thumbPath);
        return matches.insert(new NewMatch(
                null, "match", "enriched", "puck",
                1, 2, 3, 400, 500, 10000, 120,
                "win", 7, 22, null, null, 1800,
                playedAt, videoPath.toString(), thumbPath.toString(), dbSizeBytes, starred, playedAt,
                null));
    }

    /**
     * Seeds a non-starred match whose {@code video_path} is UNDELETABLE on a present drive (a
     * non-empty directory named like an .mp4, so {@code deleteFileQuietly} returns false) paired with
     * a normal, deletable thumbnail file — isolating the "video delete fails but thumb is removable"
     * case F17 guards. Its dir reports size 0, so it contributes nothing to the budget. Returns the id.
     */
    private long seedMatchWithUndeletableVideo(String video, String thumb, long playedAt)
            throws Exception {
        Path videoPath = videoDir.resolve(video);
        Files.createDirectories(videoPath);
        Files.writeString(videoPath.resolve("inner"), "x"); // non-empty -> deletion fails
        Path thumbPath = videoDir.resolve(thumb);
        Files.createFile(thumbPath);
        return matches.insert(new NewMatch(
                null, "match", "enriched", "puck",
                1, 2, 3, 400, 500, 10000, 120,
                "win", 7, 22, null, null, 1800,
                playedAt, videoPath.toString(), thumbPath.toString(), null, false, playedAt, null));
    }

    /** Seeds a ready clip of {@code parentMatchId} with on-disk video + thumb files; returns its id. */
    private long seedClip(long parentMatchId, String video, String thumb, long sizeBytes, long createdAt,
                          boolean starred) throws Exception {
        return seedClip(parentMatchId, video, thumb, sizeBytes, sizeBytes, createdAt, starred);
    }

    /**
     * Seeds a ready clip whose on-disk .mp4 is {@code diskSizeBytes} but whose recorded
     * {@code file_size_bytes} is {@code dbSizeBytes} — lets a test drive the real-vs-DB size drift.
     */
    private long seedClip(long parentMatchId, String video, String thumb, long diskSizeBytes,
                          long dbSizeBytes, long createdAt, boolean starred) throws Exception {
        Path videoPath = videoDir.resolve(video);
        Path thumbPath = videoDir.resolve(thumb);
        createSparseFile(videoPath, diskSizeBytes);
        Files.createFile(thumbPath);
        long id = clips.insert(parentMatchId, "manual", null, 0.0, 10.0, null,
                videoPath.toString(), thumbPath.toString(), dbSizeBytes, "ready", null, createdAt);
        if (starred) {
            clips.setStarred(id, true);
        }
        return id;
    }

    /**
     * Seeds a ready clip whose {@code video_path} is UNDELETABLE on a present drive: it points at a
     * NON-EMPTY directory (named like an .mp4) under {@code videoDir}, so {@link
     * java.nio.file.Files#deleteIfExists} throws {@code DirectoryNotEmptyException} and the sweeper's
     * {@code deleteFileQuietly} returns false — while the parent (videoDir) stays reachable so the sweep
     * reaches the file-op path rather than short-circuiting on {@code driveReachable}. The thumb is a
     * normal deletable file, isolating the undeletable-video behavior. Returns the clip id.
     */
    private long seedClipWithUndeletableVideo(long parentMatchId, String video, long createdAt)
            throws Exception {
        Path videoPath = videoDir.resolve(video);
        Files.createDirectories(videoPath);
        Files.writeString(videoPath.resolve("inner"), "x"); // non-empty -> deletion fails
        Path thumbPath = videoDir.resolve(video + ".jpg");
        Files.createFile(thumbPath);
        return clips.insert(parentMatchId, "manual", null, 0.0, 10.0, null,
                videoPath.toString(), thumbPath.toString(), null, "ready", null, createdAt);
    }

    private static void createSparseFile(Path path, long sizeBytes) throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
            file.setLength(sizeBytes);
        }
    }
}

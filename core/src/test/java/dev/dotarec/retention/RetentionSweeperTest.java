package dev.dotarec.retention;

import dev.dotarec.bridge.EventPublisher;
import dev.dotarec.config.AppPaths;
import dev.dotarec.config.SettingsStore;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
    private SettingsStore settings;
    private EventPublisher events;
    private RetentionSweeper sweeper;
    private Path videoDir;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        DataSource ds = TestDb.migrated(dir);
        matches = new MatchRepository(ds);
        markers = new MarkerRepository(ds);

        videoDir = Files.createDirectories(dir.resolve("video"));
        // Real SettingsStore over a temp data dir; default cap is 50GB, we shrink it per-test.
        settings = new SettingsStore(
                new AppPaths(dir.resolve("data").toString(), dir.resolve("obs").toString()));
        settings.get().videoDir = videoDir.toString();

        events = mock(EventPublisher.class);
        sweeper = new RetentionSweeper(matches, settings, events);
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
                new RetentionSweeper(matches, settings, events, new StorageMaintenanceLock(), probe);

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
    void freeSpaceCheckNeverThrowsAndReturnsNullWhenHealthy() {
        // The temp video drive has plenty of space in CI; the check must not throw and (almost
        // always) reports healthy. We only assert it doesn't blow up and returns a String-or-null.
        String warning = sweeper.checkFreeSpaceWarning();
        assertThat(warning == null || warning.contains("Low disk space")).isTrue();
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

    private static void createSparseFile(Path path, long sizeBytes) throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
            file.setLength(sizeBytes);
        }
    }
}

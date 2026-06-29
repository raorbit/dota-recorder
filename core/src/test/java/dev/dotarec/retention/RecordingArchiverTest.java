package dev.dotarec.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;

import dev.dotarec.bridge.EventPublisher;
import dev.dotarec.config.AppPaths;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.config.SettingsStore.StorageLocation;
import dev.dotarec.data.ClipRepository;
import dev.dotarec.data.MatchRepository;
import dev.dotarec.data.MatchRepository.NewMatch;
import dev.dotarec.data.TestDb;
import java.io.RandomAccessFile;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

/**
 * Drives {@link RecordingArchiver} against real temp files + a real SQLite DB: the oldest VODs are
 * moved off the active drive into archive drives (filling them in list order), drives whose cap
 * exceeds their real free space are skipped, and the global sum-of-caps budget still evicts the
 * oldest. The free-space probe is injected so the host filesystem can't make the test flaky.
 */
class RecordingArchiverTest {

    private static final long GIB = 1024L * 1024 * 1024;
    /** A drive with effectively unlimited free space unless the test overrides it. */
    private static final long HUGE_FREE = 1_000L * GIB;

    private MatchRepository matches;
    private ClipRepository clips;
    private SettingsStore settings;
    private RetentionSweeper sweeper;
    private RecordingArchiver archiver;
    private Path videoDir;
    private final Map<String, Long> freeByDir = new HashMap<>();

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        DataSource ds = TestDb.migrated(dir);
        matches = new MatchRepository(ds);
        clips = new ClipRepository(ds);
        videoDir = Files.createDirectories(dir.resolve("ssd"));
        settings = new SettingsStore(
                new AppPaths(dir.resolve("data").toString(), dir.resolve("obs").toString()));
        settings.get().videoDir = videoDir.toString();
        sweeper = new RetentionSweeper(matches, clips, settings, mock(EventPublisher.class));
        // Deterministic free-space probe: each dir reports HUGE_FREE unless the test overrides it.
        RecordingArchiver.FreeSpaceProbe probe =
                d -> freeByDir.getOrDefault(d.toAbsolutePath().normalize().toString(), HUGE_FREE);
        archiver = new RecordingArchiver(matches, clips, settings, sweeper, probe);
    }

    @Test
    void relocatesOldestActiveVodToArchiveAndRepointsPaths() throws Exception {
        Path archive = configureArchive("hdd", 10);
        settings.get().retentionCapGb = 1; // active holds 1 GiB; two 1 GiB VODs forces a move

        long oldest = seed(videoDir, "old.mp4", "old.jpg", GIB, 1_000L, false);
        long newer = seed(videoDir, "new.mp4", "new.jpg", GIB, 2_000L, false);

        RecordingArchiver.ArchiveResult result = archiver.archive(null);

        // The oldest moves to the archive (under an id-prefixed name); the newest stays on the fast
        // drive. The destination filename is prefixed with the match id so two VODs that share OBS's
        // per-second timestamp name can never collide on the archive drive.
        assertThat(result.movedIds()).containsExactly(oldest);
        assertThat(Files.exists(videoDir.resolve("old.mp4"))).isFalse();
        assertThat(Files.exists(archive.resolve(oldest + "-old.mp4"))).isTrue();
        assertThat(Files.exists(archive.resolve("thumbs").resolve(oldest + "-old.jpg"))).isTrue();
        assertThat(Files.exists(videoDir.resolve("new.mp4"))).isTrue();

        // DB row repointed to the archive (and file size kept, not nulled).
        var movedRow = matches.findById(oldest).orElseThrow();
        assertThat(movedRow.videoPath()).isEqualTo(archive.resolve(oldest + "-old.mp4").toString());
        assertThat(movedRow.thumbPath())
                .isEqualTo(archive.resolve("thumbs").resolve(oldest + "-old.jpg").toString());
        assertThat(movedRow.fileSizeBytes()).isEqualTo(GIB);
        assertThat(matches.findById(newer).orElseThrow().videoPath())
                .isEqualTo(videoDir.resolve("new.mp4").toString());
    }

    @Test
    void fillsArchivesInListOrder() throws Exception {
        Path first = configureArchive("hdd1", 1); // holds exactly one 1 GiB VOD
        Path second = configureArchive("hdd2", 10);
        settings.get().retentionCapGb = 1; // three 1 GiB VODs => move the two oldest off active

        long oldest = seed(videoDir, "a.mp4", "a.jpg", GIB, 1_000L, false);
        long mid = seed(videoDir, "b.mp4", "b.jpg", GIB, 2_000L, false);
        long newest = seed(videoDir, "c.mp4", "c.jpg", GIB, 3_000L, false);

        RecordingArchiver.ArchiveResult result = archiver.archive(null);

        assertThat(result.movedIds()).containsExactly(oldest, mid);
        // Oldest fills the first (small) archive; the next spills to the second. Destinations are
        // id-prefixed (collision-proof).
        assertThat(matches.findById(oldest).orElseThrow().videoPath())
                .isEqualTo(first.resolve(oldest + "-a.mp4").toString());
        assertThat(matches.findById(mid).orElseThrow().videoPath())
                .isEqualTo(second.resolve(mid + "-b.mp4").toString());
        // Newest stays on the fast drive.
        assertThat(matches.findById(newest).orElseThrow().videoPath())
                .isEqualTo(videoDir.resolve("c.mp4").toString());
    }

    @Test
    void skipsArchiveWhoseCapExceedsPhysicalFreeSpace() throws Exception {
        Path archive = configureArchive("hdd", 10); // generous cap...
        // ...but the drive physically has only half a VOD of free space.
        freeByDir.put(archive.toAbsolutePath().normalize().toString(), GIB / 2);
        settings.get().retentionCapGb = 1;

        long oldest = seed(videoDir, "old.mp4", "old.jpg", GIB, 1_000L, false);
        seed(videoDir, "new.mp4", "new.jpg", GIB, 2_000L, false);

        RecordingArchiver.ArchiveResult result = archiver.archive(null);

        // No physical room despite the cap: nothing moves, the VOD stays on the active drive (nothing
        // is written under the id-prefixed destination name the move would have used either).
        assertThat(result.movedIds()).isEmpty();
        assertThat(Files.exists(videoDir.resolve("old.mp4"))).isTrue();
        assertThat(Files.exists(archive.resolve(oldest + "-old.mp4"))).isFalse();
    }

    @Test
    void movesStarredBeforeUnstarredUnderCapPressure() throws Exception {
        Path archive = configureArchive("hdd", 10);
        settings.get().retentionCapGb = 1; // active holds 1 GiB; 2 GiB forces moving exactly one

        // The OLDER VOD is unstarred; the NEWER one is starred. Age alone would move the unstarred
        // first, but keepers are prioritized — so the starred one goes to the safe archive first.
        long unstarredOlder = seed(videoDir, "old.mp4", "old.jpg", GIB, 1_000L, false);
        long starredNewer = seed(videoDir, "star.mp4", "star.jpg", GIB, 2_000L, true);

        RecordingArchiver.ArchiveResult result = archiver.archive(null);

        assertThat(result.movedIds()).containsExactly(starredNewer);
        assertThat(matches.findById(starredNewer).orElseThrow().videoPath())
                .isEqualTo(archive.resolve(starredNewer + "-star.mp4").toString());
        // The unstarred (older) one stays on the active drive this pass.
        assertThat(matches.findById(unstarredOlder).orElseThrow().videoPath())
                .isEqualTo(videoDir.resolve("old.mp4").toString());
    }

    @Test
    void noArchivesConfiguredIsNoOp() throws Exception {
        // Cap well above the seeded size so the sweeper never deletes: with no archive drives there
        // is nowhere to relocate to, so the VOD just stays put on the active drive.
        settings.get().retentionCapGb = 50;
        long id = seed(videoDir, "old.mp4", "old.jpg", 2 * GIB, 1_000L, false);

        RecordingArchiver.ArchiveResult result = archiver.archive(null);

        assertThat(result.movedIds()).isEmpty();
        assertThat(matches.findById(id).orElseThrow().videoPath())
                .isEqualTo(videoDir.resolve("old.mp4").toString());
    }

    @Test
    void evictsGloballyOldestWhenTotalExceedsSumOfCaps() throws Exception {
        Path archive = configureArchive("hdd", 1); // sum-of-caps budget = 1 (active) + 1 = 2 GiB
        settings.get().retentionCapGb = 1;

        // Three 1 GiB VODs on the active drive => total 3 GiB > 2 GiB budget.
        long oldest = seed(videoDir, "a.mp4", "a.jpg", GIB, 1_000L, false);
        long mid = seed(videoDir, "b.mp4", "b.jpg", GIB, 2_000L, false);
        long newest = seed(videoDir, "c.mp4", "c.jpg", GIB, 3_000L, false);

        archiver.archive(null);

        // The global oldest is deleted (sweeper, video_path nulled); the rest fit and are placed:
        // the next-oldest spills to the archive (id-prefixed), the newest stays on the fast drive.
        assertThat(matches.findById(oldest).orElseThrow().videoPath()).isNull();
        assertThat(matches.findById(mid).orElseThrow().videoPath())
                .isEqualTo(archive.resolve(mid + "-b.mp4").toString());
        assertThat(matches.findById(newest).orElseThrow().videoPath())
                .isEqualTo(videoDir.resolve("c.mp4").toString());
    }

    @Test
    void crossStoreMoveRepointsDbBeforeDeletingSource() throws Exception {
        // Crash-safety ordering proof: on the cross-store (SSD->HDD) copy path the row must be
        // repointed to the already-written destination BEFORE the source is deleted. We make
        // updateVideoPath blow up to simulate a crash/failure at the repoint step and assert the
        // SOURCE FILE STILL EXISTS — no bytes lost — and the row was NOT left pointing at a deleted
        // file. A spy on the real repo keeps reads delegating while only updateVideoPath throws.
        Path archive = configureArchive("hdd", 10);
        settings.get().retentionCapGb = 1; // ~2 GiB on active over a 1 GiB cap forces a move

        MatchRepository spyRepo = spy(matches);
        // Repoint fails (e.g. DB locked / crash): the move must not have deleted the source yet.
        doThrow(new IllegalStateException("simulated repoint failure"))
                .when(spyRepo).updateVideoPath(anyLong(), anyString(), any());
        RetentionSweeper spySweeper =
                new RetentionSweeper(spyRepo, clips, settings, mock(EventPublisher.class));
        RecordingArchiver spyArchiver =
                new RecordingArchiver(spyRepo, clips, settings, spySweeper, hugeFreeProbe());

        // The OLDEST (move candidate) is deliberately TINY so the forced real byte-copy below is cheap;
        // a large SPARSE newer VOD pushes the active drive over its 1 GiB cap to trigger the move
        // without itself being copied (it stays on the active drive). Sparse files cost ~no disk and
        // Files.size reports the logical size the cap math needs.
        long oldest = seed(videoDir, "old.mp4", "old.jpg", 4 * 1024, 1_000L, false);
        seed(videoDir, "new.mp4", "new.jpg", 2 * GIB, 2_000L, false);

        // Force the cross-store branch: a real @TempDir move would be a same-FS atomic rename (source
        // consumed up front), which never exercises the copy-then-delete-after-repoint ordering. The
        // static mock throws ONLY on the ATOMIC_MOVE overload, so the archiver copies + verifies and
        // leaves the source in place — exactly the SSD->HDD case in production.
        RecordingArchiver.ArchiveResult result;
        try (MockedStatic<Files> ignored = forceCrossStoreMoves()) {
            result = spyArchiver.archive(null);
        }

        // The repoint failed, so the move reports nothing relocated.
        assertThat(result.movedIds()).isEmpty();
        // The source still has the bytes: the copy-then-delete order means the source is NEVER
        // unlinked before the row points at the (fully-written) destination.
        assertThat(Files.exists(videoDir.resolve("old.mp4"))).isTrue();
        assertThat(Files.size(videoDir.resolve("old.mp4"))).isEqualTo(4 * 1024);
        // The DB row is untouched: it still points at the source on the active drive, NOT at a
        // half-finished destination. (A stale destination copy may linger; the orphan scan reclaims
        // it. The cardinal sin — a row pointing at a deleted file — never happens.)
        assertThat(matches.findById(oldest).orElseThrow().videoPath())
                .isEqualTo(videoDir.resolve("old.mp4").toString());
    }

    @Test
    void crossStoreMoveSucceedsDestExistsSourceGoneRowRepointed() throws Exception {
        // The happy path through the SAME forced cross-store branch: destination fully written, source
        // deleted (only AFTER the repoint), and the row points at the id-prefixed destination name.
        Path archive = configureArchive("hdd", 10);
        settings.get().retentionCapGb = 1;

        // The OLDEST is the large (sparse) VOD so moving just it drops the active drive under its cap
        // and the drain loop stops after one move. The copy is of a sparse file, so the real cross-store
        // Files.copy (native CopyFileEx) preserves the holes and stays cheap despite the 2 GiB size.
        long oldest = seed(videoDir, "old.mp4", "old.jpg", 2 * GIB, 1_000L, false);
        seed(videoDir, "new.mp4", "new.jpg", 4 * 1024, 2_000L, false);

        RecordingArchiver.ArchiveResult result;
        try (MockedStatic<Files> ignored = forceCrossStoreMoves()) {
            result = archiver.archive(null);
        }

        assertThat(result.movedIds()).containsExactly(oldest);
        // Destination present, source gone (deleted post-repoint), row repointed to the id-prefixed name.
        Path dst = archive.resolve(oldest + "-old.mp4");
        assertThat(Files.exists(dst)).isTrue();
        assertThat(Files.size(dst)).isEqualTo(2 * GIB);
        assertThat(Files.exists(videoDir.resolve("old.mp4"))).isFalse();
        var row = matches.findById(oldest).orElseThrow();
        assertThat(row.videoPath()).isEqualTo(dst.toString());
        assertThat(row.thumbPath())
                .isEqualTo(archive.resolve("thumbs").resolve(oldest + "-old.jpg").toString());
        assertThat(row.fileSizeBytes()).isEqualTo(2 * GIB); // bytes unchanged by a relocation
    }

    @Test
    void destinationFilenameIsPrefixedWithMatchIdAndCannotClobberUnrelatedFile() throws Exception {
        // Two matches whose OBS per-second filenames happen to collide (a clock anomaly) must not
        // overwrite each other on the archive drive: the id prefix disambiguates them. We also plant a
        // pre-existing unrelated file with the bare (un-prefixed) name on the archive drive and assert
        // the move leaves it untouched.
        Path archive = configureArchive("hdd", 10);
        settings.get().retentionCapGb = 1; // one 1 GiB cap; two 1 GiB VODs => move the oldest off

        // An unrelated, pre-existing file sharing the bare VOD name (no id prefix) on the archive.
        Path bystander = Files.write(archive.resolve("clash.mp4"), new byte[] {1, 2, 3});

        long oldest = seed(videoDir, "clash.mp4", "clash.jpg", GIB, 1_000L, false);
        seed(videoDir, "newer.mp4", "newer.jpg", GIB, 2_000L, false);

        archiver.archive(null);

        // The moved file lands under an id-prefixed name, so it never targets (let alone clobbers) the
        // bystander sharing the bare name.
        Path dst = archive.resolve(oldest + "-clash.mp4");
        assertThat(matches.findById(oldest).orElseThrow().videoPath()).isEqualTo(dst.toString());
        assertThat(Files.exists(dst)).isTrue();
        // The unrelated bystander is intact, byte-for-byte.
        assertThat(Files.exists(bystander)).isTrue();
        assertThat(Files.readAllBytes(bystander)).containsExactly(1, 2, 3);
    }

    @Test
    void unplaceableHeadDoesNotBlockASmallerLaterVodFromMoving() throws Exception {
        // Head-of-line guard: an oldest VOD too large for any archive must NOT abort the whole pass.
        // A smaller, newer VOD that DOES fit has to be relocated; only the giant head stays put.
        Path archive = configureArchive("hdd", 5); // archive cap 5 GiB
        settings.get().retentionCapGb = 1; // active cap 1 GiB

        // Sizes are chosen so the SWEEPER deletes nothing (total == the 6 GiB sum-of-caps budget) while
        // the active drive is still over its OWN 1 GiB cap (so the move loop runs). The 5.5 GiB head
        // exceeds the 5 GiB archive cap (unplaceable); the 0.5 GiB tail fits. Files are sparse, so the
        // 5.5 GiB head costs ~no disk and is never byte-copied (it stays via the head-of-line skip).
        long head = seed(videoDir, "head.mp4", "head.jpg", 11 * GIB / 2, 1_000L, false);
        long tail = seed(videoDir, "tail.mp4", "tail.jpg", GIB / 2, 2_000L, false);

        RecordingArchiver.ArchiveResult result = archiver.archive(null);

        // The smaller tail got placed even though the oversized head ahead of it could not — the pass
        // skips the unplaceable head and keeps draining instead of aborting.
        assertThat(result.movedIds()).containsExactly(tail);
        assertThat(matches.findById(tail).orElseThrow().videoPath())
                .isEqualTo(archive.resolve(tail + "-tail.mp4").toString());
        // The oversized head stays on the active drive (unplaceable, but never blocks the queue) and is
        // not pruned (total was within budget, so the sweeper left it alone).
        assertThat(matches.findById(head).orElseThrow().videoPath())
                .isEqualTo(videoDir.resolve("head.mp4").toString());
        assertThat(Files.exists(videoDir.resolve("head.mp4"))).isTrue();
    }

    @Test
    void doesNotMisattributeAVodOnAPrefixSharingSiblingDriveToTheActiveDrive() throws Exception {
        // Path-prefix correctness: locationOf appends a separator before its startsWith match so an
        // active drive "<tmp>/vid" never claims a file stored under the sibling "<tmp>/video2" (the
        // bare string "<tmp>/video2/..." DOES start with "<tmp>/vid", so a regression to a bare
        // startsWith would mis-attribute it). Every other test here uses non-prefix-sharing names
        // (ssd/hdd/hdd1/hdd2), so this is the only guard on the separator-append.
        //
        // Re-point the active drive at "<tmp>/vid" and add an archive at the sibling "<tmp>/video2"
        // whose name shares that prefix. Seed an archive-resident VOD under video2 plus two VODs on the
        // active vid drive that over-cap it (forcing a real move). If video2's VOD were mis-classified
        // as living on the active drive it would be counted against the active cap AND become a move
        // candidate (it is the OLDEST, so the FIRST one a regression would try to relocate). We assert
        // it is left exactly where it sits: not moved, path unchanged, file untouched on disk.
        Path vid = Files.createDirectories(videoDir.getParent().resolve("vid"));
        settings.get().videoDir = vid.toString();
        videoDir = vid; // so configureArchive's parent-resolve produces the sibling "<tmp>/video2"
        Path video2 = configureArchive("video2", 10);
        settings.get().retentionCapGb = 1; // active "vid" holds 1 GiB; two 1 GiB VODs forces a move

        // The archive-resident VOD is the OLDEST overall: a bare-startsWith regression would attribute
        // it to "vid" and pick it as the first relocation candidate.
        long onArchive = seed(video2, "v2.mp4", "v2.jpg", GIB, 500L, false);
        long activeOldest = seed(vid, "a.mp4", "a.jpg", GIB, 1_000L, false);
        long activeNewest = seed(vid, "b.mp4", "b.jpg", GIB, 2_000L, false);

        RecordingArchiver.ArchiveResult result = archiver.archive(null);

        // Only an ACTIVE-drive VOD is relocated; the video2 VOD is correctly seen as already on the
        // archive and is never a move candidate (so it is absent from movedIds).
        assertThat(result.movedIds()).containsExactly(activeOldest);
        assertThat(result.movedIds()).doesNotContain(onArchive);

        // The video2 VOD's row and file are untouched — not repointed, not relocated, byte-for-byte
        // intact where it was seeded under the sibling drive.
        var archiveRow = matches.findById(onArchive).orElseThrow();
        assertThat(archiveRow.videoPath()).isEqualTo(video2.resolve("v2.mp4").toString());
        assertThat(archiveRow.thumbPath())
                .isEqualTo(video2.resolve("thumbs").resolve("v2.jpg").toString());
        assertThat(Files.exists(video2.resolve("v2.mp4"))).isTrue();
        assertThat(Files.size(video2.resolve("v2.mp4"))).isEqualTo(GIB);
        // No id-prefixed copy of it was created on the active vid drive (which a mis-move would do).
        assertThat(Files.exists(vid.resolve(onArchive + "-v2.mp4"))).isFalse();

        // The active drive's own oldest VOD did relocate onto video2 (under the id-prefixed name),
        // confirming the move loop actually ran and the archive drive was usable as a target.
        assertThat(matches.findById(activeOldest).orElseThrow().videoPath())
                .isEqualTo(video2.resolve(activeOldest + "-a.mp4").toString());
        assertThat(matches.findById(activeNewest).orElseThrow().videoPath())
                .isEqualTo(vid.resolve("b.mp4").toString());
    }

    /** Adds an archive location with {@code capGb} and returns its created directory. */
    private Path configureArchive(String name, int capGb) throws Exception {
        Path dir = Files.createDirectories(videoDir.getParent().resolve(name));
        if (settings.get().storageLocations == null) {
            settings.get().storageLocations = new ArrayList<>();
        }
        settings.get().storageLocations.add(new StorageLocation(name, dir.toString(), capGb));
        return dir;
    }

    private long seed(Path dir, String video, String thumb, long sizeBytes, long playedAt,
                      boolean starred) throws Exception {
        Path videoPath = dir.resolve(video);
        Path thumbDir = Files.createDirectories(dir.resolve("thumbs"));
        Path thumbPath = thumbDir.resolve(thumb);
        try (RandomAccessFile f = new RandomAccessFile(videoPath.toFile(), "rw")) {
            f.setLength(sizeBytes);
        }
        Files.createFile(thumbPath);
        return matches.insert(new NewMatch(
                null, "match", "enriched", "puck",
                1, 2, 3, 400, 500, 10000, 120,
                "win", 7, 22, null, null, 1800,
                playedAt, videoPath.toString(), thumbPath.toString(), sizeBytes, starred, playedAt,
                null));
    }

    /** The default huge-free probe wired in {@link #setUp}, reusable for spy-built archivers. */
    private RecordingArchiver.FreeSpaceProbe hugeFreeProbe() {
        return d -> freeByDir.getOrDefault(d.toAbsolutePath().normalize().toString(), HUGE_FREE);
    }

    /**
     * Forces the archiver down its cross-store COPY path for the scope of the returned mock. Same-FS
     * @TempDir moves are atomic renames (source consumed before the repoint), which never exercise the
     * copy → repoint → delete-source ordering that is the crash-safety crux. We mock ONLY the {@code
     * Files.move(src, dst, ATOMIC_MOVE, REPLACE_EXISTING)} overload to throw {@link
     * AtomicMoveNotSupportedException}; every other {@code Files} call delegates to the real method, so
     * the subsequent copy/verify/delete run for real on disk. Caller must close it (try-with-resources).
     */
    private static MockedStatic<Files> forceCrossStoreMoves() {
        MockedStatic<Files> mocked = mockStatic(Files.class, invocation -> invocation.callRealMethod());
        mocked.when(
                        () ->
                                Files.move(
                                        any(Path.class),
                                        any(Path.class),
                                        eq(StandardCopyOption.ATOMIC_MOVE),
                                        eq(StandardCopyOption.REPLACE_EXISTING)))
                .thenThrow(new AtomicMoveNotSupportedException(null, null, "forced cross-store in test"));
        return mocked;
    }
}

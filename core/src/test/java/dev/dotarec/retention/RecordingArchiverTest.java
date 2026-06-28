package dev.dotarec.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.dotarec.bridge.EventPublisher;
import dev.dotarec.config.AppPaths;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.config.SettingsStore.StorageLocation;
import dev.dotarec.data.MatchRepository;
import dev.dotarec.data.MatchRepository.NewMatch;
import dev.dotarec.data.TestDb;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    private SettingsStore settings;
    private RetentionSweeper sweeper;
    private RecordingArchiver archiver;
    private Path videoDir;
    private final Map<String, Long> freeByDir = new HashMap<>();

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        DataSource ds = TestDb.migrated(dir);
        matches = new MatchRepository(ds);
        videoDir = Files.createDirectories(dir.resolve("ssd"));
        settings = new SettingsStore(
                new AppPaths(dir.resolve("data").toString(), dir.resolve("obs").toString()));
        settings.get().videoDir = videoDir.toString();
        sweeper = new RetentionSweeper(matches, settings, mock(EventPublisher.class));
        // Deterministic free-space probe: each dir reports HUGE_FREE unless the test overrides it.
        RecordingArchiver.FreeSpaceProbe probe =
                d -> freeByDir.getOrDefault(d.toAbsolutePath().normalize().toString(), HUGE_FREE);
        archiver = new RecordingArchiver(matches, settings, sweeper, probe);
    }

    @Test
    void relocatesOldestActiveVodToArchiveAndRepointsPaths() throws Exception {
        Path archive = configureArchive("hdd", 10);
        settings.get().retentionCapGb = 1; // active holds 1 GiB; two 1 GiB VODs forces a move

        long oldest = seed(videoDir, "old.mp4", "old.jpg", GIB, 1_000L, false);
        long newer = seed(videoDir, "new.mp4", "new.jpg", GIB, 2_000L, false);

        RecordingArchiver.ArchiveResult result = archiver.archive(null);

        // The oldest moves to the archive; the newest stays on the fast drive.
        assertThat(result.movedIds()).containsExactly(oldest);
        assertThat(Files.exists(videoDir.resolve("old.mp4"))).isFalse();
        assertThat(Files.exists(archive.resolve("old.mp4"))).isTrue();
        assertThat(Files.exists(archive.resolve("thumbs").resolve("old.jpg"))).isTrue();
        assertThat(Files.exists(videoDir.resolve("new.mp4"))).isTrue();

        // DB row repointed to the archive (and file size kept, not nulled).
        var movedRow = matches.findById(oldest).orElseThrow();
        assertThat(movedRow.videoPath()).isEqualTo(archive.resolve("old.mp4").toString());
        assertThat(movedRow.thumbPath()).isEqualTo(archive.resolve("thumbs").resolve("old.jpg").toString());
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
        // Oldest fills the first (small) archive; the next spills to the second.
        assertThat(matches.findById(oldest).orElseThrow().videoPath())
                .isEqualTo(first.resolve("a.mp4").toString());
        assertThat(matches.findById(mid).orElseThrow().videoPath())
                .isEqualTo(second.resolve("b.mp4").toString());
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

        seed(videoDir, "old.mp4", "old.jpg", GIB, 1_000L, false);
        seed(videoDir, "new.mp4", "new.jpg", GIB, 2_000L, false);

        RecordingArchiver.ArchiveResult result = archiver.archive(null);

        // No physical room despite the cap: nothing moves, the VOD stays on the active drive.
        assertThat(result.movedIds()).isEmpty();
        assertThat(Files.exists(videoDir.resolve("old.mp4"))).isTrue();
        assertThat(Files.exists(archive.resolve("old.mp4"))).isFalse();
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
                .isEqualTo(archive.resolve("star.mp4").toString());
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
        // the next-oldest spills to the archive, the newest stays on the fast drive.
        assertThat(matches.findById(oldest).orElseThrow().videoPath()).isNull();
        assertThat(matches.findById(mid).orElseThrow().videoPath())
                .isEqualTo(archive.resolve("b.mp4").toString());
        assertThat(matches.findById(newest).orElseThrow().videoPath())
                .isEqualTo(videoDir.resolve("c.mp4").toString());
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
}

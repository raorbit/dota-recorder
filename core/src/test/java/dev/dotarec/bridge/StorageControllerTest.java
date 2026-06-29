package dev.dotarec.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dotarec.bridge.StorageController.DriveUsage;
import dev.dotarec.bridge.StorageController.StorageUsage;
import dev.dotarec.config.AppPaths;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.data.ClipRepository;
import dev.dotarec.data.MatchRepository;
import dev.dotarec.data.MatchRepository.NewMatch;
import dev.dotarec.data.TestDb;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageControllerTest {

    private DataSource ds;
    private MatchRepository matches;
    private ClipRepository clips;
    private SettingsStore settings;
    private StorageController controller;
    private Path videoDir;
    private Path archiveDir;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        ds = TestDb.migrated(tmp);
        matches = new MatchRepository(ds);
        clips = new ClipRepository(ds);
        videoDir = Files.createDirectories(tmp.resolve("video"));
        archiveDir = Files.createDirectories(tmp.resolve("hdd"));
        settings =
                new SettingsStore(
                        new AppPaths(tmp.resolve("data").toString(), tmp.resolve("obs").toString()));
        settings.update(
                s -> {
                    s.videoDir = videoDir.toString();
                    s.storageLocations = new java.util.ArrayList<>();
                    s.storageLocations.add(
                            new SettingsStore.StorageLocation("hdd", archiveDir.toString(), 100));
                    return s;
                });
        controller = new StorageController(settings, matches, clips);
    }

    @Test
    void usageCountsClipBytesPerDriveAndInTotals() {
        // Two match VODs on the active drive (one starred) ...
        insertMatch(videoDir.resolve("m1.mp4").toString(), 1_000L, false);
        long starredMatch = insertMatch(videoDir.resolve("m2.mp4").toString(), 2_000L, false);
        matches.setStarred(starredMatch, true);
        // ... a clip on the active drive (under videoDir/clips), and a starred clip relocated to the
        // archive drive. Before the fix, clip bytes were omitted entirely from this report.
        long parent = insertMatch(videoDir.resolve("parent.mp4").toString(), 0L, false);
        insertClip(parent, videoDir.resolve("clips").resolve("c1.mp4").toString(), 500L, false);
        long starredClip = insertClip(parent, archiveDir.resolve("clip-9-x.mp4").toString(), 4_000L, false);
        clips.setStarred(starredClip, true);

        StorageUsage usage = controller.usage();

        DriveUsage active = usage.drives().get(0);
        DriveUsage archive = usage.drives().get(1);
        assertThat(active.role()).isEqualTo("active");
        // 1000 + 2000 (matches) + 500 (active-drive clip) = 3500
        assertThat(active.usedBytes()).isEqualTo(3_500L);
        assertThat(archive.role()).isEqualTo("archive");
        // the relocated clip's bytes land on the archive drive, not the active one
        assertThat(archive.usedBytes()).isEqualTo(4_000L);

        // Totals include clips: 1000 + 2000 + 500 + 4000 = 7500 ...
        assertThat(usage.totalBytes()).isEqualTo(7_500L);
        // ... and the starred subset counts the starred match AND the starred clip: 2000 + 4000 = 6000.
        assertThat(usage.starredBytes()).isEqualTo(6_000L);
    }

    /** Inserts a minimal match VOD pointing at {@code videoPath} with the given size; returns its id. */
    private long insertMatch(String videoPath, long fileSizeBytes, boolean starred) {
        return matches.insert(
                new NewMatch(
                        null, "match", "enriched", "npc_dota_hero_puck",
                        null, null, null, null, null, null, null,
                        null, null, null, null, null, null,
                        1_000L, videoPath, null, fileSizeBytes, starred, 1_000L, null));
    }

    /** Inserts a ready clip for {@code parentId} pointing at {@code videoPath} with the given size. */
    private long insertClip(long parentId, String videoPath, long fileSizeBytes, boolean starred) {
        long id =
                clips.insert(
                        parentId, "auto", "rampage", 10.0, 30.0, null, videoPath, null, fileSizeBytes,
                        "ready", null, 1_000L);
        if (starred) {
            clips.setStarred(id, true);
        }
        return id;
    }
}

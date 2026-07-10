package dev.dotarec.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dotarec.config.SettingsStore.Settings;
import dev.dotarec.config.SettingsStore.StorageLocation;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the ONE allow-list definition ({@link StorageRoots#of}) the stream endpoints and the
 * retention sweeper share: every root class — active videoDir, archive drives, historical videoDirs,
 * and historical (removed) archive dirs — must be served, or its files 404 on stream and are refused
 * on delete/sweep while still counting toward the retention budget.
 */
class StorageRootsTest {

    @Test
    void of_includesActiveArchiveAndBothHistoricalRootClasses() {
        Settings s = new Settings();
        s.videoDir = "C:/clips";
        s.storageLocations = List.of(new StorageLocation("a", "E:/archive", 500));
        s.previousVideoDirs = List.of("D:/old-clips");
        s.previousArchiveDirs = List.of("F:/old-archive");

        assertThat(StorageRoots.of(s))
                .containsExactly("C:/clips", "E:/archive", "D:/old-clips", "F:/old-archive");
    }

    @Test
    void of_toleratesNullHistoricalLists() {
        // A Settings built outside load() (e.g. in tests) may carry null lists; of() must not NPE.
        Settings s = new Settings();
        s.videoDir = "C:/clips";
        s.storageLocations = null;
        s.previousVideoDirs = null;
        s.previousArchiveDirs = null;

        assertThat(StorageRoots.of(s)).containsExactly("C:/clips");
    }

    @Test
    void isUnder_acceptsAFileUnderAHistoricalArchiveRoot() {
        Settings s = new Settings();
        s.videoDir = "C:/clips";
        s.previousArchiveDirs = List.of("F:/old-archive");

        assertThat(StorageRoots.isUnder(Path.of("F:/old-archive/match-42.mp4"), StorageRoots.of(s)))
                .isTrue();
        // A sibling with the root as a string prefix is still outside it.
        assertThat(StorageRoots.isUnder(Path.of("F:/old-archive2/match-42.mp4"), StorageRoots.of(s)))
                .isFalse();
    }
}

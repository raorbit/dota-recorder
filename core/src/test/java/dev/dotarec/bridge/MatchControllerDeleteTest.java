package dev.dotarec.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dotarec.config.AppPaths;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.data.ClipRepository;
import dev.dotarec.data.MarkerRepository;
import dev.dotarec.data.MatchRepository;
import dev.dotarec.data.MatchRepository.NewMatch;
import dev.dotarec.data.MatchSummary;
import dev.dotarec.data.PauseRepository;
import dev.dotarec.data.TestDb;
import dev.dotarec.retention.StorageMaintenanceLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code DELETE /matches/{id}} — deletes the RECORDING only, never its clips (those have their own
 * delete). Clipless: the row goes (markers + pauses cascade via the FK) and the {@code .mp4} +
 * thumbnail are unlinked. With clips: the files are unlinked but the row survives as a videoless stub
 * (the clips cascade with it, so it must stay as their parent). File unlinks are best-effort: a
 * missing file must not block the delete.
 */
class MatchControllerDeleteTest {

    @TempDir Path dir;

    private DataSource ds;
    private MatchRepository repo;
    private MarkerRepository markers;
    private ClipRepository clips;
    private SettingsStore settings;
    private MatchController controller;

    @BeforeEach
    void setUp() throws Exception {
        ds = TestDb.migrated(dir);
        repo = new MatchRepository(ds);
        markers = new MarkerRepository(ds);
        clips = new ClipRepository(ds);
        settings = new SettingsStore(
                new AppPaths(dir.resolve("data").toString(), dir.resolve("obs").toString()));
        settings.get().videoDir = dir.toString();
        controller = new MatchController(repo, markers, new PauseRepository(ds), clips, settings);
    }

    @Test
    void delete_removesRow_files_andCascadesMarkers() throws Exception {
        Path vod = writeFile("match.mp4", new byte[] {1, 2, 3});
        Path thumb = writeFile("match.jpg", new byte[] {4, 5});
        long id = insert(vod.toString(), thumb.toString());
        markers.insert(id, "death", 12.5, null, "died", "gsi");
        assertThat(markers.findByMatchId(id)).hasSize(1);

        MatchController.DeleteOutcome outcome = controller.delete(id);

        // The response tells the renderer which branch was taken so its local mirror never guesses.
        assertThat(outcome.outcome()).isEqualTo("deleted");
        assertThat(repo.findById(id)).isEmpty();
        assertThat(Files.exists(vod)).isFalse();
        assertThat(Files.exists(thumb)).isFalse();
        // Markers cascade away with the parent row (ON DELETE CASCADE + foreign_keys=ON).
        assertThat(markers.findByMatchId(id)).isEmpty();
    }

    @Test
    void delete_withClips_removesVideoOnly_keepsRowMarkersAndClips() throws Exception {
        Path vod = writeFile("match.mp4", new byte[] {1, 2, 3});
        Path thumb = writeFile("match.jpg", new byte[] {4, 5});
        long id = insert(vod.toString(), thumb.toString());
        markers.insert(id, "death", 12.5, null, "died", "gsi");
        Path clipVod = writeFile("clip.mp4", new byte[] {7, 8, 9});
        Path clipThumb = writeFile("clip.jpg", new byte[] {6});
        clips.insert(id, "manual", null, 10.0, 20.0, "carve",
                clipVod.toString(), clipThumb.toString(), 3L, "ready", null, 1L);

        MatchController.DeleteOutcome outcome = controller.delete(id);

        assertThat(outcome.outcome()).isEqualTo("stubbed");
        // The recording itself is gone from disk...
        assertThat(Files.exists(vod)).isFalse();
        assertThat(Files.exists(thumb)).isFalse();
        // ...but the row survives with nulled paths (the retention-sweep end state), markers intact...
        assertThat(repo.findById(id)).hasValueSatisfying(m -> {
            assertThat(m.videoPath()).isNull();
            assertThat(m.thumbPath()).isNull();
            assertThat(m.fileSizeBytes()).isNull();
        });
        assertThat(markers.findByMatchId(id)).hasSize(1);
        // ...and the clips keep their rows AND their files (a clip is deleted only via its own
        // DELETE /clips/{id}).
        assertThat(clips.findByParentMatchId(id)).hasSize(1);
        assertThat(Files.exists(clipVod)).isTrue();
        assertThat(Files.exists(clipThumb)).isTrue();
    }

    @Test
    void delete_ofClipsStub_removesRow_onceClipsAreGone() throws Exception {
        // The two-step teardown: a with-clips delete leaves a videoless stub; after the clips are
        // deleted separately, deleting the stub again drops the row entirely.
        Path vod = writeFile("match.mp4", new byte[] {1, 2, 3});
        long id = insert(vod.toString(), null);
        long clipId = clips.insert(id, "manual", null, 10.0, 20.0, "carve",
                null, null, null, "ready", null, 1L);

        assertThat(controller.delete(id).outcome()).isEqualTo("stubbed");
        assertThat(repo.findById(id)).isPresent();

        clips.delete(clipId);
        assertThat(controller.delete(id).outcome()).isEqualTo("deleted");
        assertThat(repo.findById(id)).isEmpty();
    }

    @Test
    void delete_unknownId_throws404() {
        assertThatThrownBy(() -> controller.delete(999_999L))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void delete_withMissingOrNullFiles_stillRemovesRow() {
        long noFiles = insert(null, null);
        controller.delete(noFiles);
        assertThat(repo.findById(noFiles)).isEmpty();

        long ghostFile = insert(dir.resolve("gone.mp4").toString(), null);
        controller.delete(ghostFile);
        assertThat(repo.findById(ghostFile)).isEmpty();
    }

    @Test
    void delete_reReadsCurrentPathsUnderLock_unlinksRepointedFile() throws Exception {
        // Simulate the archiver relocating the VOD (repointing video_path/thumb_path to an archive
        // drive) in the window between the delete handler's existence probe and its unlink. The delete
        // must unlink the CURRENT (repointed) files, not the pre-move ones — otherwise the archived
        // copy is stranded (the CrashRecoveryRunner re-import bug this fix closes).
        Path staleVod = writeFile("stale.mp4", new byte[] {1, 2, 3});
        Path staleThumb = writeFile("stale.jpg", new byte[] {4});
        Path movedVod = writeFile("moved.mp4", new byte[] {5, 6, 7});
        Path movedThumb = writeFile("moved.jpg", new byte[] {8});
        long id = insert(staleVod.toString(), staleThumb.toString());

        // A repository that repoints the row (as the archiver would) on the first findById AFTER the
        // handler's existence probe — i.e. the re-read inside the lock sees the moved locations.
        AtomicBoolean repointed = new AtomicBoolean(false);
        MatchRepository repointing = new MatchRepository(ds) {
            @Override
            public Optional<MatchSummary> findById(long queryId) {
                if (queryId == id && repointed.compareAndSet(false, true)) {
                    // First probe returns the pre-move row, then flips paths to the archive drive so the
                    // handler's re-read under the lock observes the repointed locations.
                    updateVideoPath(id, movedVod.toString(), movedThumb.toString());
                    return super.findById(queryId).map(m -> new MatchSummary(
                            m.id(), m.dotaMatchId(), m.recordKind(), m.enrichmentState(), m.hero(),
                            m.kills(), m.deaths(), m.assists(), m.gpm(), m.xpm(), m.netWorth(),
                            m.lastHits(), m.result(), m.lobbyType(), m.gameMode(), m.rankTier(),
                            m.mmrDelta(), m.durationS(), m.playedAt(), staleVod.toString(),
                            staleThumb.toString(), m.fileSizeBytes(), m.starred(), m.createdAt(),
                            m.recordStartedWallMs()));
                }
                return super.findById(queryId);
            }
        };
        MatchController c = new MatchController(repointing, markers, new PauseRepository(ds), clips,
                settings, new StorageMaintenanceLock());

        c.delete(id);

        assertThat(repo.findById(id)).isEmpty();
        // The CURRENT (repointed) files are unlinked...
        assertThat(Files.exists(movedVod)).isFalse();
        assertThat(Files.exists(movedThumb)).isFalse();
        // ...and the pre-move (stale) source is left untouched (the archiver already consumed it in a
        // real move; here it proves the handler unlinked the re-read paths, not the probe's paths).
        assertThat(Files.exists(staleVod)).isTrue();
        assertThat(Files.exists(staleThumb)).isTrue();
    }

    @Test
    void delete_releasesLock_soASubsequentPassCanAcquire() throws Exception {
        // The handler must release the maintenance lock in a finally; a shared lock the archiver/sweeper
        // also use would deadlock the next pass if the delete leaked a hold. Probe from ANOTHER thread
        // so reentrancy can't mask a leaked hold (a same-thread re-acquire always succeeds).
        StorageMaintenanceLock lock = new StorageMaintenanceLock();
        MatchController c = new MatchController(repo, markers, new PauseRepository(ds), clips,
                settings, lock);
        long id = insert(dir.resolve("gone.mp4").toString(), null);

        c.delete(id);

        assertThat(repo.findById(id)).isEmpty();
        // A different thread can acquire the lock only if the delete released every hold it took.
        AtomicBoolean acquired = new AtomicBoolean(false);
        Thread t = new Thread(() -> {
            lock.lock();
            try {
                acquired.set(true);
            } finally {
                lock.unlock();
            }
        });
        t.start();
        t.join(2_000);
        assertThat(t.isAlive()).as("delete must not leak a maintenance-lock hold").isFalse();
        assertThat(acquired).isTrue();
    }

    @Test
    void delete_skipsFilesOutsideStorageRoots_butStillRemovesRow() throws Exception {
        // A real, readable file OUTSIDE videoDir (a tampered DB row / .. escape): the delete must NOT
        // unlink it — the containment guard skips it, matching the read-side guard — yet the row goes.
        Path outside = Files.createTempFile("match-outside-", ".mp4");
        Files.write(outside, new byte[] {1, 2, 3});
        try {
            long id = insert(outside.toString(), null);

            controller.delete(id);

            assertThat(repo.findById(id)).isEmpty();
            assertThat(Files.exists(outside)).isTrue();
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Path writeFile(String name, byte[] data) throws Exception {
        Path p = dir.resolve(name);
        Files.write(p, data);
        return p;
    }

    private long insert(String videoPath, String thumbPath) {
        return repo.insert(
                new NewMatch(
                        null, "match", "pending", "rubick",
                        null, null, null, null, null, null, null, null, null, null, null, null,
                        null, null, videoPath, thumbPath, null, false, 1L, null));
    }
}

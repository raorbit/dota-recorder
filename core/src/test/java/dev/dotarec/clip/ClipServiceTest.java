package dev.dotarec.clip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.dotarec.bridge.EventPublisher;
import dev.dotarec.config.AppPaths;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.data.ClipRepository;
import dev.dotarec.data.ClipRow;
import dev.dotarec.data.MatchRepository;
import dev.dotarec.data.MatchRepository.NewMatch;
import dev.dotarec.data.TestDb;
import dev.dotarec.retention.StorageMaintenanceLock;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives {@link ClipService} against a real SQLite DB ({@link TestDb}) + real {@link ClipRepository}
 * / {@link MatchRepository} / {@link StorageMaintenanceLock}, with a mocked {@link Clipper} (so no
 * ffmpeg runs) and {@link EventPublisher}. Because no Spring proxy is in play, the {@code @Async}
 * dispatch runs synchronously through the {@code self} reference, so {@code createManual} drives
 * generation inline and the row reaches its terminal state within the test.
 *
 * <p>Covers create-time validation (missing parent, no video, degenerate/non-finite range, over-long
 * duration/label), the happy path (pending insert -> {@code clip.created} -> generation), the
 * claim-once invariant (a second dispatch is a no-op), and {@code generateAsync}'s cleanup of an
 * orphaned/partial output (row deleted mid-generation, clipper throws) and its parent-VOD-gone fail.
 * The mock clipper writes a real file for the success cases so size/cleanup assertions are meaningful.
 */
class ClipServiceTest {

    private DataSource ds;
    private ClipRepository clips;
    private MatchRepository matches;
    private Clipper clipper;
    private EventPublisher events;
    private SettingsStore settings;
    private AppPaths paths;
    private StorageMaintenanceLock maintenanceLock;
    private ClipService service;
    private Path videoDir;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        ds = TestDb.migrated(dir);
        clips = new ClipRepository(ds);
        matches = new MatchRepository(ds);
        clipper = mock(Clipper.class);
        events = mock(EventPublisher.class);
        paths = new AppPaths(dir.resolve("data").toString(), dir.resolve("obs").toString());
        settings = new SettingsStore(paths);
        videoDir = Files.createDirectories(dir.resolve("video"));
        settings.get().videoDir = videoDir.toString();
        maintenanceLock = new StorageMaintenanceLock();

        service = new ClipService(clips, matches, clipper, events, settings, paths, maintenanceLock,
                null);
        // No Spring proxy in a unit test, so wire the (final) lazy self-reference to this instance:
        // create()'s self.generateAsync(...) then dispatches synchronously through the real method.
        setSelf(service, service);
    }

    // ---- create-time validation -------------------------------------------------------------

    @Test
    void createManual_missingParentMatch_throws() {
        assertThatThrownBy(() -> service.createManual(999L, 0.0, 10.0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no match 999");
        verify(clipper, never()).clip(any(), anyDouble(), anyDouble(), any());
    }

    @Test
    void createManual_parentWithNoVideo_throws() {
        long parent = seedMatch(null, 1800);

        assertThatThrownBy(() -> service.createManual(parent, 0.0, 10.0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has no recorded video");
        verify(clipper, never()).clip(any(), anyDouble(), anyDouble(), any());
    }

    @Test
    void createManual_degenerateRange_throws() throws Exception {
        long parent = seedMatchWithVideo(1800);

        // start >= end collapses to an empty clamped range.
        assertThatThrownBy(() -> service.createManual(parent, 30.0, 30.0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty range");
        verify(clipper, never()).clip(any(), anyDouble(), anyDouble(), any());
    }

    @Test
    void createManual_nonFiniteOffset_throws() throws Exception {
        long parent = seedMatchWithVideo(1800);

        assertThatThrownBy(() -> service.createManual(parent, Double.NaN, 10.0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-finite range");
        assertThatThrownBy(() -> service.createManual(parent, 0.0, Double.POSITIVE_INFINITY, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-finite range");
        verify(clipper, never()).clip(any(), anyDouble(), anyDouble(), any());
    }

    @Test
    void createManual_overLongDuration_throws() throws Exception {
        // Parent with no duration so the upper bound stays open; a >4h range trips the length cap.
        long parent = seedMatchWithVideo(null);

        assertThatThrownBy(() -> service.createManual(parent, 0.0, 5 * 60 * 60, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds max");
        verify(clipper, never()).clip(any(), anyDouble(), anyDouble(), any());
    }

    @Test
    void createManual_overLongLabel_throws() throws Exception {
        long parent = seedMatchWithVideo(1800);
        String label = "x".repeat(201);

        assertThatThrownBy(() -> service.createManual(parent, 0.0, 10.0, label))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("label exceeds");
        verify(clipper, never()).clip(any(), anyDouble(), anyDouble(), any());
    }

    // ---- happy path -------------------------------------------------------------------------

    @Test
    void createManual_happyPath_insertsPendingPublishesCreatedAndGenerates() throws Exception {
        long parent = seedMatchWithVideo(1800);
        // The mock clipper writes a real output + thumb so the ready row carries a real size.
        stubClipperToWriteRealFiles(64L);

        long clipId = service.createManual(parent, 30.0, 45.0, "nice play");

        // clip.created published with the freshly-inserted pending row.
        verify(events).publish(eq("clip.created"), any(ClipRow.class));
        // Generation ran exactly once and the row reached ready with the rendered size.
        verify(clipper, times(1)).clip(any(), eq(30.0), eq(15.0), any());
        ClipRow row = clips.findById(clipId).orElseThrow();
        assertThat(row.status()).isEqualTo("ready");
        assertThat(row.fileSizeBytes()).isEqualTo(64L);
        assertThat(row.videoPath()).isNotNull();
        assertThat(Path.of(row.videoPath())).exists();
        verify(events).publish(eq("clip.ready"), any());
    }

    // ---- generateAsync invariants -----------------------------------------------------------

    @Test
    void generateAsync_secondDispatchIsNoOp_clipGeneratedExactlyOnce() throws Exception {
        long parent = seedMatchWithVideo(1800);
        stubClipperToWriteRealFiles(64L);

        // First create both inserts AND generates (synchronous self-dispatch), claiming the row.
        long clipId = service.createManual(parent, 30.0, 45.0, null);
        assertThat(clips.findById(clipId).orElseThrow().status()).isEqualTo("ready");

        // A second dispatch must not re-cut: claimForGeneration only wins on a pending row.
        service.generateAsync(clipId);

        verify(clipper, times(1)).clip(any(), anyDouble(), anyDouble(), any());
    }

    @Test
    void generateAsync_rowDeletedMidGeneration_cleansUpOrphanedOutput() throws Exception {
        long parent = seedMatchWithVideo(1800);
        // Insert a pending row directly so we can delete it while the cut "runs".
        long clipId = clips.insert(parent, "manual", null, 30.0, 45.0, null,
                null, null, null, "pending", null, System.currentTimeMillis());

        // The mock clipper writes the real output, then deletes the row so updateStatus matches 0 rows
        // and generateAsync must unlink the orphaned output it just wrote.
        Path[] written = new Path[1];
        when(clipper.clip(any(), anyDouble(), anyDouble(), any())).thenAnswer(inv -> {
            Path out = inv.getArgument(3);
            Files.createDirectories(out.getParent());
            Files.write(out, new byte[32]);
            written[0] = out;
            clips.delete(clipId);
            return new Clipper.Result(out, 32L);
        });

        service.generateAsync(clipId);

        assertThat(written[0]).isNotNull();
        assertThat(written[0]).doesNotExist();
        assertThat(clips.findById(clipId)).isEmpty();
    }

    @Test
    void generateAsync_clipperThrows_deletesPartialOutputAndMarksFailed() throws Exception {
        long parent = seedMatchWithVideo(1800);
        long clipId = clips.insert(parent, "manual", null, 30.0, 45.0, null,
                null, null, null, "pending", null, System.currentTimeMillis());

        // The cut writes a partial file then throws: generateAsync's catch must unlink it + fail the row.
        Path[] partial = new Path[1];
        when(clipper.clip(any(), anyDouble(), anyDouble(), any())).thenAnswer(inv -> {
            Path out = inv.getArgument(3);
            Files.createDirectories(out.getParent());
            Files.write(out, new byte[16]);
            partial[0] = out;
            throw new IllegalStateException("ffmpeg blew up");
        });

        service.generateAsync(clipId);

        assertThat(partial[0]).isNotNull();
        assertThat(partial[0]).doesNotExist();
        ClipRow row = clips.findById(clipId).orElseThrow();
        assertThat(row.status()).isEqualTo("failed");
        assertThat(row.videoPath()).isNull();
        assertThat(row.error()).contains("ffmpeg blew up");
    }

    @Test
    void generateAsync_thumbnailThrows_keepsClipReadyAndDeletesPartialThumb() throws Exception {
        long parent = seedMatchWithVideo(1800);
        // The cut succeeds, but the thumbnail writes a partial .jpg then throws. A thumbnail failure
        // must NOT fail the clip (it stays ready with a null thumb_path) — but the partial file must be
        // cleaned up, not leaked on disk.
        when(clipper.clip(any(), anyDouble(), anyDouble(), any())).thenAnswer(inv -> {
            Path out = inv.getArgument(3);
            Files.createDirectories(out.getParent());
            Files.write(out, new byte[64]);
            return new Clipper.Result(out, 64L);
        });
        Path[] partialThumb = new Path[1];
        when(clipper.thumbnail(any(), anyDouble(), any())).thenAnswer(inv -> {
            Path tOut = inv.getArgument(2);
            Files.createDirectories(tOut.getParent());
            Files.write(tOut, new byte[8]);
            partialThumb[0] = tOut;
            throw new IllegalStateException("thumbnail blew up");
        });

        long clipId = service.createManual(parent, 30.0, 45.0, null);

        ClipRow row = clips.findById(clipId).orElseThrow();
        assertThat(row.status()).isEqualTo("ready");
        assertThat(row.thumbPath()).isNull();
        assertThat(row.videoPath()).isNotNull();
        assertThat(partialThumb[0]).isNotNull();
        assertThat(partialThumb[0]).doesNotExist();
    }

    @Test
    void generateAsync_parentVodGone_marksFailedWithoutCutting() throws Exception {
        // Parent has a video_path that points nowhere on disk, so the under-lock re-read fails.
        long parent = matches.insert(new NewMatch(
                null, "match", "enriched", "puck",
                null, null, null, null, null, null, null,
                null, null, null, null, null, 1800,
                null, videoDir.resolve("gone.mp4").toString(), null, null, false,
                System.currentTimeMillis(), null));
        long clipId = clips.insert(parent, "manual", null, 30.0, 45.0, null,
                null, null, null, "pending", null, System.currentTimeMillis());

        service.generateAsync(clipId);

        verify(clipper, never()).clip(any(), anyDouble(), anyDouble(), any());
        ClipRow row = clips.findById(clipId).orElseThrow();
        assertThat(row.status()).isEqualTo("failed");
        assertThat(row.error()).contains("missing on disk");
    }

    // ---- helpers ----------------------------------------------------------------------------

    /** Makes the mock clipper write a real {@code sizeBytes} output + a real thumbnail file. */
    private void stubClipperToWriteRealFiles(long sizeBytes) {
        when(clipper.clip(any(), anyDouble(), anyDouble(), any())).thenAnswer(inv -> {
            Path out = inv.getArgument(3);
            Files.createDirectories(out.getParent());
            Files.write(out, new byte[(int) sizeBytes]);
            return new Clipper.Result(out, sizeBytes);
        });
        when(clipper.thumbnail(any(), anyDouble(), any())).thenAnswer(inv -> {
            Path out = inv.getArgument(2);
            Files.createDirectories(out.getParent());
            Files.write(out, new byte[8]);
            return out;
        });
    }

    /** Seeds a match with the given (nullable) duration and no video on disk; returns its id. */
    private long seedMatch(String videoPath, Integer durationS) {
        return matches.insert(new NewMatch(
                null, "match", "enriched", "puck",
                null, null, null, null, null, null, null,
                null, null, null, null, null, durationS,
                null, videoPath, null, null, false, System.currentTimeMillis(), null));
    }

    /** Seeds a match whose video_path points at a real on-disk file; returns its id. */
    private long seedMatchWithVideo(Integer durationS) throws Exception {
        Path video = videoDir.resolve("parent-" + System.nanoTime() + ".mp4");
        Files.write(video, new byte[256]);
        return seedMatch(video.toString(), durationS);
    }

    /** Sets the (final) lazy {@code self} reference reflectively — Spring would inject this proxy. */
    private static void setSelf(ClipService target, ClipService self) throws Exception {
        Field f = ClipService.class.getDeclaredField("self");
        f.setAccessible(true);
        f.set(target, self);
    }
}

package dev.dotarec.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.dotarec.clip.ClipService;
import dev.dotarec.config.AppPaths;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.data.ClipRepository;
import dev.dotarec.data.ClipRow;
import dev.dotarec.data.MatchRepository;
import dev.dotarec.data.MatchRepository.NewMatch;
import dev.dotarec.data.TestDb;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Endpoint behavior of {@link ClipController}, covering the bridge-hardening fixes: POST input
 * validation surfaced as 400 (via {@link ClipService}'s {@link IllegalArgumentException} -> 400
 * mapping), PATCH star/404 over a real repo, and the path-containment guard on the clip
 * video/thumb stream endpoints.
 *
 * <p>Uses real {@link ClipRepository}/{@link MatchRepository} over {@link TestDb} where state
 * matters; {@link ClipService} is mocked (the controller's only job on POST is to map its thrown
 * {@link IllegalArgumentException} to a 400 — the validation rules themselves are exercised in
 * {@code ClipService}'s own tests). The {@link SettingsStore}'s {@code videoDir} is pointed at the
 * {@code @TempDir} so files written there pass the containment guard.
 */
class ClipControllerTest {

    @TempDir Path dir;

    private ClipRepository clips;
    private MatchRepository matches;
    private ClipService clipService;
    private ClipController controller;
    private long matchId;

    @BeforeEach
    void setUp() throws Exception {
        DataSource ds = TestDb.migrated(dir);
        clips = new ClipRepository(ds);
        matches = new MatchRepository(ds);
        clipService = mock(ClipService.class);
        // The served clip files are written directly under the TempDir, so point the storage root
        // there: the controller's containment guard requires a streamed file to live under a root.
        SettingsStore settings = new SettingsStore(
                new AppPaths(dir.resolve("data").toString(), dir.resolve("obs").toString()));
        settings.get().videoDir = dir.toString();
        controller = new ClipController(clips, clipService, matches, settings);

        matchId = matches.insert(
                new NewMatch(
                        null, "match", "pending", "rubick",
                        null, null, null, null, null, null, null, null, null, null, null, null,
                        null, null, dir.resolve("match.mp4").toString(), null, null, false, 1L, null));
    }

    // ── POST validation -> 400 ──────────────────────────────────────────────

    @Test
    void post_nonFiniteRange_is400() {
        when(clipService.createManual(eq(matchId), anyDouble(), anyDouble(), isNull()))
                .thenThrow(new IllegalArgumentException(
                        "Cannot create clip: non-finite range [NaN, 5.0] for match " + matchId));

        assertThatThrownBy(() -> controller.create(matchId,
                new ClipController.NewClipRequest(Double.NaN, 5.0, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void post_emptyRange_is400() {
        when(clipService.createManual(eq(matchId), anyDouble(), anyDouble(), isNull()))
                .thenThrow(new IllegalArgumentException(
                        "Cannot create clip: empty range [10.0, 10.0] for match " + matchId));

        assertThatThrownBy(() -> controller.create(matchId,
                new ClipController.NewClipRequest(10.0, 10.0, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void post_overLongDuration_is400() {
        when(clipService.createManual(eq(matchId), anyDouble(), anyDouble(), isNull()))
                .thenThrow(new IllegalArgumentException(
                        "Cannot create clip: range 99999.0s exceeds max 14400.0s for match " + matchId));

        assertThatThrownBy(() -> controller.create(matchId,
                new ClipController.NewClipRequest(0.0, 99_999.0, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void post_overLongLabel_is400() {
        String label = "x".repeat(201);
        when(clipService.createManual(eq(matchId), anyDouble(), anyDouble(), eq(label)))
                .thenThrow(new IllegalArgumentException(
                        "Cannot create clip: label exceeds 200 chars (201) for match " + matchId));

        assertThatThrownBy(() -> controller.create(matchId,
                new ClipController.NewClipRequest(0.0, 5.0, label)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void post_unknownMatch_is404() {
        assertThatThrownBy(() -> controller.create(999_999L,
                new ClipController.NewClipRequest(0.0, 5.0, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void post_validRange_returns202WithPendingRow() {
        long clipId = insertClip("pending", null, null);
        when(clipService.createManual(eq(matchId), anyDouble(), anyDouble(), isNull()))
                .thenReturn(clipId);

        ResponseEntity<ClipRow> resp = controller.create(matchId,
                new ClipController.NewClipRequest(0.0, 5.0, null));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().id()).isEqualTo(clipId);
        assertThat(resp.getBody().status()).isEqualTo("pending");
    }

    // ── PATCH star / 404 ────────────────────────────────────────────────────

    @Test
    void patch_setsStarred_andReturnsUpdatedRow() {
        long clipId = insertClip("ready", dir.resolve("c.mp4").toString(), null);
        assertThat(clips.findById(clipId).orElseThrow().starred()).isFalse();

        ClipRow updated = controller.patch(clipId, new ClipController.ClipPatch(true));

        assertThat(updated.id()).isEqualTo(clipId);
        assertThat(updated.starred()).isTrue();
        // Persisted, not just echoed back.
        assertThat(clips.findById(clipId).orElseThrow().starred()).isTrue();
    }

    @Test
    void patch_unknownId_is404() {
        assertThatThrownBy(() -> controller.patch(999_999L, new ClipController.ClipPatch(true)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ── path containment on the stream endpoints ────────────────────────────

    @Test
    void videoStream_underStorageRoot_doesNotRejectForContainment() throws Exception {
        Path file = dir.resolve("inside.mp4");
        Files.write(file, new byte[] {1, 2, 3});
        long clipId = insertClip("ready", file.toString(), null);

        ResponseEntity<StreamingResponseBody> resp =
                controller.videoStream(clipId, new HttpHeaders());

        // A file under videoDir passes the guard and streams (200 with no Range).
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void videoStream_outsideEveryRoot_is404ForContainment() throws Exception {
        // A real, readable file that exists OUTSIDE videoDir/storageLocations: the file passes the
        // existence check, so a 404 here is specifically the containment guard, not a missing file.
        Path outside = Files.createTempFile("clip-outside-", ".mp4");
        Files.write(outside, new byte[] {1, 2, 3});
        try {
            long clipId = insertClip("ready", outside.toString(), null);

            assertThatThrownBy(() -> controller.videoStream(clipId, new HttpHeaders()))
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(e.getReason()).contains("outside the configured storage roots");
                    });
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void thumbStream_outsideEveryRoot_is404ForContainment() throws Exception {
        Path outside = Files.createTempFile("clip-thumb-outside-", ".jpg");
        Files.write(outside, new byte[] {1, 2, 3});
        try {
            long clipId = insertClip("ready", dir.resolve("c.mp4").toString(), outside.toString());

            assertThatThrownBy(() -> controller.thumb(clipId, new HttpHeaders()))
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(e.getReason()).contains("outside the configured storage roots");
                    });
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void videoStream_unknownClip_is404() {
        assertThatThrownBy(() -> controller.videoStream(999_999L, new HttpHeaders()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private long insertClip(String status, String videoPath, String thumbPath) {
        return clips.insert(matchId, "manual", null, 0.0, 5.0, null,
                videoPath, thumbPath, videoPath == null ? null : 3L, status, null,
                System.currentTimeMillis());
    }
}

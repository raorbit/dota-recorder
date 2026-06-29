package dev.dotarec.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dotarec.config.AppPaths;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.data.ClipRepository;
import dev.dotarec.data.MarkerRepository;
import dev.dotarec.data.MatchRepository;
import dev.dotarec.data.MatchRepository.NewMatch;
import dev.dotarec.data.PauseRepository;
import dev.dotarec.data.TestDb;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * HTTP Range behavior of {@code GET /matches/{id}/video/stream} — the byte-exact 200/206/416/404
 * contract the renderer's {@code <video>} relies on for seek. The handler returns a
 * {@link StreamingResponseBody}, so we drive {@code writeTo} to capture the served bytes.
 */
class MatchControllerStreamTest {

    @TempDir Path dir;

    private MatchRepository repo;
    private MatchController controller;

    @BeforeEach
    void setUp() throws Exception {
        DataSource ds = TestDb.migrated(dir);
        repo = new MatchRepository(ds);
        // The served VODs are written directly under the TempDir, so point the storage root there:
        // the controller's containment guard requires a streamed file to live under a configured root.
        SettingsStore settings = new SettingsStore(
                new AppPaths(dir.resolve("data").toString(), dir.resolve("obs").toString()));
        settings.get().videoDir = dir.toString();
        controller = new MatchController(repo, new MarkerRepository(ds), new PauseRepository(ds),
                new ClipRepository(ds), settings);
    }

    @Test
    void noRange_servesFullBodyWith200AndAcceptRanges() throws Exception {
        byte[] data = bytes(1000);
        long id = insertWithVideo(writeVod("a.mp4", data));

        ResponseEntity<StreamingResponseBody> resp = controller.videoStream(id, new HttpHeaders());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("video/mp4"));
        assertThat(resp.getHeaders().getContentLength()).isEqualTo(1000);
        assertThat(readBody(resp)).isEqualTo(data);
    }

    @Test
    void boundedRange_is206WithExactSlice() throws Exception {
        byte[] data = bytes(1000);
        long id = insertWithVideo(writeVod("b.mp4", data));

        HttpHeaders h = new HttpHeaders();
        h.setRange(List.of(HttpRange.createByteRange(100, 199)));
        ResponseEntity<StreamingResponseBody> resp = controller.videoStream(id, h);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE))
                .isEqualTo("bytes 100-199/1000");
        assertThat(resp.getHeaders().getContentLength()).isEqualTo(100);
        assertThat(readBody(resp)).isEqualTo(Arrays.copyOfRange(data, 100, 200));
    }

    @Test
    void openEndedRange_streamsToEof_andMapsMkvContentType() throws Exception {
        byte[] data = bytes(500);
        long id = insertWithVideo(writeVod("c.mkv", data));

        HttpHeaders h = new HttpHeaders();
        h.setRange(List.of(HttpRange.createByteRange(200)));
        ResponseEntity<StreamingResponseBody> resp = controller.videoStream(id, h);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 200-499/500");
        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("video/x-matroska"));
        assertThat(readBody(resp)).isEqualTo(Arrays.copyOfRange(data, 200, 500));
    }

    @Test
    void unsatisfiableRange_is416WithSize() throws Exception {
        long id = insertWithVideo(writeVod("d.mp4", bytes(100)));

        HttpHeaders h = new HttpHeaders();
        h.setRange(List.of(HttpRange.createByteRange(500)));
        ResponseEntity<StreamingResponseBody> resp = controller.videoStream(id, h);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */100");
    }

    @Test
    void notFound_forUnknownId_nullPath_orMissingFile() throws Exception {
        assertThatThrownBy(() -> controller.videoStream(999_999L, new HttpHeaders()))
                .isInstanceOf(ResponseStatusException.class);

        long noVid = insertWithVideo(null);
        assertThatThrownBy(() -> controller.videoStream(noVid, new HttpHeaders()))
                .isInstanceOf(ResponseStatusException.class);

        long gone = insertWithVideo(dir.resolve("missing.mp4").toString());
        assertThatThrownBy(() -> controller.videoStream(gone, new HttpHeaders()))
                .isInstanceOf(ResponseStatusException.class);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String writeVod(String name, byte[] data) throws Exception {
        Path p = dir.resolve(name);
        Files.write(p, data);
        return p.toString();
    }

    private long insertWithVideo(String videoPath) {
        return repo.insert(
                new NewMatch(
                        null, "match", "pending", "rubick",
                        null, null, null, null, null, null, null, null, null, null, null, null,
                        null, null, videoPath, null, null, false, 1L, null));
    }

    private static byte[] readBody(ResponseEntity<StreamingResponseBody> resp) throws Exception {
        StreamingResponseBody body = resp.getBody();
        if (body == null) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        body.writeTo(out);
        return out.toByteArray();
    }

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i % 251);
        }
        return b;
    }
}

package dev.dotarec.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Per-response windowing in {@link VideoStreamSupport}. A {@code <video>} seek sends an open-ended
 * {@code Range: bytes=N-}; serving that to EOF would stream a VOD's whole multi-GB remainder in one
 * stalling response and tie up connections until they time out (the playback-freeze bug). The stream
 * clamps each 206 to one window; the windowed overload lets us assert that with a tiny cap instead of
 * a multi-megabyte fixture.
 */
class VideoStreamSupportTest {

    @TempDir Path dir;

    @Test
    void openEndedRange_isCappedToOneWindow() throws Exception {
        byte[] data = bytes(1000);
        Path file = writeFile("vod.mp4", data);

        // Open-ended seek from offset 100 with a 64-byte window: the response must be exactly the
        // first window (100..163), NOT the whole remainder to EOF (100..999).
        HttpHeaders h = new HttpHeaders();
        h.setRange(List.of(HttpRange.createByteRange(100)));
        ResponseEntity<StreamingResponseBody> resp = VideoStreamSupport.stream(file, h, 64);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 100-163/1000");
        assertThat(resp.getHeaders().getContentLength()).isEqualTo(64);
        assertThat(readBody(resp)).isEqualTo(Arrays.copyOfRange(data, 100, 164));
    }

    @Test
    void nextWindow_continuesFromWhereTheLastLeftOff() throws Exception {
        byte[] data = bytes(1000);
        Path file = writeFile("vod.mp4", data);

        // The browser's follow-up request after consuming the first window resumes at 164.
        HttpHeaders h = new HttpHeaders();
        h.setRange(List.of(HttpRange.createByteRange(164)));
        ResponseEntity<StreamingResponseBody> resp = VideoStreamSupport.stream(file, h, 64);

        assertThat(resp.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 164-227/1000");
        assertThat(readBody(resp)).isEqualTo(Arrays.copyOfRange(data, 164, 228));
    }

    @Test
    void boundedRangeWithinWindow_isServedExactly() throws Exception {
        byte[] data = bytes(1000);
        Path file = writeFile("vod.mp4", data);

        // An explicit range smaller than the window is honored exactly (the clamp is a no-op), so a
        // deliberate small fetch isn't padded out to a full window.
        HttpHeaders h = new HttpHeaders();
        h.setRange(List.of(HttpRange.createByteRange(100, 149)));
        ResponseEntity<StreamingResponseBody> resp = VideoStreamSupport.stream(file, h, 64);

        assertThat(resp.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 100-149/1000");
        assertThat(resp.getHeaders().getContentLength()).isEqualTo(50);
        assertThat(readBody(resp)).isEqualTo(Arrays.copyOfRange(data, 100, 150));
    }

    @Test
    void windowClampedToEof_forAShortTail() throws Exception {
        byte[] data = bytes(1000);
        Path file = writeFile("vod.mp4", data);

        // A window that would run past EOF is still clamped to length-1 (no over-read).
        HttpHeaders h = new HttpHeaders();
        h.setRange(List.of(HttpRange.createByteRange(980)));
        ResponseEntity<StreamingResponseBody> resp = VideoStreamSupport.stream(file, h, 64);

        assertThat(resp.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 980-999/1000");
        assertThat(resp.getHeaders().getContentLength()).isEqualTo(20);
        assertThat(readBody(resp)).isEqualTo(Arrays.copyOfRange(data, 980, 1000));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Path writeFile(String name, byte[] data) throws Exception {
        Path p = dir.resolve(name);
        Files.write(p, data);
        return p;
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

package dev.dotarec.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
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
 * a multi-megabyte fixture. The copy's failure handling is pinned too: a client that drops the request
 * ends it with one DEBUG line, while any other write failure or a file read failure still propagates.
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

    @Test
    void clientAbortMidStream_endsQuietlyWithOneDebugLine() throws Exception {
        Path file = writeFile("vod.mp4", bytes(200_000));
        ResponseEntity<StreamingResponseBody> resp =
                VideoStreamSupport.stream(file, new HttpHeaders(), 1 << 20);
        // The first 64KB chunk goes out, then the <video> drops the request: the shape Tomcat throws.
        OutputStream out = failingAfter(65_536, new ClientAbortException(
                new IOException("An established connection was aborted by the software in your host machine")));

        List<ILoggingEvent> events = captureLogs(() -> resp.getBody().writeTo(out));

        assertThat(events).hasSize(1);
        ILoggingEvent event = events.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(event.getFormattedMessage())
                .contains("after 65536 of 200000 bytes")
                .contains("An established connection was aborted");
    }

    @Test
    void otherWriteFailure_propagates() throws Exception {
        Path file = writeFile("vod.mp4", bytes(1000));
        ResponseEntity<StreamingResponseBody> resp = VideoStreamSupport.stream(file, new HttpHeaders());
        IOException failure = new IOException("There is not enough space on the disk");

        assertThatThrownBy(() -> resp.getBody().writeTo(failingAfter(0, failure))).isSameAs(failure);
    }

    @Test
    void fileReadFailure_propagates() throws Exception {
        Path file = writeFile("vod.mp4", bytes(1000));
        ResponseEntity<StreamingResponseBody> resp = VideoStreamSupport.stream(file, new HttpHeaders());
        // Retention sweeps the file between the response being built and the body being written.
        Files.delete(file);

        assertThatThrownBy(() -> resp.getBody().writeTo(new ByteArrayOutputStream()))
                .isInstanceOf(NoSuchFileException.class);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** An output stream that accepts {@code okBytes}, then throws {@code failure} on the next write. */
    private static OutputStream failingAfter(int okBytes, IOException failure) {
        return new OutputStream() {
            private int written;

            @Override
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if (written + len > okBytes) {
                    throw failure;
                }
                written += len;
            }
        };
    }

    /** Runs {@code body} with VideoStreamSupport logging at DEBUG and returns what it logged. */
    private static List<ILoggingEvent> captureLogs(ThrowingRunnable body) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(VideoStreamSupport.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            body.run();
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(appender);
            appender.stop();
        }
        return appender.list;
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

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

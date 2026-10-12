package dev.dotarec.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * {@link ClientAbort} decides which stream failures are a client walking away (DEBUG, no stack
 * trace) versus a real I/O error that must still surface. The messages are the exact strings the
 * JDK produces, including the Windows ones Spring's own helper misses.
 */
class ClientAbortTest {

    private static final String WSAECONNABORTED =
            "An established connection was aborted by the software in your host machine";

    @ParameterizedTest
    @ValueSource(strings = {
        WSAECONNABORTED,
        "An existing connection was forcibly closed by the remote host",
        "Connection reset by peer",
        "Connection reset",
        "Broken pipe",
        "Software caused connection abort",
    })
    void closedSocketMessages_areClientAborts(String message) {
        assertThat(ClientAbort.isClientAbort(new IOException(message))).isTrue();
    }

    @Test
    void tomcatClientAbort_isAClientAbort_whateverTheMessage() {
        // Tomcat wraps every failed response write in ClientAbortException, so the type alone counts.
        assertThat(ClientAbort.isClientAbort(new ClientAbortException())).isTrue();
        assertThat(ClientAbort.isClientAbort(new ClientAbortException(new IOException("unfamiliar"))))
                .isTrue();
    }

    @Test
    void springUnusableResponse_isAClientAbort() {
        assertThat(ClientAbort.isClientAbort(
                new AsyncRequestNotUsableException("Response not usable after response errors.")))
                .isTrue();
    }

    @Test
    void productionWriteFailureChain_isAClientAbort() {
        // The shape a failed write takes on the streaming path: Spring's async wrapper around Tomcat's
        // ClientAbortException around the socket's IOException.
        IOException socket = new IOException(WSAECONNABORTED);
        Throwable chain = new AsyncRequestNotUsableException(
                "ServletOutputStream failed to write: " + WSAECONNABORTED, new ClientAbortException(socket));

        assertThat(ClientAbort.isClientAbort(chain)).isTrue();
        assertThat(ClientAbort.describe(chain)).isEqualTo("IOException: " + WSAECONNABORTED);
    }

    @Test
    void abortBuriedInACauseChain_isFound() {
        assertThat(ClientAbort.isClientAbort(
                new UncheckedIOException(new IOException(WSAECONNABORTED)))).isTrue();
    }

    @Test
    void fileReadFailures_areNotClientAborts() {
        assertThat(ClientAbort.isClientAbort(new NoSuchFileException("C:\\vods\\match.mp4"))).isFalse();
        assertThat(ClientAbort.isClientAbort(new AccessDeniedException("C:\\vods\\match.mp4"))).isFalse();
        assertThat(ClientAbort.isClientAbort(new IOException(
                "The process cannot access the file because it is being used by another process")))
                .isFalse();
        // A storage root on a network share that drops mid-read is a file error, not a client abort.
        assertThat(ClientAbort.isClientAbort(
                new IOException("The specified network name is no longer available"))).isFalse();
        assertThat(ClientAbort.isClientAbort(new IOException())).isFalse();
    }

    @Test
    void closedSocketWording_onANonIoException_doesNotCount() {
        assertThat(ClientAbort.isClientAbort(new IllegalStateException("connection reset"))).isFalse();
    }

    @Test
    void cyclicCauseChain_terminates() {
        IOException a = new IOException("a");
        IOException b = new IOException("b", a);
        a.initCause(b);

        assertThat(ClientAbort.isClientAbort(a)).isFalse();
        assertThat(ClientAbort.describe(a)).startsWith("IOException: ");
    }
}

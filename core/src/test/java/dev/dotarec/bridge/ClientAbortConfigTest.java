package dev.dotarec.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;

/**
 * The container-error half of the client-abort handling. When a streaming write fails, Tomcat
 * reports the raw socket {@link IOException} through the async error callback; Spring wraps it as a
 * quiet {@link AsyncRequestNotUsableException} only for the POSIX wording, so the interceptor must do
 * it for the Windows abort, and must leave every other error to Spring's default handling.
 */
class ClientAbortConfigTest {

    private static final String WSAECONNABORTED =
            "An established connection was aborted by the software in your host machine";

    private final ClientAbortConfig.Interceptor interceptor = new ClientAbortConfig.Interceptor();
    private final Callable<Object> task = () -> null;

    private Logger logger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(ClientAbortConfig.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.setLevel(previousLevel);
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void windowsAbort_isRewrappedAsAnUnusableResponse() {
        IOException abort = new IOException(WSAECONNABORTED);

        Object result = interceptor.handleError(streamRequest(), task, abort);

        assertThat(result).isInstanceOf(AsyncRequestNotUsableException.class);
        assertThat(((Throwable) result).getCause()).isSameAs(abort);
    }

    @Test
    void windowsAbort_logsOneDebugLine_withoutStackTraceOrToken() {
        interceptor.handleError(streamRequest(), task, new IOException(WSAECONNABORTED));

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(event.getFormattedMessage())
                .contains("/matches/7/video/stream")
                .contains(WSAECONNABORTED)
                // The <video> authenticates with ?token=; the query string must never reach the log.
                .doesNotContain("secret-token");
    }

    @Test
    void realErrors_keepSpringsDefaultHandling() {
        Object result = interceptor.handleError(
                streamRequest(), task, new NoSuchFileException("C:\\vods\\match.mp4"));

        assertThat(result).isSameAs(CallableProcessingInterceptor.RESULT_NONE);
        assertThat(appender.list).isEmpty();
    }

    @Test
    void alreadyWrappedBySpring_isLeftAlone() {
        Object result = interceptor.handleError(streamRequest(), task,
                new AsyncRequestNotUsableException("Servlet container error notification for disconnected client"));

        assertThat(result).isSameAs(CallableProcessingInterceptor.RESULT_NONE);
    }

    private static ServletWebRequest streamRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/matches/7/video/stream");
        request.setQueryString("token=secret-token");
        return new ServletWebRequest(request);
    }
}

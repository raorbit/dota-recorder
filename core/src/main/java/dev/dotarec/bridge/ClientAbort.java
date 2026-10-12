package dev.dotarec.bridge;

import org.apache.catalina.connector.ClientAbortException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Recognizes the failures a response write hits when the client has gone away. The renderer's
 * {@code <video>} element drops a range request whenever it seeks or closes, so on the VOD/clip
 * streams this is routine, not an error, and callers log it at DEBUG without a stack trace.
 *
 * <p>Spring's own {@code DisconnectedClientHelper} only knows the POSIX phrasing ("broken pipe",
 * "connection reset by peer"), so on Windows the WSAECONNABORTED abort ("An established connection
 * was aborted by the software in your host machine") reaches the servlet container as an unhandled
 * {@link IOException} and is logged twice at ERROR with a full stack trace. This adds the Windows
 * messages.
 *
 * <p>Only the response side may consult this: a file read failure must never be classified, so
 * callers apply it to exceptions from the response output stream or the container's async error
 * notification, never to the file input.
 */
final class ClientAbort {

    /**
     * Lower-cased message fragments of a socket the peer closed. The Windows ones come from the JDK
     * formatting the Winsock error; the rest are the JDK's portable and POSIX wordings.
     */
    private static final List<String> PHRASES = List.of(
            "an established connection was aborted", // WSAECONNABORTED (10053)
            "an existing connection was forcibly closed", // WSAECONNRESET (10054)
            "connection reset", // "Connection reset" / "Connection reset by peer"
            "broken pipe", // EPIPE
            "connection abort"); // ECONNABORTED: "Software caused connection abort"

    /** Bounds the cause walk so a pathological self-referencing chain can't loop forever. */
    private static final int MAX_CAUSE_DEPTH = 10;

    private ClientAbort() {
    }

    /**
     * True when {@code t}, or any cause in its chain, says the client disconnected: Tomcat's
     * {@link ClientAbortException} (it wraps every failed response write), Spring's
     * {@link AsyncRequestNotUsableException} (the async response is unusable after such a failure),
     * or an {@link IOException} carrying one of the closed-socket messages above.
     */
    static boolean isClientAbort(Throwable t) {
        Throwable cause = t;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (cause instanceof ClientAbortException || cause instanceof AsyncRequestNotUsableException) {
                return true;
            }
            if (cause instanceof IOException && hasClosedSocketMessage(cause.getMessage())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * One-line summary for the DEBUG log: the innermost cause's type and message, which is where the
     * OS reason lives once Tomcat and Spring have wrapped it.
     */
    static String describe(Throwable t) {
        Throwable root = t;
        for (int depth = 0; root.getCause() != null && root.getCause() != root && depth < MAX_CAUSE_DEPTH;
                depth++) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private static boolean hasClosedSocketMessage(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        for (String phrase : PHRASES) {
            if (lower.contains(phrase)) {
                return true;
            }
        }
        return false;
    }
}

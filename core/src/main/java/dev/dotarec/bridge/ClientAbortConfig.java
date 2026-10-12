package dev.dotarec.bridge;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.Callable;

/**
 * Keeps a client disconnect mid-stream out of the error log. The VOD and clip streams are
 * {@code StreamingResponseBody} responses, written on an async thread. When the renderer drops one
 * (a seek, or closing the player), the failed socket write makes Tomcat raise an async error
 * notification carrying the raw {@link java.io.IOException}. Spring recognizes that as a
 * disconnect only for the POSIX wording, so the Windows abort became the request's result, the
 * dispatch rethrew it, and Tomcat logged it twice at ERROR with a full stack trace.
 *
 * <p>The interceptor turns a recognized abort into Spring's {@link AsyncRequestNotUsableException},
 * which the MVC exception resolver already handles quietly. Anything {@link ClientAbort} does not
 * recognize keeps Spring's default handling, so a real failure still surfaces.
 */
@Configuration
public class ClientAbortConfig implements WebMvcConfigurer {

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.registerCallableInterceptors(new Interceptor());
    }

    /** Rewraps a container-reported client abort so it resolves quietly; see the class docs. */
    static final class Interceptor implements CallableProcessingInterceptor {

        private static final Logger log = LoggerFactory.getLogger(ClientAbortConfig.class);

        @Override
        public <T> Object handleError(NativeWebRequest request, Callable<T> task, Throwable t) {
            // Spring already wrapped the disconnects it recognizes; leave those as they are.
            if (t instanceof AsyncRequestNotUsableException || !ClientAbort.isClientAbort(t)) {
                return RESULT_NONE;
            }
            log.debug("Client disconnected from {}: {}", requestUri(request), ClientAbort.describe(t));
            return new AsyncRequestNotUsableException("Client disconnected", t);
        }

        private static String requestUri(NativeWebRequest request) {
            HttpServletRequest servletRequest = request.getNativeRequest(HttpServletRequest.class);
            return servletRequest != null ? servletRequest.getRequestURI() : "unknown request";
        }
    }
}

package dev.dotarec.bridge;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Bounded pool for Spring MVC's async requests: the VOD and clip streams and the clip thumbnails are
 * {@code StreamingResponseBody} responses, written on this executor rather than on a Tomcat request
 * thread.
 *
 * <p>Without it MVC falls back to a {@code SimpleAsyncTaskExecutor}, a fresh thread per response:
 * Spring Boot's own {@code applicationTaskExecutor} backs off because the app defines executors of
 * its own ({@code enrichExecutor}, {@code clipExecutor}; {@code spring.task.execution.mode=auto}),
 * and the default is unbounded and churns a thread on every seek. This pool keeps nothing alive at
 * idle (core threads time out) and its cap sits well above what one renderer can issue: Chromium
 * holds at most six connections per host, and the shell's status poll is synchronous.
 */
@Configuration
public class MvcAsyncConfig {

    /** Thread name prefix; the connector test asserts a stream runs here. */
    static final String THREAD_NAME_PREFIX = "mvc-async-";

    @Bean("mvcAsyncExecutor")
    public ThreadPoolTaskExecutor mvcAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(16);
        // No queue: a stream goes straight to a thread or is refused, never parked behind another
        // open VOD stream that may live for a whole playback.
        executor.setQueueCapacity(0);
        executor.setKeepAliveSeconds(30);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix(THREAD_NAME_PREFIX);
        // Daemon so a stream still draining at shutdown can never hold the JVM open.
        executor.setDaemon(true);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // No explicit initialize(): as a bean, afterPropertiesSet() does it once, and destroy()
        // shuts it down with the context.
        return executor;
    }

    @Bean
    public WebMvcConfigurer mvcAsyncConfigurer(
            @Qualifier("mvcAsyncExecutor") AsyncTaskExecutor mvcAsyncExecutor) {
        return new WebMvcConfigurer() {
            @Override
            public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
                configurer.setTaskExecutor(mvcAsyncExecutor);
            }
        };
    }
}

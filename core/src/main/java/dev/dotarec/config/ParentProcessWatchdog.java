package dev.dotarec.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Self-terminates the core when its supervising Electron parent dies.
 *
 * <p>Electron is the sole supervisor and reaps the core via a Windows Job Object on a hard crash —
 * but that path silently degrades to a no-op when {@code koffi} can't load or the job can't be
 * created. A hard-killed Electron (SIGKILL / Task Manager "End Task" / crash) would then orphan this
 * JVM, leaving it holding the GSI ({@code :3223}) and bridge ({@code :3224}) ports and blocking the
 * next launch.
 *
 * <p>As a backstop, the supervisor passes its own pid via {@code app.parent-pid}; this polls the
 * parent's liveness and exits the JVM when it's gone, freeing the ports. It is a no-op when no parent
 * pid is configured (a standalone {@code java -jar} or {@code gradlew bootRun} has no supervising
 * parent to watch). Polling is cheap ({@link ProcessHandle#of}) and runs on the shared scheduler, so
 * it's inert in tests (scheduling disabled) and when no parent pid is set.
 */
@Component
public class ParentProcessWatchdog {

    private static final Logger log = LoggerFactory.getLogger(ParentProcessWatchdog.class);

    private final long parentPid;

    public ParentProcessWatchdog(@Value("${app.parent-pid:0}") long parentPid) {
        this.parentPid = parentPid;
    }

    @Scheduled(fixedDelay = 5_000L)
    public void checkParent() {
        if (parentPid <= 0) {
            return; // No supervising parent (standalone / dev run): nothing to watch.
        }
        if (ProcessHandle.of(parentPid).isEmpty()) {
            log.warn(
                    "Supervising parent process {} is gone; exiting to free the loopback ports",
                    parentPid);
            System.exit(0);
        }
    }
}

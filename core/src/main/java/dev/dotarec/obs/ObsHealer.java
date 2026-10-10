package dev.dotarec.obs;

import dev.dotarec.config.AppPaths;
import dev.dotarec.config.TimeSource;
import dev.dotarec.obs.setup.ObsLayout;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Restarts the managed OBS by ending its process; Electron's OBS supervisor sees the exit and
 * relaunches it, and {@link ObsConnectionScheduler} reconnects and reconfigures the new instance.
 *
 * <p>Why a restart: OBS cannot recover from a lost graphics device (a GPU driver update or reset
 * while it runs). From then on every capture, screenshot and encode fails, yet StartRecord still
 * "succeeds" and writes an empty file. Its first-run wizard, left open in the tray, likewise
 * disables every output. A fresh process fixes both.
 *
 * <p>Only the process running this app's own OBS executable is ever touched, never another OBS
 * (the user's, or deadlock-recorder's). Restarts are rate-limited so a GPU that is genuinely gone
 * cannot become a restart loop. The bundled OBS runs with {@code --multi}, which skips OBS's
 * unclean-shutdown dialog, so a killed instance relaunches without a prompt.
 */
@Component
public class ObsHealer implements ObsRestarter {

    private static final Logger log = LoggerFactory.getLogger(ObsHealer.class);

    /**
     * Minimum spacing between restarts: long enough for a relaunch to come up and be judged, and
     * longer than Electron's OBS_STABLE_MS (2 min), so each restart counts as a healthy OBS exiting
     * rather than a crash loop and never runs out Electron's relaunch budget.
     */
    static final long MIN_INTERVAL_MS = 180_000L;

    /** Restarts allowed per {@link #WINDOW_MS}; past this OBS is left alone. */
    static final int MAX_PER_WINDOW = 3;

    static final long WINDOW_MS = 60L * 60 * 1000;

    private static final long EXIT_WAIT_MS = 5_000L;

    /** Finds and ends processes by executable path. Seam for tests. */
    public interface ProcessControl {
        /** Ends every process whose executable is {@code exe}; returns how many were ended. */
        int killAll(Path exe, long waitMs);
    }

    private final Path obsExecutable;
    private final ProcessControl processes;
    private final TimeSource time;
    private final Deque<Long> recentNanos = new ArrayDeque<>();

    @Autowired
    public ObsHealer(AppPaths paths, TimeSource time) {
        this(new ObsLayout(paths.obsDir()).obs64(), ObsHealer::killByExecutable, time);
    }

    ObsHealer(Path obsExecutable, ProcessControl processes, TimeSource time) {
        this.obsExecutable = obsExecutable;
        this.processes = processes;
        this.time = time;
    }

    @Override
    public synchronized boolean restartObs(String reason) {
        long now = time.nanoTime();
        while (!recentNanos.isEmpty() && now - recentNanos.peekFirst() >= WINDOW_MS * 1_000_000L) {
            recentNanos.removeFirst();
        }
        Long last = recentNanos.peekLast();
        if (last != null && now - last < MIN_INTERVAL_MS * 1_000_000L) {
            log.info("Not restarting OBS ({}): it was restarted {}s ago", reason,
                    (now - last) / 1_000_000_000L);
            return false;
        }
        if (recentNanos.size() >= MAX_PER_WINDOW) {
            log.warn("Not restarting OBS ({}): already restarted {} times this hour", reason,
                    recentNanos.size());
            return false;
        }
        recentNanos.addLast(now);
        int killed = processes.killAll(obsExecutable, EXIT_WAIT_MS);
        if (killed == 0) {
            log.warn("OBS needs a restart ({}) but no process is running {}", reason,
                    obsExecutable);
            return false;
        }
        log.warn("Restarted OBS: {}", reason);
        return true;
    }

    private static int killByExecutable(Path exe, long waitMs) {
        String target = normalize(exe.toString());
        int[] killed = {0};
        ProcessHandle.allProcesses()
                .filter(ph -> ph.info().command()
                        .map(c -> normalize(c).equals(target))
                        .orElse(false))
                .forEach(ph -> {
                    if (ph.destroyForcibly()) {
                        killed[0]++;
                        try {
                            ph.onExit().get(waitMs, TimeUnit.MILLISECONDS);
                        } catch (Exception e) {
                            log.debug("OBS pid {} did not exit within {}ms", ph.pid(), waitMs);
                        }
                    }
                });
        return killed[0];
    }

    private static String normalize(String path) {
        return Path.of(path).toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }
}

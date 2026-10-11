package dev.dotarec.obs;

import dev.dotarec.config.AppPaths;
import dev.dotarec.config.TimeSource;
import dev.dotarec.obs.setup.ObsLayout;
import java.io.IOException;
import java.nio.file.InvalidPathException;
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
     * rather than a crash loop and never runs out Electron's relaunch budget. Enforced by
     * app/electron/supervision.test.ts, which parses this value (keep it a plain long literal) and
     * fails unless it exceeds OBS_STABLE_MS by at least 30s.
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

    /**
     * Latches the hourly-cap refusal so it warns once per episode. The FSM keeps asking while a
     * match waits to record (every render probe), so a GPU that stays dead would otherwise warn
     * every few seconds until the hour frees up. Re-armed as soon as the window has room again.
     */
    private boolean capRefusalLogged;

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
            // Routine while the last restart's OBS comes up and is judged: callers ask again
            // every few seconds, and the restart that started the cooldown was already logged.
            log.debug("Not restarting OBS ({}): it was restarted {}s ago", reason,
                    (now - last) / 1_000_000_000L);
            return false;
        }
        if (recentNanos.size() >= MAX_PER_WINDOW) {
            if (capRefusalLogged) {
                log.debug("Not restarting OBS ({}): still at {} restarts this hour", reason,
                        recentNanos.size());
            } else {
                capRefusalLogged = true;
                log.warn("Not restarting OBS ({}): already restarted {} times this hour; leaving"
                        + " it alone until the hour frees up", reason, recentNanos.size());
            }
            return false;
        }
        capRefusalLogged = false;
        int killed = processes.killAll(obsExecutable, EXIT_WAIT_MS);
        if (killed == 0) {
            // Nothing was ended (e.g. Electron is mid-relaunch), so nothing was spent: no cooldown
            // starts and the hourly budget is untouched, leaving the next real restart free to run.
            log.warn("OBS needs a restart ({}) but no process is running {}", reason,
                    obsExecutable);
            return false;
        }
        recentNanos.addLast(now);
        log.warn("Restarted OBS: {}", reason);
        return true;
    }

    private static int killByExecutable(Path exe, long waitMs) {
        String target = canonical(exe.toString());
        String exeName = fileName(exe.toString());
        if (target == null || exeName == null) {
            return 0;
        }
        int[] killed = {0};
        ProcessHandle.allProcesses()
                .filter(ph -> ph.info().command()
                        // Cheap name check first: resolving a real path touches the disk, and
                        // only obs64.exe candidates are worth it.
                        .filter(c -> exeName.equals(fileName(c)))
                        .map(c -> target.equals(canonical(c)))
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

    /**
     * The path as the file system resolves it (junctions, symlinks and 8.3 short names expanded by
     * {@link Path#toRealPath}), lower-cased because Windows paths are case-insensitive. A purely
     * lexical comparison misses our OBS whenever %LOCALAPPDATA% sits behind a junction (a common
     * way to move AppData to another drive), since our configured path and the path Windows
     * reports for the running image can then name the same file differently. Falls back to the
     * lexical form when the file can't be resolved, and to null for a string that isn't a valid
     * path. Package-visible for tests.
     */
    static String canonical(String path) {
        try {
            Path p = Path.of(path);
            try {
                p = p.toRealPath();
            } catch (IOException | SecurityException e) {
                p = p.toAbsolutePath().normalize();
            }
            return p.toString().toLowerCase(Locale.ROOT);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static String fileName(String path) {
        try {
            Path name = Path.of(path).getFileName();
            return name == null ? null : name.toString().toLowerCase(Locale.ROOT);
        } catch (InvalidPathException e) {
            return null;
        }
    }
}

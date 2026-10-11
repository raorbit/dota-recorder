package dev.dotarec.obs;

import dev.dotarec.fsm.MatchFsm;
import dev.dotarec.fsm.MatchState;
import dev.dotarec.gsi.GsiHeartbeat;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Whether the managed OBS needs to be running right now.
 *
 * <p>OBS used to run for the app's whole lifetime. Even idle it renders its scene every frame and
 * holds its audio captures open, and a second always-on OBS (deadlock-recorder) next to this one was
 * enough to make browser video stutter. So Electron now launches OBS only while this reports
 * {@code wanted}, and stops it once it has been unwanted for a while (see
 * {@code app/electron/obs-demand.ts}).
 *
 * <p>OBS is wanted while any of these hold:
 *
 * <ul>
 *   <li>Dota is running ({@value #GAME_EXE} is a live process), or sent an authorized GSI frame in
 *       the last {@link #GSI_RECENT_MS}ms, so OBS is up long before hero selection arms a recording;
 *   <li>the FSM is not IDLE, or OBS reports an active output (a recording, or a stop-failed one the
 *       FSM is still retrying);
 *   <li>something that needs OBS outside a match asked for it in the last {@link #LEASE_MS}ms: the
 *       settings preview and the audio pickers {@link #touch()} on every request.
 * </ul>
 *
 * <p>Read from request threads ({@code GET /status}) and the scheduler. It never takes the
 * {@link MatchFsm} monitor (only its volatile state reads), so a status poll can't stall behind a
 * blocking StartRecord.
 */
@Component
public class ObsDemand {

    private static final Logger log = LoggerFactory.getLogger(ObsDemand.class);

    /** The game executable whose presence keeps OBS up. */
    static final String GAME_EXE = "dota2.exe";

    /** How long one {@link #touch()} keeps OBS wanted. The preview re-touches every ~1s. */
    static final long LEASE_MS = 30_000L;

    /** An authorized GSI frame this recent counts as "Dota is running". */
    static final long GSI_RECENT_MS = 30_000L;

    /** The process scan is cached this long; status is polled every few seconds. */
    static final long PROCESS_SCAN_CACHE_MS = 2_000L;

    private final GsiHeartbeat gsi;
    private final ObsHealth health;
    private final MatchFsm fsm;
    private final BooleanSupplier gameProcessRunning;
    private final LongSupplier nanoTime;

    private volatile long leaseUntilNanos;
    private volatile boolean leased;

    private final Object scanLock = new Object();
    private long lastScanNanos;
    private boolean lastScanResult;
    private boolean scannedOnce;

    // @Lazy for the same cycle StatusService breaks: StatusService -> ObsDemand -> MatchFsm ->
    // EventPublisher -> StatusService. Only volatile getters are called on it, at runtime.
    @Autowired
    public ObsDemand(GsiHeartbeat gsi, ObsHealth health, @Lazy MatchFsm fsm) {
        this(gsi, health, fsm, ObsDemand::scanForGameProcess, System::nanoTime);
    }

    ObsDemand(
            GsiHeartbeat gsi,
            ObsHealth health,
            MatchFsm fsm,
            BooleanSupplier gameProcessRunning,
            LongSupplier nanoTime) {
        this.gsi = gsi;
        this.health = health;
        this.fsm = fsm;
        this.gameProcessRunning = gameProcessRunning;
        this.nanoTime = nanoTime;
    }

    /** Keeps OBS wanted for the next {@link #LEASE_MS}ms. Cheap; call on every request needing OBS. */
    public void touch() {
        leaseUntilNanos = nanoTime.getAsLong() + LEASE_MS * 1_000_000L;
        leased = true;
    }

    /** Whether OBS should be running now. */
    public boolean isWanted() {
        if (fsm.getState() != MatchState.IDLE || health.isRecording() || fsm.hasPendingOrphanedStop()) {
            return true;
        }
        if (leased && nanoTime.getAsLong() - leaseUntilNanos < 0L) {
            return true;
        }
        if (gsi.lastAuthorizedFrameAgoMillis() < GSI_RECENT_MS) {
            return true;
        }
        return isGameRunning();
    }

    private boolean isGameRunning() {
        synchronized (scanLock) {
            long now = nanoTime.getAsLong();
            if (!scannedOnce || now - lastScanNanos >= PROCESS_SCAN_CACHE_MS * 1_000_000L) {
                lastScanResult = gameProcessRunning.getAsBoolean();
                lastScanNanos = now;
                scannedOnce = true;
            }
            return lastScanResult;
        }
    }

    /** True when a live process's executable is {@value #GAME_EXE}. Never throws. */
    static boolean scanForGameProcess() {
        try {
            return ProcessHandle.allProcesses()
                    .anyMatch(p -> p.info().command().map(ObsDemand::isGameExe).orElse(false));
        } catch (RuntimeException e) {
            log.debug("Process scan for {} failed: {}", GAME_EXE, e.toString());
            return false;
        }
    }

    static boolean isGameExe(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        try {
            Path name = Path.of(command).getFileName();
            return name != null && GAME_EXE.equals(name.toString().toLowerCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return false;
        }
    }
}

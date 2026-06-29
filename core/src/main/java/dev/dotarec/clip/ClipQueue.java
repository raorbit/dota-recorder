package dev.dotarec.clip;

import dev.dotarec.data.ClipRepository;
import dev.dotarec.data.ClipRow;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled poller that re-dispatches {@code pending} clip rows to the {@link ClipService}.
 *
 * <p>Every 60s it asks the repository for clips still in {@code pending} and fire-and-forgets each
 * onto the bounded {@code clipExecutor} (the {@code @Async} dispatch returns immediately). A clip is
 * normally rendered the moment it is created — {@link ClipService#createManual}/{@code createAuto}
 * dispatch {@link ClipService#generateAsync} synchronously — so this queue is the safety net: a clip
 * that was inserted but whose dispatch never ran (a crash between insert and async hand-off, the
 * executor's queue full) or a future retry still gets picked up.
 *
 * <p>Idempotent by construction: only {@code pending} rows are queried, and {@code generateAsync}
 * flips a row to {@code generating} before it does any work, so a row already being rendered (or
 * already {@code ready}/{@code failed}) is never seen here and never double-cut.
 */
@Component
public class ClipQueue {

    private static final Logger log = LoggerFactory.getLogger(ClipQueue.class);

    private final ClipRepository clips;
    private final ClipService clipService;

    public ClipQueue(ClipRepository clips, ClipService clipService) {
        this.clips = clips;
        this.clipService = clipService;
    }

    /**
     * At startup, reset any clip stuck in {@code generating} back to {@code pending}. Such a row is an
     * orphan from a prior run that crashed mid-cut — the periodic {@link #sweep()} only re-dispatches
     * {@code pending}, so without this it would spin forever (a perpetual UI spinner). Mirrors the
     * recording journal's crash reconciliation. Safe ONLY at boot, before any worker is active: a live
     * {@code generating} row must never be reset, or it would be double-cut.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOrphans() {
        List<ClipRow> stuck = clips.findByStatus("generating");
        for (ClipRow clip : stuck) {
            log.info("Re-pending clip {} orphaned in 'generating' by a prior run", clip.id());
            clips.updateStatus(clip.id(), "pending", null, null, null, null);
        }
    }

    /**
     * Polls and re-dispatches pending clip rows. Cadence mirrors {@code EnrichmentQueue}. The
     * initialDelay keeps this first poll from firing the instant the scheduler starts (the tail of
     * context refresh) — before the startup {@code MigrationRunner} has created the {@code clips}
     * table on a fresh/upgrade boot, which would otherwise log a spurious "no such table" error.
     */
    @Scheduled(initialDelay = 60_000L, fixedDelay = 60_000L)
    public void sweep() {
        List<ClipRow> pending = clips.findByStatus("pending");
        if (pending.isEmpty()) {
            return;
        }
        log.debug("Dispatching {} pending clips for generation", pending.size());
        for (ClipRow clip : pending) {
            // @Async -> returns immediately, runs on clipExecutor. generateAsync flips the row to
            // generating before any work, so a still-pending row picked up here is never double-cut.
            clipService.generateAsync(clip.id());
        }
    }
}

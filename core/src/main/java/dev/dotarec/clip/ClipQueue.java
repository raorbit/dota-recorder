package dev.dotarec.clip;

import dev.dotarec.data.ClipRepository;
import dev.dotarec.data.ClipRow;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
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

    /**
     * A {@code generating} row whose generation started longer ago than this is assumed wedged — its
     * worker died after the cut but before the status write (e.g. a back-to-back SQLITE_BUSY at
     * finalize), so the {@code @Async} method's exception escaped and the row never reached a terminal
     * state. Must strictly EXCEED a single {@code generateAsync} run's true worst case: THREE
     * back-to-back {@code Clipper} process ceilings — the copy attempt and the re-encode retry, each
     * capped at {@code Clipper.MAX_TIMEOUT_MS} (30 min), plus the thumbnail grab capped at {@code
     * Clipper.MIN_TIMEOUT_MS} (10 min): a true worst case of ~70 min, bounded above by 3×MAX (90 min) —
     * plus generous margin, or a legitimately slow render (a long manual clip whose re-encode fallback runs
     * for many minutes) would be re-pended while its original worker is still finalizing and the same
     * sweep would dispatch a SECOND concurrent cut to the identical {@code -y} output path. Because the
     * {@code Clipper} timeout now scales with clip length up to that ceiling, this cutoff is sized to
     * 3x it (co-designed — see {@code Clipper.MAX_TIMEOUT_MS}). Measured from {@code
     * generation_started_at} (set when the row was claimed), NOT {@code created_at}, so a clip that sat
     * {@code pending} in a saturated queue for longer than this before being claimed is never re-pended
     * mid-render (and double-cut).
     */
    private static final long STALE_GENERATING_MS = 120L * 60_000L;

    private final ClipRepository clips;
    private final ClipService clipService;

    /**
     * Construction time, the boot cutoff for {@link #reconcileOrphans()}. Singletons construct during
     * context refresh, BEFORE Tomcat serves bridge requests and before the {@code @Scheduled} clock
     * starts (both begin at finishRefresh), so every claim stamped by THIS process is {@code >=} this
     * value — and any older stamp is provably a prior-run orphan. A backward wall-clock step across a
     * restart can only make an orphan look live, which safely degrades to {@link #sweep()}'s stale
     * self-heal. The inverse (a backward step INSIDE the seconds between construction and
     * {@code ApplicationReadyEvent} making a live claim look prior-run) is accepted: it needs an NTP
     * step to land in that window at the same moment a pre-ready worker claims a clip.
     */
    private final long bootEpochMs = System.currentTimeMillis();

    public ClipQueue(ClipRepository clips, ClipService clipService) {
        this.clips = clips;
        this.clipService = clipService;
    }

    /**
     * At startup, reset clips stuck in {@code generating} back to {@code pending}. Such a row is an
     * orphan from a prior run that crashed mid-cut — the periodic {@link #sweep()} only re-dispatches
     * {@code pending}, so without this it would spin forever (a perpetual UI spinner). Mirrors the
     * recording journal's crash reconciliation. NOT unconditional: Tomcat serves bridge requests (and
     * the {@code @Scheduled} clock starts) at context refresh, before {@code ApplicationReadyEvent}
     * fires, so a worker can already hold a LIVE claim here (a manual clip POST, or the first sweep
     * when earlier runners take &gt;60s) — resetting it would re-enable the double-cut. The boot cutoff
     * is {@link #bootEpochMs}, not the stale window: a claim stamped before this process existed has no
     * worker by definition (even a seconds-old one from a crash-and-quick-relaunch is re-pended
     * instantly), while any this-process claim is left for its worker.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOrphans() {
        for (ClipRow clip : clips.findByStatus("generating")) {
            if (clips.rependIfOrphaned(clip.id(), bootEpochMs)) {
                log.info("Re-pending clip {} orphaned in 'generating' by a prior run", clip.id());
            }
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
        // Self-heal rows wedged in 'generating' past the stale cutoff. Unlike reconcileOrphans (boot
        // only), this runs every interval, so a row stranded mid-flight by a finalize-time DB failure
        // (the @Async exception escaped before it could be marked failed) recovers without a reboot.
        // rependIfOrphaned re-checks both status AND the claim stamp in the WHERE, so a worker that
        // just finished — or whose fenced re-stamp landed after the stale query above — is not
        // clobbered back to pending. Re-pended rows are picked up by the pending dispatch below.
        long staleBefore = System.currentTimeMillis() - STALE_GENERATING_MS;
        for (ClipRow stuck : clips.findStaleGenerating(staleBefore)) {
            if (clips.rependIfOrphaned(stuck.id(), staleBefore)) {
                log.info("Re-pending clip {} wedged in 'generating' since {} (stale > {}ms)",
                        stuck.id(), stuck.createdAt(), STALE_GENERATING_MS);
            }
        }

        List<ClipRow> pending = clips.findByStatus("pending");
        if (pending.isEmpty()) {
            return;
        }
        log.debug("Dispatching {} pending clips for generation", pending.size());
        for (ClipRow clip : pending) {
            // @Async -> returns immediately, runs on clipExecutor. generateAsync flips the row to
            // generating before any work, so a still-pending row picked up here is never double-cut.
            try {
                clipService.generateAsync(clip.id());
            } catch (TaskRejectedException e) {
                // Executor saturated (AbortPolicy) — the first over-capacity dispatch throws. Log and
                // continue so the rest of the batch still gets dispatched; the rejected row stays
                // pending and is retried next tick. Mirrors ClipService.create()'s dispatch guard.
                log.debug("Clip {} dispatch rejected (queue full); left pending for next sweep", clip.id());
            }
        }
    }
}

package dev.dotarec.fsm;

import dev.dotarec.gsi.GsiFrame;
import dev.dotarec.tagger.PendingMarker;
import java.util.ArrayList;
import java.util.List;

/**
 * Mutable holder for the in-flight recording, owned by the FSM while in RECORDING/STOPPING.
 *
 * <p>{@code recordConfirmedWallMs} is the local wall-clock instant OBS confirmed OUTPUT_STARTED;
 * it is the anchor every {@code markers.video_offset_s} is computed against (plan + see
 * {@code VideoOffsetCalculator}). {@code surrogateId} keys the in-progress recording before the
 * official Dota match_id is known/confirmed by enrichment.
 *
 * <p>It also carries the live-tagging working set: the {@code lastFrame} the {@code EventTagger}
 * diffs against and the buffered {@link PendingMarker}s flushed to the DB in one batch at finalize.
 * The latest snapshot of hero/K/D/A/match-id is kept here so the finalized {@code matches} row can
 * be written even though the POST_GAME frame itself usually has the hero block absent.
 */
public class RecordingSession {

    private String surrogateId;
    private long recordConfirmedWallMs;
    private long recordStartedWallMs;
    private String videoPath;
    private String scene;

    /** The most recent frame the tagger diffs the next one against. */
    private GsiFrame lastFrame;

    /** Markers detected live, flushed to {@code markers} at finalize. */
    private final List<PendingMarker> markers = new ArrayList<>();

    /**
     * Pause spans observed live (from {@code map.paused} edges), flushed to {@code pauses} at
     * finalize. The matches row only exists at finalize, while pause edges happen mid-RECORDING, so
     * they must be buffered here (like markers) rather than written through {@code PauseRepository}
     * which needs a real {@code matchId}. The last entry's {@code endWall} is null while a pause is
     * still open; {@link #drainPauses(long)} closes it to the finalize wall clock.
     */
    private final List<PauseSpanBuffer> pauseSpans = new ArrayList<>();

    // Latest observed match facts, snapshotted as frames arrive so finalize can persist them even
    // when the terminal POST_GAME frame omits the hero/player block.
    private String hero;
    private long matchId;
    private int kills;
    private int deaths;
    private int assists;
    private int radiantScore;
    private int direScore;

    /**
     * True once a GAME_IN_PROGRESS frame has been observed for this recording. Lets the FSM tell a
     * NEW match's opening draft (an arm state seen after the prior match was already in progress) apart
     * from the SAME match's repeated arm-state frames, so the latter don't thrash stop/start.
     */
    private boolean reachedGameInProgress;

    public String getSurrogateId() {
        return surrogateId;
    }

    public void setSurrogateId(String surrogateId) {
        this.surrogateId = surrogateId;
    }

    public long getRecordConfirmedWallMs() {
        return recordConfirmedWallMs;
    }

    public void setRecordConfirmedWallMs(long recordConfirmedWallMs) {
        this.recordConfirmedWallMs = recordConfirmedWallMs;
    }

    public long getRecordStartedWallMs() {
        return recordStartedWallMs;
    }

    public void setRecordStartedWallMs(long recordStartedWallMs) {
        this.recordStartedWallMs = recordStartedWallMs;
    }

    public String getVideoPath() {
        return videoPath;
    }

    public void setVideoPath(String videoPath) {
        this.videoPath = videoPath;
    }

    public String getScene() {
        return scene;
    }

    public void setScene(String scene) {
        this.scene = scene;
    }

    public GsiFrame getLastFrame() {
        return lastFrame;
    }

    public void setLastFrame(GsiFrame lastFrame) {
        this.lastFrame = lastFrame;
    }

    public List<PendingMarker> getMarkers() {
        return markers;
    }

    public void addMarkers(List<PendingMarker> more) {
        markers.addAll(more);
    }

    /**
     * Opens a pause span at {@code startWall} (a false-&gt;true {@code paused} edge). A no-op if a span
     * is already open so a stutter in the {@code paused} flag can't nest spans.
     */
    public void openPause(long startWall) {
        if (hasOpenPause()) {
            return;
        }
        pauseSpans.add(new PauseSpanBuffer(startWall, null));
    }

    /**
     * Closes the currently-open pause span at {@code endWall} (a true-&gt;false {@code paused} edge). A
     * no-op when no span is open (a resume without a matching pause).
     */
    public void closePause(long endWall) {
        if (!hasOpenPause()) {
            return;
        }
        PauseSpanBuffer open = pauseSpans.get(pauseSpans.size() - 1);
        // Clamp so a backward wall-clock jump (NTP step) between the open and close edge can never
        // persist end_wall < start_wall; a degenerate span collapses to zero width instead. This also
        // covers drainPauses, which closes a still-open span to finalizeWall.
        long clamped = Math.max(open.startWall(), endWall);
        pauseSpans.set(pauseSpans.size() - 1, new PauseSpanBuffer(open.startWall(), clamped));
    }

    /**
     * Returns the buffered pause spans for persistence, closing a still-open span to
     * {@code finalizeWall} first so no span is left with a null {@code endWall} (the game ended while
     * paused, or the watchdog force-finalized mid-pause).
     */
    public List<PauseSpanBuffer> drainPauses(long finalizeWall) {
        if (hasOpenPause()) {
            closePause(finalizeWall);
        }
        return pauseSpans;
    }

    private boolean hasOpenPause() {
        return !pauseSpans.isEmpty() && pauseSpans.get(pauseSpans.size() - 1).endWall() == null;
    }

    public String getHero() {
        return hero;
    }

    public long getMatchId() {
        return matchId;
    }

    public int getKills() {
        return kills;
    }

    public int getDeaths() {
        return deaths;
    }

    public int getAssists() {
        return assists;
    }

    public int getRadiantScore() {
        return radiantScore;
    }

    public int getDireScore() {
        return direScore;
    }

    public boolean hasReachedGameInProgress() {
        return reachedGameInProgress;
    }

    /** Latches that this recording has seen a GAME_IN_PROGRESS frame (set by the FSM). */
    public void markReachedGameInProgress() {
        this.reachedGameInProgress = true;
    }

    /**
     * Snapshots the match facts from a frame that carries them. Frames with the hero block absent
     * (heartbeats, hero-select, the terminal POST_GAME frame) leave the last good hero/id intact so
     * finalize still records who played; the K/D/A counters are likewise held at their last good value
     * whenever the player block is absent.
     */
    public void observe(GsiFrame frame) {
        if (frame.matchId() != 0L) {
            this.matchId = frame.matchId();
        }
        if (frame.heroPresent() && frame.hero() != null) {
            this.hero = frame.hero();
        }
        // Guard the counters on player presence (like matchId/hero): a heartbeat/reconnect frame drops
        // the player block, which zeroes K/D/A upstream (GsiPayload.toFrame). Copying those zeros would
        // let a dropout-then-silence force-finalize persist 0/0/0 over the last good counters.
        if (frame.playerPresent()) {
            this.kills = frame.kills();
            this.deaths = frame.deaths();
            this.assists = frame.assists();
        }
        this.radiantScore = frame.radiantScore();
        this.direScore = frame.direScore();
    }

    /**
     * A buffered pause span in wall-clock millis. {@code endWall} is null only while the pause is
     * open mid-recording; {@link #drainPauses(long)} guarantees it is non-null before persistence.
     */
    public record PauseSpanBuffer(long startWall, Long endWall) {
    }
}

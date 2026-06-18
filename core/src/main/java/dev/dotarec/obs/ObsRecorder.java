package dev.dotarec.obs;

import java.time.Instant;

/**
 * The testable seam between recording orchestration (a later FSM PR) and the real OBS engine.
 *
 * <p>The FSM depends only on this interface so it can be unit-tested against an in-memory fake that
 * simulates connect/ready/start/stop without a live OBS. {@link ObsController} is the production
 * implementation backed by obs-websocket v5.
 *
 * <p>Contract notes the FSM relies on:
 *
 * <ul>
 *   <li>None of these methods may assume OBS is running. {@link #ensureConnected()} degrades to
 *       {@code false} rather than throwing; the start/stop primitives throw {@link ObsException}
 *       (a typed, recoverable error) if invoked while disconnected.</li>
 *   <li>{@link #startRecording()} returning successfully means OBS <em>accepted</em> the request,
 *       not that frames are flowing. The genuine "recording is real" signal is OUTPUT_STARTED,
 *       reflected by {@link #recordConfirmedAt()} becoming non-null and {@code ObsHealth.recording}
 *       flipping true. Gate on that before trusting a recording exists.</li>
 *   <li>Thumbnails must be captured BEFORE {@link #stopRecording()} -- a screenshot after the scene
 *       goes idle is black. See {@link ThumbnailService}.</li>
 * </ul>
 */
public interface ObsRecorder {

    /**
     * Opens (or reopens) the obs-websocket connection using the current host/port/password from
     * settings. Asynchronous: this kicks off the connection; use {@link #ensureConnected()} to wait
     * for readiness. Safe to call repeatedly; an existing connection is torn down first so changed
     * settings take effect.
     */
    void connect();

    /**
     * Ensures a live, identified, protocol-verified (v5) connection exists, connecting if needed.
     * Never throws on a down/unreachable OBS or a protocol mismatch -- it logs a clear message,
     * leaves {@code ObsHealth.connected=false}, and returns {@code false}.
     *
     * @return {@code true} if OBS is connected and speaking obs-websocket v5
     */
    boolean ensureConnected();

    /**
     * Whether OBS is safe to record against right now: connected AND a program scene is active AND a
     * desktop-audio input exists and is unmuted. This is the arm-time gate that prevents a green GSI
     * card from silently recording a black/silent file. Read-only: it never mutates OBS.
     */
    boolean isReady();

    /**
     * Sends StartRecord. Returns the confirmed OUTPUT_STARTED instant as an ISO-8601 string if it
     * has already arrived, else {@code null} (the event may land microseconds later on the socket
     * thread; read {@link #recordConfirmedAt()} for the authoritative anchor).
     *
     * @throws ObsException if OBS is not connected or rejects/times out the request
     */
    String startRecording();

    /**
     * Sends StopRecord and returns the saved output file path read from the StopRecord response
     * (the primary, authoritative path source in v5; the STARTED event's path is null).
     *
     * @return absolute path of the recorded file, or {@code null} if OBS reported none
     * @throws ObsException if OBS is not connected or rejects/times out the request
     */
    String stopRecording();

    /**
     * The wall-clock instant OBS confirmed OUTPUT_STARTED for the current/most-recent recording, or
     * {@code null} if recording has never been confirmed. This is the anchor every
     * {@code markers.video_offset_s} is computed against.
     */
    Instant recordConfirmedAt();
}

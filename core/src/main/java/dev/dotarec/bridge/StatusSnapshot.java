package dev.dotarec.bridge;

import dev.dotarec.retention.LowDiskState;

/**
 * Immutable snapshot of live recorder status, serialized to JSON for the {@code GET /status} REST
 * endpoint and pushed over the {@code /ws} WebSocket as the {@code status} envelope payload.
 *
 * <p>The shape is the cross-process contract with the renderer; keep it in sync with the
 * {@code StatusSnapshot} TypeScript type in {@code app/src/api/client.ts}. Field names map 1:1 to
 * JSON keys (Jackson serializes records by component name).
 *
 * <ul>
 *   <li>{@code gsi} -- sourced from {@code GsiHeartbeat}; {@code lastFrameAgoMs} is null until the
 *       first frame arrives.</li>
 *   <li>{@code obs} -- sourced from {@code ObsHealth}; all false until the OBS PR lands.</li>
 *   <li>{@code fsm} -- sourced from {@code MatchFsm}; {@code activeMatchId} is null until match
 *       persistence lands.</li>
 *   <li>{@code storage} -- sourced from {@code LowDiskState}; {@code lowDisk} is null while the
 *       video drive is healthy.</li>
 * </ul>
 */
public record StatusSnapshot(GsiStatus gsi, ObsStatus obs, FsmStatus fsm, StorageStatus storage) {

    /** GSI feed liveness. {@code lastFrameAgoMs} is null before any frame has been received. */
    public record GsiStatus(boolean connected, Long lastFrameAgoMs) {}

    /**
     * OBS connection / scene / recording flags. {@code captureBlack} is true when the current or
     * most recent recording sampled black (near-zero luminance) — a black-screen recording that
     * {@code recording} alone can't reveal; the UI warns on it. Persists past the recording's end
     * until the next recording arms. {@code wanted} says whether OBS should be running now (see
     * {@code ObsDemand}): Electron starts and stops OBS on it, and a disconnected OBS that isn't
     * wanted is on standby, not an error.
     */
    public record ObsStatus(
            boolean connected,
            boolean sceneActive,
            boolean recording,
            boolean captureBlack,
            boolean wanted) {}

    /** FSM state name and the active match id (null when no match is in flight). */
    public record FsmStatus(String state, Long activeMatchId) {}

    /**
     * Storage health. {@code lowDisk} is the latest low-disk observation from the hourly
     * free-space check, or null while the video drive is healthy. Carried here (not only as a
     * one-off {@code error} frame) so a window re-created from the tray shows the banner at once and
     * the shell can raise its own low-disk notification without a WebSocket of its own.
     */
    public record StorageStatus(LowDiskState.LowDisk lowDisk) {}
}

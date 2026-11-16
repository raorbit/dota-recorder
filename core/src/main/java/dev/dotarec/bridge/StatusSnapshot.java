package dev.dotarec.bridge;

import dev.dotarec.retention.LowDiskState;
import java.util.List;

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
 *   <li>{@code deletes} -- sourced from {@code DeleteQueue}; both lists are empty while no delete is
 *       pending.</li>
 *   <li>{@code kept} -- sourced from {@code StatusWebSocket}; {@code frames} is 0 while it keeps no
 *       frame for the next window.</li>
 * </ul>
 */
public record StatusSnapshot(
        GsiStatus gsi, ObsStatus obs, FsmStatus fsm, StorageStatus storage, DeleteStatus deletes,
        KeptStatus kept) {

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

    /**
     * The recordings ({@code matchIds}) and clips ({@code clipIds}) whose delete the core has accepted
     * and not finished, oldest first: queued behind the storage-maintenance lock, or running (see
     * {@code DeleteQueue}). Carried here so a window opened while a delete waits (one closed to the
     * tray and opened again, say) still shows those rows as being deleted.
     */
    public record DeleteStatus(List<Long> matchIds, List<Long> clipIds) {}

    /**
     * How many one-off frames no window has taken yet, which the core keeps in memory for the next
     * one that connects (see {@code StatusWebSocket#broadcastOrKeep}): a queued delete's failure,
     * say, while the window was closed to the tray. Carried here so the shell's unattended update
     * install, which restarts the core, waits until a window has taken them rather than losing them
     * (see {@code app/electron/status-reading.ts}; the renderer doesn't read it).
     */
    public record KeptStatus(int frames) {}
}

package dev.dotarec.bridge;

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
 * </ul>
 */
public record StatusSnapshot(GsiStatus gsi, ObsStatus obs, FsmStatus fsm) {

    /** GSI feed liveness. {@code lastFrameAgoMs} is null before any frame has been received. */
    public record GsiStatus(boolean connected, Long lastFrameAgoMs) {}

    /** OBS connection / scene / recording flags. */
    public record ObsStatus(boolean connected, boolean sceneActive, boolean recording) {}

    /** FSM state name and the active match id (null when no match is in flight). */
    public record FsmStatus(String state, Long activeMatchId) {}
}

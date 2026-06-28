package dev.dotarec.bridge;

import dev.dotarec.fsm.MatchFsm;
import dev.dotarec.gsi.GsiHeartbeat;
import dev.dotarec.obs.ObsHealth;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * Assembles a {@link StatusSnapshot} from the live health/state components.
 *
 * <p>Single source of truth for "what the UI status card shows": the {@code GET /status} REST
 * endpoint, the initial frame sent on WebSocket connect, and every {@code status} broadcast all go
 * through {@link #snapshot()} so the REST view and the pushed view can never disagree.
 */
@Service
public class StatusService {

    private final GsiHeartbeat gsiHeartbeat;
    private final ObsHealth obsHealth;
    private final MatchFsm matchFsm;

    // @Lazy breaks the construction cycle StatusService -> MatchFsm -> EventPublisher ->
    // StatusService. MatchFsm is only dereferenced at runtime in snapshot(), so a lazy proxy is
    // safe and keeps the core domain bean free of presentation-layer wiring concerns.
    public StatusService(GsiHeartbeat gsiHeartbeat, ObsHealth obsHealth, @Lazy MatchFsm matchFsm) {
        this.gsiHeartbeat = gsiHeartbeat;
        this.obsHealth = obsHealth;
        this.matchFsm = matchFsm;
    }

    /** Builds a point-in-time snapshot of recorder status. */
    public StatusSnapshot snapshot() {
        boolean gsiConnected = gsiHeartbeat.isAlive();
        // Surface elapsed-since-last-frame only once a frame has actually arrived; the heartbeat
        // reports Long.MAX_VALUE before the first mark(), which is meaningless to the UI.
        long ago = gsiHeartbeat.lastFrameAgoMillis();
        Long lastFrameAgoMs = (ago == Long.MAX_VALUE) ? null : ago;

        StatusSnapshot.GsiStatus gsi = new StatusSnapshot.GsiStatus(gsiConnected, lastFrameAgoMs);
        StatusSnapshot.ObsStatus obs =
                new StatusSnapshot.ObsStatus(
                        obsHealth.isConnected(), obsHealth.isSceneActive(), obsHealth.isRecording());
        // activeMatchId stays null until match persistence/lifecycle lands in a later PR.
        StatusSnapshot.FsmStatus fsm =
                new StatusSnapshot.FsmStatus(matchFsm.getState().name(), null);

        return new StatusSnapshot(gsi, obs, fsm);
    }
}

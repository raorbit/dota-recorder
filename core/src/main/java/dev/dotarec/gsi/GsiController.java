package dev.dotarec.gsi;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives Dota 2 Game State Integration POSTs.
 *
 * <p>Dota POSTs JSON ~10x/sec to the GSI ingest connector (127.0.0.1:3223). For the v0.1
 * foundation this only records a heartbeat so the UI status card can reflect GSI connectivity
 * (plan: "GSI Listener (local HTTP :3223)" and "status card reflects GSI connectivity").
 *
 * <p>TODO(plan: Match lifecycle / Event detection): parse the raw body into a {@link GsiFrame}
 * via {@link GsiPayload} and drive {@code MatchFsm.onFrame} + {@code EventTagger}.
 */
@RestController
public class GsiController {

    private final GsiHeartbeat heartbeat;

    public GsiController(GsiHeartbeat heartbeat) {
        this.heartbeat = heartbeat;
    }

    @PostMapping("/gsi")
    public ResponseEntity<Void> ingest(@RequestBody(required = false) Map<String, Object> body) {
        heartbeat.mark();
        // TODO(plan): GsiPayload -> GsiFrame -> MatchFsm.onFrame(frame) -> EventTagger.
        return ResponseEntity.ok().build();
    }
}

package dev.dotarec.gsi;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dotarec.fsm.MatchFsm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives Dota 2 Game State Integration POSTs on the GSI connector (127.0.0.1:3223).
 *
 * <p>Dota POSTs JSON ~10x/sec. The handler does the minimum on the request thread to keep the GSI
 * feed snappy:
 * <ol>
 *   <li>Stamp {@code wallClockMillis} at entry -- this is the local arrival time the video-offset
 *       math anchors against, so it must be captured before any parsing latency.</li>
 *   <li>{@link GsiHeartbeat#mark()} FIRST, so the UI status card reflects connectivity even for a
 *       heartbeat ping that carries no parseable game state.</li>
 *   <li>Parse the body into a {@link GsiPayload}, flatten to a {@link GsiFrame}, and drive
 *       {@code MatchFsm.onFrame}.</li>
 * </ol>
 *
 * <p>Step 3 runs inside a try/catch that logs and STILL returns 200: Dota ignores the response body
 * and a non-200 only produces client-side log noise, so a malformed/partial frame must never break
 * the heartbeat or the feed. The body is taken as a raw String (not auto-bound) so a parse failure
 * is handled here rather than by Spring's message converter returning 400.
 */
@RestController
public class GsiController {

    private static final Logger log = LoggerFactory.getLogger(GsiController.class);

    private final GsiHeartbeat heartbeat;
    private final MatchFsm matchFsm;
    private final ObjectMapper mapper;

    public GsiController(GsiHeartbeat heartbeat, MatchFsm matchFsm, ObjectMapper mapper) {
        this.heartbeat = heartbeat;
        this.matchFsm = matchFsm;
        this.mapper = mapper;
    }

    @PostMapping("/gsi")
    public ResponseEntity<Void> ingest(@RequestBody(required = false) String body) {
        long wallClockMillis = System.currentTimeMillis();
        heartbeat.mark();
        if (body == null || body.isBlank()) {
            return ResponseEntity.ok().build();
        }
        try {
            GsiPayload payload = mapper.readValue(body, GsiPayload.class);
            GsiFrame frame = payload.toFrame(wallClockMillis);
            matchFsm.onFrame(frame);
        } catch (Exception e) {
            // Malformed/partial body, or an FSM hiccup: log and still ack. Dota discards the
            // response, so a 500 here would just spam its console without helping anyone.
            log.warn("Failed to process GSI frame: {}", e.toString());
        }
        return ResponseEntity.ok().build();
    }
}

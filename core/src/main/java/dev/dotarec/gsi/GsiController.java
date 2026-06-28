package dev.dotarec.gsi;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.fsm.MatchFsm;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
 *   <li>Parse the body into a {@link GsiPayload}, validate its {@code auth { token }} against the
 *       configured GSI token, and on an accepted frame flatten to a {@link GsiFrame} and drive
 *       {@code MatchFsm.onFrame}.</li>
 * </ol>
 *
 * <p>Step 3 runs inside a try/catch that logs and STILL returns 200: Dota ignores the response body
 * and a non-200 only produces client-side log noise, so a malformed/partial/unauthenticated frame
 * must never break the heartbeat or the feed. The body is taken as a raw String (not auto-bound) so a
 * parse failure is handled here rather than by Spring's message converter returning 400.
 *
 * <p>Auth: when {@code settings.gsiAuthToken} is set, a frame whose token does not match is dropped
 * (the FSM is not driven) so a spoofed local POST cannot trigger/stop recordings. A BLANK configured
 * token accepts every frame — a half-configured install (cfg not yet written, or written without a
 * token) must never go dark; the {@code GsiCfgInstaller} only mints a token alongside a cfg carrying it.
 */
@RestController
public class GsiController {

    private static final Logger log = LoggerFactory.getLogger(GsiController.class);

    private final GsiHeartbeat heartbeat;
    private final MatchFsm matchFsm;
    private final ObjectMapper mapper;
    private final SettingsStore settings;
    private volatile boolean accountCaptureDone;

    public GsiController(
            GsiHeartbeat heartbeat, MatchFsm matchFsm, ObjectMapper mapper, SettingsStore settings) {
        this.heartbeat = heartbeat;
        this.matchFsm = matchFsm;
        this.mapper = mapper;
        this.settings = settings;
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
            if (!authorized(payload)) {
                // Drop spoofed/unauthenticated frames silently (still 200) — never drive the FSM.
                log.warn("Dropping GSI frame with missing/mismatched auth token");
                return ResponseEntity.ok().build();
            }
            // Only authorized frames credit the watchdog's liveness clock, so a flood of invalid POSTs
            // to the token-exempt /gsi endpoint can't suppress force-finalization during a real silence.
            heartbeat.markAuthorized();
            captureAccountId(payload);
            GsiFrame frame = payload.toFrame(wallClockMillis);
            matchFsm.onFrame(frame);
        } catch (Exception e) {
            // Malformed/partial body, or an FSM hiccup: log and still ack. Dota discards the
            // response, so a 500 here would just spam its console without helping anyone.
            log.warn("Failed to process GSI frame: {}", e.toString());
        }
        return ResponseEntity.ok().build();
    }

    /**
     * True when the frame may drive the FSM. A blank configured token accepts everything (migration
     * safety); otherwise the frame's token must match, compared in constant time.
     */
    private boolean authorized(GsiPayload payload) {
        String configured = settings.get().gsiAuthToken;
        if (configured == null || configured.isBlank()) {
            return true;
        }
        String presented = payload.authToken();
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                configured.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    private void captureAccountId(GsiPayload payload) {
        if (accountCaptureDone || settings.get().accountId != null) {
            accountCaptureDone = true;
            return;
        }
        Long accountId = payload.parseAccountId();
        if (accountId == null) {
            return;
        }
        settings.update(
                s -> {
                    if (s.accountId == null) {
                        s.accountId = accountId;
                    }
                    return s;
                });
        accountCaptureDone = true;
        log.info("Captured Dota account id from authenticated GSI frame");
    }
}

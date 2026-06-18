package dev.dotarec.bridge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Serializes typed events to the {@code { "type", "payload" }} envelope and broadcasts them to all
 * connected UI clients via {@link StatusWebSocket}.
 *
 * <p>This is the one place application code calls to push to the UI. Plan event types include
 * {@code status} (recorder state for the status card) and, later, {@code match.recorded} /
 * {@code match.enriched} (library refresh notifications).
 *
 * <p>Dependency direction is {@code EventPublisher -> StatusWebSocket} (never the reverse) to keep
 * the bean graph acyclic; {@link StatusWebSocket} sends its own initial frame on connect.
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final StatusWebSocket socket;
    private final StatusService statusService;
    private final ObjectMapper mapper;

    public EventPublisher(StatusWebSocket socket, StatusService statusService, ObjectMapper mapper) {
        this.socket = socket;
        this.statusService = statusService;
        this.mapper = mapper;
    }

    /**
     * Serializes {@code { type, payload }} and broadcasts it. A null {@code payload} is allowed and
     * serializes to {@code "payload": null}. Serialization failures are logged, not thrown, so a
     * bad event never disrupts the caller (e.g. the FSM mid-transition).
     */
    public void publish(String type, Object payload) {
        Map<String, Object> envelope = new HashMap<>(2);
        envelope.put("type", type);
        envelope.put("payload", payload);
        try {
            socket.broadcast(mapper.writeValueAsString(envelope));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize event of type '{}'", type, e);
        }
    }

    /** Convenience: broadcasts the current recorder status as a {@code status} frame. */
    public void publishStatus() {
        publish("status", statusService.snapshot());
    }
}

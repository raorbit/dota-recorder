package dev.dotarec.bridge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Pushes live recorder status and match events to subscribed UI clients.
 *
 * <p>Plan (State Management): broadcast live recording state -- FSM state, active match_id, OBS
 * recording status, GSI health (drives the green/red status card) -- plus {@code match.recorded}
 * and {@code match.enriched} notifications so the library refreshes when enrichment completes.
 *
 * <p>Every frame is a JSON envelope {@code { "type": string, "payload": any }}. The {@code status}
 * type carries a {@link StatusSnapshot}. On connect, a client immediately receives the current
 * status so the UI renders correct state without waiting for the next change broadcast.
 *
 * <p>Bean-cycle note: this handler depends only on {@link StatusService} + {@link ObjectMapper}
 * (to send the initial frame). {@link EventPublisher} depends on <em>this</em> handler to push
 * subsequent broadcasts. Keeping the dependency one-directional (EventPublisher -> StatusWebSocket)
 * avoids a circular bean reference.
 */
@Component
public class StatusWebSocket extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(StatusWebSocket.class);

    private final StatusService statusService;
    private final ObjectMapper mapper;
    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

    public StatusWebSocket(StatusService statusService, ObjectMapper mapper) {
        this.statusService = statusService;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        // Prime the new client with current status so its card isn't blank until the next change.
        try {
            String frame = mapper.writeValueAsString(envelope("status", statusService.snapshot()));
            sendTo(session, frame);
        } catch (JsonProcessingException e) {
            // Serialization of our own snapshot should never fail; log and keep the session open.
            log.warn("Failed to serialize initial status frame", e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    /**
     * Sends a pre-serialized JSON frame to every open session. Dead/half-open sessions are dropped
     * rather than aborting the whole broadcast. Called from {@link EventPublisher}.
     */
    public void broadcast(String json) {
        for (WebSocketSession session : sessions) {
            sendTo(session, json);
        }
    }

    private void sendTo(WebSocketSession session, String json) {
        if (!session.isOpen()) {
            sessions.remove(session);
            return;
        }
        try {
            // WebSocketSession.sendMessage is not thread-safe: concurrent sends to the same session
            // can interleave frames. Serialize per session.
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException | IllegalStateException e) {
            // Broken pipe / closed mid-send: drop the session, let the client reconnect.
            log.debug("Dropping WebSocket session after send failure: {}", session.getId(), e);
            sessions.remove(session);
        }
    }

    /** Builds the wire envelope. Shared shape with {@link EventPublisher#envelope}. */
    private static Map<String, Object> envelope(String type, Object payload) {
        return Map.of("type", type, "payload", payload);
    }
}

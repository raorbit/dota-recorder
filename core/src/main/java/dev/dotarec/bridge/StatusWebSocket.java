package dev.dotarec.bridge;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Pushes live recorder status and match events to subscribed UI clients.
 *
 * <p>Plan (State Management): broadcast live recording state -- FSM state, active match_id, OBS
 * recording status, GSI health (drives the green/red status card) -- plus {@code match.recorded}
 * and {@code match.enriched} notifications so the library refreshes when enrichment completes.
 *
 * <p>TODO(plan): serialize and broadcast status / match.recorded / match.enriched payloads.
 */
@Component
public class StatusWebSocket extends TextWebSocketHandler {

    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    // TODO(plan): broadcast(String json) over sessions for status / match.recorded / match.enriched.
}

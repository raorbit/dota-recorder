package dev.dotarec.bridge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
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

    /** The most frames {@link #broadcastOrKeep} keeps for the next session; older ones are dropped. */
    static final int MAX_KEPT = 20;

    private final StatusService statusService;
    private final ObjectMapper mapper;
    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    /**
     * Frames {@link #broadcastOrKeep} sent while no session was open, oldest first, for the next one
     * that connects. Guarded by itself, which also covers adding a session to {@link #sessions}, so a
     * frame either reaches a new session as a broadcast or is kept for it, never neither.
     */
    private final Deque<String> kept = new ArrayDeque<>();

    public StatusWebSocket(StatusService statusService, ObjectMapper mapper) {
        this.statusService = statusService;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // Prime the new client with current status so its card isn't blank until the next change.
        // Ordering: build + send the priming frame, then publish the session to the broadcast set --
        // all under the SAME per-session monitor sendTo()/broadcast() use. The session isn't visible
        // to a concurrent broadcast() until priming has been sent, and the shared lock serializes that
        // broadcast's send AFTER the priming send, so a heartbeat can't overtake the initial frame and
        // leave the client showing stale state.
        List<String> unsent = List.of();
        synchronized (session) {
            try {
                String frame =
                        mapper.writeValueAsString(envelope("status", statusService.snapshot()));
                sendTo(session, frame);
            } catch (JsonProcessingException e) {
                // Serialization of our own snapshot should never fail; log and keep the session open.
                log.warn("Failed to serialize initial status frame", e);
            }
            List<String> missed;
            synchronized (kept) {
                sessions.add(session);
                missed = List.copyOf(kept);
                kept.clear();
            }
            // Then what no session got (see broadcastOrKeep), still ahead of any later broadcast.
            for (int i = 0; i < missed.size(); i++) {
                if (!sendTo(session, missed.get(i))) {
                    unsent = missed.subList(i, missed.size());
                    break;
                }
            }
        }
        // What this session couldn't take either waits for the next one. Outside the session's
        // monitor: broadcastOrKeep takes the two the other way round.
        if (!unsent.isEmpty()) {
            synchronized (kept) {
                for (int i = unsent.size() - 1; i >= 0; i--) {
                    kept.addFirst(unsent.get(i));
                }
            }
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
        sendToAll(json);
    }

    /**
     * {@link #broadcast}s a one-off frame a window must see even when none is open: one that reaches
     * no session is kept and sent to the next session that connects, right after its status frame. For
     * news with no other carrier, such as a queued delete that failed while the window was closed to
     * the tray. At most {@link #MAX_KEPT} are kept.
     */
    public void broadcastOrKeep(String json) {
        synchronized (kept) {
            if (sendToAll(json) == 0) {
                kept.addLast(json);
                while (kept.size() > MAX_KEPT) {
                    kept.removeFirst();
                }
            }
        }
    }

    /** Sends to every open session; returns how many took the frame. */
    private int sendToAll(String json) {
        int sent = 0;
        for (WebSocketSession session : sessions) {
            if (sendTo(session, json)) {
                sent++;
            }
        }
        return sent;
    }

    /** False when the session is closed or the send failed; the session is then dropped. */
    private boolean sendTo(WebSocketSession session, String json) {
        if (!session.isOpen()) {
            sessions.remove(session);
            return false;
        }
        try {
            // WebSocketSession.sendMessage is not thread-safe: concurrent sends to the same session
            // can interleave frames. Serialize per session.
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
            return true;
        } catch (IOException | IllegalStateException e) {
            // Broken pipe / closed mid-send: drop the session, let the client reconnect.
            log.debug("Dropping WebSocket session after send failure: {}", session.getId(), e);
            sessions.remove(session);
            return false;
        }
    }

    /** Builds the wire envelope. Shared shape with {@link EventPublisher#envelope}. */
    private static Map<String, Object> envelope(String type, Object payload) {
        return Map.of("type", type, "payload", payload);
    }
}

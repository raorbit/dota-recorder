package dev.dotarec.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Guards the connect-time priming send against a concurrent {@link StatusWebSocket#broadcast broadcast}
 * overtaking it. The priming frame must reach a freshly-connected session before any heartbeat frame,
 * or the client shows stale state until the next ~5s tick.
 */
class StatusWebSocketTest {

    private static StatusSnapshot snapshot() {
        return new StatusSnapshot(
                new StatusSnapshot.GsiStatus(false, null),
                new StatusSnapshot.ObsStatus(false, false, false),
                new StatusSnapshot.FsmStatus("IDLE", null));
    }

    @Test
    void concurrentBroadcastNeverOvertakesThePrimingFrame() throws Exception {
        StatusService statusService = mock(StatusService.class);
        StatusWebSocket socket = new StatusWebSocket(statusService, new ObjectMapper());

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);

        // Record every payload the session receives, in send order. The fix must guarantee the priming
        // "status" frame is the FIRST thing this session ever gets, even though a broadcaster thread is
        // hammering broadcast() concurrently with the connect.
        List<String> sent = new CopyOnWriteArrayList<>();
        doAnswer(
                        inv -> {
                            sent.add(((TextMessage) inv.getArgument(0)).getPayload());
                            return null;
                        })
                .when(session)
                .sendMessage(any());

        CountDownLatch primingEntered = new CountDownLatch(1);
        CountDownLatch releasePriming = new CountDownLatch(1);
        // snapshot() runs INSIDE the synchronized(session) block, before the session is published to
        // the set. Stalling here holds the session monitor open while the broadcaster spins on
        // broadcast(): it proves broadcast()'s per-session send can only proceed once priming releases
        // the monitor -- and by then the session is already in the set AND primed.
        when(statusService.snapshot())
                .thenAnswer(
                        inv -> {
                            primingEntered.countDown();
                            releasePriming.await(2, TimeUnit.SECONDS);
                            return snapshot();
                        });

        // Broadcaster: once the connect thread is inside the stalled priming block, spin broadcasting
        // until the session receives a broadcast frame (i.e. it has been added to the set). Whatever it
        // sends must land after the priming frame.
        Thread broadcaster =
                new Thread(
                        () -> {
                            try {
                                primingEntered.await(2, TimeUnit.SECONDS);
                                // Let priming proceed only after we've started contending, so the
                                // broadcaster's send races the priming send on the same monitor.
                                releasePriming.countDown();
                                long deadline = System.currentTimeMillis() + 2000;
                                while (System.currentTimeMillis() < deadline
                                        && sent.stream().noneMatch(p -> p.contains("\"heartbeat\""))) {
                                    socket.broadcast("{\"type\":\"heartbeat\"}");
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });
        broadcaster.start();

        // Connect thread holds the session monitor across snapshot() + priming send + sessions.add().
        Thread connect = new Thread(() -> socket.afterConnectionEstablished(session));
        connect.start();

        connect.join(3000);
        broadcaster.join(3000);

        // The priming frame is present and is the first frame the session ever received; no heartbeat
        // slipped in front of it.
        assertThat(sent).isNotEmpty();
        assertThat(sent.get(0))
                .as("first frame to a new session must be the priming status frame")
                .contains("\"status\"");
    }

    @Test
    void primingFrameIsSentAndSessionIsRegisteredForLaterBroadcasts() throws Exception {
        StatusService statusService = mock(StatusService.class);
        when(statusService.snapshot()).thenReturn(snapshot());
        StatusWebSocket socket = new StatusWebSocket(statusService, new ObjectMapper());

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        List<String> sent = new ArrayList<>();
        doAnswer(
                        inv -> {
                            sent.add(((TextMessage) inv.getArgument(0)).getPayload());
                            return null;
                        })
                .when(session)
                .sendMessage(any());

        socket.afterConnectionEstablished(session);

        // Priming frame arrives on connect...
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).contains("\"status\"");

        // ...and the session is now registered, so a subsequent broadcast reaches it (strictly after
        // priming). This confirms the session was added to the set, not dropped by the priming path.
        socket.broadcast("{\"type\":\"heartbeat\"}");
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1)).contains("\"heartbeat\"");
    }
}

package dev.dotarec.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
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
                new StatusSnapshot.ObsStatus(false, false, false, false, false),
                new StatusSnapshot.FsmStatus("IDLE", null),
                new StatusSnapshot.StorageStatus(null),
                new StatusSnapshot.DeleteStatus(List.of(), List.of()),
                new StatusSnapshot.KeptStatus(0));
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

    @Test
    void aFrameNoSessionTook_goesToTheNextSessionThatConnects_rightAfterItsStatus() throws Exception {
        // A queued delete failed while the window was closed to the tray: the window opened next must
        // still hear of it, once.
        StatusWebSocket socket = socket();
        socket.broadcastOrKeep(FAILED);
        socket.broadcast(HEARTBEAT); // an ordinary broadcast nobody took is just gone

        List<String> first = new ArrayList<>();
        socket.afterConnectionEstablished(window(first));
        List<String> second = new ArrayList<>();
        socket.afterConnectionEstablished(window(second));

        assertThat(first).hasSize(2);
        assertThat(first.get(0)).contains("\"status\"");
        assertThat(first.get(1)).isEqualTo(FAILED);
        assertThat(second).hasSize(1);
        assertThat(second.get(0)).contains("\"status\"");
    }

    @Test
    void aFrameASessionTook_isNotKept() throws Exception {
        StatusWebSocket socket = socket();
        List<String> open = new ArrayList<>();
        socket.afterConnectionEstablished(window(open));

        socket.broadcastOrKeep(FAILED);
        List<String> later = new ArrayList<>();
        socket.afterConnectionEstablished(window(later));

        assertThat(open).hasSize(2);
        assertThat(open.get(1)).isEqualTo(FAILED);
        assertThat(later).hasSize(1);
    }

    @Test
    void keepsOnlyTheLatestFrames_whileNoSessionTakesThem() throws Exception {
        StatusWebSocket socket = socket();
        for (int i = 0; i <= StatusWebSocket.MAX_KEPT; i++) {
            socket.broadcastOrKeep("{\"n\":" + i + "}");
        }

        List<String> sent = new ArrayList<>();
        socket.afterConnectionEstablished(window(sent));

        // The oldest, 0, was dropped; 1 to MAX_KEPT follow the status frame in order.
        assertThat(sent).hasSize(1 + StatusWebSocket.MAX_KEPT);
        assertThat(sent.get(1)).isEqualTo("{\"n\":1}");
        assertThat(sent.get(StatusWebSocket.MAX_KEPT))
                .isEqualTo("{\"n\":" + StatusWebSocket.MAX_KEPT + "}");
    }

    @Test
    void keptFramesCountsWhatWaitsForTheNextSession() throws Exception {
        // /status carries this, so the shell's update install doesn't restart the core over them.
        StatusWebSocket socket = socket();
        assertThat(socket.keptFrames()).isZero();
        socket.broadcastOrKeep(FAILED);
        socket.broadcastOrKeep(FAILED);
        assertThat(socket.keptFrames()).isEqualTo(2);

        socket.afterConnectionEstablished(window(new ArrayList<>()));
        assertThat(socket.keptFrames()).isZero();
        socket.broadcastOrKeep(FAILED); // taken by the session that is open now
        assertThat(socket.keptFrames()).isZero();
    }

    @Test
    void keptFramesStillCountsWhatTheNextSessionCouldNotTake() throws Exception {
        StatusWebSocket socket = socket();
        socket.broadcastOrKeep(FAILED);

        // The status frame goes out, the kept one doesn't: the session closed in between.
        WebSocketSession closing = mock(WebSocketSession.class);
        when(closing.getUri()).thenReturn(URI.create(WS + LIBRARY));
        when(closing.isOpen()).thenReturn(true, false);
        socket.afterConnectionEstablished(closing);

        assertThat(socket.keptFrames()).isEqualTo(1);
    }

    @Test
    void aSessionThatDoesNotTakeKeptFrames_isNotCountedAsHavingTakenOne() throws Exception {
        // The player's own socket is the only one open (the library's is backing off): it may get
        // the frame, but has nothing to show it with, so it is kept for the library's.
        StatusWebSocket socket = socket();
        List<String> player = new ArrayList<>();
        socket.afterConnectionEstablished(recording(player, PLAYER));

        socket.broadcastOrKeep(FAILED);
        assertThat(socket.keptFrames()).isEqualTo(1);

        List<String> library = new ArrayList<>();
        socket.afterConnectionEstablished(window(library));
        assertThat(library).hasSize(2);
        assertThat(library.get(1)).isEqualTo(FAILED);
        assertThat(socket.keptFrames()).isZero();
    }

    @Test
    void aSessionThatDoesNotTakeKeptFrames_isNotSentTheOnesKept() throws Exception {
        // Both sockets dropped and the player's came back first: the kept frame waits for the
        // library's.
        StatusWebSocket socket = socket();
        socket.broadcastOrKeep(FAILED);

        List<String> player = new ArrayList<>();
        socket.afterConnectionEstablished(recording(player, PLAYER));
        assertThat(player).hasSize(1);
        assertThat(player.get(0)).contains("\"status\"");
        assertThat(socket.keptFrames()).isEqualTo(1);

        List<String> library = new ArrayList<>();
        socket.afterConnectionEstablished(window(library));
        assertThat(library).hasSize(2);
        assertThat(library.get(1)).isEqualTo(FAILED);
    }

    @Test
    void takesKeptReadsTheParamFromTheQueryAlone() {
        assertThat(StatusWebSocket.takesKept(at(WS + LIBRARY))).isTrue();
        assertThat(StatusWebSocket.takesKept(at(WS + "?deleteFailures=1"))).isTrue();
        assertThat(StatusWebSocket.takesKept(at(WS + PLAYER))).isFalse();
        assertThat(StatusWebSocket.takesKept(at(WS + "?token=t&deleteFailures=0"))).isFalse();
        assertThat(StatusWebSocket.takesKept(at(WS + "?token=deleteFailures%3D1"))).isFalse();
        assertThat(StatusWebSocket.takesKept(at(WS))).isFalse();
        assertThat(StatusWebSocket.takesKept(mock(WebSocketSession.class))).isFalse();
    }

    private static final String WS = "ws://127.0.0.1:3224/ws";
    /** The library window's socket says it shows delete failures; the player's doesn't. */
    private static final String LIBRARY = "?token=t&deleteFailures=1";
    private static final String PLAYER = "?token=t";

    private static final String FAILED =
            "{\"type\":\"delete.failed\",\"payload\":{\"kind\":\"match\",\"id\":7,\"message\":\"locked\"}}";
    private static final String HEARTBEAT = "{\"type\":\"heartbeat\"}";

    private static StatusWebSocket socket() {
        StatusService statusService = mock(StatusService.class);
        when(statusService.snapshot()).thenReturn(snapshot());
        return new StatusWebSocket(statusService, new ObjectMapper());
    }

    /** The library window's session: it takes kept frames. See {@link #recording}. */
    private static WebSocketSession window(List<String> sent) throws Exception {
        return recording(sent, LIBRARY);
    }

    /** A session connected at {@code uri}, for {@link StatusWebSocket#takesKept}. */
    private static WebSocketSession at(String uri) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(URI.create(uri));
        return session;
    }

    /**
     * An open session connected with {@code query} that records every frame it is sent into {@code
     * sent}, in order.
     */
    private static WebSocketSession recording(List<String> sent, String query) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(URI.create(WS + query));
        when(session.isOpen()).thenReturn(true);
        doAnswer(
                        inv -> {
                            sent.add(((TextMessage) inv.getArgument(0)).getPayload());
                            return null;
                        })
                .when(session)
                .sendMessage(any());
        return session;
    }
}

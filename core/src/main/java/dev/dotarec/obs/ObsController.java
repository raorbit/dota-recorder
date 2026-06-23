package dev.dotarec.obs;

import dev.dotarec.config.SettingsStore;
import io.obswebsocket.community.client.OBSCommunicator;
import io.obswebsocket.community.client.OBSRemoteController;
import io.obswebsocket.community.client.message.event.outputs.RecordStateChangedEvent;
import io.obswebsocket.community.client.message.response.general.GetVersionResponse;
import io.obswebsocket.community.client.message.response.inputs.GetInputMuteResponse;
import io.obswebsocket.community.client.message.response.inputs.GetSpecialInputsResponse;
import io.obswebsocket.community.client.message.response.record.StopRecordResponse;
import io.obswebsocket.community.client.message.response.scenes.GetCurrentProgramSceneResponse;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Drives OBS Studio recording over obs-websocket v5, behind the {@link ObsRecorder} seam.
 *
 * <p>This is the capture engine for the recorder: a later FSM PR orchestrates when to start/stop,
 * but it only ever talks to the {@link ObsRecorder} interface so it can be unit-tested against a
 * fake. The primitives this class provides:
 *
 * <ul>
 *   <li>{@link #ensureConnected()} -- (re)establish the websocket, perform a v5 handshake, and
 *       report whether OBS is reachable. Never throws on a down OBS; returns {@code false}.</li>
 *   <li>{@link #isReady()} -- connected AND a program scene is active AND a desktop-audio input
 *       exists, so arming a recording against a green GSI card cannot silently capture nothing.</li>
 *   <li>{@link #startRecording()} / {@link #stopRecording()} -- StartRecord/StopRecord, with the
 *       saved file path read from the StopRecord response (the STARTED event's path is null in
 *       v5).</li>
 *   <li>{@link #recordConfirmedAt()} -- the wall-clock instant OBS confirmed OUTPUT_STARTED, the
 *       anchor for {@code markers.video_offset_s}.</li>
 * </ul>
 *
 * <p>Resilience: OBS not running at core boot must not crash the core. We never auto-connect at
 * construction; the FSM/settings layer calls {@link #ensureConnected()} on demand (settings-save,
 * arm time, schedule), and connection failures degrade to {@code connected=false} plus a typed
 * {@link ObsException}. Event callbacks run on the library socket thread and only flip the
 * {@code volatile} {@link ObsHealth} flags via {@link ObsEvents}; they never block.
 */
@Service
public class ObsController implements ObsRecorder {

    private static final Logger log = LoggerFactory.getLogger(ObsController.class);

    /** Per-request response timeout for synchronous obs-websocket calls. */
    private static final long REQUEST_TIMEOUT_MS = 5_000L;

    /** How long to wait for the websocket to become identified/ready after connect(). */
    private static final int CONNECT_TIMEOUT_SECONDS = 3;

    private final SettingsStore settings;
    private final ObsHealth health;
    private final ObsEvents events;

    /** The live controller; null when never connected or after a clean disconnect. */
    private volatile OBSRemoteController controller;

    /**
     * Latch {@link #ensureConnected()} waits on. Counted down on {@code onReady} (success) AND on the
     * disconnect/close/error lifecycle callbacks (failure), so a down or connection-refused OBS frees
     * the caller promptly instead of blocking it for the full connect timeout.
     */
    private volatile CountDownLatch readyLatch;

    /**
     * Set true only by {@code onReady}, so {@link #ensureConnected()} can tell a real identified
     * connection apart from a failure callback that merely released {@link #readyLatch}. Without it,
     * a down OBS would fall through to {@code verifyProtocol()} and either block on a dead socket for
     * the request timeout or misreport the outage as an "old obs-websocket". Reset at each connect.
     */
    private volatile boolean connectionReady;

    public ObsController(SettingsStore settings, ObsHealth health, ObsEvents events) {
        this.settings = settings;
        this.health = health;
        this.events = events;
    }

    @Override
    public synchronized void connect() {
        // A fresh connect attempt: tear down any prior controller and rebuild from current settings
        // (host/port/password may have changed via PUT /settings since the last attempt).
        disconnectQuietly();

        SettingsStore.Settings s = settings.get();
        CountDownLatch latch = new CountDownLatch(1);
        this.readyLatch = latch;
        this.connectionReady = false;

        OBSRemoteController c =
                OBSRemoteController.builder()
                        .host(s.obsHost)
                        .port(s.obsPort)
                        // The library default is password-LESS; stock OBS v5 forces auth, so always
                        // pass the configured password (may be "" if the user truly disabled auth).
                        .password(s.obsPassword == null ? "" : s.obsPassword)
                        .connectionTimeout(CONNECT_TIMEOUT_SECONDS)
                        // Do not auto-connect from the builder; we drive connect() explicitly so a
                        // down OBS at boot cannot throw during bean wiring.
                        .autoConnect(false)
                        .registerEventListener(
                                RecordStateChangedEvent.class, this::handleRecordStateChanged)
                        .lifecycle()
                        // Release the latch on success AND on every failure path so a down OBS does
                        // not strand ensureConnected() for the full timeout. Counting down an
                        // already-zero latch (e.g. an error after a healthy onReady) is a no-op.
                        // Only onReady sets connectionReady, so a failure-path release is told apart.
                        .onReady(
                                () -> {
                                    connectionReady = true;
                                    latch.countDown();
                                })
                        .onDisconnect(
                                () -> {
                                    onConnectionLost();
                                    latch.countDown();
                                })
                        .onClose(
                                code -> {
                                    onConnectionLost();
                                    latch.countDown();
                                })
                        .onControllerError(
                                rt -> {
                                    onError(rt == null ? null : rt.getThrowable());
                                    latch.countDown();
                                })
                        .onCommunicatorError(
                                rt -> {
                                    onError(rt == null ? null : rt.getThrowable());
                                    latch.countDown();
                                })
                        .and()
                        .build();
        this.controller = c;
        events.reset();
        c.connect();
    }

    @Override
    public synchronized boolean ensureConnected() {
        if (health.isConnected() && controller != null) {
            return true;
        }
        try {
            connect();
            CountDownLatch latch = this.readyLatch;
            boolean signaled =
                    latch != null
                            && latch.await(CONNECT_TIMEOUT_SECONDS + 2, TimeUnit.SECONDS);
            if (!signaled || !connectionReady) {
                // Either the connect timed out, or a disconnect/close/error woke us early. Either way
                // OBS is not identified -> degrade WITHOUT calling verifyProtocol (which would block on
                // a dead socket or misreport the outage as an old-protocol handshake). Do not crash.
                log.warn(
                        "OBS not reachable at {}:{}; staying disconnected",
                        settings.get().obsHost,
                        settings.get().obsPort);
                disconnectQuietly();
                health.setConnected(false);
                return false;
            }
            // Identified+ready: now verify we are talking v5, not a v4/old-OBS that merely accepted
            // the socket. A protocol mismatch otherwise surfaces as a confusing generic failure.
            verifyProtocol();
            health.setConnected(true);
            refreshSceneActive();
            log.info("OBS connected at {}:{}", settings.get().obsHost, settings.get().obsPort);
            return true;
        } catch (ObsException e) {
            // verifyProtocol() threw: a real connection but the wrong protocol. Surface the clear
            // message, tear down, stay disconnected.
            log.warn("OBS handshake rejected: {}", e.getMessage());
            disconnectQuietly();
            health.setConnected(false);
            return false;
        } catch (Exception e) {
            log.warn("OBS connect failed: {}", e.toString());
            disconnectQuietly();
            health.setConnected(false);
            return false;
        }
    }

    /**
     * Confirms the connected server speaks obs-websocket v5 by comparing its advertised rpcVersion
     * against the version this client library implements. A v4 / pre-28 OBS that somehow accepts the
     * socket would report a different rpcVersion; we want a clear "wrong protocol" message rather
     * than a cryptic request failure later.
     */
    private void verifyProtocol() {
        OBSRemoteController c = this.controller;
        if (c == null) {
            throw new ObsException("OBS controller went away during handshake");
        }
        GetVersionResponse version = c.getVersion(REQUEST_TIMEOUT_MS);
        if (version == null || !version.isSuccessful() || version.getRpcVersion() == null) {
            throw new ObsException(
                    "OBS did not answer GetVersion; it may be an old (v4) obs-websocket. "
                            + "Update OBS to 28+ and enable the v5 WebSocket server.");
        }
        int serverRpc = version.getRpcVersion().intValue();
        int clientRpc = OBSCommunicator.RPC_VERSION;
        if (serverRpc != clientRpc) {
            throw new ObsException(
                    "OBS WebSocket protocol mismatch: OBS reports rpcVersion "
                            + serverRpc
                            + " ("
                            + version.getObsWebSocketVersion()
                            + ") but this app speaks v"
                            + clientRpc
                            + ". Use OBS 28+ with the built-in v5 WebSocket server.");
        }
    }

    @Override
    public boolean isReady() {
        // Connected + a program scene is active + at least one desktop-audio input exists. These are
        // read-only existence checks: they prove a scene/source is CONFIGURED, not that pixels are
        // non-black -- but they catch the common "green GSI, OBS records nothing" failure (no scene,
        // no audio device) before the FSM arms a recording.
        if (!health.isConnected()) {
            return false;
        }
        OBSRemoteController c = this.controller;
        if (c == null) {
            return false;
        }
        try {
            boolean sceneOk = refreshSceneActive();
            boolean audioOk = hasDesktopAudio(c);
            return sceneOk && audioOk;
        } catch (Exception e) {
            log.warn("OBS readiness check failed: {}", e.toString());
            return false;
        }
    }

    @Override
    public String startRecording() {
        // Clear the previous recording's confirmed-start anchor BEFORE issuing StartRecord. The FSM
        // reads recordConfirmedAt() the instant this returns, but THIS match's OUTPUT_STARTED lands
        // asynchronously a moment later -- and events.reset() otherwise only runs on (re)connect, not
        // between matches on a live connection. Without this, the 2nd+ match per connection would
        // anchor on the PRIOR match's start instant (the null-fallback never engages on a non-null
        // stale value), inflating durationS and every marker video_offset_s by the inter-match gap.
        events.reset();
        OBSRemoteController c = requireController();
        // A previous StopRecord may have timed out while OBS kept recording: its OUTPUT_STOPPED never
        // arrived, so health.recording is still true and OBS would reject this StartRecord (an output
        // is already active), silently breaking this and every future match until the app restarts.
        // If we still believe a recording is live, issue a best-effort corrective stop first.
        if (health.isRecording()) {
            log.warn("OBS still flagged as recording at StartRecord; issuing a corrective StopRecord first");
            StopRecordResponse corrective;
            try {
                corrective = c.stopRecord(REQUEST_TIMEOUT_MS);
            } catch (RuntimeException e) {
                // Keep health.recording=true so the NEXT match retries this corrective stop instead of
                // skipping it forever; bail so the FSM stays IDLE rather than issuing a StartRecord OBS
                // will reject anyway.
                throw new ObsException("Corrective StopRecord before StartRecord failed", e);
            }
            if (corrective == null || !corrective.isSuccessful()) {
                // The library returns null / unsuccessful on TIMEOUT -- it does NOT throw (see
                // stopRecording()). OBS may well still be recording, so do NOT clear health.recording:
                // clearing it would make every later match skip this block and fail silently, exactly
                // the permanent breakage this guard exists to prevent. Bail and let the next match retry.
                throw new ObsException(
                        "Corrective StopRecord failed"
                                + (corrective == null ? " (no response / timeout)" : ""));
            }
            health.setRecording(false);
        }
        var resp = c.startRecord(REQUEST_TIMEOUT_MS);
        if (resp == null || !resp.isSuccessful()) {
            throw new ObsException(
                    "OBS StartRecord failed"
                            + (resp == null ? " (no response / timeout)" : ""));
        }
        // recording=true and recordConfirmedAt are set by the OUTPUT_STARTED event on the socket
        // thread, not here -- StartRecord returning success only means OBS accepted the request,
        // not that frames are flowing. The FSM should treat OUTPUT_STARTED as the real anchor.
        log.info("OBS StartRecord accepted");
        return events.recordConfirmedAt() == null ? null : events.recordConfirmedAt().toString();
    }

    @Override
    public String stopRecording() {
        OBSRemoteController c = requireController();
        StopRecordResponse resp = c.stopRecord(REQUEST_TIMEOUT_MS);
        if (resp == null || !resp.isSuccessful()) {
            throw new ObsException(
                    "OBS StopRecord failed" + (resp == null ? " (no response / timeout)" : ""));
        }
        // PRIMARY path source: the StopRecord response. The OUTPUT_STARTED event carries a null
        // outputPath in v5, so this is the authoritative way to learn where OBS wrote the file.
        String path = resp.getOutputPath();
        if (path == null || path.isBlank()) {
            // Fallback to the STOPPED event path if the response somehow omitted it.
            path = events.lastStoppedOutputPath();
        }
        health.setRecording(false);
        log.info("OBS StopRecord saved file: {}", path);
        return path;
    }

    @Override
    public Instant recordConfirmedAt() {
        return events.recordConfirmedAt();
    }

    /**
     * Refreshes {@link ObsHealth#setSceneActive} from the current program scene and returns whether
     * one is active. A non-blank current program scene name counts as active.
     */
    private boolean refreshSceneActive() {
        OBSRemoteController c = this.controller;
        if (c == null) {
            health.setSceneActive(false);
            return false;
        }
        try {
            GetCurrentProgramSceneResponse scene = c.getCurrentProgramScene(REQUEST_TIMEOUT_MS);
            boolean active =
                    scene != null
                            && scene.isSuccessful()
                            && scene.getCurrentProgramSceneName() != null
                            && !scene.getCurrentProgramSceneName().isBlank();
            health.setSceneActive(active);
            return active;
        } catch (Exception e) {
            health.setSceneActive(false);
            return false;
        }
    }

    /**
     * True when OBS exposes at least one desktop-audio special input that is not muted. Catches the
     * silent-VOD failure mode (recording with no audio device). Mic inputs are ignored here -- this
     * app cares that game/system audio is captured.
     */
    private boolean hasDesktopAudio(OBSRemoteController c) {
        GetSpecialInputsResponse special = c.getSpecialInputs(REQUEST_TIMEOUT_MS);
        if (special == null || !special.isSuccessful()) {
            return false;
        }
        return isAudioInputLive(c, special.getDesktop1())
                || isAudioInputLive(c, special.getDesktop2());
    }

    private boolean isAudioInputLive(OBSRemoteController c, String inputName) {
        if (inputName == null || inputName.isBlank()) {
            return false;
        }
        try {
            GetInputMuteResponse mute = c.getInputMute(inputName, REQUEST_TIMEOUT_MS);
            // Present and not muted. A muted desktop device would yield a silent VOD.
            return mute != null
                    && mute.isSuccessful()
                    && Boolean.FALSE.equals(mute.getInputMuted());
        } catch (Exception e) {
            return false;
        }
    }

    /** The live controller; only callers that already require an active connection use this. */
    OBSRemoteController controller() {
        return controller;
    }

    private OBSRemoteController requireController() {
        OBSRemoteController c = this.controller;
        if (c == null || !health.isConnected()) {
            throw new ObsException("OBS is not connected");
        }
        return c;
    }

    /** Forwards the event's already-extracted fields to {@link ObsEvents} on the socket thread. */
    private void handleRecordStateChanged(RecordStateChangedEvent event) {
        if (event == null) {
            return;
        }
        events.onRecordStateChanged(event.getOutputState(), event.getOutputPath());
    }

    private void onConnectionLost() {
        health.setConnected(false);
        health.setSceneActive(false);
        health.setRecording(false);
        log.info("OBS connection lost");
    }

    private void onError(Throwable t) {
        log.warn("OBS websocket error: {}", t == null ? "unknown" : t.toString());
        health.setConnected(false);
    }

    private void disconnectQuietly() {
        OBSRemoteController c = this.controller;
        this.controller = null;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception e) {
                log.debug("Ignoring error during OBS disconnect: {}", e.toString());
            }
        }
    }
}

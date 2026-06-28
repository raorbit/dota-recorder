package dev.dotarec.gsi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.fsm.MatchFsm;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Drives the real {@link GsiController} via standalone MockMvc (no full Spring context). Proves the
 * ingest contract: a real frame -> 200 + heartbeat marked + {@code FSM.onFrame} called with the
 * flattened frame; a garbage body -> still 200 (Dota discards the response, so a non-200 is pointless
 * client noise) and the FSM is left untouched.
 */
class GsiControllerTest {

    private GsiHeartbeat heartbeat;
    private MatchFsm fsm;
    private SettingsStore.Settings settingsValue;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        heartbeat = new GsiHeartbeat();
        fsm = mock(MatchFsm.class);
        // Blank gsiAuthToken by default => accept-all (so the real-frame fixtures, which carry no auth
        // block, still drive the FSM). Individual tests set a token to exercise the auth gate.
        settingsValue = new SettingsStore.Settings();
        SettingsStore settings = mock(SettingsStore.class);
        when(settings.get()).thenReturn(settingsValue);
        doAnswer(
                        invocation -> {
                            @SuppressWarnings("unchecked")
                            UnaryOperator<SettingsStore.Settings> mutator =
                                    invocation.getArgument(0, UnaryOperator.class);
                            settingsValue = mutator.apply(settingsValue);
                            return null;
                        })
                .when(settings)
                .update(org.mockito.ArgumentMatchers.any());
        GsiController controller = new GsiController(heartbeat, fsm, new ObjectMapper(), settings);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void realFrame_returns200_marksHeartbeat_andDrivesFsm() throws Exception {
        String body = Files.readString(Path.of("src/test/resources/gsi/game_in_progress.json"));

        mvc.perform(post("/gsi").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        assertThat(heartbeat.isAlive())
                .as("a successful POST must mark the GSI heartbeat alive")
                .isTrue();

        ArgumentCaptor<GsiFrame> captor = ArgumentCaptor.forClass(GsiFrame.class);
        verify(fsm).onFrame(captor.capture());
        GsiFrame frame = captor.getValue();
        assertThat(frame.gameState()).isEqualTo("DOTA_GAMERULES_STATE_GAME_IN_PROGRESS");
        assertThat(frame.kills()).isEqualTo(8);
        assertThat(frame.heroPresent()).isTrue();
    }

    @Test
    void garbageBody_stillReturns200_andHeartbeatStillMarked_butFsmNotCalled() throws Exception {
        mvc.perform(post("/gsi").contentType(MediaType.APPLICATION_JSON).content("}{not json"))
                .andExpect(status().isOk());

        // The heartbeat is marked BEFORE parsing, so even a malformed frame keeps GSI "alive".
        assertThat(heartbeat.isAlive()).isTrue();
        // But a body that doesn't parse must never reach the FSM.
        verify(fsm, never()).onFrame(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void emptyBody_returns200_marksHeartbeat_doesNotCallFsm() throws Exception {
        mvc.perform(post("/gsi")).andExpect(status().isOk());

        assertThat(heartbeat.isAlive()).isTrue();
        verify(fsm, never()).onFrame(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void matchingAuthToken_drivesFsm() throws Exception {
        settingsValue.gsiAuthToken = "secret-tok";
        String body =
                "{\"map\":{\"game_state\":\"DOTA_GAMERULES_STATE_GAME_IN_PROGRESS\"},"
                        + "\"auth\":{\"token\":\"secret-tok\"}}";

        mvc.perform(post("/gsi").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        verify(fsm).onFrame(org.mockito.ArgumentMatchers.any());
        // An authorized frame credits the watchdog's liveness clock.
        assertThat(heartbeat.lastAuthorizedFrameAgoMillis())
                .as("an authorized frame marks the watchdog heartbeat")
                .isLessThan(5_000L);
    }

    @Test
    void mismatchedAuthToken_returns200_marksHeartbeat_butDropsFrame() throws Exception {
        settingsValue.gsiAuthToken = "secret-tok";
        String body =
                "{\"map\":{\"game_state\":\"DOTA_GAMERULES_STATE_GAME_IN_PROGRESS\"},"
                        + "\"auth\":{\"token\":\"WRONG\"}}";

        mvc.perform(post("/gsi").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        // Heartbeat is marked before the auth check, so a spoofed frame still shows GSI "alive"...
        assertThat(heartbeat.isAlive()).isTrue();
        // ...but it must never reach the FSM (cannot trigger/stop a recording)...
        verify(fsm, never()).onFrame(org.mockito.ArgumentMatchers.any());
        // ...and crucially it must NOT credit the watchdog's authorized-frame clock, or a flood of
        // spoofed posts could suppress force-finalization during a real GSI silence.
        assertThat(heartbeat.lastAuthorizedFrameAgoMillis())
                .as("a dropped frame must not mark the watchdog heartbeat")
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void missingAuthToken_whenTokenConfigured_dropsFrame() throws Exception {
        settingsValue.gsiAuthToken = "secret-tok";
        String body = "{\"map\":{\"game_state\":\"DOTA_GAMERULES_STATE_GAME_IN_PROGRESS\"}}";

        mvc.perform(post("/gsi").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        verify(fsm, never()).onFrame(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void acceptedFrameCapturesAccountIdFromPlayerBlock() throws Exception {
        String body =
                "{\"map\":{\"game_state\":\"DOTA_GAMERULES_STATE_GAME_IN_PROGRESS\"},"
                        + "\"player\":{\"accountid\":\"96828122\"}}";

        mvc.perform(post("/gsi").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        assertThat(settingsValue.accountId).isEqualTo(96828122L);
        verify(fsm).onFrame(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void mismatchedAuthDoesNotCaptureAccountId() throws Exception {
        settingsValue.gsiAuthToken = "secret-tok";
        String body =
                "{\"map\":{\"game_state\":\"DOTA_GAMERULES_STATE_GAME_IN_PROGRESS\"},"
                        + "\"auth\":{\"token\":\"WRONG\"},"
                        + "\"player\":{\"accountid\":\"96828122\"}}";

        mvc.perform(post("/gsi").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        assertThat(settingsValue.accountId).isNull();
        verify(fsm, never()).onFrame(org.mockito.ArgumentMatchers.any());
    }
}

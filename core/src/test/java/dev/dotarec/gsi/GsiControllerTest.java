package dev.dotarec.gsi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dotarec.fsm.MatchFsm;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        heartbeat = new GsiHeartbeat();
        fsm = mock(MatchFsm.class);
        GsiController controller = new GsiController(heartbeat, fsm, new ObjectMapper());
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
}

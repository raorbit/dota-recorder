package dev.dotarec.setup;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Drives the real {@link SetupController} via standalone MockMvc with mocked discovery/installer,
 * pinning the JSON contract the renderer consumes for discover / install / install-manual.
 */
class SetupControllerTest {

    private SteamPathDiscovery discovery;
    private GsiCfgInstaller installer;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        discovery = mock(SteamPathDiscovery.class);
        installer = mock(GsiCfgInstaller.class);
        mvc = MockMvcBuilders.standaloneSetup(new SetupController(discovery, installer)).build();
    }

    @Test
    void discover_found_returnsDotaDir() throws Exception {
        when(discovery.findDotaInstallDir()).thenReturn(Optional.of("C:\\dota"));

        mvc.perform(post("/setup/gsi/discover"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.dotaDir").value("C:\\dota"));
    }

    @Test
    void discover_notFound_returns200WithFoundFalse() throws Exception {
        when(discovery.findDotaInstallDir()).thenReturn(Optional.empty());

        mvc.perform(post("/setup/gsi/discover"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.dotaDir").doesNotExist());
    }

    @Test
    void install_returnsTheInstallResult() throws Exception {
        when(installer.install())
                .thenReturn(new GsiCfgInstaller.InstallResult(true, "C:\\dota", "C:\\dota\\cfg.cfg"));

        mvc.perform(post("/setup/gsi/install"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installed").value(true))
                .andExpect(jsonPath("$.cfgPath").value("C:\\dota\\cfg.cfg"));
    }

    @Test
    void installManual_returnsCfgBodyAndFilename() throws Exception {
        when(installer.manualInstructions())
                .thenReturn(
                        new GsiCfgInstaller.ManualInstructions(
                                "gamestate_integration_dotarec.cfg", "\"dota-recorder\"\n{}\n", null));

        mvc.perform(post("/setup/gsi/install-manual"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cfgFileName").value("gamestate_integration_dotarec.cfg"))
                .andExpect(jsonPath("$.cfgBody").value("\"dota-recorder\"\n{}\n"));
    }
}

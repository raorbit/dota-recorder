package dev.dotarec.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dotarec.fsm.MatchFsm;
import dev.dotarec.fsm.MatchState;
import dev.dotarec.gsi.GsiHeartbeat;
import dev.dotarec.obs.ObsDemand;
import dev.dotarec.obs.ObsHealth;
import dev.dotarec.retention.LowDiskState;
import org.junit.jupiter.api.Test;

/**
 * The {@code storage} component of the status snapshot: it mirrors {@link LowDiskState} so the
 * REST view and the pushed view carry the latest low-disk observation, and serializes with a
 * stable shape (an explicit {@code null} while the drive is healthy) for the renderer and the
 * shell's parsers.
 */
class StatusServiceTest {

    private final LowDiskState lowDiskState = new LowDiskState();
    private final StatusService service = newService(lowDiskState);

    private static StatusService newService(LowDiskState lowDiskState) {
        GsiHeartbeat gsi = mock(GsiHeartbeat.class);
        when(gsi.isAlive()).thenReturn(false);
        when(gsi.lastFrameAgoMillis()).thenReturn(Long.MAX_VALUE);
        MatchFsm fsm = mock(MatchFsm.class);
        when(fsm.getState()).thenReturn(MatchState.IDLE);
        return new StatusService(gsi, mock(ObsHealth.class), fsm, mock(ObsDemand.class), lowDiskState);
    }

    @Test
    void storageIsHealthyUntilTheSweeperRecordsALowDiskObservation() {
        assertThat(service.snapshot().storage().lowDisk()).isNull();

        LowDiskState.LowDisk low = new LowDiskState.LowDisk(1L << 30, 5L << 30, "Low disk space", 1_000L);
        lowDiskState.set(low);
        assertThat(service.snapshot().storage().lowDisk()).isEqualTo(low);

        lowDiskState.clear();
        assertThat(service.snapshot().storage().lowDisk()).isNull();
    }

    @Test
    void storageSerializesWithAnExplicitNullWhileHealthy() throws Exception {
        String json = new ObjectMapper().writeValueAsString(service.snapshot());
        assertThat(json).contains("\"storage\":{\"lowDisk\":null}");

        lowDiskState.set(new LowDiskState.LowDisk(42L, 5L << 30, "Low disk space", 7L));
        json = new ObjectMapper().writeValueAsString(service.snapshot());
        assertThat(json)
                .contains("\"freeBytes\":42")
                .contains("\"thresholdBytes\":" + (5L << 30))
                .contains("\"message\":\"Low disk space\"")
                .contains("\"observedAtMs\":7");
    }
}

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
import dev.dotarec.retention.StorageMaintenanceLock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code storage} component of the status snapshot: it mirrors {@link LowDiskState} so the
 * REST view and the pushed view carry the latest low-disk observation, and serializes with a
 * stable shape (an explicit {@code null} while the drive is healthy) for the renderer and the
 * shell's parsers. And the {@code deletes} component: the deletes {@link DeleteQueue} has pending,
 * so a window opened while one waits still shows its row as being deleted. And the {@code kept}
 * component: the frames {@link StatusWebSocket} keeps for the next window, which the shell's update
 * install waits on.
 */
class StatusServiceTest {

    private final LowDiskState lowDiskState = new LowDiskState();
    private final StorageMaintenanceLock maintenanceLock = new StorageMaintenanceLock();
    private final DeleteQueue deletes = new DeleteQueue(maintenanceLock, null, Duration.ofMillis(50));
    private final StatusWebSocket socket = mock(StatusWebSocket.class);
    private final StatusService service = newService(lowDiskState, deletes, socket);

    private static StatusService newService(
            LowDiskState lowDiskState, DeleteQueue deletes, StatusWebSocket socket) {
        GsiHeartbeat gsi = mock(GsiHeartbeat.class);
        when(gsi.isAlive()).thenReturn(false);
        when(gsi.lastFrameAgoMillis()).thenReturn(Long.MAX_VALUE);
        MatchFsm fsm = mock(MatchFsm.class);
        when(fsm.getState()).thenReturn(MatchState.IDLE);
        return new StatusService(gsi, mock(ObsHealth.class), fsm, mock(ObsDemand.class), lowDiskState,
                deletes, socket);
    }

    @AfterEach
    void stopDeleteWorker() {
        deletes.shutdown();
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

    @Test
    void deletesListTheQueuedRecordingsAndClips_untilTheyAreDone() throws Exception {
        assertThat(service.snapshot().deletes()).isEqualTo(noDeletes());

        try (HeldLock pass = HeldLock.hold(maintenanceLock)) {
            // A pass holds the lock past the wait, so these are queued. Recording 7 and clip 7 are two
            // deletes, each listed under its own kind.
            deletes.run(DeleteQueue.Kind.MATCH, 9, 1L, () -> "deleted", done -> { });
            deletes.run(DeleteQueue.Kind.MATCH, 7, 1L, () -> "deleted", done -> { });
            deletes.run(DeleteQueue.Kind.CLIP, 7, 1L, () -> true, done -> { });

            assertThat(service.snapshot().deletes())
                    .isEqualTo(new StatusSnapshot.DeleteStatus(List.of(9L, 7L), List.of(7L)));
            assertThat(new ObjectMapper().writeValueAsString(service.snapshot()))
                    .contains("\"deletes\":{\"matchIds\":[9,7],\"clipIds\":[7]}");
            pass.release();
            awaitDeletes(service, noDeletes());
        }
        assertThat(new ObjectMapper().writeValueAsString(service.snapshot()))
                .contains("\"deletes\":{\"matchIds\":[],\"clipIds\":[]}");
    }

    @Test
    void deletesListOneStillWaitingForTheLockOnItsRequest() throws Exception {
        // Not queued yet, but the core has it all the same, so a window opened now shows it too.
        DeleteQueue waiting = new DeleteQueue(maintenanceLock, null, Duration.ofSeconds(30));
        StatusService status = newService(lowDiskState, waiting, socket);
        try (HeldLock pass = HeldLock.hold(maintenanceLock)) {
            Thread request = new Thread(
                    () -> waiting.run(DeleteQueue.Kind.MATCH, 7, 1L, () -> "deleted", done -> { }));
            request.start();

            awaitDeletes(status, new StatusSnapshot.DeleteStatus(List.of(7L), List.of()));
            pass.release();
            request.join(5_000);
        } finally {
            waiting.shutdown();
        }
        assertThat(status.snapshot().deletes()).isEqualTo(noDeletes());
    }

    @Test
    void keptCountsTheFramesNoWindowHasTakenYet() throws Exception {
        assertThat(service.snapshot().kept()).isEqualTo(new StatusSnapshot.KeptStatus(0));
        assertThat(new ObjectMapper().writeValueAsString(service.snapshot()))
                .contains("\"kept\":{\"frames\":0}");

        // A queued delete failed while the window was closed to the tray, and another after it.
        when(socket.keptFrames()).thenReturn(2);
        assertThat(service.snapshot().kept()).isEqualTo(new StatusSnapshot.KeptStatus(2));
        assertThat(new ObjectMapper().writeValueAsString(service.snapshot()))
                .contains("\"kept\":{\"frames\":2}");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static StatusSnapshot.DeleteStatus noDeletes() {
        return new StatusSnapshot.DeleteStatus(List.of(), List.of());
    }

    /** Polls until {@code status} lists {@code expected}: the deletes run on other threads. */
    private static void awaitDeletes(StatusService status, StatusSnapshot.DeleteStatus expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!status.snapshot().deletes().equals(expected) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(status.snapshot().deletes()).isEqualTo(expected);
    }
}

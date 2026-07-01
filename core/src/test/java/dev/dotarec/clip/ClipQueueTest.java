package dev.dotarec.clip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.dotarec.data.ClipRepository;
import dev.dotarec.data.ClipRow;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;

/**
 * Unit tests for {@link ClipQueue}, the scheduled poller that re-dispatches pending clip rows and
 * self-heals rows wedged in {@code generating}. Uses a mocked {@link ClipRepository} / {@link
 * ClipService} so no ffmpeg or DB is involved — these assert the queue's dispatch/cutoff wiring, not
 * generation itself (that lives in {@link ClipServiceTest}).
 */
class ClipQueueTest {

    /**
     * The stale-generating cutoff must strictly exceed a single {@code generateAsync} run's true worst
     * case: THREE back-to-back {@link Clipper} process ceilings (copy + re-encode + thumbnail). If it
     * did not, a legitimately slow render would be re-pended while its original worker is still
     * finalizing, and the same sweep would dispatch a second concurrent cut to the identical output.
     */
    @Test
    void staleCutoffExceedsThreeProcessCeilings() throws Exception {
        long staleMs = readLongStatic(ClipQueue.class, "STALE_GENERATING_MS");
        long processTimeoutMin = readLongStatic(Clipper.class, "PROCESS_TIMEOUT_MINUTES");
        long threeCeilingsMs = TimeUnit.MINUTES.toMillis(3L * processTimeoutMin);

        assertThat(staleMs).isGreaterThan(threeCeilingsMs);
    }

    /**
     * Finding 2: an executor saturation on ONE per-clip dispatch (AbortPolicy throws
     * {@link TaskRejectedException}) must not abort the whole sweep batch — the remaining pending clips
     * must still be dispatched, and the rejected row stays pending for the next tick.
     */
    @Test
    void sweep_taskRejectedOnOneDispatch_stillDispatchesRemainingPending() {
        ClipRepository clips = mock(ClipRepository.class);
        ClipService service = mock(ClipService.class);

        ClipRow c1 = clip(1L);
        ClipRow c2 = clip(2L);
        ClipRow c3 = clip(3L);
        when(clips.findStaleGenerating(anyLong())).thenReturn(List.of());
        when(clips.findByStatus("pending")).thenReturn(List.of(c1, c2, c3));

        // The FIRST dispatch is rejected (queue full); the rest must still be attempted.
        doThrow(new TaskRejectedException("queue full")).when(service).generateAsync(1L);

        ClipQueue queue = new ClipQueue(clips, service);
        queue.sweep();

        // Every pending clip was dispatched exactly once despite the first throwing — the batch was not
        // aborted at the rejection.
        verify(service, times(1)).generateAsync(1L);
        verify(service, times(1)).generateAsync(2L);
        verify(service, times(1)).generateAsync(3L);
    }

    private static ClipRow clip(long id) {
        return new ClipRow(id, 100L, "manual", null, 30.0, 45.0, null,
                null, null, null, "pending", null, System.currentTimeMillis(), false);
    }

    private static long readLongStatic(Class<?> owner, String field) throws Exception {
        Field f = owner.getDeclaredField(field);
        f.setAccessible(true);
        return f.getLong(null);
    }
}

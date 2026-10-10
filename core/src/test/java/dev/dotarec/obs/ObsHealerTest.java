package dev.dotarec.obs;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dotarec.config.TimeSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ObsHealerTest {

    private static final Path OBS =
            Path.of("C:\\Users\\me\\AppData\\Local\\dota-recorder\\obs\\bin\\64bit\\obs64.exe");

    private final AtomicLong nanos = new AtomicLong(1_000_000_000_000L);
    private final List<Path> killed = new ArrayList<>();
    private int running = 1;

    private ObsHealer healer() {
        return new ObsHealer(OBS, (exe, waitMs) -> {
            killed.add(exe);
            return running;
        }, new TimeSource(nanos::get, () -> 0L));
    }

    private void advanceSeconds(long s) {
        nanos.addAndGet(s * 1_000_000_000L);
    }

    @Test
    void restart_endsOnlyThisAppsObs() {
        assertThat(healer().restartObs("test")).isTrue();
        assertThat(killed).containsExactly(OBS);
    }

    @Test
    void restarts_areSpacedOut_andCappedPerHour() {
        ObsHealer healer = healer();
        assertThat(healer.restartObs("1")).isTrue();
        assertThat(healer.restartObs("too soon")).isFalse();

        advanceSeconds(ObsHealer.MIN_INTERVAL_MS / 1000);
        assertThat(healer.restartObs("2")).isTrue();
        advanceSeconds(ObsHealer.MIN_INTERVAL_MS / 1000);
        assertThat(healer.restartObs("3")).isTrue();
        advanceSeconds(ObsHealer.MIN_INTERVAL_MS / 1000);
        assertThat(healer.restartObs("over the cap"))
                .as("a GPU that stays dead is left alone")
                .isFalse();
        assertThat(killed).hasSize(3);

        // Once the first restart ages out of the hour, healing resumes.
        advanceSeconds(ObsHealer.WINDOW_MS / 1000);
        assertThat(healer.restartObs("4")).isTrue();
    }

    @Test
    void noRunningObs_reportsNothingRestarted() {
        running = 0;
        assertThat(healer().restartObs("test")).isFalse();
    }
}

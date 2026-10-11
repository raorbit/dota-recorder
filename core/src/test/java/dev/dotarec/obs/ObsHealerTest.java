package dev.dotarec.obs;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.dotarec.config.TimeSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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
    void refusals_dontFloodTheLog() {
        // The FSM asks again on every render probe while a match waits to record, so a refusal
        // must not warn each time: "too soon" is routine, and the cap warns once per episode.
        Logger healerLog = (Logger) LoggerFactory.getLogger(ObsHealer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        healerLog.addAppender(appender);
        try {
            ObsHealer healer = healer();
            healer.restartObs("1");
            healer.restartObs("too soon");
            for (int i = 2; i <= ObsHealer.MAX_PER_WINDOW; i++) {
                advanceSeconds(ObsHealer.MIN_INTERVAL_MS / 1000);
                healer.restartObs(String.valueOf(i));
            }
            advanceSeconds(ObsHealer.MIN_INTERVAL_MS / 1000);
            for (int i = 0; i < 5; i++) {
                assertThat(healer.restartObs("over the cap")).isFalse();
                advanceSeconds(5);
            }
            assertThat(capWarnings(appender)).as("one warning per cap episode").hasSize(1);
            assertThat(appender.list)
                    .filteredOn(e -> e.getLevel() == Level.WARN)
                    .noneMatch(e -> e.getFormattedMessage().contains("ago"));

            // The window frees, healing resumes, and a later cap episode warns again.
            advanceSeconds(ObsHealer.WINDOW_MS / 1000);
            for (int i = 1; i <= ObsHealer.MAX_PER_WINDOW; i++) {
                assertThat(healer.restartObs("again " + i)).isTrue();
                advanceSeconds(ObsHealer.MIN_INTERVAL_MS / 1000);
            }
            assertThat(healer.restartObs("over the cap again")).isFalse();
            assertThat(capWarnings(appender)).hasSize(2);
        } finally {
            healerLog.detachAppender(appender);
            appender.stop();
        }
    }

    private static List<ILoggingEvent> capWarnings(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("this hour"))
                .toList();
    }

    @Test
    void noRunningObs_reportsNothingRestarted() {
        running = 0;
        assertThat(healer().restartObs("test")).isFalse();
    }
}

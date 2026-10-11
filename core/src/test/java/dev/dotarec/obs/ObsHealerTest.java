package dev.dotarec.obs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.dotarec.config.TimeSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

    @Test
    void canonical_ignoresCaseAndDotSegments(@TempDir Path dir) throws Exception {
        Path exe = Files.createFile(dir.resolve("obs64.exe"));
        String messy = dir.resolve("sub").resolve("..").resolve("OBS64.EXE").toString()
                .toUpperCase(Locale.ROOT);
        Files.createDirectory(dir.resolve("sub"));

        assertThat(ObsHealer.canonical(messy)).isEqualTo(ObsHealer.canonical(exe.toString()));
    }

    @Test
    void canonical_seesThroughAJunction(@TempDir Path dir) throws Exception {
        // %LOCALAPPDATA% moved to another drive behind a junction: our configured path and the
        // path Windows reports for the running OBS must still match.
        assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"),
                "junctions are Windows-only");
        Path real = Files.createDirectories(dir.resolve("real").resolve("bin"));
        Path exe = Files.createFile(real.resolve("obs64.exe"));
        Path link = dir.resolve("link");
        Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(),
                dir.resolve("real").toString()).redirectErrorStream(true).start();
        assumeTrue(mklink.waitFor(10, TimeUnit.SECONDS) && mklink.exitValue() == 0,
                "could not create a junction here");
        try {
            String viaJunction = link.resolve("bin").resolve("obs64.exe").toString();
            assertThat(ObsHealer.canonical(viaJunction))
                    .isEqualTo(ObsHealer.canonical(exe.toString()));
        } finally {
            // Remove the link itself, never what it points at.
            new ProcessBuilder("cmd", "/c", "rmdir", link.toString()).start()
                    .waitFor(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void canonical_ofAnInvalidPath_isNull() {
        assertThat(ObsHealer.canonical("C:/bad" + (char) 0 + "path")).isNull();
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

    @Test
    void aCallThatEndsNothing_spendsNoCooldownOrBudget() {
        ObsHealer healer = healer();
        // Electron is mid-relaunch: no OBS process to end, so nothing was restarted.
        running = 0;
        for (int i = 0; i < ObsHealer.MAX_PER_WINDOW + 1; i++) {
            assertThat(healer.restartObs("nothing running")).isFalse();
        }

        running = 1;
        assertThat(healer.restartObs("real"))
                .as("no cooldown from the no-op calls")
                .isTrue();
        for (int i = 2; i <= ObsHealer.MAX_PER_WINDOW; i++) {
            advanceSeconds(ObsHealer.MIN_INTERVAL_MS / 1000);
            assertThat(healer.restartObs("real " + i))
                    .as("the no-op calls used none of the hourly budget")
                    .isTrue();
        }
    }
}

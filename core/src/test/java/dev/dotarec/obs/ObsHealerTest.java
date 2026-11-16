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
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
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
    void aCallThatEndsNothing_backsOffTheRescan_andWarnsOncePerEpisode() {
        // OBS is connected yet its process can't be found (or can't be ended): the FSM asks on
        // every render probe, and each ask would walk the whole process table and warn.
        Logger healerLog = (Logger) LoggerFactory.getLogger(ObsHealer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        healerLog.addAppender(appender);
        try {
            ObsHealer healer = healer();
            running = 0;
            assertThat(healer.restartObs("probe 1")).isFalse();
            for (int i = 0; i < 5; i++) {
                advanceSeconds(5);
                assertThat(healer.restartObs("probe")).isFalse();
            }
            assertThat(killed).as("no rescan inside the back-off").hasSize(1);

            advanceSeconds(ObsHealer.NO_PROCESS_RETRY_MS / 1000);
            assertThat(healer.restartObs("probe after back-off")).isFalse();
            assertThat(killed).as("rescans once the back-off passes").hasSize(2);
            assertThat(noProcessWarnings(appender)).as("one warning per episode").hasSize(1);

            // A real restart ends the episode; the next no-process episode warns again.
            advanceSeconds(ObsHealer.NO_PROCESS_RETRY_MS / 1000);
            running = 1;
            assertThat(healer.restartObs("real")).isTrue();
            running = 0;
            advanceSeconds(ObsHealer.MIN_INTERVAL_MS / 1000);
            assertThat(healer.restartObs("gone again")).isFalse();
            assertThat(noProcessWarnings(appender)).hasSize(2);
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

    private static List<ILoggingEvent> noProcessWarnings(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("no process is running"))
                .toList();
    }

    private static List<ILoggingEvent> capWarnings(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("this hour"))
                .toList();
    }

    @Test
    void isObsRunning_isFalseWhileNoProcessRunsThisAppsObs(@TempDir Path dir) throws Exception {
        // A real process-table scan: nothing runs an obs64.exe that exists only in a temp dir.
        Path exe = Files.createFile(dir.resolve("obs64.exe"));
        ObsHealer healer = new ObsHealer(exe, (e, waitMs) -> 0, new TimeSource(nanos::get, () -> 0L));

        assertThat(healer.isObsRunning()).isFalse();
    }

    @Test
    void isObsRunning_isTrueWhileAProcessRunsThisAppsObs() {
        // A real process-table scan that must find a match: pointed at the executable this test JVM
        // runs, the healer has at least this process to see. A scan that misses a live OBS lets a
        // socket drop that outlasts two scheduler ticks clear the flag of an OBS still recording.
        Optional<String> self = ProcessHandle.current().info().command();
        assumeTrue(self.isPresent(), "this process's executable can't be read here");

        assertThat(healerFor(Path.of(self.get())).isObsRunning()).isTrue();
    }

    @Test
    void findObsProcess_namesTheOneRunningObs_byItsPidAndStart(
            @TempDir(cleanup = CleanupMode.NEVER) Path dir) throws Exception {
        Path exe = standInObs(dir);
        Process obs = run(exe);
        try {
            ObsHealer healer = healerFor(exe);
            ObsProcess found = healer.findObsProcess();

            assertThat(found)
                    .isEqualTo(new ObsProcess(obs.pid(), obs.info().startInstant().orElseThrow()));
            assertThat(healer.isObsRunning(found)).isTrue();

            end(obs);
            assertThat(healer.isObsRunning(found)).as("that run of OBS is gone").isFalse();
            assertThat(healer.findObsProcess()).isNull();
        } finally {
            end(obs);
            remove(exe);
        }
    }

    @Test
    void findObsProcess_withTwoRunningThisAppsObs_namesNeither(
            @TempDir(cleanup = CleanupMode.NEVER) Path dir) throws Exception {
        // The core can't tell which of the two its connection reached.
        Path exe = standInObs(dir);
        Process first = run(exe);
        Process second = run(exe);
        try {
            assertThat(healerFor(exe).findObsProcess()).isNull();
        } finally {
            end(first);
            end(second);
            remove(exe);
        }
    }

    @Test
    void isObsRunning_isFalseForItsPidOnceAProcessStartedLaterHoldsIt() {
        // Windows reuses pids: a live process with the right pid but another start instant is a
        // later process, not the run of OBS that was named.
        ProcessHandle self = ProcessHandle.current();
        Optional<Instant> started = self.info().startInstant();
        assumeTrue(started.isPresent(), "this process's start can't be read here");
        ObsHealer healer = healerFor(OBS);

        assertThat(healer.isObsRunning(new ObsProcess(self.pid(), started.get()))).isTrue();
        assertThat(healer.isObsRunning(new ObsProcess(self.pid(), started.get().minusSeconds(60))))
                .isFalse();
    }

    private ObsHealer healerFor(Path exe) {
        return new ObsHealer(exe, (e, waitMs) -> 0, new TimeSource(nanos::get, () -> 0L));
    }

    /**
     * A stand-in for this app's OBS executable in {@code dir}: a copy of Windows' ping, which runs
     * from any folder and waits as long as it is told to. Windows-only, like the app.
     */
    private static Path standInObs(Path dir) throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"),
                "the stand-in OBS is a copy of Windows' ping");
        Path ping = Path.of(System.getenv("SystemRoot"), "System32", "PING.EXE");
        assumeTrue(Files.isRegularFile(ping), "no ping.exe to stand in for OBS");
        return Files.copy(ping, dir.resolve("obs64.exe"));
    }

    /** Runs the stand-in OBS for about a minute, unless {@link #end} ends it first. */
    private static Process run(Path exe) throws Exception {
        return new ProcessBuilder(exe.toString(), "-n", "60", "127.0.0.1")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private static void end(Process p) throws InterruptedException {
        p.destroyForcibly();
        p.waitFor(10, TimeUnit.SECONDS);
    }

    /**
     * Deletes the stand-in OBS and its folder, best effort. Windows can hold an exe a moment past
     * its process's end (an antivirus scan of a new exe in the temp folder, say), which would fail
     * the test in JUnit's own cleanup of the folder; so the tests that run it leave that cleanup off
     * and try here for a few seconds, leaving the copy for the temp folder's own cleanup if it stays.
     */
    private static void remove(Path exe) throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                Files.deleteIfExists(exe);
                Files.deleteIfExists(exe.getParent());
                return;
            } catch (java.io.IOException e) {
                Thread.sleep(100);
            }
        }
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
            advanceSeconds(ObsHealer.NO_PROCESS_RETRY_MS / 1000);
        }

        running = 1;
        assertThat(healer.restartObs("real"))
                .as("no restart cooldown from the no-op calls")
                .isTrue();
        for (int i = 2; i <= ObsHealer.MAX_PER_WINDOW; i++) {
            advanceSeconds(ObsHealer.MIN_INTERVAL_MS / 1000);
            assertThat(healer.restartObs("real " + i))
                    .as("the no-op calls used none of the hourly budget")
                    .isTrue();
        }
    }
}

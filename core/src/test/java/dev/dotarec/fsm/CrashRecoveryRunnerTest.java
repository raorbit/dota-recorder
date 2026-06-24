package dev.dotarec.fsm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dotarec.config.AppPaths;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.data.MarkerRepository;
import dev.dotarec.data.MatchRepository;
import dev.dotarec.data.MatchRepository.NewMatch;
import dev.dotarec.data.MatchSummary;
import dev.dotarec.data.PauseRepository;
import dev.dotarec.data.PauseSpan;
import dev.dotarec.data.RecordingSessionRepository;
import dev.dotarec.data.RecordingSessionRepository.RecordingEvent;
import dev.dotarec.data.RecordingSessionRepository.RecordingSessionRow;
import dev.dotarec.data.TestDb;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrashRecoveryRunnerTest {

    private DataSource ds;
    private RecordingSessionRepository journal;
    private MatchRepository matches;
    private MarkerRepository markers;
    private PauseRepository pauses;
    private CrashRecoveryRunner runner;
    private Path dir;
    private Path videoDir;
    private SettingsStore settings;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        dir = tmp;
        ds = TestDb.migrated(tmp);
        journal = new RecordingSessionRepository(ds);
        matches = new MatchRepository(ds);
        markers = new MarkerRepository(ds);
        pauses = new PauseRepository(ds);
        videoDir = Files.createDirectories(tmp.resolve("video"));
        settings =
                new SettingsStore(
                        new AppPaths(tmp.resolve("data").toString(), tmp.resolve("obs").toString()));
        settings.update(
                s -> {
                    s.videoDir = videoDir.toString();
                    return s;
                });
        runner =
                new CrashRecoveryRunner(
                        journal, matches, markers, pauses, ds, new ObjectMapper(), settings);
    }

    @Test
    void recoversUnfinishedJournalIntoMatchAndDeletesJournal() throws Exception {
        Path video = dir.resolve("recovered.mp4");
        Files.writeString(video, "recovered bytes");

        journal.open(
                new RecordingSessionRow(
                        "session-1",
                        "surrogate-1",
                        "stopping",
                        99L,
                        "npc_dota_hero_puck",
                        1_000L,
                        1_000L,
                        6_000L,
                        "DOTA_GAMERULES_STATE_POST_GAME",
                        4,
                        2,
                        10,
                        video.toString(),
                        "D:/thumb.jpg",
                        900L,
                        6_000L));
        journal.appendEvent(
                "session-1",
                new RecordingEvent(
                        "marker",
                        2_000L,
                        120,
                        """
                        {"type":"kill","videoOffsetS":2.5,"gameClock":120,"label":"Kill","source":"gsi"}
                        """,
                        2_001L));
        journal.appendEvent("session-1", new RecordingEvent("pause_open", 3_000L, null, null, 3_001L));
        journal.appendEvent("session-1", new RecordingEvent("pause_close", 4_500L, null, null, 4_501L));

        runner.run(null);

        assertThat(journal.findUnfinished()).isEmpty();
        assertThat(journal.findEvents("session-1")).isEmpty();

        List<MatchSummary> rows = matches.findAll();
        assertThat(rows).hasSize(1);
        MatchSummary match = rows.get(0);
        assertThat(match.dotaMatchId()).isEqualTo(99L);
        assertThat(match.hero()).isEqualTo("npc_dota_hero_puck");
        assertThat(match.kills()).isEqualTo(4);
        assertThat(match.deaths()).isEqualTo(2);
        assertThat(match.assists()).isEqualTo(10);
        assertThat(match.videoPath()).isEqualTo(video.toString());
        assertThat(match.fileSizeBytes()).isEqualTo(Files.size(video));
        assertThat(match.durationS()).isEqualTo(5);
        assertThat(match.recordStartedWallMs()).isEqualTo(1_000L);

        assertThat(markers.findByMatchId(match.id()))
                .singleElement()
                .satisfies(
                        marker -> {
                            assertThat(marker.type()).isEqualTo("kill");
                            assertThat(marker.videoOffsetS()).isEqualTo(2.5);
                            assertThat(marker.gameClock()).isEqualTo(120);
                            assertThat(marker.label()).isEqualTo("Kill");
                            assertThat(marker.source()).isEqualTo("gsi");
                        });

        assertThat(pauses.findByMatchId(match.id()))
                .singleElement()
                .satisfies(
                        span -> {
                            assertThat(span.startWall()).isEqualTo(3_000L);
                            assertThat(span.endWall()).isEqualTo(4_500L);
                        });
    }

    @Test
    void openPauseIsClosedToSessionUpdatedAtDuringRecovery() {
        journal.open(
                new RecordingSessionRow(
                        "session-2",
                        "surrogate-2",
                        "recording",
                        null,
                        "npc_dota_hero_lina",
                        1_000L,
                        1_000L,
                        8_000L,
                        "DOTA_GAMERULES_STATE_GAME_IN_PROGRESS",
                        1,
                        1,
                        1,
                        null,
                        null,
                        900L,
                        8_000L));
        journal.appendEvent("session-2", new RecordingEvent("pause_open", 5_000L, null, null, 5_001L));

        runner.run(null);

        MatchSummary match = matches.findAll().get(0);
        List<PauseSpan> recoveredPauses = pauses.findByMatchId(match.id());
        assertThat(recoveredPauses).singleElement()
                .satisfies(span -> assertThat(span.endWall()).isEqualTo(8_000L));
    }

    @Test
    void importsUnreferencedMp4FilesAsGsiOnlyRows() throws Exception {
        Path referenced = videoDir.resolve("known.mp4");
        Path orphan = videoDir.resolve("orphan.mp4");
        Path ignoredThumb = Files.createDirectories(videoDir.resolve("thumbs")).resolve("orphan.jpg");
        Files.writeString(referenced, "known");
        Files.writeString(orphan, "orphan bytes");
        Files.writeString(ignoredThumb, "not a recording");
        matches.insert(
                new NewMatch(
                        null,
                        "match",
                        "pending",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        1_000L,
                        referenced.toString(),
                        null,
                        Files.size(referenced),
                        false,
                        1_000L,
                        null));

        runner.run(null);

        assertThat(matches.findAll())
                .filteredOn(row -> orphan.toString().equals(row.videoPath()))
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.enrichmentState()).isEqualTo("gsi_only");
                            assertThat(row.fileSizeBytes()).isEqualTo(Files.size(orphan));
                            assertThat(row.playedAt()).isNotNull();
                        });
        assertThat(matches.findAll())
                .filteredOn(row -> referenced.toString().equals(row.videoPath()))
                .hasSize(1);
    }

    @Test
    void recoveryDropsStaleJournalWhenDotaMatchAlreadyFinalized() {
        // First recovery finalizes Dota match 99 into a matches row (UNIQUE dota_match_id).
        journal.open(sessionRow("session-a", 99L));
        runner.run(null);
        assertThat(matches.findAll())
                .filteredOn(m -> Long.valueOf(99L).equals(m.dotaMatchId()))
                .hasSize(1);

        // A second stranded journal row references the same, now-taken, Dota match id. Re-inserting
        // would hit the UNIQUE constraint, roll back, and replay every boot; recovery must instead
        // drop the stale row.
        journal.open(sessionRow("session-b", 99L));
        runner.run(null);

        assertThat(journal.findUnfinished()).isEmpty();
        assertThat(matches.findAll())
                .filteredOn(m -> Long.valueOf(99L).equals(m.dotaMatchId()))
                .hasSize(1);
    }

    @Test
    void malformedVideoPathInDbDoesNotCrashOrphanScan() throws Exception {
        Path orphan = videoDir.resolve("orphan.mp4");
        Files.writeString(orphan, "orphan bytes");

        // A journaled session whose video_path is not a parseable filesystem path (illegal Windows
        // path characters), mimicking a corrupt/hand-edited row. recover() stores it, then the orphan
        // scan walks referenced paths -- which must skip the bad one rather than throw
        // InvalidPathException out of the ApplicationRunner at boot.
        journal.open(
                new RecordingSessionRow(
                        "session-bad",
                        "surrogate-bad",
                        "stopping",
                        7L,
                        "npc_dota_hero_lina",
                        1_000L,
                        1_000L,
                        6_000L,
                        "DOTA_GAMERULES_STATE_POST_GAME",
                        0,
                        0,
                        0,
                        "Z:\\bad<>name.mp4",
                        null,
                        900L,
                        6_000L));

        runner.run(null); // must not throw

        assertThat(matches.findAll())
                .filteredOn(row -> orphan.toString().equals(row.videoPath()))
                .singleElement()
                .satisfies(row -> assertThat(row.enrichmentState()).isEqualTo("gsi_only"));
    }

    @Test
    void recoveryLinksOrphanVideoInsteadOfSplittingIntoTwoRows() throws Exception {
        // Crash mid-recording: the journal never got the video_path (OBS reveals it only on
        // StopRecord). The leftover .mp4 must attach to the recovered metadata/markers row rather
        // than import as a separate, detached gsi_only row.
        Path video = videoDir.resolve("crashed.mp4");
        Files.writeString(video, "partial recording bytes");
        Files.setLastModifiedTime(
                video, java.nio.file.attribute.FileTime.fromMillis(5_000L)); // within session window

        journal.open(
                new RecordingSessionRow(
                        "session-crash",
                        "surrogate-crash",
                        "recording",
                        1234L,
                        "npc_dota_hero_puck",
                        1_000L,
                        1_000L,
                        6_000L,
                        "DOTA_GAMERULES_STATE_GAME_IN_PROGRESS",
                        5,
                        1,
                        7,
                        null,
                        null,
                        900L,
                        6_000L));
        journal.appendEvent(
                "session-crash",
                new RecordingEvent(
                        "marker",
                        2_000L,
                        60,
                        "{\"type\":\"death\",\"videoOffsetS\":1.0,\"gameClock\":60,\"source\":\"gsi\"}",
                        2_001L));

        runner.run(null);

        List<MatchSummary> rows = matches.findAll();
        assertThat(rows).hasSize(1); // linked, not split into two
        MatchSummary match = rows.get(0);
        assertThat(match.videoPath()).isEqualTo(video.toString());
        assertThat(match.dotaMatchId()).isEqualTo(1234L);
        assertThat(match.fileSizeBytes()).isEqualTo(Files.size(video));
        assertThat(markers.findByMatchId(match.id())).hasSize(1);
        assertThat(journal.findUnfinished()).isEmpty();
    }

    private RecordingSessionRow sessionRow(String id, Long dotaMatchId) {
        return new RecordingSessionRow(
                id,
                "surrogate-" + id,
                "stopping",
                dotaMatchId,
                "npc_dota_hero_puck",
                1_000L,
                1_000L,
                6_000L,
                "DOTA_GAMERULES_STATE_POST_GAME",
                1,
                1,
                1,
                null,
                null,
                900L,
                6_000L);
    }
}

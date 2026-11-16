package dev.dotarec.fsm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dotarec.config.AppPaths;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.data.ClipRepository;
import dev.dotarec.data.ClipRow;
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
import dev.dotarec.retention.StorageMaintenanceLock;
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
    private ClipRepository clips;
    private MarkerRepository markers;
    private PauseRepository pauses;
    private CrashRecoveryRunner runner;
    private Path dir;
    private Path videoDir;
    private SettingsStore settings;
    private StorageMaintenanceLock maintenanceLock;
    private AppPaths paths;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        dir = tmp;
        ds = TestDb.migrated(tmp);
        journal = new RecordingSessionRepository(ds);
        matches = new MatchRepository(ds);
        clips = new ClipRepository(ds);
        markers = new MarkerRepository(ds);
        pauses = new PauseRepository(ds);
        paths = new AppPaths(tmp.resolve("data").toString(), tmp.resolve("obs").toString());
        settings = new SettingsStore(paths);
        // A recording folder the recorder has had since before any file in it: its adoption floor
        // admits every file, so the tests below see only the other gates.
        videoDir = chooseVideoDir("video");
        seedAdoptionFloor(videoDir, 0L);
        maintenanceLock = new StorageMaintenanceLock();
        runner = newRunner();
    }

    private CrashRecoveryRunner newRunner() {
        return new CrashRecoveryRunner(
                journal, matches, clips, markers, pauses, ds, new ObjectMapper(), settings,
                maintenanceLock, paths);
    }

    /** A runner whose recovery transactions can't get a connection, so each one rolls back. */
    private CrashRecoveryRunner newRunnerWhoseRecoveryFails() {
        DataSource locked =
                (DataSource) java.lang.reflect.Proxy.newProxyInstance(
                        DataSource.class.getClassLoader(),
                        new Class<?>[] {DataSource.class},
                        (proxy, method, args) -> {
                            throw new java.sql.SQLException("database is locked");
                        });
        return new CrashRecoveryRunner(
                journal, matches, clips, markers, pauses, locked, new ObjectMapper(), settings,
                maintenanceLock, paths);
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
        // Orphans must carry a recorder-produced name (OBS's default FilenameFormatting) to be adopted.
        Path orphan = videoDir.resolve("2026-06-28 14-30-15.mp4");
        Path ignoredThumb = Files.createDirectories(videoDir.resolve("thumbs")).resolve("orphan.jpg");
        Files.writeString(referenced, "known");
        Files.writeString(orphan, "orphan bytes");
        Files.writeString(ignoredThumb, "not a recording");
        makeStale(orphan); // a genuine crash orphan is stale; a fresh mtime is skipped as maybe-live
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
    void importsUnreferencedMkvAndMovFilesAsGsiOnlyRows() throws Exception {
        // A crash mid-record on a non-mp4 container (RecFormat2=mkv/mov) leaves an orphan file. The
        // scan recognizes the recording-extension allow-list, not just .mp4, so these are adopted.
        Path orphanMkv = videoDir.resolve("2026-06-28 14-30-15.mkv");
        Path orphanMov = videoDir.resolve("2026-06-28 15-40-16.mov");
        Files.writeString(orphanMkv, "mkv bytes");
        Files.writeString(orphanMov, "mov bytes");
        makeStale(orphanMkv);
        makeStale(orphanMov);

        runner.run(null);

        assertThat(matches.findAll())
                .filteredOn(row -> orphanMkv.toString().equals(row.videoPath()))
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.enrichmentState()).isEqualTo("gsi_only");
                            assertThat(row.fileSizeBytes()).isEqualTo(Files.size(orphanMkv));
                            assertThat(row.playedAt()).isNotNull();
                        });
        assertThat(matches.findAll())
                .filteredOn(row -> orphanMov.toString().equals(row.videoPath()))
                .singleElement()
                .satisfies(row -> assertThat(row.enrichmentState()).isEqualTo("gsi_only"));
    }

    @Test
    void freshMtimeOrphanIsSkippedButStaleOneIsImported() throws Exception {
        // Recovery races Tomcat/GSI ingest + the OBS scheduler at boot: a recording can arm during the
        // scan, leaving OBS actively writing an .mp4 whose journal row has no path yet (looks
        // unreferenced). Adopting that live file would steal it from MatchFsm and later duplicate it. A
        // file being written by a live recorder has a FRESH mtime; a genuine crash orphan is STALE. The
        // scan must skip the fresh one and still import the stale one.
        Path live = videoDir.resolve("2026-06-28 20-00-00.mp4");
        Path crashed = videoDir.resolve("2026-06-28 14-30-15.mp4");
        Files.writeString(live, "still being written");
        Files.writeString(crashed, "crash orphan bytes");
        // live keeps its just-now mtime (Files.writeString stamps ~now); crashed is aged out.
        makeStale(crashed);

        runner.run(null);

        // The fresh (maybe-live) file is NOT adopted...
        assertThat(matches.findAll())
                .noneMatch(row -> live.toString().equals(row.videoPath()));
        assertThat(Files.exists(live)).isTrue(); // and it is left untouched on disk
        // ...while the stale crash orphan IS imported.
        assertThat(matches.findAll())
                .filteredOn(row -> crashed.toString().equals(row.videoPath()))
                .singleElement()
                .satisfies(row -> assertThat(row.enrichmentState()).isEqualTo("gsi_only"));
    }

    @Test
    void foreignNamedFileInVideoDirIsLeftUntouched() throws Exception {
        // videoDir is user-configurable: pointing it at a folder of personal videos must never adopt
        // them (an adopted row hands the file to retention's oldest-first delete). Only basenames this
        // recorder produces (OBS's "%CCYY-%MM-%DD %hh-%mm-%ss" names, the archiver's/clipper's prefixed
        // forms) are adopted; anything else is left completely untouched.
        Path foreign = videoDir.resolve("family-vacation.mp4");
        Files.writeString(foreign, "the user's own video");
        makeStale(foreign); // stale enough to pass the quiescence guard — provenance alone must block

        runner.run(null);

        assertThat(matches.findAll()).isEmpty();
        assertThat(Files.exists(foreign)).isTrue();
    }

    @Test
    void linkOrphanVideoNeverCapturesAFileWrittenByTheCurrentBoot() throws Exception {
        // Double-fault shape: a crashed null-path journal row whose ±5-min link window reaches into
        // THIS boot, while OBS (started by the current boot) is already writing a fresh recording.
        // The freshness guard must keep the old journal from capturing the live file: anything last
        // modified at/after this process started is excluded from linking.
        long now = System.currentTimeMillis();
        Path live = videoDir.resolve("2026-07-10 12-00-00.mp4");
        Files.writeString(live, "the current boot's in-flight recording"); // mtime ~now, after boot

        journal.open(
                new RecordingSessionRow(
                        "session-live",
                        "surrogate-live",
                        "recording",
                        4321L,
                        "npc_dota_hero_puck",
                        now - 60_000L,
                        now - 60_000L,
                        now,
                        "DOTA_GAMERULES_STATE_GAME_IN_PROGRESS",
                        1,
                        1,
                        1,
                        null,
                        null,
                        now - 61_000L,
                        now));

        runner.run(null);

        // The session is recovered WITHOUT a video (better a pathless row than stealing the live file)...
        assertThat(matches.findAll())
                .filteredOn(row -> Long.valueOf(4321L).equals(row.dotaMatchId()))
                .singleElement()
                .satisfies(row -> assertThat(row.videoPath()).isNull());
        // ...and the fresh file is neither linked nor adopted (quiescence) — left for MatchFsm.
        assertThat(matches.findAll()).noneMatch(row -> live.toString().equals(row.videoPath()));
        assertThat(Files.exists(live)).isTrue();
    }

    @Test
    void recoveryKeepsAStrandedPartOfAnAlreadyRecordedMatch_asItsOwnLinkedRow() {
        // First recovery finalizes Dota match 99 into a matches row.
        journal.open(sessionRow("session-a", 99L));
        runner.run(null);
        assertThat(matches.findAll())
                .filteredOn(m -> Long.valueOf(99L).equals(m.dotaMatchId()))
                .hasSize(1);

        // A second stranded journal row for the same Dota match is another part of a split match:
        // finalize deletes a journal in the same transaction as its insert, so a stranded one is
        // never a duplicate. It is recovered as its own row, linked and enriched like the first.
        journal.open(sessionRow("session-b", 99L));
        runner.run(null);

        assertThat(journal.findUnfinished()).isEmpty();
        assertThat(matches.findAll())
                .filteredOn(m -> Long.valueOf(99L).equals(m.dotaMatchId()))
                .hasSize(2)
                .allSatisfy(m -> assertThat(m.enrichmentState()).isEqualTo("pending"));
    }

    @Test
    void malformedVideoPathInDbDoesNotCrashOrphanScan() throws Exception {
        Path orphan = videoDir.resolve("2026-06-28 14-30-15.mp4");
        Files.writeString(orphan, "orphan bytes");
        makeStale(orphan);

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
        Path video = videoDir.resolve("2026-06-28 14-30-15.mp4");
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

    @Test
    void relinksStrandedArchiveFileToOriginalRowWhenItsVideoIsMissing() throws Exception {
        Path archiveDir = configureArchive("hdd");
        // The row's recorded path no longer exists on disk: an interrupted cross-store move consumed the
        // source (atomic rename) before the repoint committed, so the row points at nothing. Re-linking
        // requires attribution — the remainder after "<id>-" must equal the row's recorded basename and
        // the on-disk size must equal the row's recorded size (genuine residue is the same file).
        String recordingBytes = "the real recording bytes";
        long id =
                insertMatch(
                        videoDir.resolve("gone.mp4").toString(), null, (long) recordingBytes.length());
        // The relocated copy survived on the archive drive under its id-prefixed name, unreferenced.
        Path stranded = archiveDir.resolve(id + "-gone.mp4");
        Files.writeString(stranded, recordingBytes);

        runner.run(null);

        // No duplicate row: the ORIGINAL row is re-linked to the recovered archive copy.
        assertThat(matches.findAll()).hasSize(1);
        assertThat(matches.findById(id).orElseThrow().videoPath()).isEqualTo(stranded.toString());
        assertThat(Files.exists(stranded)).isTrue();
    }

    @Test
    void relinkAlsoRecoversTheArchivedThumbnail() throws Exception {
        Path archiveDir = configureArchive("hdd");
        String recordingBytes = "bytes";
        long id =
                insertMatch(
                        videoDir.resolve("gone.mp4").toString(), null, (long) recordingBytes.length());
        Path stranded = archiveDir.resolve(id + "-gone.mp4");
        Files.writeString(stranded, recordingBytes);
        // The move also relocated the thumbnail under <archive>/thumbs/<id>-...; recovery finds it.
        Path strandedThumb =
                Files.createDirectories(archiveDir.resolve("thumbs")).resolve(id + "-gone.jpg");
        Files.writeString(strandedThumb, "thumb");

        runner.run(null);

        MatchSummary row = matches.findById(id).orElseThrow();
        assertThat(row.videoPath()).isEqualTo(stranded.toString());
        assertThat(row.thumbPath()).isEqualTo(strandedThumb.toString());
    }

    @Test
    void relinkNeverTakesAnInterruptedThumbnailCopy() throws Exception {
        Path archiveDir = configureArchive("hdd");
        String recordingBytes = "bytes";
        long id =
                insertMatch(
                        videoDir.resolve("gone.mp4").toString(), null, (long) recordingBytes.length());
        Path stranded = archiveDir.resolve(id + "-gone.mp4");
        Files.writeString(stranded, recordingBytes);
        // An archive move cut short leaves its thumbnail copy as "<id>-gone.jpg.partial", and here it
        // is the only id-prefixed thumb: re-linking the row to it would show a truncated image.
        Path partialThumb =
                Files.createDirectories(archiveDir.resolve("thumbs")).resolve(id + "-gone.jpg.partial");
        Files.writeString(partialThumb, "cut");

        runner.run(null);

        MatchSummary row = matches.findById(id).orElseThrow();
        assertThat(row.videoPath()).isEqualTo(stranded.toString());
        assertThat(row.thumbPath()).isNotEqualTo(partialThumb.toString());
    }

    @Test
    void relinkNeverTakesAThumbnailCopyCutShort() throws Exception {
        Path archiveDir = configureArchive("hdd");
        String recordingBytes = "bytes";
        long id =
                insertMatch(
                        videoDir.resolve("gone.mp4").toString(), null, (long) recordingBytes.length());
        Path stranded = archiveDir.resolve(id + "-gone.mp4");
        Files.writeString(stranded, recordingBytes);
        // An older archiver's thumbnail copy cut short sits under the final name, full size and all
        // zeros: re-linking the row to it would show a blank image.
        Path cutShort = Files.createDirectories(archiveDir.resolve("thumbs")).resolve(id + "-gone.jpg");
        Files.write(cutShort, new byte[4096]);

        runner.run(null);

        MatchSummary row = matches.findById(id).orElseThrow();
        assertThat(row.videoPath()).isEqualTo(stranded.toString());
        assertThat(row.thumbPath()).isNotEqualTo(cutShort.toString());
    }

    @Test
    void doesNotRelinkIdPrefixedArchiveFileWithMismatchedBasename() throws Exception {
        Path archiveDir = configureArchive("hdd");
        // The row LOST its video (missing path), which used to make ANY "<id>-..." file its new VOD. A
        // user-placed "12-family.mp4" must not be captured that way: re-linking requires the remainder
        // after the prefix to equal the row's recorded basename.
        long id = insertMatch(videoDir.resolve("gone.mp4").toString(), null, 2_048L);
        Path foreign = archiveDir.resolve(id + "-family.mp4");
        Files.writeString(foreign, "the user's own video");
        makeStale(foreign);

        runner.run(null);

        // Not re-linked, not adopted (foreign name), not deleted.
        assertThat(matches.findById(id).orElseThrow().videoPath())
                .isEqualTo(videoDir.resolve("gone.mp4").toString());
        assertThat(matches.findAll()).hasSize(1);
        assertThat(Files.exists(foreign)).isTrue();
    }

    @Test
    void doesNotRelinkIdPrefixedArchiveFileWhoseSizeMismatchesTheStoredSize() throws Exception {
        Path archiveDir = configureArchive("hdd");
        // The remainder matches the lost video's basename, but the on-disk size differs from the size
        // the row recorded at finalize — not provably the row's own bytes, so no re-link (and the
        // foreign-shaped name keeps it from being adopted either).
        long id = insertMatch(videoDir.resolve("gone.mp4").toString(), null, 2_048L);
        Path wrongSize = archiveDir.resolve(id + "-gone.mp4");
        Files.writeString(wrongSize, "not 2048 bytes");
        makeStale(wrongSize);

        runner.run(null);

        assertThat(matches.findById(id).orElseThrow().videoPath())
                .isEqualTo(videoDir.resolve("gone.mp4").toString());
        assertThat(matches.findAll()).hasSize(1);
        assertThat(Files.exists(wrongSize)).isTrue();
    }

    @Test
    void doesNotRelinkWhenRowHasNoRecordedBasenameToAttribute() throws Exception {
        Path archiveDir = configureArchive("hdd");
        // A row whose video_path is already NULL (e.g. swept) has no basename/size to attribute an
        // "<id>-" file with, so it must NOT be re-linked; the provenance rule then decides adoption —
        // this one is recorder-named, so it is imported as a STANDALONE row, never as match <id>'s VOD.
        long id = insertMatch(null, null, null);
        Path stray = archiveDir.resolve(id + "-2026-06-28 14-30-15.mp4");
        Files.writeString(stray, "an old recording");
        makeStale(stray);

        runner.run(null);

        assertThat(matches.findById(id).orElseThrow().videoPath()).isNull();
        assertThat(matches.findAll())
                .filteredOn(row -> stray.toString().equals(row.videoPath()))
                .singleElement()
                .satisfies(row -> assertThat(row.enrichmentState()).isEqualTo("gsi_only"));
    }

    @Test
    void dropsRedundantArchiveLeftoverWhenRowAlreadyIntact() throws Exception {
        Path archiveDir = configureArchive("hdd");
        // The row still points at an existing file on the active drive (the move copied across but
        // crashed before repointing, so the original is intact) ...
        Path keep = videoDir.resolve("keep.mp4");
        Files.writeString(keep, "intact recording");
        long id = insertMatch(keep.toString(), null, Files.size(keep));
        // ... while a redundant copy from the interrupted move sits on the archive drive. Genuine move
        // residue is a full, byte-identical duplicate: same name after the "<id>-" prefix AND same size
        // as the intact copy (the archiver verifies copy size == source size). Both must hold to delete.
        Path leftover = archiveDir.resolve(id + "-keep.mp4");
        Files.writeString(leftover, "intact recording");

        runner.run(null);

        // The redundant leftover is removed (disk reclaimed); the row is untouched; no duplicate row.
        assertThat(Files.exists(leftover)).isFalse();
        assertThat(matches.findAll()).hasSize(1);
        assertThat(matches.findById(id).orElseThrow().videoPath()).isEqualTo(keep.toString());
    }

    @Test
    void doesNotDeleteIdPrefixedArchiveFileThatIsNotGenuineResidue() throws Exception {
        Path archiveDir = configureArchive("hdd");
        // The row is intact on the active drive under a specific name...
        Path keep = videoDir.resolve("keep.mp4");
        Files.writeString(keep, "intact recording");
        long id = insertMatch(keep.toString(), null, Files.size(keep));
        // ...but a user-placed file merely COLLIDES with match <id>'s numeric prefix: its remainder is a
        // different name ("grand-final.mp4", not "keep.mp4") and its size differs. It is NOT this match's
        // move residue, and its basename isn't a recorder-produced shape either, so it must be left
        // completely untouched — never deleted, never adopted into retention's delete authority.
        Path notResidue = archiveDir.resolve(id + "-grand-final.mp4");
        Files.writeString(notResidue, "a completely unrelated user recording");
        makeStale(notResidue);

        runner.run(null);

        // The unattributable file survives untouched — no delete, no adopted row.
        assertThat(Files.exists(notResidue)).isTrue();
        assertThat(matches.findAll()).hasSize(1);
        // The intact row is untouched.
        assertThat(matches.findById(id).orElseThrow().videoPath()).isEqualTo(keep.toString());
    }

    @Test
    void doesNotDeleteIdPrefixedArchiveFileWithMatchingNameButDifferentSize() throws Exception {
        Path archiveDir = configureArchive("hdd");
        // A trickier collision: the remainder DOES match the intact row's name, but the on-disk sizes
        // differ (e.g. a truncated partial copy, or a coincidental same-named unrelated file). Without
        // the size check this would be wrongly deleted; the attribution guard keeps it, and the
        // provenance gate ("keep.mp4" isn't a recorder-produced shape) declines to adopt it.
        Path keep = videoDir.resolve("keep.mp4");
        Files.writeString(keep, "intact recording");
        long id = insertMatch(keep.toString(), null, Files.size(keep));
        Path sameNameDifferentBytes = archiveDir.resolve(id + "-keep.mp4");
        Files.writeString(sameNameDifferentBytes, "a longer, different set of bytes entirely");
        makeStale(sameNameDifferentBytes);

        runner.run(null);

        // Not provably residue (size mismatch) -> untouched: not deleted, not adopted.
        assertThat(Files.exists(sameNameDifferentBytes)).isTrue();
        assertThat(matches.findAll()).hasSize(1);
        assertThat(matches.findById(id).orElseThrow().videoPath()).isEqualTo(keep.toString());
    }

    @Test
    void importsUnprefixedArchiveOrphanAsGsiOnlyRow() throws Exception {
        Path archiveDir = configureArchive("hdd");
        // An OBS-named recording on the archive drive with no id prefix (e.g. moved there by hand)
        // has nothing to re-link to, so it is adopted as a standalone gsi_only row.
        Path orphan = archiveDir.resolve("2026-06-28 14-30-15.mp4");
        Files.writeString(orphan, "loose recording");
        makeStale(orphan);

        runner.run(null);

        assertThat(matches.findAll())
                .filteredOn(row -> orphan.toString().equals(row.videoPath()))
                .singleElement()
                .satisfies(row -> assertThat(row.enrichmentState()).isEqualTo("gsi_only"));
    }

    @Test
    void archivedClipIsNotImportedAsMatchNorDeleted() throws Exception {
        Path archiveDir = configureArchive("hdd");
        long parentId = insertMatch(videoDir.resolve("parent.mp4").toString(), null, 4_096L);
        // A clip that was successfully relocated to the archive drive: its row points at the archived
        // copy in the archive ROOT (named clip-<clipId>-<original>). The orphan scan must treat it as
        // referenced — not adopt it as a bogus match, and not delete it as a "redundant" leftover.
        long clipId = insertClip(parentId, "placeholder");
        Path archivedClip = archiveDir.resolve("clip-" + clipId + "-parent-clip-" + clipId + ".mp4");
        Files.writeString(archivedClip, "rendered clip bytes");
        clips.updateVideoPath(clipId, archivedClip.toString(), null);

        runner.run(null);

        // No match row was created pointing at the clip file...
        assertThat(matches.findAll())
                .noneMatch(row -> archivedClip.toString().equals(row.videoPath()));
        // ...the parent match is the only match row...
        assertThat(matches.findAll()).hasSize(1);
        // ...the clip file survives and its row still points at it.
        assertThat(Files.exists(archivedClip)).isTrue();
        assertThat(clips.findById(clipId).orElseThrow().videoPath()).isEqualTo(archivedClip.toString());
    }

    @Test
    void dropsRedundantArchivedClipLeftoverWhenClipAlreadyIntact() throws Exception {
        Path archiveDir = configureArchive("hdd");
        long parentId = insertMatch(videoDir.resolve("parent.mp4").toString(), null, 4_096L);
        // The clip row still points at an existing source (interrupted move copied across but crashed
        // before repointing), so the archive-drive copy is a redundant duplicate to reclaim. Genuine
        // move residue is a full, byte-identical duplicate: same name after the "clip-<id>-" prefix
        // AND same size as the intact copy — the same attribution the match path requires.
        Path clipSource = Files.createDirectories(videoDir.resolve("clips")).resolve("1-clip-1.mp4");
        Files.writeString(clipSource, "the live clip");
        long clipId = insertClip(parentId, clipSource.toString());
        Path leftover = archiveDir.resolve("clip-" + clipId + "-1-clip-1.mp4");
        Files.writeString(leftover, "the live clip");

        runner.run(null);

        // Redundant leftover removed; the clip row is untouched; no bogus match row imported.
        assertThat(Files.exists(leftover)).isFalse();
        assertThat(clips.findById(clipId).orElseThrow().videoPath()).isEqualTo(clipSource.toString());
        assertThat(matches.findAll()).hasSize(1);
    }

    @Test
    void doesNotDeleteClipPrefixedArchiveFileThatIsNotGenuineResidue() throws Exception {
        Path archiveDir = configureArchive("hdd");
        long parentId = insertMatch(videoDir.resolve("parent.mp4").toString(), null, 4_096L);
        // The clip row is intact on the active drive under a specific name...
        Path clipSource = Files.createDirectories(videoDir.resolve("clips")).resolve("1-clip-1.mp4");
        Files.writeString(clipSource, "the live clip");
        long clipId = insertClip(parentId, clipSource.toString());
        // ...but a user-placed file merely COLLIDES with clip <id>'s prefix: its remainder is a
        // different name ("something.mp4", not "1-clip-1.mp4") and its size differs. It is NOT this
        // clip's move residue, and its basename isn't a recorder-produced shape, so it must be left
        // completely untouched — before the attribution guard, the intact-row branch deleted any
        // "clip-<id>-" file outright.
        Path notResidue = archiveDir.resolve("clip-" + clipId + "-something.mp4");
        Files.writeString(notResidue, "an unrelated user-placed recording");
        makeStale(notResidue);

        runner.run(null);

        // The unattributable file survives untouched — no delete, no adopted row.
        assertThat(Files.exists(notResidue)).isTrue();
        assertThat(matches.findAll()).hasSize(1); // only the parent match
        // The intact clip row is untouched.
        assertThat(clips.findById(clipId).orElseThrow().videoPath()).isEqualTo(clipSource.toString());
    }

    @Test
    void doesNotDeleteClipPrefixedArchiveFileWithMatchingNameButDifferentSize() throws Exception {
        Path archiveDir = configureArchive("hdd");
        long parentId = insertMatch(videoDir.resolve("parent.mp4").toString(), null, 4_096L);
        // The trickier collision: the remainder DOES match the intact clip's name, but the on-disk
        // sizes differ (a truncated partial copy, or a same-named unrelated file). Not provably
        // residue -> never deleted; its name IS a recorder-produced clip shape, so the
        // provenance-gated import still adopts it as a standalone row.
        Path clipSource = Files.createDirectories(videoDir.resolve("clips")).resolve("1-clip-1.mp4");
        Files.writeString(clipSource, "the live clip");
        long clipId = insertClip(parentId, clipSource.toString());
        Path sameNameDifferentBytes = archiveDir.resolve("clip-" + clipId + "-1-clip-1.mp4");
        Files.writeString(sameNameDifferentBytes, "a longer, different set of bytes entirely");
        makeStale(sameNameDifferentBytes);

        runner.run(null);

        assertThat(Files.exists(sameNameDifferentBytes)).isTrue();
        assertThat(matches.findAll())
                .filteredOn(row -> sameNameDifferentBytes.toString().equals(row.videoPath()))
                .singleElement()
                .satisfies(row -> assertThat(row.enrichmentState()).isEqualTo("gsi_only"));
        assertThat(clips.findById(clipId).orElseThrow().videoPath()).isEqualTo(clipSource.toString());
    }

    @Test
    void relinksStrandedArchivedClipWhenItsVideoMissing() throws Exception {
        Path archiveDir = configureArchive("hdd");
        long parentId = insertMatch(videoDir.resolve("parent.mp4").toString(), null, 4_096L);
        // The clip row points at a source that no longer exists (a same-filesystem rename consumed it
        // before the repoint committed), while the relocated copy survives on the archive drive.
        // Re-linking requires attribution — the remainder after "clip-<clipId>-" must equal the row's
        // recorded basename and the on-disk size must equal the row's recorded size.
        String clipBytes = "the real clip bytes";
        long clipId =
                insertClip(
                        parentId,
                        videoDir.resolve("clips").resolve("1-clip-1.mp4").toString(),
                        (long) clipBytes.length());
        Path stranded = archiveDir.resolve("clip-" + clipId + "-1-clip-1.mp4");
        Files.writeString(stranded, clipBytes);

        runner.run(null);

        // The ORIGINAL clip row is re-linked to the recovered copy — no duplicate, no bogus match.
        assertThat(clips.findById(clipId).orElseThrow().videoPath()).isEqualTo(stranded.toString());
        assertThat(Files.exists(stranded)).isTrue();
        assertThat(matches.findAll()).hasSize(1);
    }

    @Test
    void relinkAlsoRecoversTheArchivedClipThumbnail() throws Exception {
        Path archiveDir = configureArchive("hdd");
        long parentId = insertMatch(videoDir.resolve("parent.mp4").toString(), null, 4_096L);
        String clipBytes = "the real clip bytes";
        long clipId =
                insertClip(
                        parentId,
                        videoDir.resolve("clips").resolve("1-clip-1.mp4").toString(),
                        (long) clipBytes.length());
        Path stranded = archiveDir.resolve("clip-" + clipId + "-1-clip-1.mp4");
        Files.writeString(stranded, clipBytes);
        // The move also relocated the clip thumbnail under <archive>/thumbs/clip-<clipId>-...; recovery
        // must find it via the clip prefix (exercises recoverArchivedThumb's clip-prefix branch).
        Path strandedThumb =
                Files.createDirectories(archiveDir.resolve("thumbs"))
                        .resolve("clip-" + clipId + "-1-clip-1.jpg");
        Files.writeString(strandedThumb, "thumb");

        runner.run(null);

        ClipRow row = clips.findById(clipId).orElseThrow();
        assertThat(row.videoPath()).isEqualTo(stranded.toString());
        assertThat(row.thumbPath()).isEqualTo(strandedThumb.toString());
    }

    @Test
    void doesNotRelinkClipPrefixedArchiveFileWithMismatchedBasename() throws Exception {
        Path archiveDir = configureArchive("hdd");
        long parentId = insertMatch(videoDir.resolve("parent.mp4").toString(), null, 4_096L);
        // The clip LOST its video (missing path), which used to make ANY "clip-<id>-..." file its new
        // video. A user-placed "clip-1-whatever.mp4" must not be captured that way: re-linking requires
        // the remainder after the prefix to equal the row's recorded basename.
        String clipPath = videoDir.resolve("clips").resolve("1-clip-1.mp4").toString();
        long clipId = insertClip(parentId, clipPath, 2_048L);
        Path foreign = archiveDir.resolve("clip-" + clipId + "-whatever.mp4");
        Files.writeString(foreign, "the user's own video");
        makeStale(foreign);

        runner.run(null);

        // Not re-linked, not adopted (foreign name), not deleted.
        assertThat(clips.findById(clipId).orElseThrow().videoPath()).isEqualTo(clipPath);
        assertThat(matches.findAll()).hasSize(1); // only the parent match
        assertThat(Files.exists(foreign)).isTrue();
    }

    @Test
    void doesNotRelinkClipPrefixedArchiveFileWhoseSizeMismatchesTheStoredSize() throws Exception {
        Path archiveDir = configureArchive("hdd");
        long parentId = insertMatch(videoDir.resolve("parent.mp4").toString(), null, 4_096L);
        // The remainder matches the lost clip's basename, but the on-disk size differs from the size
        // the row recorded when the clip rendered — not provably the row's own bytes, so no re-link.
        // The name IS a recorder-produced clip shape, so the provenance-gated import still adopts it
        // as a STANDALONE row, never as clip <id>'s video.
        String clipPath = videoDir.resolve("clips").resolve("1-clip-1.mp4").toString();
        long clipId = insertClip(parentId, clipPath, 2_048L);
        Path wrongSize = archiveDir.resolve("clip-" + clipId + "-1-clip-1.mp4");
        Files.writeString(wrongSize, "not 2048 bytes");
        makeStale(wrongSize);

        runner.run(null);

        assertThat(clips.findById(clipId).orElseThrow().videoPath()).isEqualTo(clipPath);
        assertThat(Files.exists(wrongSize)).isTrue();
        assertThat(matches.findAll())
                .filteredOn(row -> wrongSize.toString().equals(row.videoPath()))
                .singleElement()
                .satisfies(row -> assertThat(row.enrichmentState()).isEqualTo("gsi_only"));
    }

    @Test
    void doesNotRelinkWhenClipRowHasNoRecordedBasenameToAttribute() throws Exception {
        Path archiveDir = configureArchive("hdd");
        long parentId = insertMatch(videoDir.resolve("parent.mp4").toString(), null, 4_096L);
        // A clip row whose video_path is NULL (never rendered, or reset by a sweep) has no
        // basename/size to attribute a "clip-<id>-" file with, so it must NOT be re-linked; the
        // provenance rule then decides adoption — this one is recorder-named, so it is imported as a
        // STANDALONE row, never as clip <id>'s video.
        long clipId = insertClip(parentId, null, null);
        Path stray = archiveDir.resolve("clip-" + clipId + "-1-clip-1.mp4");
        Files.writeString(stray, "an old clip render");
        makeStale(stray);

        runner.run(null);

        assertThat(clips.findById(clipId).orElseThrow().videoPath()).isNull();
        assertThat(Files.exists(stray)).isTrue();
        assertThat(matches.findAll())
                .filteredOn(row -> stray.toString().equals(row.videoPath()))
                .singleElement()
                .satisfies(row -> assertThat(row.enrichmentState()).isEqualTo("gsi_only"));
    }

    @Test
    void importsClipPrefixedArchiveFileAsGsiOnlyWhenNoClipRowExists() throws Exception {
        Path archiveDir = configureArchive("hdd");
        // An archived-clip-named file whose clip row no longer exists (the clip was deleted).
        // leadingClipId parses it, recoverArchivedClipLeftover finds no row and returns false, so it
        // falls through to a standalone gsi_only import rather than being deleted.
        Path orphan = archiveDir.resolve("clip-99-5-clip-99.mp4");
        Files.writeString(orphan, "loose clip residue");
        makeStale(orphan);

        runner.run(null);

        assertThat(clips.findById(99L)).isEmpty();
        assertThat(Files.exists(orphan)).isTrue(); // never deleted — we don't destroy unattributable files
        assertThat(matches.findAll())
                .filteredOn(row -> orphan.toString().equals(row.videoPath()))
                .singleElement()
                .satisfies(row -> assertThat(row.enrichmentState()).isEqualTo("gsi_only"));
    }

    @Test
    void aStrandedArchiveCopyIsRelinkedThoughItPredatesTheArchivesFloor() throws Exception {
        // The archiver keeps a recording's modification time on its copy, so a recording moved off
        // before the upgrade is older than the archive's floor. Only the import is held to the floor:
        // the re-link proves the copy is the row's own by its name and size.
        Path archive = chooseArchive("hdd");
        seedAdoptionFloor(archive, System.currentTimeMillis());
        long id = insertMatch(videoDir.resolve("2026-10-01 20-00-00.mp4").toString(), null, 15L);
        Path stranded = writeModifiedAt(archive.resolve(id + "-2026-10-01 20-00-00.mp4"), LONG_AGO_MS);
        long clipId = insertClip(id, videoDir.resolve("clips").resolve(id + "-clip-1.mp4").toString(), 15L);
        Path strandedClip = writeModifiedAt(archive.resolve("clip-" + clipId + "-" + id + "-clip-1.mp4"), LONG_AGO_MS);

        runner.run(null);

        assertThat(matches.findById(id).orElseThrow().videoPath()).isEqualTo(stranded.toString());
        assertThat(clips.findById(clipId).orElseThrow().videoPath()).isEqualTo(strandedClip.toString());
        assertThat(matches.findAll()).hasSize(1);
    }

    @Test
    void aCopyCutShortIsNeverReLinkedToTheRowThatLostItsVideo() throws Exception {
        // The recording's drive isn't back at this boot, and the archive holds an older archiver's copy
        // of it cut short: the row's name and size, but a zeroed tail, so it isn't the recording. The
        // match keeps its path for the boot its drive is back on, the clip too, and the copies stay.
        Path archive = chooseArchive("hdd");
        seedAdoptionFloor(archive, System.currentTimeMillis());
        byte[] recording = recordingOf(256 * 1024);
        Path usb = dir.resolve("usb");
        String videoPath = usb.resolve("2026-10-01 20-00-00.mp4").toString();
        long id = insertMatch(videoPath, null, (long) recording.length);
        Path copy = writeModifiedAt(
                archive.resolve(id + "-2026-10-01 20-00-00.mp4"), cutShortAt(recording, 100 * 1024), LONG_AGO_MS);
        String clipPath = usb.resolve("clips").resolve(id + "-clip-1.mp4").toString();
        long clipId = insertClip(id, clipPath, (long) recording.length);
        Path clipCopy = writeModifiedAt(
                archive.resolve("clip-" + clipId + "-" + id + "-clip-1.mp4"), new byte[recording.length], LONG_AGO_MS);

        runner.run(null);

        assertThat(matches.findAll())
                .singleElement()
                .satisfies(row -> assertThat(row.videoPath()).isEqualTo(videoPath));
        assertThat(clips.findById(clipId).orElseThrow().videoPath()).isEqualTo(clipPath);
        assertThat(copy).exists();
        assertThat(clipCopy).exists();
    }

    @Test
    void aRecordingThatEndsInZerosIsReLinkedOnceItsFolderShowsItsFileIsGone() throws Exception {
        // A recording an OS crash cut short, so it ends in zeros, renamed onto an archive on the same
        // drive just before the core died, so its row was never repointed. Its folder is there
        // without it, so no file of its own can come back: the row is re-linked to it rather than
        // losing its video to the sweeper, and no second row takes it.
        Path archive = configureArchive("hdd");
        byte[] crashed = cutShortAt(recordingOf(256 * 1024), 180 * 1024);
        String videoPath = videoDir.resolve("2026-10-04 20-00-00.mp4").toString();
        long id = insertMatch(videoPath, null, (long) crashed.length);
        Path moved = writeModifiedAt(archive.resolve(id + "-2026-10-04 20-00-00.mp4"), crashed, LONG_AGO_MS);

        runner.run(null);

        assertThat(matches.findAll())
                .singleElement()
                .satisfies(row -> assertThat(row.videoPath()).isEqualTo(moved.toString()));
    }

    @Test
    void genuineMoveResidueIsDeletedThoughItPredatesTheArchivesFloor() throws Exception {
        Path archive = chooseArchive("hdd");
        seedAdoptionFloor(archive, System.currentTimeMillis());
        Path intact = writeModifiedAt(videoDir.resolve("2026-10-01 20-00-00.mp4"), LONG_AGO_MS);
        long id = insertMatch(intact.toString(), null, Files.size(intact));
        Path residue = writeModifiedAt(archive.resolve(id + "-2026-10-01 20-00-00.mp4"), LONG_AGO_MS);
        Path clip = writeModifiedAt(
                Files.createDirectories(videoDir.resolve("clips")).resolve(id + "-clip-1.mp4"), LONG_AGO_MS);
        long clipId = insertClip(id, clip.toString(), Files.size(clip));
        Path clipResidue = writeModifiedAt(archive.resolve("clip-" + clipId + "-" + id + "-clip-1.mp4"), LONG_AGO_MS);

        runner.run(null);

        assertThat(residue).doesNotExist();
        assertThat(clipResidue).doesNotExist();
        assertThat(intact).exists();
        assertThat(clip).exists();
        assertThat(matches.findAll()).hasSize(1);
    }

    @Test
    void aSourceTheArchiverLeftBehindIsRemovedWhateverItsAge() throws Exception {
        // The archiver copied a recording from before the video folder's floor to the archive drive and
        // repointed its row, but deleting the source failed (a player held it open). The floor keeps
        // that source from ever being adopted, and no row counts it against the cap, so the boot
        // removes it, and its thumbnail's left-behind source too.
        Path archive = configureArchive("hdd");
        seedAdoptionFloor(videoDir, System.currentTimeMillis());
        long id = insertMatch("placeholder", null, null);
        Path copy = writeModifiedAt(archive.resolve(id + "-2026-10-01 20-00-00.mp4"), LONG_AGO_MS);
        Path thumbCopy = writeModifiedAt(
                Files.createDirectories(archive.resolve("thumbs")).resolve(id + "-surrogate-1.jpg"), LONG_AGO_MS);
        matches.updateVideoPath(id, copy.toString(), thumbCopy.toString());
        Path source = writeModifiedAt(videoDir.resolve("2026-10-01 20-00-00.mp4"), LONG_AGO_MS);
        Path thumbSource = writeModifiedAt(
                Files.createDirectories(videoDir.resolve("thumbs")).resolve("surrogate-1.jpg"), LONG_AGO_MS);

        runner.run(null);

        assertThat(source).doesNotExist();
        assertThat(thumbSource).doesNotExist();
        assertThat(copy).exists();
        assertThat(thumbCopy).exists();
        assertThat(matches.findAll())
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.videoPath()).isEqualTo(copy.toString());
                            assertThat(row.thumbPath()).isEqualTo(thumbCopy.toString());
                        });
    }

    @Test
    void onlyAProvableSourceOfAnArchivedCopyIsRemoved() throws Exception {
        Path archive = configureArchive("hdd");
        seedAdoptionFloor(videoDir, System.currentTimeMillis());
        Path thumbs = Files.createDirectories(videoDir.resolve("thumbs"));
        // The archived copy's name, but other bytes.
        long id = insertMatch("placeholder", null, null);
        Path copy = writeModifiedAt(archive.resolve(id + "-2026-10-01 20-00-00.mp4"), LONG_AGO_MS);
        Path thumbCopy = writeModifiedAt(
                Files.createDirectories(archive.resolve("thumbs")).resolve(id + "-surrogate-1.jpg"), LONG_AGO_MS);
        matches.updateVideoPath(id, copy.toString(), thumbCopy.toString());
        Path otherBytes = videoDir.resolve("2026-10-01 20-00-00.mp4");
        Files.writeString(otherBytes, "a longer, different set of bytes entirely");
        makeStale(otherBytes);
        // The archived thumbnail's name and size, but another row still shows it.
        Path shown = writeModifiedAt(thumbs.resolve("surrogate-1.jpg"), LONG_AGO_MS);
        insertMatch(videoDir.resolve("2026-09-01 20-00-00.mp4").toString(), shown.toString(), null);
        // A copy on a drive that isn't plugged in can't be compared.
        long unplugged = insertMatch("placeholder", null, null);
        matches.updateVideoPath(
                unplugged, dir.resolve("usb").resolve(unplugged + "-2026-10-02 20-00-00.mp4").toString(), null);
        Path uncompared = writeModifiedAt(videoDir.resolve("2026-10-02 20-00-00.mp4"), LONG_AGO_MS);
        // A "copy" in the same folder is no archived copy.
        long here = insertMatch("placeholder", null, null);
        Path sameFolder = writeModifiedAt(videoDir.resolve(here + "-2026-10-03 20-00-00.mp4"), LONG_AGO_MS);
        matches.updateVideoPath(here, sameFolder.toString(), null);
        Path besideIt = writeModifiedAt(videoDir.resolve("2026-10-03 20-00-00.mp4"), LONG_AGO_MS);
        // A name this recorder never writes, beside the archived copy of a row an early 0.1.x adopted
        // under it (no name gate before v0.1.9): the same name less "<id>-" and the same bytes, but
        // the boot deletes only what this recorder names. The user's own file is never touched.
        long foreignRow = insertMatch("placeholder", null, null);
        Path foreignCopy = writeModifiedAt(archive.resolve(foreignRow + "-family.mp4"), LONG_AGO_MS);
        matches.updateVideoPath(foreignRow, foreignCopy.toString(), null);
        Path foreign = writeModifiedAt(videoDir.resolve("family.mp4"), LONG_AGO_MS);

        runner.run(null);

        assertThat(otherBytes).exists();
        assertThat(shown).exists();
        assertThat(uncompared).exists();
        assertThat(besideIt).exists();
        assertThat(foreign).exists();
        assertThat(matches.findById(foreignRow).orElseThrow().videoPath()).isEqualTo(foreignCopy.toString());
        assertThat(matches.findAll()).hasSize(5);
    }

    @Test
    void aRowReLinkedToACopyCutShortGoesBackOnItsOriginal() throws Exception {
        // Every 0.1.x archiver before the ".partial" copy wrote its copy under the final "<id>-<name>",
        // full size from the start, so one cut short (a quit, an update, a shutdown) has a zeroed tail,
        // and a boot that ran before the recording's drive was back re-linked the row to it by its name
        // and size. The boot that sees the original again puts the row back on it, thumbnail too,
        // rather than deleting the only good copy as a source left behind; the next boot drops the
        // copy as move residue.
        Path archive = configureArchive("hdd");
        seedAdoptionFloor(videoDir, System.currentTimeMillis());
        byte[] recording = recordingOf(256 * 1024);
        byte[] thumbnail = recordingOf(16 * 1024);
        long id = insertMatch("placeholder", null, (long) recording.length);
        Path copy = writeModifiedAt(
                archive.resolve(id + "-2026-10-01 20-00-00.mp4"), cutShortAt(recording, 100 * 1024), LONG_AGO_MS);
        Path thumbCopy = writeModifiedAt(
                Files.createDirectories(archive.resolve("thumbs")).resolve(id + "-surrogate-1.jpg"),
                cutShortAt(thumbnail, 4 * 1024), LONG_AGO_MS);
        matches.updateVideoPath(id, copy.toString(), thumbCopy.toString());
        Path original = writeModifiedAt(videoDir.resolve("2026-10-01 20-00-00.mp4"), recording, LONG_AGO_MS);
        Path thumbOriginal = writeModifiedAt(
                Files.createDirectories(videoDir.resolve("thumbs")).resolve("surrogate-1.jpg"), thumbnail,
                LONG_AGO_MS);

        runner.run(null);

        assertThat(original).exists();
        assertThat(thumbOriginal).exists();
        assertThat(matches.findAll())
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.videoPath()).isEqualTo(original.toString());
                            assertThat(row.thumbPath()).isEqualTo(thumbOriginal.toString());
                        });

        newRunner().run(null);

        assertThat(copy).doesNotExist();
        assertThat(original).exists();
        assertThat(matches.findById(id).orElseThrow().videoPath()).isEqualTo(original.toString());
    }

    @Test
    void aSourceGoesOnlyWhenItsCopyHoldsItsBytes() throws Exception {
        // A copy can be full size and end in data and still not hold the recording: one cut short in
        // its last 64 KiB, or one an older archiver left with a stretch its drive never got (a power
        // cut before the cache was written). The source goes only when the copy matches it at its
        // start, middle and end; otherwise the row goes back on the source. A whole copy still takes
        // its source.
        Path archive = configureArchive("hdd");
        seedAdoptionFloor(videoDir, System.currentTimeMillis());
        byte[] recording = recordingOf(256 * 1024);
        byte[] holed = recording.clone();
        java.util.Arrays.fill(holed, 96 * 1024, 160 * 1024, (byte) 0);
        long cutLate = archivedRow(
                archive, "2026-10-01 20-00-00.mp4", cutShortAt(recording, recording.length - 10 * 1024));
        long withAHole = archivedRow(archive, "2026-10-02 20-00-00.mp4", holed);
        long whole = archivedRow(archive, "2026-10-03 20-00-00.mp4", recording);
        Path cutLateSource = writeModifiedAt(videoDir.resolve("2026-10-01 20-00-00.mp4"), recording, LONG_AGO_MS);
        Path holedSource = writeModifiedAt(videoDir.resolve("2026-10-02 20-00-00.mp4"), recording, LONG_AGO_MS);
        Path wholeSource = writeModifiedAt(videoDir.resolve("2026-10-03 20-00-00.mp4"), recording, LONG_AGO_MS);

        runner.run(null);

        assertThat(matches.findById(cutLate).orElseThrow().videoPath()).isEqualTo(cutLateSource.toString());
        assertThat(matches.findById(withAHole).orElseThrow().videoPath()).isEqualTo(holedSource.toString());
        assertThat(cutLateSource).exists();
        assertThat(holedSource).exists();
        assertThat(wholeSource).doesNotExist();
        assertThat(matches.findById(whole).orElseThrow().videoPath())
                .isEqualTo(archive.resolve(whole + "-2026-10-03 20-00-00.mp4").toString());
    }

    @Test
    void aWholeCopyKeepsItsRowBesideAnOriginalCutShort() throws Exception {
        // The archived copy's original name and size in the video folder, but cut short (the user
        // copied the recording back and the copy didn't finish, say), beside the whole copy the row
        // is on. The row stays on the copy: moved onto the file, it would make the next boot delete
        // the whole copy as move residue. Both files stay.
        Path archive = configureArchive("hdd");
        seedAdoptionFloor(videoDir, System.currentTimeMillis());
        byte[] recording = recordingOf(256 * 1024);
        long id = archivedRow(archive, "2026-10-01 20-00-00.mp4", recording);
        Path copy = archive.resolve(id + "-2026-10-01 20-00-00.mp4");
        Path cutShort = writeModifiedAt(
                videoDir.resolve("2026-10-01 20-00-00.mp4"), cutShortAt(recording, 100 * 1024), LONG_AGO_MS);

        runner.run(null);
        newRunner().run(null);

        assertThat(matches.findAll())
                .singleElement()
                .satisfies(row -> assertThat(row.videoPath()).isEqualTo(copy.toString()));
        assertThat(copy).exists();
        assertThat(cutShort).exists();
    }

    @Test
    void anOriginalThatCantBeReadLeavesItAndItsCopyAloneUntilItCan() throws Exception {
        // The source the archiver left behind can't be read at its start (here another handle locks
        // its first 64 KiB, the way a bad sector fails a read), but its end can. That tells nothing
        // about the copy: the row stays on it and both files stay, boot after boot, and once the
        // source reads again it goes as a source left behind.
        Path archive = configureArchive("hdd");
        seedAdoptionFloor(videoDir, System.currentTimeMillis());
        byte[] recording = recordingOf(256 * 1024);
        long id = archivedRow(archive, "2026-10-05 20-00-00.mp4", recording);
        Path copy = archive.resolve(id + "-2026-10-05 20-00-00.mp4");
        Path source = writeModifiedAt(videoDir.resolve("2026-10-05 20-00-00.mp4"), recording, LONG_AGO_MS);

        try (java.nio.channels.FileChannel held = java.nio.channels.FileChannel.open(
                        source, java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE);
                java.nio.channels.FileLock lock = held.lock(0, 64 * 1024, false)) {
            runner.run(null);
            newRunner().run(null);

            assertThat(matches.findAll())
                    .singleElement()
                    .satisfies(row -> assertThat(row.videoPath()).isEqualTo(copy.toString()));
            assertThat(copy).exists();
            assertThat(source).exists();
        }

        newRunner().run(null);

        assertThat(source).doesNotExist();
        assertThat(copy).exists();
        assertThat(matches.findById(id).orElseThrow().videoPath()).isEqualTo(copy.toString());
    }

    @Test
    void anOriginalDamagedSinceItWasCopiedNeverTakesTheRowFromAWholeCopy() throws Exception {
        // Whole copies, beside sources that changed since they were copied: one reads as zeros in its
        // middle (a file system error), one holds other bytes there. Neither shows the copy is the
        // damaged one, so no row moves onto its source, which would make the next boot drop the
        // whole copy as move residue, and every file stays.
        Path archive = configureArchive("hdd");
        seedAdoptionFloor(videoDir, System.currentTimeMillis());
        byte[] recording = recordingOf(256 * 1024);
        byte[] holed = recording.clone();
        java.util.Arrays.fill(holed, 96 * 1024, 160 * 1024, (byte) 0);
        byte[] altered = recording.clone();
        for (int i = 120 * 1024; i < 136 * 1024; i++) {
            altered[i] = (byte) (altered[i] == (byte) 0x7f ? 0x7e : 0x7f);
        }
        long overHoled = archivedRow(archive, "2026-10-01 20-00-00.mp4", recording);
        long overAltered = archivedRow(archive, "2026-10-02 20-00-00.mp4", recording);
        Path holedSource = writeModifiedAt(videoDir.resolve("2026-10-01 20-00-00.mp4"), holed, LONG_AGO_MS);
        Path alteredSource = writeModifiedAt(videoDir.resolve("2026-10-02 20-00-00.mp4"), altered, LONG_AGO_MS);

        runner.run(null);
        newRunner().run(null);

        for (long id : List.of(overHoled, overAltered)) {
            Path copy = Path.of(matches.findById(id).orElseThrow().videoPath());
            assertThat(copy.getParent()).isEqualTo(archive);
            assertThat(copy).exists();
        }
        assertThat(holedSource).exists();
        assertThat(alteredSource).exists();
        assertThat(matches.findAll()).hasSize(2);
    }

    @Test
    void aRowGoesBackOnItsOriginalOnlyWhenTheOriginalHoldsAllItsCopyDoes() throws Exception {
        // The copy is cut short, and its samples show it, but the original also changed since, at a
        // stretch the samples don't read. Put back on it, the row would make the next boot drop the
        // copy, the only file holding that stretch. Read end to end, the two differ where the copy
        // holds data, so both stay and the row stays on the copy.
        Path archive = configureArchive("hdd");
        seedAdoptionFloor(videoDir, System.currentTimeMillis());
        byte[] recording = recordingOf(256 * 1024);
        long id = archivedRow(archive, "2026-10-01 20-00-00.mp4", cutShortAt(recording, 200 * 1024));
        Path copy = archive.resolve(id + "-2026-10-01 20-00-00.mp4");
        byte[] changed = recording.clone();
        for (int i = 70 * 1024; i < 80 * 1024; i++) {
            changed[i] = (byte) (changed[i] == (byte) 0x7f ? 0x7e : 0x7f);
        }
        Path original = writeModifiedAt(videoDir.resolve("2026-10-01 20-00-00.mp4"), changed, LONG_AGO_MS);

        runner.run(null);
        newRunner().run(null);

        assertThat(matches.findById(id).orElseThrow().videoPath()).isEqualTo(copy.toString());
        assertThat(copy).exists();
        assertThat(original).exists();
    }

    @Test
    void aRecordingThatEndsInZerosIsSettledByReadingItEndToEnd() throws Exception {
        // Recordings an OS crash cut short (a power cut before their end reached the drive), so they
        // end in zeros. One was archived whole and its source left behind: the copy is the source end
        // to end, so the source goes, and no second row takes it. The other's copy an older archiver
        // cut short before that: it lacks what the source holds and holds nothing the source
        // doesn't, so the row goes back on the source.
        Path archive = configureArchive("hdd");
        long tenMinutesAgo = System.currentTimeMillis() - 10L * 60_000L;
        byte[] crashed = cutShortAt(recordingOf(256 * 1024), 180 * 1024);
        long wholeRow = archivedRow(archive, "2026-10-03 20-00-00.mp4", crashed);
        Path wholeCopy = archive.resolve(wholeRow + "-2026-10-03 20-00-00.mp4");
        Path leftBehind = writeModifiedAt(videoDir.resolve("2026-10-03 20-00-00.mp4"), crashed, tenMinutesAgo);
        long cutRow = archivedRow(archive, "2026-10-04 20-00-00.mp4", cutShortAt(crashed, 100 * 1024));
        Path original = writeModifiedAt(videoDir.resolve("2026-10-04 20-00-00.mp4"), crashed, tenMinutesAgo);

        runner.run(null);

        assertThat(leftBehind).doesNotExist();
        assertThat(wholeCopy).exists();
        assertThat(matches.findById(wholeRow).orElseThrow().videoPath()).isEqualTo(wholeCopy.toString());
        assertThat(matches.findById(cutRow).orElseThrow().videoPath()).isEqualTo(original.toString());
        assertThat(matches.findAll()).hasSize(2);
    }

    @Test
    void reconciliationWaitsForTheStorageMaintenanceLock() throws Exception {
        // The archiver/sweeper @Scheduled clocks start at context refresh while this runner executes
        // after it, so a locked maintenance pass can overlap recovery. Mid-move, the archiver's fresh
        // cross-store copy is referenced by no row yet, so an UNLOCKED scan would delete it as move
        // residue right before the repoint lands. The runner must block on the shared lock instead of
        // scanning past an in-flight pass.
        Path orphan = videoDir.resolve("2026-06-28 14-30-15.mp4");
        Files.writeString(orphan, "orphan bytes");
        makeStale(orphan);

        maintenanceLock.lock(); // an archive/sweep pass is in flight
        Thread recovery = new Thread(() -> runner.run(null));
        try {
            recovery.start();
            recovery.join(500);
            // Still blocked behind the pass: nothing has been reconciled yet.
            assertThat(matches.findAll()).isEmpty();
        } finally {
            maintenanceLock.unlock();
        }
        recovery.join(10_000);
        assertThat(recovery.isAlive()).isFalse();
        assertThat(matches.findAll())
                .filteredOn(row -> orphan.toString().equals(row.videoPath()))
                .hasSize(1);
    }

    // --- Adoption floors: the basename gate passes the user's own OBS recordings too ---

    @Test
    void anOldObsNamedFileInANewlyChosenVideoFolderIsNeverAdopted() throws Exception {
        // The reviewed case: recording pointed at the folder the user's own OBS saves into. Its names
        // pass the basename gate, and adopting them would hand years of personal recordings to
        // retention's oldest-first delete.
        Path videos = chooseVideoDir("Videos");
        Path personal = writeModifiedAt(videos.resolve("2024-05-01 18-30-12.mkv"), LONG_AGO_MS);
        Path recent = videos.resolve("2026-11-01 20-00-00 (2).mp4");
        Files.writeString(recent, "made ten minutes before the folder was chosen");
        makeStale(recent);
        long before = System.currentTimeMillis();

        runner.run(null);
        newRunner().run(null); // and not on a later boot either

        assertThat(matches.findAll()).isEmpty();
        assertThat(personal).exists();
        assertThat(recent).exists();
        assertThat(storedFloor(videos)).isNotNull().isGreaterThanOrEqualTo(before);
    }

    @Test
    void anOldObsNamedFileInANewlyAddedArchiveFolderIsNeverAdopted() throws Exception {
        Path archive = chooseArchive("Videos");
        Path personal = writeModifiedAt(archive.resolve("2024-05-01 18-30-12.mkv"), LONG_AGO_MS);
        // Leading digits read as a match id with no row, so this goes the import path too.
        Path prefixed = archive.resolve("12-2026-11-01 20-00-00.mp4");
        Files.writeString(prefixed, "another personal recording");
        makeStale(prefixed);

        runner.run(null);
        newRunner().run(null);

        assertThat(matches.findAll()).isEmpty();
        assertThat(personal).exists();
        assertThat(prefixed).exists();
        assertThat(storedFloor(archive)).isNotNull();
    }

    @Test
    void anOrphanWrittenSinceTheFloorIsAdopted() throws Exception {
        // A crash orphan written after its folder's floor is what the scan exists for, and is still
        // adopted: from exactly the floor on, in the video folder and on an archive drive alike.
        Path videos = chooseVideoDir("Videos");
        Path archive = chooseArchive("hdd");
        long floor = System.currentTimeMillis() - 60L * 60_000L; // the boot that first read both
        seedAdoptionFloor(videos, floor);
        seedAdoptionFloor(archive, floor);
        Path atFloor = writeModifiedAt(videos.resolve("2026-11-14 10-00-00.mp4"), floor);
        Path beforeFloor = writeModifiedAt(videos.resolve("2026-11-14 09-00-00.mp4"), floor - 1);
        Path archived = writeModifiedAt(archive.resolve("2026-11-14 10-30-00.mkv"), floor + 30L * 60_000L);
        Path archivedBefore = writeModifiedAt(archive.resolve("2026-11-14 08-00-00.mkv"), floor - 60_000L);

        runner.run(null);

        assertThat(matches.findAll())
                .extracting(MatchSummary::videoPath)
                .containsExactlyInAnyOrder(atFloor.toString(), archived.toString());
        assertThat(matches.findAll()).allSatisfy(row -> assertThat(row.enrichmentState()).isEqualTo("gsi_only"));
        assertThat(beforeFloor).exists();
        assertThat(archivedBefore).exists();
    }

    @Test
    void aVideoFolderChosenAgainAdoptsNothingItGainedMeanwhile() throws Exception {
        // Recording moves off the user's Videos folder and later back. Meanwhile their own OBS kept
        // saving there, all of it newer than the floor Videos had the first time, so that floor must
        // not come back.
        Path videos = chooseVideoDir("Videos");
        long first = System.currentTimeMillis() - 60L * 60_000L; // the boot that first read Videos
        seedAdoptionFloor(videos, first);
        chooseVideoDir("Dota2Rec");
        runner.run(null); // a boot that records elsewhere
        assertThat(storedFloor(videos)).isNull();
        Path gained = writeModifiedAt(videos.resolve("2026-11-14 12-00-00.mp4"), first + 30L * 60_000L);
        chooseVideoDir("Videos");
        long before = System.currentTimeMillis();

        newRunner().run(null);

        assertThat(matches.findAll()).isEmpty();
        assertThat(gained).exists();
        assertThat(storedFloor(videos)).isNotNull().isGreaterThanOrEqualTo(before);
    }

    @Test
    void anArchiveAddedBackAdoptsNothingItGainedMeanwhile() throws Exception {
        Path archive = chooseArchive("hdd");
        long first = System.currentTimeMillis() - 60L * 60_000L;
        seedAdoptionFloor(archive, first);
        removeArchive("hdd");
        runner.run(null);
        assertThat(storedFloor(archive)).isNull();
        Path gained = writeModifiedAt(archive.resolve("2026-11-14 12-00-00.mkv"), first + 30L * 60_000L);
        chooseArchive("hdd");

        newRunner().run(null);

        assertThat(matches.findAll()).isEmpty();
        assertThat(gained).exists();
        // The folder that stayed a root throughout keeps its floor.
        assertThat(storedFloor(videoDir)).isZero();
    }

    @Test
    void anUnpluggedArchiveKeepsItsFloor() throws Exception {
        // A drive that is configured but unplugged is still a root: it keeps its floor, and what the
        // recorder left on it is adopted once it's back.
        Path archive = chooseArchive("hdd");
        long first = System.currentTimeMillis() - 60L * 60_000L;
        seedAdoptionFloor(archive, first);
        Files.delete(archive);
        runner.run(null);
        assertThat(storedFloor(archive)).isEqualTo(first);
        Files.createDirectories(archive);
        Path leftover = writeModifiedAt(archive.resolve("2026-11-14 12-00-00.mkv"), first + 30L * 60_000L);

        newRunner().run(null);

        assertThat(matches.findAll())
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.videoPath()).isEqualTo(leftover.toString());
                            assertThat(row.enrichmentState()).isEqualTo("gsi_only");
                        });
    }

    @Test
    void aFolderFirstFlooredOnAClockFarBehindStillTurnsAwayWhatItHeld() throws Exception {
        // This boot's clock is a year behind the recordings the user's own OBS already saved in the
        // newly chosen folder (a reset CMOS clock before time sync, say). The floor goes just after
        // the newest of them rather than at this boot's wrong "now", so none passes once the clock is
        // right. Only names the scan could adopt count.
        Path videos = chooseVideoDir("Videos");
        long yearAhead = System.currentTimeMillis() + 365L * 24 * 60 * 60_000L;
        Path personal = writeModifiedAt(videos.resolve("2027-11-14 20-00-00.mp4"), yearAhead);
        Path older = writeModifiedAt(videos.resolve("2024-05-01 18-30-12.mkv"), LONG_AGO_MS);
        writeModifiedAt(videos.resolve("family.mp4"), yearAhead + 60_000L);

        runner.run(null);

        assertThat(storedFloor(videos)).isEqualTo(yearAhead + 1);
        assertThat(matches.findAll()).isEmpty();
        assertThat(personal).exists();
        assertThat(older).exists();
    }

    @Test
    void anArchiveThatCantBeReadGetsItsFloorOnTheFirstBootThatCan() throws Exception {
        // Configured but unplugged on its first boot: what it holds can't be checked, so it gets no
        // floor yet, and the boot it's back floors it after what it holds.
        Path archive = chooseArchive("usb");
        Files.delete(archive);
        runner.run(null);
        assertThat(storedFloor(archive)).isNull();

        Files.createDirectories(archive);
        long ahead = System.currentTimeMillis() + 60L * 60_000L;
        Path held = writeModifiedAt(archive.resolve("12-2026-11-14 20-00-00.mp4"), ahead);
        newRunner().run(null);

        assertThat(storedFloor(archive)).isEqualTo(ahead + 1);
        assertThat(matches.findAll()).isEmpty();
        assertThat(held).exists();
    }

    @Test
    void theFloorsFileIsTheFormatBothAppsKeep() throws Exception {
        // The file the Go remake writes, laid out the way its json.MarshalIndent lays it out, and this
        // app's additions read back as plain JSON.
        Path videos = chooseVideoDir("Videos");
        Path archive = chooseArchive("hdd");
        long floor = System.currentTimeMillis() - 60L * 60_000L;
        String key = new ObjectMapper().writeValueAsString(OrphanAdoptionFloors.key(videos));
        Files.writeString(
                floorsFile(),
                "{\n  \"version\": 1,\n  \"floors\": {\n    " + key + ": " + floor + "\n  }\n}");
        Path adopted = writeModifiedAt(videos.resolve("2026-11-14 10-00-00.mp4"), floor + 60_000L);
        Path left = writeModifiedAt(videos.resolve("2026-11-14 09-00-00.mp4"), floor - 60_000L);

        runner.run(null);

        assertThat(matches.findAll()).extracting(MatchSummary::videoPath).containsExactly(adopted.toString());
        assertThat(left).exists();
        assertThat(storedFloor(videos)).isEqualTo(floor);
        assertThat(storedFloor(archive)).isNotNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "not json",
                "",
                "null",
                "[]",
                "{\"version\":2,\"floors\":{}}",
                "{\"version\":\"1\",\"floors\":{}}",
                "{\"version\":1}",
                "{\"version\":1,\"floors\":null}",
                "{\"version\":1,\"floors\":{\"c:\\\\x\":1.5}}",
                "{\"version\":1,\"floors\":{\"c:\\\\x\":1e3}}",
                "{\"version\":1,\"floors\":{\"c:\\\\x\":null}}",
                "{\"version\":1,\"floors\":{\"c:\\\\x\":\"0\"}}",
                "{\"Version\":1,\"Floors\":{}}",
                "{\"version\":1,\"floors\":{}} x",
            })
    void aCorruptFloorsFileAdoptsNothingOld(String content) throws Exception {
        // videoDir is floored since the epoch, until the file breaks.
        Path orphan = videoDir.resolve("2026-06-28 14-30-15.mp4");
        Files.writeString(orphan, "would be adopted");
        makeStale(orphan);
        Files.writeString(floorsFile(), content);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> OrphanAdoptionFloors.read(floorsFile()));
        long before = System.currentTimeMillis();

        runner.run(null);

        assertThat(matches.findAll()).isEmpty();
        assertThat(orphan).exists();
        assertThat(storedFloor(videoDir)).isNotNull().isGreaterThanOrEqualTo(before);
    }

    @Test
    void aScanThatCannotSaveItsFloorsAdoptsNothing() throws Exception {
        // A floor that can't be saved might not be there next boot, so a scan that couldn't save its
        // floors adopts nothing, even from a folder whose floor it read.
        chooseArchive("hdd"); // new, so the scan has a floor to save
        Path orphan = videoDir.resolve("2026-06-28 14-30-15.mp4");
        Files.writeString(orphan, "would be adopted");
        makeStale(orphan);
        Files.createDirectories(floorsFile().resolveSibling(OrphanAdoptionFloors.FILE_NAME + ".tmp"));

        runner.run(null);

        assertThat(matches.findAll()).isEmpty();
        assertThat(orphan).exists();
    }

    @Test
    void withoutADataFolderNothingIsAdoptedOrLinked() throws Exception {
        Path orphan = videoDir.resolve("2026-06-28 14-30-15.mp4");
        Files.writeString(orphan, "would be adopted");
        makeStale(orphan);
        Path crashed = writeModifiedAt(videoDir.resolve("2026-06-28 15-00-00.mp4"), 5_000L);
        journal.open(crashedSessionRow());

        new CrashRecoveryRunner(
                        journal, matches, clips, markers, pauses, ds, new ObjectMapper(), settings,
                        maintenanceLock)
                .run(null);

        assertThat(matches.findAll()).singleElement().satisfies(row -> assertThat(row.videoPath()).isNull());
        assertThat(orphan).exists();
        assertThat(crashed).exists();
    }

    @Test
    void linkingRefusesANameThisRecorderDoesNotWrite() throws Exception {
        // The link window is minutes wide around a crashed session, and the user's own recording can
        // sit in it: a link makes a file a match's video just as an import does.
        Path foreign = writeModifiedAt(videoDir.resolve("crashed.mp4"), 5_000L);
        journal.open(crashedSessionRow());

        runner.run(null);

        assertJournalRowWithoutVideo();
        assertThat(foreign).exists();
    }

    @Test
    void linkingRefusesAFileOlderThanTheFloor() throws Exception {
        Path videos = chooseVideoDir("Videos");
        seedAdoptionFloor(videos, 5_500L);
        Path old = writeModifiedAt(videos.resolve("2026-06-28 14-30-15.mp4"), 5_000L);
        journal.open(crashedSessionRow());

        runner.run(null);

        assertJournalRowWithoutVideo();
        assertThat(old).exists();
    }

    @Test
    void aNewVideoFolderLinksItsPathlessJournalsFileFromBeforeItsFloor() throws Exception {
        // The upgrade boot after a crash mid-match (or the first boot in a newly chosen folder) gives
        // the video folder its first floor in the same boot that recovers the pathless journal. The
        // journal is evidence of recording from its start, so the link reaches back there, less the
        // link grace, and the file is linked rather than turned away for good. The stored floor is
        // still the scan's, so the import takes nothing else from before it, and archives start at the
        // scan too.
        Path videos = chooseVideoDir("Videos");
        Path archive = chooseArchive("hdd");
        Crash crash = crashedHalfAnHourAgo(null);
        Path crashed = writeModifiedAt(videos.resolve("2026-11-14 10-00-00.mp4"), crash.updatedMs());
        Path older = writeModifiedAt(videos.resolve("2026-11-14 09-00-00.mp4"),
                crash.startedMs() - 5L * 60_000L - 60_000L);
        // The user's own OBS, after the crashed match's window and before this boot: past the link's
        // reach, and before the folder's floor, so nothing may adopt it.
        Path personal = writeModifiedAt(videos.resolve("2026-11-14 10-20-00.mp4"),
                System.currentTimeMillis() - 10L * 60_000L);
        Path archived = writeModifiedAt(archive.resolve("2026-11-14 09-50-00.mkv"), crash.startedMs());
        long scanStart = System.currentTimeMillis();

        runner.run(null);

        assertThat(matches.findAll())
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.dotaMatchId()).isEqualTo(1234L);
                            assertThat(row.videoPath()).isEqualTo(crashed.toString());
                        });
        assertThat(older).exists();
        assertThat(personal).exists();
        assertThat(archived).exists();
        assertThat(storedFloor(videos)).isNotNull().isGreaterThanOrEqualTo(scanStart);
        assertThat(storedFloor(archive)).isNotNull().isGreaterThanOrEqualTo(scanStart);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "journal with its path: {0}")
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void aFirstVideoFloorIsTheScanWithoutAPathlessJournal(boolean journalWithPath) throws Exception {
        Path videos = chooseVideoDir("Videos");
        long started = System.currentTimeMillis() - 30L * 60_000L;
        if (journalWithPath) {
            Path own = videos.resolve("2026-11-14 10-00-00.mp4");
            Files.writeString(own, "the journal's own recording");
            started = crashedHalfAnHourAgo(own.toString()).startedMs();
        }
        Path personal = writeModifiedAt(videos.resolve("2026-11-14 09-55-00.mp4"), started);
        long scanStart = System.currentTimeMillis();

        runner.run(null);

        assertThat(matches.findAll()).noneMatch(row -> personal.toString().equals(row.videoPath()));
        assertThat(personal).exists();
        assertThat(storedFloor(videos)).isNotNull().isGreaterThanOrEqualTo(scanStart);
    }

    @Test
    void aPathlessJournalNeverLowersAnExistingFloor() throws Exception {
        Path videos = chooseVideoDir("Videos");
        long existing = System.currentTimeMillis() - 10L * 60_000L;
        seedAdoptionFloor(videos, existing);
        Crash crash = crashedHalfAnHourAgo(null);
        Path crashed = writeModifiedAt(videos.resolve("2026-11-14 10-00-00.mp4"), crash.updatedMs());

        runner.run(null);

        assertJournalRowWithoutVideo();
        assertThat(crashed).exists();
        assertThat(storedFloor(videos)).isEqualTo(existing);
    }

    @Test
    void aBootThatCannotSaveItsFloorsStillLinksItsCrashedRecording() throws Exception {
        // The upgrade boot after an install killed the app mid-match, with the floors file blocked
        // (another program holding it, say). The link needs no saved floor, so the recording still
        // gets its file; only the imports, which do, adopt nothing this boot.
        Path videos = chooseVideoDir("Videos");
        Crash crash = crashedHalfAnHourAgo(null);
        Path crashed = writeModifiedAt(videos.resolve("2026-11-14 10-00-00.mp4"), crash.updatedMs());
        Files.createDirectories(floorsFile().resolveSibling(OrphanAdoptionFloors.FILE_NAME + ".tmp"));

        runner.run(null);

        assertThat(matches.findAll())
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.dotaMatchId()).isEqualTo(1234L);
                            assertThat(row.videoPath()).isEqualTo(crashed.toString());
                        });
        assertThat(journal.findUnfinished()).isEmpty();
        assertThat(storedFloor(videos)).isNull();
    }

    @Test
    void aCrashedRecordingWhoseRecoveryRollsBackIsLinkedOnTheNextBoot() throws Exception {
        // The boot that gives the video folder its first floor can't commit the pathless recording's
        // recovery (a locked database, say), so the journal keeps it. Saved, that floor would put the
        // file out of reach for good; held back, the next boot reaches back for it again. The folder
        // adopts nothing on the boot that held its floor, and the other roots' floors are saved.
        Path videos = chooseVideoDir("Videos");
        Path archive = chooseArchive("hdd");
        Crash crash = crashedHalfAnHourAgo(null);
        Path crashed = writeModifiedAt(videos.resolve("2026-11-14 10-00-00.mp4"), crash.updatedMs());

        newRunnerWhoseRecoveryFails().run(null);

        assertThat(journal.findUnfinished()).hasSize(1);
        assertThat(matches.findAll()).isEmpty();
        assertThat(storedFloor(videos)).isNull();
        assertThat(storedFloor(archive)).isNotNull();

        newRunner().run(null);

        assertThat(matches.findAll())
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.dotaMatchId()).isEqualTo(1234L);
                            assertThat(row.videoPath()).isEqualTo(crashed.toString());
                        });
        assertThat(journal.findUnfinished()).isEmpty();
        assertThat(storedFloor(videos)).isNotNull();
    }

    @Test
    void aPathlessRecordingWaitsInTheJournalForABootThatCanReadItsFolder() throws Exception {
        // The recording folder is on a drive that isn't mounted yet at this boot. The pathless
        // recording's file can't be looked for, so it stays in the journal and the folder gets no
        // floor; a recording whose path the journal has is recovered as usual.
        Path usb = chooseVideoDir("usb");
        Files.delete(usb);
        Crash crash = crashedHalfAnHourAgo(null);
        Path finished = Files.createDirectories(dir.resolve("old")).resolve("2026-11-14 08-00-00.mp4");
        Files.writeString(finished, "a recording whose path the journal has");
        journal.open(sessionAt("with-path", crash.startedMs() - 60L * 60_000L, finished.toString()));

        runner.run(null);

        assertThat(journal.findUnfinished())
                .extracting(RecordingSessionRow::sessionId)
                .containsExactly("session-crash");
        assertThat(matches.findAll()).extracting(MatchSummary::videoPath).containsExactly(finished.toString());
        assertThat(storedFloor(usb)).isNull();

        Files.createDirectories(usb);
        Path crashed = writeModifiedAt(usb.resolve("2026-11-14 10-00-00.mp4"), crash.updatedMs());
        newRunner().run(null);

        assertThat(journal.findUnfinished()).isEmpty();
        assertThat(matches.findAll())
                .extracting(MatchSummary::videoPath)
                .containsExactlyInAnyOrder(finished.toString(), crashed.toString());
        assertThat(storedFloor(usb)).isNotNull();
    }

    @Test
    void aPathlessRecordingWaitsWhenThePlanCouldNotReadItsFolder() throws Exception {
        // The recording folder, new to the scan, is on a drive that comes up while the boot plans the
        // floors: the plan's read fails, so the folder gets no floor, and the drive is ready a moment
        // later. Recovered then, the recording would be linked against no floor, which admits nothing,
        // and committed without its file. The plan's one read decides, so it waits for the next boot.
        Path usb = chooseVideoDir("usb");
        Crash crash = crashedHalfAnHourAgo(null);
        Path crashed = writeModifiedAt(usb.resolve("2026-11-14 10-00-00.mp4"), crash.updatedMs());
        java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
        CrashRecoveryRunner slowDrive =
                new CrashRecoveryRunner(
                        journal, matches, clips, markers, pauses, ds, new ObjectMapper(), settings,
                        maintenanceLock, paths) {
                    @Override
                    java.util.OptionalLong newestRecorderFileMs(Path root) {
                        if (root.equals(usb) && !ready.getAndSet(true)) {
                            return java.util.OptionalLong.empty();
                        }
                        return super.newestRecorderFileMs(root);
                    }
                };

        slowDrive.run(null);

        assertThat(journal.findUnfinished())
                .extracting(RecordingSessionRow::sessionId)
                .containsExactly("session-crash");
        assertThat(matches.findAll()).isEmpty();
        assertThat(storedFloor(usb)).isNull();

        newRunner().run(null);

        assertThat(journal.findUnfinished()).isEmpty();
        assertThat(matches.findAll())
                .singleElement()
                .satisfies(row -> assertThat(row.videoPath()).isEqualTo(crashed.toString()));
        assertThat(storedFloor(usb)).isNotNull();
    }

    @Test
    void withNoVideoFolderSetAPathlessRecordingIsRecoveredAtOnce() {
        // Only a folder that is set but can't be read is worth waiting for.
        settings.update(
                s -> {
                    s.videoDir = "";
                    return s;
                });
        journal.open(crashedSessionRow());

        runner.run(null);

        assertJournalRowWithoutVideo();
    }

    @Test
    void theLinkReachesBackOnlyForPathlessSessionsWithAStart() {
        RecordingSessionRow pathless = sessionAt("pathless", 100_000_000L, null);
        RecordingSessionRow withPath = sessionAt("with-path", 50_000_000L, "C:\\v\\own.mp4");
        RecordingSessionRow noStart = sessionAt("no-start", 0L, null);

        assertThat(CrashRecoveryRunner.videoLinkFromMs(List.of(), 200_000_000L)).isEqualTo(200_000_000L);
        assertThat(CrashRecoveryRunner.videoLinkFromMs(List.of(pathless, withPath, noStart), 200_000_000L))
                .isEqualTo(100_000_000L - 5L * 60_000L);
        assertThat(CrashRecoveryRunner.videoLinkFromMs(List.of(pathless), 10_000_000L)).isEqualTo(10_000_000L);
    }

    // --- Rows adopted before the floors existed ---

    @Test
    void theUpgradeBootReleasesRowsAdoptedBeforeTheFloorsAndKeepsTheirFiles() throws Exception {
        // 0.1.20 adopted the user's own OBS recordings from the folder it records into, and the
        // archiver has since moved one of them. The first boot with floors takes those rows out of
        // the library and leaves the files where they are, below the floors their folders now get, so
        // no boot adopts them again. A row the recorder made is untouched.
        Files.delete(floorsFile());
        Path archive = chooseArchive("hdd");
        long monthAgo = System.currentTimeMillis() - 30L * 24 * 60 * 60_000L;
        Path personal = writeModifiedAt(videoDir.resolve("2024-05-01 18-30-12.mkv"), LONG_AGO_MS);
        long personalRow = adoptedBeforeTheFloors(personal, monthAgo);
        long movedRow = adoptedBeforeTheFloors(
                writeModifiedAt(videoDir.resolve("2024-06-01 18-30-12.mp4"), LONG_AGO_MS), monthAgo);
        Path moved = writeModifiedAt(archive.resolve(movedRow + "-2024-06-01 18-30-12.mp4"), LONG_AGO_MS);
        Files.delete(videoDir.resolve("2024-06-01 18-30-12.mp4"));
        matches.updateVideoPath(movedRow, moved.toString(), null);
        Path own = writeModifiedAt(videoDir.resolve("2026-11-01 20-00-00.mp4"), LONG_AGO_MS);
        long recorded = insertMatch(own.toString(), null, Files.size(own));

        runner.run(null);

        assertThat(matches.findAll()).extracting(MatchSummary::id).containsExactly(recorded);
        assertThat(matches.findById(personalRow)).isEmpty();
        assertThat(personal).exists();
        assertThat(moved).exists();
        com.fasterxml.jackson.databind.JsonNode marker = new ObjectMapper().readTree(releaseMarker().toFile());
        assertThat(marker.get("version").intValue()).isEqualTo(1);
        assertThat(marker.get("rows").intValue()).isEqualTo(2);
        assertThat(marker.get("releasedAtMs").longValue()).isPositive();

        newRunner().run(null);

        assertThat(matches.findAll()).extracting(MatchSummary::id).containsExactly(recorded);
    }

    @Test
    void theReleaseKeepsWhatThisAppCertainlyMadeAndWhatTheUserKept() throws Exception {
        Files.delete(floorsFile());
        Path archive = chooseArchive("hdd");
        long monthAgo = System.currentTimeMillis() - 30L * 24 * 60 * 60_000L;
        // Starred by the user.
        long starred = adoptedBeforeTheFloors(
                writeModifiedAt(videoDir.resolve("2024-05-01 18-30-12.mkv"), LONG_AGO_MS), monthAgo);
        matches.setStarred(starred, true);
        // Another row's archived name, and a clip's: residue only this app makes.
        long residue = adoptedBeforeTheFloors(
                writeModifiedAt(archive.resolve("99-2024-05-02 18-30-12.mp4"), LONG_AGO_MS), monthAgo);
        long clipFile = adoptedBeforeTheFloors(
                writeModifiedAt(archive.resolve("clip-7-3-clip-7.mp4"), LONG_AGO_MS), monthAgo);
        // The source the archiver failed to delete once it had moved another row's recording.
        long archived = insertMatch("placeholder", null, null);
        Path copy = writeModifiedAt(archive.resolve(archived + "-2024-05-03 18-30-12.mp4"), LONG_AGO_MS);
        matches.updateVideoPath(archived, copy.toString(), null);
        long duplicate = adoptedBeforeTheFloors(
                writeModifiedAt(videoDir.resolve("2024-05-03 18-30-12.mp4"), LONG_AGO_MS), monthAgo);
        // One with a clip keeps itself and the clip and drops its video; one with a clip still to be
        // cut from its video keeps that too.
        Path clipped = writeModifiedAt(videoDir.resolve("2024-05-04 18-30-12.mp4"), LONG_AGO_MS);
        long withClip = adoptedBeforeTheFloors(clipped, monthAgo);
        long clipId = insertClip(withClip, videoDir.resolve("clips").resolve(withClip + "-clip-1.mp4").toString());
        long cutting = adoptedBeforeTheFloors(
                writeModifiedAt(videoDir.resolve("2024-05-05 18-30-12.mp4"), LONG_AGO_MS), monthAgo);
        clips.insert(cutting, "manual", null, 1.0, 5.0, null, null, null, null, "pending", null, 1_000L);

        runner.run(null);

        for (long kept : List.of(starred, residue, clipFile, duplicate, cutting)) {
            assertThat(matches.findById(kept).orElseThrow().videoPath()).as("row %d", kept).isNotNull();
        }
        MatchSummary dropped = matches.findById(withClip).orElseThrow();
        assertThat(dropped.videoPath()).isNull();
        assertThat(dropped.fileSizeBytes()).isNull();
        assertThat(clips.findById(clipId)).isPresent();
        assertThat(clipped).exists();
        assertThat(new ObjectMapper().readTree(releaseMarker().toFile()).get("rows").intValue()).isEqualTo(1);
    }

    @Test
    void twoRowsAdoptedFromOneUserFileDontKeepEachOther() throws Exception {
        // 0.1.20 adopted the user's own recording, archived it, and couldn't delete the source (a
        // player held it open); the next boot adopted that source again, and a later pass archived it
        // too. Each row's file is the other's copy, but neither row is this app's: both leave the
        // library, and both files stay where they are.
        Files.delete(floorsFile());
        Path archive = chooseArchive("hdd");
        long monthAgo = System.currentTimeMillis() - 30L * 24 * 60 * 60_000L;
        Path source = writeModifiedAt(videoDir.resolve("2024-05-01 18-30-12.mkv"), LONG_AGO_MS);
        long first = adoptedBeforeTheFloors(source, monthAgo);
        Path firstCopy = writeModifiedAt(archive.resolve(first + "-2024-05-01 18-30-12.mkv"), LONG_AGO_MS);
        matches.updateVideoPath(first, firstCopy.toString(), null);
        long second = adoptedBeforeTheFloors(source, monthAgo + 60_000L);
        Path secondCopy = writeModifiedAt(archive.resolve(second + "-2024-05-01 18-30-12.mkv"), LONG_AGO_MS);
        Files.delete(source);
        matches.updateVideoPath(second, secondCopy.toString(), null);

        runner.run(null);

        assertThat(matches.findAll()).isEmpty();
        assertThat(firstCopy).exists();
        assertThat(secondCopy).exists();
    }

    @Test
    void aLeftBehindSourceIsNotKeptByTheCopyOfARowTheReleaseTakesOut() throws Exception {
        // As above, but the second adoption is still the source in the video folder. Its copy's row
        // leaves the library, so the copy is no evidence for it: it leaves too, and neither file is
        // deleted.
        Files.delete(floorsFile());
        Path archive = chooseArchive("hdd");
        long monthAgo = System.currentTimeMillis() - 30L * 24 * 60 * 60_000L;
        Path source = writeModifiedAt(videoDir.resolve("2024-05-01 18-30-12.mkv"), LONG_AGO_MS);
        long first = adoptedBeforeTheFloors(source, monthAgo);
        Path copy = writeModifiedAt(archive.resolve(first + "-2024-05-01 18-30-12.mkv"), LONG_AGO_MS);
        matches.updateVideoPath(first, copy.toString(), null);
        adoptedBeforeTheFloors(source, monthAgo + 60_000L);

        runner.run(null);

        assertThat(matches.findAll()).isEmpty();
        assertThat(source).exists();
        assertThat(copy).exists();
    }

    @Test
    void theReleaseKeepsNoSourceOfACopyCutShort() throws Exception {
        // 0.1.20 re-linked a match to its archived copy cut short while the recording's drive was out,
        // and its next boot with the drive back adopted the original as a row of its own. That row is
        // no duplicate of a whole copy: it leaves the library like any other adopted before the floors,
        // so retention can't delete the only good copy, and the match goes back on it.
        Files.delete(floorsFile());
        Path archive = chooseArchive("hdd");
        byte[] recording = recordingOf(256 * 1024);
        long match = archivedRow(archive, "2024-05-03 18-30-12.mp4", cutShortAt(recording, 100 * 1024));
        Path original = writeModifiedAt(videoDir.resolve("2024-05-03 18-30-12.mp4"), recording, LONG_AGO_MS);
        long adopted = adoptedBeforeTheFloors(original, System.currentTimeMillis() - 30L * 24 * 60 * 60_000L);

        runner.run(null);

        assertThat(matches.findById(adopted)).isEmpty();
        assertThat(original).exists();
        assertThat(matches.findById(match).orElseThrow().videoPath()).isEqualTo(original.toString());
    }

    @Test
    void theCutoffIsTheEarliestFloorTheFileHeldWhenTheBootBegan() throws Exception {
        // The floors file predates the release (an earlier build kept floors but never released, or
        // its marker couldn't be saved): only a row adopted before its earliest floor predates the
        // floors. One adopted under them, from a folder recording has since moved off, is the scan's
        // own and stays.
        long earliest = System.currentTimeMillis() - 60L * 60_000L;
        seedAdoptionFloor(videoDir, earliest);
        long before = adoptedBeforeTheFloors(
                writeModifiedAt(videoDir.resolve("2024-05-01 18-30-12.mkv"), LONG_AGO_MS), earliest - 60_000L);
        Path old = Files.createDirectories(dir.resolve("old"));
        long since = adoptedBeforeTheFloors(
                writeModifiedAt(old.resolve("2026-11-14 10-00-00.mp4"), earliest + 5L * 60_000L),
                earliest + 10L * 60_000L);

        runner.run(null);

        assertThat(matches.findById(before)).isEmpty();
        assertThat(matches.findById(since)).isPresent();
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "floors file {0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "unreadable"})
    void withNoFloorReadTheReleaseTakesEveryAdoptedRowWhateverItsCreatedAt(String floorsFile) throws Exception {
        // This boot's clock is a year behind the boot that adopted the row (a reset CMOS clock before
        // time sync, say). With no floor read, no row can have been adopted under one, so the boot's
        // own clock is no cutoff: taken as one, it would release nothing and mark the release done.
        if (floorsFile.equals("missing")) {
            Files.delete(floorsFile());
        } else {
            Files.writeString(floorsFile(), "not json");
        }
        long yearAhead = System.currentTimeMillis() + 365L * 24 * 60 * 60_000L;
        Path personal = writeModifiedAt(videoDir.resolve("2024-05-01 18-30-12.mkv"), LONG_AGO_MS);
        long row = adoptedBeforeTheFloors(personal, yearAhead);

        runner.run(null);

        assertThat(matches.findById(row)).isEmpty();
        assertThat(personal).exists();
        assertThat(new ObjectMapper().readTree(releaseMarker().toFile()).get("rows").intValue()).isEqualTo(1);
    }

    @Test
    void theReleaseRunsOnceEvenWhenTheFloorsFileIsLostLater() throws Exception {
        Files.delete(floorsFile());
        runner.run(null);
        assertThat(releaseMarker()).exists();
        // A row the scan adopted under the floors, and then the floors file goes.
        long now = System.currentTimeMillis();
        long adopted = adoptedBeforeTheFloors(
                writeModifiedAt(videoDir.resolve("2026-11-14 10-00-00.mp4"), now - 10L * 60_000L), now - 60_000L);
        Files.delete(floorsFile());

        newRunner().run(null);

        assertThat(matches.findById(adopted)).isPresent();
    }

    @Test
    void aBootThatCannotSaveItsFloorsReleasesNothingAndTheNextOneDoes() throws Exception {
        Files.delete(floorsFile());
        long row = adoptedBeforeTheFloors(
                writeModifiedAt(videoDir.resolve("2024-05-01 18-30-12.mkv"), LONG_AGO_MS),
                System.currentTimeMillis() - 60_000L);
        Path blocked = Files.createDirectories(floorsFile().resolveSibling(OrphanAdoptionFloors.FILE_NAME + ".tmp"));

        runner.run(null);

        assertThat(matches.findById(row)).isPresent();
        assertThat(releaseMarker()).doesNotExist();
        Files.delete(blocked);

        newRunner().run(null);

        assertThat(matches.findById(row)).isEmpty();
        assertThat(releaseMarker()).exists();
    }

    @Test
    void aRowWhoseFileItsFolderFloorAdmitsIsKept() throws Exception {
        // Modified after its folder's floor (edited in place, say): released, the scan would only
        // adopt it again, as a new row.
        long earliest = System.currentTimeMillis() - 60L * 60_000L;
        seedAdoptionFloor(videoDir, earliest);
        Path edited = writeModifiedAt(videoDir.resolve("2024-05-01 18-30-12.mkv"), earliest + 10L * 60_000L);
        long row = adoptedBeforeTheFloors(edited, earliest - 60_000L);

        runner.run(null);

        assertThat(matches.findAll())
                .singleElement()
                .satisfies(
                        kept -> {
                            assertThat(kept.id()).isEqualTo(row);
                            assertThat(kept.createdAt()).isEqualTo(earliest - 60_000L);
                            assertThat(kept.videoPath()).isEqualTo(edited.toString());
                        });
    }

    @Test
    void aFileReleasedOnTheUpgradeBootIsNeverLinkedToTheMatchThatCrashedThen() throws Exception {
        // The upgrade boot after a crash mid-match: the link reaches back before the video folder's
        // floor, into a window where the user's own OBS made a recording an earlier boot adopted. The
        // journal is replayed while that file is still a row's, so the link passes it over.
        Files.delete(floorsFile());
        Crash crash = crashedHalfAnHourAgo(null);
        Path personal = writeModifiedAt(videoDir.resolve("2026-11-14 10-00-00.mp4"), crash.updatedMs());
        long adopted = adoptedBeforeTheFloors(personal, System.currentTimeMillis() - 5L * 60_000L);

        runner.run(null);

        assertThat(matches.findById(adopted)).isEmpty();
        assertJournalRowWithoutVideo();
        assertThat(personal).exists();
    }

    /**
     * A row for {@code file} as the orphan import writes one ({@code gsi_only}, played at the file's
     * modification time), created at {@code createdAt}.
     */
    private long adoptedBeforeTheFloors(Path file, long createdAt) throws Exception {
        return matches.insert(
                new NewMatch(
                        null, "match", "gsi_only", null,
                        null, null, null, null, null, null, null,
                        null, null, null, null, null, null,
                        Files.getLastModifiedTime(file).toMillis(), file.toString(), null, Files.size(file),
                        false, createdAt, null));
    }

    private Path releaseMarker() {
        return paths.dataDir().resolve(OrphanAdoptionFloors.RELEASE_FILE_NAME);
    }

    private record Crash(long startedMs, long updatedMs) {}

    /**
     * Journals a recording that started 30 minutes ago and was last updated 20 minutes ago, with
     * {@code videoPath} null when OBS never revealed it (a stop that never came).
     */
    private Crash crashedHalfAnHourAgo(String videoPath) {
        long now = System.currentTimeMillis();
        RecordingSessionRow row = sessionAt("session-crash", now - 30L * 60_000L, videoPath);
        journal.open(row);
        return new Crash(row.recordStartedWallMs(), row.updatedAt());
    }

    private static RecordingSessionRow sessionAt(String id, long startedMs, String videoPath) {
        return new RecordingSessionRow(
                id,
                "surrogate-" + id,
                "recording",
                1234L,
                "npc_dota_hero_puck",
                startedMs,
                startedMs,
                startedMs + 10L * 60_000L,
                "DOTA_GAMERULES_STATE_GAME_IN_PROGRESS",
                1,
                1,
                1,
                videoPath,
                null,
                startedMs - 60_000L,
                startedMs + 10L * 60_000L);
    }

    private static final long LONG_AGO_MS = 1_714_588_212_000L; // 2024-05-01 18:30:12 UTC

    private Path floorsFile() {
        return paths.dataDir().resolve(OrphanAdoptionFloors.FILE_NAME);
    }

    /** Root's floor read back from the file as plain JSON, the way the other app would; null if none. */
    private Long storedFloor(Path root) throws Exception {
        com.fasterxml.jackson.databind.JsonNode file = new ObjectMapper().readTree(floorsFile().toFile());
        assertThat(file.get("version").intValue()).isEqualTo(1);
        com.fasterxml.jackson.databind.JsonNode floor = file.get("floors").get(OrphanAdoptionFloors.key(root));
        return floor == null ? null : floor.longValue();
    }

    private static Path writeModifiedAt(Path file, long modifiedMs) throws Exception {
        Files.writeString(file, "recording bytes");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(modifiedMs));
        return file;
    }

    private static Path writeModifiedAt(Path file, byte[] bytes, long modifiedMs) throws Exception {
        Files.write(file, bytes);
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(modifiedMs));
        return file;
    }

    /** {@code size} bytes of a recording, none of them zero. */
    private static byte[] recordingOf(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) (1 + i % 251);
        }
        return bytes;
    }

    /**
     * {@code bytes} as an older archiver's copy cut short at {@code at} holds them: CopyFileEx sized
     * the copy to full length up front, so from there on it reads as zeros.
     */
    private static byte[] cutShortAt(byte[] bytes, int at) {
        byte[] copy = bytes.clone();
        java.util.Arrays.fill(copy, at, copy.length, (byte) 0);
        return copy;
    }

    /** A match row on its archived copy {@code <archive>/<id>-<name>}, which holds {@code bytes}. */
    private long archivedRow(Path archive, String name, byte[] bytes) throws Exception {
        long id = insertMatch("placeholder", null, (long) bytes.length);
        Path copy = writeModifiedAt(archive.resolve(id + "-" + name), bytes, LONG_AGO_MS);
        matches.updateVideoPath(id, copy.toString(), null);
        return id;
    }

    /** A session that crashed mid-recording with no journaled path; its link window spans ~5_000ms. */
    private RecordingSessionRow crashedSessionRow() {
        return new RecordingSessionRow(
                "session-crash",
                "surrogate-crash",
                "recording",
                1234L,
                "npc_dota_hero_puck",
                1_000L,
                1_000L,
                6_000L,
                "DOTA_GAMERULES_STATE_GAME_IN_PROGRESS",
                1,
                1,
                1,
                null,
                null,
                900L,
                6_000L);
    }

    private void assertJournalRowWithoutVideo() {
        assertThat(matches.findAll())
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.dotaMatchId()).isEqualTo(1234L);
                            assertThat(row.videoPath()).isNull();
                        });
        assertThat(journal.findUnfinished()).isEmpty();
    }

    /**
     * Ages a file's last-modified time well past the orphan-import quiescence window so the scan treats
     * it as a genuine crash orphan (rather than a possibly-live recording) and adopts it. Freshly-written
     * files keep an mtime ~now, which the quiescence guard skips.
     */
    private static void makeStale(Path file) throws Exception {
        Files.setLastModifiedTime(
                file,
                java.nio.file.attribute.FileTime.fromMillis(
                        System.currentTimeMillis() - 10L * 60_000L));
    }

    /**
     * Adds an archive storage location at {@code <tmp>/<name>} the recorder has had since before any
     * file on it, and returns its directory.
     */
    private Path configureArchive(String name) throws Exception {
        Path archiveDir = chooseArchive(name);
        seedAdoptionFloor(archiveDir, 0L);
        return archiveDir;
    }

    /** Points recording at {@code <tmp>/<name>}, a folder no scan has read, so it has no floor yet. */
    Path chooseVideoDir(String name) throws Exception {
        Path chosen = Files.createDirectories(dir.resolve(name));
        settings.update(
                s -> {
                    s.videoDir = chosen.toString();
                    return s;
                });
        return chosen;
    }

    /** Records {@code root}'s adoption floor as an earlier scan would have. */
    void seedAdoptionFloor(Path root, long fromMs) throws Exception {
        Path file = paths.dataDir().resolve(OrphanAdoptionFloors.FILE_NAME);
        java.util.Map<String, Long> floors = OrphanAdoptionFloors.read(file);
        floors.put(OrphanAdoptionFloors.key(root), fromMs);
        OrphanAdoptionFloors.write(file, floors);
    }

    /** Adds an archive storage location at {@code <tmp>/<name>} no scan has read. */
    Path chooseArchive(String name) throws Exception {
        Path archiveDir = Files.createDirectories(dir.resolve(name));
        settings.update(
                s -> {
                    if (s.storageLocations == null) {
                        s.storageLocations = new java.util.ArrayList<>();
                    }
                    s.storageLocations.add(
                            new SettingsStore.StorageLocation(name, archiveDir.toString(), 100));
                    return s;
                });
        return archiveDir;
    }

    /** Removes the archive storage location {@code id} from the settings, leaving its folder. */
    void removeArchive(String id) {
        settings.update(
                s -> {
                    s.storageLocations.removeIf(location -> id.equals(location.id()));
                    return s;
                });
    }

    /** Inserts a minimal finalized match row pointing at {@code videoPath} and returns its id. */
    private long insertMatch(String videoPath, String thumbPath, Long fileSizeBytes) {
        return matches.insert(
                new NewMatch(
                        null, "match", "enriched", "npc_dota_hero_puck",
                        null, null, null, null, null, null, null,
                        null, null, null, null, null, null,
                        1_000L, videoPath, thumbPath, fileSizeBytes, false, 1_000L, null));
    }

    /** Inserts a ready clip for {@code parentId} pointing at {@code videoPath} and returns its id. */
    private long insertClip(long parentId, String videoPath) {
        return insertClip(parentId, videoPath, 2_048L);
    }

    /** Inserts a ready clip with an explicit recorded {@code fileSizeBytes} (null when never rendered). */
    private long insertClip(long parentId, String videoPath, Long fileSizeBytes) {
        return clips.insert(
                parentId, "auto", "rampage", 10.0, 30.0, null, videoPath, null, fileSizeBytes, "ready",
                null, 1_000L);
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

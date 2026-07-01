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

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        dir = tmp;
        ds = TestDb.migrated(tmp);
        journal = new RecordingSessionRepository(ds);
        matches = new MatchRepository(ds);
        clips = new ClipRepository(ds);
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
                        journal, matches, clips, markers, pauses, ds, new ObjectMapper(), settings);
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
        Path orphanMkv = videoDir.resolve("orphan.mkv");
        Path orphanMov = videoDir.resolve("orphan.mov");
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
        Path live = videoDir.resolve("live.mp4");
        Path crashed = videoDir.resolve("crashed.mp4");
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

    @Test
    void relinksStrandedArchiveFileToOriginalRowWhenItsVideoIsMissing() throws Exception {
        Path archiveDir = configureArchive("hdd");
        // The row's recorded path no longer exists on disk: an interrupted cross-store move consumed the
        // source (atomic rename) before the repoint committed, so the row points at nothing.
        long id = insertMatch(videoDir.resolve("gone.mp4").toString(), null, 2_048L);
        // The relocated copy survived on the archive drive under its id-prefixed name, unreferenced.
        Path stranded = archiveDir.resolve(id + "-gone.mp4");
        Files.writeString(stranded, "the real recording bytes");

        runner.run(null);

        // No duplicate row: the ORIGINAL row is re-linked to the recovered archive copy.
        assertThat(matches.findAll()).hasSize(1);
        assertThat(matches.findById(id).orElseThrow().videoPath()).isEqualTo(stranded.toString());
        assertThat(Files.exists(stranded)).isTrue();
    }

    @Test
    void relinkAlsoRecoversTheArchivedThumbnail() throws Exception {
        Path archiveDir = configureArchive("hdd");
        long id = insertMatch(videoDir.resolve("gone.mp4").toString(), null, 2_048L);
        Path stranded = archiveDir.resolve(id + "-gone.mp4");
        Files.writeString(stranded, "bytes");
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
        // move residue, so it must be imported (never deleted), mirroring the clip path's policy of never
        // destroying an unattributable file.
        Path notResidue = archiveDir.resolve(id + "-grand-final.mp4");
        Files.writeString(notResidue, "a completely unrelated user recording");
        makeStale(notResidue);

        runner.run(null);

        // The unattributable file survives...
        assertThat(Files.exists(notResidue)).isTrue();
        // ...and is adopted as a standalone gsi_only row rather than deleted.
        assertThat(matches.findAll())
                .filteredOn(row -> notResidue.toString().equals(row.videoPath()))
                .singleElement()
                .satisfies(row -> assertThat(row.enrichmentState()).isEqualTo("gsi_only"));
        // The intact row is untouched.
        assertThat(matches.findById(id).orElseThrow().videoPath()).isEqualTo(keep.toString());
    }

    @Test
    void doesNotDeleteIdPrefixedArchiveFileWithMatchingNameButDifferentSize() throws Exception {
        Path archiveDir = configureArchive("hdd");
        // A trickier collision: the remainder DOES match the intact row's name, but the on-disk sizes
        // differ (e.g. a truncated partial copy, or a coincidental same-named unrelated file). Without
        // the size check this would be wrongly deleted; the attribution guard keeps it.
        Path keep = videoDir.resolve("keep.mp4");
        Files.writeString(keep, "intact recording");
        long id = insertMatch(keep.toString(), null, Files.size(keep));
        Path sameNameDifferentBytes = archiveDir.resolve(id + "-keep.mp4");
        Files.writeString(sameNameDifferentBytes, "a longer, different set of bytes entirely");
        makeStale(sameNameDifferentBytes);

        runner.run(null);

        // Not provably residue (size mismatch) -> imported, not deleted.
        assertThat(Files.exists(sameNameDifferentBytes)).isTrue();
        assertThat(matches.findAll())
                .filteredOn(row -> sameNameDifferentBytes.toString().equals(row.videoPath()))
                .singleElement()
                .satisfies(row -> assertThat(row.enrichmentState()).isEqualTo("gsi_only"));
        assertThat(matches.findById(id).orElseThrow().videoPath()).isEqualTo(keep.toString());
    }

    @Test
    void importsUnprefixedArchiveOrphanAsGsiOnlyRow() throws Exception {
        Path archiveDir = configureArchive("hdd");
        // A recording on the archive drive with no id prefix (a user-placed file, or one whose row was
        // deleted) has nothing to re-link to, so it is adopted as a standalone gsi_only row.
        Path orphan = archiveDir.resolve("manual.mp4");
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
        // before repointing), so the archive-drive copy is a redundant duplicate to reclaim.
        Path clipSource = Files.createDirectories(videoDir.resolve("clips")).resolve("1-clip-1.mp4");
        Files.writeString(clipSource, "the live clip");
        long clipId = insertClip(parentId, clipSource.toString());
        Path leftover = archiveDir.resolve("clip-" + clipId + "-1-clip-1.mp4");
        Files.writeString(leftover, "redundant copy");

        runner.run(null);

        // Redundant leftover removed; the clip row is untouched; no bogus match row imported.
        assertThat(Files.exists(leftover)).isFalse();
        assertThat(clips.findById(clipId).orElseThrow().videoPath()).isEqualTo(clipSource.toString());
        assertThat(matches.findAll()).hasSize(1);
    }

    @Test
    void relinksStrandedArchivedClipWhenItsVideoMissing() throws Exception {
        Path archiveDir = configureArchive("hdd");
        long parentId = insertMatch(videoDir.resolve("parent.mp4").toString(), null, 4_096L);
        // The clip row points at a source that no longer exists (a same-filesystem rename consumed it
        // before the repoint committed), while the relocated copy survives on the archive drive.
        long clipId = insertClip(parentId, videoDir.resolve("clips").resolve("gone-clip.mp4").toString());
        Path stranded = archiveDir.resolve("clip-" + clipId + "-1-clip-1.mp4");
        Files.writeString(stranded, "the real clip bytes");

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
        long clipId = insertClip(parentId, videoDir.resolve("clips").resolve("gone-clip.mp4").toString());
        Path stranded = archiveDir.resolve("clip-" + clipId + "-1-clip-1.mp4");
        Files.writeString(stranded, "the real clip bytes");
        // The move also relocated the clip thumbnail under <archive>/thumbs/clip-<clipId>-...; recovery
        // must find it via the clip prefix (exercises recoverArchivedThumb's clip-prefix branch).
        Path strandedThumb =
                Files.createDirectories(archiveDir.resolve("thumbs")).resolve("clip-" + clipId + "-gone.jpg");
        Files.writeString(strandedThumb, "thumb");

        runner.run(null);

        ClipRow row = clips.findById(clipId).orElseThrow();
        assertThat(row.videoPath()).isEqualTo(stranded.toString());
        assertThat(row.thumbPath()).isEqualTo(strandedThumb.toString());
    }

    @Test
    void importsClipPrefixedArchiveFileAsGsiOnlyWhenNoClipRowExists() throws Exception {
        Path archiveDir = configureArchive("hdd");
        // A "clip-<id>-" file on the archive drive whose clip row no longer exists (the clip was
        // deleted). leadingClipId parses it, recoverArchivedClipLeftover finds no row and returns false,
        // so it falls through to a standalone gsi_only import rather than being deleted.
        Path orphan = archiveDir.resolve("clip-99-x.mp4");
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

    /** Adds an archive storage location at {@code <tmp>/<name>} and returns its directory. */
    private Path configureArchive(String name) throws Exception {
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

    /** Inserts a minimal finalized match row pointing at {@code videoPath} and returns its id. */
    private long insertMatch(String videoPath, String thumbPath, long fileSizeBytes) {
        return matches.insert(
                new NewMatch(
                        null, "match", "enriched", "npc_dota_hero_puck",
                        null, null, null, null, null, null, null,
                        null, null, null, null, null, null,
                        1_000L, videoPath, thumbPath, fileSizeBytes, false, 1_000L, null));
    }

    /** Inserts a ready clip for {@code parentId} pointing at {@code videoPath} and returns its id. */
    private long insertClip(long parentId, String videoPath) {
        return clips.insert(
                parentId, "auto", "rampage", 10.0, 30.0, null, videoPath, null, 2_048L, "ready", null,
                1_000L);
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

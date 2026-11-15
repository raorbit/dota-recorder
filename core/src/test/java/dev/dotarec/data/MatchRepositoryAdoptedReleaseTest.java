package dev.dotarec.data;

import dev.dotarec.data.MatchRepository.NewMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the release of the rows the orphan import adopted before the adoption floors: {@link
 * MatchRepository#findAdoptedBefore} selects only unstarred {@code gsi_only} rows created before the
 * cutoff that still hold a video, and {@link MatchRepository#deleteAdopted} and {@link
 * MatchRepository#dropAdoptedVideo} act only on a row that is still that, pointing at the video the
 * caller read, so a star or a repoint since wins. Neither touches a file.
 */
class MatchRepositoryAdoptedReleaseTest {

    private DataSource ds;
    private MatchRepository repo;
    private ClipRepository clips;
    private MarkerRepository markers;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        ds = TestDb.migrated(dir);
        repo = new MatchRepository(ds);
        clips = new ClipRepository(ds);
        markers = new MarkerRepository(ds);
    }

    @Test
    void findsOnlyUnstarredAdoptedRowsWithAVideoFromBeforeTheCutoff() {
        long adopted = insert("gsi_only", "C:\\v\\a.mp4", false, 1_000L);
        insert("gsi_only", "C:\\v\\b.mp4", true, 1_000L);
        insert("gsi_only", null, false, 1_000L);
        insert("gsi_only", "C:\\v\\c.mp4", false, 5_000L);
        insert("pending", "C:\\v\\d.mp4", false, 1_000L);

        assertThat(repo.findAdoptedBefore(5_000L)).extracting(MatchSummary::id).containsExactly(adopted);
    }

    @Test
    void deleteAdoptedDeletesOnlyABareRowThatIsStillAdoptedUnstarredAndUnmoved() throws Exception {
        long bare = insert("gsi_only", "C:\\v\\a.mp4", false, 1_000L);
        long starred = insert("gsi_only", "C:\\v\\b.mp4", false, 1_000L);
        repo.setStarred(starred, true);
        long moved = insert("gsi_only", "C:\\v\\c.mp4", false, 1_000L);
        repo.updateVideoPath(moved, "D:\\archive\\" + moved + "-c.mp4", null);
        long recorded = insert("enriched", "C:\\v\\d.mp4", false, 1_000L);
        long marked = insert("gsi_only", "C:\\v\\e.mp4", false, 1_000L);
        markers.insert(marked, "kill", 1.0, 60, "Kill", "gsi");
        long clipped = insert("gsi_only", "C:\\v\\f.mp4", false, 1_000L);
        clips.insert(clipped, "manual", null, 1.0, 5.0, null, "C:\\v\\clips\\f.mp4", null, 10L, "ready", null, 1_000L);

        try (Connection conn = ds.getConnection()) {
            assertThat(repo.deleteAdopted(conn, bare, "C:\\v\\a.mp4")).isTrue();
            assertThat(repo.deleteAdopted(conn, starred, "C:\\v\\b.mp4")).isFalse();
            assertThat(repo.deleteAdopted(conn, moved, "C:\\v\\c.mp4")).isFalse();
            assertThat(repo.deleteAdopted(conn, recorded, "C:\\v\\d.mp4")).isFalse();
            assertThat(repo.deleteAdopted(conn, marked, "C:\\v\\e.mp4")).isFalse();
            assertThat(repo.deleteAdopted(conn, clipped, "C:\\v\\f.mp4")).isFalse();
        }

        assertThat(repo.findById(bare)).isEmpty();
        assertThat(repo.findAll()).extracting(MatchSummary::id)
                .containsExactlyInAnyOrder(starred, moved, recorded, marked, clipped);
    }

    @Test
    void dropAdoptedVideoKeepsTheRowAndRefusesWhileAClipIsStillToBeCut() throws Exception {
        long clipped = insert("gsi_only", "C:\\v\\a.mp4", false, 1_000L);
        long clipId = clips.insert(clipped, "manual", null, 1.0, 5.0, null, "C:\\v\\clips\\a.mp4", null, 10L,
                "ready", null, 1_000L);
        long cutting = insert("gsi_only", "C:\\v\\b.mp4", false, 1_000L);
        clips.insert(cutting, "manual", null, 1.0, 5.0, null, null, null, null, "generating", null, 1_000L);
        long starred = insert("gsi_only", "C:\\v\\c.mp4", false, 1_000L);
        repo.setStarred(starred, true);
        long moved = insert("gsi_only", "C:\\v\\d.mp4", false, 1_000L);
        repo.updateVideoPath(moved, "D:\\archive\\" + moved + "-d.mp4", null);

        try (Connection conn = ds.getConnection()) {
            assertThat(repo.dropAdoptedVideo(conn, clipped, "C:\\v\\a.mp4")).isTrue();
            assertThat(repo.dropAdoptedVideo(conn, cutting, "C:\\v\\b.mp4")).isFalse();
            assertThat(repo.dropAdoptedVideo(conn, starred, "C:\\v\\c.mp4")).isFalse();
            assertThat(repo.dropAdoptedVideo(conn, moved, "C:\\v\\d.mp4")).isFalse();
        }

        MatchSummary dropped = repo.findById(clipped).orElseThrow();
        assertThat(dropped.videoPath()).isNull();
        assertThat(dropped.fileSizeBytes()).isNull();
        assertThat(clips.findById(clipId)).isPresent();
        assertThat(repo.findById(cutting).orElseThrow().videoPath()).isEqualTo("C:\\v\\b.mp4");
        assertThat(repo.findById(starred).orElseThrow().videoPath()).isEqualTo("C:\\v\\c.mp4");
        assertThat(repo.findById(moved).orElseThrow().videoPath()).isEqualTo("D:\\archive\\" + moved + "-d.mp4");
    }

    private long insert(String enrichmentState, String videoPath, boolean starred, long createdAt) {
        return repo.insert(new NewMatch(
                null, "match", enrichmentState, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                900L, videoPath, null, videoPath == null ? null : 100L, starred, createdAt, null));
    }
}

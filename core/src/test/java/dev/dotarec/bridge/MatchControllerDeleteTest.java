package dev.dotarec.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dotarec.data.MarkerRepository;
import dev.dotarec.data.MatchRepository;
import dev.dotarec.data.MatchRepository.NewMatch;
import dev.dotarec.data.PauseRepository;
import dev.dotarec.data.TestDb;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code DELETE /matches/{id}} — removes the row (markers + pauses cascade via the FK) and unlinks the
 * {@code .mp4} + thumbnail. File unlinks are best-effort: a missing file must not block the row delete.
 */
class MatchControllerDeleteTest {

    @TempDir Path dir;

    private DataSource ds;
    private MatchRepository repo;
    private MarkerRepository markers;
    private MatchController controller;

    @BeforeEach
    void setUp() throws Exception {
        ds = TestDb.migrated(dir);
        repo = new MatchRepository(ds);
        markers = new MarkerRepository(ds);
        controller = new MatchController(repo, markers, new PauseRepository(ds));
    }

    @Test
    void delete_removesRow_files_andCascadesMarkers() throws Exception {
        Path vod = writeFile("match.mp4", new byte[] {1, 2, 3});
        Path thumb = writeFile("match.jpg", new byte[] {4, 5});
        long id = insert(vod.toString(), thumb.toString());
        markers.insert(id, "death", 12.5, null, "died", "gsi");
        assertThat(markers.findByMatchId(id)).hasSize(1);

        controller.delete(id);

        assertThat(repo.findById(id)).isEmpty();
        assertThat(Files.exists(vod)).isFalse();
        assertThat(Files.exists(thumb)).isFalse();
        // Markers cascade away with the parent row (ON DELETE CASCADE + foreign_keys=ON).
        assertThat(markers.findByMatchId(id)).isEmpty();
    }

    @Test
    void delete_unknownId_throws404() {
        assertThatThrownBy(() -> controller.delete(999_999L))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void delete_withMissingOrNullFiles_stillRemovesRow() {
        long noFiles = insert(null, null);
        controller.delete(noFiles);
        assertThat(repo.findById(noFiles)).isEmpty();

        long ghostFile = insert(dir.resolve("gone.mp4").toString(), null);
        controller.delete(ghostFile);
        assertThat(repo.findById(ghostFile)).isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Path writeFile(String name, byte[] data) throws Exception {
        Path p = dir.resolve(name);
        Files.write(p, data);
        return p;
    }

    private long insert(String videoPath, String thumbPath) {
        return repo.insert(
                new NewMatch(
                        null, "match", "pending", "rubick",
                        null, null, null, null, null, null, null, null, null, null, null, null,
                        null, null, videoPath, thumbPath, null, false, 1L, null));
    }
}

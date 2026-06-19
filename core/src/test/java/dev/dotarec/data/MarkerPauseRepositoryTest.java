package dev.dotarec.data;

import dev.dotarec.data.MatchRepository.NewMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Round-trips markers and pauses: insert ordering, nullable columns, and pause open/close. */
class MarkerPauseRepositoryTest {

    private MatchRepository matches;
    private MarkerRepository markers;
    private PauseRepository pauses;
    private long matchId;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        DataSource ds = TestDb.migrated(dir);
        matches = new MatchRepository(ds);
        markers = new MarkerRepository(ds);
        pauses = new PauseRepository(ds);
        matchId = matches.insert(new NewMatch(
                null, "match", "enriched", "puck",
                null, null, null, null, null, null, null,
                "win", 7, 22, null, null, 1800,
                1_000L, "C:/v/m.mp4", null, 1024L, false, 1_000L, null));
    }

    @Test
    void markersReturnInVideoOffsetOrder() {
        markers.insert(matchId, "death", 120.5, 300, "first blood", "gsi");
        markers.insert(matchId, "kill", 30.0, 90, null, "gsi");
        markers.insert(matchId, "roshan", 75.25, null, "rosh", "replay");

        List<MarkerRow> rows = markers.findByMatchId(matchId);
        assertThat(rows).extracting(MarkerRow::videoOffsetS)
                .containsExactly(30.0, 75.25, 120.5);
        // Ordered by offset: [0]=kill@30 (gsi, clock 90), [1]=roshan@75.25 (replay, null clock),
        // [2]=death@120.5 (gsi, clock 300). Nullable game_clock + source survive the round trip.
        assertThat(rows.get(0).gameClock()).isEqualTo(90);
        assertThat(rows.get(1).gameClock()).isNull();
        assertThat(rows.get(1).source()).isEqualTo("replay");
        assertThat(rows.get(2).gameClock()).isEqualTo(300);
    }

    @Test
    void markersScopedToTheirMatch() {
        long other = matches.insert(new NewMatch(
                null, "match", "enriched", "lina",
                null, null, null, null, null, null, null,
                "loss", 7, 22, null, null, 1200,
                2_000L, "C:/v/o.mp4", null, 2048L, false, 2_000L, null));
        markers.insert(matchId, "kill", 10.0, 30, null, "gsi");
        markers.insert(other, "kill", 10.0, 30, null, "gsi");

        assertThat(markers.findByMatchId(matchId)).hasSize(1);
        assertThat(markers.findByMatchId(other)).hasSize(1);
    }

    @Test
    void pauseOpensWithNullEndThenCloses() {
        long pauseId = pauses.open(matchId, 5_000L);
        List<PauseSpan> open = pauses.findByMatchId(matchId);
        assertThat(open).hasSize(1);
        assertThat(open.get(0).endWall()).isNull();

        int updated = pauses.close(pauseId, 8_000L);
        assertThat(updated).isEqualTo(1);
        assertThat(pauses.findByMatchId(matchId).get(0).endWall()).isEqualTo(8_000L);

        // Closing an already-closed span is a no-op.
        assertThat(pauses.close(pauseId, 9_000L)).isZero();
    }

    @Test
    void pausesReturnChronologically() {
        pauses.insert(matchId, 3_000L, 4_000L);
        pauses.insert(matchId, 1_000L, 2_000L);
        assertThat(pauses.findByMatchId(matchId)).extracting(PauseSpan::startWall)
                .containsExactly(1_000L, 3_000L);
    }
}

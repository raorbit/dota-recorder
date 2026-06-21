package dev.dotarec.enrich;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dotarec.data.MatchRepository;
import dev.dotarec.data.MatchRepository.EnrichmentUpdate;
import dev.dotarec.data.MatchRepository.NewMatch;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.dotarec.data.TestDb;

/**
 * Verifies the queue's eligibility filter and dispatch against a real SQLite repo with a recording
 * enricher: only {@code pending} match rows with a non-null dota id, under the attempt cap, and past
 * their backoff window are dispatched; the per-tick LIMIT bounds a backlog; the row-id (not dota id)
 * is what gets dispatched.
 */
class EnrichmentQueueTest {

    private MatchRepository repo;
    private RecordingEnricher enricher;
    private EnrichmentQueue queue;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        DataSource ds = TestDb.migrated(tmp);
        repo = new MatchRepository(ds);
        enricher = new RecordingEnricher();
        queue = new EnrichmentQueue(repo, enricher);
    }

    @Test
    void dispatchesRowIdAndDotaIdPair() {
        long id = insertPending(4242L);
        queue.sweep();
        assertThat(enricher.dispatched).hasSize(1);
        assertThat(enricher.dispatched.get(0).rowId()).isEqualTo(id);
        assertThat(enricher.dispatched.get(0).dotaId()).isEqualTo(4242L);
    }

    @Test
    void skipsRowsWithNullDotaMatchId() {
        // A manual row (null dota_match_id) must never be dispatched.
        repo.insert(new NewMatch(
                null, "manual", "pending", "rubick",
                null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, false, 1_000L, null));
        long withId = insertPending(7L);

        queue.sweep();

        assertThat(enricher.dispatched).hasSize(1);
        assertThat(enricher.dispatched.get(0).dotaId()).isEqualTo(7L);
        assertThat(enricher.dispatched.get(0).rowId()).isEqualTo(withId);
    }

    @Test
    void skipsNonPendingRows() {
        insertWithState(10L, "enriched");
        insertWithState(11L, "failed");
        insertWithState(12L, "gsi_only");
        long pending = insertPending(13L);

        queue.sweep();

        assertThat(enricher.dispatched).hasSize(1);
        assertThat(enricher.dispatched.get(0).dotaId()).isEqualTo(13L);
        assertThat(enricher.dispatched.get(0).rowId()).isEqualTo(pending);
    }

    @Test
    void skipsRowsAtOrOverTheAttemptCap() {
        long under = insertPending(20L);
        long atCap = insertPending(21L);
        repo.applyEnrichment(atCap, pendingAttempts(EnrichmentQueue.MAX_ATTEMPTS));

        queue.sweep();

        assertThat(enricher.dispatched).hasSize(1);
        assertThat(enricher.dispatched.get(0).rowId()).isEqualTo(under);
    }

    @Test
    void skipsRowsStillInsideBackoffWindow() {
        long ready = insertPending(30L);
        long backedOff = insertPending(31L);
        // next-after far in the future -> not yet eligible.
        repo.applyEnrichment(backedOff, new EnrichmentUpdate(
                null, null, null, null, null, null, null, null, null, null,
                "pending", 1, System.currentTimeMillis() + 10 * 60_000L));

        queue.sweep();

        assertThat(enricher.dispatched).hasSize(1);
        assertThat(enricher.dispatched.get(0).rowId()).isEqualTo(ready);
    }

    @Test
    void limitsDispatchToTwentyFivePerTick() {
        for (int i = 0; i < 40; i++) {
            insertPending(1000L + i);
        }
        queue.sweep();
        // LIMIT 25 in the repo query bounds a backlog so the pool can't be flooded in one tick.
        assertThat(enricher.dispatched).hasSize(25);
    }

    // ---- helpers -----------------------------------------------------------

    private long insertPending(long dotaMatchId) {
        return insertWithState(dotaMatchId, "pending");
    }

    private long insertWithState(long dotaMatchId, String state) {
        return repo.insert(new NewMatch(
                dotaMatchId, "match", state, "rubick",
                null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, false, 1_000L, null));
    }

    private static EnrichmentUpdate pendingAttempts(int attempts) {
        return new EnrichmentUpdate(
                null, null, null, null, null, null, null, null, null, null,
                "pending", attempts, null);
    }

    /** Enricher subclass that records (rowId, dotaId) dispatches instead of running async work. */
    private static final class RecordingEnricher extends Enricher {
        private final List<Dispatch> dispatched = new ArrayList<>();

        RecordingEnricher() {
            super(null, null, null, null);
        }

        @Override
        public void enrich(long matchRowId, long dotaMatchId) {
            dispatched.add(new Dispatch(matchRowId, dotaMatchId));
        }
    }

    private record Dispatch(long rowId, long dotaId) {
    }
}

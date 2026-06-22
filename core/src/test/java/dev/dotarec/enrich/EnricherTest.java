package dev.dotarec.enrich;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dotarec.bridge.EventPublisher;
import dev.dotarec.config.AppPaths;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.data.MatchRepository;
import dev.dotarec.data.MatchRepository.NewMatch;
import dev.dotarec.data.MatchSummary;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.dotarec.data.TestDb;

/**
 * Exercises the enricher's state machine over a real SQLite repo with a fake {@link MatchSource}:
 * the win formula (radiant + dire), full field mapping, idempotent re-enrich, the
 * NotReady/Missing -> stays-pending path, the null-{@code dota_match_id} skip, and the
 * our-player-absent permanent fail. No network, no Spring context.
 */
class EnricherTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private MatchRepository repo;
    private SettingsStore settings;
    private RecordingPublisher events;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        DataSource ds = TestDb.migrated(tmp);
        repo = new MatchRepository(ds);
        AppPaths paths = new AppPaths(tmp.resolve("data").toString(), tmp.resolve("obs").toString());
        settings = new SettingsStore(paths);
        settings.update(s -> { s.accountId = 96828122L; return s; });
        events = new RecordingPublisher();
    }

    @Test
    void readyRadiantWinFullyEnrichesAndPublishes() throws Exception {
        long id = insertPending(7654321098L);
        Enricher enricher = enricher(FetchResult.ready(parse("opendota/match_response.json")));

        enricher.enrich(id, 7654321098L);

        MatchSummary row = repo.findById(id).orElseThrow();
        assertThat(row.enrichmentState()).isEqualTo("enriched");
        // account 96828122 is slot 0 (Radiant) and radiant_win=true -> win.
        assertThat(row.result()).isEqualTo("win");
        assertThat(row.lobbyType()).isEqualTo(7);
        assertThat(row.gameMode()).isEqualTo(22);
        assertThat(row.durationS()).isEqualTo(2415);
        assertThat(row.playedAt()).isEqualTo(1718900000L * 1000L);
        assertThat(row.gpm()).isEqualTo(412);
        assertThat(row.xpm()).isEqualTo(533);
        assertThat(row.netWorth()).isEqualTo(18240);
        assertThat(row.lastHits()).isEqualTo(121);
        assertThat(row.rankTier()).isEqualTo(74);
        assertThat(row.mmrDelta()).isNull(); // no API has it

        assertThat(events.types()).containsExactly("match.enriched");
        assertThat(events.last().payload()).isEqualTo(
                Map.of("id", id, "dotaMatchId", 7654321098L));
    }

    @Test
    void winFormulaLossForRadiantPlayerWhenDireWins() throws Exception {
        // Our account slot 0 (Radiant) but radiant_win=false -> loss.
        long id = insertPending(900L);
        OpenDotaMatch match = new OpenDotaMatch(900L, false, 1800, 1000L, 7, 22,
                List.of(player(96828122L, 0)));
        enricher(FetchResult.ready(match)).enrich(id, 900L);
        assertThat(repo.findById(id).orElseThrow().result()).isEqualTo("loss");
    }

    @Test
    void winFormulaWinForDirePlayerWhenDireWins() throws Exception {
        // Our account on Dire (slot 128) and radiant_win=false -> win.
        long id = insertPending(901L);
        OpenDotaMatch match = new OpenDotaMatch(901L, false, 1800, 1000L, 0, 22,
                List.of(player(96828122L, 128)));
        enricher(FetchResult.ready(match)).enrich(id, 901L);
        assertThat(repo.findById(id).orElseThrow().result()).isEqualTo("win");
    }

    @Test
    void notReadyLeavesRowPendingAndBumpsAttempts() throws Exception {
        long id = insertPending(902L);
        enricher(FetchResult.NotReady.INSTANCE).enrich(id, 902L);

        MatchSummary row = repo.findById(id).orElseThrow();
        assertThat(row.enrichmentState()).isEqualTo("pending");
        assertThat(repo.enrichAttempts(id)).isEqualTo(1);
        assertThat(events.types()).isEmpty();
    }

    @Test
    void missingLeavesRowPendingAndBumpsAttempts() throws Exception {
        long id = insertPending(903L);
        enricher(FetchResult.Missing.INSTANCE).enrich(id, 903L);

        assertThat(repo.findById(id).orElseThrow().enrichmentState()).isEqualTo("pending");
        assertThat(repo.enrichAttempts(id)).isEqualTo(1);
        assertThat(events.types()).isEmpty();
    }

    @Test
    void crossingAttemptCapFlipsToFailedAndPublishes() {
        long id = insertPendingWithAttempts(904L, Enricher.MAX_ATTEMPTS - 1);
        enricher(FetchResult.Missing.INSTANCE).enrich(id, 904L);

        assertThat(repo.findById(id).orElseThrow().enrichmentState()).isEqualTo("failed");
        assertThat(events.types()).containsExactly("match.enrichFailed");
        assertThat(events.last().payload()).isEqualTo(Map.of("id", id));
    }

    @Test
    void ourPlayerAbsentIsPermanentFail() throws Exception {
        long id = insertPending(905L);
        // Scoreboard without our account id -> can't attribute -> failed.
        OpenDotaMatch match = new OpenDotaMatch(905L, true, 1800, 1000L, 7, 22,
                List.of(player(55555555L, 0)));
        enricher(FetchResult.ready(match)).enrich(id, 905L);

        assertThat(repo.findById(id).orElseThrow().enrichmentState()).isEqualTo("failed");
        assertThat(events.types()).containsExactly("match.enrichFailed");
    }

    @Test
    void reEnrichOfEnrichedRowIsIdempotentNoOp() throws Exception {
        long id = insertPending(7654321098L);
        Enricher enricher = enricher(FetchResult.ready(parse("opendota/match_response.json")));

        enricher.enrich(id, 7654321098L);
        events.clear();
        // Second dispatch: short-circuits on state='enriched', no new publish, row unchanged.
        enricher.enrich(id, 7654321098L);

        assertThat(repo.findById(id).orElseThrow().enrichmentState()).isEqualTo("enriched");
        assertThat(events.types()).isEmpty();
    }

    @Test
    void nullDotaMatchIdRowIsNeverTouched() {
        // record_kind='manual', dota_match_id null. enrich() must early-return.
        long id = repo.insert(new NewMatch(
                null, "manual", "gsi_only", "rubick",
                null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, false, 1_000L, null));

        // Even if (hypothetically) dispatched, a Ready fetch must not enrich it.
        FailingSource source = new FailingSource();
        new Enricher(source, repo, settings, events).enrich(id, 0L);

        MatchSummary row = repo.findById(id).orElseThrow();
        assertThat(row.enrichmentState()).isEqualTo("gsi_only");
        assertThat(source.fetched).isFalse();
        assertThat(events.types()).isEmpty();
    }

    @Test
    void nullAccountIdHoldsRowInPendingRatherThanFailing() throws Exception {
        settings.update(s -> { s.accountId = null; return s; });
        long id = insertPending(906L);
        enricher(FetchResult.ready(parse("opendota/match_response.json"))).enrich(id, 906L);

        // No account configured -> hold in pending (resumes once set), do NOT fail.
        assertThat(repo.findById(id).orElseThrow().enrichmentState()).isEqualTo("pending");
        assertThat(repo.enrichAttempts(id)).isEqualTo(1);
        assertThat(events.types()).isEmpty();
    }

    @Test
    void retryPreservesRecorderOwnedDurationAndPlayedAt() {
        // The recorder inserts every match with a real duration + played_at. OpenDota lags, so the
        // first poll is almost always NotReady -> the retry path must NOT blank those columns.
        long id = insertPendingWithStats(907L, 1800, 1_700_000_000_000L);
        enricher(FetchResult.NotReady.INSTANCE).enrich(id, 907L);

        MatchSummary row = repo.findById(id).orElseThrow();
        assertThat(row.enrichmentState()).isEqualTo("pending");
        assertThat(row.durationS()).isEqualTo(1800);
        assertThat(row.playedAt()).isEqualTo(1_700_000_000_000L);
        assertThat(repo.enrichAttempts(id)).isEqualTo(1);
    }

    @Test
    void failPreservesRecorderOwnedColumnsOnCapCrossing() {
        long id = insertPendingWithStats(908L, 2400, 1_700_000_000_000L);
        // Bump attempts to one below the cap via the narrow retry path (NOT the wide applyEnrichment,
        // which would itself blank the stats), then let one Missing cross the cap -> failed.
        repo.applyRetry(id, "pending", Enricher.MAX_ATTEMPTS - 1, null);
        enricher(FetchResult.Missing.INSTANCE).enrich(id, 908L);

        MatchSummary row = repo.findById(id).orElseThrow();
        assertThat(row.enrichmentState()).isEqualTo("failed");
        assertThat(row.durationS()).isEqualTo(2400);
        assertThat(row.playedAt()).isEqualTo(1_700_000_000_000L);
    }

    @Test
    void nullRadiantWinHoldsInPendingNotGuessedEnriched() throws Exception {
        long id = insertPending(909L);
        // A Ready body (duration present) whose winner hasn't parsed yet: must not fabricate a result.
        OpenDotaMatch match = new OpenDotaMatch(909L, null, 1800, 1000L, 7, 22,
                List.of(player(96828122L, 0)));
        enricher(FetchResult.ready(match)).enrich(id, 909L);

        MatchSummary row = repo.findById(id).orElseThrow();
        assertThat(row.enrichmentState()).isEqualTo("pending");
        assertThat(row.result()).isNull();
        assertThat(repo.enrichAttempts(id)).isEqualTo(1);
        assertThat(events.types()).isEmpty();
    }

    @Test
    void nullMatchedPlayerSlotHoldsInPending() throws Exception {
        long id = insertPending(910L);
        // Our player is present by account id but has a null player_slot -> side is unknown.
        OpenDotaMatch.Player me = new OpenDotaMatch.Player(
                96828122L, null, 1, 5, 5, 5, 300, 400, 12000, 100, 60);
        OpenDotaMatch match = new OpenDotaMatch(910L, true, 1800, 1000L, 7, 22, List.of(me));
        enricher(FetchResult.ready(match)).enrich(id, 910L);

        MatchSummary row = repo.findById(id).orElseThrow();
        assertThat(row.enrichmentState()).isEqualTo("pending");
        assertThat(row.result()).isNull();
        assertThat(events.types()).isEmpty();
    }

    // ---- helpers -----------------------------------------------------------

    private Enricher enricher(FetchResult result) {
        return new Enricher(new FixedSource(result), repo, settings, events);
    }

    private long insertPending(long dotaMatchId) {
        return insertPendingWithAttempts(dotaMatchId, 0);
    }

    /** Seeds a pending row carrying recorder-owned duration_s + played_at, as the recorder writes them. */
    private long insertPendingWithStats(long dotaMatchId, int durationS, long playedAt) {
        return repo.insert(new NewMatch(
                dotaMatchId, "match", "pending", "rubick",
                null, null, null, null, null, null, null,
                null, null, null, null, null, durationS,
                playedAt, null, null, null, false, 1_000L, null));
    }

    private long insertPendingWithAttempts(long dotaMatchId, int attempts) {
        long id = repo.insert(new NewMatch(
                dotaMatchId, "match", "pending", "rubick",
                null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, false, 1_000L, null));
        if (attempts > 0) {
            // Bump the attempt counter to within one of the cap via a pending applyEnrichment.
            repo.applyEnrichment(id, new MatchRepository.EnrichmentUpdate(
                    null, null, null, null, null, null, null, null, null, null,
                    "pending", attempts, null));
        }
        return id;
    }

    private OpenDotaMatch parse(String classpath) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(classpath)) {
            assertThat(in).isNotNull();
            return mapper.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    OpenDotaMatch.class);
        }
    }

    private static OpenDotaMatch.Player player(long accountId, int slot) {
        return new OpenDotaMatch.Player(accountId, slot, 1, 5, 5, 5, 300, 400, 12000, 100, 60);
    }

    /** MatchSource that always returns one canned result. */
    private record FixedSource(FetchResult result) implements MatchSource {
        @Override
        public FetchResult fetch(long dotaMatchId) {
            return result;
        }
    }

    /** MatchSource that records whether it was called; used to prove the null-id skip never fetches. */
    private static final class FailingSource implements MatchSource {
        boolean fetched = false;

        @Override
        public FetchResult fetch(long dotaMatchId) {
            fetched = true;
            return FetchResult.Transient.INSTANCE;
        }
    }

    /** Captures published events instead of broadcasting over a socket. */
    private static final class RecordingPublisher extends EventPublisher {
        private final List<Event> published = new ArrayList<>();

        RecordingPublisher() {
            super(null, null, null);
        }

        @Override
        public void publish(String type, Object payload) {
            published.add(new Event(type, payload));
        }

        List<String> types() {
            return published.stream().map(Event::type).toList();
        }

        Event last() {
            return published.get(published.size() - 1);
        }

        void clear() {
            published.clear();
        }
    }

    private record Event(String type, Object payload) {
    }
}

package dev.dotarec.data;

import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Plain-JDBC read/write access to the {@code matches} table. JOOQ is a documented TODO (plan stack);
 * until then this hand-rolled mapper is the source of truth for matches access.
 *
 * <p>Covers the browse UI (filtered list + per-bucket counts + single-match detail), the star
 * toggle, and the retention sweep helpers (sum of video bytes, oldest-first sweep candidates, and
 * nulling a pruned row's {@code video_path}/{@code thumb_path} while keeping the row).
 */
@Repository
public class MatchRepository {

    /** Column list shared by every read so the mapper sees a stable shape. */
    private static final String COLUMNS = """
            id, dota_match_id, record_kind, enrichment_state, hero,
            kills, deaths, assists, gpm, xpm, net_worth, last_hits,
            result, lobby_type, game_mode, rank_tier, mmr_delta, duration_s,
            played_at, video_path, thumb_path, file_size_bytes, starred, created_at,
            record_started_wall_ms
            """;

    /** Cap on rows {@link #findPendingEnrichment} returns per tick, so a backlog can't flood the pool. */
    private static final int PENDING_LIMIT = 25;

    /**
     * Lease stamped into {@code enrich_next_after_ms} when {@link #findPendingEnrichment} claims a row
     * for dispatch, so a subsequent sweep won't re-select a row whose fetch is still in flight. It
     * comfortably exceeds the worst-case fetch wall time (a 10s-timeout fetch for up to
     * {@value #PENDING_LIMIT} rows serialized through the 2-thread enrich pool). The lease only
     * suppresses duplicate dispatch: a successful enrich clears the row from eligibility (state
     * becomes {@code enriched}) and a retry overwrites this with the real backoff -- so the only
     * lasting effect is self-healing a row whose async task died mid-fetch once the lease expires.
     */
    private static final long CLAIM_LEASE_MS = 5L * 60_000L;

    private final DataSource dataSource;

    public MatchRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Newest first. Returns an empty list on a fresh DB. */
    public List<MatchSummary> findAll() {
        String sql = "SELECT " + COLUMNS + " FROM matches "
                + "ORDER BY COALESCE(played_at, created_at) DESC, id DESC";
        List<MatchSummary> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(map(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query matches", e);
        }
        return out;
    }

    /** Single match by surrogate id, or empty if no such row. */
    public Optional<MatchSummary> findById(long id) {
        String sql = "SELECT " + COLUMNS + " FROM matches WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query match " + id, e);
        }
    }

    /**
     * True if a {@code matches} row already carries this Dota match id (which is UNIQUE in the
     * schema). Reads on a caller-supplied connection so crash recovery can check within its own
     * transaction: a stranded journal row whose match was already finalized by a later re-record
     * must be dropped, not re-inserted (which would hit the UNIQUE constraint and replay every boot).
     */
    public boolean existsByDotaMatchId(Connection conn, long dotaMatchId) throws SQLException {
        String sql = "SELECT 1 FROM matches WHERE dota_match_id = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, dotaMatchId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Filtered, newest-first match list.
     *
     * <p>Filters are ANDed and all optional:
     * <ul>
     *   <li>{@code bucket} -- one of {@link Bucket}; applies that bucket's exact membership predicate
     *       (un-enriched matches only ever appear under {@code Unsorted}, Turbo/AbilityDraft are
     *       carved out of Unranked). Unknown/blank bucket = no bucket filter.</li>
     *   <li>{@code result} -- exact {@code result} match (e.g. {@code win}/{@code loss}).</li>
     *   <li>{@code q} -- case-insensitive substring over {@code hero}.</li>
     *   <li>{@code fromMs}/{@code toMs} -- inclusive bounds on {@code COALESCE(played_at, created_at)}.</li>
     * </ul>
     *
     * @param bucket bucket key (nullable/blank to skip); see {@link Bucket#fromKey}
     * @param result exact result filter (nullable/blank to skip)
     * @param q      hero substring (nullable/blank to skip)
     * @param fromMs inclusive lower bound on play time, or null
     * @param toMs   inclusive upper bound on play time, or null
     */
    public List<MatchSummary> findMatches(String bucket, String result, String q,
                                          Long fromMs, Long toMs) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM matches WHERE 1 = 1");
        List<Object> params = new ArrayList<>();

        Optional<Bucket> resolved = Bucket.fromKey(bucket);
        // An explicit but unrecognized bucket selects nothing rather than silently returning all
        // rows -- a typo'd category should look empty, not like "everything".
        if (bucket != null && !bucket.isBlank() && resolved.isEmpty()) {
            sql.append(" AND 1 = 0");
        }
        resolved.ifPresent(b -> sql.append(" AND (").append(b.predicate()).append(")"));

        if (result != null && !result.isBlank()) {
            sql.append(" AND result = ?");
            params.add(result.trim());
        }
        if (q != null && !q.isBlank()) {
            // LIKE on SQLite is case-insensitive for ASCII by default; lower() both sides so the
            // hero substring search is predictable regardless of stored casing.
            sql.append(" AND lower(hero) LIKE ?");
            params.add("%" + q.trim().toLowerCase() + "%");
        }
        if (fromMs != null) {
            sql.append(" AND COALESCE(played_at, created_at) >= ?");
            params.add(fromMs);
        }
        if (toMs != null) {
            sql.append(" AND COALESCE(played_at, created_at) <= ?");
            params.add(toMs);
        }
        sql.append(" ORDER BY COALESCE(played_at, created_at) DESC, id DESC");

        List<MatchSummary> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query filtered matches", e);
        }
        return out;
    }

    /**
     * Count per bucket, keyed by {@link Bucket#key()}. Every bucket appears in the map (0 when
     * empty). Each count is computed from the same predicate the list filter uses, so a bucket's
     * count always equals the size of {@code findMatches(bucketKey, ...)} with no other filters.
     */
    public Map<String, Integer> bucketCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            for (Bucket b : Bucket.values()) {
                String sql = "SELECT COUNT(*) FROM matches WHERE " + b.predicate();
                try (ResultSet rs = st.executeQuery(sql)) {
                    counts.put(b.key(), rs.next() ? rs.getInt(1) : 0);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to compute bucket counts", e);
        }
        return counts;
    }

    /**
     * Sets the starred flag on a match. Starred matches are exempt from the retention sweep.
     *
     * @return rows updated (0 if no such match)
     */
    public int setStarred(long id, boolean starred) {
        String sql = "UPDATE matches SET starred = ? WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, starred ? 1 : 0);
            ps.setLong(2, id);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to set starred on match " + id, e);
        }
    }

    // ---- retention helpers -------------------------------------------------

    /**
     * Total bytes of currently-stored video, i.e. {@code SUM(file_size_bytes)} over rows that still
     * have a {@code video_path} (pruned rows have a null path and don't count). Returns 0 on an
     * empty DB or when every file size is null.
     */
    public long totalVideoBytes() {
        String sql = "SELECT COALESCE(SUM(file_size_bytes), 0) FROM matches "
                + "WHERE video_path IS NOT NULL";
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to sum video bytes", e);
        }
    }

    /**
     * Sweep candidates oldest-first: rows still holding a video file ({@code video_path NOT NULL})
     * that are not starred. Ordered by {@code COALESCE(played_at, created_at)} then id ascending so
     * the retention sweeper deletes the oldest recordings first.
     */
    public List<MatchSummary> findSweepCandidates() {
        String sql = "SELECT " + COLUMNS + " FROM matches "
                + "WHERE starred = 0 AND video_path IS NOT NULL "
                + "ORDER BY COALESCE(played_at, created_at) ASC, id ASC";
        List<MatchSummary> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(map(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query sweep candidates", e);
        }
        return out;
    }

    /**
     * Marks a row as pruned: nulls {@code video_path}, {@code thumb_path} and {@code file_size_bytes}
     * while KEEPING the row (so markers/stats survive as a browsable record without a playable clip).
     *
     * @return rows updated (0 if no such match)
     */
    public int nullVideoPath(long id) {
        String sql = "UPDATE matches "
                + "SET video_path = NULL, thumb_path = NULL, file_size_bytes = NULL "
                + "WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to null video path for match " + id, e);
        }
    }

    // ---- insert / seed -----------------------------------------------------

    /**
     * Inserts a match row and returns its generated id. Used by the recorder/enricher to persist a
     * recording and by tests to seed fixtures. {@code createdAt} is required (NOT NULL in the
     * schema); the rest are nullable.
     */
    public long insert(NewMatch m) {
        try (Connection conn = dataSource.getConnection()) {
            return insert(conn, m);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to insert match", e);
        }
    }

    /**
     * Inserts a match row on a caller-supplied connection (which the caller commits/rolls back), so
     * the write can join a larger transaction -- e.g. the recorder persisting a match row plus its
     * markers and pauses atomically. Does not open, commit, or close the connection.
     */
    public long insert(Connection conn, NewMatch m) throws SQLException {
        String sql = """
                INSERT INTO matches (
                    dota_match_id, record_kind, enrichment_state, hero,
                    kills, deaths, assists, gpm, xpm, net_worth, last_hits,
                    result, lobby_type, game_mode, rank_tier, mmr_delta, duration_s,
                    record_started_wall_ms,
                    played_at, video_path, thumb_path, file_size_bytes, starred, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            int i = 1;
            setNullableLong(ps, i++, m.dotaMatchId());
            ps.setString(i++, m.recordKind() != null ? m.recordKind() : "match");
            ps.setString(i++, m.enrichmentState() != null ? m.enrichmentState() : "pending");
            ps.setString(i++, m.hero());
            setNullableInt(ps, i++, m.kills());
            setNullableInt(ps, i++, m.deaths());
            setNullableInt(ps, i++, m.assists());
            setNullableInt(ps, i++, m.gpm());
            setNullableInt(ps, i++, m.xpm());
            setNullableInt(ps, i++, m.netWorth());
            setNullableInt(ps, i++, m.lastHits());
            ps.setString(i++, m.result());
            setNullableInt(ps, i++, m.lobbyType());
            setNullableInt(ps, i++, m.gameMode());
            setNullableInt(ps, i++, m.rankTier());
            setNullableInt(ps, i++, m.mmrDelta());
            setNullableInt(ps, i++, m.durationS());
            setNullableLong(ps, i++, m.recordStartedWallMs());
            setNullableLong(ps, i++, m.playedAt());
            ps.setString(i++, m.videoPath());
            ps.setString(i++, m.thumbPath());
            setNullableLong(ps, i++, m.fileSizeBytes());
            ps.setInt(i++, m.starred() ? 1 : 0);
            ps.setLong(i, m.createdAt());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        }
    }

    // ---- enrichment --------------------------------------------------------

    /**
     * Eligibility query for the enrichment queue: {@code pending} match rows that have a Dota
     * match id, are under the attempt cap, and whose backoff window has elapsed. Oldest-first
     * (by id) and capped at {@value #PENDING_LIMIT} so a backlog can't flood the bounded enrich
     * pool in one tick. The {@code dota_match_id IS NOT NULL} guard ensures manual/clip rows are
     * never fetched.
     *
     * @param maxAttempts exclusive cap on {@code enrich_attempts}
     * @param nowMs       current wall clock; a row is eligible when {@code enrich_next_after_ms}
     *                    is null or has passed
     */
    public List<PendingMatch> findPendingEnrichment(int maxAttempts, long nowMs) {
        String select = "SELECT id, dota_match_id, enrich_attempts FROM matches "
                + "WHERE enrichment_state = 'pending' AND dota_match_id IS NOT NULL "
                + "AND enrich_attempts < ? "
                + "AND (enrich_next_after_ms IS NULL OR enrich_next_after_ms <= ?) "
                + "ORDER BY id ASC LIMIT " + PENDING_LIMIT;
        List<PendingMatch> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            // Select + claim run in one transaction so a concurrent/next sweep can't read the same
            // rows before they're leased. SQLite is configured WAL + busy_timeout, so the brief
            // write lock is safe on this single-user app.
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(select)) {
                    ps.setInt(1, maxAttempts);
                    ps.setLong(2, nowMs);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.add(new PendingMatch(
                                    rs.getLong("id"),
                                    rs.getLong("dota_match_id"),
                                    rs.getInt("enrich_attempts")));
                        }
                    }
                }
                // Claim the selected rows by stamping a short lease into enrich_next_after_ms so the
                // next tick skips them while their fetch is in flight. The enricher's real outcome
                // (enriched, or a retry with the actual backoff) overwrites this lease afterwards.
                if (!out.isEmpty()) {
                    String claim = "UPDATE matches SET enrich_next_after_ms = ? "
                            + "WHERE enrichment_state = 'pending' AND id IN ("
                            + placeholders(out.size()) + ")";
                    try (PreparedStatement ps = conn.prepareStatement(claim)) {
                        int i = 1;
                        ps.setLong(i++, nowMs + CLAIM_LEASE_MS);
                        for (PendingMatch m : out) {
                            ps.setLong(i++, m.id());
                        }
                        ps.executeUpdate();
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query pending enrichment", e);
        }
        return out;
    }

    /**
     * Current {@code enrich_attempts} for a row, or 0 if the row is absent. The enricher reads this
     * to compute the next attempt count + backoff without inflating {@link MatchSummary}.
     */
    public int enrichAttempts(long id) {
        String sql = "SELECT enrich_attempts FROM matches WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read enrich_attempts for match " + id, e);
        }
    }

    /**
     * Applies an enrichment outcome to a single row in one UPDATE. Serves all three enricher
     * paths via the {@link EnrichmentUpdate}'s terminal {@code state}: full enrich
     * ({@code enriched}), partial NotReady ({@code pending}), and permanent fail ({@code failed}).
     * The retry bookkeeping ({@code enrich_attempts}/{@code enrich_next_after_ms}) is written here
     * too so the queue's WHERE sees the bump. {@code mmr_delta} is always bound null (no API has
     * it). Keyed on the surrogate {@code id}.
     *
     * <p>{@code duration_s} and {@code played_at} are recorder-owned: the recorder always writes a
     * real value at finalize. A {@code Ready} body only guarantees a non-null {@code duration}, not
     * a non-null {@code start_time}, so both are bound through {@code COALESCE(?, column)} -- the API
     * value wins when present, but a null one preserves the recorder's value rather than blanking it.
     *
     * @return rows updated (0 if no such match)
     */
    public int applyEnrichment(long id, EnrichmentUpdate u) {
        String sql = "UPDATE matches SET "
                + "result = ?, lobby_type = ?, game_mode = ?, gpm = ?, xpm = ?, net_worth = ?, "
                + "last_hits = ?, rank_tier = ?, mmr_delta = NULL, "
                + "duration_s = COALESCE(?, duration_s), played_at = COALESCE(?, played_at), "
                + "enrichment_state = ?, enrich_attempts = ?, enrich_next_after_ms = ? "
                + "WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, u.result());
            setNullableInt(ps, i++, u.lobbyType());
            setNullableInt(ps, i++, u.gameMode());
            setNullableInt(ps, i++, u.gpm());
            setNullableInt(ps, i++, u.xpm());
            setNullableInt(ps, i++, u.netWorth());
            setNullableInt(ps, i++, u.lastHits());
            setNullableInt(ps, i++, u.rankTier());
            setNullableInt(ps, i++, u.durationS());
            setNullableLong(ps, i++, u.playedAt());
            ps.setString(i++, u.state());
            ps.setInt(i++, u.attempts());
            setNullableLong(ps, i++, u.nextAfterMs());
            ps.setLong(i, id);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to apply enrichment to match " + id, e);
        }
    }

    /**
     * Narrow retry/fail UPDATE for the enricher's non-Ready paths. Touches ONLY the retry bookkeeping
     * ({@code enrichment_state}, {@code enrich_attempts}, {@code enrich_next_after_ms}) and NEVER the
     * recorder-owned columns -- notably {@code duration_s}/{@code played_at}, which {@link #applyEnrichment}
     * blanks when its carrier is null. Since OpenDota lags a finalized match, the first poll is almost
     * always NotReady/Missing; routing those through here keeps the recorder's real values intact.
     *
     * <p>{@code enrich_attempts} is bumped self-relatively ({@code + delta}) so two racing dispatches
     * can't lose an increment, and the {@code enrichment_state <> 'enriched'} guard stops a late
     * straggler from reverting an already-enriched row back to pending/failed.
     *
     * @param attemptsDelta amount to add to {@code enrich_attempts} (1 per poll)
     * @param nextAfterMs   next-eligible wall clock, or null to clear the backoff
     * @return rows updated (0 if no such row, or it was already enriched)
     */
    public int applyRetry(long id, String state, int attemptsDelta, Long nextAfterMs) {
        String sql = "UPDATE matches SET "
                + "enrichment_state = ?, enrich_attempts = enrich_attempts + ?, "
                + "enrich_next_after_ms = ? "
                + "WHERE id = ? AND enrichment_state <> 'enriched'";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, state);
            ps.setInt(2, attemptsDelta);
            setNullableLong(ps, 3, nextAfterMs);
            ps.setLong(4, id);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to apply enrichment retry to match " + id, e);
        }
    }

    // ---- mapping / binding -------------------------------------------------

    private MatchSummary map(ResultSet rs) throws SQLException {
        return new MatchSummary(
                rs.getLong("id"),
                getNullableLong(rs, "dota_match_id"),
                rs.getString("record_kind"),
                rs.getString("enrichment_state"),
                rs.getString("hero"),
                getNullableInt(rs, "kills"),
                getNullableInt(rs, "deaths"),
                getNullableInt(rs, "assists"),
                getNullableInt(rs, "gpm"),
                getNullableInt(rs, "xpm"),
                getNullableInt(rs, "net_worth"),
                getNullableInt(rs, "last_hits"),
                rs.getString("result"),
                getNullableInt(rs, "lobby_type"),
                getNullableInt(rs, "game_mode"),
                getNullableInt(rs, "rank_tier"),
                getNullableInt(rs, "mmr_delta"),
                getNullableInt(rs, "duration_s"),
                getNullableLong(rs, "played_at"),
                rs.getString("video_path"),
                rs.getString("thumb_path"),
                getNullableLong(rs, "file_size_bytes"),
                rs.getInt("starred") != 0,
                rs.getLong("created_at"),
                getNullableLong(rs, "record_started_wall_ms")
        );
    }

    private void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object p = params.get(i);
            if (p instanceof Long l) {
                ps.setLong(i + 1, l);
            } else {
                ps.setObject(i + 1, p);
            }
        }
    }

    private static void setNullableInt(PreparedStatement ps, int idx, Integer v) throws SQLException {
        if (v != null) {
            ps.setInt(idx, v);
        } else {
            ps.setNull(idx, java.sql.Types.INTEGER);
        }
    }

    private static void setNullableLong(PreparedStatement ps, int idx, Long v) throws SQLException {
        if (v != null) {
            ps.setLong(idx, v);
        } else {
            ps.setNull(idx, java.sql.Types.INTEGER);
        }
    }

    /** Comma-separated {@code ?} placeholders for an {@code IN (...)} clause of {@code n} ids. */
    private static String placeholders(int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }

    private Integer getNullableInt(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    /**
     * Insertable shape for {@link #insert(NewMatch)}. Every field is nullable except {@code createdAt}
     * (NOT NULL in the schema) and {@code starred}. {@code recordKind}/{@code enrichmentState} fall
     * back to the schema defaults ({@code match}/{@code pending}) when null.
     *
     * <p>{@code recordStartedWallMs} is the OBS record-confirmed wall clock the recorder anchors
     * video offsets on; it is persisted so the later replay-enrichment pass can re-derive offsets
     * deterministically. It is appended last so existing positional call sites only grow by one.
     */
    public record NewMatch(
            Long dotaMatchId,
            String recordKind,
            String enrichmentState,
            String hero,
            Integer kills,
            Integer deaths,
            Integer assists,
            Integer gpm,
            Integer xpm,
            Integer netWorth,
            Integer lastHits,
            String result,
            Integer lobbyType,
            Integer gameMode,
            Integer rankTier,
            Integer mmrDelta,
            Integer durationS,
            Long playedAt,
            String videoPath,
            String thumbPath,
            Long fileSizeBytes,
            boolean starred,
            long createdAt,
            Long recordStartedWallMs
    ) {
    }

    /**
     * Minimal shape the enrichment queue dispatches on -- just the surrogate id, the Dota match id
     * to fetch, and the current attempt count. Deliberately not the heavy {@link MatchSummary}.
     */
    public record PendingMatch(long id, long dotaMatchId, int attempts) {
    }

    /**
     * Carrier for {@link #applyEnrichment(long, EnrichmentUpdate)}. The {@code state} is the terminal
     * enrichment state to write ({@code enriched}/{@code failed}/{@code pending}), so one method
     * serves the full-enrich, partial-NotReady, and fail paths. {@code attempts}/{@code nextAfterMs}
     * carry the retry bookkeeping. {@code mmr_delta} is intentionally absent -- always written null.
     */
    public record EnrichmentUpdate(
            String result,
            Integer lobbyType,
            Integer gameMode,
            Integer gpm,
            Integer xpm,
            Integer netWorth,
            Integer lastHits,
            Integer rankTier,
            Integer durationS,
            Long playedAt,
            String state,
            int attempts,
            Long nextAfterMs
    ) {
    }
}

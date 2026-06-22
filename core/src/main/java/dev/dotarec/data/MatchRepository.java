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
}

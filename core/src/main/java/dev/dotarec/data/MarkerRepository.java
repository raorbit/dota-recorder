package dev.dotarec.data;

import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain-JDBC access to the {@code markers} table. Mirrors the hand-rolled style of
 * {@link MatchRepository}; JOOQ is a documented TODO (plan stack).
 *
 * <p>Markers are written live by the GSI tagger and later by replay enrichment, and read back by
 * the player as the seekable timeline for a match. Reads are ordered by {@code video_offset_s} so
 * the timeline renders in playback order; the {@code idx_markers_match} index (V1) backs the lookup.
 */
@Repository
public class MarkerRepository {

    private final DataSource dataSource;

    public MarkerRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Inserts one marker and returns the generated id.
     *
     * @param matchId      owning {@code matches.id}
     * @param type         marker kind
     * @param videoOffsetS seconds from the start of the video
     * @param gameClock    in-game clock seconds, or null
     * @param label        optional label, or null
     * @param source       {@code gsi} or {@code replay}
     */
    public long insert(long matchId, String type, double videoOffsetS, Integer gameClock,
                       String label, String source) {
        String sql = """
                INSERT INTO markers (match_id, type, video_offset_s, game_clock, label, source)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, matchId);
            ps.setString(2, type);
            ps.setDouble(3, videoOffsetS);
            if (gameClock != null) {
                ps.setInt(4, gameClock);
            } else {
                ps.setNull(4, java.sql.Types.INTEGER);
            }
            ps.setString(5, label);
            ps.setString(6, source != null ? source : "gsi");
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to insert marker for match " + matchId, e);
        }
    }

    /** All markers for a match ordered by video offset (playback order), then id for stability. */
    public List<MarkerRow> findByMatchId(long matchId) {
        String sql = """
                SELECT id, match_id, type, video_offset_s, game_clock, label, source
                FROM markers
                WHERE match_id = ?
                ORDER BY video_offset_s ASC, id ASC
                """;
        List<MarkerRow> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, matchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query markers for match " + matchId, e);
        }
        return out;
    }

    private MarkerRow map(ResultSet rs) throws SQLException {
        int clock = rs.getInt("game_clock");
        Integer gameClock = rs.wasNull() ? null : clock;
        return new MarkerRow(
                rs.getLong("id"),
                rs.getLong("match_id"),
                rs.getString("type"),
                rs.getDouble("video_offset_s"),
                gameClock,
                rs.getString("label"),
                rs.getString("source")
        );
    }
}

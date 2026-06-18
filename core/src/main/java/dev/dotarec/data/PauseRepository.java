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
 * Plain-JDBC access to the {@code pauses} table. Mirrors the hand-rolled style of
 * {@link MatchRepository}; JOOQ is a documented TODO (plan stack).
 *
 * <p>A pause span is opened ({@code end_wall} null) when the game pauses and closed when it resumes.
 * Reads are ordered by {@code start_wall} so the timeline greys out dead time in chronological
 * order; the {@code idx_pauses_match} index (V1) backs the lookup.
 */
@Repository
public class PauseRepository {

    private final DataSource dataSource;

    public PauseRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Opens a pause span and returns its generated id. {@code end_wall} is left null until the pause
     * is closed via {@link #close(long, long)}.
     *
     * @param matchId   owning {@code matches.id}
     * @param startWall epoch millis when the pause began
     */
    public long open(long matchId, long startWall) {
        String sql = "INSERT INTO pauses (match_id, start_wall, end_wall) VALUES (?, ?, NULL)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, matchId);
            ps.setLong(2, startWall);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to open pause for match " + matchId, e);
        }
    }

    /**
     * Inserts a complete pause span in one shot (start + end). Convenience for enrichment / tests
     * that already know both ends.
     */
    public long insert(long matchId, long startWall, Long endWall) {
        String sql = "INSERT INTO pauses (match_id, start_wall, end_wall) VALUES (?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, matchId);
            ps.setLong(2, startWall);
            if (endWall != null) {
                ps.setLong(3, endWall);
            } else {
                ps.setNull(3, java.sql.Types.INTEGER);
            }
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to insert pause for match " + matchId, e);
        }
    }

    /**
     * Closes the open pause span with the given id by stamping {@code end_wall}. A no-op (0 rows) if
     * the span is already closed or does not exist.
     *
     * @return rows updated
     */
    public int close(long pauseId, long endWall) {
        String sql = "UPDATE pauses SET end_wall = ? WHERE id = ? AND end_wall IS NULL";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, endWall);
            ps.setLong(2, pauseId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to close pause " + pauseId, e);
        }
    }

    /** All pause spans for a match ordered by start (chronological), then id for stability. */
    public List<PauseSpan> findByMatchId(long matchId) {
        String sql = """
                SELECT id, match_id, start_wall, end_wall
                FROM pauses
                WHERE match_id = ?
                ORDER BY start_wall ASC, id ASC
                """;
        List<PauseSpan> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, matchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query pauses for match " + matchId, e);
        }
        return out;
    }

    private PauseSpan map(ResultSet rs) throws SQLException {
        long end = rs.getLong("end_wall");
        Long endWall = rs.wasNull() ? null : end;
        return new PauseSpan(
                rs.getLong("id"),
                rs.getLong("match_id"),
                rs.getLong("start_wall"),
                endWall
        );
    }
}

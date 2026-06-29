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
import java.util.Optional;

/**
 * Plain-JDBC access to the {@code clips} table. Mirrors the hand-rolled style of
 * {@link MarkerRepository} / {@link MatchRepository}; JOOQ is a documented TODO (plan stack).
 *
 * <p>Clips are sub-ranges of a parent match's recording rendered into their own .mp4. They are
 * created in {@code pending} state (by an auto-trigger or a manual carve) and the async clip
 * generator flips them through {@code generating} → {@code ready}/{@code failed} via
 * {@link #updateStatus}. Reads for a match are ordered by start offset so the clip list renders in
 * timeline order; the {@code idx_clips_parent} index (V4) backs the lookup.
 */
@Repository
public class ClipRepository {

    private final DataSource dataSource;

    public ClipRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Inserts one clip and returns the generated id. {@code createdAt} should be
     * {@code System.currentTimeMillis()} supplied by the caller.
     */
    public long insert(long parentMatchId, String kind, String triggerReason, double startOffsetS,
                       double endOffsetS, String label, String videoPath, String thumbPath,
                       Long fileSizeBytes, String status, String error, long createdAt) {
        try (Connection conn = dataSource.getConnection()) {
            return insert(conn, parentMatchId, kind, triggerReason, startOffsetS, endOffsetS, label,
                    videoPath, thumbPath, fileSizeBytes, status, error, createdAt);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to insert clip for match " + parentMatchId, e);
        }
    }

    /**
     * Inserts one clip on a caller-supplied connection (which the caller commits/rolls back), so a
     * clip write can join an enclosing transaction. Does not open/commit/close.
     */
    public long insert(Connection conn, long parentMatchId, String kind, String triggerReason,
                       double startOffsetS, double endOffsetS, String label, String videoPath,
                       String thumbPath, Long fileSizeBytes, String status, String error,
                       long createdAt) throws SQLException {
        String sql = """
                INSERT INTO clips (parent_match_id, kind, trigger_reason, start_offset_s, end_offset_s,
                                   label, video_path, thumb_path, file_size_bytes, status, error, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, parentMatchId);
            ps.setString(2, kind);
            ps.setString(3, triggerReason);
            ps.setDouble(4, startOffsetS);
            ps.setDouble(5, endOffsetS);
            ps.setString(6, label);
            ps.setString(7, videoPath);
            ps.setString(8, thumbPath);
            setNullableLong(ps, 9, fileSizeBytes);
            ps.setString(10, status);
            ps.setString(11, error);
            ps.setLong(12, createdAt);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        }
    }

    /** All clips for a match ordered by start offset (timeline order), then id for stability. */
    public List<ClipRow> findByParentMatchId(long parentMatchId) {
        String sql = """
                SELECT id, parent_match_id, kind, trigger_reason, start_offset_s, end_offset_s,
                       label, video_path, thumb_path, file_size_bytes, status, error, created_at
                FROM clips
                WHERE parent_match_id = ?
                ORDER BY start_offset_s ASC, id ASC
                """;
        List<ClipRow> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, parentMatchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query clips for match " + parentMatchId, e);
        }
        return out;
    }

    /** Single clip by id, or empty if it does not exist. */
    public Optional<ClipRow> findById(long id) {
        String sql = """
                SELECT id, parent_match_id, kind, trigger_reason, start_offset_s, end_offset_s,
                       label, video_path, thumb_path, file_size_bytes, status, error, created_at
                FROM clips
                WHERE id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query clip " + id, e);
        }
    }

    /** All clips in a given status, oldest first — backs the generator queue and retry sweep. */
    public List<ClipRow> findByStatus(String status) {
        String sql = """
                SELECT id, parent_match_id, kind, trigger_reason, start_offset_s, end_offset_s,
                       label, video_path, thumb_path, file_size_bytes, status, error, created_at
                FROM clips
                WHERE status = ?
                ORDER BY created_at ASC, id ASC
                """;
        List<ClipRow> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query clips with status " + status, e);
        }
        return out;
    }

    /** Every clip, newest first — backs the library "Clips" bucket's flat list. */
    public List<ClipRow> findAll() {
        String sql = """
                SELECT id, parent_match_id, kind, trigger_reason, start_offset_s, end_offset_s,
                       label, video_path, thumb_path, file_size_bytes, status, error, created_at
                FROM clips
                ORDER BY created_at DESC, id DESC
                """;
        List<ClipRow> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(map(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query all clips", e);
        }
        return out;
    }

    /** Total clip count across all matches — feeds the library bucket counts. */
    public long count() {
        String sql = "SELECT COUNT(*) FROM clips";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count clips", e);
        }
    }

    /** Total bytes of all clips with a known file size — feeds retention disk accounting. */
    public long sumFileSizeBytes() {
        String sql = "SELECT COALESCE(SUM(file_size_bytes), 0) FROM clips WHERE file_size_bytes IS NOT NULL";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to sum clip file sizes", e);
        }
    }

    /**
     * Flips a clip's lifecycle status and (null-safely) the generator outputs. Used by the clip
     * generator to move a row through {@code generating} → {@code ready}/{@code failed}; pass null
     * for outputs that do not apply to the new status (e.g. {@code error} on success).
     */
    public void updateStatus(long id, String status, String videoPath, Long fileSizeBytes,
                             String thumbPath, String error) {
        String sql = """
                UPDATE clips
                SET status = ?, video_path = ?, file_size_bytes = ?, thumb_path = ?, error = ?
                WHERE id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, videoPath);
            setNullableLong(ps, 3, fileSizeBytes);
            ps.setString(4, thumbPath);
            ps.setString(5, error);
            ps.setLong(6, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update status for clip " + id, e);
        }
    }

    /** Removes a clip row by id (the on-disk .mp4/thumb cleanup is the caller's responsibility). */
    public void delete(long id) {
        String sql = "DELETE FROM clips WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete clip " + id, e);
        }
    }

    private ClipRow map(ResultSet rs) throws SQLException {
        return new ClipRow(
                rs.getLong("id"),
                rs.getLong("parent_match_id"),
                rs.getString("kind"),
                rs.getString("trigger_reason"),
                rs.getDouble("start_offset_s"),
                rs.getDouble("end_offset_s"),
                rs.getString("label"),
                rs.getString("video_path"),
                rs.getString("thumb_path"),
                getNullableLong(rs, "file_size_bytes"),
                rs.getString("status"),
                rs.getString("error"),
                rs.getLong("created_at")
        );
    }

    private static void setNullableLong(PreparedStatement ps, int idx, Long v) throws SQLException {
        if (v != null) {
            ps.setLong(idx, v);
        } else {
            ps.setNull(idx, java.sql.Types.INTEGER);
        }
    }

    private Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }
}

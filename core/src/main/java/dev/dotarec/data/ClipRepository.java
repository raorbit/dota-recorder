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
                       label, video_path, thumb_path, file_size_bytes, status, error, created_at, starred
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
                       label, video_path, thumb_path, file_size_bytes, status, error, created_at, starred
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
                       label, video_path, thumb_path, file_size_bytes, status, error, created_at, starred
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

    /**
     * Clips wedged in {@code generating} since before {@code startedBefore} (epoch millis), oldest
     * first. Backs the periodic self-heal: a row whose worker died after the cut but before the
     * status write (e.g. a back-to-back SQLITE_BUSY at finalize) stays {@code generating} forever,
     * since the boot-only orphan reconcile never runs again. The sweep re-pends these so they recover
     * without a reboot.
     *
     * <p>Staleness is anchored on {@code generation_started_at} (when {@link #claimForGeneration}
     * flipped the row to {@code generating}), NOT {@code created_at}: a clip can sit {@code pending} in
     * a saturated queue past the cutoff and only then be claimed and rendered, so a {@code created_at}
     * cutoff would re-pend a live render and double-cut it. Falls back to {@code created_at} when
     * {@code generation_started_at} is NULL (legacy rows from before V6, or a row reset by the boot
     * reconcile), which is safe because such a row is not concurrently being rendered with a fresh start.
     */
    public List<ClipRow> findStaleGenerating(long startedBefore) {
        String sql = """
                SELECT id, parent_match_id, kind, trigger_reason, start_offset_s, end_offset_s,
                       label, video_path, thumb_path, file_size_bytes, status, error, created_at, starred
                FROM clips
                WHERE status = 'generating' AND COALESCE(generation_started_at, created_at) < ?
                ORDER BY created_at ASC, id ASC
                """;
        List<ClipRow> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, startedBefore);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query stale generating clips", e);
        }
        return out;
    }

    /** Every clip, newest first — backs the library "Clips" bucket's flat list. */
    public List<ClipRow> findAll() {
        String sql = """
                SELECT id, parent_match_id, kind, trigger_reason, start_offset_s, end_offset_s,
                       label, video_path, thumb_path, file_size_bytes, status, error, created_at, starred
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

    /**
     * Flips a clip's lifecycle status and (null-safely) the generator outputs. Used by the clip
     * generator to move a row through {@code generating} → {@code ready}/{@code failed}; pass null
     * for outputs that do not apply to the new status (e.g. {@code error} on success).
     *
     * @return the number of rows updated — 0 means the row was deleted (e.g. the user removed the clip
     *     while it was still generating), which the caller uses to clean up the orphaned output it just
     *     wrote rather than leaving it on disk.
     */
    public int updateStatus(long id, String status, String videoPath, Long fileSizeBytes,
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
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update status for clip " + id, e);
        }
    }

    /**
     * Compare-and-set variant of {@link #updateStatus} for the generator's TERMINAL writes: flips a
     * clip to {@code ready}/{@code failed} (and its outputs) ONLY if the row is still
     * {@code generating} ({@code WHERE id=? AND status='generating'}), mirroring
     * {@link #rependIfStale}'s guard. This closes a double-cut race: if the periodic self-heal already
     * re-pended a slow render and a SECOND worker re-claimed it, the ORIGINAL worker's terminal write
     * must not resurrect/overwrite the row and point it at a file the second worker is still rewriting.
     * The plain {@link #updateStatus} (unconditional {@code WHERE id=?}) is retained for the boot-time
     * orphan reconcile, which legitimately flips {@code generating} → {@code pending}.
     *
     * @return the number of rows updated — 0 means the row is no longer {@code generating} (re-pended,
     *     re-claimed, or deleted), so the caller must clean up the orphaned output it just wrote.
     */
    public int updateStatusIfGenerating(long id, String status, String videoPath, Long fileSizeBytes,
                                        String thumbPath, String error) {
        String sql = """
                UPDATE clips
                SET status = ?, video_path = ?, file_size_bytes = ?, thumb_path = ?, error = ?
                WHERE id = ? AND status = 'generating'
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, videoPath);
            setNullableLong(ps, 3, fileSizeBytes);
            ps.setString(4, thumbPath);
            ps.setString(5, error);
            ps.setLong(6, id);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update status for clip " + id, e);
        }
    }

    /**
     * Atomically claims a {@code pending} clip for generation by flipping it to {@code generating} and
     * stamping {@code generation_started_at = startedAt} (epoch millis), returning true only if THIS
     * call won the transition (one row matched {@code status='pending'}). A compare-and-set so a clip
     * dispatched twice — the immediate create dispatch racing the {@link #findByStatus}-driven retry
     * sweep — is rendered exactly once: the loser sees the row already {@code generating}/{@code ready}/
     * {@code failed} (or deleted) and skips, so a completed clip is never re-cut and its output never
     * overwritten. The start stamp anchors the {@link #findStaleGenerating} wedged-row cutoff on when
     * the render actually began, not on insert time.
     */
    public boolean claimForGeneration(long id, long startedAt) {
        String sql = "UPDATE clips SET status = 'generating', generation_started_at = ? "
                + "WHERE id = ? AND status = 'pending'";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, startedAt);
            ps.setLong(2, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to claim clip " + id + " for generation", e);
        }
    }

    /**
     * Re-pends a wedged {@code generating} row, but ONLY if it is still {@code generating} — a
     * compare-and-set ({@code WHERE id=? AND status='generating'}) so a worker that flipped the row to
     * {@code ready}/{@code failed} between the sweep's stale query and this write is not clobbered back
     * to {@code pending} (which would re-cut a finished clip). Clears the prior generator outputs.
     *
     * @return true only if THIS call re-pended the row.
     */
    public boolean rependIfStale(long id) {
        String sql = """
                UPDATE clips
                SET status = 'pending', video_path = NULL, file_size_bytes = NULL,
                    thumb_path = NULL, error = NULL, generation_started_at = NULL
                WHERE id = ? AND status = 'generating'
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to re-pend stale clip " + id, e);
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

    /**
     * Sets the starred flag on a clip. A starred clip is exempt from the retention sweep — kept until
     * manually deleted, independently of its parent match's star. Mirrors
     * {@link MatchRepository#setStarred}.
     *
     * @return rows updated (0 if no such clip)
     */
    public int setStarred(long id, boolean starred) {
        String sql = "UPDATE clips SET starred = ? WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, starred ? 1 : 0);
            ps.setLong(2, id);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to set starred on clip " + id, e);
        }
    }

    /** Repoints a clip's file paths after an archive move (mirrors {@link MatchRepository#updateVideoPath}). */
    public void updateVideoPath(long id, String videoPath, String thumbPath) {
        String sql = "UPDATE clips SET video_path = ?, thumb_path = ? WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, videoPath);
            ps.setString(2, thumbPath);
            ps.setLong(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to repoint clip " + id, e);
        }
    }

    /**
     * Clips eligible for retention eviction, oldest first: those with a video on disk that are NOT
     * starred. Drives the "clips last" eviction phase — the sweeper deletes these only after exhausting
     * non-starred match VODs. Starred clips are protected (never auto-deleted), mirroring starred matches.
     */
    public List<ClipRow> findSweepCandidates() {
        String sql = """
                SELECT id, parent_match_id, kind, trigger_reason, start_offset_s, end_offset_s,
                       label, video_path, thumb_path, file_size_bytes, status, error, created_at, starred
                FROM clips
                WHERE starred = 0 AND video_path IS NOT NULL
                ORDER BY created_at ASC, id ASC
                """;
        List<ClipRow> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(map(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query clip sweep candidates", e);
        }
        return out;
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
                rs.getLong("created_at"),
                rs.getInt("starred") != 0
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

package dev.dotarec.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * Durable journal for an in-progress recording. The FSM will use this before a match row exists so
 * startup recovery can reconcile a crashed recording rather than losing its live markers/snapshot.
 */
@Repository
public class RecordingSessionRepository {

    private final DataSource dataSource;

    public RecordingSessionRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void open(RecordingSessionRow row) {
        try (Connection conn = dataSource.getConnection()) {
            open(conn, row);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to open recording session " + row.sessionId(), e);
        }
    }

    public void open(Connection conn, RecordingSessionRow row) throws SQLException {
        String sql = """
                INSERT INTO recording_session (
                    session_id, surrogate_id, state, dota_match_id, hero,
                    record_confirmed_wall_ms, record_started_wall_ms, last_frame_wall_ms,
                    last_game_state, kills, deaths, assists, video_path, thumb_path,
                    opened_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindSession(ps, row);
            ps.executeUpdate();
        }
    }

    public int updateSnapshot(String sessionId, Snapshot snapshot) {
        try (Connection conn = dataSource.getConnection()) {
            return updateSnapshot(conn, sessionId, snapshot);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update recording session " + sessionId, e);
        }
    }

    public int updateSnapshot(Connection conn, String sessionId, Snapshot snapshot)
            throws SQLException {
        String sql = """
                UPDATE recording_session SET
                    state = ?, dota_match_id = ?, hero = ?, last_frame_wall_ms = ?,
                    last_game_state = ?, kills = ?, deaths = ?, assists = ?,
                    video_path = ?, thumb_path = ?, updated_at = ?
                WHERE session_id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, snapshot.state());
            setNullableLong(ps, i++, snapshot.dotaMatchId());
            ps.setString(i++, snapshot.hero());
            setNullableLong(ps, i++, snapshot.lastFrameWallMs());
            ps.setString(i++, snapshot.lastGameState());
            setNullableInt(ps, i++, snapshot.kills());
            setNullableInt(ps, i++, snapshot.deaths());
            setNullableInt(ps, i++, snapshot.assists());
            ps.setString(i++, snapshot.videoPath());
            ps.setString(i++, snapshot.thumbPath());
            ps.setLong(i++, snapshot.updatedAt());
            ps.setString(i, sessionId);
            return ps.executeUpdate();
        }
    }

    public long appendEvent(String sessionId, RecordingEvent event) {
        try (Connection conn = dataSource.getConnection()) {
            return appendEvent(conn, sessionId, event);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to append recording event for " + sessionId, e);
        }
    }

    public long appendEvent(Connection conn, String sessionId, RecordingEvent event)
            throws SQLException {
        String sql = """
                INSERT INTO recording_event (
                    session_id, type, wall_ms, game_clock, payload_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, sessionId);
            ps.setString(2, event.type());
            ps.setLong(3, event.wallMs());
            setNullableInt(ps, 4, event.gameClock());
            ps.setString(5, event.payloadJson());
            ps.setLong(6, event.createdAt());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        }
    }

    public List<RecordingSessionRow> findUnfinished() {
        // The journal models "row exists = unfinished, row gone = done": a successful finalize or
        // crash recovery DELETEs the row, so every surviving row is unfinished (no writer ever sets
        // a 'completed' state).
        String sql = "SELECT " + sessionColumns()
                + " FROM recording_session ORDER BY opened_at ASC";
        List<RecordingSessionRow> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(mapSession(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query unfinished recording sessions", e);
        }
        return out;
    }

    public List<RecordingEventRow> findEvents(String sessionId) {
        String sql = """
                SELECT id, session_id, type, wall_ms, game_clock, payload_json, created_at
                FROM recording_event
                WHERE session_id = ?
                ORDER BY id ASC
                """;
        List<RecordingEventRow> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(
                            new RecordingEventRow(
                                    rs.getLong("id"),
                                    rs.getString("session_id"),
                                    rs.getString("type"),
                                    rs.getLong("wall_ms"),
                                    getNullableInt(rs, "game_clock"),
                                    rs.getString("payload_json"),
                                    rs.getLong("created_at")));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query recording events for " + sessionId, e);
        }
        return out;
    }

    public int delete(String sessionId) {
        try (Connection conn = dataSource.getConnection()) {
            return delete(conn, sessionId);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete recording session " + sessionId, e);
        }
    }

    public int delete(Connection conn, String sessionId) throws SQLException {
        String sql = "DELETE FROM recording_session WHERE session_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            return ps.executeUpdate();
        }
    }

    private static String sessionColumns() {
        return """
                session_id, surrogate_id, state, dota_match_id, hero,
                record_confirmed_wall_ms, record_started_wall_ms, last_frame_wall_ms,
                last_game_state, kills, deaths, assists, video_path, thumb_path,
                opened_at, updated_at
                """;
    }

    private void bindSession(PreparedStatement ps, RecordingSessionRow row) throws SQLException {
        int i = 1;
        ps.setString(i++, row.sessionId());
        ps.setString(i++, row.surrogateId());
        ps.setString(i++, row.state());
        setNullableLong(ps, i++, row.dotaMatchId());
        ps.setString(i++, row.hero());
        ps.setLong(i++, row.recordConfirmedWallMs());
        ps.setLong(i++, row.recordStartedWallMs());
        setNullableLong(ps, i++, row.lastFrameWallMs());
        ps.setString(i++, row.lastGameState());
        setNullableInt(ps, i++, row.kills());
        setNullableInt(ps, i++, row.deaths());
        setNullableInt(ps, i++, row.assists());
        ps.setString(i++, row.videoPath());
        ps.setString(i++, row.thumbPath());
        ps.setLong(i++, row.openedAt());
        ps.setLong(i, row.updatedAt());
    }

    private RecordingSessionRow mapSession(ResultSet rs) throws SQLException {
        return new RecordingSessionRow(
                rs.getString("session_id"),
                rs.getString("surrogate_id"),
                rs.getString("state"),
                getNullableLong(rs, "dota_match_id"),
                rs.getString("hero"),
                rs.getLong("record_confirmed_wall_ms"),
                rs.getLong("record_started_wall_ms"),
                getNullableLong(rs, "last_frame_wall_ms"),
                rs.getString("last_game_state"),
                getNullableInt(rs, "kills"),
                getNullableInt(rs, "deaths"),
                getNullableInt(rs, "assists"),
                rs.getString("video_path"),
                rs.getString("thumb_path"),
                rs.getLong("opened_at"),
                rs.getLong("updated_at"));
    }

    private static void setNullableInt(PreparedStatement ps, int idx, Integer v)
            throws SQLException {
        if (v != null) {
            ps.setInt(idx, v);
        } else {
            ps.setNull(idx, java.sql.Types.INTEGER);
        }
    }

    private static void setNullableLong(PreparedStatement ps, int idx, Long v)
            throws SQLException {
        if (v != null) {
            ps.setLong(idx, v);
        } else {
            ps.setNull(idx, java.sql.Types.INTEGER);
        }
    }

    private static Integer getNullableInt(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    public record RecordingSessionRow(
            String sessionId,
            String surrogateId,
            String state,
            Long dotaMatchId,
            String hero,
            long recordConfirmedWallMs,
            long recordStartedWallMs,
            Long lastFrameWallMs,
            String lastGameState,
            Integer kills,
            Integer deaths,
            Integer assists,
            String videoPath,
            String thumbPath,
            long openedAt,
            long updatedAt) {}

    public record Snapshot(
            String state,
            Long dotaMatchId,
            String hero,
            Long lastFrameWallMs,
            String lastGameState,
            Integer kills,
            Integer deaths,
            Integer assists,
            String videoPath,
            String thumbPath,
            long updatedAt) {}

    public record RecordingEvent(
            String type,
            long wallMs,
            Integer gameClock,
            String payloadJson,
            long createdAt) {}

    public record RecordingEventRow(
            long id,
            String sessionId,
            String type,
            long wallMs,
            Integer gameClock,
            String payloadJson,
            long createdAt) {}
}

package dev.dotarec.data;

import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain-JDBC read access to the matches table. JOOQ is a documented TODO (plan stack); until
 * then this hand-rolled mapper is the source of truth for matches reads.
 */
@Repository
public class MatchRepository {

    private final DataSource dataSource;

    public MatchRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Newest first. Returns an empty list on a fresh DB. */
    public List<MatchSummary> findAll() {
        String sql = """
                SELECT id, dota_match_id, record_kind, enrichment_state, hero,
                       kills, deaths, assists, gpm, xpm, net_worth, last_hits,
                       result, lobby_type, game_mode, rank_tier, mmr_delta, duration_s,
                       played_at, video_path, thumb_path, file_size_bytes, starred, created_at
                FROM matches
                ORDER BY COALESCE(played_at, created_at) DESC, id DESC
                """;
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
                rs.getLong("created_at")
        );
    }

    private Integer getNullableInt(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }
}

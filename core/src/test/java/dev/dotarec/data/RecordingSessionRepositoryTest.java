package dev.dotarec.data;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dotarec.data.RecordingSessionRepository.RecordingEvent;
import dev.dotarec.data.RecordingSessionRepository.RecordingSessionRow;
import dev.dotarec.data.RecordingSessionRepository.Snapshot;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingSessionRepositoryTest {

    private DataSource ds;
    private RecordingSessionRepository repo;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        ds = TestDb.migrated(dir);
        repo = new RecordingSessionRepository(ds);
    }

    @Test
    void migrationCreatesVersion3JournalTables() throws Exception {
        try (Connection conn = ds.getConnection();
                Statement st = conn.createStatement();
                ResultSet version = st.executeQuery("PRAGMA user_version")) {
            assertThat(version.next()).isTrue();
            assertThat(version.getInt(1)).isEqualTo(MigrationRunner.LATEST_VERSION);
        }

        try (Connection conn = ds.getConnection();
                Statement st = conn.createStatement();
                ResultSet tables =
                        st.executeQuery(
                                """
                                SELECT name FROM sqlite_master
                                WHERE type = 'table' AND name IN ('recording_session', 'recording_event')
                                ORDER BY name
                                """)) {
            assertThat(tableNames(tables)).containsExactly("recording_event", "recording_session");
        }
    }

    @Test
    void openUpdateAppendFindAndDeleteSession() {
        repo.open(
                new RecordingSessionRow(
                        "session-1",
                        "surrogate-1",
                        "recording",
                        123L,
                        "npc_dota_hero_puck",
                        1_000L,
                        1_000L,
                        1_250L,
                        "DOTA_GAMERULES_STATE_GAME_IN_PROGRESS",
                        1,
                        0,
                        3,
                        null,
                        null,
                        900L,
                        1_250L));

        int updated =
                repo.updateSnapshot(
                        "session-1",
                        new Snapshot(
                                "stopping",
                                123L,
                                "npc_dota_hero_puck",
                                2_000L,
                                "DOTA_GAMERULES_STATE_POST_GAME",
                                2,
                                1,
                                5,
                                "D:/vods/puck.mp4",
                                "D:/vods/puck.jpg",
                                2_100L));
        assertThat(updated).isEqualTo(1);

        long first =
                repo.appendEvent(
                        "session-1",
                        new RecordingEvent(
                                "marker",
                                1_500L,
                                300,
                                "{\"type\":\"kill\"}",
                                1_501L));
        long second =
                repo.appendEvent(
                        "session-1",
                        new RecordingEvent("pause_open", 1_750L, null, null, 1_751L));

        assertThat(second).isGreaterThan(first);

        assertThat(repo.findUnfinished())
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.sessionId()).isEqualTo("session-1");
                            assertThat(row.state()).isEqualTo("stopping");
                            assertThat(row.videoPath()).isEqualTo("D:/vods/puck.mp4");
                            assertThat(row.lastGameState())
                                    .isEqualTo("DOTA_GAMERULES_STATE_POST_GAME");
                            assertThat(row.kills()).isEqualTo(2);
                            assertThat(row.deaths()).isEqualTo(1);
                            assertThat(row.assists()).isEqualTo(5);
                        });

        assertThat(repo.findEvents("session-1"))
                .extracting(RecordingSessionRepository.RecordingEventRow::type)
                .containsExactly("marker", "pause_open");

        assertThat(repo.delete("session-1")).isEqualTo(1);
        assertThat(repo.findUnfinished()).isEmpty();
        assertThat(repo.findEvents("session-1")).isEmpty();
    }

    private static java.util.List<String> tableNames(ResultSet rs) throws Exception {
        java.util.List<String> names = new java.util.ArrayList<>();
        while (rs.next()) {
            names.add(rs.getString("name"));
        }
        return names;
    }
}

package dev.dotarec.gsi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Parses the REAL captured GSI frame ({@code resources/gsi/game_in_progress.json}, a Dota Hero Demo
 * sample) and asserts the flattened {@link GsiFrame} matches it field-for-field. Shapes the DTO to
 * the wire, not to docs: this is the contract test that locks the mapping.
 */
class GsiPayloadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GsiFrame parseFixture() throws Exception {
        Path fixture = Path.of("src/test/resources/gsi/game_in_progress.json");
        String body = Files.readString(fixture);
        GsiPayload payload = MAPPER.readValue(body, GsiPayload.class);
        return payload.toFrame(1_700_000_000_000L, 5_000_000_000L);
    }

    @Test
    void mapsRealFrameFieldForField() throws Exception {
        GsiFrame frame = parseFixture();

        assertThat(frame.gameState()).isEqualTo("DOTA_GAMERULES_STATE_GAME_IN_PROGRESS");
        // clock_time is a positive int here (167); carried through verbatim.
        assertThat(frame.gameClock()).isEqualTo(167);
        assertThat(frame.paused()).isFalse();
        // map.matchid is the STRING "0" (Hero Demo) -> parsed to 0L, not an error.
        assertThat(frame.matchId()).isZero();

        assertThat(frame.heroPresent()).isTrue();
        assertThat(frame.alive()).isTrue();
        assertThat(frame.hero()).isEqualTo("npc_dota_hero_drow_ranger");
        assertThat(frame.heroId()).isEqualTo(6);

        assertThat(frame.activity()).isEqualTo("playing");
        // The fixture reports 8 kills; deaths/assists are 0 here.
        assertThat(frame.kills()).isEqualTo(8);
        assertThat(frame.deaths()).isZero();
        assertThat(frame.assists()).isZero();

        assertThat(frame.radiantScore()).isEqualTo(8);
        assertThat(frame.direScore()).isZero();

        // wallClockMillis / monotonicNanos are caller-supplied arrival stamps, not from the wire.
        assertThat(frame.wallClockMillis()).isEqualTo(1_700_000_000_000L);
        assertThat(frame.monotonicNanos()).isEqualTo(5_000_000_000L);
    }

    @Test
    void absentHeroAndPlayerBlocks_yieldSafeDefaults_noNpe() throws Exception {
        // Heartbeat / hero-select shape: provider + map only, no player or hero block.
        String body =
                """
                {
                  "provider": {"name": "Dota 2", "appid": 570},
                  "map": {"matchid": "0", "clock_time": -75, "game_state": "DOTA_GAMERULES_STATE_HERO_SELECTION", "paused": false}
                }
                """;
        GsiPayload payload = MAPPER.readValue(body, GsiPayload.class);
        GsiFrame frame = payload.toFrame(42L, 99L);

        assertThat(frame.heroPresent()).isFalse();
        // Absent hero must NOT read as dead-but-present; alive is false because the block is gone.
        assertThat(frame.alive()).isFalse();
        assertThat(frame.hero()).isNull();
        assertThat(frame.activity()).isNull();
        // clock_time is negative pre-horn; carried through, not clamped.
        assertThat(frame.gameClock()).isEqualTo(-75);
        assertThat(frame.kills()).isZero();
    }

    @Test
    void absentGameState_mapsToUnknown_soFsmNoOps() throws Exception {
        // A frame with no game_state at all (e.g. INIT ping) must flatten to "UNKNOWN", never null.
        GsiPayload payload = MAPPER.readValue("{\"map\": {}}", GsiPayload.class);
        assertThat(payload.toFrame(1L, 1L).gameState()).isEqualTo("UNKNOWN");

        // An entirely empty body is also safe.
        GsiPayload empty = MAPPER.readValue("{}", GsiPayload.class);
        assertThat(empty.toFrame(1L, 1L).gameState()).isEqualTo("UNKNOWN");
    }

    @Test
    void matchIdParsing_handlesBlankAbsentAndNonNumeric() {
        assertThat(GsiPayload.parseMatchId("0")).isZero();
        assertThat(GsiPayload.parseMatchId("7654321098")).isEqualTo(7654321098L);
        assertThat(GsiPayload.parseMatchId(null)).isZero();
        assertThat(GsiPayload.parseMatchId("")).isZero();
        assertThat(GsiPayload.parseMatchId("  ")).isZero();
        // A future client returning something non-numeric must not crash the feed.
        assertThat(GsiPayload.parseMatchId("not-a-number")).isZero();
    }

    @Test
    void parseAccountId_handlesNumericBlankAbsentAndNonNumeric() throws Exception {
        GsiPayload payload =
                MAPPER.readValue("{\"player\":{\"accountid\":\" 96828122 \"}}", GsiPayload.class);
        assertThat(payload.parseAccountId()).isEqualTo(96828122L);

        assertThat(MAPPER.readValue("{\"player\":{\"accountid\":\"\"}}", GsiPayload.class)
                .parseAccountId()).isNull();
        assertThat(MAPPER.readValue("{\"player\":{\"accountid\":\"nope\"}}", GsiPayload.class)
                .parseAccountId()).isNull();
        assertThat(MAPPER.readValue("{}", GsiPayload.class).parseAccountId()).isNull();
    }
}

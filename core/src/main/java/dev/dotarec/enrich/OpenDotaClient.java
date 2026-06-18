package dev.dotarec.enrich;

import java.net.http.HttpClient;
import org.springframework.stereotype.Component;

/**
 * Queries the official match record (OpenDota / STRATZ) by match_id.
 *
 * <p>Plan (Stack -> Post-game API): {@code java.net.http} + Jackson, queried after the game.
 * Provides final result, duration, all 10 players, heroes/items/net worth/lane/role, rank tier,
 * lobby type, game mode -- but is delayed minutes after match end and is rate-limited / may need
 * an API key.
 *
 * <p>TODO(plan): build requests against the OpenDota match endpoint and map responses with
 * Jackson; add retry/backoff for the post-match availability lag.
 */
@Component
public class OpenDotaClient {

    private final HttpClient http = HttpClient.newHttpClient();

    // TODO(plan): fetchMatch(long matchId) -> parsed details via java.net.http + Jackson.
}

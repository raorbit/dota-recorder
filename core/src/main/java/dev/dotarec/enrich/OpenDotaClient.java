package dev.dotarec.enrich;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dotarec.config.SettingsStore;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Queries the official match record from OpenDota by {@code match_id} and classifies the response
 * into a {@link FetchResult}. This is the transport half of the enrichment seam: it owns the HTTP
 * call, status mapping, and JSON parse; the {@link Enricher} owns merging a {@link FetchResult.Ready}
 * body into a row.
 *
 * <p>Status mapping:
 * <ul>
 *   <li><b>200</b> -> parse the body; if duration is null (OpenDota hasn't finished parsing) ->
 *       {@link FetchResult.NotReady}, else {@link FetchResult.Ready}.</li>
 *   <li><b>404</b> -> {@link FetchResult.Missing} (backfill lag; retry up to the cap).</li>
 *   <li>anything else / IOException / timeout / interrupt / JSON hiccup on a 200 ->
 *       {@link FetchResult.Transient} (retry; never burn a permanent fail on a transient blip).</li>
 * </ul>
 *
 * <p>{@code fetch} never throws -- every failure mode is folded into {@link FetchResult.Transient}
 * so the queue stays a dumb eligibility filter and the enricher decides terminal state.
 *
 * <p>PR5 exercises the live socket end-to-end; B7 wires this seam + parses fixtures.
 */
@Component
public class OpenDotaClient implements MatchSource {

    private static final Logger log = LoggerFactory.getLogger(OpenDotaClient.class);

    static final String BASE = "https://api.opendota.com/api/matches/";

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final SettingsStore settings;

    public OpenDotaClient(ObjectMapper mapper, SettingsStore settings) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), mapper, settings);
    }

    /** Seam constructor: tests inject a stub {@link HttpClient} to drive the status/parse paths. */
    OpenDotaClient(HttpClient http, ObjectMapper mapper, SettingsStore settings) {
        this.http = http;
        this.mapper = mapper;
        this.settings = settings;
    }

    @Override
    public FetchResult fetch(long dotaMatchId) {
        try {
            // Build the request inside the try so a malformed URI (e.g. a stray char in the api_key)
            // folds to Transient like any other failure, never escaping to break enrichment wholesale.
            HttpRequest request = HttpRequest.newBuilder(URI.create(url(dotaMatchId)))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return classify(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Enrichment fetch interrupted for match {}", dotaMatchId);
            return FetchResult.Transient.INSTANCE;
        } catch (Exception e) {
            // IOException, HttpTimeoutException, a bad-URI IllegalArgumentException, etc. -- all
            // transient; retry, never throw out. Log only matchId + exception (never the URL, which
            // carries the api_key) to avoid leaking the credential into the log.
            log.debug("Enrichment fetch failed for match {}: {}", dotaMatchId, e.toString());
            return FetchResult.Transient.INSTANCE;
        }
    }

    /**
     * Maps an HTTP status + body to a {@link FetchResult}. Package-private so the parse/classify
     * logic is unit-testable directly against fixture bodies without a socket.
     */
    FetchResult classify(int status, String body) {
        if (status == 404) {
            return FetchResult.Missing.INSTANCE;
        }
        if (status != 200) {
            log.debug("OpenDota returned status {} -- treating as transient", status);
            return FetchResult.Transient.INSTANCE;
        }
        OpenDotaMatch match;
        try {
            match = mapper.readValue(body, OpenDotaMatch.class);
        } catch (JsonProcessingException e) {
            // A 200 we couldn't parse is more likely a transient OpenDota hiccup than a permanently
            // broken match -- don't burn a retry, just try again.
            log.debug("Failed to parse 200 body as OpenDotaMatch -- transient: {}", e.toString());
            return FetchResult.Transient.INSTANCE;
        }
        if (match == null || match.duration() == null) {
            // 200 but OpenDota hasn't parsed the match yet (duration/stats null). Retry.
            return FetchResult.NotReady.INSTANCE;
        }
        return new FetchResult.Ready(match);
    }

    private String url(long dotaMatchId) {
        String key = settings.get().opendotaApiKey;
        if (key != null && !key.isBlank()) {
            // Percent-encode the user-supplied key so a stray character can't yield a URI that
            // URI.create rejects. A valid OpenDota key is URL-safe, so this is a no-op for it.
            String encoded = URLEncoder.encode(key.trim(), StandardCharsets.UTF_8);
            return BASE + dotaMatchId + "?api_key=" + encoded;
        }
        return BASE + dotaMatchId;
    }
}

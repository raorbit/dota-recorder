package dev.dotarec.enrich;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dotarec.config.AppPaths;
import dev.dotarec.config.SettingsStore;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the transport half of the enrichment seam: status -> {@link FetchResult} mapping and the
 * DTO parse. Drives {@link OpenDotaClient#classify} directly against fixtures (no socket) and feeds
 * a stub {@link HttpClient} to {@link OpenDotaClient#fetch} for the IOException -> Transient path.
 */
class OpenDotaClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private SettingsStore settings;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        AppPaths paths = new AppPaths(tmp.resolve("data").toString(), tmp.resolve("obs").toString());
        settings = new SettingsStore(paths);
    }

    @Test
    void parsesFullBodyIntoReady() throws Exception {
        OpenDotaClient client = new OpenDotaClient(throwingHttp(), mapper, settings);
        FetchResult result = client.classify(200, fixture("opendota/match_response.json"));

        assertThat(result).isInstanceOf(FetchResult.Ready.class);
        OpenDotaMatch match = ((FetchResult.Ready) result).match();
        assertThat(match.match_id()).isEqualTo(7654321098L);
        assertThat(match.radiant_win()).isTrue();
        assertThat(match.duration()).isEqualTo(2415);
        assertThat(match.lobby_type()).isEqualTo(7);
        assertThat(match.game_mode()).isEqualTo(22);
        assertThat(match.players()).hasSize(3);

        OpenDotaMatch.Player me = match.players().get(0);
        assertThat(me.account_id()).isEqualTo(96828122L);
        assertThat(me.player_slot()).isZero();
        assertThat(me.gold_per_min()).isEqualTo(412);
        assertThat(me.xp_per_min()).isEqualTo(533);
        assertThat(me.net_worth()).isEqualTo(18240);
        assertThat(me.last_hits()).isEqualTo(121);
        assertThat(me.rank_tier()).isEqualTo(74);
    }

    @Test
    void ignoresUnknownJsonProperties() {
        OpenDotaClient client = new OpenDotaClient(throwingHttp(), mapper, settings);
        // A body with extra/unknown keys must still map cleanly (ignoreUnknown).
        String body = "{\"match_id\":42,\"duration\":100,\"radiant_win\":false,"
                + "\"barracks_status_dire\":63,\"players\":[]}";
        FetchResult result = client.classify(200, body);
        assertThat(result).isInstanceOf(FetchResult.Ready.class);
    }

    @Test
    void unparsed200WithNullDurationIsNotReady() throws Exception {
        OpenDotaClient client = new OpenDotaClient(throwingHttp(), mapper, settings);
        FetchResult result = client.classify(200, fixture("opendota/match_response_unparsed.json"));
        assertThat(result).isSameAs(FetchResult.NotReady.INSTANCE);
    }

    @Test
    void status404IsMissing() {
        OpenDotaClient client = new OpenDotaClient(throwingHttp(), mapper, settings);
        assertThat(client.classify(404, "")).isSameAs(FetchResult.Missing.INSTANCE);
    }

    @Test
    void status500IsTransient() {
        OpenDotaClient client = new OpenDotaClient(throwingHttp(), mapper, settings);
        assertThat(client.classify(500, "oops")).isSameAs(FetchResult.Transient.INSTANCE);
    }

    @Test
    void unparseable200BodyIsTransientNotPermanentFail() {
        OpenDotaClient client = new OpenDotaClient(throwingHttp(), mapper, settings);
        // A 200 we cannot parse is treated as a transient hiccup, not a burned retry.
        assertThat(client.classify(200, "not json at all")).isSameAs(FetchResult.Transient.INSTANCE);
    }

    @Test
    void ioExceptionDuringSendIsTransient() {
        OpenDotaClient client = new OpenDotaClient(throwingHttp(), mapper, settings);
        // The stub HttpClient throws IOException on send -> fetch must fold it into Transient.
        assertThat(client.fetch(123L)).isSameAs(FetchResult.Transient.INSTANCE);
    }

    @Test
    void status200BodyDrivesFetchEndToEnd() {
        OpenDotaClient client = new OpenDotaClient(
                stubHttp(200, fixtureQuiet("opendota/match_response.json")), mapper, settings);
        assertThat(client.fetch(7654321098L)).isInstanceOf(FetchResult.Ready.class);
    }

    @Test
    void apiKeyIsUrlEncodedInRequestUri() {
        // A URI-hostile key (space, '#') must be percent-encoded so URI.create accepts it and the
        // raw key never appears verbatim in the request. Guards against a malformed key silently
        // breaking enrichment for every match.
        settings.update(s -> { s.opendotaApiKey = "ab cd#ef"; return s; });
        java.util.concurrent.atomic.AtomicReference<URI> seen = new java.util.concurrent.atomic.AtomicReference<>();
        HttpClient capturing = new StubHttpClient((req) -> {
            seen.set(req.uri());
            return new StubResponse(200, fixtureQuiet("opendota/match_response.json"), req);
        });

        new OpenDotaClient(capturing, mapper, settings).fetch(7654321098L);

        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().toString())
                .contains("api_key=ab+cd%23ef")
                .doesNotContain("ab cd#ef");
    }

    @Test
    void blankApiKeyProducesNoQueryString() {
        // Default settings carry a blank key -> no ?api_key= appended.
        java.util.concurrent.atomic.AtomicReference<URI> seen = new java.util.concurrent.atomic.AtomicReference<>();
        HttpClient capturing = new StubHttpClient((req) -> {
            seen.set(req.uri());
            return new StubResponse(200, fixtureQuiet("opendota/match_response.json"), req);
        });

        new OpenDotaClient(capturing, mapper, settings).fetch(42L);

        assertThat(seen.get().toString()).doesNotContain("api_key").endsWith("/matches/42");
    }

    // ---- helpers -----------------------------------------------------------

    private static String fixture(String classpath) throws IOException {
        try (InputStream in = OpenDotaClientTest.class.getClassLoader().getResourceAsStream(classpath)) {
            assertThat(in).as("fixture %s on classpath", classpath).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String fixtureQuiet(String classpath) {
        try {
            return fixture(classpath);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** HttpClient whose send() always throws IOException, exercising the Transient fold. */
    private static HttpClient throwingHttp() {
        return new StubHttpClient((req) -> {
            throw new IOException("boom");
        });
    }

    /** HttpClient returning a canned status + body. */
    private static HttpClient stubHttp(int status, String body) {
        return new StubHttpClient((req) -> new StubResponse(status, body, req));
    }

    @FunctionalInterface
    private interface Sender {
        HttpResponse<String> send(HttpRequest req) throws IOException;
    }

    /** Minimal HttpClient that delegates send() to a lambda; all other methods are unused. */
    private static final class StubHttpClient extends HttpClient {
        private final Sender sender;

        StubHttpClient(Sender sender) {
            this.sender = sender;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException {
            return (HttpResponse<T>) sender.send(request);
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest r, HttpResponse.BodyHandler<T> h) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest r, HttpResponse.BodyHandler<T> h,
                HttpResponse.PushPromiseHandler<T> p) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<java.net.CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<java.time.Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<java.net.ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public javax.net.ssl.SSLContext sslContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public javax.net.ssl.SSLParameters sslParameters() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<java.net.Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<java.util.concurrent.Executor> executor() {
            return Optional.empty();
        }
    }

    /** Minimal string HttpResponse carrying a status + body. */
    private record StubResponse(int status, String body, HttpRequest req)
            implements HttpResponse<String> {
        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public HttpRequest request() {
            return req;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(java.util.Map.of(), (a, b) -> true);
        }

        @Override
        public String body() {
            return body;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return req.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}

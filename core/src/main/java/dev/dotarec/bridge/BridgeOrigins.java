package dev.dotarec.bridge;

import java.util.List;

/**
 * The single source of truth for which browser origins may talk to the local bridge — reused by
 * both {@link CorsConfig} (REST, over fetch) and {@link WebSocketConfig} (the live-status handshake).
 * Keeping one list avoids the two drifting apart, which is exactly how the packaged WebSocket once
 * regressed (CORS allowed the renderer's origin but the WS allow-list missed a variant of it).
 *
 * <p>The renderer runs from a different origin than the core, so every value here is one of the
 * renderer's real origins:
 *
 * <ul>
 *   <li>{@code http://localhost:5173} / {@code http://127.0.0.1:5173} — the dev Vite server.</li>
 *   <li>The packaged page is loaded via {@code loadFile()} (a {@code file://} URL). Chromium does
 *       not serialize a file origin the same way for every request kind: {@code fetch()} sends the
 *       opaque {@code Origin: null}, while a {@code WebSocket} handshake from the same page sends a
 *       bare {@code Origin: file://} (no host). Both must be allowed, and they are distinct strings:
 *       <ul>
 *         <li>{@code "null"} — the opaque-origin serialization (fetch).</li>
 *         <li>{@code "file://"} — the bare file origin (WebSocket). A wildcard {@code "file://*"}
 *             does NOT match this: the matcher requires at least one character after {@code file://},
 *             so the host-less form falls through. The bare literal is therefore listed explicitly.</li>
 *         <li>{@code "file://*"} — kept for any Chromium build that appends a host/path to the file
 *             origin, so the allow-list is robust across versions.</li>
 *       </ul></li>
 * </ul>
 *
 * <p>Origin scoping is defense-in-depth; the per-launch bridge token (see {@link BridgeAuthFilter})
 * is the real gate. Restricting origins still matters: it stops an arbitrary page the user visits
 * from reading bridge responses if the tokenless dev/standalone mode were ever running.
 */
final class BridgeOrigins {

    /** Allowed-origin PATTERNS for the renderer (passed to {@code setAllowedOriginPatterns}). */
    static final List<String> RENDERER_PATTERNS =
            List.of("http://localhost:5173", "http://127.0.0.1:5173", "file://", "file://*", "null");

    /** Same list as a {@code String[]} for the varargs {@code setAllowedOriginPatterns(...)} APIs. */
    static String[] patterns() {
        return RENDERER_PATTERNS.toArray(String[]::new);
    }

    private BridgeOrigins() {}
}

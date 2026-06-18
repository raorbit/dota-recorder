package dev.dotarec.bridge;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Live status endpoint consumed by the Electron UI over the loopback bridge.
 *
 * <p>Contract: {@code GET /status} -> 200 with the {@link StatusSnapshot} shape. This is the
 * pull-based companion to the {@code /ws} push channel: the UI reads it once on load (and can
 * re-poll as a fallback), then relies on {@code status} WebSocket frames for live updates.
 */
@RestController
public class StatusController {

    private final StatusService statusService;

    public StatusController(StatusService statusService) {
        this.statusService = statusService;
    }

    @GetMapping("/status")
    public StatusSnapshot status() {
        return statusService.snapshot();
    }
}

package dev.dotarec.bridge;

import dev.dotarec.data.MatchRepository;
import dev.dotarec.data.MatchSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Matches list endpoint consumed by the Electron browse UI over the loopback bridge.
 *
 * <p>TODO: bucket (record_kind/category), result, q (search), and from/to date filters arrive in
 * a later step (plan browse/filter behavior); v0.1 returns the full list.
 */
@RestController
public class MatchController {

    private final MatchRepository repository;

    public MatchController(MatchRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/matches")
    public List<MatchSummary> matches() {
        return repository.findAll();
    }
}

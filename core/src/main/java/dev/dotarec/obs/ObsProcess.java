package dev.dotarec.obs;

import java.time.Instant;
import java.util.Objects;

/**
 * One run of this app's OBS: its process id and the instant that process started. The pair names
 * a single process for good. Windows hands a freed pid to later processes, but never one that
 * started at the same instant, so a reused pid can't pass for an OBS that has exited.
 *
 * @see ObsHealer#findObsProcess()
 */
public record ObsProcess(long pid, Instant startedAt) {

    public ObsProcess {
        Objects.requireNonNull(startedAt, "startedAt");
    }
}

package dev.dotarec.config;

import java.util.function.LongSupplier;

/**
 * The two clocks the recording path reads, bundled into one injectable seam.
 *
 * <ul>
 *   <li>{@code nanoClock} — a MONOTONIC clock ({@code System::nanoTime} in production) for the
 *       record-confirmed offset anchor and the finalize duration. Immune to an OS/NTP wall-clock step,
 *       so marker offsets and duration stay consistent even if the wall clock jumps.</li>
 *   <li>{@code wallClock} — a wall clock ({@code System::currentTimeMillis} in production) for
 *       storage/display stamps only ({@code played_at}, {@code created_at}, journal, pause drain),
 *       NEVER for offset math.</li>
 * </ul>
 *
 * <p>Bundling both behind ONE bean lets {@link dev.dotarec.fsm.MatchFsm} take a single constructor —
 * no per-clock test-seam ctor overloads (which forced a {@code @Autowired} on the production ctor and
 * only failed to boot under {@code @EnableScheduling}). Two separate {@code LongSupplier} beans would
 * collide by type, so they are wrapped here instead. A test constructs {@code new TimeSource(fakeNano,
 * fakeWall)} directly to pin either clock.
 */
public record TimeSource(LongSupplier nanoClock, LongSupplier wallClock) {

    /** The production clocks: monotonic {@code System::nanoTime} and wall {@code System::currentTimeMillis}. */
    public static TimeSource system() {
        return new TimeSource(System::nanoTime, System::currentTimeMillis);
    }

    /** Current value of the monotonic clock (nanoseconds). */
    public long nanoTime() {
        return nanoClock.getAsLong();
    }

    /** Current value of the wall clock (epoch milliseconds). */
    public long wallMillis() {
        return wallClock.getAsLong();
    }
}

package dev.dotarec.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Drives {@link ParentProcessWatchdog#checkParent()} against a test subclass that overrides the
 * {@code parentAlive()} / {@code exit()} seams so {@code System.exit} is never actually called.
 * Proves the watchdog stays inert with no configured parent, stays quiet while the supervisor is
 * alive, and self-terminates exactly once when the supervisor is gone — the backstop that frees the
 * loopback ports for the next launch when the koffi Job Object path degrades to a no-op.
 */
class ParentProcessWatchdogTest {

    /**
     * Records seam invocations and stubs liveness instead of touching real processes or exiting the
     * test JVM.
     */
    private static final class TestWatchdog extends ParentProcessWatchdog {
        private final boolean parentAliveResult;
        int parentAliveCalls;
        int exitCalls;

        TestWatchdog(long parentPid, boolean parentAliveResult) {
            super(parentPid);
            this.parentAliveResult = parentAliveResult;
        }

        @Override
        protected boolean parentAlive() {
            parentAliveCalls++;
            return parentAliveResult;
        }

        @Override
        protected void exit() {
            exitCalls++;
        }
    }

    @Test
    void noOp_whenNoParentPidConfigured() {
        // parentPid=0 is the @Value default for a standalone java -jar / gradlew bootRun.
        TestWatchdog watchdog = new TestWatchdog(0L, false);

        watchdog.checkParent();

        // Early return must short-circuit before any liveness check or exit, otherwise every
        // standalone/dev run would self-terminate on a phantom missing parent.
        assertThat(watchdog.parentAliveCalls).isZero();
        assertThat(watchdog.exitCalls).isZero();
    }

    @Test
    void noOp_whenParentPidNegative() {
        // Boundary: parentPid<0 is also "no supervising parent".
        TestWatchdog watchdog = new TestWatchdog(-1L, false);

        watchdog.checkParent();

        assertThat(watchdog.parentAliveCalls).isZero();
        assertThat(watchdog.exitCalls).isZero();
    }

    @Test
    void doesNotExit_whileParentAlive() {
        TestWatchdog watchdog = new TestWatchdog(4242L, true);

        // Steady state across the whole recording lifetime: repeated polls must stay quiet.
        watchdog.checkParent();
        watchdog.checkParent();
        watchdog.checkParent();

        assertThat(watchdog.parentAliveCalls).isEqualTo(3);
        assertThat(watchdog.exitCalls).isZero();
    }

    @Test
    void exitsOnce_whenParentGone() {
        TestWatchdog watchdog = new TestWatchdog(4242L, false);

        watchdog.checkParent();

        // The whole point of the backstop: a single self-terminate to free :3223 / :3224.
        assertThat(watchdog.parentAliveCalls).isEqualTo(1);
        assertThat(watchdog.exitCalls).isEqualTo(1);
    }
}

package dev.dotarec.fsm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link ArchivedCopyChecks}: what one boot found for a pair holds for the next ones, while it can. */
class ArchivedCopyChecksTest {

    private static final long NOW = 1_763_100_000_000L;

    private Path data;
    private Path copy;
    private Path original;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        data = dir.resolve("data");
        copy = Files.writeString(dir.resolve("copy.mp4"), "recording bytes");
        original = Files.writeString(dir.resolve("original.mp4"), "recording bytes");
    }

    @Test
    void aVerdictHoldsForTheNextBootsUntilItsTimeIsUp() {
        ArchivedCopyChecks boot = new ArchivedCopyChecks(data, NOW);
        assertThat(boot.recent(copy, original)).isNull();
        boot.found(copy, original, "DIFFERENT");
        // Only an earlier boot's verdict counts: this one's was just read.
        assertThat(boot.recent(copy, original)).isNull();
        boot.save();

        ArchivedCopyChecks next = new ArchivedCopyChecks(data, NOW + 60_000L);
        assertThat(next.recent(copy, original)).isEqualTo("DIFFERENT");
        next.save();
        assertThat(new ArchivedCopyChecks(data, NOW + ArchivedCopyChecks.RECHECK_AFTER_MS - 1).recent(copy, original))
                .isEqualTo("DIFFERENT");
        assertThat(new ArchivedCopyChecks(data, NOW + ArchivedCopyChecks.RECHECK_AFTER_MS).recent(copy, original))
                .isNull();
    }

    @Test
    void aPairIsComparedAgainOnceEitherFileChanges() throws Exception {
        remember("WHOLE");
        Files.setLastModifiedTime(original, FileTime.fromMillis(Files.getLastModifiedTime(original).toMillis() + 1_000L));
        assertThat(new ArchivedCopyChecks(data, NOW + 60_000L).recent(copy, original)).isNull();

        remember("WHOLE");
        Files.writeString(copy, "recording bytes and more");
        assertThat(new ArchivedCopyChecks(data, NOW + 60_000L).recent(copy, original)).isNull();
    }

    @Test
    void aVerdictFromLaterThanThisBootsClockCountsForNothing() {
        // A boot on a clock far behind can't tell how old it is.
        remember("DIFFERENT");
        assertThat(new ArchivedCopyChecks(data, NOW - 60_000L).recent(copy, original)).isNull();
    }

    @Test
    void aBootThatSawNoneOfThePairsDropsThem() {
        remember("DIFFERENT");

        new ArchivedCopyChecks(data, NOW + 60_000L).save();

        assertThat(data.resolve(ArchivedCopyChecks.FILE_NAME)).doesNotExist();
    }

    @Test
    void aFileThatCantBeReadRemembersNothing() throws Exception {
        Files.createDirectories(data);
        Files.writeString(data.resolve(ArchivedCopyChecks.FILE_NAME), "not json");

        ArchivedCopyChecks boot = new ArchivedCopyChecks(data, NOW);

        assertThat(boot.recent(copy, original)).isNull();
        assertThat(new ArchivedCopyChecks(null, NOW).recent(copy, original)).isNull();
    }

    private void remember(String verdict) {
        ArchivedCopyChecks boot = new ArchivedCopyChecks(data, NOW);
        boot.found(copy, original, verdict);
        boot.save();
    }
}

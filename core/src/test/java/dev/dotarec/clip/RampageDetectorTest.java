package dev.dotarec.clip;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dotarec.clip.RampageDetector.RampageSpan;
import dev.dotarec.data.MarkerRow;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies rampage detection from kill offsets: a qualifying chain is at least {@code threshold}
 * kills (default 5) each within {@code maxGapSeconds} (default 18.0) of the previous. Covers the
 * boundary cases that bite -- exactly-threshold, a single gap that splits a streak, ties at the same
 * offset counting as distinct kills, unsorted input, two chains in one match -- and that a long
 * uninterrupted streak collapses to one span covering its full first..last range. The marker
 * overload must contribute only {@code "kill"} rows.
 */
class RampageDetectorTest {

    @Test
    void emptyInput_yieldsNoRampages() {
        assertThat(RampageDetector.detectFromOffsets(List.of())).isEmpty();
    }

    @Test
    void fewerThanThreshold_yieldsNoRampages() {
        // Four tightly-packed kills -- one short of the default 5.
        assertThat(RampageDetector.detectFromOffsets(List.of(10.0, 20.0, 30.0, 40.0))).isEmpty();
    }

    @Test
    void exactlyThresholdWithinWindow_yieldsOneSpan() {
        // 5 kills, each 10s apart (<= 18s), so one rampage spanning 100..140.
        List<RampageSpan> spans =
                RampageDetector.detectFromOffsets(List.of(100.0, 110.0, 120.0, 130.0, 140.0));

        assertThat(spans).hasSize(1);
        RampageSpan span = spans.get(0);
        assertThat(span.firstOffsetS()).isEqualTo(100.0);
        assertThat(span.lastOffsetS()).isEqualTo(140.0);
        assertThat(span.killCount()).isEqualTo(5);
    }

    @Test
    void fiveKillsWithOneGapOverWindow_isNotARampage() {
        // 5 kills, but the 3rd->4th gap is 20s (> 18s). That splits the streak into chains of 3 and
        // 2 -- neither reaches the threshold -- so no rampage is emitted.
        List<RampageSpan> spans =
                RampageDetector.detectFromOffsets(List.of(10.0, 20.0, 30.0, 50.0, 60.0));

        assertThat(spans).isEmpty();
    }

    @Test
    void sixConsecutive_collapsesToSingleSpanCoveringFirstToLast() {
        // A 6-in-a-row streak (each 5s apart) is ONE span over the whole range, not several.
        List<RampageSpan> spans =
                RampageDetector.detectFromOffsets(List.of(0.0, 5.0, 10.0, 15.0, 20.0, 25.0));

        assertThat(spans).hasSize(1);
        RampageSpan span = spans.get(0);
        assertThat(span.firstOffsetS()).isEqualTo(0.0);
        assertThat(span.lastOffsetS()).isEqualTo(25.0);
        assertThat(span.killCount()).isEqualTo(6);
    }

    @Test
    void gapExactlyAtWindow_staysInSameChain() {
        // A gap of exactly maxGap (18s) is within window (<=), so the chain holds together.
        List<RampageSpan> spans =
                RampageDetector.detectFromOffsets(List.of(0.0, 18.0, 36.0, 54.0, 72.0));

        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).killCount()).isEqualTo(5);
        assertThat(spans.get(0).firstOffsetS()).isEqualTo(0.0);
        assertThat(spans.get(0).lastOffsetS()).isEqualTo(72.0);
    }

    @Test
    void twoSeparateRampagesInOneList_yieldTwoSpans() {
        // Two 5-kill streaks separated by a 100s lull -> two distinct spans.
        List<Double> offsets = List.of(
                10.0, 20.0, 30.0, 40.0, 50.0, // chain A
                200.0, 210.0, 220.0, 230.0, 240.0); // chain B
        List<RampageSpan> spans = RampageDetector.detectFromOffsets(offsets);

        assertThat(spans).hasSize(2);
        assertThat(spans.get(0).firstOffsetS()).isEqualTo(10.0);
        assertThat(spans.get(0).lastOffsetS()).isEqualTo(50.0);
        assertThat(spans.get(0).killCount()).isEqualTo(5);
        assertThat(spans.get(1).firstOffsetS()).isEqualTo(200.0);
        assertThat(spans.get(1).lastOffsetS()).isEqualTo(240.0);
        assertThat(spans.get(1).killCount()).isEqualTo(5);
    }

    @Test
    void tiesAtIdenticalOffset_countAsDistinctKills() {
        // Two kills land at the exact same offset (gap 0). They are NOT deduped -- a double-kill
        // tick is two kills -- so this 5-entry list with a tie is still a 5-kill rampage.
        List<RampageSpan> spans =
                RampageDetector.detectFromOffsets(List.of(10.0, 20.0, 20.0, 30.0, 40.0));

        assertThat(spans).hasSize(1);
        RampageSpan span = spans.get(0);
        assertThat(span.killCount()).isEqualTo(5);
        assertThat(span.firstOffsetS()).isEqualTo(10.0);
        assertThat(span.lastOffsetS()).isEqualTo(40.0);
    }

    @Test
    void unsortedInput_isSortedBeforeChaining() {
        // The same 5 kills as the exactly-threshold case, scrambled. Detection must sort first, so
        // the result is identical: one span 100..140 of 5 kills.
        List<RampageSpan> spans =
                RampageDetector.detectFromOffsets(List.of(130.0, 100.0, 140.0, 110.0, 120.0));

        assertThat(spans).hasSize(1);
        RampageSpan span = spans.get(0);
        assertThat(span.firstOffsetS()).isEqualTo(100.0);
        assertThat(span.lastOffsetS()).isEqualTo(140.0);
        assertThat(span.killCount()).isEqualTo(5);
    }

    @Test
    void customThresholdAndGap_areHonoured() {
        // threshold 3, gap 5s: a 3-kill streak qualifies; the trailing kill 30s later does not join.
        List<RampageSpan> spans =
                RampageDetector.detectFromOffsets(List.of(0.0, 4.0, 8.0, 38.0), 3, 5.0);

        assertThat(spans).hasSize(1);
        RampageSpan span = spans.get(0);
        assertThat(span.killCount()).isEqualTo(3);
        assertThat(span.firstOffsetS()).isEqualTo(0.0);
        assertThat(span.lastOffsetS()).isEqualTo(8.0);
    }

    @Test
    void detectFromMarkers_usesOnlyKillRowsAndTheirOffsets() {
        // A mix of kill/death/assist markers in arbitrary order. Only the 5 kills feed detection;
        // the interleaved deaths and assists must not break the chain or count toward it.
        List<MarkerRow> markers = List.of(
                marker("death", 5.0),
                marker("kill", 10.0),
                marker("assist", 12.0),
                marker("kill", 20.0),
                marker("kill", 30.0),
                marker("death", 35.0),
                marker("kill", 40.0),
                marker("kill", 50.0));

        List<RampageSpan> spans = RampageDetector.detect(markers);

        assertThat(spans).hasSize(1);
        RampageSpan span = spans.get(0);
        assertThat(span.killCount()).isEqualTo(5);
        assertThat(span.firstOffsetS()).isEqualTo(10.0);
        assertThat(span.lastOffsetS()).isEqualTo(50.0);
    }

    @Test
    void detectFromMarkers_withNoKills_yieldsNoRampages() {
        List<MarkerRow> markers = List.of(
                marker("death", 5.0),
                marker("assist", 12.0),
                marker("roshan", 300.0));

        assertThat(RampageDetector.detect(markers)).isEmpty();
    }

    /** Build a minimal {@link MarkerRow} with just the fields detection reads (type + offset). */
    private static MarkerRow marker(String type, double videoOffsetS) {
        return new MarkerRow(0L, 0L, type, videoOffsetS, null, null, "gsi");
    }
}

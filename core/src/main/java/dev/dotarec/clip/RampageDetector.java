package dev.dotarec.clip;

import dev.dotarec.data.MarkerRow;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds rampages -- bursts of the player's own kills tightly clustered in the recorded video --
 * from kill offsets alone, with no GSI, no IO and no Spring.
 *
 * <p>A rampage is a chain of at least {@code threshold} kills (default 5) where each consecutive
 * kill lands within {@code maxGapSeconds} (default 18.0) of the previous one. The offsets are
 * video-offset seconds -- the same base as {@link MarkerRow#videoOffsetS()} -- so an emitted span
 * can be handed straight to the seeker. A single uninterrupted streak yields exactly one span
 * covering its first..last offset; a gap wider than the window closes the current chain and starts
 * a fresh one, so one match can produce several spans.
 *
 * <p>This is pure, deterministic and side-effect free.
 */
public final class RampageDetector {

    /** Minimum chain length that counts as a rampage. */
    public static final int DEFAULT_THRESHOLD = 5;

    /** Maximum seconds between consecutive kills for them to stay in the same chain. */
    public static final double DEFAULT_MAX_GAP_SECONDS = 18.0;

    private RampageDetector() {
    }

    /**
     * One detected rampage, covering a contiguous run of kills.
     *
     * @param firstOffsetS video-offset seconds of the first kill in the chain
     * @param lastOffsetS  video-offset seconds of the last kill in the chain
     * @param killCount    number of kills in the chain (always {@code >= threshold})
     */
    public record RampageSpan(double firstOffsetS, double lastOffsetS, int killCount) {
    }

    /**
     * Detect rampages from raw kill offsets, using the default 5-kill / 18.0s window.
     *
     * @param killOffsetsSeconds video-offset seconds of each kill (order-independent; nulls ignored)
     * @return one span per qualifying chain, in ascending offset order
     */
    public static List<RampageSpan> detectFromOffsets(List<Double> killOffsetsSeconds) {
        return detectFromOffsets(killOffsetsSeconds, DEFAULT_THRESHOLD, DEFAULT_MAX_GAP_SECONDS);
    }

    /**
     * Detect rampages from raw kill offsets.
     *
     * <p>Offsets are sorted ascending, then chains are grown greedily: the running chain extends to
     * the next kill while the gap stays {@code <= maxGapSeconds} (ties at the same offset have a gap
     * of 0 and always stay together, each counting as a distinct kill). A wider gap closes the
     * chain; if the closed chain held at least {@code threshold} kills it emits one span spanning its
     * first..last offset, otherwise it is discarded. The final open chain is closed the same way.
     *
     * @param killOffsetsSeconds video-offset seconds of each kill (order-independent; nulls ignored)
     * @param threshold          minimum chain length to qualify as a rampage
     * @param maxGapSeconds       maximum seconds between consecutive kills within a chain
     * @return one span per qualifying chain, in ascending offset order
     */
    public static List<RampageSpan> detectFromOffsets(
            List<Double> killOffsetsSeconds, int threshold, double maxGapSeconds) {
        List<RampageSpan> spans = new ArrayList<>();
        if (killOffsetsSeconds == null || killOffsetsSeconds.isEmpty()) {
            return spans;
        }

        List<Double> sorted = new ArrayList<>(killOffsetsSeconds.size());
        for (Double offset : killOffsetsSeconds) {
            if (offset != null) {
                sorted.add(offset);
            }
        }
        if (sorted.isEmpty()) {
            return spans;
        }
        sorted.sort(null);

        double chainFirst = sorted.get(0);
        double prev = sorted.get(0);
        int chainCount = 1;

        for (int i = 1; i < sorted.size(); i++) {
            double offset = sorted.get(i);
            if (offset - prev <= maxGapSeconds) {
                chainCount++;
            } else {
                emitIfQualifies(spans, chainFirst, prev, chainCount, threshold);
                chainFirst = offset;
                chainCount = 1;
            }
            prev = offset;
        }
        emitIfQualifies(spans, chainFirst, prev, chainCount, threshold);

        return spans;
    }

    /**
     * Detect rampages from marker rows, using the default 5-kill / 18.0s window. Only {@code "kill"}
     * markers contribute; everything else (deaths, assists, roshan, ...) is ignored.
     *
     * @param markers marker rows for a match (any order; non-kill rows ignored)
     * @return one span per qualifying chain, in ascending offset order
     */
    public static List<RampageSpan> detect(List<MarkerRow> markers) {
        return detect(markers, DEFAULT_THRESHOLD, DEFAULT_MAX_GAP_SECONDS);
    }

    /**
     * Detect rampages from marker rows. Only {@code "kill"} markers contribute; everything else
     * (deaths, assists, roshan, ...) is ignored. Their {@link MarkerRow#videoOffsetS()} values are
     * fed to {@link #detectFromOffsets(List, int, double)}.
     *
     * @param markers       marker rows for a match (any order; non-kill rows ignored)
     * @param threshold     minimum chain length to qualify as a rampage
     * @param maxGapSeconds maximum seconds between consecutive kills within a chain
     * @return one span per qualifying chain, in ascending offset order
     */
    public static List<RampageSpan> detect(List<MarkerRow> markers, int threshold, double maxGapSeconds) {
        if (markers == null || markers.isEmpty()) {
            return new ArrayList<>();
        }
        List<Double> killOffsets = new ArrayList<>();
        for (MarkerRow marker : markers) {
            if (marker != null && "kill".equals(marker.type())) {
                killOffsets.add(marker.videoOffsetS());
            }
        }
        return detectFromOffsets(killOffsets, threshold, maxGapSeconds);
    }

    private static void emitIfQualifies(
            List<RampageSpan> spans, double first, double last, int count, int threshold) {
        if (count >= threshold) {
            spans.add(new RampageSpan(first, last, count));
        }
    }
}

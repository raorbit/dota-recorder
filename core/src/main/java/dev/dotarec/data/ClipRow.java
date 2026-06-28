package dev.dotarec.data;

/**
 * One row of the {@code clips} table: a sub-range of a parent match's recording rendered into its own
 * .mp4. Clips are either {@code auto} (triggered by a tagged moment, e.g. a rampage) or {@code manual}
 * (carved by the user). {@code startOffsetS}/{@code endOffsetS} are seconds into the parent VOD that
 * bound the clip; the video/thumb paths and size are NULL until the async generator fills them in and
 * flips {@code status} from {@code pending} → {@code generating} → {@code ready}/{@code failed}.
 *
 * @param id             surrogate PK
 * @param parentMatchId  owning {@code matches.id}
 * @param kind           {@code auto} or {@code manual}
 * @param triggerReason  reason an auto clip fired (e.g. {@code rampage}); null for manual clips
 * @param startOffsetS   start, in seconds from the start of the parent video
 * @param endOffsetS     end, in seconds from the start of the parent video
 * @param label          optional human-readable label, or null
 * @param videoPath      path to the rendered clip .mp4, or null until ready
 * @param thumbPath      path to the clip thumbnail, or null until ready
 * @param fileSizeBytes  rendered clip size in bytes, or null until ready
 * @param status         {@code pending}, {@code generating}, {@code ready}, or {@code failed}
 * @param error          failure detail when {@code status == failed}, else null
 * @param createdAt      epoch millis the clip row was created
 */
public record ClipRow(
        long id,
        long parentMatchId,
        String kind,
        String triggerReason,
        double startOffsetS,
        double endOffsetS,
        String label,
        String videoPath,
        String thumbPath,
        Long fileSizeBytes,
        String status,
        String error,
        long createdAt
) {
}

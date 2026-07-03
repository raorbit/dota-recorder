package dev.dotarec.fsm;

/**
 * The journaled shape of a tagged marker, shared by the writer ({@link MatchFsm#markerPayload}) and the
 * reader ({@link CrashRecoveryRunner#parseMarker}) so the two can never drift on the wire format.
 *
 * <p>The component NAMES are the FROZEN JSON keys: {@code type}, {@code videoOffsetS}, {@code gameClock},
 * {@code label}, {@code source}. Renaming a component renames the key and would silently drop every marker
 * recovered from a crash-era journal. {@code MatchFsm} serializes this record whole; {@code
 * CrashRecoveryRunner} deserializes it field-by-field (NOT via {@code readValue}) to keep its per-field
 * tolerance — chiefly an absent {@code gameClock} falling back to the journal event's own game clock
 * rather than becoming null.
 */
record MarkerPayload(String type, double videoOffsetS, Integer gameClock, String label, String source) {}

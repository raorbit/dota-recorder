// Pure, dependency-free byte formatting for human-readable size readouts (e.g. the low-disk banner's
// "12.4 GB free"). Kept React/DOM-free so it unit-tests in plain Node (see format-bytes.test.ts).
//
// Distinct from settings/recording/fmtSize, which rounds to whole GB for the storage-cap fields; this
// one keeps one decimal at GB+ so a few-GB free-space figure reads precisely rather than snapping to a
// whole number. Base-1024 units (KiB semantics) shown with the familiar B/KB/MB/GB/TB/PB labels.
const UNITS = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'] as const;

export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B';
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < UNITS.length - 1) {
    value /= 1024;
    unit++;
  }
  // Bytes are whole; larger units get one decimal, and a trailing ".0" drops out naturally because the
  // rounded value is a Number (e.g. 8 -> "8 GB", 12.4 -> "12.4 GB").
  const rounded = unit === 0 ? Math.round(value) : Math.round(value * 10) / 10;
  return `${rounded} ${UNITS[unit]}`;
}

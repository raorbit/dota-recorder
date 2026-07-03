import type { DriveUsage } from '../../../api/client';

// Resolution presets offered in the dropdown. A stored value outside this list
// (e.g. an ultrawide) is preserved and shown as an extra leading option.
export const RES_PRESETS: ReadonlyArray<{ readonly value: string; readonly label: string }> = [
  { value: '1280x720', label: '1280 × 720 (720p)' },
  { value: '1920x1080', label: '1920 × 1080 (1080p)' },
  { value: '2560x1440', label: '2560 × 1440 (1440p)' },
  { value: '3840x2160', label: '3840 × 2160 (4K)' },
];

// The core auto-probes a hardware encoder and writes back a short OBS token; map
// it to a human label. Unknown tokens fall through to the raw value.
export const ENCODER_LABELS: Record<string, string> = {
  nvenc: 'NVIDIA NVENC (H.264)',
  amd: 'AMD AMF (H.264)',
  qsv: 'Intel QuickSync (H.264)',
  x264: 'x264 (software)',
};

// The fps/quality/format value sets below mirror the server-side allow-lists in
// SettingsController.ALLOWED_* — keep them in sync (the core 400s a value outside its set).
//
// Frame-rate presets. OBS "Common FPS" integers only (FPSType stays 0); 120/144 would
// need FPSType=1 and fractional rates (29.97) need a String, so the int field is
// restricted to 30/60.
export const FPS_PRESETS: ReadonlyArray<{ readonly value: number; readonly label: string }> = [
  { value: 30, label: '30 fps' },
  { value: 60, label: '60 fps' },
];

// OBS RecQuality tokens (case-sensitive). "Small" is tolerated if already stored but
// omitted from the picker. A stored value outside this list is preserved as a leading option.
export const QUALITY_PRESETS: ReadonlyArray<{ readonly value: string; readonly label: string }> = [
  { value: 'Stream', label: 'Stream (smaller files)' },
  { value: 'HQ', label: 'High quality' },
  { value: 'Lossless', label: 'Lossless (huge files)' },
];

// OBS RecFormat2 containers. Restricted to the MP4 variants the in-app Chromium <video> can decode
// for jump-to-moment playback: mkv/mov record fine but won't preview in-app (no Matroska demuxer,
// flaky MOV), which silently breaks the headline feature. Both options here are crash-safe; plain
// `mp4` is omitted (unfinalized-file corruption risk on crash). Out-of-list stored value preserved.
export const FORMAT_PRESETS: ReadonlyArray<{ readonly value: string; readonly label: string }> = [
  { value: 'hybrid_mp4', label: 'MP4 (hybrid)' },
  { value: 'fragmented_mp4', label: 'MP4 (fragmented)' },
];

// Floor for any drive cap (GB). There is no ceiling anymore — caps are free-form numbers,
// bounded only by the drive's real capacity (which the UI surfaces as a warning).
export const CAP_MIN_GB = 10;

// Bounds for the auto-clip padding (seconds). The core CLAMPS values outside this range
// to [1,60] (it does not 400); we also clamp client-side on blur/save so the field shows
// the value that will actually take effect (a cleared field reads as 0).
export const PADDING_MIN_S = 1;
export const PADDING_MAX_S = 60;

// Coerce a cap field's raw value into the positive integer the backend accepts. The core
// now rejects <=0 (a cleared field yields Number('')===0), so we never send a non-positive
// or fractional cap: blank/NaN/<=0 snaps up to CAP_MIN_GB and any fraction is rounded. Used
// both to reflect a sane value back into the field (onBlur) and to sanitize what we PUT.
export function clampCapGb(value: number): number {
  if (!Number.isFinite(value) || value < CAP_MIN_GB) return CAP_MIN_GB;
  return Math.round(value);
}

// Coerce the padding field's raw value into the bounded integer the backend accepts:
// blank/NaN/<1 snaps up to PADDING_MIN_S, anything past PADDING_MAX_S clamps down, and
// fractions are rounded. Used to reflect a sane value back (onBlur) and sanitize the PUT.
export function clampPadding(value: number): number {
  if (!Number.isFinite(value) || value < PADDING_MIN_S) return PADDING_MIN_S;
  return Math.min(Math.round(value), PADDING_MAX_S);
}

// Human-readable size from a byte count (null -> em dash). TB once past 1024 GB.
export function fmtSize(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined) return '—';
  const gb = bytes / 1024 ** 3;
  return gb >= 1024 ? `${(gb / 1024).toFixed(1)} TB` : `${Math.round(gb)} GB`;
}

// True when a configured cap can't be reached because the drive is too small: the cap
// exceeds what's physically attainable for our VODs (bytes we already store there + free
// space). Only meaningful once the drive has been saved and stat'd (freeBytes known).
export function capExceedsDrive(capGb: number, usage: DriveUsage | undefined): boolean {
  if (!usage || usage.freeBytes === null) return false;
  const reachableBytes = usage.usedBytes + usage.freeBytes;
  return capGb * 1024 ** 3 > reachableBytes;
}

// Once OBS can't start recording with the hardware encoder Auto picked, the core records with x264
// for the rest of its process: a tray session can run for days on the CPU while the encoder still
// reads Auto. GET /settings names the encoder Auto fell back from (Settings.autoEncoderFallbackFrom),
// and the encoder row says so in one sentence while Auto is the choice on screen. Null when there is
// nothing to say: Auto runs what it picked, or another encoder is chosen (picking one drops the
// fallback on save).
export function encoderFallbackNote(
  encoderChoice: string,
  fallbackFrom: string | null,
  labels: Readonly<Record<string, string>>,
): string | null {
  if (encoderChoice !== 'auto' || !fallbackFrom) return null;
  const from = labels[fallbackFrom] ?? fallbackFrom;
  const x264 = labels.x264 ?? 'x264';
  return (
    `Auto is using ${x264} because ${from} failed to start recording. ` +
    'Restarting the app tries it again.'
  );
}

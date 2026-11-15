package dev.dotarec.obs;

/**
 * OBS accepted StartRecord but never confirmed {@code OUTPUT_STARTED}. Unlike a rejected request,
 * this means OBS itself is wedged (its outputs disabled by a dialog, or its graphics device lost),
 * which a restart of OBS fixes, or that it can't open its encoder, which a restart alone doesn't.
 * Once a second run of OBS fails this way, or the OBS that took the request crashed while it
 * started the output, the FSM switches an auto-picked hardware encoder to x264 (see
 * {@link dev.dotarec.obs.setup.EncoderFallback}). A video folder that is missing or full fails the
 * same way, before OBS opens any encoder; the fallback leaves the encoder alone then.
 */
public class ObsStartNotConfirmedException extends ObsException {

    public ObsStartNotConfirmedException(String message) {
        super(message);
    }
}

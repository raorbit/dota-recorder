package dev.dotarec.obs;

/**
 * OBS accepted StartRecord but never confirmed {@code OUTPUT_STARTED}. Unlike a rejected request,
 * this means OBS itself is wedged (its outputs disabled by a dialog, or its graphics device lost),
 * which a restart of OBS fixes, or that its encoder can't open, which a restart alone doesn't: when
 * starts keep failing, the FSM switches an auto-picked hardware encoder to x264 first (see
 * {@link dev.dotarec.obs.setup.EncoderFallback}).
 */
public class ObsStartNotConfirmedException extends ObsException {

    public ObsStartNotConfirmedException(String message) {
        super(message);
    }
}

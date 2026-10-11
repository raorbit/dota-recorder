package dev.dotarec.obs;

/**
 * OBS accepted StartRecord but never confirmed {@code OUTPUT_STARTED}. Unlike a rejected request,
 * this means OBS itself is wedged (its outputs disabled by a dialog, or its graphics device lost),
 * which a restart of OBS fixes.
 */
public class ObsStartNotConfirmedException extends ObsException {

    public ObsStartNotConfirmedException(String message) {
        super(message);
    }
}

package dev.dotarec.obs.setup;

/**
 * Switches the recording encoder to x264 when OBS can't start recording with the hardware encoder
 * it was given. {@link ObsConfigWriter} implements it; the FSM asks once starts keep failing.
 */
@FunctionalInterface
public interface EncoderFallback {

    /** For tests and setups without a managed OBS profile: never switches anything. */
    EncoderFallback NONE = () -> false;

    /**
     * Switches an auto-picked hardware encoder to x264 in the OBS profile, for the rest of this
     * process. A running OBS keeps the profile it loaded, so the caller restarts OBS after.
     *
     * @return true when the profile now names x264 where it named a hardware encoder
     */
    boolean fallBackToX264();
}

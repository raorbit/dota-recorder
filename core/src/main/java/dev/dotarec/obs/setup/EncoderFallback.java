package dev.dotarec.obs.setup;

/**
 * Switches the recording encoder to x264 when OBS can't start recording with the hardware encoder
 * it was given. {@link ObsConfigWriter} implements it; the FSM asks once a second run of OBS in a
 * row accepts a start and never starts it, or once an OBS crashed while it started recording.
 */
@FunctionalInterface
public interface EncoderFallback {

    /** For tests and setups without a managed OBS profile: never switches anything. */
    EncoderFallback NONE = () -> false;

    /**
     * Switches an auto-picked hardware encoder to x264 in the OBS profile, for the rest of this
     * process. A running OBS keeps the profile it loaded, so x264 records from the next OBS launched:
     * the caller's restart, or Electron's relaunch of an OBS that crashed. Nothing is switched while
     * the video folder is missing or its drive has under 50 MB free, which OBS refuses before it
     * opens any encoder. A folder that exists but can't be written to is not detected: OBS fails
     * that start only after the encoder opened, so it can still bring the switch.
     *
     * @return true when the profile now names x264 where it named a hardware encoder
     */
    boolean fallBackToX264();
}

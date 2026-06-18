package dev.dotarec.setup;

/**
 * Supplies the Dota 2 launch option required to enable GSI.
 *
 * <p>Plan (Setup -> GSI): Dota only loads gamestate_integration cfgs when launched with
 * {@code -gamestateintegration}; the setup UI surfaces this string for the user to paste into
 * Steam launch options.
 */
public final class LaunchOptionHelper {

    /** The Steam launch option that activates GSI cfg loading. */
    public static final String LAUNCH_OPTION = "-gamestateintegration";

    private LaunchOptionHelper() {
    }

    public static String launchOption() {
        return LAUNCH_OPTION;
    }
}

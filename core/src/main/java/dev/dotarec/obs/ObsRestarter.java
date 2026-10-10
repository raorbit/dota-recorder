package dev.dotarec.obs;

/**
 * Restarts a wedged OBS. The FSM calls this when OBS can no longer produce a recording; Electron's
 * OBS supervisor relaunches the process it sees exit, and the core reconnects and reconfigures it.
 */
public interface ObsRestarter {

    /** For tests and setups without a managed OBS: never restarts anything. */
    ObsRestarter NONE = reason -> false;

    /**
     * Ends the managed OBS process so it is relaunched fresh.
     *
     * @param reason logged alongside the restart
     * @return true when an OBS process was ended
     */
    boolean restartObs(String reason);
}

package dev.doughbay.fabric;
/** Selected escape action; optional movement never overrides pause/disconnect. */
final class EvasionRoute {
    enum Action { PAUSE, DISCONNECT, TELEPORT }
    static Action action(int mode, boolean useHomes, boolean hasHomes) {
        if (mode == 0) return Action.PAUSE;
        if (mode != 1 || useHomes && !hasHomes) return Action.DISCONNECT;
        return Action.TELEPORT;
    }
    static boolean mayWander(int mode, boolean useHomes, boolean hasHomes) {
        return action(mode,useHomes,hasHomes) == Action.TELEPORT;
    }
}

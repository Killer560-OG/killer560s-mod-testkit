package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * A {@code 93-solve-*} case's own settings and checks on top of {@link SimPuzzleSolveTests}' (see
 * {@link SimPuzzleRoundTests}). One instance per run.
 */
abstract class SolveExtra {

    String name = "";
    long mark;

    void begin(String name, long mark) {
        this.name = name;
        this.mark = mark;
    }

    /** Client thread, inside the room's configure, with the auto-puzzles and cheat-utils configs. */
    void configure(Object auto, Object cheat) {
    }

    /** Every watched tick; {@code solvedAt} is -1 until the sim said solved, {@code container} a container was open. */
    void tick(ClientGameTestContext ctx, int t, int solvedAt, boolean container) {
    }

    /** False while the case still waits for something after the solve (up to 20 s). */
    boolean satisfied() {
        return true;
    }

    /** Null on a pass, else what went wrong. */
    String verdict(ClientGameTestContext ctx, int solvedAt) {
        return null;
    }

    String summary() {
        return "";
    }
}

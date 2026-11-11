package dev.testkit.gametest.menu;

import dev.testkit.gametest.hx.Session;

/**
 * flaghunt-1110 group C: the container automations with his movement keys held while the menu opens. Vanilla releases the keys
 * when a screen opens, but the server's last input still says "moving" until the next input packet, so a click sent before
 * it is GrimAC MultiActionsC (and the close MultiActionsD). Each case is the matching 1105-same-*-click case (it asserts the
 * feature acted and its clicks reached the wire, his own click in the same ticks) run with forward, forward+sprint or sneak
 * held from before the menu opened; GrimAC (default and experimental) must stay silent.
 */
final class Flaghunt1110MenuCases {

    private Flaghunt1110MenuCases() {
    }

    static void register(Session s) {
        for (String mode : new String[]{"fwd", "sprint", "sneak"}) {
            MenuSuite.test(s, "1110-c-autosell-moving-" + mode, c -> moving(c, mode, () -> SameTickMenu1105Cases.autoSell(c)));
            MenuSuite.test(s, "1110-c-autoterm-moving-" + mode, c -> moving(c, mode, () -> SameTickMenu1105Cases.autoTerm(c, false)));
            MenuSuite.test(s, "1110-c-invsort-moving-" + mode, c -> moving(c, mode, () -> SameTickMenu1105Cases.invSort(c)));
        }
        // The existing solver / chest / leap / anvil cases, run with forward + sprint held .
        MenuSuite.test(s, "1110-c-exp-chronomatron-moving", c -> moving(c, "sprint", () -> ExperimentCases.auto(c, "CHRONOMATRON")));
        MenuSuite.test(s, "1110-c-exp-ultrasequencer-moving", c -> moving(c, "sprint", () -> ExperimentCases.auto(c, "ULTRASEQUENCER")));
        MenuSuite.test(s, "1110-c-exp-superpairs-moving", c -> moving(c, "sprint", () -> ExperimentCases.auto(c, "SUPERPAIRS")));
        for (String mode : new String[]{"fwd", "sprint", "sneak"}) {
            MenuSuite.test(s, "1110-c-autoclosechest-moving-" + mode, c -> DungeonMenuCases.autoCloseChestMoving(c, mode));
        }
        MenuSuite.test(s, "1110-c-fastleap-moving", c -> moving(c, "sprint", () -> DungeonMenuCases.fastLeap(c)));
        MenuSuite.test(s, "1110-c-anvil-moving", c -> moving(c, "sprint", () -> AnvilCases.pairs(c)));
        MenuSuite.test(s, "1110-c-croesus-moving", c -> moving(c, "sprint", () -> DungeonMenuCases.croesus(c)));
    }

    private interface Body {
        void run() throws Exception;
    }

    private static void moving(Session c, String mode, Body body) throws Exception {
        var in = c.ctx().getInput();
        try {
            switch (mode) {
                case "sneak" -> in.holdKey(o -> o.keyShift);
                case "sprint" -> {
                    in.holdKey(o -> o.keyUp);
                    in.holdKey(o -> o.keySprint);
                }
                default -> in.holdKey(o -> o.keyUp);
            }
            c.ctx().waitTicks(8);
            body.run();
        } finally {
            in.releaseKey(o -> o.keyUp);
            in.releaseKey(o -> o.keySprint);
            in.releaseKey(o -> o.keyShift);
        }
    }
}

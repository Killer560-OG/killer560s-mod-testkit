package dev.testkit.gametest.hx;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * cheatbugs-1109: Terminal machinery on its own switches (Auto/Hover/Queue with the Terminal Solver off), Auto Simon Says
 * with every other Simon Says switch off, Blood Camp's Work Just Outside Blood Room default, and Auto Superboom on a P3
 * gate block (the server saw held=2 with his own dig in the swap's tick, Grim default and experimental checkouts).
 */
final class HxCheatBugs1109Cases {

    private static final String TERM = "terminals.TerminalSolverConfig";
    private static final String SS = "simonsays.SimonSaysConfig";
    private static final String BLOOD = "bloodcamp.BloodCampConfig";
    private static final String BOOM = "minecraft:tnt[custom_data={id:\"SUPERBOOM_TNT\"},item_name=\"Superboom TNT\"]";

    private HxCheatBugs1109Cases() {
    }

    static void register(Session s) {
        s.test("1109-terms-own-switches", HxCheatBugs1109Cases::terms);
        s.test("1109-ss-own-switch", HxCheatBugs1109Cases::ss);
        s.test("1109-bloodcamp-outside-setting", HxCheatBugs1109Cases::bloodSetting);
        s.test("1109-superboom-gate", c -> boom(c, "gate"));
        s.test("1109-superboom-crypt", c -> boom(c, "crypt"));
        s.test("1109-superboom-prince", c -> boom(c, "prince"));
    }

    private static boolean b(Object cfg, String m) {
        return (Boolean) Mod.call(cfg, m);
    }

    private static void gateOff(Session c, boolean on) {
        c.onClient(mc -> {
            Mod.staticCall("util.SkyblockGate", "setEnabled", on);
            return null;
        });
    }

    private static void terms(Session c) {
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        gateOff(c, false);
        try {
            Object cfg = Mod.cfg(TERM);
            try (Settings set = new Settings(c).with(TERM, "Enabled", false).with(TERM, "AutoTerminalsEnabled", false)
                    .with(TERM, "HoverTerminalsEnabled", false).with(TERM, "QueueTerms", false)) {
                c.check(!c.onClient(mc -> b(cfg, "isMachineryOn")), "all four off: the terminal machinery still runs");
                if (Mod.isCheat()) {
                    try (Settings h = new Settings(c).with(TERM, "HoverTerminalsEnabled", true)) {
                        c.check(c.onClient(mc -> b(cfg, "isHoverTerminalsEnabled")), "Hover Terms on with the solver off: not enabled");
                        c.check(c.onClient(mc -> b(cfg, "isMachineryOn")), "Hover Terms on: machinery off");
                        c.check(!c.onClient(mc -> b(cfg, "isEnabled")), "the solver switch followed Hover Terms");
                    }
                    try (Settings a = new Settings(c).with(TERM, "AutoTerminalsEnabled", true)) {
                        c.check(c.onClient(mc -> b(cfg, "isMachineryOn")), "Auto Terms on: machinery off");
                    }
                }
                try (Settings q = new Settings(c).with(TERM, "QueueTerms", true)) {
                    c.check(c.onClient(mc -> b(cfg, "isMachineryOn")), "Queue Terms on with the solver off: machinery off");
                }
                c.check(!c.onClient(mc -> b(cfg, "isMachineryOn")), "back to all off: the machinery still runs");
            }
        } finally {
            gateOff(c, gateWas);
        }
    }

    private static void ss(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Simon Says is cheat-only");
            return;
        }
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        gateOff(c, false);
        try {
            Object cfg = Mod.cfg(SS);
            try (Settings set = new Settings(c).with(SS, "Enabled", false).with(SS, "AutoSimonSaysEnabled", false)
                    .with(SS, "AutoSolveEnabled", false).with(SS, "AutoStartEnabled", false).with(SS, "TriggerBotEnabled", false)) {
                c.check(!c.onClient(mc -> b(cfg, "isMachineryOn")), "everything off: Simon Says machinery runs");
                try (Settings a = new Settings(c).with(SS, "AutoSimonSaysEnabled", true).with(SS, "AutoSolveEnabled", true)) {
                    c.check(c.onClient(mc -> b(cfg, "isMachineryOn")), "Auto SS on with the solver switch off: machinery off");
                    c.check(!c.onClient(mc -> b(cfg, "isEnabled")), "the solver switch followed Auto SS");
                }
            }
        } finally {
            gateOff(c, gateWas);
        }
    }

    private static void bloodSetting(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Work Just Outside Blood Room is cheat-only");
            return;
        }
        Object cfg = Mod.cfg(BLOOD);
        try (Settings set = new Settings(c).with(BLOOD, "WorkOutside", false)) {
            c.check(!c.onClient(mc -> b(cfg, "isWorkOutside")), "Work Just Outside is not OFF");
        }
        try (Settings set = new Settings(c).with(BLOOD, "WorkOutside", true)) {
            c.check(c.onClient(mc -> b(cfg, "isWorkOutside")), "Work Just Outside did not turn ON");
        }
    }

    /** Auto Superboom on one target kind: "gate" (a P3 gate block, in P3), "crypt" (a lid of bottom slabs on a stone base) or
     *  "prince" (a sea lantern ringed by polished andesite). The server must see held=2 with his dig in the swap's tick. */
    private static void boom(Session c, String kind) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Superboom is cheat-only");
            return;
        }
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        SameTick st = null;
        try (Settings set = new Settings(c).with("autosuperboom.AutoSuperboomConfig", "Enabled", true)) {
            gateOff(c, false);
            c.hx().give(2, BOOM);
            c.hx().give(4, "minecraft:stick");
            c.hx().give(5, "minecraft:stick");
            dungeon(c);
            if (kind.equals("gate")) {
                c.hx().chat("[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!");
                c.server().command("tellraw @a \"[BOSS] Goldor: Who dares trespass into my domain?\"");
                c.waitUntil("P3 running", mc -> (Boolean) Mod.staticCall("leveraura.P3SectionTracker", "running"), 100);
            }
            c.server().command("forceload add 10 120 24 135");
            c.server().sync(20);
            c.server().command("fill 14 119 124 20 119 132 minecraft:stone");
            c.server().command("fill 14 120 124 20 125 132 minecraft:air");
            // The player at 17.5,120,126.5 (gate: 127.5) and the block he is aimed at, pitched at its middle.
            boolean g = kind.equals("gate");
            double pz = g ? 127.5 : 126.5;
            BlockPos target = g ? new BlockPos(17, 121, 129) : new BlockPos(17, 120, 129);
            double dy = 121.62 - (g ? 121.5 : kind.equals("crypt") ? 121.25 : 120.5);
            float pitch = g ? 0f : (float) Math.toDegrees(Math.atan2(dy, 129 - pz));
            Runnable build = () -> {
                if (g) {
                    c.server().command("setblock 17 121 129 minecraft:cracked_stone_bricks");
                } else if (kind.equals("crypt")) {
                    c.server().command("fill 16 120 129 18 120 130 minecraft:stone_bricks");
                    c.server().command("fill 16 121 129 18 121 130 minecraft:smooth_stone_slab[type=bottom]");
                } else {
                    c.server().command("setblock 17 120 130 minecraft:sea_lantern");
                    for (String o : new String[]{"16 120 130", "18 120 130", "17 120 129", "17 120 131"}) {
                        c.server().command("setblock " + o + " minecraft:polished_andesite");
                    }
                }
            };
            build.run();
            c.server().command(String.format(java.util.Locale.ROOT, "tp @p 17.5 120 %.1f 0 %.2f", pz, pitch));
            c.onClient(mc -> {
                mc.player.setYRot(0f);
                mc.player.setXRot(pitch);
                mc.player.getInventory().setSelectedSlot(4);
                return null;
            });
            c.ctx().waitTicks(5);
            if (!g) {
                Object found = c.onClient(mc -> Mod.staticCall("secretwaypoints.CryptScanner", "scan", mc.level,
                        new int[]{1, 113, 33, 145}));
                c.note(kind + ": CryptScanner found " + found);
                c.check(String.valueOf(found).contains(kind.equals("crypt") ? "CRYPT" : "PRINCE"), "the scanner does not see the " + kind);
            }
            long since = c.hx().head();
            st = c.onClient(mc -> SameTick.start(SameTick.Act.DIG, 4, p -> p instanceof ServerboundSetCarriedItemPacket h
                    && (h.getSlot() == 2 || h.getSlot() == 4), 4, 5));
            for (int r = 0; r < 6; r++) {
                build.run();
                c.waitUntil("the " + kind + " block under his crosshair", mc -> mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bh
                        && bh.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                        && (g ? bh.getBlockPos().equals(target) : bh.getBlockPos().getZ() <= 131 && bh.getBlockPos().getZ() >= 129
                        && bh.getBlockPos().getY() <= 121), 100);
                c.ctx().waitTicks(10);
            }
            SameTick.judge(c, st, "auto superboom " + kind, SameTick.Expect.SHARE);
            st = null;
            c.ctx().waitTicks(10);
            var held = c.hx().events(since, "held");
            long boom = held.stream().filter(e -> e.get("slot").getAsInt() == 2).count();
            c.note("server held=2 events " + boom);
            c.check(boom >= 1, "the server never saw the Superboom slot on a " + kind + " block (the feature did not act)");
        } finally {
            if (st != null) {
                SameTick s2 = st;
                c.onClient(mc -> {
                    s2.close();
                    return null;
                });
            }
            gateOff(c, gateWas);
            for (int s : new int[]{2, 4, 5}) {
                c.hx().give(s, "minecraft:air");
            }
            hub(c);
        }
    }
}

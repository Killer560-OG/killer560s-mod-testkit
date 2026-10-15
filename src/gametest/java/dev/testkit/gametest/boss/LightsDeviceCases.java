package dev.testkit.gametest.boss;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.PacketWatch;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LeverBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lights Device (mod lights-aura-1015, killer560 2026-10-15: "Make lights have its own aura trigger and own triggerbot
 * stuff it should not be impacted by other auras. It should be its own cheat."). The six S2 Lights levers are built at
 * their real coordinates (wall at z 142, the 846 arena) and he stands 2.5 blocks in front of them facing the wall, so the
 * crosshair is on the middle-low lever (60,134,142). Cheat jar only; every case proves what was flicked from the server's
 * own lever states AND the mod's record of which half flicked it, and GrimAC must stay silent (Session.test).
 * <ul>
 *   <li>1476 Lights Aura with Secret Aura and In Boss both OFF: before S2 it flicks exactly the three unlit levers (never a
 *       lit one); once S2 opens with all six lit it makes exactly one activation flick; no other feature sends anything.
 *       The camera never moves and the body is given back.</li>
 *   <li>1477 Lights Aura AND Lights Triggerbot with Secret Aura ON (In Rooms, In Boss, S2 Section Levers on): all six
 *       unlit levers are lit, each flicked exactly once (six uses, never two in one tick), every flick by the Lights
 *       Device, none ever attempted by Secret Aura.</li>
 *   <li>1478 Lights Triggerbot alone (Aura off, 100 ms delay): only the lever under the crosshair is flicked, not before
 *       the delay; turning (server tp) onto a second lever flicks that one too; the other four stay unlit.</li>
 * </ul>
 */
final class LightsDeviceCases {

    static final String CFG = "lightsdevice.LightsDeviceConfig";
    static final String FEATURE = "lightsdevice.LightsDeviceFeature";
    private static final String CU = "cheatutils.CheatUtilsConfig";
    private static final String LA = "leveraura.LeverAuraConfig";
    static final int[][] LIGHTS = {{62, 136}, {58, 136}, {60, 135}, {60, 134}, {62, 133}, {58, 133}};
    /** Under the crosshair from (60.5, 133, 139.5) at yaw 0, pitch 0. */
    static final BlockPos LOOKED = new BlockPos(60, 134, 142);

    private LightsDeviceCases() {
    }

    static void register(Session s) {
        if (!Mod.isCheat()) {
            return;
        }
        s.test("1476-boss-lights-aura", LightsDeviceCases::aura);
        s.test("1477-boss-lights-with-secret-aura", LightsDeviceCases::withSecretAura);
        s.test("1478-boss-lights-triggerbot", LightsDeviceCases::triggerbot);
    }

    static BlockPos lever(int[] l) {
        return new BlockPos(l[0], l[1], 142);
    }

    /** Each lever unlit unless its index is in {@code lit}. */
    static void setLevers(Session c, int... lit) {
        for (int i = 0; i < LIGHTS.length; i++) {
            boolean on = false;
            for (int k : lit) {
                on |= k == i;
            }
            int[] l = LIGHTS[i];
            c.server().command("setblock " + l[0] + " " + l[1] + " 142 minecraft:lever[face=wall,facing=north,powered=" + on + "]");
        }
    }

    static boolean[] litOnClient(Session c) {
        return c.onClient(mc -> {
            boolean[] out = new boolean[LIGHTS.length];
            for (int i = 0; i < LIGHTS.length; i++) {
                var st = mc.level.getBlockState(lever(LIGHTS[i]));
                out[i] = st.getBlock() instanceof LeverBlock && st.getValue(LeverBlock.POWERED);
            }
            return out;
        });
    }

    static void waitLevers(Session c, String what, int... lit) {
        c.waitUntil(what, mc -> {
            for (int i = 0; i < LIGHTS.length; i++) {
                var st = mc.level.getBlockState(lever(LIGHTS[i]));
                boolean want = false;
                for (int k : lit) {
                    want |= k == i;
                }
                if (!(st.getBlock() instanceof LeverBlock) || st.getValue(LeverBlock.POWERED) != want) {
                    return false;
                }
            }
            return true;
        }, 100);
    }

    static int flickCount() {
        return (Integer) Mod.staticCall(FEATURE, "flickCount");
    }

    /** The Lights Device's flicks since {@code count} was read ("aura x, y, z" / "trigger x, y, z"), oldest first. */
    @SuppressWarnings("unchecked")
    static List<String> flicksSince(int count) {
        List<String> all = (List<String>) Mod.staticCall(FEATURE, "recentFlicks");
        int n = flickCount() - count;
        if (n > all.size()) {
            throw new AssertionError(n + " flicks since, more than the mod keeps (" + all.size() + ")");
        }
        return new ArrayList<>(all.subList(all.size() - n, all.size()));
    }

    /** Every Lights Device switch off, and every other feature that could click a lever here off, with undo. */
    static void baseline(Session c, List<AutoCloseable> undo) {
        undo.add(c.onClient(mc -> raw(CFG, "AuraEnabled", false)));
        undo.add(c.onClient(mc -> raw(CFG, "TriggerEnabled", false)));
        undo.add(c.onClient(mc -> Mod.with(CFG, "PreFlick", true)));
        undo.add(c.onClient(mc -> Mod.with(CFG, "Finish", true)));
        undo.add(c.onClient(mc -> Mod.with(CFG, "Range", 4.5)));
        undo.add(c.onClient(mc -> Mod.with(CFG, "Cps", 10)));
        undo.add(c.onClient(mc -> Mod.with(CFG, "ChatFeedback", false)));
        undo.add(c.onClient(mc -> Mod.with(CFG, "TriggerDelayMs", 0)));
        undo.add(c.onClient(mc -> Mod.with(CFG, "TriggerCooldownMs", 0)));
        undo.add(c.onClient(mc -> Mod.with(CU, "SecretAuraEnabled", false)));
        undo.add(c.onClient(mc -> Mod.with(CU, "AuraBossLevers", false)));
    }

    /** A switch whose gated getter would read false outside Skyblock: set and restored through its Raw getter. */
    static AutoCloseable raw(String cls, String property, boolean value) {
        Object target = Mod.cfg(cls);
        Object old = Mod.call(target, "is" + property + "Raw");
        Mod.call(target, "set" + property, value);
        return () -> Mod.call(target, "set" + property, old);
    }

    /** The 846 arena, his position in front of the wall, F7 on the sidebar, a fresh P3 run (Goldor's line). */
    static void arena(Session c) {
        c.server().command("forceload add 54 134 66 144");
        c.server().sync(20);
        c.server().command("fill 54 132 134 66 132 144 minecraft:stone");
        c.server().command("fill 54 133 134 66 139 142 minecraft:air");
        c.server().command("fill 56 132 143 64 138 143 minecraft:stone");
        setLevers(c);
        c.ctx().waitTicks(10);
        TerminalAuraCases.tp(c, 60.5, 133, 139.5, 0f); // facing +z, the lever wall 2.5 blocks ahead
        c.ctx().waitTicks(20);
        c.check(c.onClient(mc -> mc.player.onGround() && Math.abs(mc.player.getY() - 133) < 0.01),
                "not standing on the arena floor: " + c.onClient(mc -> mc.player.position()));
        c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
        c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
        waitLevers(c, "six unlit levers on the client");
    }

    static void newRun(Session c) {
        int gen = (Integer) Mod.staticCall("leveraura.P3SectionTracker", "generation");
        c.hx().chat(TerminalAuraCases.GOLDOR);
        c.waitUntil("Floor7Tracker phase P3 and a new P3 run",
                mc -> "P3".equals(String.valueOf(Mod.staticCall(TerminalAuraCases.TRACKER, "getPhase")))
                        && (Integer) Mod.staticCall("leveraura.P3SectionTracker", "generation") != gen, 100);
        c.check((Integer) Mod.staticCall("leveraura.P3SectionTracker", "section") == 1, "a new run is not section 1");
    }

    /** S1's objectives done and its gate destroyed: S2 opens. */
    static void openS2(Session c) {
        c.hx().chat("Killer560 activated a terminal! (7/7)");
        c.hx().chat("The gate has been destroyed!");
        c.waitUntil("S2 open (P3SectionTracker section 2)",
                mc -> (Integer) Mod.staticCall("leveraura.P3SectionTracker", "section") == 2, 60);
    }

    static void cleanup(Session c, List<AutoCloseable> undo) {
        c.onClient(mc -> {
            BossAuraTurnCases.restore(undo);
            return null;
        });
        c.server().command("fill 54 132 134 66 139 144 minecraft:air");
        c.server().command("forceload remove 54 134 66 144");
        c.hx().sidebarClear();
        c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 0f, "pitch", 0f);
    }

    /** Ticks waited, counting the use packets the client sent; {@code until} (or the tick limit) ends it. */
    static int watchUses(Session c, int maxTicks, java.util.function.IntPredicate until) {
        int t = 0;
        for (; t < maxTicks; t++) {
            c.ctx().waitTick();
            if (until.test(PacketWatch.totalUses())) {
                break;
            }
        }
        return t;
    }

    // ---- 1476 -----------------------------------------------------------------------------------------------------

    static void aura(Session c) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        try {
            baseline(c, undo);
            arena(c);
            setLevers(c, 0, 2, 4); // three lit, three (58,136 / 60,134 / 58,133) unlit
            waitLevers(c, "three lit, three unlit", 0, 2, 4);
            newRun(c);
            c.ctx().waitTicks(5);
            int before = flickCount();
            float[] cam = BossAuraTurnCases.start(c);
            PacketWatch.start();
            c.onClient(mc -> {
                Mod.set(CFG, "setAuraEnabled", true);
                return null;
            });
            watchUses(c, 120, n -> n >= 3);
            // ...and then a while more: nothing lit may be flicked before S2.
            for (int i = 0; i < 40; i++) {
                c.ctx().waitTick();
                c.onClient(mc -> {
                    BossAuraTurnCases.track(mc, cam);
                    return null;
                });
            }
            int preUses = PacketWatch.totalUses();
            int preMax = PacketWatch.maxUsesOnOneTick();
            List<String> pre = flicksSince(before);
            boolean[] lit = litOnClient(c);
            c.note("before S2: " + preUses + " use packet(s), flicks " + pre + ", lit " + java.util.Arrays.toString(lit));
            c.check(preUses == 3, "before S2 the aura should flick the three unlit levers exactly once each: " + preUses + " uses");
            c.check(pre.size() == 3 && pre.stream().allMatch(f -> f.startsWith("aura ")), "the Lights Aura's record: " + pre);
            for (int i : new int[]{1, 3, 5}) {
                String at = LIGHTS[i][0] + ", " + LIGHTS[i][1] + ", 142";
                c.check(pre.contains("aura " + at), "the unlit lever " + at + " was not flicked by the aura: " + pre);
            }
            for (boolean b : lit) {
                c.check(b, "not every lever is lit after the pre-flick: " + java.util.Arrays.toString(lit));
            }
            c.check(preMax <= 1, "two uses went out on one tick: " + preMax);

            // S2 opens with all six lit: exactly one activation flick, then nothing.
            openS2(c);
            watchUses(c, 120, n -> n >= preUses + 1);
            c.ctx().waitTicks(40);
            int s2Uses = PacketWatch.totalUses() - preUses;
            PacketWatch.stop();
            List<String> all = flicksSince(before);
            c.note("S2 open: " + s2Uses + " use packet(s); every flick " + all);
            c.check(s2Uses == 1, "all six lit when S2 opened: exactly one activation flick expected, " + s2Uses + " uses");
            c.check(all.size() == 4 && all.get(3).startsWith("aura "), "the activation flick is not the aura's: " + all);
            c.onClient(mc -> {
                Mod.set(CFG, "setAuraEnabled", false);
                return null;
            });
            BossAuraTurnCases.finish(c, cam, "lights aura");
        } finally {
            PacketWatch.stop();
            cleanup(c, undo);
        }
    }

    // ---- 1477 -----------------------------------------------------------------------------------------------------

    static void withSecretAura(Session c) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        try {
            baseline(c, undo);
            arena(c);
            newRun(c);
            // Secret Aura fully on, its boss half too, and In Boss's section levers: none of it may touch the Lights.
            undo.add(c.onClient(mc -> Mod.with(CU, "SecretAuraEnabled", true)));
            undo.add(c.onClient(mc -> Mod.with(CU, "AuraLevers", true)));
            undo.add(c.onClient(mc -> Mod.with(CU, "AuraBossLevers", true)));
            undo.add(c.onClient(mc -> Mod.with(CU, "AuraRange", 4.5)));
            undo.add(c.onClient(mc -> Mod.with(CU, "AuraCooldownMs", 100)));
            undo.add(c.onClient(mc -> Mod.with(LA, "SectionLevers", true)));
            undo.add(c.onClient(mc -> Mod.with(LA, "SectionLeversEarly", true)));
            undo.add(c.onClient(mc -> Mod.with(CFG, "Cps", 20)));
            c.note("Secret Aura reads boss: " + c.onClient(mc -> Mod.staticCall("livemap.LiveMapFeature", "isInBoss"))
                    + ", in dungeon " + c.onClient(mc -> Mod.staticCall("secrets.DungeonState", "isInDungeon")));
            c.ctx().waitTicks(5);
            int before = flickCount();
            PacketWatch.start();
            c.onClient(mc -> {
                Mod.set(CFG, "setAuraEnabled", true);
                Mod.set(CFG, "setTriggerEnabled", true);
                return null;
            });
            watchUses(c, 200, n -> n >= LIGHTS.length);
            c.ctx().waitTicks(40);
            int uses = PacketWatch.totalUses();
            int maxTick = PacketWatch.maxUsesOnOneTick();
            PacketWatch.stop();
            c.onClient(mc -> {
                Mod.set(CFG, "setAuraEnabled", false);
                Mod.set(CFG, "setTriggerEnabled", false);
                return null;
            });
            List<String> got = flicksSince(before);
            boolean[] lit = litOnClient(c);
            @SuppressWarnings("unchecked")
            Map<Long, Object> secretAttempts = (Map<Long, Object>) Mod.field("cheatutils.SecretAuraFeature", "attempts");
            List<String> secretOnLights = new ArrayList<>();
            for (int[] l : LIGHTS) {
                if (secretAttempts.containsKey(lever(l).asLong())) {
                    secretOnLights.add(lever(l).toShortString());
                }
            }
            c.note(String.format(Locale.ROOT, "%d use packet(s), at most %d on one tick; flicks %s; lit %s; Secret Aura attempts on"
                    + " Lights levers %s", uses, maxTick, got, java.util.Arrays.toString(lit), secretOnLights));
            c.check(secretOnLights.isEmpty(), "Secret Aura attempted a Lights lever: " + secretOnLights);
            c.check(uses == LIGHTS.length, "six unlit levers want six flicks, one each: " + uses + " uses");
            c.check(maxTick <= 1, "two uses went out on one tick: " + maxTick);
            c.check(got.size() == LIGHTS.length, "the Lights Device recorded " + got.size() + " flicks: " + got);
            for (int[] l : LIGHTS) {
                String at = l[0] + ", " + l[1] + ", 142";
                long n = got.stream().filter(f -> f.endsWith(" " + at)).count();
                c.check(n == 1, at + " was flicked " + n + " times by the Lights Device: " + got);
            }
            for (boolean b : lit) {
                c.check(b, "not every lever is lit: " + java.util.Arrays.toString(lit));
            }
            c.check(got.contains("trigger " + LOOKED.toShortString()) || got.contains("aura " + LOOKED.toShortString()),
                    "the looked-at lever was not flicked: " + got);
        } finally {
            PacketWatch.stop();
            cleanup(c, undo);
        }
    }

    // ---- 1478 -----------------------------------------------------------------------------------------------------

    static void triggerbot(Session c) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        try {
            baseline(c, undo);
            undo.add(c.onClient(mc -> Mod.with(CFG, "TriggerDelayMs", 100)));
            arena(c);
            newRun(c);
            c.check(c.onClient(mc -> mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult b
                    && b.getBlockPos().equals(LOOKED)), "the crosshair is not on " + LOOKED.toShortString() + ": "
                    + c.onClient(mc -> String.valueOf(mc.hitResult == null ? null : mc.hitResult.getLocation())));
            c.ctx().waitTicks(5);
            int before = flickCount();
            PacketWatch.start();
            c.onClient(mc -> {
                Mod.set(CFG, "setTriggerEnabled", true);
                return null;
            });
            int t = watchUses(c, 100, n -> n >= 1);
            c.ctx().waitTicks(40);
            int uses = PacketWatch.totalUses();
            boolean[] lit = litOnClient(c);
            List<String> got = flicksSince(before);
            c.note("first look: use after " + (t + 1) + " tick(s); " + uses + " use(s); flicks " + got + "; lit "
                    + java.util.Arrays.toString(lit));
            c.check(uses == 1, "only the looked-at lever may be flicked: " + uses + " uses");
            c.check(got.equals(List.of("trigger " + LOOKED.toShortString())), "the Triggerbot's record: " + got);
            c.check(t + 1 >= 2, "the 100 ms delay is two ticks, the flick came after " + (t + 1));
            for (int i = 0; i < LIGHTS.length; i++) {
                c.check(lit[i] == (i == 3), "lever " + lever(LIGHTS[i]).toShortString() + " lit=" + lit[i]
                        + " - only the looked-at one may change");
            }

            // Turn onto the top-right corner lever (62,136,142) with a server tp: that one, and only it, next.
            BlockPos second = lever(LIGHTS[0]);
            c.hx().call("dungeon.tp", "x", 60.5, "y", 133.0, "z", 139.5, "yaw", -31.1f, "pitch", -25.9f);
            c.waitUntil("the crosshair on " + second.toShortString(), mc -> mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult b
                    && b.getBlockPos().equals(second), 60);
            watchUses(c, 100, n -> n >= 2);
            c.ctx().waitTicks(40);
            uses = PacketWatch.totalUses();
            PacketWatch.stop();
            lit = litOnClient(c);
            got = flicksSince(before);
            c.note("second look: " + uses + " use(s); flicks " + got + "; lit " + java.util.Arrays.toString(lit));
            c.check(uses == 2, "two looks, two flicks: " + uses + " uses");
            c.check(got.equals(List.of("trigger " + LOOKED.toShortString(), "trigger " + second.toShortString())),
                    "the Triggerbot's record: " + got);
            for (int i = 0; i < LIGHTS.length; i++) {
                c.check(lit[i] == (i == 3 || i == 0), "lever " + lever(LIGHTS[i]).toShortString() + " lit=" + lit[i]);
            }
            c.onClient(mc -> {
                Mod.set(CFG, "setTriggerEnabled", false);
                return null;
            });
        } finally {
            PacketWatch.stop();
            cleanup(c, undo);
        }
    }
}

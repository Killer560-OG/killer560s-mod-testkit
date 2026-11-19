package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static dev.testkit.gametest.SimBossTests.call;
import static dev.testkit.gametest.SimBossTests.expect;
import static dev.testkit.gametest.SimBossTests.println;
import static dev.testkit.gametest.SimBossTests.start;
import static dev.testkit.gametest.SimLeap1116.chatSince;

/**
 * simleapspots-1117 (killer560 2026-11-17, killer560s-mod-logs/simleapspots-1117/SPEC.md): in the sim's F7/M7 boss a leap
 * goes to the CLASS's spot for the phase, with his yaw and pitch.
 * <ul>
 * <li>{@code 1117-sim-leapspots}: with no Sim Teammates placed the menu still lists four heads named by class, each labeled (lore) with
 * one of the four classes he does not play. Played as Mage and then as Tank, every phase (Maxor, Storm F7, Storm M7, Goldor
 * S1-S4, Necron, M7 dragons) and every listed class: a leap lands exactly on the owner's spot and look (the server's
 * landing and his client both, after he stood somewhere else), a class with no spot says he leaped wrong and moves nothing
 * (no leap counted, no InfiniLeap cooldown); Necron's Healer spot differs for a Mage; in P5 Tank/Healer/Mage go under the
 * dragon spawning (first in spawn order when two are, the one that spawned last when none) and keep his look; Player
 * Leaps changes nothing alone; Sim Class and Co-op Leaps survive a reload.</li>
 * <li>{@code 1117-sim-dragonorder}: the P5 dragon order is drawn per fight: seeds 11 and 12 give different orders of the
 * five, seed 11 again gives its first order, and the first flame and dragon are the order's first.</li>
 * <li>{@code 1117-sim-f7end} (simleapspots2-1117, killer560 "in f7 there should be no stage after necron it should just
 * end"): an F7 fight started at Necron ends (phase DONE, "boss cleared (Floor 7)") without ever entering P5 or drawing a
 * dragon order.</li>
 * <li>simleapspots2-1117 in {@code 1117-sim-leapspots}: F7 Necron sends a leap to ANY class to {@code /tpto necron}'s spot
 * ("In necron have any leap be to the /tpto necron in f7"), and P1 leaps go from the crystal pads themselves (a right
 * click there is no longer the pad's).</li>
 * </ul>
 */
final class SimLeapSpots1117 {

    static final String NAME = "1117-sim-leapspots";
    static final String ORDER_NAME = "1117-sim-dragonorder";
    static final String F7_END = "1117-sim-f7end";
    private static final String LEAP = "com.killer560.hub.roomsim.SimLeap";
    private static final String PREFS = "com.killer560.hub.roomsim.SimMenuPrefs";
    private static final String CLASSES = "com.killer560.hub.roomsim.SimClasses";
    private static final String FIGHT = "com.killer560.hub.bosssim.BossFight";
    private static final String DC = "com.killer560.hub.dungeonclass.DungeonClass";

    /** The owner's spots: x, y, z, yaw, pitch by stage and class (SPEC.md). */
    private static final Map<String, Map<String, double[]>> SPOTS = new LinkedHashMap<>();

    private static void put(String stage, String cls, double x, double y, double z, double yaw, double pitch) {
        SPOTS.computeIfAbsent(stage, k -> new HashMap<>()).put(cls, new double[]{x, y, z, yaw, pitch});
    }

    static {
        put("P1", "TANK", 95.5, 224.0, 40.5, 51.0, -6.4);
        put("P1", "HEALER", 51.5, 224.0, 40.5, -51.6, -9.7);
        put("P2F7", "MAGE", 57.298, 169.0, 64.702, -90.7, -10.8);
        put("P2F7", "BERSERKER", 101.084, 115.0625, 50.249, -0.1, -7.2);
        put("P2F7", "HEALER", 107.899, 120.0, 94.014, -90.2, -0.6);
        put("P2F7", "ARCHER", 37.5, 170.0, 89.5, -118.5, -9.5);
        put("P2F7", "TANK", 109.291, 170.0, 94.586, 162.1, -19.6);
        put("P2M7", "HEALER", 87.5, 166.0, 89.5, -154.6, -26.7);
        put("P2M7", "ARCHER", 88.5, 169.0, 74.5, -133.8, -41.3);
        put("P2M7", "TANK", 57.299, 169.0, 64.700, -91.1, -14.7);
        put("P2M7", "BERSERKER", 100.938, 115.0625, 50.833, 0.8, -7.6);
        put("P2M7", "MAGE", 87.700, 169.0, 66.291, -100.7, -43.9);
        put("S1", "HEALER", 107.878, 120.0, 93.917, -89.8, -0.4);
        put("S1", "TANK", 108.032, 113.0, 73.075, 159.4, -12.6);
        put("S1", "ARCHER", 93.119, 112.0, 92.443, 89.8, 18.4);
        put("S1", "BERSERKER", 63.5, 127.0, 35.5, -13.1, 1.5);
        put("S1", "MAGE", 60.5, 132.0, 139.0, 90.9, 0.8);
        put("S2", "MAGE", 59.5, 120.0, 125.728, -179.4, 15.6);
        put("S2", "HEALER", 69.006, 109.0, 124.391, 167.3, 21.7);
        put("S2", "BERSERKER", 44.979, 109.0, 123.341, -110.7, 26.6);
        put("S2", "ARCHER", 39.382, 109.0, 140.399, 0.8, 36.4);
        put("S2", "TANK", 2.437, 109.0, 105.127, -180.0, 0.3);
        put("S3", "TANK", -0.108, 109.0, 112.268, 88.2, 22.6);
        put("S3", "HEALER", 1.200, 119.0, 93.472, 90.1, 17.9);
        put("S3", "BERSERKER", 18.649, 121.5, 91.300, -1.3, -10.2);
        put("S3", "ARCHER", 1.148, 109.0, 77.841, -168.5, -8.7);
        put("S3", "MAGE", 54.5, 115.0625, 50.5, 179.5, -2.6);
        put("S4", "TANK", 41.431, 109.0, 32.975, -177.6, 16.8);
        put("S4", "ARCHER", 44.105, 121.0, 31.766, -173.0, 27.9);
        put("S4", "BERSERKER", 67.224, 109.0, 32.907, -178.5, 20.5);
        put("S4", "HEALER", 72.678, 115.0, 45.725, 1.7, 25.9);
        put("S4", "MAGE", 54.367, 115.0, 58.454, 179.9, -0.6);
        put("P4", "HEALER", 54.5, 5.0, 76.5, 179.9, -2.3);
        put("P4MAGE", "HEALER", 88.472, 6.0, 95.780, -110.2, 1.7);
        // F7 Necron: every class goes to BossSpots "necron" (/tpto necron), its yaw 180 and a level look.
        // The 6th value: his client may drift up to 0.5 after landing there, Necron's body (54, 66, 76) pushes him off the spot.
        for (String c : new String[]{"MAGE", "TANK", "HEALER", "ARCHER", "BERSERKER"}) {
            SPOTS.computeIfAbsent("P4F7", k -> new HashMap<>()).put(c, new double[]{54.5, 65.0, 76.5, 180.0, 0.0, 0.5});
        }
    }

    /** Stage key -> the sim start and whether Master Mode. */
    private static final String[][] STAGES = {{"P1", "P1", "f"}, {"P2F7", "P2", "f"}, {"P2M7", "P2", "m"},
            {"S1", "S1", "m"}, {"S2", "S2", "m"}, {"S3", "S3", "m"}, {"S4", "S4", "m"}, {"P4", "P4", "m"}, {"P4F7", "P4", "f"}};

    private SimLeapSpots1117() {
    }

    private static ServerPlayer sp(Minecraft mc) {
        return mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
    }

    static void pick(ClientGameTestContext ctx, String cls) {
        ctx.runOnClient(mc -> {
            try {
                Object p = ModUnderTest.staticCall(PREFS, "load");
                p.getClass().getField("simClass").set(p, ModUnderTest.enumValue(DC, cls));
                ModUnderTest.call(p, "save", new Class<?>[]{}, new Object[]{});
                ModUnderTest.staticCall(CLASSES, "selfChanged", new Class<?>[]{boolean.class}, new Object[]{true});
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        ctx.waitTicks(2);
    }

    static void playerLeaps(ClientGameTestContext ctx, boolean on) {
        ctx.runOnClient(mc -> {
            try {
                Object p = ModUnderTest.staticCall(PREFS, "load");
                p.getClass().getField("playerLeaps").setBoolean(p, on);
                ModUnderTest.call(p, "save", new Class<?>[]{}, new Object[]{});
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
    }

    private static Object prefField(ClientGameTestContext ctx, String field) {
        return ctx.computeOnClient(mc -> {
            try {
                Object p = call(PREFS, "cached");
                return p.getClass().getField(field).get(p);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
    }

    /** Opens the leap menu (InfiniLeap in slot 0, its 2 s cooldown and the 1 s relative-look window waited out). */
    private static Map<String, Integer> openMenu(ClientGameTestContext ctx, Map<String, String> classOf, int wait) {
        if (wait > 0) ctx.waitTicks(wait);
        SimLeap1116.use(ctx);
        Map<String, Integer> slots = new HashMap<>();
        ctx.runOnClient(mc -> {
            for (int i = 0; i < 36; i++) {
                ItemStack s = mc.player.containerMenu.getSlot(i).getItem();
                if (!s.is(Items.PLAYER_HEAD)) continue;
                String name = s.getHoverName().getString();
                var lore = s.get(DataComponents.LORE);
                String cls = lore == null || lore.lines().isEmpty() ? "?" : lore.lines().get(0).getString();
                slots.put(name, i);
                classOf.put(name, cls);
            }
        });
        return slots;
    }

    private static String mateOf(Map<String, String> classOf, String cls) {
        String label = "BERSERKER".equals(cls) ? "Berserk" : cls.charAt(0) + cls.substring(1).toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : classOf.entrySet()) if (e.getValue().equals(label)) return e.getKey();
        return null;
    }

    private static int leaps(ClientGameTestContext ctx) {
        return (Integer) ctx.computeOnClient(mc -> call(LEAP, "leapsForTest"));
    }

    private static int wrongs(ClientGameTestContext ctx) {
        return (Integer) ctx.computeOnClient(mc -> call(LEAP, "wrongLeapsForTest"));
    }

    private static double[] serverPos(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            ServerPlayer p = sp(mc);
            return new double[]{p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot()};
        });
    }

    private static String fmt(double[] a) {
        return String.format(Locale.ROOT, "%.3f/%.3f/%.3f yaw %.1f pitch %.1f", a[0], a[1], a[2], a[3], a[4]);
    }

    /**
     * Leaps to the teammate playing {@code cls}. {@code want} null: a wrong leap (line, nothing moved). {@code want} with
     * NaN yaw: x/z only and the look kept (P5). Returns the server's landing or null.
     */
    private static double[] leapTo(ClientGameTestContext ctx, List<String> f, String tag, String cls, double[] want) {
        return leapTo(ctx, f, tag, cls, want, 45);
    }

    private static double[] leapTo(ClientGameTestContext ctx, List<String> f, String tag, String cls, double[] want, int wait) {
        Map<String, String> classOf = new LinkedHashMap<>();
        Map<String, Integer> slots = openMenu(ctx, classOf, wait);
        String mate = mateOf(classOf, cls);
        if (mate == null) {
            f.add(tag + "no head for a " + cls + " in the menu " + classOf);
            ctx.runOnClient(mc -> mc.player.closeContainer());
            return null;
        }
        double[] before = serverPos(ctx);
        int l0 = leaps(ctx), w0 = wrongs(ctx);
        long mark = LogTap.mark();
        int slot = slots.get(mate);
        ctx.runOnClient(mc -> mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, slot, 0,
                ContainerInput.PICKUP, mc.player));
        ctx.waitTicks(6);
        double[] after = serverPos(ctx);
        if (want == null) {
            expect(f, tag + cls + " (" + mate + ") is a wrong leap: the line", chatSince(mark, "You leaped wrong"), true);
            expect(f, tag + cls + " wrong leap counted", wrongs(ctx) - w0, 1);
            expect(f, tag + cls + " wrong leap: no leap", leaps(ctx) - l0, 0);
            double moved = Math.hypot(after[0] - before[0], after[2] - before[2]) + Math.abs(after[1] - before[1]);
            expect(f, tag + cls + " wrong leap: he did not move (" + String.format(Locale.ROOT, "%.3f", moved) + ")", moved < 0.3, true);
            return null;
        }
        double[] land = ctx.computeOnClient(mc -> (double[]) call(LEAP, "lastLandingForTest"));
        expect(f, tag + cls + " (" + mate + ") leap counted", leaps(ctx) - l0, 1);
        expect(f, tag + cls + " Hypixel's line", chatSince(mark, "You have teleported to " + mate + "!"), true);
        if (land == null) {
            f.add(tag + cls + ": no landing recorded");
            return null;
        }
        println(NAME, tag + cls + " from " + fmt(before) + " landed " + fmt(land) + ", now " + fmt(after));
        double dist = Math.hypot(land[0] - before[0], land[2] - before[2]) + Math.abs(land[1] - before[1]);
        expect(f, tag + cls + " the leap moved him (was " + String.format(Locale.ROOT, "%.2f", dist) + " away)", dist > 0.5, true);
        double off = Math.hypot(land[0] - want[0], land[2] - want[2]);
        expect(f, tag + cls + " landed at x/z " + String.format(Locale.ROOT, "%.3f,%.3f want %.3f,%.3f", land[0], land[2], want[0], want[2]),
                off < 0.002, true);
        double client = ctx.computeOnClient(mc -> Math.hypot(mc.player.getX() - want[0], mc.player.getZ() - want[2]));
        expect(f, tag + cls + " his client is there (" + String.format(Locale.ROOT, "%.3f", client) + ")", client < (want.length > 5 ? want[5] : 0.05), true);
        if (Double.isNaN(want[3])) {
            expect(f, tag + cls + " kept his look", Math.abs(Mth.wrapDegrees(land[3] - before[3])) < 0.05
                    && Math.abs(land[4] - before[4]) < 0.05, true);
            return land;
        }
        expect(f, tag + cls + " y " + land[1], Math.abs(land[1] - want[1]) < 0.002, true);
        expect(f, tag + cls + " yaw " + String.format(Locale.ROOT, "%.2f want %.1f", land[3], want[3]),
                Math.abs(Mth.wrapDegrees(land[3] - want[3])) < 0.02, true);
        expect(f, tag + cls + " pitch " + String.format(Locale.ROOT, "%.2f want %.1f", land[4], want[4]),
                Math.abs(land[4] - want[4]) < 0.02, true);
        float cy = ctx.computeOnClient(mc -> mc.player.getYRot());
        expect(f, tag + cls + " his client's yaw", Math.abs(Mth.wrapDegrees(cy - want[3])) < 0.05, true);
        return land;
    }

    private static final String[] ALL = {"MAGE", "TANK", "HEALER", "ARCHER", "BERSERKER"};

    static void run(ClientGameTestContext ctx, List<String> f) throws Exception {
        try {
            SimLeap1116.setTeammates(ctx, 0);
            playerLeaps(ctx, true);   // single player: the spots anyway
            for (String self : new String[]{"MAGE", "TANK"}) {
                pick(ctx, self);
                for (String[] st : STAGES) {
                    start(ctx, st[1], "m".equals(st[2]));
                    SimLeap1116.give(ctx, "INFINITE_SPIRIT_LEAP", 1);
                    String stage = st[0].equals("P4") && self.equals("MAGE") ? "P4MAGE" : st[0];
                    String tag = "[" + self + " " + st[0] + "] ";
                    expect(f, tag + "phase", String.valueOf(call(FIGHT, "phase")), st[1].startsWith("S") ? "P3" : st[1]);
                    if (st == STAGES[0]) {
                        Map<String, String> classOf = new LinkedHashMap<>();
                        openMenu(ctx, classOf, 45);
                        ctx.runOnClient(mc -> mc.player.closeContainer());
                        println(NAME, tag + "menu " + classOf);
                        expect(f, tag + "four heads, each named by its class (no TeammateN)", classOf.size() == 4 && classOf.entrySet().stream().allMatch(e -> e.getKey().equals(e.getValue())), true);
                        String me = "BERSERKER".equals(self) ? "Berserk" : self.charAt(0) + self.substring(1).toLowerCase(Locale.ROOT);
                        expect(f, tag + "the heads are the four classes he does not play", classOf.values().contains(me)
                                || new java.util.HashSet<>(classOf.values()).size() != 4, false);
                    }
                    for (String cls : ALL) {
                        if (cls.equals(self)) continue;
                        // P1: after the first leap he stands on a crystal pad, so the next leap's right click goes from the pad
                        // (a pad is never clicked, simleapspots2-1117). F7 Necron: back to the P4 spawn, every leap lands on one spot.
                        if (st[0].equals("P4F7")) SimBossTests.tp(ctx, 54.5, 64, 108.5, 0f, 0f);
                        leapTo(ctx, f, tag, cls, SPOTS.get(stage).get(cls));
                    }
                }
            }
            p5(ctx, f);

            // Saved: a reload keeps Sim Class and Co-op Leaps.
            ctx.runOnClient(mc -> call(PREFS, "reload"));
            expect(f, "Sim Class survives a reload", String.valueOf(prefField(ctx, "simClass")), "TANK");
            expect(f, "Co-op Leaps survives a reload", prefField(ctx, "playerLeaps"), true);
            playerLeaps(ctx, false);
            ctx.runOnClient(mc -> call(PREFS, "reload"));
            expect(f, "Co-op Leaps back to Default Leaps after a reload", prefField(ctx, "playerLeaps"), false);
        } finally {
            ctx.runOnClient(mc -> mc.player.closeContainer());
            ModUnderTest.staticCall(FIGHT, "dragonOrderForTest", new Class<?>[]{int[].class}, new Object[]{null});
            playerLeaps(ctx, false);
            pick(ctx, "MAGE");
        }
    }

    /**
     * P5 (M7), order Green, Red, ...: as Healer, Tank goes under Green while it spawns (Red's burst comes 40 ticks later, so
     * both are spawning soon after); once Green is up, Mage goes under Red (still spawning); Archer and Berserk are wrong; as
     * Mage once Red is up too (none spawning), Healer goes under Red, whose burst came last. His look is kept every time.
     */
    private static void p5(ClientGameTestContext ctx, List<String> f) {
        pick(ctx, "HEALER");
        ModUnderTest.staticCall(FIGHT, "dragonOrderForTest", new Class<?>[]{int[].class}, new Object[]{new int[]{2, 0, 1, 3, 4}});
        long mark = start(ctx, "P5", true);
        SimLeap1116.give(ctx, "INFINITE_SPIRIT_LEAP", 1);
        expect(f, "[P5] dragon order fixed for the case", String.valueOf(call(FIGHT, "dragonOrder")), "Green, Red, Orange, Blue, Purple");
        if (!SimBossTests.waitChat(ctx, mark, "[BOSS] The Wither King: You... again?", 160)) {
            f.add("[P5] the Wither King's line never came");
            return;
        }
        double[] green = {27.5, Double.NaN, 94.5, Double.NaN, Double.NaN};
        double[] red = {27.5, Double.NaN, 59.5, Double.NaN, Double.NaN};
        SimBossTests.tp(ctx, 54.5, 5, 76.5, 33f, 12f);
        leapTo(ctx, f, "[P5 Green spawning] ", "TANK", green, 0);
        if (!SimBossTests.until(ctx, "Green's dragon up", mc -> LogTap.since(mark).stream().anyMatch(l -> l.contains("Green dragon spawned")), 200)) {
            f.add("[P5] Green's dragon never spawned");
            return;
        }
        boolean redUp = LogTap.since(mark).stream().anyMatch(l -> l.contains("Red dragon spawned"));
        expect(f, "[P5] Red still spawning when Green is up", redUp, false);
        SimBossTests.tp(ctx, 54.5, 5, 76.5, -20f, 5f);
        leapTo(ctx, f, "[P5 Red spawning] ", "MAGE", red, 0);
        leapTo(ctx, f, "[P5] ", "ARCHER", null);
        leapTo(ctx, f, "[P5] ", "BERSERKER", null);
        if (!SimBossTests.until(ctx, "Red's dragon up", mc -> LogTap.since(mark).stream().anyMatch(l -> l.contains("Red dragon spawned")), 200)) {
            f.add("[P5] Red's dragon never spawned");
            return;
        }
        pick(ctx, "MAGE");
        SimBossTests.tp(ctx, 54.5, 5, 76.5, 70f, -8f);
        leapTo(ctx, f, "[P5 none spawning] ", "HEALER", red);
        pick(ctx, "TANK");
    }

    /** The dragon order of a fight started at P5 with this seed (null: no seed). */
    private static String orderFor(ClientGameTestContext ctx, Long seed) {
        if (seed != null) ModUnderTest.staticCall(FIGHT, "seedForTest", new Class<?>[]{long.class}, new Object[]{seed});
        start(ctx, "P5", true);
        return String.valueOf(call(FIGHT, "dragonOrder"));
    }

    /** F7 started at Necron: the fight ends after him, never P5, no dragon order drawn. */
    static void f7End(ClientGameTestContext ctx, List<String> f) {
        long mark = start(ctx, "P4", false);
        expect(f, "F7 (not Master Mode)", call(FIGHT, "master"), false);
        java.util.concurrent.atomic.AtomicBoolean p5 = new java.util.concurrent.atomic.AtomicBoolean();
        boolean done = SimBossTests.until(ctx, "the F7 fight ends after Necron", mc -> {
            String ph = String.valueOf(call(FIGHT, "phase"));
            if (ph.equals("P5")) p5.set(true);
            return ph.equals("DONE");
        }, 700);
        List<String> log = LogTap.since(mark);
        println(F7_END, "done " + done + ", P5 seen " + p5.get() + ", " + call(FIGHT, "describe"));
        expect(f, "Necron died", log.stream().anyMatch(l -> l.contains("[BossSim] Necron down at P4")), true);
        expect(f, "the fight ended (DONE)", done, true);
        expect(f, "never P5 on F7", p5.get(), false);
        expect(f, "cleared as Floor 7", log.stream().anyMatch(l -> l.contains("[BossSim] boss cleared (Floor 7)")), true);
        expect(f, "no dragon order drawn on F7", log.stream().anyMatch(l -> l.contains("P5 dragon order")), false);
        ctx.waitTicks(40);
        expect(f, "still DONE 2 s later", String.valueOf(call(FIGHT, "phase")), "DONE");
    }

    static void order(ClientGameTestContext ctx, List<String> f) {
        ModUnderTest.staticCall(FIGHT, "dragonOrderForTest", new Class<?>[]{int[].class}, new Object[]{null});
        String a = orderFor(ctx, 11L);
        String b = orderFor(ctx, 12L);
        String a2 = orderFor(ctx, 11L);
        println(ORDER_NAME, "seed 11: " + a + "; seed 12: " + b + "; seed 11 again: " + a2);
        for (String o : new String[]{a, b}) {
            List<String> parts = new java.util.ArrayList<>(List.of(o.split(", ")));
            java.util.Collections.sort(parts);
            expect(f, "a permutation of the five (" + o + ")", parts.toString(), "[Blue, Green, Orange, Purple, Red]");
        }
        expect(f, "seeds 11 and 12 give different orders", a.equals(b), false);
        expect(f, "seed 11 again gives the same order", a2, a);
        // The first flame and dragon are the order's first.
        String first = a2.split(", ")[0];
        long mark = LogTap.mark();
        boolean up = SimBossTests.until(ctx, "the first dragon", mc -> LogTap.since(mark).stream().anyMatch(l -> l.contains("dragon spawned at P5")), 260);
        String line = LogTap.since(mark).stream().filter(l -> l.contains("dragon spawned at P5")).findFirst().orElse("");
        println(ORDER_NAME, "first spawn: " + line);
        expect(f, "a dragon spawned", up, true);
        expect(f, "the first dragon is the order's first (" + first + ")", line.contains("] " + first + " dragon spawned"), true);
    }
}

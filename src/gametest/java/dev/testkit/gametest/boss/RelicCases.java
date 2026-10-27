package dev.testkit.gametest.boss;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 1025-boss-relic-*: the mod's Relic Triggerbot and Relic Look ({@code witherdragons.RelicHelpersFeature}, cheat build,
 * mod branch relic-1025) against {@code boss.relic.*} (hx/boss/HxRelic: the five cauldrons at the real M7 P5 positions,
 * relic armor stands, and a server that gives the relic on a stand interact within 3 blocks and takes it on its own
 * cauldron). M7 sidebar, the player at y 7 in the boss's x/z, so the mod's P5 test holds. Every pickup and place is
 * proven by the server's own {@code relic.pickup} / {@code relic.place} events, and the session fails the case on any
 * GrimAC line.
 * <ol>
 *   <li>{@code 1025-boss-relic-trigger}: Relic Triggerbot alone. Facing away from the relic for 30 ticks: no click. Turned
 *   (by a server teleport) to face the relic: picked up. Turned to the Red cauldron: placed, on the right cauldron.</li>
 *   <li>{@code 1025-boss-relic-look}: Relic Look and Triggerbot together, starting faced away: the look turns him to the
 *   relic, the triggerbot picks it up, the look turns him to the Red cauldron, the triggerbot places it - no harness
 *   rotation at all after the start.</li>
 *   <li>{@code 1025-boss-relic-legit} (legit jar): with both settings written on, nothing is ever clicked.</li>
 * </ol>
 */
final class RelicCases {

    static final String CONFIG = "witherdragons.RelicHelpersConfig";
    static final String FEATURE = "witherdragons.RelicHelpersFeature";

    /** Where he stands: 3.9 blocks (eye to box) from the Red cauldron (51, 7, 42). */
    static final double PX = 53.5;
    static final double PY = 7.0;
    static final double PZ = 46.5;
    /** The relic: 1.9 blocks from him, on no cauldron's x/z. */
    static final double RX = 54.5;
    static final double RY = 7.0;
    static final double RZ = 48.5;

    private RelicCases() {
    }

    static void register(Session s) {
        if (Mod.isCheat()) {
            s.test("1025-boss-relic-trigger", c -> run(c, false));
            s.test("1025-boss-relic-look", c -> run(c, true));
        } else {
            s.test("1025-boss-relic-legit", RelicCases::legit);
        }
    }

    static void arena(Session c, List<AutoCloseable> undo) {
        c.hx().call("boss.relic.build");
        c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(M7)");
        c.waitUntil("DungeonState M7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
        undo.add(c.onClient(mc -> Mod.with(CONFIG, "TriggerEnabled", false)));
        undo.add(c.onClient(mc -> Mod.with(CONFIG, "LookEnabled", false)));
        undo.add(c.onClient(mc -> Mod.with(CONFIG, "CooldownMs", 200)));
        undo.add(c.onClient(mc -> Mod.with(CONFIG, "SwapToRelic", true)));
    }

    static int spawnRelic(Session c) {
        JsonObject st = c.hx().call("boss.relic.spawn", "color", "red", "x", RX, "y", RY, "z", RZ).getAsJsonObject();
        int id = st.get("standId").getAsInt();
        c.waitUntil("the client to have the relic stand (" + st + ")", mc -> mc.level.getEntity(id) != null, 200);
        return id;
    }

    static void run(Session c, boolean look) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        try {
            arena(c, undo);
            // Facing west, away from both the relic (south-east) and the cauldron (north-west... behind his left).
            tp(c, PX, PY, PZ, 90f, 0f);
            c.ctx().waitTicks(20);
            spawnRelic(c);
            c.waitUntil("the mod's P5 test", mc -> Boolean.TRUE.equals(Mod.staticCall(FEATURE, "inRelicPhase", mc)), 100);
            int clicksBefore = c.onClient(mc -> (Integer) Mod.staticCall(FEATURE, "clicksSent"));
            int looksBefore = c.onClient(mc -> (Integer) Mod.staticCall(FEATURE, "looksStarted"));
            int pickBefore = c.events("relic.pickup").size();
            int placeBefore = c.events("relic.place").size();
            c.onClient(mc -> {
                Mod.set(CONFIG, "setTriggerEnabled", true);
                if (look) {
                    Mod.set(CONFIG, "setLookEnabled", true);
                }
                return null;
            });
            if (!look) {
                c.ctx().waitTicks(30);
                c.check(c.events("relic.pickup").size() == pickBefore, "the triggerbot clicked a relic the crosshair was not on: "
                        + c.events("relic.pickup"));
                c.check(c.onClient(mc -> (Integer) Mod.staticCall(FEATURE, "clicksSent")) == clicksBefore,
                        "the triggerbot sent a click while facing away");
                float[] at = lookAt(c, new Vec3(RX, RY + 1.0, RZ));
                tp(c, PX, PY, PZ, at[0], at[1]);
            }
            try {
                c.waitUntil("the relic picked up by the server", mc -> c.events("relic.pickup").size() > pickBefore, 120);
            } catch (AssertionError e) {
                c.note("relic pickup diag: " + diag(c));
                throw e;
            }
            JsonObject pick = last(c, "relic.pickup");
            c.note("pickup: " + pick);
            c.check(pick.get("taken").getAsBoolean(), "the server did not give the relic: " + pick);
            c.waitUntil("the relic in the client's inventory", mc -> Mod.staticCall(FEATURE, "heldRelic", mc.player) != null, 40);
            if (!look) {
                float[] at = lookAt(c, new Vec3(51.5, 7.5, 42.5));
                tp(c, PX, PY, PZ, at[0], at[1]);
            }
            try {
                c.waitUntil("the relic placed by the server", mc -> c.events("relic.place").size() > placeBefore, 160);
            } catch (AssertionError e) {
                // preexisting-1026's final boss run: the trigger-only place never went out once (no "placed" line), with
                // nothing in the log to say which gate held it. Name them before failing.
                c.note("relic place diag: " + diag(c));
                throw e;
            }
            JsonObject place = last(c, "relic.place");
            c.note("place: " + place);
            c.check(place.get("placed").getAsBoolean() && "Red".equals(place.get("cauldron").getAsString()),
                    "not placed on its own cauldron: " + place);
            c.check(c.events("relic.place").size() == placeBefore + 1, "more than one cauldron click: " + c.events("relic.place"));
            c.check(c.events("relic.pickup").size() == pickBefore + 1, "more than one relic click: " + c.events("relic.pickup"));
            int clicks = c.onClient(mc -> (Integer) Mod.staticCall(FEATURE, "clicksSent")) - clicksBefore;
            c.check(clicks == 2, "the triggerbot sent " + clicks + " clicks, expected exactly 2");
            if (look) {
                int looks = c.onClient(mc -> (Integer) Mod.staticCall(FEATURE, "looksStarted")) - looksBefore;
                c.note("look approaches: " + looks);
                c.check(looks >= 2, "Relic Look began " + looks + " approach(es), expected the relic and then the cauldron");
                float pitch = c.onClient(mc -> mc.player.getXRot());
                c.check(pitch >= -90f && pitch <= 90f, "pitch out of range: " + pitch);
            }
        } finally {
            c.onClient(mc -> {
                Mod.set(CONFIG, "setTriggerEnabled", false);
                Mod.set(CONFIG, "setLookEnabled", false);
                for (int i = undo.size() - 1; i >= 0; i--) {
                    try {
                        undo.get(i).close();
                    } catch (Exception e) {
                        System.out.println("[relic] restore failed: " + e);
                    }
                }
                return null;
            });
            c.ctx().waitTicks(5);
            c.hx().call("boss.relic.clear");
            c.hx().sidebarClear();
            c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 0f, "pitch", 0f);
        }
    }

    static void legit(Session c) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        try {
            arena(c, undo);
            float[] at = lookAt(c, new Vec3(RX, RY + 1.0, RZ));
            tp(c, PX, PY, PZ, at[0], at[1]);
            c.ctx().waitTicks(20);
            spawnRelic(c);
            int pickBefore = c.events("relic.pickup").size();
            c.onClient(mc -> {
                Mod.set(CONFIG, "setTriggerEnabled", true);
                Mod.set(CONFIG, "setLookEnabled", true);
                return null;
            });
            c.ctx().waitTicks(40);
            c.check(c.events("relic.pickup").size() == pickBefore, "a legit jar picked up a relic by itself");
        } finally {
            c.onClient(mc -> {
                Mod.set(CONFIG, "setTriggerEnabled", false);
                Mod.set(CONFIG, "setLookEnabled", false);
                return null;
            });
            c.hx().call("boss.relic.clear");
            c.hx().sidebarClear();
            c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 0f, "pitch", 0f);
        }
    }

    static JsonObject last(Session c, String type) {
        List<JsonObject> ev = c.events(type);
        return ev.get(ev.size() - 1);
    }

    /** Yaw and pitch from his eye (standing at PX/PY/PZ) to {@code p}. */
    /** Every gate of the Relic Triggerbot, for a click that never went out (preexisting-1026: once each for pickup and place). */
    private static String diag(Session c) {
        return c.onClient(mc -> "inRelicPhase " + Mod.staticCall(FEATURE, "inRelicPhase", mc)
                        + ", held " + Mod.staticCall(FEATURE, "heldRelic", mc.player) + ", selected slot "
                        + mc.player.getInventory().getSelectedSlot() + ", hit " + (mc.hitResult instanceof
                        net.minecraft.world.phys.BlockHitResult bh ? "block " + bh.getBlockPos().toShortString() : mc.hitResult == null
                        ? "null" : mc.hitResult.getType().toString()) + ", heard " + java.util.Arrays.toString((float[])
                        Mod.staticCall("util.RightClick", "heardLook")) + ", look " + mc.player.getYRot() + "/"
                        + mc.player.getXRot() + ", eye " + mc.player.getEyePosition() + ", lastClick "
                        + Mod.field(FEATURE, "lastClickTick") + ", swapped " + Mod.field(FEATURE, "swappedTick") + ", now "
                        + Mod.staticCall("util.ActionGate", "currentTick") + ", screen " + dev.testkit.compat.McCompat.screen(mc));
    }

    static float[] lookAt(Session c, Vec3 p) {
        Vec3 eye = new Vec3(PX, PY + 1.62, PZ);
        Vec3 d = p.subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(d.z, d.x)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        return new float[]{yaw, pitch};
    }

    static void tp(Session c, double x, double y, double z, float yaw, float pitch) {
        c.hx().call("dungeon.tp", "x", x, "y", y, "z", z, "yaw", yaw, "pitch", pitch);
        c.waitUntil("the client at " + x + "," + y + "," + z, mc -> mc.player != null
                && mc.player.position().distanceTo(new Vec3(x, y, z)) < 0.05
                && Math.abs(net.minecraft.util.Mth.wrapDegrees(mc.player.getYRot() - yaw)) < 0.5f, 200);
        c.ctx().waitTicks(3);
    }
}

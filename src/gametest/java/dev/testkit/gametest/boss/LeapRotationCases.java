package dev.testkit.gametest.boss;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.testkit.compat.McPackets;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.WireCapture;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Leap Takes Target's Look (mod leaprot-1104, fastleap/LeapRotation; killer560, 2026-11-04). His 26.3 log: a leap's
 * teleport normally carries the leaped-to player's look (absolute), but a teleport within about 1 s of the one before
 * comes with a RELATIVE look 0/0, so a leap then keeps his own look. With the setting ON the mod turns him to the
 * teammate's look when such a teleport and Hypixel's "You have teleported to Name!" line meet. Here the teammate is a
 * fake player {@link #MATE} at the destination looking {@link #MATE_YAW} / {@link #MATE_PITCH}; he looks 170 / 20.
 * <ul>
 *   <li>1104-leaplook-relative-on: ON, relative-look teleport + the line: he turns to the mate's look, the mod counts one,
 *       and six ticks later the SERVER has that look (the next movement packet carried it); GrimAC (the session's flag
 *       check) says nothing.</li>
 *   <li>1104-leaplook-relative-off: OFF, the same: his own look stays.</li>
 *   <li>1104-leaplook-no-line: ON, the relative-look teleport with no leap line (an etherwarp, a setback): his look stays.</li>
 *   <li>1104-leaplook-absolute: ON, an absolute teleport (Hypixel's usual leap, look 0 / 0) + the line: vanilla's look,
 *       nothing counted.</li>
 * </ul>
 */
final class LeapRotationCases {

    static final String CONFIG = "fastleap.FastLeapConfig";
    /** "SicoKaleb" names are what HxPrimitives.player skips when it picks the real client. */
    static final String MATE = "SicoKalebLeap";
    static final int AX = 70;
    static final int BX = 82;
    static final int Y = 180;
    static final int Z = 90;
    static final float YAW = 170f;
    static final float PITCH = 20f;
    static final float MATE_YAW = 230f;
    static final float MATE_PITCH = -30f;

    private LeapRotationCases() {
    }

    static void register(Session s) {
        s.test("1104-leaplook-relative-on", c -> run(c, true, true, true));
        s.test("1104-leaplook-relative-off", c -> run(c, false, true, true));
        s.test("1104-leaplook-no-line", c -> run(c, true, true, false));
        s.test("1104-leaplook-absolute", c -> run(c, true, false, true));
        for (Act a : Act.values()) {
            s.test("1105-leaplook-same-" + a.id, c -> sameTick(c, a));
        }
    }

    /**
     * leapsame-1105 (killer560, 2026-11-05: "if I leap in the exact same tick that rotation packet is server side ... is it
     * going to flag"): what he (or Fast Leap) does in the very client tick the turn goes out. The turn tick is made exact:
     * after the relative-look teleport landed, one client task delivers the leap line (LeapManager.onChat, as the chat
     * packet does) and starts the action, so the pending turn and the action meet on the next tick. The wire (encoder
     * level, split at client_tick_end) must show the action in the same client tick as the first movement packet with the
     * mate's look; the session's GrimAC check judges it.
     */
    enum Act {
        USE_HAND("use-hand"), USE_FASTLEAP("use-fastleap"), CLICK_HAND("click-hand"), CLICK_FASTLEAP("click-fastleap"),
        ATTACK_HAND("attack-hand"), ACCEPT("accept"),
        /** A use sent between ticks (a frame, a packet handler) at the old look just before the turn tick: the turn must
         *  wait one tick, or that tick's movement packet reports another look than the use's (BadPacketsJ). */
        USE_EARLY("use-early");

        final String id;

        Act(String id) {
            this.id = id;
        }
    }

    private static final String LM = "fastleap.LeapManager";
    private static final String LINE = "You have teleported to " + MATE + "!";

    private static void sameTick(Session c, Act act) throws Exception {
        // ATTACK_HAND: the mate looks straight at +x (where he stands, a block from the landing), so the turned
        // crosshair lands on him and the click is a real attack, not a swing at air.
        float mYaw = act == Act.ATTACK_HAND ? 270f : MATE_YAW;
        float mPitch = act == Act.ATTACK_HAND ? 15f : MATE_PITCH;
        boolean menu = act == Act.CLICK_HAND || act == Act.CLICK_FASTLEAP;
        DungeonFixBossCases.floor(c, AX, Y, Z);
        DungeonFixBossCases.floor(c, BX, Y, Z);
        TerminalAuraCases.tp(c, AX + 0.5, Y, Z + 0.5, 0f);
        c.server().command(String.format(Locale.ROOT, "testkit spawn %s %.1f %d %.1f", MATE, BX + 1.5, Y, Z + 0.5));
        c.server().sync(20);
        c.hx().call("player.look", "player", MATE, "yaw", mYaw, "pitch", mPitch);
        c.hx().call("sidebar.set", dev.testkit.gametest.Fixtures.byId("core.sidebar-catacombs-f7").payload().getAsJsonObject().deepCopy());
        c.hx().give(0, dev.testkit.gametest.Fixtures.byId("items.spirit-leap").payload().getAsJsonObject().get("stack").getAsString());
        c.hx().call("menu.onUse", "id", "SPIRIT_LEAP", "menu", null); // a use opens nothing
        WireCapture.describe = LeapRotationCases::describe;
        try (AutoCloseable cfg = c.onClient(mc -> Mod.with(CONFIG, "LeapTakesTargetLook", true))) {
            c.waitUntil("F7 and the leap in slot 0", mc -> "F7".equals(Mod.staticCall("secrets.DungeonState", "getFloor"))
                    && "SPIRIT_LEAP".equals(Mod.staticCall("cheatutils.CheatUtils", "skyblockId", mc.player.getInventory().getItem(0))), 100);
            c.waitUntil("the client sees " + MATE + " at " + mYaw + "/" + mPitch, mc -> mc.level != null
                    && mc.level.players().stream().anyMatch(p -> p.getName().getString().equals(MATE)
                    && Math.abs(Mth.wrapDegrees(p.getYHeadRot() - mYaw)) < 1f && Math.abs(p.getXRot() - mPitch) < 1f), 100);
            c.onClient(mc -> {
                mc.player.getInventory().setSelectedSlot(act == Act.ATTACK_HAND ? 1 : 0); // an empty hand attacks
                mc.player.setYRot(YAW);
                mc.player.setXRot(PITCH);
                Mod.staticCall(LM, "setCooldownTicksForTest", 0.0);
                return null;
            });
            c.waitUntil("he stands at A", mc -> mc.player.onGround(), 100);
            c.ctx().waitTicks(30);
            long before = taken(c);
            WireCapture.start();
            if (act == Act.ACCEPT) {
                // the line first, then the teleport: the pair is made as the teleport is applied (its accept just sent),
                // so the turn goes out in the tick whose packets begin with that accept
                c.ctx().runOnClient(mc -> Mod.staticCall(LM, "onChat", LINE));
                c.hx().call("dungeon.tp", "x", BX + 0.5, "y", (double) Y, "z", Z + 0.5, "yaw", 0f, "pitch", 0f, "relRot", true);
            } else {
                c.hx().call("dungeon.tp", "x", BX + 0.5, "y", (double) Y, "z", Z + 0.5, "yaw", 0f, "pitch", 0f, "relRot", true);
                c.waitUntil("the client at the leap target", mc -> mc.player != null
                        && mc.player.position().distanceTo(new Vec3(BX + 0.5, Y, Z + 0.5)) < 0.05, 100);
                if (menu) {
                    JsonObject spec = dev.testkit.gametest.Fixtures.byId("menus.spirit-leap").payload().getAsJsonObject().deepCopy();
                    spec.getAsJsonObject("slots").getAsJsonObject("13").addProperty("name", "§a" + MATE);
                    c.hx().call("menu.show", spec);
                    c.waitUntil("the Spirit Leap menu", mc -> mc.player.containerMenu != mc.player.inventoryMenu, 60);
                }
                if (act == Act.USE_FASTLEAP || act == Act.CLICK_FASTLEAP) {
                    // past ActionGate's 6-tick teleport settle (and the menu's 3-tick screen settle), inside the 1.5 s
                    // pairing window: Fast Leap then acts on the very tick it is asked to, the tick the turn goes
                    c.ctx().waitTicks(10);
                }
                c.ctx().runOnClient(mc -> {
                    Mod.staticCall(LM, "onChat", LINE); // the pair: the turn is pending for the next tick
                    Mod.staticCall(LM, "setCooldownTicksForTest", 0.0); // the line set the leap cooldown; Fast Leap may go
                    switch (act) {
                        case USE_HAND -> KeyMapping.click((InputConstants.Key) Mod.field(mc.options.keyUse, "key"));
                        case ATTACK_HAND -> KeyMapping.click((InputConstants.Key) Mod.field(mc.options.keyAttack, "key"));
                        case USE_EARLY -> mc.gameMode.useItem(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND);
                        case CLICK_HAND -> mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, 13, 0,
                                ContainerInput.PICKUP, mc.player);
                        default -> Mod.staticCall(LM, "leap", MATE, true, false, false); // Fast Leap: use, or click the head
                    }
                });
            }
            c.ctx().waitTicks(12);
            WireCapture.stop();
            boolean turns = cheat();
            long after = taken(c);
            List<List<String>> ticks = ticks();
            int turnTick = -1;
            for (int i = 0; i < ticks.size() && turnTick < 0; i++) {
                for (String p : ticks.get(i)) {
                    float[] l = look(p);
                    if (p.startsWith("move") && l != null && near(l, mYaw, mPitch)) {
                        turnTick = i;
                        break;
                    }
                }
            }
            String marker = switch (act) {
                case USE_HAND, USE_FASTLEAP, USE_EARLY -> "use ";
                case CLICK_HAND, CLICK_FASTLEAP -> "ServerboundContainerClickPacket";
                case ATTACK_HAND -> "ServerboundAttackPacket"; // 26.3; 26.2 sends ServerboundInteractPacket
                case ACCEPT -> "ServerboundAcceptTeleportationPacket";
            };
            StringBuilder wire = new StringBuilder();
            for (int i = 0; i < ticks.size(); i++) {
                wire.append(i == turnTick ? "\n  TURN TICK " : "\n  tick ").append(i).append(": ").append(String.join(", ", ticks.get(i)));
            }
            c.note(act.id + ": taken " + before + " -> " + after + ", wire by client tick:" + wire);
            int actTick = -1;
            for (int i = 0; i < ticks.size() && actTick < 0; i++) {
                for (String p : ticks.get(i)) {
                    if (p.startsWith(marker) || act == Act.ATTACK_HAND && p.equals("ServerboundInteractPacket")) {
                        actTick = i;
                        break;
                    }
                }
            }
            if (!turns) { // a legit jar has the switch but never turns
                c.check(after == before && turnTick < 0, "a legit jar turned him (taken " + before + " -> " + after + ")");
                return;
            }
            c.check(after - before == 1, "taken count went " + before + " -> " + after);
            c.check(turnTick >= 0, "no movement packet carried the mate's look " + mYaw + "/" + mPitch);
            if (act == Act.USE_EARLY) { // the use leaves at the old look, the turn one tick later
                c.check(actTick >= 0 && turnTick == actTick + 1, act.id + ": the use went in client tick " + actTick
                        + ", the turn in " + turnTick + ", want the next one");
                return;
            }
            c.check(actTick == turnTick, act.id + ": the action went in client tick " + actTick + ", the turn in " + turnTick);
            if (marker.equals("use ")) { // what BadPacketsJ compares: the use's look with that tick's movement look
                for (String p : ticks.get(turnTick)) {
                    if (p.startsWith("use ")) {
                        c.check(near(look(p), mYaw, mPitch), "the use in the turn tick carried " + p + ", the move "
                                + mYaw + "/" + mPitch);
                    }
                }
            }
            JsonObject st = c.hx().call("player.state").getAsJsonObject();
            c.note(String.format(Locale.ROOT, "server look %.1f/%.1f", st.get("yaw").getAsFloat(), st.get("pitch").getAsFloat()));
        } finally {
            WireCapture.stop();
            WireCapture.describe = null;
            c.ctx().runOnClient(mc -> {
                if (mc.player.containerMenu != mc.player.inventoryMenu) {
                    mc.player.closeContainer();
                }
                Mod.staticCall(LM, "setCooldownTicksForTest", 0.0);
            });
            c.hx().call("menu.onUse", "id", "SPIRIT_LEAP", "menu", null);
            c.hx().call("give", "slot", 0, "stack", "minecraft:air");
            c.hx().sidebarClear();
            c.server().command("testkit remove " + MATE);
            c.ctx().waitTicks(50); // past the pairing window and Fast Leap's menu wait before the next case
        }
    }

    private static String describe(Object p) {
        if (p instanceof ServerboundUseItemPacket u) {
            return String.format(Locale.ROOT, "use %.1f/%.1f", McPackets.useItemYRot(u), McPackets.useItemXRot(u));
        }
        if (p instanceof ServerboundMovePlayerPacket m) {
            return m.hasRotation() ? String.format(Locale.ROOT, "move%s %.1f/%.1f", m.hasPosition() ? "+pos" : "",
                    m.getYRot(0f), m.getXRot(0f)) : "move pos";
        }
        return p.getClass().getSimpleName();
    }

    /** The captured play packets, one list per client tick (client_tick_end closes a tick). */
    private static List<List<String>> ticks() {
        List<List<String>> out = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        for (WireCapture.Sent s : WireCapture.snapshot()) {
            if (!s.phase().equals("play")) {
                continue;
            }
            if (s.text().equals("ServerboundClientTickEndPacket")) {
                out.add(cur);
                cur = new ArrayList<>();
            } else if (!s.text().equals("ServerboundKeepAlivePacket") && !s.text().equals("ServerboundPongPacket")) {
                cur.add(s.text());
            }
        }
        if (!cur.isEmpty()) {
            out.add(cur);
        }
        return out;
    }

    private static float[] look(String p) {
        int sp = p.lastIndexOf(' ');
        int sl = p.lastIndexOf('/');
        if (sp < 0 || sl < sp) {
            return null;
        }
        return new float[] {Float.parseFloat(p.substring(sp + 1, sl)), Float.parseFloat(p.substring(sl + 1))};
    }

    private static boolean near(float[] l, float yaw, float pitch) {
        return l != null && Math.abs(Mth.wrapDegrees(l[0] - yaw)) < 1.5f && Math.abs(l[1] - pitch) < 1.5f;
    }

    private static void run(Session c, boolean on, boolean relative, boolean line) throws Exception {
        DungeonFixBossCases.floor(c, AX, Y, Z);
        DungeonFixBossCases.floor(c, BX, Y, Z);
        TerminalAuraCases.tp(c, AX + 0.5, Y, Z + 0.5, 0f);
        c.server().command(String.format(Locale.ROOT, "testkit spawn %s %.1f %d %.1f", MATE, BX + 1.5, Y, Z + 0.5));
        c.server().sync(20);
        c.hx().call("player.look", "player", MATE, "yaw", MATE_YAW, "pitch", MATE_PITCH);
        try (AutoCloseable cfg = c.onClient(mc -> Mod.with(CONFIG, "LeapTakesTargetLook", on))) {
            c.waitUntil("the client sees " + MATE + " at " + MATE_YAW + "/" + MATE_PITCH, mc -> mc.level != null
                    && mc.level.players().stream().anyMatch(p -> p.getName().getString().equals(MATE)
                    && Math.abs(Mth.wrapDegrees(p.getYHeadRot() - MATE_YAW)) < 1f && Math.abs(p.getXRot() - MATE_PITCH) < 1f), 100);
            c.onClient(mc -> {
                mc.player.setYRot(YAW);
                mc.player.setXRot(PITCH);
                return null;
            });
            // Settle first: the session's first case met a run of server setbacks (a teleport every 2 ticks, GrimAC default)
            // while he stood at A, and a look sent while one is unanswered is dropped (gate2 c263 default, 2026-11-04).
            c.waitUntil("he stands at A", mc -> mc.player.onGround(), 100);
            c.ctx().waitTicks(30);
            long before = taken(c);
            if (relative) {
                c.hx().call("dungeon.tp", "x", BX + 0.5, "y", (double) Y, "z", Z + 0.5, "yaw", 0f, "pitch", 0f, "relRot", true);
            } else {
                c.hx().call("dungeon.tp", "x", BX + 0.5, "y", (double) Y, "z", Z + 0.5, "yaw", 0f, "pitch", 0f);
            }
            if (line) {
                c.hx().chat("You have teleported to " + MATE + "!");
            }
            c.waitUntil("the client at the leap target", mc -> mc.player != null
                    && mc.player.position().distanceTo(new Vec3(BX + 0.5, Y, Z + 0.5)) < 0.05, 100);
            boolean turns = cheat() && on && relative && line; // a legit jar has the switch but never turns
            float wantYaw = turns ? MATE_YAW : relative ? YAW : 0f;
            float wantPitch = turns ? MATE_PITCH : relative ? PITCH : 0f;
            c.ctx().waitTicks(10);
            // a remote player's look reaches the client in 1/256-turn steps (1.4 degrees)
            float tol = turns ? 1.5f : 0.5f;
            float[] look = c.onClient(mc -> new float[] {mc.player.getYRot(), mc.player.getXRot()});
            long after = taken(c);
            float serverYaw = Float.NaN;
            float serverPitch = Float.NaN;
            for (int i = 0; i < 8; i++) { // the next movement packet carries the look; up to 40 ticks for it
                c.ctx().waitTicks(5);
                JsonObject st = c.hx().call("player.state").getAsJsonObject();
                serverYaw = st.get("yaw").getAsFloat();
                serverPitch = st.get("pitch").getAsFloat();
                if (Math.abs(Mth.wrapDegrees(serverYaw - wantYaw)) < 1.5f && Math.abs(serverPitch - wantPitch) < 1.5f) {
                    break;
                }
            }
            c.note(String.format(Locale.ROOT, "setting %s, %s look, leap line %s: camera %.1f/%.1f (want %.0f/%.0f), taken "
                    + "%d -> %d, server look %.1f/%.1f", on ? "ON" : "OFF", relative ? "relative" : "absolute", line,
                    look[0], look[1], wantYaw, wantPitch, before, after, serverYaw, serverPitch));
            c.check(Math.abs(Mth.wrapDegrees(look[0] - wantYaw)) < tol && Math.abs(look[1] - wantPitch) < tol,
                    "camera " + look[0] + "/" + look[1] + ", want " + wantYaw + "/" + wantPitch);
            if (turns) {
                c.check(Math.abs(look[0] - YAW) <= 180.5f, "the turn took the short way: yaw " + look[0] + " from " + YAW);
            }
            c.check(after - before == (turns ? 1 : 0), "taken count went " + before + " -> " + after);
            c.check(Math.abs(Mth.wrapDegrees(serverYaw - wantYaw)) < tol && Math.abs(serverPitch - wantPitch) < tol,
                    "the server has his look at " + serverYaw + "/" + serverPitch + ", want " + wantYaw + "/" + wantPitch);
        } finally {
            c.server().command("testkit remove " + MATE);
            c.ctx().waitTicks(30); // past the 1.5 s pairing window before the next case
        }
    }

    private static boolean cheat() {
        try {
            return Class.forName("com.killer560.hub.BuildVariant").getField("CHEAT_FEATURES_ENABLED").getBoolean(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static long taken(Session c) {
        return c.onClient(mc -> ((Number) Mod.staticCall("fastleap.LeapRotation", "taken")).longValue());
    }
}

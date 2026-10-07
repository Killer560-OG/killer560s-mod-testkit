package dev.testkit.gametest.boss;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.PacketTrace;
import dev.testkit.harness.PacketWatch;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 409: the mod's Terminal Open Logger ({@code termlog/TerminalOpenLogger}, mod branch term-logger, 2026-10-07) against
 * {@code boss.term.*} (hx/boss/HxTerminalStand): a non-marker "Inactive Terminal" stand on a pillar topped by a
 * command block, which opens "Click in order!" only when the clicking eye is at or above the stand's feet, else says
 * {@code HxTerminalStand.BELOW_LINE} (or nothing, with line off).
 *
 * <p>Every click is a REAL right click ({@code pressKey(keyUse)}) with the crosshair checked on the stand first, and
 * the server's own {@code term.interact} record proves each one arrived. Judged:
 * <ol>
 *   <li>outside the boss (no F7 sidebar) the logger is not armed and a click on the stand records nothing;</li>
 *   <li>with the F7 sidebar and the arena in the boss's x/z, it arms by itself (setting at its default);</li>
 *   <li>above / level / below / below-silent give one record each, with the geometry's sign (below is negative), the
 *       command block as the terminal block, OPEN + title + a delay inside the window, or NO_OPEN with the server's
 *       line (and none on the silent one);</li>
 *   <li>{@code summaryLines} buckets them (eye-stand +3.5, +1.5 open; -1.5 0/2);</li>
 *   <li>the same four clicks with the setting off record nothing, and the client sends exactly the same packets as
 *       with it on (PacketTrace tap, ambient packets excluded) - the logger adds none.</li>
 * </ol>
 */
final class TermLogCases {

    static final String LOGGER = "termlog.TerminalOpenLogger";
    static final String CONFIG = "termlog.TerminalOpenLoggerConfig";
    static final String BELOW_LINE = "You can't open a terminal from below it!";

    /** Pillar at block (X, Z); ground top at G. Positive x/z: Floor7Tracker.inF7Boss is x > -7 && z > -7. */
    static final int X = 40;
    static final int Z = 40;
    static final int G = 151;

    /** Packets a still or turning player sends anyway; not part of the comparison. */
    static final Set<String> AMBIENT = Set.of("client_tick_end", "keep_alive", "pong", "player_input",
            "chunk_batch_received", "accept_teleportation", "player_loaded", "client_information",
            "move_player_pos", "move_player_rot", "move_player_pos_rot", "move_player_status_only");

    private TermLogCases() {
    }

    static void register(Session s) {
        s.test("409-boss-termlog", TermLogCases::run);
    }

    private record Spot(String name, double x, double y, double z, double eyeDy, boolean opens, boolean line) {
    }

    static final List<Spot> SPOTS = List.of(
            new Spot("above", X + 0.5, G + 5, Z + 2.5, 3.62, true, true),
            new Spot("level", X - 1.5, G + 3, Z + 0.5, 1.62, true, true),
            new Spot("below", X + 2.5, G, Z + 0.5, -1.38, false, true),
            new Spot("below-silent", X + 2.5, G, Z + 0.5, -1.38, false, false));

    static void run(Session c) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        try {
            if (!Mod.has(LOGGER)) {
                c.check(false, "this jar has no " + LOGGER);
            }
            boolean defaultOn = c.onClient(mc -> (Boolean) Mod.get(CONFIG, "isEnabled"));
            c.check(defaultOn, "Terminal Open Logger should default ON (it records by itself in the boss)");
            c.onClient(mc -> Mod.staticCall(LOGGER, "clearFiles"));

            c.hx().call("boss.term.build", "x", X, "z", Z, "groundY", G);
            Spot level = SPOTS.get(1);
            c.hx().call("dungeon.tp", "x", level.x(), "y", level.y(), "z", level.z(), "yaw", 0f, "pitch", 0f);
            c.waitUntil("the client at the terminal arena", mc -> mc.player != null
                    && mc.player.position().distanceTo(new Vec3(level.x(), level.y(), level.z())) < 0.05, 200);
            c.ctx().waitTicks(20);
            JsonObject st = c.hx().call("boss.term.spawn").getAsJsonObject();
            int standId = st.get("standId").getAsInt();
            c.check(!st.get("marker").getAsBoolean(), "the fake's stand must be pickable (not a marker)");

            // ---- 1. outside the boss ----
            c.hx().sidebarClear();
            c.waitUntil("DungeonState.getFloor()==null", mc -> Mod.staticCall("secrets.DungeonState", "getFloor") == null, 100);
            c.ctx().waitTicks(3);
            boolean armedOutside = c.onClient(mc -> (Boolean) Mod.staticCall(LOGGER, "isArmed"));
            c.check(!armedOutside, "the logger is armed outside any dungeon");
            long before = records(c);
            int serverBefore = c.events("term.interact").size();
            place(c, SPOTS.get(1), standId);
            c.ctx().getInput().pressKey(o -> o.keyUse);
            c.waitUntil("the server to see the outside-boss click", mc -> c.events("term.interact").size() > serverBefore, 40);
            c.ctx().waitTicks(25);
            closeScreen(c);
            long afterOutside = records(c);
            int pendingOutside = c.onClient(mc -> (Integer) Mod.staticCall(LOGGER, "pendingCount"));
            c.note("outside the boss: armed " + armedOutside + ", records " + before + " -> " + afterOutside
                    + ", pending " + pendingOutside);
            c.check(afterOutside == before && pendingOutside == 0, "a click outside the boss was recorded");

            // ---- 2. enter the boss: arms by itself ----
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("the logger to arm in the F7 boss", mc -> (Boolean) Mod.staticCall(LOGGER, "isArmed"), 100);
            c.note("armed by itself on entering the F7 boss (setting at its default)");

            // ---- 3. four clicks, logger on ----
            Map<String, Integer> onPackets = new TreeMap<>();
            int[] onInteracts = new int[1];
            List<JsonObject> recs = new ArrayList<>();
            List<JsonObject> serverOn = new ArrayList<>();
            playAll(c, standId, onPackets, onInteracts, serverOn, recs, true);

            for (int i = 0; i < SPOTS.size(); i++) {
                Spot sp = SPOTS.get(i);
                JsonObject rec = recs.get(i);
                JsonObject g = rec.getAsJsonObject("geometry");
                JsonObject out = rec.getAsJsonObject("outcome");
                JsonObject p0 = rec.getAsJsonArray("packets").get(0).getAsJsonObject();
                double dy = g.get("dyEyeStandFeet").getAsDouble();
                String result = out.get("result").getAsString();
                c.note(String.format(Locale.ROOT, "%s: %s delay %s title %s | type %s | dy eye-stand %+.3f eye-block %s"
                                + " | pitch %.1f | box %.2f | losStand %s losBlock %s | block %s (%s) | chat %s",
                        sp.name(), result, out.has("delayTicks") ? out.get("delayTicks") : "-",
                        out.has("title") ? out.get("title") : "-", p0.get("type").getAsString(), dy,
                        g.has("dyEyeBlockTop") ? g.get("dyEyeBlockTop") : "-",
                        rec.getAsJsonObject("player").get("pitch").getAsDouble(), g.get("boxDistStand").getAsDouble(),
                        g.get("losStand"), g.get("losBlock"),
                        rec.getAsJsonObject("block").get("id"), rec.getAsJsonObject("block").get("rule"), out.get("chat")));
                JsonObject server = serverOn.get(i);
                c.check(server.get("opened").getAsBoolean() == sp.opens(),
                        sp.name() + ": the server's own verdict disagrees with the plan: " + server);
                c.check("interact".equals(p0.get("type").getAsString()) && p0.get("entityId").getAsInt() == standId,
                        sp.name() + ": first packet should be an interact on stand " + standId + ": " + p0);
                c.check(Math.abs(dy - sp.eyeDy()) < 0.05, sp.name() + ": dyEyeStandFeet " + dy + ", expected " + sp.eyeDy());
                c.check(sp.eyeDy() < 0 ? dy < 0 : dy > 0, sp.name() + ": wrong sign of dy " + dy);
                c.check("minecraft:command_block".equals(rec.getAsJsonObject("block").get("id").getAsString()),
                        sp.name() + ": terminal block should be the command block: " + rec.getAsJsonObject("block"));
                c.check(Math.abs(g.get("dyEyeBlockTop").getAsDouble() - dy) < 0.01,
                        sp.name() + ": block top is the stand's feet here, so dyEyeBlockTop should equal dyEyeStandFeet");
                c.check("Inactive Terminal".equals(rec.getAsJsonObject("stand").get("name").getAsString()),
                        sp.name() + ": stand name " + rec.getAsJsonObject("stand").get("name"));
                c.check("ENTITY".equals(rec.getAsJsonObject("crosshair").get("type").getAsString()),
                        sp.name() + ": crosshair should be on the stand: " + rec.getAsJsonObject("crosshair"));
                JsonArray chat = out.getAsJsonArray("chat");
                if (sp.opens()) {
                    c.check("OPEN".equals(result), sp.name() + ": expected OPEN, got " + out);
                    c.check("Click in order!".equals(out.get("title").getAsString()) && "NUMBERS".equals(out.get("terminal").getAsString()),
                            sp.name() + ": title/terminal " + out);
                    int delay = out.get("delayTicks").getAsInt();
                    c.check(delay >= 0 && delay < 20, sp.name() + ": delay " + delay + " outside the 20-tick window");
                } else {
                    c.check("NO_OPEN".equals(result), sp.name() + ": expected NO_OPEN, got " + out);
                    boolean hasLine = chat.toString().contains(BELOW_LINE);
                    c.check(hasLine == sp.line(), sp.name() + ": chat " + chat + " (server line expected: " + sp.line() + ")");
                }
                for (int k = 0; k < chat.size(); k++) {
                    c.check(!chat.get(k).getAsString().startsWith("[TermLog]"), "the logger recorded its own chat line");
                }
            }

            // ---- 4. the summary ----
            @SuppressWarnings("unchecked")
            List<String> summary = c.onClient(mc -> (List<String>) Mod.staticCall(LOGGER, "summaryLines"));
            summary.forEach(l -> c.note("summary| " + l));
            String all = String.join("\n", summary);
            c.check(all.startsWith("4 right-click attempt(s), 2 opened"), "summary header: " + summary.get(0));
            for (String want : List.of("+3.5..+4.0  1/1 open (100%)", "+1.5..+2.0  1/1 open (100%)",
                    "-1.5..-1.0  0/2 open (0%)")) {
                c.check(all.contains(want), "summary is missing '" + want + "'");
            }
            int inFile = c.onClient(mc -> (Integer) Mod.staticCall(LOGGER, "recordsInFile"));
            c.check(inFile == 4, "the attempts file holds " + inFile + " records, expected 4");

            // ---- 5. setting off: nothing recorded, same packets ----
            undo.add(c.onClient(mc -> Mod.with(CONFIG, "Enabled", false)));
            c.ctx().waitTicks(2);
            Map<String, Integer> offPackets = new TreeMap<>();
            int[] offInteracts = new int[1];
            List<JsonObject> serverOff = new ArrayList<>();
            long beforeOff = records(c);
            playAll(c, standId, offPackets, offInteracts, serverOff, null, false);
            long afterOff = records(c);
            int inFileOff = c.onClient(mc -> (Integer) Mod.staticCall(LOGGER, "recordsInFile"));
            c.note("packets with the logger ON:  " + onPackets + " (PacketWatch interacts " + onInteracts[0] + ")");
            c.note("packets with the logger OFF: " + offPackets + " (PacketWatch interacts " + offInteracts[0] + ")");
            c.check(afterOff == beforeOff && inFileOff == 4, "the logger recorded with its setting off: "
                    + beforeOff + " -> " + afterOff + ", file " + inFileOff);
            c.check(onInteracts[0] == 4 && offInteracts[0] == 4, "PacketWatch should count 4 interacts in each run, got "
                    + onInteracts[0] + " / " + offInteracts[0]);
            c.check(onPackets.equals(offPackets), "the logger changed what the client sends: ON " + onPackets
                    + " vs OFF " + offPackets);
        } finally {
            closeScreen(c);
            c.onClient(mc -> {
                PacketTrace.tap = null;
                PacketWatch.stop();
                for (int i = undo.size() - 1; i >= 0; i--) {
                    try {
                        undo.get(i).close();
                    } catch (Exception e) {
                        System.out.println("[termlog] restore failed: " + e);
                    }
                }
                return null;
            });
            c.hx().call("boss.term.clear");
            c.hx().sidebarClear();
            c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 0f, "pitch", 0f);
        }
    }

    /** The four spots in order; fills the packet multiset, the server events and (logger on) the records. */
    static void playAll(Session c, int standId, Map<String, Integer> packets, int[] interacts, List<JsonObject> server,
                        List<JsonObject> recs, boolean loggerOn) throws Exception {
        for (Spot sp : SPOTS) {
            c.hx().call("boss.term.config", "line", sp.line());
            long before = records(c);
            int serverBefore = c.events("term.interact").size();
            // Placed and aimed first; counting starts just before the click and stops after its outcome.
            place(c, sp, standId);
            c.onClient(mc -> {
                PacketWatch.start();
                PacketTrace.tap = p -> {
                    var id = p.type().id();
                    String name = id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
                    if (!AMBIENT.contains(name)) {
                        synchronized (packets) {
                            packets.merge(name, 1, Integer::sum);
                        }
                    }
                };
                return null;
            });
            c.ctx().getInput().pressKey(o -> o.keyUse);
            c.waitUntil(sp.name() + ": the server to see the click (term.interact)",
                    mc -> c.events("term.interact").size() > serverBefore, 40);
            if (sp.opens()) {
                c.waitUntil(sp.name() + ": the terminal screen", mc -> dev.testkit.compat.McCompat.screen(mc) != null, 40);
            }
            if (loggerOn) {
                c.waitUntil(sp.name() + ": the logger's record", mc -> (Long) Mod.staticCall(LOGGER, "sessionRecords") > before, 60);
            } else {
                c.ctx().waitTicks(25);
            }
            closeScreen(c);
            c.ctx().waitTicks(3);
            c.onClient(mc -> {
                PacketTrace.tap = null;
                interacts[0] += PacketWatch.entityInteracts();
                PacketWatch.stop();
                return null;
            });
            List<JsonObject> ev = c.events("term.interact");
            server.add(ev.get(ev.size() - 1));
            if (recs != null) {
                @SuppressWarnings("unchecked")
                List<String> recent = c.onClient(mc -> (List<String>) Mod.staticCall(LOGGER, "recentRecords"));
                recs.add(JsonParser.parseString(recent.get(recent.size() - 1)).getAsJsonObject());
            }
        }
    }

    static long records(Session c) {
        return c.onClient(mc -> (Long) Mod.staticCall(LOGGER, "sessionRecords"));
    }

    /** Teleport to the spot, turn to the stand's centre, and make sure the crosshair is on it. */
    static void place(Session c, Spot sp, int standId) {
        // Turned by the teleport itself: a client-side turn made before the teleport lands is undone by it.
        double dx = (X + 0.5) - sp.x();
        double dyc = (G + 3 + 0.9875) - (sp.y() + 1.62);
        double dz = (Z + 0.5) - sp.z();
        float aimYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float aimPitch = (float) -Math.toDegrees(Math.atan2(dyc, Math.sqrt(dx * dx + dz * dz)));
        c.hx().call("dungeon.tp", "x", sp.x(), "y", (double) sp.y(), "z", sp.z(), "yaw", aimYaw, "pitch", aimPitch);
        c.ctx().waitTicks(5);
        try {
            c.waitUntil(sp.name() + ": the client at the spot", mc -> mc.player != null
                    && mc.player.position().distanceTo(new Vec3(sp.x(), sp.y(), sp.z())) < 0.05, 200);
            c.waitUntil(sp.name() + ": the client to have the terminal stand " + standId,
                    mc -> mc.level.getEntity(standId) != null, 200);
        } catch (AssertionError e) {
            String where = c.onClient(mc -> mc.player == null ? "no player" : mc.player.position() + ", stand "
                    + mc.level.getEntity(standId) + ", block below " + mc.level.getBlockState(mc.player.blockPosition().below()));
            throw new AssertionError(e.getMessage() + " (client: " + where + ")", e);
        }
        c.ctx().runOnClient(mc -> {
            Entity stand = mc.level.getEntity(standId);
            Vec3 eye = mc.player.getEyePosition();
            Vec3 d = stand.getBoundingBox().getCenter().subtract(eye);
            float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
        });
        c.ctx().waitTicks(3);
        try {
            c.waitUntil(sp.name() + ": the crosshair on the terminal stand", mc ->
                    mc.hitResult instanceof EntityHitResult eh && eh.getEntity().getId() == standId, 20);
        } catch (AssertionError e) {
            String what = c.onClient(mc -> {
                Entity stand = mc.level.getEntity(standId);
                return "hit " + (mc.hitResult == null ? null : mc.hitResult.getType() + " " + mc.hitResult.getLocation()
                        + (mc.hitResult instanceof EntityHitResult eh ? " " + eh.getEntity() : ""))
                        + ", eye " + mc.player.getEyePosition() + ", yaw " + mc.player.getYRot() + " pitch "
                        + mc.player.getXRot() + ", stand " + stand + " box " + (stand == null ? null : stand.getBoundingBox())
                        + " pickable " + (stand != null && stand.isPickable()) + ", screen " + dev.testkit.compat.McCompat.screen(mc);
            });
            throw new AssertionError(e.getMessage() + " (" + what + ")", e);
        }
    }

    static void closeScreen(Session c) {
        c.onClient(mc -> {
            if (mc.player != null && dev.testkit.compat.McCompat.screen(mc) != null) {
                mc.player.closeContainer();
            }
            return null;
        });
    }
}

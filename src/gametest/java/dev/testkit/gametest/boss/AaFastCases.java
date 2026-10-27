package dev.testkit.gametest.boss;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.HxKit;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Latency;
import dev.testkit.harness.PacketTrace;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Arrow Align Aura as fast as GrimAC allows (mod aafast-1027, killer560 2026-10-27: "trying to do as many clicks as fast as
 * possible without flagging. Ideally I want it to multi click every item frame as fast as possible"). Layout 7 of the mod's
 * table (index 6, nine frames, every target rotation 1), each frame 1 to 7 clicks from solved, 40 clicks in all; he stands
 * where every frame is inside 3.0 and looks between the rows, so every frame needs a turn.
 * <p>
 * Every case proves it acted before cleanliness counts: the server's use_entity count equals the clicks needed, the SERVER's
 * own frame rotations ({@code data get entity <uuid> ItemRotation}) are all 1 afterwards, and the client's wire shows how
 * many clicks went in each tick, that no tick touched two frames (GrimAC MultiInteractA), nothing went out between ticks
 * (Post), and, for Turn And Click Same Tick, that frames were clicked in the tick whose movement packet first turned to
 * them. Ticks to solve = client ticks from the first click to the last, inclusive.
 * <ul>
 *   <li>1027-aafast-&lt;mode&gt;-&lt;ms&gt;: modes cpt1 (CPT 1, the default), cpt4 (the old max), cpt7, same1 (CPT 1 + same
 *   tick), same7 (CPT 7 + same tick, the fastest); 0/50/150/300 ms round trip (inbound {@link Latency}).</li>
 *   <li>1027-aafast-twoframes (FLAGGED expected): the harness clicks two frames in one tick at START, each on its own
 *   look written that tick - the control for "more than one frame per tick". Recorded, not judged: MultiInteractA and
 *   MultiInteractB are EXPERIMENTAL in GrimAC 2.3.74 and report only with experimental-checks: true in the test server's
 *   config/GrimAC/config.yml (off by default); with it on, both flag and Grim drops the second click.</li>
 *   <li>1027-aafast-sameframe7: the harness clicks one frame 7 times in one tick on the look the last movement packet
 *   reported - the same-frame control, must be clean.</li>
 *   <li>1027-aafast-settings: Clicks Per Tick clamps to 1..7, Turn And Click Same Tick persists through save + load
 *   (and on a legit jar reads false whatever is saved).</li>
 * </ul>
 */
final class AaFastCases {

    private static final String AA_CFG = "arrowalign.ArrowAlignConfig";
    /** Layout 7 (grid index dy + dz * 5); every frame's target rotation is 1. */
    private static final int[] LAYOUT = {5, 7, 9, 10, 12, 14, 15, 17, 19};
    /** Clicks each LAYOUT frame needs: 40 in all, every count 1-7 present. */
    private static final int[] NEEDS = {7, 3, 5, 1, 6, 2, 4, 7, 5};
    private static final AABB WALL = new AABB(-4, 118, 73, 0, 126, 81);
    private static final int[] LATENCIES = {0, 50, 150, 300};
    private static final Pattern ROT = Pattern.compile("has the following entity data: (\\d+)b");

    private AaFastCases() {
    }

    private record Mode(String name, int cpt, boolean same, int fpt) {
    }

    /** Frames Per Tick 1 everywhere but fpt3 / fpt15 (killer560, 2026-10-27: "Add Frames Per Tick as a cheat-only option"). */
    private static final Mode[] MODES = {new Mode("cpt1", 1, false, 1), new Mode("cpt4", 4, false, 1),
            new Mode("cpt7", 7, false, 1), new Mode("same1", 1, true, 1), new Mode("same7", 7, true, 1),
            new Mode("fpt3", 7, true, 3), new Mode("fpt15", 7, true, 15)};

    /** Whether the test server's GrimAC runs its experimental checks (MultiInteractA/B among them): its config.yml. */
    private static boolean experimentalChecks() {
        try {
            java.util.Properties props = new java.util.Properties();
            try (var in = java.nio.file.Files.newInputStream(java.nio.file.Path.of(
                    System.getProperty("testkit.testserver.launch")))) {
                props.load(in);
            }
            java.nio.file.Path cfg = java.nio.file.Path.of(props.getProperty("dir"), "config", "GrimAC", "config.yml");
            return java.nio.file.Files.readString(cfg).lines().anyMatch(l -> l.trim().equals("experimental-checks: true"));
        } catch (Exception e) {
            return false;
        }
    }

    static void register(Session s) {
        s.test("1027-aafast-settings", AaFastCases::settings);
        if (!Mod.isCheat()) {
            return;
        }
        for (int ms : LATENCIES) {
            for (Mode m : MODES) {
                if (m.fpt > 1) {
                    // Judged in the body: clean on Grim's default config; on experimental checks MultiInteractA/B must show.
                    s.testExpectingFlags("1027-aafast-" + m.name + "-" + ms, c -> solve(c, m, ms));
                } else {
                    s.test("1027-aafast-" + m.name + "-" + ms, c -> solve(c, m, ms));
                }
            }
        }
        s.test("1027-aafast-sameframe7", AaFastCases::sameFrame7);
        s.testExpectingFlags("1027-aafast-twoframes", AaFastCases::twoFrames);
    }

    // ---- the harness's own clicks, at START of a tick ---------------------------------------------------------------

    private static final ConcurrentLinkedQueue<Runnable> AT_START = new ConcurrentLinkedQueue<>();
    private static boolean hooked;

    private static void atStart(Session c, Runnable r) {
        c.onClient(mc -> {
            if (!hooked) {
                ClientTickEvents.START_CLIENT_TICK.register(client -> {
                    Runnable job;
                    while ((job = AT_START.poll()) != null) {
                        job.run();
                    }
                });
                hooked = true;
            }
            AT_START.add(r);
            return null;
        });
    }

    // ---- setup ------------------------------------------------------------------------------------------------------

    private static HxKit.Settings gateOff(Session c) {
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        return new HxKit.Settings(c).custom(() -> Mod.staticCall("util.SkyblockGate", "setEnabled", false),
                () -> Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas));
    }

    private static void wall(Session c) {
        c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
        c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
        c.server().command("forceload add -3 72 3 82");
        c.server().sync(20);
        c.server().command("fill -1 119 72 3 119 82 stone");
        c.server().command("fill -3 119 74 -3 125 80 stone");
        // Every frame's box inside 3.0 of the eye from here (GrimTickCases 832); level look between the rows, on no frame.
        c.hx().call("dungeon.tp", "x", -0.7, "y", 120.0, "z", 77.5, "yaw", 90f, "pitch", 0f);
        c.waitUntil("standing by the wall", mc -> mc.player.onGround() && Math.abs(mc.player.getY() - 120.0) < 0.01
                && mc.level.getBlockState(new BlockPos(-3, 122, 77)).is(net.minecraft.world.level.block.Blocks.STONE), 200);
    }

    private static void cleanup(Session c) {
        c.hx().removeStands();
        c.server().command("kill @e[type=item_frame,x=-2,y=122,z=77,distance=..6]");
        c.server().command("fill -3 119 72 3 125 82 air");
        c.server().command("forceload remove -3 72 3 82");
        c.hx().sidebarClear();
        c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 0f, "pitch", 0f);
    }

    /** Layout 7 afresh, frame i at rotation (1 - needs[i]) & 7. Returns {ids, uuids} in LAYOUT order. */
    private static Object[] grid(Session c, int[] needs) {
        return grid(c, needs, true);
    }

    private static Object[] grid(Session c, int[] needs, boolean solver) {
        c.hx().removeStands();
        c.waitUntil("the old frames gone", mc -> mc.level.getEntitiesOfClass(ItemFrame.class, WALL).isEmpty(), 100);
        int[] ids = new int[LAYOUT.length];
        String[] uuids = new String[LAYOUT.length];
        for (int i = 0; i < LAYOUT.length; i++) {
            int idx = LAYOUT[i];
            JsonObject f = c.hx().call("frame", "x", -2, "y", 120 + idx % 5, "z", 75 + idx / 5, "facing", "east",
                    "item", "arrow", "rotation", (1 - needs[i]) & 7).getAsJsonObject();
            ids[i] = f.get("id").getAsInt();
            uuids[i] = f.get("uuid").getAsString();
        }
        c.waitUntil("the client to have the frames",
                mc -> mc.level.getEntitiesOfClass(ItemFrame.class, WALL).size() == LAYOUT.length, 200);
        if (solver) {
            c.waitUntil("Arrow Align to read a solution", mc -> Mod.field(Mod.ROOT + "arrowalign.ArrowAlignFeature",
                    "solution") != null, 100);
        }
        c.ctx().waitTicks(3);
        return new Object[]{ids, uuids};
    }

    /** The server's own rotation of a frame, from {@code data get entity}. */
    private static int serverRotation(Session c, String uuid) {
        int before = c.server().countSince("has the following entity data");
        c.server().command("data get entity " + uuid + " ItemRotation");
        for (int i = 0; i < 300; i++) {
            c.ctx().waitTicks(1);
            if (i == 150) {
                c.server().command("data get entity " + uuid + " ItemRotation"); // under load a line can be slow
            }
            if (c.server().countSince("has the following entity data") > before) {
                Integer last = null;
                for (String line : c.server().since()) {
                    Matcher m = ROT.matcher(line);
                    if (m.find()) {
                        last = Integer.parseInt(m.group(1));
                    }
                }
                if (last != null) {
                    return last;
                }
            }
        }
        throw new AssertionError("the server never answered data get entity " + uuid + " ItemRotation");
    }

    private static void aura(Session c, boolean on) {
        c.onClient(mc -> {
            Mod.set(AA_CFG, "setAuraEnabled", on);
            return null;
        });
    }

    /** Which tracked frame (entity id) a look from his eye meets first within 4 blocks, or -1. */
    private static int frameOnLook(Minecraft mc, float yaw, float pitch) {
        Vec3 eye = mc.player.getEyePosition();
        float yr = yaw * Mth.DEG_TO_RAD;
        float pr = pitch * Mth.DEG_TO_RAD;
        float cp = Mth.cos(pr);
        Vec3 end = eye.add(new Vec3(-Mth.sin(yr) * cp, -Mth.sin(pr), Mth.cos(yr) * cp).scale(4.0));
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (ItemFrame f : mc.level.getEntitiesOfClass(ItemFrame.class, WALL)) {
            var hit = f.getBoundingBox().clip(eye, end);
            if (hit.isPresent() && eye.distanceToSqr(hit.get()) < bestD) {
                bestD = eye.distanceToSqr(hit.get());
                best = f.getId();
            }
        }
        return best;
    }

    /** One wire event: an interact (entity id) or a movement packet's look (the frame it meets, -2 = no rotation). */
    private record Wire(int tick, boolean interact, int entity, boolean teleport) {
        Wire(int tick, boolean interact, int entity) {
            this(tick, interact, entity, false);
        }
    }

    /** The move after an accept_teleportation is vanilla's answer to a server teleport, not a turn of the mod's. */
    private static final boolean[] TELEPORT_NEXT = {false};

    private static final List<Wire> WIRE = Collections.synchronizedList(new ArrayList<>());

    /** Client ticks on the wire: each ends with its client_tick_end, so a tick is everything since the last one. (Not
     *  PacketTrace.tickCount(): its START hook runs after the mod's, so a click sent at START lands in the bucket before.) */
    private static final int[] TICK = {0};

    private static void tapOn(Session c) {
        WIRE.clear();
        c.onClient(mc -> {
            TICK[0] = 0;
            TELEPORT_NEXT[0] = false;
            PacketTrace.start();
            PacketTrace.tap = p -> {
                Minecraft m = Minecraft.getInstance();
                if (p instanceof ServerboundInteractPacket in) {
                    WIRE.add(new Wire(TICK[0], true, in.entityId()));
                } else if (p instanceof net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket) {
                    TELEPORT_NEXT[0] = true;
                } else if (p instanceof ServerboundMovePlayerPacket mv && mv.hasRotation() && m.player != null) {
                    WIRE.add(new Wire(TICK[0], false, frameOnLook(m, mv.getYRot(0f), mv.getXRot(0f)), TELEPORT_NEXT[0]));
                    TELEPORT_NEXT[0] = false;
                } else if (p instanceof net.minecraft.network.protocol.game.ServerboundClientTickEndPacket) {
                    TICK[0]++;
                }
            };
            return null;
        });
    }

    private static void tapOff(Session c) {
        c.onClient(mc -> {
            PacketTrace.tap = null;
            PacketTrace.stop();
            return null;
        });
    }

    // ---- the solve cases --------------------------------------------------------------------------------------------

    private static void solve(Session c, Mode mode, int ms) throws Exception {
        int total = 0;
        for (int n : NEEDS) {
            total += n;
        }
        try (HxKit.Settings off = gateOff(c);
             HxKit.Settings on = new HxKit.Settings(c).with(AA_CFG, "SolverEnabled", true)
                     .with(AA_CFG, "PreventMisclicksEnabled", false).with(AA_CFG, "TriggerBotEnabled", false)
                     .with(AA_CFG, "AuraEnabled", false).with(AA_CFG, "AuraRange", 3.0).with(AA_CFG, "AuraCps", 20)
                     .with(AA_CFG, "AuraClicksPerTick", mode.cpt).with(AA_CFG, "AuraSameTickTurn", mode.same)
                     .with(AA_CFG, "AuraFramesPerTick", mode.fpt)
                     .with(AA_CFG, "AuraLeaveLastArrow", false).with(AA_CFG, "AuraDisableWhileCrouching", true)) {
            wall(c);
            if (ms > 0) {
                c.onClient(mc -> {
                    Latency.install(ms);
                    return null;
                });
                c.ctx().waitTicks(40);
            }
            Object[] g = grid(c, NEEDS);
            int[] ids = (int[]) g[0];
            String[] uuids = (String[]) g[1];
            c.check(c.onClient(mc -> frameOnLook(mc, mc.player.getYRot(), mc.player.getXRot())) == -1,
                    "the start look is on a frame: every frame must need a turn");
            long since = c.hx().head();
            tapOn(c);
            aura(c, true);
            final int want = total;
            if (mode.fpt > 1 && experimentalChecks()) {
                expectedFlags(c, mode, ms, since, want, uuids);
                return;
            }
            c.waitUntil("every click reached the server (use_entity)",
                    mc -> c.hx().events(since, "use_entity").size() >= want, 400);
            // The server's own rotations, read at once: the frames have been seen to vanish from the server about a second
            // after a solve in a session's first frame case (m2, cause not found), and the click count after the quiet
            // wait below is what proves nothing overshot.
            c.ctx().waitTicks(5);
            int[] rot = new int[LAYOUT.length];
            for (int i = 0; i < LAYOUT.length; i++) {
                rot[i] = serverRotation(c, uuids[i]);
            }
            c.ctx().waitTicks(30 + ms / 25); // nothing more may go out: every frame is solved
            aura(c, false);
            tapOff(c);
            List<String> outside = PacketTrace.sentOutsideTick();
            List<JsonObject> used = c.hx().events(since, "use_entity");

            // The wire, per client tick.
            List<Wire> wire;
            synchronized (WIRE) {
                wire = new ArrayList<>(WIRE);
            }
            Map<Integer, List<Integer>> perTick = new java.util.TreeMap<>();
            int lastLook = -1; // the frame the last reported look met
            int sameTickClicks = 0; // ticks whose clicks were on a frame the previous look did not meet
            int framesTurnedFirst = 0;
            List<String> turnOnly = new ArrayList<>();
            int prevLookAtTickStart = -1;
            int firstFrameOfTick = -1;
            int curTick = -1;
            Set<Integer> turnedTicks = new LinkedHashSet<>();
            List<String> wrongLook = new ArrayList<>();
            for (int i = 0; i < wire.size(); i++) {
                Wire w = wire.get(i);
                if (w.tick != curTick) {
                    curTick = w.tick;
                    prevLookAtTickStart = lastLook;
                    firstFrameOfTick = -1;
                }
                if (w.interact) {
                    perTick.computeIfAbsent(w.tick, k -> new ArrayList<>()).add(w.entity);
                    if (firstFrameOfTick < 0) {
                        firstFrameOfTick = w.entity;
                    }
                    // Frames Per Tick's further frames go at the first frame's look on purpose: only the first is judged.
                    if (w.entity == firstFrameOfTick && w.entity != prevLookAtTickStart) {
                        // Not on the look the server last had: the movement packet later in THIS tick must turn to it.
                        int after = -3;
                        for (int j = i + 1; j < wire.size() && wire.get(j).tick == w.tick; j++) {
                            if (!wire.get(j).interact) {
                                after = wire.get(j).entity;
                                break;
                            }
                        }
                        if (after == w.entity) {
                            turnedTicks.add(w.tick);
                        } else {
                            wrongLook.add("t" + w.tick + " frame #" + w.entity + " (look before: #" + prevLookAtTickStart
                                    + ", this tick's move: #" + after + ")");
                        }
                    }
                } else {
                    lastLook = w.entity;
                    if (w.entity >= 0 && !w.teleport && !perTick.containsKey(w.tick)) {
                        framesTurnedFirst++;
                        turnOnly.add("t" + w.tick + " look on #" + w.entity);
                    }
                }
            }
            sameTickClicks = turnedTicks.size();
            int first = perTick.isEmpty() ? 0 : ((java.util.TreeMap<Integer, List<Integer>>) perTick).firstKey();
            int last = perTick.isEmpty() ? 0 : ((java.util.TreeMap<Integer, List<Integer>>) perTick).lastKey();
            int ticks = perTick.isEmpty() ? 0 : last - first + 1;
            int maxPerTick = 0; // clicks on ONE frame in one tick, the most seen
            int maxFrames = 0; // distinct frames in one tick, the most seen
            List<String> twoFrames = new ArrayList<>();
            StringBuilder shape = new StringBuilder();
            for (var e : perTick.entrySet()) {
                Map<Integer, Integer> byFrame = new HashMap<>();
                e.getValue().forEach(id -> byFrame.merge(id, 1, Integer::sum));
                maxPerTick = Math.max(maxPerTick, java.util.Collections.max(byFrame.values()));
                maxFrames = Math.max(maxFrames, byFrame.size());
                if (byFrame.size() > mode.fpt) {
                    twoFrames.add("t" + e.getKey() + " " + e.getValue());
                }
                shape.append(e.getKey() - first).append(':').append(e.getValue().size()).append(' ');
            }
            long st0 = used.isEmpty() ? 0 : used.get(0).get("tick").getAsLong();
            long st1 = used.isEmpty() ? 0 : used.get(used.size() - 1).get("tick").getAsLong();
            List<String> wrongRot = new ArrayList<>();
            for (int i = 0; i < LAYOUT.length; i++) {
                if (rot[i] != 1) {
                    wrongRot.add("grid " + LAYOUT[i] + " (#" + ids[i] + ") at " + rot[i]);
                }
            }
            Map<Integer, Integer> perFrame = new HashMap<>();
            for (JsonObject e : used) {
                perFrame.merge(e.get("entityId").getAsInt(), 1, Integer::sum);
            }
            List<String> wrongCount = new ArrayList<>();
            for (int i = 0; i < LAYOUT.length; i++) {
                int got = perFrame.getOrDefault(ids[i], 0);
                if (got != NEEDS[i]) {
                    wrongCount.add("grid " + LAYOUT[i] + " got " + got + " of " + NEEDS[i]);
                }
            }
            c.note(String.format(Locale.ROOT, "aafast %s %d ms: %d clicks (%d server use_entity), %d client ticks to solve "
                            + "(server ticks %d), max %d a tick, same-tick turned ticks %d, turn-only ticks %d, server "
                            + "rotations %s; per tick (offset:clicks) %s",
                    mode.name, ms, wire.stream().filter(w -> w.interact).count(), used.size(), ticks, st1 - st0 + 1,
                    maxPerTick, sameTickClicks, framesTurnedFirst, java.util.Arrays.toString(rot), shape.toString().trim()));
            c.check(used.size() == total, "the server handled " + used.size() + " clicks, the frames needed " + total);
            c.check(wrongCount.isEmpty(), "clicks per frame wrong: " + wrongCount);
            c.check(wrongRot.isEmpty(), "server rotations not solved: " + wrongRot);
            c.check(twoFrames.isEmpty(), "more frames in one tick than Frames Per Tick " + mode.fpt + ": " + twoFrames);
            c.check(maxPerTick <= mode.cpt, "a tick carried " + maxPerTick + " clicks with Clicks Per Tick " + mode.cpt);
            c.check(outside.isEmpty(), "sent outside a tick (Post): " + outside);
            c.check(wrongLook.isEmpty(), "a click on a frame neither the last look nor this tick's move met: " + wrongLook);
            if (mode.same) {
                c.check(sameTickClicks > 0, "Turn And Click Same Tick never clicked in its turn tick: nothing was tested");
                if (framesTurnedFirst != 0) {
                    c.note("turn-only ticks " + turnOnly + "; PacketTrace (START-bucketed):");
                    PacketTrace.lines().forEach(c::note);
                }
                c.check(framesTurnedFirst == 0, framesTurnedFirst + " tick(s) turned without clicking under Same Tick: "
                        + turnOnly);
                c.check(ticks <= LAYOUT.length * ((7 + mode.cpt - 1) / mode.cpt) + 2,
                        "same tick took " + ticks + " ticks");
            } else {
                c.check(sameTickClicks == 0, "with Same Tick off " + sameTickClicks + " click tick(s) were not on the last look");
            }
            if (mode.cpt == 7) {
                c.check(maxPerTick == 7, "Clicks Per Tick 7 never put 7 clicks in a tick (a frame needing 7 was there)");
            }
            if (mode.fpt > 1) {
                int wantTicks = (LAYOUT.length + mode.fpt - 1) / mode.fpt;
                c.note("aafast " + mode.name + " " + ms + " ms: up to " + maxFrames + " frames in a tick, " + ticks
                        + " tick(s) to solve (want " + wantTicks + "), Grim experimental checks off");
                c.check(maxFrames == Math.min(mode.fpt, LAYOUT.length), "Frames Per Tick " + mode.fpt + " put at most "
                        + maxFrames + " frames in a tick");
                c.check(ticks == wantTicks, mode.name + " took " + ticks + " ticks, want " + wantTicks);
                c.ctx().waitTicks(10);
                c.check(c.scenario().flags().isEmpty(), "Grim's default checks flagged Frames Per Tick: "
                        + c.scenario().flags());
            } else if (mode.same && mode.cpt == 7) {
                c.check(ticks == LAYOUT.length, "same7 should take exactly one tick per frame (" + LAYOUT.length
                        + "), took " + ticks);
            }
        } finally {
            aura(c, false);
            tapOff(c);
            if (ms > 0) {
                c.onClient(mc -> {
                    Latency.remove();
                    return null;
                });
                c.ctx().waitTicks(20 + ms / 25);
            }
            cleanup(c);
        }
    }

    /**
     * Frames Per Tick above 1 with GrimAC's experimental checks on: recorded for the owner, not a pass/fail on cleanliness.
     * MultiInteractA (a second entity before the next tick packet) must be among the flags; how many clicks still reached
     * the server (Grim cancels a flagged interact), and whether the device got solved anyway, are noted.
     */
    private static void expectedFlags(Session c, Mode mode, int ms, long since, int want, String[] uuids) {
        c.waitUntil("the Aura to send its first ticks", mc -> !c.hx().events(since, "use_entity").isEmpty(), 200);
        for (int i = 0; i < 200 && c.hx().events(since, "use_entity").size() < want; i++) {
            c.ctx().waitTicks(1);
        }
        int got = c.hx().events(since, "use_entity").size();
        int[] rot = new int[LAYOUT.length];
        for (int i = 0; i < LAYOUT.length; i++) {
            rot[i] = serverRotation(c, uuids[i]);
        }
        c.ctx().waitTicks(20 + ms / 25);
        aura(c, false);
        tapOff(c);
        List<String> flags = c.scenario().flags();
        long a = flags.stream().filter(f -> f.contains("MultiInteractA")).count();
        long b = flags.stream().filter(f -> f.contains("MultiInteractB")).count();
        long other = flags.size() - a - b;
        long sent;
        synchronized (WIRE) {
            sent = WIRE.stream().filter(w -> w.interact).count();
        }
        boolean solved = java.util.Arrays.stream(rot).allMatch(r -> r == 1);
        c.note(String.format(Locale.ROOT, "aafast %s %d ms, Grim EXPERIMENTAL checks on: %d flag line(s) (MultiInteractA %d, "
                        + "MultiInteractB %d, other %d); %d clicks sent, %d reached the server (needed %d); server rotations %s, "
                        + "solved within 10 s: %s", mode.name, ms, flags.size(), a, b, other, sent, got, want,
                java.util.Arrays.toString(rot), solved));
        flags.stream().limit(6).forEach(f -> c.note("  " + f));
        c.check(a > 0, "Frames Per Tick " + mode.fpt + " with experimental checks on drew no MultiInteractA: " + flags);
        c.check(other == 0, "flags other than MultiInteractA/B: " + flags);
    }

    // ---- controls ---------------------------------------------------------------------------------------------------

    /** One frame clicked 7 times in one tick at START, on the look the movement packets already report. */
    private static void sameFrame7(Session c) throws Exception {
        try (HxKit.Settings off = gateOff(c);
             HxKit.Settings on = new HxKit.Settings(c).with(AA_CFG, "SolverEnabled", false)
                     .with(AA_CFG, "AuraEnabled", false).with(AA_CFG, "TriggerBotEnabled", false)
                     .with(AA_CFG, "PreventMisclicksEnabled", false)) {
            wall(c);
            int[] needs = {7, 7, 7, 7, 7, 7, 7, 7, 7};
            Object[] g = grid(c, needs, false);
            int centre = ((int[]) g[0])[4]; // grid 12, y 122 z 77
            String uuid = ((String[]) g[1])[4];
            look(c, new Vec3(-1.97, 122.5, 77.5));
            c.ctx().waitTicks(5);
            c.check(c.onClient(mc -> frameOnLook(mc, mc.player.getYRot(), mc.player.getXRot())) == centre,
                    "the look is not on the center frame");
            long since = c.hx().head();
            tapOn(c);
            atStart(c, () -> clickN(centre, 7));
            c.waitUntil("seven clicks on the server", mc -> c.hx().events(since, "use_entity").size() >= 7, 100);
            c.ctx().waitTicks(20);
            tapOff(c);
            int rot = serverRotation(c, uuid);
            List<Integer> ticks;
            synchronized (WIRE) {
                ticks = WIRE.stream().filter(w -> w.interact).map(w -> w.tick).distinct().toList();
            }
            c.note("sameframe7: " + c.hx().events(since, "use_entity").size() + " use_entity in client tick(s) " + ticks
                    + ", server rotation " + rot);
            c.check(ticks.size() == 1, "the seven clicks went in " + ticks.size() + " ticks, not one");
            c.check(rot == 1, "the server rotation is " + rot + " after 7 clicks from 2, want 1");
            c.check(PacketTrace.sentOutsideTick().isEmpty(), "sent outside a tick: " + PacketTrace.sentOutsideTick());
        } finally {
            tapOff(c);
            cleanup(c);
        }
    }

    /**
     * Two frames in one tick: at START the body is turned to frame A and A is clicked, then to frame B and B is clicked;
     * the tick's movement packet carries B's look. Expected: MultiInteractA (a second entity before a tick packet), and
     * Hitboxes for A (judged at the movement packet against B's look).
     */
    private static void twoFrames(Session c) throws Exception {
        try (HxKit.Settings off = gateOff(c);
             HxKit.Settings on = new HxKit.Settings(c).with(AA_CFG, "SolverEnabled", false)
                     .with(AA_CFG, "AuraEnabled", false).with(AA_CFG, "TriggerBotEnabled", false)
                     .with(AA_CFG, "PreventMisclicksEnabled", false)) {
            wall(c);
            int[] needs = {7, 7, 7, 7, 7, 7, 7, 7, 7};
            Object[] g = grid(c, needs, false);
            int a = ((int[]) g[0])[4]; // grid 12
            int b = ((int[]) g[0])[3]; // grid 10, y 120 z 77
            long since = c.hx().head();
            int flagsBefore = c.scenario().flags().size();
            tapOn(c);
            atStart(c, () -> {
                Minecraft mc = Minecraft.getInstance();
                aim(mc, new Vec3(-1.97, 122.5, 77.5));
                clickN(a, 1);
                aim(mc, new Vec3(-1.97, 120.5, 77.5));
                clickN(b, 1);
            });
            c.ctx().waitTicks(40);
            tapOff(c);
            List<String> flags = c.scenario().flags();
            List<String> mine = flags.subList(Math.min(flagsBefore, flags.size()), flags.size());
            List<String> ticks;
            synchronized (WIRE) {
                ticks = WIRE.stream().filter(w -> w.interact).map(w -> "t" + w.tick + " #" + w.entity).toList();
            }
            boolean multi = mine.stream().anyMatch(f -> f.contains("MultiInteractA"));
            // MultiInteractA is an EXPERIMENTAL check in GrimAC 2.3.74: it only reports with experimental-checks: true in
            // the test server's config/GrimAC/config.yml (off by default). Hitboxes never looks at an item frame (Reach
            // returns NONE for a non-living entity), so the look written between the two clicks draws nothing.
            c.note("twoframes: " + c.hx().events(since, "use_entity").size() + " use_entity reached the server; wire "
                    + ticks + "; MultiInteractA " + (multi ? "FLAGGED" : "silent") + "; flags " + mine);
            c.check(ticks.size() == 2 && ticks.get(0).startsWith(ticks.get(1).split(" ")[0] + " "),
                    "the two clicks did not go in one client tick: " + ticks);
        } finally {
            tapOff(c);
            cleanup(c);
        }
    }

    /** Turn his body (camera too) to a point, from the client thread. Pitch within -90..90, yaw on the running value. */
    private static void aim(Minecraft mc, Vec3 at) {
        Vec3 d = at.subtract(mc.player.getEyePosition());
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        float run = mc.player.getYRot() + Mth.wrapDegrees(yaw - mc.player.getYRot());
        mc.player.setYRot(run);
        mc.player.setYHeadRot(run);
        mc.player.setXRot(Mth.clamp(pitch, -90f, 90f));
    }

    private static void look(Session c, Vec3 at) {
        c.onClient(mc -> {
            aim(mc, at);
            return null;
        });
    }

    /** n interacts on an entity through the vanilla path, the hit where the eye-to-center line meets its box. */
    private static void clickN(int entityId, int n) {
        Minecraft mc = Minecraft.getInstance();
        Entity e = mc.level.getEntity(entityId);
        if (e == null || mc.gameMode == null) {
            return;
        }
        AABB box = e.getBoundingBox();
        Vec3 eye = mc.player.getEyePosition();
        Vec3 hit = box.clip(eye, box.getCenter()).orElse(box.getCenter());
        for (int i = 0; i < n; i++) {
            mc.gameMode.interact(mc.player, e, new EntityHitResult(e, hit), InteractionHand.MAIN_HAND);
        }
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    // ---- settings ---------------------------------------------------------------------------------------------------

    private static void settings(Session c) throws Exception {
        boolean cheat = Mod.isCheat();
        Object[] was = c.onClient(mc -> new Object[]{Mod.get(AA_CFG, "getAuraClicksPerTick"),
                Mod.field(Mod.cfg(AA_CFG), "auraSameTickTurn")});
        try {
            List<String> problems = new ArrayList<>();
            c.onClient(mc -> {
                Object cfg = Mod.cfg(AA_CFG);
                Mod.call(cfg, "setAuraClicksPerTick", 99);
                int hi = (Integer) Mod.call(cfg, "getAuraClicksPerTick");
                Mod.call(cfg, "setAuraClicksPerTick", 0);
                int lo = (Integer) Mod.call(cfg, "getAuraClicksPerTick");
                Mod.call(cfg, "setAuraClicksPerTick", 5);
                Mod.call(cfg, "setAuraSameTickTurn", true);
                Mod.call(cfg, "save");
                Mod.staticCall(AA_CFG, "load");
                Object re = Mod.cfg(AA_CFG);
                int cpt = (Integer) Mod.call(re, "getAuraClicksPerTick");
                boolean same = (Boolean) Mod.call(re, "isAuraSameTickTurn");
                c.note("settings: set 99 -> " + hi + ", set 0 -> " + lo + ", saved 5/true -> reloaded " + cpt + "/" + same
                        + " (" + (cheat ? "cheat" : "legit") + " jar)");
                if (hi != 7 || lo != 1) {
                    problems.add("Clicks Per Tick clamps to " + lo + ".." + hi + ", want 1..7");
                }
                if (cpt != 5) {
                    problems.add("Clicks Per Tick reloaded as " + cpt);
                }
                if (same != cheat) {
                    problems.add("Turn And Click Same Tick reads " + same + " on a " + (cheat ? "cheat" : "legit") + " jar");
                }
                // Frames Per Tick: 1..15, saved; always 1 on a legit jar whatever is saved.
                Mod.call(re, "setAuraFramesPerTick", 99);
                int fhi = (Integer) Mod.field(re, "auraFramesPerTick");
                Mod.call(re, "setAuraFramesPerTick", 0);
                int flo = (Integer) Mod.field(re, "auraFramesPerTick");
                Mod.call(re, "setAuraFramesPerTick", 4);
                Mod.call(re, "save");
                Mod.staticCall(AA_CFG, "load");
                int fpt = (Integer) Mod.call(Mod.cfg(AA_CFG), "getAuraFramesPerTick");
                c.note("settings: Frames Per Tick set 99 -> " + fhi + ", set 0 -> " + flo + ", saved 4 -> reads " + fpt);
                if (fhi != 15 || flo != 1) {
                    problems.add("Frames Per Tick clamps to " + flo + ".." + fhi + ", want 1..15");
                }
                if (fpt != (cheat ? 4 : 1)) {
                    problems.add("Frames Per Tick reads " + fpt + " after saving 4 on a " + (cheat ? "cheat" : "legit") + " jar");
                }
                re = Mod.cfg(AA_CFG);
                Mod.call(re, "setAuraFramesPerTick", 1);
                Mod.call(re, "setAuraSameTickTurn", false);
                Mod.call(re, "save");
                Mod.staticCall(AA_CFG, "load");
                if ((Boolean) Mod.field(Mod.cfg(AA_CFG), "auraSameTickTurn")) {
                    problems.add("Turn And Click Same Tick did not save off");
                }
                return null;
            });
            c.check(problems.isEmpty(), String.join("; ", problems));
        } finally {
            c.onClient(mc -> {
                Object cfg = Mod.cfg(AA_CFG);
                Mod.call(cfg, "setAuraClicksPerTick", was[0]);
                Mod.call(cfg, "setAuraSameTickTurn", was[1]);
                Mod.call(cfg, "save");
                return null;
            });
        }
    }
}

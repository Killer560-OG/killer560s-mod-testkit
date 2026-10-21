package dev.testkit.gametest.ui;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.VideoSim;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.mod.Quiet;
import dev.testkit.harness.VideoCamera;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1916-video-autoclear-flyover: promo footage of Auto Clear clearing a few rooms of a sim F7, seen from a free flyover
 * camera ({@link VideoCamera}, render-side only, the 1913 camera) that circles out and above him and glides after each
 * warp, with the HUD on (the shared Status HUD with its Auto Clear column, the Progress HUD, the Dungeon Map).
 *
 * <p>The mobs are the sim's own live starred mobs ({@code SimLiveMobs}, mod 13def261: {@code -PacVideoMobs} a tile,
 * default 2, of 1 HP, walking; a miniboss in each CHAMPION room), placed by the sim's server at GO, which also turns the
 * sim's clear-on-entry off - so a room clears only when Auto Clear has killed its last starred mob. Auto Clear runs on its
 * own (Any Mob Room, Spirit Sceptre, AOTV - 1020-sim-ac-speed's loadout) from GO until the sim has cleared
 * {@code -PacVideoRooms} rooms (default 12; "[Sim] room cleared: X (every starred mob in it is dead)"), then two seconds
 * more. A room takes Auto Clear 7-50 ticks on these mobs, trip included (2026-10-20: nine rooms in 14 s). {@code -PacCode=<map code>} replays a floor.
 *
 * <p>Recorded with {@link VideoRecorder#tickPerFrame}: one PNG per game tick, so encoded at 20 fps (resampled to 60) the
 * clip plays at 1x; the mod's client tick count over the clip is checked against the frame count. The camera never moves
 * the player: {@link VideoCamera} only changes what the frame is drawn from. He is made invulnerable on the server (the
 * walking mobs hit back; a death would end the clip), nothing else. Night Vision on the server player for the camera only.
 * Only runs when named.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class VideoAutoClearSuite implements FabricClientGameTest {

    static final String NAME = "1916-video-autoclear-flyover";

    private static final String AUTO = "com.killer560.hub.autoclear.AutoClearFeature";
    private static final String AUTO_CONFIG = "com.killer560.hub.autoclear.AutoClearConfig";
    private static final String SIM_RUN = "com.killer560.hub.roomsim.SimRun";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";

    private static final double LIMIT_MS = 150_000;
    private static final Pattern ROOM_TIME = Pattern.compile("\\[AutoClear\\] room time: (.+?) (\\d+) ticks \\((.+?)\\):.*"
            + "kills (\\d+)");
    private static final Pattern SIM_CLEARED = Pattern.compile("\\[Sim\\] room cleared: (.+?) \\(every starred mob in it is dead\\)");

    private static boolean named() {
        String filter = System.getProperty("testkit.scenario", "");
        return !filter.isBlank() && filter.contains("video") && UiCase.selected(NAME);
    }

    private static long clientTick(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> ((Number) ModUnderTest.staticCall(
                "com.killer560.hub.livemap.autoclear.ClearExecutor", "clientTick")).longValue());
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (dev.testkit.harness.ScenarioList.active() || dev.testkit.harness.ModGate.missing() != null) {
            dev.testkit.gametest.Scenario.skip(NAME);
            return;
        }
        if (!named()) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(20);
        ctx.computeOnClient(mc -> {
            if (Quiet.enabled()) {
                Quiet.apply();
            }
            ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled");
            mc.options.bobView().set(false);
            mc.options.gamma().set(1.0);
            mc.options.guiScale().set(3);
            mc.options.fov().set(80);
            mc.options.renderDistance().set(12);
            mc.options.hideGui = false;
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            return null;
        });
        ctx.getInput().resizeWindow(1920, 1080);
        ctx.waitTicks(3);
        ctx.runOnClient(mc -> mc.resizeGui());
        ctx.waitTicks(3);
        if (!VideoSim.prepare(ctx)) {
            dev.testkit.gametest.Scenario.skipped(NAME, "needs real room captures and room database");
            return;
        }
        ctx.runOnClient(mc -> {
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                    "setAutoPuzzlesMasterEnabled", false);
            Object map = ModUnderTest.config("com.killer560.hub.livemap.LiveMapConfig");
            ModUnderTest.set(map, "setEnabled", true);
            // the HUD the clip is for: the shared Status HUD (its Clearing column) and the Progress HUD's room plan
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autosecret.AutoSecretConfig"), "setStatusHud", true);
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autoroutes.AutoRoutesConfig"), "setProgressHud", true);
            ModUnderTest.turnOff("com.killer560.hub.cheatutils.CheatUtilsConfig", "setSecretAuraEnabled");
        });
        try {
            UiCase.run(ctx, NAME, VideoAutoClearSuite::body);
        } finally {
            ctx.runOnClient(mc -> {
                VideoCamera.clear();
                mc.options.hideGui = false;
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                if (mc.level != null) {
                    ModUnderTest.staticCall(AUTO, "cancel");
                }
                ModUnderTest.set(ModUnderTest.config(AUTO_CONFIG), "setEnabled", false);
                Mod.staticCall("gui.MenuAnim", "setClockForTest", -1.0);
            });
            VideoSim.liveMobsOff(ctx);
            if (ctx.computeOnClient(mc -> mc.level != null)) {
                VideoSim.leave(ctx);
            }
        }
    }

    private static void body(UiCase c) throws Exception {
        ClientGameTestContext ctx = c.ctx();
        int perTile = Integer.getInteger("testkit.acVideoMobs", 2);
        int wantRooms = Integer.getInteger("testkit.acVideoRooms", 12);
        String code = System.getProperty("testkit.acCode", "");
        String mapCode = VideoSim.liveMobFloor(ctx, code, perTile, 1.0, true);
        c.check(mapCode != null, "this jar has no SimLiveMobs - the sim has no mobs for Auto Clear to clear");
        if (mapCode == null) {
            return;
        }
        c.note("map code: " + mapCode);
        System.out.println("[" + NAME + "] MAP CODE: " + mapCode);
        giveLoadout(ctx);
        VideoSim.nightVision(ctx);
        long mark = LogTap.mark();
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_RUN, "begin", new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")}));
        for (int i = 0; i < 600 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_RUN, "isRunning")); i++) {
            ctx.waitTicks(1);
        }
        String placed = null;
        for (int i = 0; i < 200 && placed == null; i += 5) {
            ctx.waitTicks(5);
            placed = LogTap.since(mark).stream().filter(l -> l.contains("[Sim] live mobs placed:")).findFirst().orElse(null);
        }
        c.note("sim: " + (placed == null ? "NO live mobs line" : placed.replaceAll("^.*\\[Sim\\]", "[Sim]")));
        System.out.println("[" + NAME + "] " + (placed == null ? "NO live mobs line" : placed.replaceAll("^.*\\[Sim\\]", "[Sim]")));
        c.check(placed != null, "the sim placed no live mobs");
        serverRun(ctx, sp -> {
            sp.getAbilities().invulnerable = true;
            sp.onUpdateAbilities();
        });
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(AUTO_CONFIG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setHyperionHops", false);
            dev.testkit.compat.McCompat.chat(mc).clearMessages(false);
        });
        VideoSim.autoClearMode(ctx, "ANY_MOB_ROOM", "SPIRIT_SCEPTRE");
        ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
        Flyover fly = c.onClient(Flyover::start);
        c.onClient(mc -> fly.step(mc, true));
        ctx.waitTicks(40); // chunks round the camera

        VideoRecorder r = new VideoRecorder(c, "autoclear-flyover").tickPerFrame();
        // counted from before GO: the sim's clear lines are what end the clip
        long runMark = mark;
        long read = LogTap.mark();
        Vec3 last = ctx.computeOnClient(mc -> mc.player.position());
        double travelled = 0;
        double maxAway = 0;
        double minAway = 1e9;
        boolean started = false;
        boolean busySeen = false;
        int cleared = 0;
        int kills = 0;
        Set<String> clearedRooms = new LinkedHashSet<>();
        String end = "(still running)";
        long tick0 = clientTick(ctx);
        try {
            r.hideCursor();
            int lead = 20;
            int limit = (int) Math.round(LIMIT_MS / 50.0);
            int tail = -1;
            for (int i = 0; i < limit; i++) {
                if (i == lead) {
                    r.mark("autoclear-start");
                    ctx.runOnClient(mc -> ModUnderTest.staticCall(AUTO, "start"));
                    started = true;
                }
                double away = c.onClient(mc -> {
                    // chat sits on top of the Dungeon Map in the corner, and the vanilla advancement toasts ("Monster
                    // Hunter") cover the top right: neither is the mod's, both are cleared every frame
                    dev.testkit.compat.McCompat.clearChatAndToasts(mc);
                    return fly.step(mc, false);
                });
                if (i > lead) {
                    maxAway = Math.max(maxAway, away);
                    minAway = Math.min(minAway, away);
                }
                r.frame();
                if (i % 5 == 0) {
                    if (!ctx.computeOnClient(mc -> mc.level != null && mc.player != null)) {
                        c.note("the world went away " + i / 20 + " s in");
                        break;
                    }
                    Vec3 at = ctx.computeOnClient(mc -> mc.player.position());
                    travelled += at.distanceTo(last);
                    last = at;
                    long m2 = LogTap.mark();
                    for (String l : LogTap.since(read)) {
                        Matcher m = ROOM_TIME.matcher(l);
                        if (m.find()) {
                            kills += Integer.parseInt(m.group(4));
                            c.note("room time: " + l.replaceAll("^.*room time: ", ""));
                        }
                        // the sim's own word that a room is cleared: its last starred mob is dead (Auto Clear's own
                        // room-time line says "left at its last death", which is the same moment seen client-side)
                        Matcher s = SIM_CLEARED.matcher(l);
                        if (s.find() && clearedRooms.add(s.group(1))) {
                            cleared++;
                            r.mark("cleared-" + s.group(1).replace(' ', '_'));
                        }
                        if (l.contains("[AutoClear] done: ") || l.contains("[AutoClear] stopped: ")) {
                            end = l.replaceAll("^.*\\[AutoClear\\] ", "");
                        }
                    }
                    read = m2;
                    if (i > lead) {
                        boolean busy = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AUTO, "isBusy"));
                        busySeen |= busy;
                        if (tail < 0 && busySeen && !busy) {
                            r.mark("autoclear-ended");
                            tail = i + 30;
                        }
                    }
                    if (tail < 0 && cleared >= wantRooms) {
                        r.mark("rooms-enough");
                        tail = i + 40;
                    }
                }
                if (tail >= 0 && i >= tail) {
                    break;
                }
            }
        } finally {
            r.close();
            ctx.runOnClient(mc -> {
                VideoCamera.clear();
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                if (mc.level != null) {
                    ModUnderTest.staticCall(AUTO, "cancel");
                }
            });
        }
        long ticks = clientTick(ctx) - tick0;
        int simCleared = 0;
        int corrections = 0;
        for (String l : LogTap.since(runMark)) {
            if (l.contains("[Sim] room cleared:")) {
                simCleared++;
                c.note("sim: " + l.replaceAll("^.*\\[Sim\\] ", ""));
            }
            if (l.contains("[Correction]")) {
                corrections++;
            }
        }
        String result = String.format(Locale.ROOT, "RESULT frames %d (%.1f s at 20 fps), client ticks %d, Auto Clear rooms "
                        + "cleared %d %s, kills %d, sim rooms cleared %d, travelled %.0f blocks, camera %.1f-%.1f blocks from his"
                        + " eyes, camera cuts %d, frames with a block between camera and his eyes %d, corrections %d, end \"%s\"",
                r.frames(), r.frames() / 20.0, ticks, cleared, clearedRooms, kills, simCleared, travelled, minAway, maxAway,
                fly.cuts, fly.hidden, corrections, end);
        c.note(result);
        System.out.println("[" + NAME + "] " + result);
        c.check(started, "Auto Clear was never started");
        c.check(r.frames() > 200, "only " + r.frames() + " frames recorded");
        c.check(Math.abs(ticks - r.frames()) <= r.frames() / 50 + 5, "the clip is not one tick per frame: " + ticks
                + " client ticks for " + r.frames() + " frames - it would not play at real speed");
        c.check(kills > 0 && cleared > 0, "Auto Clear killed nothing / cleared no room (kills " + kills + ", rooms "
                + cleared + ")");
        c.check(simCleared > 0, "the sim cleared no room");
        c.check(travelled > 20, "he barely moved (" + travelled + " blocks)");
        c.check(maxAway > 3, "the flyover camera never left him (max " + maxAway + ")");
        c.check(fly.hidden <= r.frames() / 10, fly.hidden + " of " + r.frames() + " frames have a block between the camera"
                + " and him");
    }

    /**
     * A camera high over his shoulder looking down into the room (the sim's rooms are open to the sky, as 1911's flyover
     * shows), circling slowly and gliding after him. Take 1 used 1913's low orbit; in these rooms it sat against walls and
     * lost him behind them for a second after each warp, and pulling it in along his line (take 2) only put it inside
     * walls. So: each frame the spot it wants is the first of a ranked list (its current bearing, then bearings either side,
     * each at a steep then a shallower elevation) whose line to his eyes is clear for the full distance; the camera glides
     * to it, and is cut straight there when the glide's own line to him is blocked (a cut beats a frame of wall). One step a
     * game tick (the recorder's tick-per-frame mode). Render-side only ({@link VideoCamera}).
     */
    private static final class Flyover {
        double tx;
        double ty;
        double tz;
        double angle;
        Vec3 cam;
        int cuts;
        int hidden;
        /** 15 (take B) showed the rooms well but left him small; 12 keeps the room and makes him readable. */
        static final double DIST = 12.0;
        static final double[] ELEVATIONS = {62, 52, 72, 42, 80};
        /** Degrees a frame: 9 a second at 20 frames a second. */
        static final double TURN_PER_FRAME = 0.45;
        /** Share of the gap to him the look point closes a frame, and the camera's own glide. */
        static final double LOOK_FOLLOW = 0.6;
        static final double CAM_FOLLOW = 0.22;

        static Flyover start(Minecraft mc) {
            Flyover o = new Flyover();
            Vec3 p = mc.player.position();
            o.tx = p.x;
            o.ty = p.y;
            o.tz = p.z;
            o.angle = mc.player.getYRot() + 180; // behind him to start
            return o;
        }

        private static Vec3 dir(double angleDeg, double elevDeg) {
            double a = Math.toRadians(angleDeg);
            double e = Math.toRadians(elevDeg);
            return new Vec3(-Math.sin(a) * Math.cos(e), Math.sin(e), Math.cos(a) * Math.cos(e));
        }

        /** How far along {@code d} from {@code from} is clear (a 0.8 margin off the first block), up to {@code want}. */
        private static double clear(Minecraft mc, Vec3 from, Vec3 d, double want) {
            Vec3 to = from.add(d.scale(want + 0.8));
            // fluids stop it too: runs 1 and 2 of 2026-10-20 had 13 and 10 frames from inside a room's water (all blue)
            var hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.ANY, mc.player));
            if (hit.getType() == HitResult.Type.MISS) {
                return want;
            }
            return Math.max(0, Math.min(want, hit.getLocation().distanceTo(from) - 0.8));
        }

        private static boolean sees(Minecraft mc, Vec3 cam, Vec3 eye) {
            var hit = mc.level.clip(new ClipContext(cam, eye, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
            return hit.getType() == HitResult.Type.MISS;
        }

        /** The spot it wants this frame, and its bearing (written to {@link #angle} when it moves to another one). */
        private Vec3 want(Minecraft mc, Vec3 eye) {
            double bestD = -1;
            Vec3 best = null;
            double bestA = angle;
            for (int k = 0; k <= 12; k++) {
                double off = k == 0 ? 0 : ((k + 1) / 2) * 30.0 * (k % 2 == 1 ? 1 : -1);
                for (double el : ELEVATIONS) {
                    Vec3 d = dir(angle + off, el);
                    double free = clear(mc, eye, d, DIST);
                    if (free >= DIST - 0.01) {
                        angle += off;
                        return eye.add(d.scale(free));
                    }
                    if (free > bestD) {
                        bestD = free;
                        best = eye.add(d.scale(free));
                        bestA = angle + off;
                    }
                }
            }
            // nowhere has the full distance (a covered corridor): the longest clear line there is
            angle = bestA;
            return best;
        }

        /** One frame: follow him, turn, place the camera; returns its distance from his eyes. Client thread. */
        double step(Minecraft mc, boolean snap) {
            Vec3 p = mc.player.position();
            Vec3 his = mc.player.getEyePosition();
            double k = snap ? 1.0 : LOOK_FOLLOW;
            tx += (p.x - tx) * k;
            ty += (p.y - ty) * k;
            tz += (p.z - tz) * k;
            if (!snap) {
                angle += TURN_PER_FRAME;
            }
            Vec3 target = want(mc, his);
            if (snap || cam == null) {
                cam = target;
            } else {
                Vec3 next = cam.add(target.subtract(cam).scale(CAM_FOLLOW));
                if (sees(mc, next, his) && mc.level.getFluidState(BlockPos.containing(next)).isEmpty()) {
                    cam = next;
                } else {
                    // a cut: the look point cuts with it, so the first frame after it is not aimed at where he was
                    cam = target;
                    cuts++;
                    tx = p.x;
                    ty = p.y;
                    tz = p.z;
                }
            }
            if (!sees(mc, cam, his)) {
                hidden++;
            }
            Vec3 look = new Vec3(tx, ty + 1.2, tz).subtract(cam);
            float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
            float pitch = (float) -Math.toDegrees(Math.atan2(look.y, Math.hypot(look.x, look.z)));
            VideoCamera.set(cam.x, cam.y, cam.z, yaw, pitch);
            return cam.distanceTo(his);
        }
    }

    /** AOTV in slot 0 and the Spirit Sceptre in slot 1 (1020-sim-ac-speed's loadout). */
    private static void giveLoadout(ClientGameTestContext ctx) {
        serverRun(ctx, sp -> {
            var inv = sp.getInventory();
            for (int i = 0; i < 36; i++) {
                inv.setItem(i, ItemStack.EMPTY);
            }
            String[] ids = {"ASPECT_OF_THE_VOID", "BAT_WAND"};
            for (int i = 0; i < ids.length; i++) {
                inv.setItem(i, (ItemStack) ModUnderTest.staticCall(SIM_ITEMS, "build", new Class<?>[]{String.class},
                        new Object[]{ids[i]}));
            }
        });
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
        ctx.waitTicks(5);
    }

    private static void serverRun(ClientGameTestContext ctx, java.util.function.Consumer<net.minecraft.server.level.ServerPlayer> task) {
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                try {
                    var sp = server.getPlayerList().getPlayer(uuid);
                    if (sp != null) {
                        task.accept(sp);
                    }
                } finally {
                    done.set(true);
                }
            });
        });
        ctx.waitFor(mc -> done.get() != null, 200);
        ctx.waitTicks(3);
    }
}

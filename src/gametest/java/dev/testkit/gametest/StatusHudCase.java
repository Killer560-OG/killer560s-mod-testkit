package dev.testkit.gametest;

import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 147-sim-autostatus-hud (mod autoclear-hud-1015, killer560 2026-10-15: "make it the same hud for all the auto clears. Make
 * it far shorter and have one side that says clearing and one for secreting if it is doing both"). Runs inside
 * {@link SimAutopilotTests}' world setup: a generated sim F7 with one uncleared mob room (a starred zombie) and up to two
 * secret rooms with seeded one-node routes, Dungeon Autopilot in Solo with Auto Clear on (Hyperion).
 * <p>
 * Every 5 ticks of the run it reads the shared Status HUD ({@code autosecret.AutoStatusHud}): what it shows, its box, the
 * drawn scale, the Dungeon Map's drawn box and whether the HUD really drew. It must:
 * <ul>
 *   <li>show NOTHING before the run starts and again once the run's "Stopped" line has gone (7 s after it ends);</li>
 *   <li>show both columns, "Clearing" left and "Secreting" right, while Autopilot runs;</li>
 *   <li>change what it shows as the work moves between rooms (two or more different rooms named);</li>
 *   <li>stay under a third of the window's width, every sample (the old Autopilot line ran across the whole screen);</li>
 *   <li>never share a pixel with the Dungeon Map while both drew;</li>
 *   <li>actually draw in game (its HudSeen stamp), and the old Autopilot Status / Auto Clear Status elements must be gone.</li>
 * </ul>
 * On a jar from before it the HUD class is missing and the case fails on the first read; it still takes the "before"
 * screenshot of the old status lines. Screenshots: {@code <case>-before} / {@code <case>-after} in the report's screens.
 */
final class StatusHudCase {

    private static final String NAME = SimAutopilotTests.HUD_CASE;
    private static final String HUD = "com.killer560.hub.autosecret.AutoStatusHud";
    private static final String REG = "com.killer560.hub.hud.HudElementRegistry";
    private static final String ELEMENT = "com.killer560.hub.hud.HudElement";
    private static final String SEEN = "com.killer560.hub.hud.HudSeen";
    private static final String AS = "com.killer560.hub.autosecret.AutoSecretFeature";
    private static final String AS_CONFIG = "com.killer560.hub.autosecret.AutoSecretConfig";
    private static final String AUTO_CLEAR_CONFIG = "com.killer560.hub.autoclear.AutoClearConfig";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String SIM_ROOM_STATE = "com.killer560.hub.roomsim.SimRoomState";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final int RUN_TICKS = 20 * 180;

    private StatusHudCase() {
    }

    /** One sample of the HUD. */
    private record Sample(int tick, String[] left, String[] right, int w, int h, double drawnPx, int windowPx,
                          int[] box, int[] mapBox, long seenMs, long mapSeenMs) {
    }

    static void run(ClientGameTestContext ctx, List<String> failures) {
        // ---- a floor with an uncleared mob room and secret rooms ---------------------------------------------------
        SimAutopilotTests.Room mobRoom = null;
        List<SimAutopilotTests.Status> secretRooms = new ArrayList<>();
        List<SimAutopilotTests.Room> rooms = null;
        for (int attempt = 1; attempt <= 4 && (mobRoom == null || secretRooms.isEmpty()); attempt++) {
            long before = Scenario.simBuildCount(ctx);
            ctx.runOnClient(mc -> mc.execute(() -> {
                Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
                ModUnderTest.staticCall(FLOOR_GEN, "generate",
                        new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                        new Object[]{mc, floor, 3, 4});
            }));
            ctx.waitFor(mc -> mc.level != null);
            Scenario.awaitSimBuild(ctx, before);
            ctx.waitTicks(80);
            SimAutopilotTests.awaitFloorChunks(ctx);
            rooms = SimAutopilotTests.floor(ctx);
            Vec3 me = ctx.computeOnClient(mc -> mc.player.position());
            mobRoom = null;
            for (SimAutopilotTests.Room r : rooms) {
                if (r.mob() && !r.cleared() && !SimAutopilotTests.strands(r.name())
                        && (mobRoom == null || SimAutopilotTests.dist(me, r) < SimAutopilotTests.dist(me, mobRoom))) {
                    mobRoom = r;
                }
            }
            secretRooms.clear();
            for (SimAutopilotTests.Status s : ctx.computeOnClient(SimAutopilotTests::readStatus)) {
                if (s.unfound() > 0 && (mobRoom == null || !s.name().equals(mobRoom.name())) && !s.name().equals("Unknown")
                        && !SimAutopilotTests.strands(s.name())
                        && !List.of("PUZZLE", "TRAP", "BLOOD", "ENTRANCE").contains(s.type()) && secretRooms.size() < 2) {
                    secretRooms.add(s);
                }
            }
            println("floor " + attempt + ": mob room " + (mobRoom == null ? "none" : mobRoom.name()) + ", secret rooms "
                    + secretRooms.stream().map(SimAutopilotTests.Status::name).toList());
        }
        if (mobRoom == null || secretRooms.isEmpty()) {
            failures.add("hud: no generated floor had an uncleared mob room and a secret room");
            return;
        }
        SimAutopilotTests.Room clearRoom = mobRoom;

        // ---- loadout, the run, the other mob rooms cleared, routes, the zombie ---------------------------------
        AtomicReference<Boolean> given = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp == null) {
                    given.set(false);
                    return;
                }
                var inv = sp.getInventory();
                for (int i = 0; i < 36; i++) {
                    inv.setItem(i, ItemStack.EMPTY);
                }
                String[] ids = {"ASPECT_OF_THE_VOID", "HYPERION", "BAT_WAND"};
                for (int i = 0; i < ids.length; i++) {
                    inv.setItem(i, (ItemStack) ModUnderTest.staticCall(SIM_ITEMS, "build", new Class<?>[]{String.class},
                            new Object[]{ids[i]}));
                }
                given.set(true);
            });
        });
        ctx.waitFor(mc -> given.get() != null, 200);
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "begin",
                new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")}));
        for (int i = 0; i < 400 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomsim.SimRun", "isRunning")); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(40);
        SimAutopilotTests.ground(ctx);
        for (SimAutopilotTests.Room r : rooms) {
            if (r.mob() && !r.name().equals(clearRoom.name())) {
                ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ROOM_STATE, "markCleared",
                        new Class<?>[]{String.class}, new Object[]{r.name()}));
            }
        }
        Map<String, JsonObject> routes = new HashMap<>();
        for (SimAutopilotTests.Status s : secretRooms) {
            JsonObject route = SimAutopilotTests.routeFor(ctx, s);
            if (route != null) {
                routes.put(s.name(), route);
            }
        }
        SimAutopilotTests.writeRoutes(ctx, routes);
        println("routes seeded: " + routes.keySet() + "; clear room " + clearRoom.name());
        if (routes.isEmpty()) {
            failures.add("hud: no route could be seeded");
            return;
        }
        if (SimAutopilotTests.spawnOne(ctx, clearRoom) == null) {
            failures.add("hud: could not place a starred zombie in " + clearRoom.name());
            return;
        }
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(AUTO_CLEAR_CONFIG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setHyperionHops", true);
            Object weapon = ModUnderTest.enumValue(AUTO_CLEAR_CONFIG + "$Weapon", "HYPERION");
            ModUnderTest.call(cfg, "setWeapon", new Class<?>[]{weapon.getClass()}, new Object[]{weapon});
            SimAutopilotTests.setStatusHud(ModUnderTest.config(AS_CONFIG));
        });
        SimAutopilotTests.setMode(ctx, "SOLO", false);

        // ---- idle: nothing --------------------------------------------------------------------------------------
        boolean hasHud = ctx.computeOnClient(mc -> {
            try {
                Class.forName(HUD);
                return true;
            } catch (ClassNotFoundException e) {
                return false;
            }
        });
        if (!hasHud) {
            failures.add("hud: the jar has no shared Status HUD (" + HUD + ")");
        }
        if (hasHud) {
            ctx.waitTicks(5);
            List<String> idle = shown(ctx);
            println("idle before the run: " + idle);
            if (!idle.isEmpty()) {
                failures.add("hud: shows " + idle + " before anything runs");
            }
            boolean oldGone = ctx.computeOnClient(mc -> ModUnderTest.staticCall(REG, "byId", new Class<?>[]{String.class},
                    new Object[]{"autopilot_status"}) == null && ModUnderTest.staticCall(REG, "byId",
                    new Class<?>[]{String.class}, new Object[]{"auto_clear_status"}) == null);
            if (!oldGone) {
                failures.add("hud: Autopilot Status / Auto Clear Status are still registered - the status would show twice");
            }
        }

        // ---- the run --------------------------------------------------------------------------------------------
        Boolean started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "startAutopilot"));
        if (!Boolean.TRUE.equals(started)) {
            failures.add("hud: Autopilot refused to start");
            return;
        }
        List<Sample> samples = new ArrayList<>();
        boolean shotTaken = false;
        Vec3 last = ctx.computeOnClient(mc -> mc.player.position());
        double travelled = 0;
        boolean finished = false;
        for (int t = 0; t < RUN_TICKS; t += 5) {
            ctx.waitTicks(5);
            Vec3 now = ctx.computeOnClient(mc -> mc.player.position());
            travelled += now.distanceTo(last);
            last = now;
            if (hasHud) {
                Sample s = sample(ctx, t + 5);
                samples.add(s);
                if (!shotTaken && s.left() != null && s.right() != null && s.left().length > 1 && !"-".equals(s.left()[1])
                        && s.seenMs() < 500) {
                    shot(ctx, "after");
                    shotTaken = true;
                }
                if (t % 100 == 95) {
                    println("t=" + (t + 5) / 20 + "s: " + describe(s));
                }
            } else if (!shotTaken && t >= 60) {
                shot(ctx, "before");
                shotTaken = true;
            }
            if (!(Boolean) ctx.computeOnClient(mc -> ModUnderTest.staticCall(AS, "isRunning"))) {
                finished = true;
                break;
            }
        }
        if (!finished) {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(AS, "stop", new Class<?>[]{String.class, boolean.class},
                    new Object[]{"test timeout", true}));
        }
        println(String.format(Locale.US, "run: %s, travelled %.1f blocks, %d sample(s)", finished ? "ended by itself"
                : "timed out", travelled, samples.size()));
        if (travelled < 10) {
            failures.add(String.format(Locale.US, "hud: the run travelled only %.1f blocks - nothing happened", travelled));
        }
        if (!hasHud) {
            return;
        }

        // ---- judged -------------------------------------------------------------------------------------------
        int both = 0;
        int drew = 0;
        double widest = 0;
        Set<String> roomsShown = new LinkedHashSet<>();
        for (Sample s : samples) {
            if (s.left() == null && s.right() == null) {
                continue;
            }
            if (s.seenMs() < 500) {
                drew++;
            }
            if (s.left() != null && s.right() != null) {
                both++;
                if (!"Clearing".equals(s.left()[0]) || !"Secreting".equals(s.right()[0])) {
                    failures.add("hud: columns are not Clearing | Secreting: " + describe(s));
                    break;
                }
            }
            for (String[] col : new String[][]{s.left(), s.right()}) {
                if (col != null && col.length > 1 && !col[1].equals("-") && !col[1].startsWith("next: ")) {
                    roomsShown.add(col[1]);
                }
            }
            if (s.drawnPx() > widest) {
                widest = s.drawnPx();
            }
            if (s.drawnPx() * 3 > s.windowPx()) {
                failures.add(String.format(Locale.US, "hud: %.0f px wide in a %d px window (over a third): %s", s.drawnPx(),
                        s.windowPx(), describe(s)));
                break;
            }
            if (s.mapBox() != null && s.mapSeenMs() < 500 && s.seenMs() < 500 && intersects(s.box(), s.mapBox())) {
                failures.add("hud: drawn over the Dungeon Map: hud " + java.util.Arrays.toString(s.box()) + ", map "
                        + java.util.Arrays.toString(s.mapBox()));
                break;
            }
        }
        println("judged: " + both + " sample(s) with both columns, " + drew + " drawn in game, rooms named " + roomsShown
                + String.format(Locale.US, ", widest %.0f px", widest));
        if (both == 0) {
            failures.add("hud: never showed the Clearing and Secreting columns side by side while Autopilot ran");
        }
        if (drew == 0) {
            failures.add("hud: never drew in game (no HudSeen stamp while it had something to show)");
        }
        if (roomsShown.size() < 2) {
            failures.add("hud: named only " + roomsShown + " - it does not follow the run from room to room");
        }

        // ---- after: the stop line, then nothing ------------------------------------------------------------------
        ctx.waitTicks(20 * 7);
        List<String> after = shown(ctx);
        println("7 s after the run: " + after);
        if (!after.isEmpty()) {
            failures.add("hud: still shows " + after + " 7 s after the run ended");
        }
    }

    // ------------------------------------------------------------------------------------------------- reading

    @SuppressWarnings("unchecked")
    private static List<String> shown(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> new ArrayList<>((List<String>) ModUnderTest.staticCall(HUD, "shownText")));
    }

    private static Sample sample(ClientGameTestContext ctx, int tick) {
        return ctx.computeOnClient(mc -> {
            String[][] cols = (String[][]) ModUnderTest.staticCall(HUD, "shownColumns");
            int[] size = (int[]) ModUnderTest.staticCall(HUD, "shownSize");
            Object el = ModUnderTest.staticCall(REG, "byId", new Class<?>[]{String.class}, new Object[]{"auto_status"});
            Object map = ModUnderTest.staticCall(REG, "byId", new Class<?>[]{String.class}, new Object[]{"live_map"});
            double gui = mc.getWindow().getGuiScale();
            int[] box = el == null ? null : box(el);
            float scale = el == null ? 1f : scale(el);
            long now = System.currentTimeMillis();
            long seen = seen("auto_status", now);
            long mapSeen = map == null ? Long.MAX_VALUE : seen("live_map", now);
            return new Sample(tick, cols[0] == null ? null : cols[0].clone(), cols[1] == null ? null : cols[1].clone(),
                    size[0], size[1], size[0] * scale * gui, mc.getWindow().getWidth(), box, map == null ? null : box(map),
                    seen, mapSeen);
        });
    }

    private static long seen(String id, long now) {
        return ((Number) ModUnderTest.staticCall(SEEN, "msSince", new Class<?>[]{String.class, long.class},
                new Object[]{id, now})).longValue();
    }

    private static float scale(Object element) {
        try {
            Class<?> type = Class.forName(ELEMENT);
            return ((Number) ModUnderTest.staticCall(REG, "resolveScale", new Class<?>[]{type}, new Object[]{element}))
                    .floatValue();
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    /** {x0, y0, x1, y1} of the element as drawn, in GUI units. */
    private static int[] box(Object element) {
        try {
            Class<?> type = Class.forName(ELEMENT);
            int[] pos = (int[]) ModUnderTest.staticCall(REG, "resolvePosition", new Class<?>[]{type}, new Object[]{element});
            float s = scale(element);
            int w = (Integer) type.getMethod("width").invoke(element);
            int h = (Integer) type.getMethod("height").invoke(element);
            return new int[]{pos[0], pos[1], pos[0] + Math.round(w * s), pos[1] + Math.round(h * s)};
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean intersects(int[] a, int[] b) {
        return a != null && b != null && a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3];
    }

    private static String describe(Sample s) {
        return "t" + s.tick() / 20 + "s " + (s.left() == null ? "-" : String.join(" / ", s.left())) + " || "
                + (s.right() == null ? "-" : String.join(" / ", s.right())) + String.format(Locale.US,
                " [%dx%d units, %.0f of %d px]", s.w(), s.h(), s.drawnPx(), s.windowPx());
    }

    private static void shot(ClientGameTestContext ctx, String tag) {
        try {
            // The run's chat lines cover the left of the small test window, where the status HUD sits.
            ctx.runOnClient(dev.testkit.compat.McCompat::clearChatAndToasts);
            ctx.waitTicks(1);
            Path p = ctx.takeScreenshot(dev.testkit.harness.Report.fileName(NAME + "-" + tag));
            Path dir = dev.testkit.harness.Report.dir().resolve("screens");
            Files.createDirectories(dir);
            Path to = dir.resolve(NAME + "-" + tag + ".png");
            Files.copy(p, to, StandardCopyOption.REPLACE_EXISTING);
            println("screenshot " + tag + ": " + to);
        } catch (Exception e) {
            println("screenshot " + tag + " failed: " + e);
        }
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}

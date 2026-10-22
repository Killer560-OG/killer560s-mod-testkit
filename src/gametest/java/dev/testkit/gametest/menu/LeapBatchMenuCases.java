package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * killer560's 2026-10-11 leap-menu batch, the real-menu half (mod branch leap-2; the editor half is
 * {@code ui/LeapWizardCases}). Every case drives a real Spirit Leap chest the Hx server opens, with four heads (LoAnna 11,
 * LoBen 13, LoCara 15, LoDev 17).
 * <ul>
 *   <li>821: Show Player Heads draws a face in every box: a frame with it ON against one with it OFF differs by thousands
 *       of pixels, and each spot still leaps (key 1 clicks the head the box shows).</li>
 *   <li>822: Leap Style "Map in Clear": in the clear the leap menu draws the map with the four teammates' faces where
 *       they stand (client-side players placed in four rooms); a real click on LoCara's face makes the server see a click
 *       on slot 15. In boss ({@code DungeonState} sim override) the same menu draws the boxes and key 1 leaps.</li>
 *   <li>823: the delay: Spirit Leap right-clicked for real, the server opens the menu; the open-window time (the screen
 *       being created, measured here) against when the mod is ready ({@code lastOpenToReadyMs}) and when a 1 key pressed
 *       every tick first leaps. Run on an old jar too it gives the before number (the old settle window).</li>
 *   <li>824: no carried head: with a head forced onto the cursor every tick, the leap menu draws nothing at the cursor
 *       (pixel diff against a no-carried frame), while a plain chest with the same carried head does (control).</li>
 *   <li>825: frozen layout: a click whose head the server then takes out of the menu without closing it (as Hypixel
 *       does until the leap lands) leaves every name in its spot for the next 20 ticks; the menu really did lose that
 *       head (control).</li>
 * </ul>
 */
final class LeapBatchMenuCases {

    private static final String CFG = "spiritleap.SpiritLeapOverlayConfig";
    private static final String FEAT = "spiritleap.SpiritLeapOverlayFeature";
    private static final String LEAPCFG = "leapmenu.LeapMenuConfig";
    static final List<String> PARTY = List.of("LoAnna", "LoBen", "LoCara", "LoDev");
    static final Map<String, Integer> SLOT = Map.of("LoAnna", 11, "LoBen", 13, "LoCara", 15, "LoDev", 17);
    private static final int ENTITY_BASE = 820_100;

    private LeapBatchMenuCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "821-menu-leap-heads", LeapBatchMenuCases::heads);
        MenuSuite.test(s, "822-menu-leap-map-clear", LeapBatchMenuCases::mapClear);
        MenuSuite.test(s, "823-menu-leap-open-delay", LeapBatchMenuCases::openDelay);
        MenuSuite.test(s, "824-menu-leap-no-carried", LeapBatchMenuCases::noCarried);
        MenuSuite.test(s, "825-menu-leap-freeze", LeapBatchMenuCases::freeze);
    }

    // ---- fixture ------------------------------------------------------------------------------------------------------

    /** A Spirit Leap menu with the four heads; {@code onClick} is each head's action ("close", "keep" or "take"). */
    static JsonObject spec(String onClick) {
        JsonObject spec = MenuKit.menu("menus.spirit-leap");
        JsonObject slots = new JsonObject();
        JsonObject on = new JsonObject();
        for (String n : PARTY) {
            JsonObject head = new JsonObject();
            head.addProperty("item", "minecraft:player_head");
            head.addProperty("name", "§a" + n);
            slots.add(String.valueOf(SLOT.get(n)), head);
            JsonObject action = new JsonObject();
            switch (onClick) {
                case "close" -> action.addProperty("close", true);
                case "take" -> {
                    JsonObject set = new JsonObject();
                    set.addProperty(String.valueOf(SLOT.get(n)), "minecraft:air");
                    action.add("set", set);
                }
                default -> action.addProperty("chat", "kept");
            }
            on.add(String.valueOf(SLOT.get(n)), action);
        }
        spec.add("slots", slots);
        spec.add("on", on);
        return spec;
    }

    /** Saved state of the leap config file and the party tracker, put back by {@link #restore}. */
    static final class Saved {
        Path path;
        byte[] bytes;
        List<String> members;
    }

    @SuppressWarnings("unchecked")
    static Saved setUp(Session c) throws Exception {
        Saved sv = new Saved();
        sv.path = (Path) Mod.field(LEAPCFG, "CONFIG_PATH");
        sv.bytes = Files.exists(sv.path) ? Files.readAllBytes(sv.path) : null;
        sv.members = c.onClient(mc -> {
            Collection<String> m = (Collection<String>) Mod.field("leapmenu.PartyTracker", "MEMBERS");
            List<String> was = new ArrayList<>(m);
            m.clear();
            return was;
        });
        c.onClient(mc -> {
            Object archer = Mod.enumValue("dungeonclass.DungeonClass", "ARCHER");
            Mod.call(Mod.cfg(LEAPCFG), "clearClassOrder", archer);
            Mod.call(Mod.cfg(LEAPCFG), "setLastEditedClass", archer);
            Mod.call(Mod.cfg(LEAPCFG), "setDefaultOrder", Mod.enumValue("leapmenu.LeapOrderMode", "HYPIXEL"));
            return null;
        });
        return sv;
    }

    @SuppressWarnings("unchecked")
    static void restore(Session c, Saved sv) {
        c.onClient(mc -> {
            Collection<String> m = (Collection<String>) Mod.field("leapmenu.PartyTracker", "MEMBERS");
            m.clear();
            m.addAll(sv.members);
            try {
                if (sv.bytes != null) {
                    Files.write(sv.path, sv.bytes);
                } else {
                    Files.deleteIfExists(sv.path);
                }
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            Mod.staticCall(LEAPCFG, "load");
            return null;
        });
    }

    static void open(Session c, JsonObject spec) {
        MenuKit.show(c, spec);
        MenuKit.awaitScreen(c, "Spirit Leap", 100);
        c.waitUntil("the overlay to hide the vanilla menu", mc ->
                (Boolean) Mod.staticCall(FEAT, "isHiding", McCompat.screen(mc)), 100);
        c.ctx().waitTicks(4);
    }

    /** GUI coordinates to window pixels (the leap menu is a vanilla chest screen: plain GUI scale). */
    static void cursorAt(Session c, double gx, double gy) {
        double[] w = c.onClient(mc -> new double[]{
                gx * mc.getWindow().getScreenWidth() / (double) mc.getWindow().getGuiScaledWidth(),
                gy * mc.getWindow().getScreenHeight() / (double) mc.getWindow().getGuiScaledHeight()});
        c.ctx().getInput().setCursorPos(w[0], w[1]);
        c.ctx().waitTicks(2);
    }

    static int gui2(Session c) {
        int old = c.onClient(mc -> mc.options.guiScale().get());
        c.onClient(mc -> {
            mc.options.guiScale().set(2);
            mc.resizeGui();
            return null;
        });
        c.ctx().waitTicks(3);
        return old;
    }

    static void guiBack(Session c, int old) {
        c.onClient(mc -> {
            mc.options.guiScale().set(old);
            mc.resizeGui();
            return null;
        });
    }

    static BufferedImage shot(Session c, String label) throws Exception {
        c.onClient(mc -> {
            McCompat.clearChatAndToasts(mc);
            return null;
        });
        c.ctx().waitTicks(3);
        String name = c.name() + "-" + label;
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        dev.testkit.harness.Report.screenshot(name, taken);
        String dir = System.getenv("TESTKIT_LEAP2_SHOTS");
        if (dir != null && !dir.isBlank()) {
            Path out = Path.of(dir).resolve(name + "-" + (MenuKit.cheat() ? "cheat" : "legit") + ".png");
            Files.createDirectories(out.getParent());
            Files.copy(taken, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        c.note("picture " + name);
        return javax.imageio.ImageIO.read(taken.toFile());
    }

    /** Pixels whose summed RGB differs by more than 30 inside {@code box} (window px; null = whole image). */
    static int diff(BufferedImage a, BufferedImage b, int[] box) {
        int x0 = box == null ? 0 : Math.max(0, box[0]);
        int y0 = box == null ? 0 : Math.max(0, box[1]);
        int x1 = box == null ? Math.min(a.getWidth(), b.getWidth()) : Math.min(Math.min(a.getWidth(), b.getWidth()), box[2]);
        int y1 = box == null ? Math.min(a.getHeight(), b.getHeight()) : Math.min(Math.min(a.getHeight(), b.getHeight()), box[3]);
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int p = a.getRGB(x, y);
                int q = b.getRGB(x, y);
                int d = Math.abs((p >> 16 & 255) - (q >> 16 & 255)) + Math.abs((p >> 8 & 255) - (q >> 8 & 255))
                        + Math.abs((p & 255) - (q & 255));
                if (d > 30) {
                    n++;
                }
            }
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    static List<String> drawnSpots(Session c) {
        return c.onClient(mc -> List.copyOf((List<String>) Mod.staticCall(FEAT, "lastDrawnSpots")));
    }

    static int pressKeyAndAwait(Session c, int spot) {
        int before = c.events("menu.action").size();
        c.ctx().getInput().pressKey(49 + spot);
        c.waitUntil("a menu.action for key " + (spot + 1), mc -> c.events("menu.action").size() > before, 60);
        List<JsonObject> e = c.events("menu.action");
        return e.get(e.size() - 1).get("slot").getAsInt();
    }

    // ---- 821 --------------------------------------------------------------------------------------------------------

    static void heads(Session c) throws Exception {
        MenuKit.reset(c);
        Saved sv = setUp(c);
        int gui = gui2(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CFG, "Enabled", true).set(CFG, "ShowHeads", false);
            open(c, spec("keep"));
            cursorAt(c, 2, 2);
            List<String> spots = drawnSpots(c);
            c.check(spots.equals(PARTY), "the boxes show " + spots + ", want " + PARTY);
            BufferedImage off = shot(c, "heads-off");
            c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setShowHeads", true);
                return null;
            });
            BufferedImage on = shot(c, "heads-on");
            int changed = diff(off, on, null);
            c.check(changed > 4 * 400, "Show Player Heads changed only " + changed + " pixel(s) - no faces drawn");
            int slot = pressKeyAndAwait(c, 2);
            c.check(slot == SLOT.get(spots.get(2)), "key 3 clicked slot " + slot + ", the box shows " + spots.get(2));
            c.note("Show Player Heads: " + changed + " pixel(s) differ between OFF and ON (4 faces); key 3 -> slot " + slot);
        } finally {
            guiBack(c, gui);
            MenuKit.reset(c);
            restore(c, sv);
        }
    }

    // ---- 822 --------------------------------------------------------------------------------------------------------

    static double[] room(int col, int row) {
        return new double[]{-185 + 16 + 32 * col, -185 + 16 + 32 * row};
    }

    static void spawnMates(Minecraft mc, List<double[]> at) {
        for (int i = 0; i < PARTY.size(); i++) {
            int id = ENTITY_BASE + i;
            Entity existing = mc.level.getEntity(id);
            if (existing != null) {
                existing.setPos(at.get(i)[0], 70, at.get(i)[1]);
                continue;
            }
            // Random UUIDs are version 4, which is what the map's teammate filter accepts.
            RemotePlayer p = new RemotePlayer(mc.level, new GameProfile(UUID.randomUUID(), PARTY.get(i)));
            p.setId(id);
            p.setPos(at.get(i)[0], 70, at.get(i)[1]);
            mc.level.addEntity(p);
        }
    }

    static void removeMates(Minecraft mc) {
        for (int i = 0; i < PARTY.size(); i++) {
            if (mc.level != null && mc.level.getEntity(ENTITY_BASE + i) != null) {
                mc.level.removeEntity(ENTITY_BASE + i, Entity.RemovalReason.DISCARDED);
            }
        }
    }

    @SuppressWarnings("unchecked")
    static void mapClear(Session c) throws Exception {
        MenuKit.reset(c);
        Saved sv = setUp(c);
        int gui = gui2(c);
        boolean overrideOn = false;
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CFG, "Enabled", true).set(CFG, "ShowHeads", true)
                    .set(CFG, "Style", Mod.enumValue(CFG + "$LeapStyle", "MAP_IN_CLEAR"));
            c.onClient(mc -> {
                Mod.staticCall("leapmenu.PartyTracker", "noteTeammates", PARTY);
                Mod.staticCall("secrets.DungeonState", "setRoomSim", true);
                spawnMates(mc, List.of(room(0, 0), room(4, 1), room(1, 4), room(5, 5)));
                return null;
            });
            c.ctx().waitTicks(3);
            boolean clear = c.onClient(mc -> (Boolean) Mod.staticCall("secrets.DungeonState", "isInDungeon")
                    && !(Boolean) Mod.staticCall("secrets.DungeonState", "isBossPhaseActive"));
            c.check(clear, "not in a dungeon clear after setRoomSim(true)");
            open(c, spec("close"));
            c.waitUntil("the map view with 4 faces", mc -> "MAP".equals(Mod.staticCall(FEAT, "lastView"))
                    && ((List<?>) Mod.staticCall(FEAT, "lastHeads")).size() == 4, 60);
            List<Object> heads = c.onClient(mc -> List.copyOf((List<Object>) Mod.staticCall(FEAT, "lastHeads")));
            List<String> names = new ArrayList<>();
            int[] cara = null;
            for (Object h : heads) {
                String n = (String) Mod.call(h, "name");
                names.add(n);
                int[] box = {(Integer) Mod.call(h, "x0"), (Integer) Mod.call(h, "y0"), (Integer) Mod.call(h, "x1"),
                        (Integer) Mod.call(h, "y1")};
                if (n.equals("LoCara")) {
                    cara = box;
                }
            }
            c.check(names.containsAll(PARTY), "faces on the map: " + names);
            c.check(cara != null, "no face for LoCara");
            // the four rooms are different, so the faces must be at four different places
            long distinct = heads.stream().map(h -> Mod.call(h, "x0") + "," + Mod.call(h, "y0")).distinct().count();
            c.check(distinct == 4, "faces overlap: " + heads);
            cursorAt(c, 2, 2);
            shot(c, "map-clear");
            int[] box = cara;
            cursorAt(c, (box[0] + box[2]) / 2.0, (box[1] + box[3]) / 2.0);
            int before = c.events("menu.action").size();
            c.ctx().getInput().pressMouse(0);
            c.waitUntil("a menu.action for the click on LoCara's face", mc -> c.events("menu.action").size() > before, 60);
            List<JsonObject> ev = c.events("menu.action");
            int slot = ev.get(ev.size() - 1).get("slot").getAsInt();
            c.check(slot == 15, "a click on LoCara's face clicked slot " + slot + ", her head is 15");
            MenuKit.reset(c);

            // Boss: the same menu is the boxes again.
            c.onClient(mc -> Mod.staticCall("secrets.DungeonState", "toggleSimOverride"));
            overrideOn = true;
            boolean boss = c.onClient(mc -> (Boolean) Mod.staticCall("secrets.DungeonState", "isBossPhaseActive"));
            c.check(boss, "the sim override did not put DungeonState in boss");
            open(c, spec("close"));
            c.waitUntil("the boxes in boss", mc -> "BOXES".equals(Mod.staticCall(FEAT, "lastView")), 60);
            List<String> spots = drawnSpots(c);
            int bossSlot = pressKeyAndAwait(c, 0);
            c.check(bossSlot == SLOT.get(spots.get(0)), "in boss key 1 clicked " + bossSlot + ", spot 0 shows " + spots.get(0));
            c.note("clear: map with faces " + names + ", click on LoCara's face at " + box[0] + "," + box[1]
                    + " -> server click slot " + slot + "; boss: boxes " + spots + ", key 1 -> slot " + bossSlot);
        } finally {
            if (overrideOn) {
                c.onClient(mc -> Mod.staticCall("secrets.DungeonState", "toggleSimOverride"));
            }
            c.onClient(mc -> {
                Mod.staticCall("secrets.DungeonState", "setRoomSim", false);
                removeMates(mc);
                return null;
            });
            guiBack(c, gui);
            MenuKit.reset(c);
            restore(c, sv);
        }
    }

    // ---- 823 --------------------------------------------------------------------------------------------------------

    private static volatile long screenInitNanos;
    private static boolean hookInstalled;

    static void installOpenHook() {
        if (hookInstalled) {
            return;
        }
        hookInstalled = true;
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof AbstractContainerScreen<?> && "Spirit Leap".equals(screen.getTitle().getString())) {
                screenInitNanos = System.nanoTime();
            }
        });
    }

    static void openDelay(Session c) throws Exception {
        MenuKit.reset(c);
        Saved sv = setUp(c);
        c.onClient(mc -> {
            installOpenHook();
            return null;
        });
        boolean hasTiming = c.onClient(mc -> {
            try {
                Mod.staticCall(FEAT, "lastOpenToReadyMs");
                return true;
            } catch (AssertionError e) {
                return false;
            }
        });
        List<String> rows = new ArrayList<>();
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CFG, "Enabled", true);
            c.hx().give(0, MenuKit.item("items.spirit-leap"));
            c.ctx().getInput().pressKey(o -> o.keyHotbarSlots[0]);
            c.waitUntil("the leap in hotbar slot 0", mc -> "SPIRIT_LEAP".equals(
                    Mod.staticCall("cheatutils.CheatUtils", "skyblockId", mc.player.getInventory().getItem(0))), 60);
            c.hx().call("menu.onUse", "id", "SPIRIT_LEAP", "menu", spec("close"));
            for (int run = 0; run < 3; run++) {
                if (run > 0) {
                    // A fresh stack each run (a second use of the same one opened nothing on the first try).
                    c.hx().give(0, MenuKit.item("items.spirit-leap"));
                    c.ctx().waitTicks(10);
                }
                screenInitNanos = 0;
                int before = c.events("menu.action").size();
                long t0 = System.nanoTime();
                c.ctx().getInput().pressKey(o -> o.keyUse);
                c.waitUntil("the Spirit Leap menu to open", mc -> screenInitNanos != 0, 100);
                long opened = screenInitNanos;
                // A 1 key every tick until one leaps: the first tick it can is when the menu is usable.
                int presses = 0;
                while (c.events("menu.action").size() == before && presses < 40) {
                    c.ctx().getInput().pressKey(49);
                    presses++;
                }
                c.waitUntil("a leap", mc -> c.events("menu.action").size() > before, 40);
                double useToOpen = (opened - t0) / 1e6;
                String ready = "n/a (old jar)";
                if (hasTiming) {
                    double r = c.onClient(mc -> ((Number) Mod.staticCall(FEAT, "lastOpenToReadyMs")).doubleValue());
                    double d = c.onClient(mc -> ((Number) Mod.staticCall(FEAT, "lastOpenToDrawMs")).doubleValue());
                    ready = String.format(java.util.Locale.ROOT, "%.1f ms ready, %.1f ms first draw", r, d);
                    c.check(r >= 0 && r < 25, "run " + run + ": the menu was ready " + r + " ms after it opened");
                }
                String row = String.format(java.util.Locale.ROOT,
                        "run %d: use -> open-window %.0f ms (test-thread clock, includes up to a tick of input lag); "
                                + "open -> mod %s; 1 key pressed every tick leapt on press %d", run, useToOpen, ready, presses);
                rows.add(row);
                c.note(row);
                MenuKit.awaitNoScreen(c, 60);
                c.ctx().waitTicks(20);
            }
            if (hasTiming) {
                c.check(rows.stream().allMatch(r -> r.endsWith("press 1")), "a key press on the first tick did not leap: " + rows);
            }
        } finally {
            c.hx().call("menu.onUse", "id", "SPIRIT_LEAP", "menu", null);
            c.hx().call("give", "slot", 0, "stack", "minecraft:air");
            MenuKit.reset(c);
            restore(c, sv);
        }
    }

    // ---- 824 --------------------------------------------------------------------------------------------------------

    static void noCarried(Session c) throws Exception {
        MenuKit.reset(c);
        Saved sv = setUp(c);
        int gui = gui2(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CFG, "Enabled", true);
            int[] cursor = {6, 6};
            int[] near = c.onClient(mc -> {
                double f = mc.getWindow().getScreenWidth() / (double) mc.getWindow().getGuiScaledWidth();
                return new int[]{0, 0, (int) ((cursor[0] + 24) * f), (int) ((cursor[1] + 24) * f)};
            });
            // The picture's background must hold still: on the legit run of 2026-10-12 the world behind the menu moved
            // between the two frames (trees, 2474 "changed" pixels with no head anywhere). Look straight up at the sky.
            // Up at y 300 nothing but sky is above him (the 2026-10-12 legit run stood under a jungle canopy, and looking
            // up there still moved 2131 pixels between two clean frames). On a barrier block: teleported there and no
            // more, he fell back into the water before the second pass (1041 px of noise, 2026-10-22).
            AutoCloseable sky = MenuKit.stillSky(c);
            // (a) the leap menu, then (b) the same chest with the overlay off: the control that a carried head shows.
            int[] counts = new int[2];
            int[] noise = new int[2];
            for (int pass = 0; pass < 2; pass++) {
                boolean overlay = pass == 0;
                c.onClient(mc -> {
                    Mod.call(Mod.cfg(CFG), "setEnabled", overlay);
                    return null;
                });
                MenuKit.show(c, spec("keep"));
                MenuKit.awaitScreen(c, "Spirit Leap", 100);
                c.ctx().waitTicks(6);
                cursorAt(c, cursor[0], cursor[1]);
                BufferedImage clean = shot(c, (overlay ? "leap" : "chest") + "-no-carried");
                // Two clean frames: how much the cursor area changes with nothing carried (the noise floor).
                noise[pass] = diff(clean, shot(c, (overlay ? "leap" : "chest") + "-no-carried-2"), near);
                c.onClient(mc -> {
                    ((AbstractContainerScreen<?>) McCompat.screen(mc)).getMenu().setCarried(new ItemStack(Items.PLAYER_HEAD));
                    return null;
                });
                BufferedImage carried = shot(c, (overlay ? "leap" : "chest") + "-carried");
                boolean stillCarried = c.onClient(mc -> !((AbstractContainerScreen<?>) McCompat.screen(mc)).getMenu()
                        .getCarried().isEmpty());
                c.check(stillCarried, (overlay ? "leap" : "chest") + ": the carried head was gone by the picture");
                counts[pass] = diff(clean, carried, near);
                c.onClient(mc -> {
                    ((AbstractContainerScreen<?>) McCompat.screen(mc)).getMenu().setCarried(ItemStack.EMPTY);
                    return null;
                });
                MenuKit.reset(c);
            }
            sky.close();
            c.check(noise[0] <= 5 && noise[1] <= 5, "the background moved between two clean frames (" + noise[0] + ", "
                    + noise[1] + " pixel(s)) - the comparison means nothing");
            c.check(counts[1] > 50, "control: a plain chest with a carried head changed only " + counts[1]
                    + " pixel(s) at the cursor - the picture cannot see a carried item");
            c.check(counts[0] <= 5, "the leap menu drew the carried head: " + counts[0] + " pixel(s) changed at the cursor");
            c.note("carried head at the cursor: leap menu " + counts[0] + " changed pixel(s), plain chest " + counts[1]
                    + " (clean-frame noise " + noise[0] + "/" + noise[1] + ")");
        } finally {
            guiBack(c, gui);
            MenuKit.reset(c);
            restore(c, sv);
        }
    }

    // ---- 825 --------------------------------------------------------------------------------------------------------

    static void freeze(Session c) throws Exception {
        MenuKit.reset(c);
        Saved sv = setUp(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CFG, "Enabled", true);
            open(c, spec("take"));
            List<String> before = drawnSpots(c);
            c.check(before.equals(PARTY), "before the click the boxes show " + before);
            int slot = pressKeyAndAwait(c, 0);
            c.check(slot == 11, "key 1 clicked slot " + slot);
            List<String> seen = new ArrayList<>();
            boolean headGone = false;
            for (int t = 0; t < 20; t++) {
                c.ctx().waitTicks(1);
                List<String> now = drawnSpots(c);
                if (!now.equals(before)) {
                    seen.add("tick " + t + ": " + now);
                }
                headGone |= c.onClient(mc -> {
                    var s = McCompat.screen(mc);
                    return s instanceof AbstractContainerScreen<?> cs && !cs.getMenu().slots.get(11).getItem().is(Items.PLAYER_HEAD);
                });
            }
            c.check(headGone, "control: LoAnna's head never left slot 11 - nothing to freeze against");
            c.check(seen.isEmpty(), "the names moved while the click was in flight: " + seen);
            c.note("click on LoAnna (slot 11), her head left the menu; for 20 ticks the boxes stayed " + before);
        } finally {
            MenuKit.reset(c);
            restore(c, sv);
        }
    }
}

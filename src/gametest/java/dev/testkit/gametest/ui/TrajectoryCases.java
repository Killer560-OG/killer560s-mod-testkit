package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.CameraType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.Vec3;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * killer560's 2026-10-15 to-do: "The thing that detects where arrows from bows are going to go and Ender pearls and
 * whatnot should also detect for short bows whenever I am holding them" (mod shortbow-traj-1015).
 * <ul>
 *   <li>1080 shortbow: a bow whose lore says "Shortbow: Instantly shoots!" (no id, no name) draws ONE full-power arc
 *       (3.0 blocks a tick) the whole time it is held, without drawing - with the Bows switch OFF, so nothing but the new
 *       Shortbows switch can be drawing it. Judged by the feature's own test views AND a screenshot (cyan arc pixels
 *       against a frame with the feature OFF), so a jar without the views still fails on what it draws. Shortbows OFF
 *       draws nothing.</li>
 *   <li>1081 Terminator: an item with id TERMINATOR draws three arcs, the side ones at the same pitch and exactly 5
 *       degrees of yaw either side, measured from each arc's first simulated step; looking down 60 degrees the 3D angle
 *       between them narrows (the wiki: the spread narrows as you look up or down). A lore-only "Shoots 3 arrows at
 *       once." shortbow also draws three; a Juju by id alone draws one.</li>
 *   <li>1082 normal bow: a plain bow held but not drawn draws NOTHING (main read an idle bow as fully drawn and drew a
 *       full-power arc - pixels and views both); drawn (use key held) it draws one arc whose speed grows to 3.0.</li>
 *   <li>1083 settings: Shortbows defaults ON, survives save + load, is a button on the Trajectories tab with a
 *       tooltip.</li>
 * </ul>
 */
final class TrajectoryCases {

    private static final String CFG = "trajectories.TrajectoriesConfig";
    private static final String FEATURE = "trajectories.TrajectoriesFeature";

    private TrajectoryCases() {
    }

    private static ItemStack[] savedInv;
    private static float savedPitch;
    private static float savedYaw;

    private static boolean hasViews() {
        return Mod.has("trajectories.Shortbows");
    }

    // ---- items ----------------------------------------------------------------------------------------------------

    private static ItemStack bow(String id, String... lore) {
        ItemStack s = new ItemStack(Items.BOW);
        if (id != null) {
            CompoundTag tag = new CompoundTag();
            tag.putString("id", id);
            s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
        if (lore.length > 0) {
            List<Component> lines = new ArrayList<>();
            for (String l : lore) {
                lines.add(Component.literal(l));
            }
            s.set(DataComponents.LORE, new ItemLore(lines));
        }
        // A display name that says "bow" and nothing else: detection must never read it.
        s.set(DataComponents.CUSTOM_NAME, Component.literal("Test Bow"));
        return s;
    }

    /** Hypixel's lore lines (stripped), as the wiki prints them for a single-arrow shortbow. */
    static ItemStack shortbowLoreOnly() {
        return bow(null, "Damage: +310", "Shortbow: Instantly shoots!", "Shot Cooldown: 0.5s", "LEGENDARY BOW");
    }

    static ItemStack terminator() {
        return bow("TERMINATOR", "Shoots 3 arrows at once.", "Can damage endermen.", "Shortbow: Instantly Shoots!",
                "Shot Cooldown: 0.5s", "MYTHIC DUNGEON BOW");
    }

    // ---- scene ----------------------------------------------------------------------------------------------------

    private static void scene(UiCase c, float pitch) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            McCompat.clearChatAndToasts(mc);
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            if (savedInv == null) {
                var inv = mc.player.getInventory();
                savedInv = new ItemStack[42];
                for (int i = 0; i < 41; i++) {
                    savedInv[i] = inv.getItem(i).copy();
                }
                savedInv[41] = new ItemStack(Items.STICK, inv.getSelectedSlot() + 1);
                savedPitch = mc.player.getXRot();
                savedYaw = mc.player.getYRot();
            }
            mc.player.setXRot(pitch);
            mc.player.setYRot(30f);
            Object cfg = Mod.cfg(CFG);
            Mod.call(cfg, "setEnabled", false);
            Mod.call(cfg, "setShowBows", false);
            Mod.call(cfg, "setShowPearls", false);
            Mod.call(cfg, "setRange", 30);
            if (hasViews()) {
                Mod.call(cfg, "setShowShortbows", true);
            }
            return null;
        });
    }

    private static void look(UiCase c, float pitch) {
        c.onClient(mc -> {
            mc.player.setXRot(pitch);
            mc.player.setYRot(30f);
            return null;
        });
        c.ticks(3);
    }

    /** The stack in slot 0 (selected) and arrows in slot 1, on the client AND the integrated server. */
    private static void hold(UiCase c, ItemStack main) {
        c.onClient(mc -> {
            var inv = mc.player.getInventory();
            for (int i = 0; i < 41; i++) {
                inv.setItem(i, ItemStack.EMPTY);
            }
            inv.setItem(0, main.copy());
            inv.setItem(1, new ItemStack(Items.ARROW, 64));
            inv.setSelectedSlot(0);
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            if (server != null) {
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayer(uuid);
                    if (sp != null) {
                        var sinv = sp.getInventory();
                        for (int i = 0; i < 41; i++) {
                            sinv.setItem(i, ItemStack.EMPTY);
                        }
                        sinv.setItem(0, main.copy());
                        sinv.setItem(1, new ItemStack(Items.ARROW, 64));
                        sinv.setSelectedSlot(0);
                    }
                });
            }
            return null;
        });
        c.ticks(60); // equip animation and the item-name popup settle
    }

    private static void enabled(UiCase c, boolean on) {
        c.onClient(mc -> {
            Mod.call(Mod.cfg(CFG), "setEnabled", on);
            return null;
        });
        c.ticks(3);
    }

    private static void restore(UiCase c) {
        c.ctx().getInput().releaseKey(o -> o.keyUse);
        c.onClient(mc -> {
            Object cfg = Mod.cfg(CFG);
            Mod.call(cfg, "setEnabled", false);
            Mod.call(cfg, "setShowBows", false);
            Mod.call(cfg, "setShowPearls", true);
            if (hasViews()) {
                Mod.call(cfg, "setShowShortbows", true);
            }
            Mod.call(cfg, "save");
            if (savedInv != null) {
                var inv = mc.player.getInventory();
                for (int i = 0; i < 41; i++) {
                    inv.setItem(i, savedInv[i]);
                }
                inv.setSelectedSlot(savedInv[41].getCount() - 1);
                mc.player.setXRot(savedPitch);
                mc.player.setYRot(savedYaw);
                savedInv = null;
            }
            return null;
        });
    }

    // ---- views ----------------------------------------------------------------------------------------------------

    /** What the feature drew in its last render call: kind, arcs, yaws, pitch, speed, and each arc's first step. */
    private record Drawn(String kind, int arcs, float[] yaws, float pitch, double speed, List<Vec3[]> firstSteps,
                         long calls) {
        String describe() {
            StringBuilder b = new StringBuilder(kind + " x" + arcs + " speed " + String.format(Locale.ROOT, "%.3f", speed)
                    + " pitch " + pitch + " yaws");
            for (float y : yaws) {
                b.append(' ').append(String.format(Locale.ROOT, "%.2f", y));
            }
            b.append("; first steps");
            for (Vec3[] s : firstSteps) {
                Vec3 d = s[1].subtract(s[0]);
                b.append(String.format(Locale.ROOT, " [len %.3f, yaw %.2f]", d.length(), yawOf(d)));
            }
            return b.toString();
        }
    }

    @SuppressWarnings("unchecked")
    private static Drawn drawn(UiCase c) {
        c.ticks(3);
        return c.onClient(mc -> {
            List<List<Vec3>> arcs = (List<List<Vec3>>) Mod.staticCall(FEATURE, "lastArcs");
            List<Vec3[]> steps = new ArrayList<>();
            for (List<Vec3> a : arcs) {
                if (a.size() >= 2) {
                    steps.add(new Vec3[]{a.get(0), a.get(1)});
                }
            }
            return new Drawn((String) Mod.staticCall(FEATURE, "lastKind"),
                    ((Number) Mod.staticCall(FEATURE, "lastArcCount")).intValue(),
                    (float[]) Mod.staticCall(FEATURE, "lastYaws"),
                    ((Number) Mod.staticCall(FEATURE, "lastPitch")).floatValue(),
                    ((Number) Mod.staticCall(FEATURE, "lastSpeed")).doubleValue(), steps,
                    ((Number) Mod.staticCall(FEATURE, "renderCalls")).longValue());
        });
    }

    /** Minecraft yaw of a direction: x = -sin(yaw), z = cos(yaw). */
    private static double yawOf(Vec3 d) {
        return Math.toDegrees(Math.atan2(-d.x, d.z));
    }

    private static double angle(Vec3 a, Vec3 b) {
        double cos = a.normalize().dot(b.normalize());
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
    }

    // ---- pixels ---------------------------------------------------------------------------------------------------

    private static BufferedImage shot(UiCase c, String label) {
        c.ticks(6);
        String name = c.name() + "-" + label;
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        Path kept = dev.testkit.harness.Report.screenshot(name, taken);
        c.note(label + " -> " + (kept != null ? kept : taken));
        try {
            return javax.imageio.ImageIO.read(taken.toFile());
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read " + taken, e);
        }
    }

    /** Pixels that changed against {@code base} (summed RGB delta > 30) AND are the arc's cyan (0.3, 0.9, 1.0). */
    private static int cyan(BufferedImage base, BufferedImage img) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int p = img.getRGB(x, y), q = base.getRGB(x, y);
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                int d = Math.abs(r - ((q >> 16) & 0xFF)) + Math.abs(g - ((q >> 8) & 0xFF)) + Math.abs(b - (q & 0xFF));
                if (d > 30 && b > 200 && g > 170 && r < 140) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Cyan pixels the feature adds to the current scene: one frame OFF, one ON. */
    private static int arcPixels(UiCase c, String label) {
        enabled(c, false);
        BufferedImage off = shot(c, label + "-off");
        enabled(c, true);
        BufferedImage on = shot(c, label + "-on");
        return cyan(off, on);
    }

    // ---- 1080 -----------------------------------------------------------------------------------------------------

    static void shortbow(UiCase c) {
        try {
            scene(c, 50f);
            hold(c, shortbowLoreOnly());
            int px = arcPixels(c, "lore-shortbow");
            boolean using = c.onClient(mc -> mc.player.isUsingItem());
            c.note("lore-only shortbow, Bows OFF, not drawn (using " + using + "): " + px + " cyan arc px");
            if (using) {
                c.problem("the player is using the bow, so 'without drawing' is not what was measured");
            }
            if (px < 40) {
                c.problem("holding a shortbow (lore \"Shortbow: Instantly shoots!\") drew only " + px
                        + " cyan px - no full-power path without drawing");
            }
            if (!hasViews()) {
                c.problem("this jar has no Shortbows trajectories (mod before shortbow-traj-1015)");
                return;
            }
            Drawn d = drawn(c);
            c.note("views: " + d.describe());
            c.check("SHORTBOW".equals(d.kind) && d.arcs == 1, "a lore-only shortbow drew " + d.describe());
            if (!d.firstSteps.isEmpty()) {
                double len = d.firstSteps.get(0)[1].subtract(d.firstSteps.get(0)[0]).length();
                c.check(Math.abs(len - 3.0) < 0.02, String.format(Locale.ROOT,
                        "the shortbow arc's first step is %.3f blocks, a full-power arrow moves 3.0", len));
            }
            c.check(Math.abs(d.speed - 3.0) < 1e-6, "shortbow speed " + d.speed + ", want 3.0 (full power)");

            // Shortbows OFF: nothing, while the render hook still runs.
            c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setShowShortbows", false);
                return null;
            });
            long before = drawn(c).calls;
            Drawn off = drawn(c);
            int offPx = arcPixels(c, "shortbows-switch-off");
            c.note("Shortbows OFF: " + off.describe() + ", render calls " + before + " -> " + off.calls + ", " + offPx
                    + " cyan px");
            c.check(off.calls > before, "the render hook did not run, so 'draws nothing' proves nothing");
            c.check(off.arcs == 0 && offPx < 10, "Shortbows OFF still drew " + off.arcs + " arc(s), " + offPx + " px");
        } finally {
            restore(c);
        }
    }

    // ---- 1081 -----------------------------------------------------------------------------------------------------

    static void terminatorSpread(UiCase c) {
        try {
            scene(c, 50f);
            hold(c, terminator());
            int px = arcPixels(c, "terminator");
            c.note("Terminator, not drawn: " + px + " cyan arc px");
            if (px < 80) {
                c.problem("holding a Terminator drew only " + px + " cyan px");
            }
            if (!hasViews()) {
                c.problem("this jar has no Shortbows trajectories (mod before shortbow-traj-1015)");
                return;
            }
            look(c, 0f);
            Drawn level = drawn(c);
            c.note("pitch 0: " + level.describe());
            if (level.arcs != 3 || level.firstSteps.size() != 3) {
                c.problem("a Terminator drew " + level.arcs + " arc(s), want 3");
                return;
            }
            double centre = yawOf(level.firstSteps.get(1)[1].subtract(level.firstSteps.get(1)[0]));
            double left = yawOf(level.firstSteps.get(0)[1].subtract(level.firstSteps.get(0)[0]));
            double right = yawOf(level.firstSteps.get(2)[1].subtract(level.firstSteps.get(2)[0]));
            c.note(String.format(Locale.ROOT, "measured yaws %.3f / %.3f / %.3f (player yaw 30)", left, centre, right));
            c.check(Math.abs(centre - 30.0) < 0.05, String.format(Locale.ROOT, "centre arc at yaw %.3f, want 30", centre));
            c.check(Math.abs((centre - left) - 5.0) < 0.05 && Math.abs((right - centre) - 5.0) < 0.05,
                    String.format(Locale.ROOT, "side arcs %.3f and %.3f degrees off the centre, want 5 each",
                            centre - left, right - centre));
            for (Vec3[] s : level.firstSteps) {
                Vec3 d = s[1].subtract(s[0]);
                double pitch = -Math.toDegrees(Math.asin(d.y / d.length()));
                c.check(Math.abs(pitch) < 0.05 && Math.abs(d.length() - 3.0) < 0.02, String.format(Locale.ROOT,
                        "an arc left at pitch %.3f, %.3f blocks a tick (want 0, 3.0)", pitch, d.length()));
            }
            double flat = angle(level.firstSteps.get(0)[1].subtract(level.firstSteps.get(0)[0]),
                    level.firstSteps.get(1)[1].subtract(level.firstSteps.get(1)[0]));
            look(c, -60f);
            Drawn up = drawn(c);
            double steep = up.firstSteps.size() == 3 ? angle(up.firstSteps.get(0)[1].subtract(up.firstSteps.get(0)[0]),
                    up.firstSteps.get(1)[1].subtract(up.firstSteps.get(1)[0])) : -1;
            c.note(String.format(Locale.ROOT, "3D angle side-centre: %.3f at pitch 0, %.3f at pitch -60", flat, steep));
            c.check(up.arcs == 3 && steep > 0 && steep < flat - 1.0,
                    "the spread does not narrow looking up: " + up.describe());

            // A shortbow whose lore alone says three arrows draws three; a Juju by id alone draws one.
            look(c, 0f);
            hold(c, bow(null, "Shoots 3 arrows at once.", "Shortbow: Instantly shoots!"));
            Drawn loreThree = drawn(c);
            hold(c, bow("JUJU_SHORTBOW"));
            Drawn juju = drawn(c);
            c.note("lore 'Shoots 3 arrows at once.': " + loreThree.describe() + "; Juju by id: " + juju.describe());
            c.check(loreThree.arcs == 3, "a lore-only 3-arrow shortbow drew " + loreThree.arcs + " arc(s)");
            c.check("SHORTBOW".equals(juju.kind) && juju.arcs == 1, "a JUJU_SHORTBOW (id only) drew " + juju.describe());
        } finally {
            restore(c);
        }
    }

    // ---- 1082 -----------------------------------------------------------------------------------------------------

    static void normalBow(UiCase c) {
        try {
            scene(c, 50f);
            c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setShowBows", true);
                return null;
            });
            hold(c, new ItemStack(Items.BOW));
            int px = arcPixels(c, "plain-bow-idle");
            c.note("plain bow, Bows ON, not drawn: " + px + " cyan arc px");
            if (px > 10) {
                c.problem("a plain bow that is not being drawn drew " + px + " cyan px - it should only show while drawn");
            }
            if (!hasViews()) {
                c.problem("this jar has no trajectory test views (mod before shortbow-traj-1015)");
                return;
            }
            Drawn idle = drawn(c);
            c.note("idle views: " + idle.describe());
            c.check(idle.arcs == 0, "an idle plain bow drew " + idle.describe());

            // Draw it: hold the use key.
            look(c, 0f);
            c.ctx().getInput().holdKey(o -> o.keyUse);
            c.ticks(4);
            boolean using = c.onClient(mc -> mc.player.isUsingItem());
            Drawn early = drawn(c);
            c.ticks(25);
            Drawn full = drawn(c);
            c.ctx().getInput().releaseKey(o -> o.keyUse);
            c.ticks(5);
            Drawn released = drawn(c);
            c.note("drawing (using " + using + "): early " + early.describe() + "; after 25 ticks " + full.describe()
                    + "; released " + released.describe());
            c.check(using, "holding the use key did not draw the bow, so the drawn half proves nothing");
            c.check("BOW".equals(early.kind) && early.arcs == 1 && early.speed > 0.2 && early.speed < 2.9,
                    "early in the draw: " + early.describe() + " (want one BOW arc below full speed)");
            c.check("BOW".equals(full.kind) && full.arcs == 1 && Math.abs(full.speed - 3.0) < 1e-6,
                    "fully drawn: " + full.describe() + " (want one BOW arc at 3.0)");
            c.check(released.arcs == 0, "after releasing the bow still drew " + released.describe());
        } finally {
            restore(c);
        }
    }

    // ---- 1083 -----------------------------------------------------------------------------------------------------

    static void settings(UiCase c) {
        if (!hasViews()) {
            c.problem("this jar has no Shortbows setting (mod before shortbow-traj-1015)");
            return;
        }
        Path file = c.onClient(mc -> mc.gameDirectory.toPath().resolve(
                "config/killer560/interface/trajectories/killer560smod-trajectories.json"));
        try {
            boolean fresh = c.onClient(mc -> {
                try {
                    var ctor = Mod.cls(CFG).getDeclaredConstructor();
                    ctor.setAccessible(true);
                    return (Boolean) Mod.call(ctor.newInstance(), "isShowShortbows");
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            c.check(fresh, "a new TrajectoriesConfig has Shortbows OFF; it defaults ON");
            Object cfg = Mod.cfg(CFG);
            c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setShowShortbows", false);
                Mod.call(cfg, "save");
                Mod.staticCall(CFG, "load");
                return null;
            });
            Object back = Mod.cfg(CFG);
            c.note("after save + load: showShortbows " + Mod.call(back, "isShowShortbows") + "; file " + file
                    + " exists " + Files.exists(file));
            c.check(back != cfg, "load() did not make a new instance, so the round trip proved nothing");
            c.check(Boolean.FALSE.equals(Mod.call(back, "isShowShortbows")), "Shortbows OFF did not survive save + load");
            c.check(Files.exists(file), "no settings file at " + file);

            // The tab: a Shortbows button (with a tooltip) once Trajectories is ON.
            Mod.call(back, "setShowShortbows", true);
            List<String> labels = c.onClient(mc -> {
                List<String> out = new ArrayList<>();
                try {
                    Object tab = Mod.cls("gui.tab.TrajectoriesTab").getDeclaredConstructor().newInstance();
                    Runnable noop = () -> { };
                    for (Object w : (List<?>) Mod.call(tab, "buildWidgets", 0, 0, 300, noop)) {
                        out.add(((net.minecraft.client.gui.components.AbstractWidget) w).getMessage().getString());
                    }
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
                return out;
            });
            c.note("Trajectories tab: " + labels);
            boolean button = labels.stream().anyMatch(s -> s.replaceAll("§.", "").equals("Shortbows: ON"));
            c.check(button, "no 'Shortbows: ON' button on the Trajectories tab: " + labels);
            String tip = c.onClient(mc -> (String) Mod.staticCall("gui.SettingTooltips", "describe", "Trajectories",
                    "Shortbows: ON"));
            c.note("tooltip: " + tip);
            c.check(tip != null && tip.toLowerCase(Locale.ROOT).contains("shortbow"), "no Shortbows tooltip: " + tip);
        } finally {
            c.onClient(mc -> {
                Object now = Mod.cfg(CFG);
                Mod.call(now, "setEnabled", false);
                Mod.call(now, "setShowShortbows", true);
                Mod.call(now, "save");
                return null;
            });
        }
    }
}

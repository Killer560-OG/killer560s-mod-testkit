package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod mithril-mobs-1103 (killer560, 2026-11-03): Commission Mobs' style and range. Draw-only; the session's GrimAC check
 * covers the traffic, and both cases require that no container click was sent.
 * <ul>
 *   <li>1103-mining-mob-style: "let me choose outline, fill, fill+outline or glow". A NoAI "[Lv25] Goblin" zombie four
 *       blocks in front of the player (GUI hidden, color pure green), a screenshot with Commission Mobs off as the base,
 *       then one per style. Outline: green pixels where the box's front edges project, none on the body's middle. Fill:
 *       the body's middle turns green. Fill + Outline: both. Glow: no box drawn (the box frame counter stands still), the
 *       mob is in the glow set and Minecraft.shouldEntityAppearGlowing says true for it, green pixels round the body and
 *       none on its middle. Then the style survives a save and MiningQolConfig.load, and the cycling row wraps both ways.</li>
 *   <li>1103-mining-mob-range: "it feels like it has a very small range". A goblin zombie 70 blocks out whose name rides an
 *       armor stand: highlighted (past the old 64-block cap); the stand is killed and the mob stays highlighted; a zombie
 *       70 blocks out with no name and no stand is never highlighted (no type rules); a "Glacite Walker" stand-named mob is
 *       not highlighted for Goblin Slayer; with Goblin Slayer DONE the remembered goblin goes; and a killed one goes.</li>
 * </ul>
 */
final class HxCommissionMobs1103Cases {

    private static final String CFG = "miningqol.MiningQolConfig";
    private static final String STATE = "miningqol.MiningState";
    private static final String MOBS = "miningqol.CommissionMobs";
    private static final String STYLE = "miningqol.MiningQolConfig$CommissionMobStyle";
    private static final String TAIL = ",NoAI:1b,Silent:1b,PersistenceRequired:1b,Invulnerable:1b}";

    private HxCommissionMobs1103Cases() {
    }

    static void register(Session s) {
        s.test("1103-mining-mob-style", HxCommissionMobs1103Cases::style);
        s.test("1103-mining-mob-range", HxCommissionMobs1103Cases::range);
    }

    private static void mines(Session c, String... coms) {
        c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Royal Mines", "Purse: 0");
        List<String> tab = new ArrayList<>();
        tab.add("Area: Dwarven Mines");
        tab.add("Profile: Test");
        tab.add("");
        tab.add("Commissions:");
        for (String com : coms) {
            tab.add(" " + com);
        }
        tab.add("");
        c.hx().tab(tab, null, null);
        String want = String.join(",", coms);
        c.waitUntil("Dwarven Mines with " + want, mc -> "DWARVEN_MINES".equals(Mod.staticCall(STATE, "island"))
                && ((List<?>) Mod.staticCall(STATE, "commissions")).size() == coms.length
                && Mod.staticCall(STATE, "commissions").toString().contains(coms[0].split(":")[0].trim())
                && Mod.staticCall(STATE, "commissions").toString().contains(coms[0].contains("DONE") ? "true" : "false"), 100);
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> targetsNow() {
        return new ArrayList<>((List<Integer>) Mod.staticCall(MOBS, "targetIds"));
    }

    private static List<Integer> targets(Session c) {
        return c.onClient(mc -> targetsNow());
    }

    private static int intCall(Session c, String cls, String m) {
        return c.onClient(mc -> ((Number) Mod.staticCall(cls, m)).intValue());
    }

    /** The id of the first entity (stands when {@code stands}) whose custom name contains {@code part}, or -1. */
    private static int idNamed(net.minecraft.client.Minecraft mc, String part, boolean stands) {
        for (Entity e : mc.level.entitiesForRendering()) {
            boolean stand = e instanceof net.minecraft.world.entity.decoration.ArmorStand;
            if (stand == stands && e.hasCustomName() && e.getCustomName().getString().contains(part)) {
                return e.getId();
            }
        }
        return -1;
    }

    /** The unnamed zombie at block x, z, or -1. */
    private static int unnamedZombieAt(net.minecraft.client.Minecraft mc, int x, int z) {
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof net.minecraft.world.entity.monster.zombie.Zombie && !e.hasCustomName()
                    && Math.abs(e.getX() - x - 0.5) < 1.0 && Math.abs(e.getZ() - z - 0.5) < 1.0) {
                return e.getId();
            }
        }
        return -1;
    }

    // ---- 1103-mining-mob-style --------------------------------------------------------------------------------------

    private static BufferedImage shot(Session c, String tag) throws Exception {
        c.ctx().waitTicks(6);
        java.nio.file.Path p = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name() + "-" + tag));
        return javax.imageio.ImageIO.read(p.toFile());
    }

    /** How much greener the pixel got: (g - max(r, b)) now minus the same in the base. */
    private static int greener(BufferedImage base, BufferedImage img, int x, int y) {
        int a = base.getRGB(x, y), b = img.getRGB(x, y);
        int ga = ((a >> 8) & 255) - Math.max((a >> 16) & 255, a & 255);
        int gb = ((b >> 8) & 255) - Math.max((b >> 16) & 255, b & 255);
        return gb - ga;
    }

    /** Pixels in [x0, x1) x [y0, y1) that got at least 40 greener. */
    private static int greenCount(BufferedImage base, BufferedImage img, int x0, int y0, int x1, int y1) {
        int n = 0;
        for (int y = Math.max(0, y0); y < Math.min(img.getHeight(), y1); y++) {
            for (int x = Math.max(0, x0); x < Math.min(img.getWidth(), x1); x++) {
                if (greener(base, img, x, y) >= 40) {
                    n++;
                }
            }
        }
        return n;
    }

    /** The counts one shot gives: {middle of the body, the box's front-edge columns, the band round the body}. */
    private static int[] measure(BufferedImage base, BufferedImage img, double f) {
        int w = img.getWidth(), h = img.getHeight();
        int cx = w / 2, cy = h / 2;
        // Mob 4 blocks out (center), box half-width 0.3, so the front face is 3.7 out, the back 4.3. The eye is 1.62 above
        // the mob's feet and the box is 1.95 tall: rows from cy - 0.33/3.7 f (top) to cy + 1.62/3.7 f (bottom).
        int edgeOuter = (int) Math.round(0.3 / 3.7 * f);
        int edgeInner = (int) Math.round(0.3 / 4.3 * f);
        int top = cy - (int) Math.round(0.33 / 3.7 * f);
        int bottom = cy + (int) Math.round(1.62 / 3.7 * f);
        int midRow = cy + (int) Math.round(0.5 / 4.0 * f);   // the torso, below the head
        int body = Math.max(2, (int) Math.round(0.08 / 4.0 * f));
        int middle = greenCount(base, img, cx - body, midRow - body, cx + body + 1, midRow + body + 1);
        int edges = greenCount(base, img, cx + edgeInner - 3, top + 4, cx + edgeOuter + 4, bottom - 4)
                + greenCount(base, img, cx - edgeOuter - 4, top + 4, cx - edgeInner + 4, bottom - 4);
        int band = (int) Math.round(0.75 / 4.0 * f);
        int round = greenCount(base, img, cx - band, top - 6, cx + band + 1, bottom + 6) - middle;
        return new int[]{middle, edges, round};
    }

    private static void style(Session c) throws Exception {
        double[] at = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        Object outline = c.onClient(mc -> Mod.enumValue(STYLE, "OUTLINE"));
        boolean hideGui = c.onClient(mc -> dev.testkit.compat.McCompat.hudHidden(mc));
        try (Settings set = new Settings(c).with(CFG, "CommissionHud", true).with(CFG, "CommissionMobs", false)
                .with(CFG, "CommissionMobsEsp", false).with(CFG, "CommissionMobColor", 0xFF00FF00)
                .with(CFG, "CommissionMobStyle", outline)) {
            mines(c, "Goblin Slayer: 40%");
            // Feet level with the player's, exactly 4 blocks north of his eye, facing him (south).
            c.server().command(String.format(java.util.Locale.ROOT, "summon zombie %.3f %.3f %.3f", at[0], at[1], at[2] - 4)
                    + " {Tags:[\"k1103s\"],Rotation:[0f,0f],"
                    + "CustomName:\"[Lv25] Goblin 100/100\",CustomNameVisible:0b" + TAIL);
            c.waitUntil("the goblin on the client", mc -> idNamed(mc, "Goblin", false) >= 0, 80);
            int goblin = c.onClient(mc -> idNamed(mc, "Goblin", false));
            c.ctx().runOnClient(mc -> {
                mc.player.setYRot(180f);
                mc.player.setXRot(0f);
                dev.testkit.compat.McCompat.setHudHidden(mc, true);
                dev.testkit.compat.McCompat.clearChatAndToasts(mc);
            });
            c.ctx().waitTicks(10);
            double fpx = c.onClient(mc -> (mc.getWindow().getHeight() / 2.0)
                    / Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0));
            BufferedImage base = shot(c, "base");
            c.ctx().runOnClient(mc -> Mod.set(CFG, "setCommissionMobs", true));
            c.waitUntil("the goblin highlighted", mc -> targetsNow().contains(goblin), 60);

            String[] names = {"OUTLINE", "FILL", "FILL_OUTLINE", "GLOW"};
            int[][] got = new int[4][];
            for (int i = 0; i < names.length; i++) {
                final String name = names[i];
                c.ctx().runOnClient(mc -> Mod.set(CFG, "setCommissionMobStyle", Mod.enumValue(STYLE, name)));
                c.waitUntil(name + " in use", mc -> name.equals(Mod.staticCall(MOBS, "styleNow")), 20);
                int frames = intCall(c, MOBS, "frames");
                BufferedImage img = shot(c, name.toLowerCase());
                int drawn = intCall(c, MOBS, "frames") - frames;
                got[i] = measure(base, img, fpx);
                c.note(name + ": middle " + got[i][0] + ", edge columns " + got[i][1] + ", round the body " + got[i][2]
                        + ", box frames " + drawn);
                if (name.equals("GLOW")) {
                    c.check(drawn == 0, "Glow still drew boxes (" + drawn + " frames)");
                    boolean glows = c.onClient(mc -> {
                        Entity e = mc.level.getEntity(goblin);
                        return e != null && mc.shouldEntityAppearGlowing(e);
                    });
                    c.check(glows, "Glow: Minecraft.shouldEntityAppearGlowing is false for the goblin");
                    c.check(c.onClient(mc -> Mod.staticCall(MOBS, "glowIdsNow").toString()).contains(String.valueOf(goblin)),
                            "Glow: the goblin is not in the glow set");
                } else {
                    c.check(drawn > 0, name + ": no box frames drawn");
                    c.check(c.onClient(mc -> ((List<?>) Mod.staticCall(MOBS, "glowIdsNow")).isEmpty()),
                            name + ": something is in the glow set");
                }
            }
            int[] o = got[0], fl = got[1], fo = got[2], gl = got[3];
            c.check(o[1] >= 8 && o[0] == 0, "Outline: want edge pixels and a clean middle, got " + java.util.Arrays.toString(o));
            c.check(fl[0] > 0, "Fill: the body's middle did not turn green " + java.util.Arrays.toString(fl));
            c.check(fo[0] > 0 && fo[1] >= 8, "Fill + Outline: want a green middle and edges " + java.util.Arrays.toString(fo));
            c.check(gl[0] == 0 && gl[2] >= 8, "Glow: want an outline round the body and a clean middle "
                    + java.util.Arrays.toString(gl));

            // Survives a save and a reload; the row's step wraps both ways.
            String[] r = c.onClient(mc -> {
                Object cfg = Mod.cfg(CFG);
                Mod.call(cfg, "setCommissionMobStyle", Mod.enumValue(STYLE, "FILL_OUTLINE"));
                Mod.call(cfg, "save");
                Mod.staticCall(CFG, "load");
                Object fresh = Mod.cfg(CFG);
                String loaded = String.valueOf(Mod.call(fresh, "getCommissionMobStyle"));
                Mod.call(fresh, "setCommissionMobStyle", Mod.enumValue(STYLE, "OUTLINE"));
                Mod.call(fresh, "stepCommissionMobStyle", -1);
                String back = String.valueOf(Mod.call(fresh, "getCommissionMobStyle"));
                Mod.call(fresh, "stepCommissionMobStyle", 1);
                String fwd = String.valueOf(Mod.call(fresh, "getCommissionMobStyle"));
                return new String[]{loaded, back, fwd};
            });
            c.note("after reload " + r[0] + "; Outline step -1 -> " + r[1] + ", then +1 -> " + r[2]);
            c.check("FILL_OUTLINE".equals(r[0]), "the style did not survive the reload: " + r[0]);
            c.check("GLOW".equals(r[1]) && "OUTLINE".equals(r[2]), "the style row does not wrap: " + r[1] + " " + r[2]);
            c.check(c.events("container.click").isEmpty(), "Commission Mobs clicked something");
        } finally {
            c.ctx().runOnClient(mc -> dev.testkit.compat.McCompat.setHudHidden(mc, hideGui));
            c.server().command("kill @e[tag=k1103s]");
            c.ctx().runOnClient(mc -> Mod.staticCall(CFG, "load"));
            hub(c);
        }
    }

    // ---- 1103-mining-mob-range --------------------------------------------------------------------------------------

    private static void range(Session c) {
        BlockPos f = c.onClient(mc -> mc.player.blockPosition());
        int x = f.getX(), y = f.getY(), z = f.getZ();
        int far = 70;
        int rd = c.onClient(mc -> mc.options.renderDistance().get());
        try (Settings set = new Settings(c).with(CFG, "CommissionHud", true).with(CFG, "CommissionMobs", true)
                .with(CFG, "CommissionMobsEsp", false)) {
            if (rd < 8) {
                c.ctx().runOnClient(mc -> {
                    mc.options.renderDistance().set(8);
                    mc.options.broadcastOptions();
                });
                c.ctx().waitTicks(40);
            }
            mines(c, "Goblin Slayer: 40%");
            // A goblin far out whose name rides a stand; an unnamed zombie far out; a walker named by a stand.
            int gx = x + far, wx = x - far;
            c.server().command("summon zombie " + gx + ".5 " + y + " " + z + ".5 {Tags:[\"k1103r\",\"k1103rg\"]" + TAIL);
            c.server().command("summon armor_stand " + gx + ".5 " + (y + 2) + " " + z + ".5 {Tags:[\"k1103r\",\"k1103rs\"],"
                    + "CustomName:\"[Lv25] Goblin 100/100\",CustomNameVisible:1b,Invisible:1b,NoGravity:1b,Marker:1b}");
            c.server().command("summon zombie " + x + ".5 " + y + " " + (z + far) + ".5 {Tags:[\"k1103r\"]" + TAIL);
            c.server().command("summon zombie " + wx + ".5 " + y + " " + z + ".5 {Tags:[\"k1103r\"]" + TAIL);
            c.server().command("summon armor_stand " + wx + ".5 " + (y + 2) + " " + z + ".5 {Tags:[\"k1103r\"],"
                    + "CustomName:\"[Lv100] Glacite Walker 2000/2000\",CustomNameVisible:1b,Invisible:1b,NoGravity:1b,Marker:1b}");
            c.waitUntil("the far mobs on the client", mc -> unnamedZombieAt(mc, gx, z) >= 0 && unnamedZombieAt(mc, x, z + far) >= 0
                    && unnamedZombieAt(mc, wx, z) >= 0 && idNamed(mc, "Goblin", true) >= 0
                    && idNamed(mc, "Glacite Walker", true) >= 0, 200);
            int[] ids = c.onClient(mc -> new int[]{unnamedZombieAt(mc, gx, z), unnamedZombieAt(mc, x, z + far),
                    unnamedZombieAt(mc, wx, z), idNamed(mc, "Goblin", true)});
            int goblin = ids[0], plain = ids[1], walker = ids[2], stand = ids[3];
            double dist = c.onClient(mc -> Math.sqrt(mc.level.getEntity(goblin).distanceToSqr(mc.player)));
            c.note("render distance " + rd + " (8 for the case); goblin " + goblin + " at " + Math.round(dist) + " blocks (stand "
                    + stand + "), unnamed " + plain + ", walker " + walker);
            c.check(dist > 64, "control: the far goblin is only " + dist + " blocks out");
            c.waitUntil("the far goblin highlighted", mc -> targetsNow().contains(goblin), 60);
            c.server().command("kill @e[tag=k1103rs]");
            c.waitUntil("the stand gone from the client", mc -> mc.level.getEntity(stand) == null, 60);
            for (int i = 0; i < 4; i++) {
                c.ctx().waitTicks(10);
                List<Integer> t = targets(c);
                c.check(t.contains(goblin), "the goblin lost its highlight after its stand went: " + t);
                c.check(!t.contains(plain), "an unnamed zombie with no stand was highlighted: " + t);
                c.check(!t.contains(walker), "a Glacite Walker was highlighted for Goblin Slayer: " + t);
            }
            c.note("the goblin stayed highlighted 40 ticks after its stand went; the unnamed and the walker never");
            mines(c, "Goblin Slayer: DONE");
            c.waitUntil("Goblin Slayer DONE: the remembered goblin goes", mc -> !targetsNow().contains(goblin)
                    && ((Number) Mod.staticCall(MOBS, "knownCount")).intValue() == 0, 40);
            // Remembered again (stand back), then killed: it goes.
            mines(c, "Goblin Slayer: 40%");
            c.server().command("summon armor_stand " + gx + ".5 " + (y + 2) + " " + z + ".5 {Tags:[\"k1103r\",\"k1103rs\"],"
                    + "CustomName:\"[Lv25] Goblin 100/100\",CustomNameVisible:1b,Invisible:1b,NoGravity:1b,Marker:1b}");
            c.waitUntil("highlighted again with its stand back", mc -> targetsNow().contains(goblin), 80);
            c.server().command("kill @e[tag=k1103rs]");
            c.server().command("kill @e[tag=k1103rg]");
            c.waitUntil("a killed goblin goes", mc -> !targetsNow().contains(goblin), 60);
            c.check(c.events("container.click").isEmpty(), "Commission Mobs clicked something");
            c.note("DONE drops the remembered goblin; a killed one goes too");
        } finally {
            c.server().command("kill @e[tag=k1103r]");
            if (rd < 8) {
                c.ctx().runOnClient(mc -> {
                    mc.options.renderDistance().set(rd);
                    mc.options.broadcastOptions();
                });
            }
            hub(c);
        }
    }
}

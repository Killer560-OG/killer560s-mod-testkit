package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * killer560's 2026-10-15 small dungeon list (mod branch small-dungeon-1015), the parts a singleplayer world proves.
 *
 * <p>1051 Thorn: the cheat-only "All Mobs Through Walls" (every living mob but bats) and "Bats Through Walls" switches.
 * A cow and a bat stand behind a wall; with the Spirit Mob Highlight on (line of sight) neither is picked, with the
 * cheat switch on its target is, flagged as drawn through walls. The legit jar has neither row and picks nothing even
 * with the saved flags on.
 *
 * <p>1052 Dungeon ESP: the ESP has no colour of its own (no "Wither Boss Color" row, no saved witherColor) and draws in the
 * Wither Boss Highlight's colour; the Tracer row is on both builds and, without the cheat ESP, a tracer goes only to a
 * wither that can be seen.
 *
 * <p>1053 Secrets HUD: a "Secrets Text Color" setting; the HUD line is drawn in it and it survives a config reload.
 */
final class SmallDungeonCases {

    private static final String TH_CFG = "thorn.ThornConfig";
    private static final String TH_ESP = "thorn.ThornEspFeature";
    private static final String ESP_CFG = "mobesp.MobEspConfig";
    private static final String ESP = "mobesp.MobEspFeature";
    private static final String DI_CFG = "dungeoninfo.DungeonInfoConfig";
    private static final String STATE = "com.killer560.hub.secrets.DungeonState";

    private SmallDungeonCases() {
    }

    // =================================================================================================================
    // 1051 Thorn through-walls switches
    // =================================================================================================================

    static void thornThroughWalls(UiCase c) throws Exception {
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, TH_CFG);
        Difficulty[] old = new Difficulty[1];
        boolean cheat = Mod.isCheat();
        try {
            old[0] = c.onClient(mc -> mc.getSingleplayerServer().getWorldData().getDifficulty());
            DungeonB2Cases.server(c, s -> s.setDifficulty(Difficulty.EASY, true));
            DungeonB2Cases.command(c, "kill @e[tag=sd15]");
            DungeonB2Cases.command(c, "tp @a 40.5 -60 30.5 -90 0");
            wall(c, true);
            DungeonB2Cases.command(c, "summon cow 46.5 -60 30.5 {NoAI:1b,Silent:1b,PersistenceRequired:1b,Tags:[\"sd15\"]}");
            DungeonB2Cases.command(c, "summon bat 46.5 -58 32.5 {NoAI:1b,Silent:1b,PersistenceRequired:1b,Tags:[\"sd15\"]}");
            c.ticks(20);

            // ---- the tab ----
            Screen s = DungeonB2Cases.openTab(c, "Thorn (F4)");
            List<String> rows = DungeonB2Cases.labels(c, s);
            c.note("Thorn rows: " + rows);
            boolean allRow = rows.stream().anyMatch(l -> l.startsWith("All Mobs Through Walls:"));
            boolean batRow = rows.stream().anyMatch(l -> l.startsWith("Bats Through Walls:"));
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            if (cheat) {
                c.check(allRow && batRow, "the cheat Thorn tab has no All Mobs / Bats Through Walls switches (" + allRow + "/" + batRow + ")");
            } else {
                c.check(!allRow && !batRow, "the legit Thorn tab shows a cheat through-walls switch");
            }

            Object cfg = c.onClient(mc -> Mod.cfg(TH_CFG));
            boolean hasSetters = c.onClient(mc -> {
                for (Method m : cfg.getClass().getMethods()) {
                    if (m.getName().equals("setAllMobsThroughWalls")) {
                        return true;
                    }
                }
                return false;
            });
            c.check(hasSetters, "ThornConfig has no setAllMobsThroughWalls");
            if (!hasSetters) {
                return;
            }
            int[] ids = c.onClient(mc -> {
                int cow = -1;
                int bat = -1;
                for (Entity e : mc.level.entitiesForRendering()) {
                    if (e instanceof net.minecraft.world.entity.animal.cow.AbstractCow) {
                        cow = e.getId();
                    } else if (e instanceof net.minecraft.world.entity.ambient.Bat) {
                        bat = e.getId();
                    }
                }
                return new int[]{cow, bat};
            });
            c.check(ids[0] >= 0 && ids[1] >= 0, "the cow / bat never reached the client (" + ids[0] + "/" + ids[1] + ") - nothing measured");
            if (ids[0] < 0 || ids[1] < 0) {
                return;
            }

            // ---- control: the line-of-sight highlight does not see through the wall ----
            setFlags(c, cfg, false, false, true);
            Set<Integer> ctl = picked(c);
            c.note("Spirit Mob Highlight on, wall between: picked " + ctl);
            c.check(!ctl.contains(ids[0]) && !ctl.contains(ids[1]), "premise: the wall did not hide the cow / bat from the line-of-sight highlight");

            // ---- All Mobs ----
            setFlags(c, cfg, true, false, false);
            Set<Integer> mobs = picked(c);
            Set<Integer> mobsForced = forced(c);
            c.note("All Mobs Through Walls: picked " + mobs + ", through walls " + mobsForced);
            // ---- Bats ----
            setFlags(c, cfg, false, true, false);
            Set<Integer> bats = picked(c);
            Set<Integer> batsForced = forced(c);
            c.note("Bats Through Walls: picked " + bats + ", through walls " + batsForced);
            // ---- both ----
            setFlags(c, cfg, true, true, false);
            Set<Integer> both = picked(c);
            c.note("both: picked " + both);
            // ---- neither ----
            setFlags(c, cfg, false, false, false);
            Set<Integer> none = picked(c);
            if (cheat) {
                c.check(mobs.contains(ids[0]) && mobsForced.contains(ids[0]), "All Mobs Through Walls did not pick the cow behind the wall");
                c.check(!mobs.contains(ids[1]), "All Mobs Through Walls picked the bat");
                c.check(bats.contains(ids[1]) && batsForced.contains(ids[1]), "Bats Through Walls did not pick the bat behind the wall");
                c.check(!bats.contains(ids[0]), "Bats Through Walls picked the cow");
                c.check(both.contains(ids[0]) && both.contains(ids[1]), "both switches did not pick both");
                c.check(none.isEmpty(), "with every switch off something is still picked: " + none);
            } else {
                c.check(mobs.isEmpty() && bats.isEmpty() && both.isEmpty(), "legit jar: the saved flags picked targets (" + mobs + bats + both + ")");
            }
            setFlags(c, cfg, true, true, false);
            Mod.call(cfg, "save");
            Path file = saved.keySet().iterator().next();
            String json = Files.readString(file);
            c.check(json.contains("allMobsThroughWalls") && json.contains("batsThroughWalls"), "the switches are not saved");
            c.onClient(mc -> Mod.staticCall(TH_CFG, "load"));
            Object cfg2 = c.onClient(mc -> Mod.cfg(TH_CFG));
            boolean back = c.onClient(mc -> (Boolean) Mod.call(cfg2, "getAllMobsThroughWallsRaw")
                    && (Boolean) Mod.call(cfg2, "getBatsThroughWallsRaw"));
            c.check(back, "the switches did not survive a reload");
        } finally {
            DungeonB2Cases.command(c, "kill @e[tag=sd15]");
            wall(c, false);
            if (old[0] != null) {
                DungeonB2Cases.server(c, s -> s.setDifficulty(old[0], true));
            }
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            DungeonB2Cases.restoreConfig(c, saved, TH_CFG);
            c.onClient(mc -> {
                Mod.staticCall(TH_ESP, "reset");
                return null;
            });
        }
    }

    /** The test wall (x 42, y -60..-52, z 24..37), set on the server block by block: a /fill typed after case 687 did nothing
     *  (the server kept air, 1051 failed its premise on merged main 2026-10-15) while setBlock worked. Waits for the client to
     *  show the change before returning. */
    static void wall(UiCase c, boolean up) {
        DungeonB2Cases.server(c, sv -> {
            var state = up ? net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()
                    : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            for (int z = 24; z <= 37; z++) {
                for (int y = -60; y <= -52; y++) {
                    sv.overworld().setBlock(new net.minecraft.core.BlockPos(42, y, z), state, 3);
                }
            }
        });
        boolean shown = false;
        for (int i = 0; i < 40 && !shown; i++) {
            c.ticks(1);
            shown = c.onClient(mc -> mc.level.getBlockState(new net.minecraft.core.BlockPos(42, -58, 30)).isAir() != up);
        }
        c.check(shown, "the client never showed the wall " + (up ? "up" : "removed"));
    }

    private static void setFlags(UiCase c, Object cfg, boolean allMobs, boolean bats, boolean spiritMobs) {
        c.onClient(mc -> {
            Mod.call(cfg, "setAllMobsThroughWalls", allMobs);
            Mod.call(cfg, "setBatsThroughWalls", bats);
            Mod.call(cfg, "setMobEsp", spiritMobs);
            Mod.call(cfg, "setBearEsp", false);
            Mod.call(cfg, "setBowEsp", false);
            Mod.call(cfg, "setThornEsp", false);
            Mod.call(cfg, "setThroughWalls", false);
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private static Set<Integer> picked(UiCase c) {
        return c.onClient(mc -> {
            Mod.staticCall(TH_ESP, "tick", mc);
            return Set.copyOf(((Map<Integer, Integer>) Mod.field(Mod.ROOT + TH_ESP, "targets")).keySet());
        });
    }

    @SuppressWarnings("unchecked")
    private static Set<Integer> forced(UiCase c) {
        return c.onClient(mc -> Set.copyOf((Set<Integer>) Mod.field(Mod.ROOT + TH_ESP, "forcedIds")));
    }

    // =================================================================================================================
    // 1052 Dungeon ESP colour + tracer
    // =================================================================================================================

    static void espColourTracer(UiCase c) throws Exception {
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, ESP_CFG);
        Difficulty[] old = new Difficulty[1];
        boolean cheat = Mod.isCheat();
        int mine = 0xFF123456;
        try {
            old[0] = c.onClient(mc -> mc.getSingleplayerServer().getWorldData().getDifficulty());
            DungeonB2Cases.server(c, s -> s.setDifficulty(Difficulty.EASY, true));
            DungeonB2Cases.command(c, "kill @e[tag=sd15]");
            DungeonB2Cases.command(c, "tp @a 40.5 -60 30.5 -90 0");
            wall(c, false);
            DungeonB2Cases.command(c, "summon wither 47.5 -60 30.5 {NoAI:1b,Invul:0,Silent:1b,PersistenceRequired:1b,Tags:[\"sd15\"]}");
            c.onClient(mc -> {
                Mod.setField(STATE, "simOverrideActive", true);
                Object e = Mod.cfg(ESP_CFG);
                Mod.call(e, "setStarredMobs", false);
                Mod.call(e, "setBats", false);
                Mod.call(e, "setEsp", false);
                Mod.call(e, "setWitherHighlight", true);
                Mod.call(e, "setWitherHighlightColor", mine);
                Mod.call(e, "setWitherTracer", true);
                return null;
            });
            c.ticks(20);
            int id = c.onClient(mc -> {
                for (Entity e : mc.level.entitiesForRendering()) {
                    if (e instanceof net.minecraft.world.entity.boss.wither.WitherBoss) {
                        return e.getId();
                    }
                }
                return -1;
            });
            c.check(id >= 0, "the wither never reached the client - nothing measured");
            if (id < 0) {
                return;
            }

            // ---- rows ----
            Screen s = DungeonB2Cases.openTab(c, cheat ? "Dungeon ESP" : "Starred Mob Hitboxes");
            List<String> rows = DungeonB2Cases.labels(c, s);
            c.note("ESP tab rows: " + rows);
            c.check(rows.stream().noneMatch(l -> l.startsWith("Wither Boss Color")), "the 'Wither Boss Color' row is still there");
            c.check(rows.stream().anyMatch(l -> l.startsWith("Tracer:")), "no Tracer row in the Wither Boss Highlight section");
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            Object e0 = c.onClient(mc -> Mod.cfg(ESP_CFG));
            boolean hasOldColour = c.onClient(mc -> {
                for (Method m : e0.getClass().getMethods()) {
                    if (m.getName().equals("getWitherColor")) {
                        return true;
                    }
                }
                return false;
            });
            c.check(!hasOldColour, "MobEspConfig still has a separate wither (ESP) colour");
            c.onClient(mc -> Mod.call(e0, "save"));
            String json = Files.readString(saved.keySet().iterator().next());
            c.check(!json.contains("witherColor\""), "the config still saves a separate witherColor");

            // ---- legit highlight: a visible wither gets a tracer, a hidden one does not ----
            Map<String, Object> seen = looked(c, id);
            c.note("highlight only, in sight: glow " + seen.get("glow") + ", tracer " + seen.get("tracer"));
            c.check(Integer.valueOf(mine).equals(seen.get("glow")), "the highlight does not draw the wither in the highlight colour: " + seen.get("glow"));
            c.check(Integer.valueOf(mine).equals(seen.get("tracer")), "no tracer to a visible wither with only the highlight on: " + seen.get("tracer"));
            wall(c, true);
            c.ticks(12);
            Map<String, Object> hidden = looked(c, id);
            c.note("highlight only, behind a wall: glow " + hidden.get("glow") + ", tracer " + hidden.get("tracer"));
            c.check(hidden.get("tracer") == null, "a tracer went to a wither behind a wall without the cheat ESP");
            c.check(hidden.get("glow") == null && hidden.get("box") == null, "the legit highlight drew a wither behind a wall");
            c.onClient(mc -> Mod.call(e0, "setWitherTracer", false));
            wall(c, false);
            c.ticks(12);
            Map<String, Object> off = looked(c, id);
            c.check(off.get("tracer") == null, "a tracer is drawn with Tracer off");

            // ---- the cheat ESP: same colour, through walls, tracer to every wither ----
            if (cheat) {
                c.onClient(mc -> {
                    Mod.call(e0, "setWitherHighlight", false);
                    Mod.call(e0, "setEsp", true);
                    Mod.call(e0, "setWitherTracer", true);
                    return null;
                });
                wall(c, true);
                c.ticks(12);
                Map<String, Object> esp = looked(c, id);
                c.note("ESP on, behind a wall: box " + esp.get("box") + ", tracer " + esp.get("tracer"));
                c.check(Integer.valueOf(mine).equals(esp.get("box")), "the ESP did not draw the wither in the highlight colour: " + esp.get("box"));
                c.check(Integer.valueOf(mine).equals(esp.get("tracer")), "the ESP tracer is missing or not in the highlight colour: " + esp.get("tracer"));
            }
        } finally {
            c.onClient(mc -> {
                Mod.setField(STATE, "simOverrideActive", false);
                McCompat.setScreen(mc, null);
                return null;
            });
            DungeonB2Cases.command(c, "kill @e[tag=sd15]");
            wall(c, false);
            if (old[0] != null) {
                DungeonB2Cases.server(c, s -> s.setDifficulty(old[0], true));
            }
            DungeonB2Cases.restoreConfig(c, saved, ESP_CFG);
        }
    }

    /** What the Dungeon ESP holds for the wither right now: glow colour, box colour, tracer colour (null = none). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> looked(UiCase c, int id) {
        return c.onClient(mc -> {
            Map<String, Object> out = new java.util.HashMap<>();
            Map<Integer, Integer> glow = (Map<Integer, Integer>) Mod.field(Mod.ROOT + ESP, "glowTargets");
            Map<Integer, Object> box = (Map<Integer, Object>) Mod.field(Mod.ROOT + ESP, "boxTargets");
            Map<Integer, Integer> tracer = (Map<Integer, Integer>) Mod.field(Mod.ROOT + ESP, "tracerTargets");
            out.put("glow", glow.get(id));
            Object b = box.get(id);
            out.put("box", b == null ? null : Mod.call(b, "argb"));
            out.put("tracer", tracer.get(id));
            return out;
        });
    }

    // =================================================================================================================
    // 1053 Secrets HUD colour
    // =================================================================================================================

    static void secretsHudColour(UiCase c) throws Exception {
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, DI_CFG);
        int mine = 0xFF22CC44;
        try {
            Object cfg = c.onClient(mc -> Mod.cfg(DI_CFG));
            boolean has = c.onClient(mc -> {
                for (Method m : cfg.getClass().getMethods()) {
                    if (m.getName().equals("setTextColor")) {
                        return true;
                    }
                }
                return false;
            });
            c.check(has, "DungeonInfoConfig has no setTextColor");
            Screen s = DungeonB2Cases.openTab(c, "Secrets HUD");
            List<String> rows = DungeonB2Cases.labels(c, s);
            c.note("Secrets HUD rows: " + rows);
            c.check(rows.stream().anyMatch(l -> l.startsWith("Secrets Text Color")), "no 'Secrets Text Color' row");
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            if (!has) {
                return;
            }
            c.onClient(mc -> {
                Mod.call(cfg, "setSecretsHudEnabled", true);
                Mod.staticCall("secrets.DungeonState", "setRoomSim", true);
                Mod.call(cfg, "setTextColor", 0xFFFFFFFF);
                return null;
            });
            c.ticks(5);
            List<int[]> before = hudColours(c);
            c.note("default: " + describe(before));
            c.check(!before.isEmpty(), "the Secrets HUD drew no text - nothing below can mean anything");
            c.check(before.stream().allMatch(r -> r[0] == 0xFFFFFFFF), "the default Secrets HUD text is not white: " + describe(before));

            c.onClient(mc -> {
                Mod.call(cfg, "setTextColor", mine);
                Mod.call(cfg, "save");
                return null;
            });
            List<int[]> after = hudColours(c);
            c.note("set to 22CC44: " + describe(after));
            c.check(!after.isEmpty() && after.stream().allMatch(r -> r[0] == mine), "the Secrets HUD text did not take the chosen colour: " + describe(after));

            // reload from disk: the colour is read back and drawn
            c.onClient(mc -> {
                Mod.call(cfg, "setTextColor", 0xFFFFFFFF);
                Mod.staticCall(DI_CFG, "load");
                return null;
            });
            int read = c.onClient(mc -> (Integer) Mod.call(Mod.cfg(DI_CFG), "getTextColor"));
            List<int[]> reloaded = hudColours(c);
            c.note("after a reload: setting " + Integer.toHexString(read) + ", drawn " + describe(reloaded));
            c.check(read == mine, "the colour did not survive a reload: " + Integer.toHexString(read));
            c.check(!reloaded.isEmpty() && reloaded.stream().allMatch(r -> r[0] == mine), "the reloaded colour is not drawn: " + describe(reloaded));
        } finally {
            c.onClient(mc -> {
                Mod.staticCall("secrets.DungeonState", "setRoomSim", false);
                McCompat.setScreen(mc, null);
                return null;
            });
            DungeonB2Cases.restoreConfig(c, saved, DI_CFG);
        }
    }

    private static String describe(List<int[]> runs) {
        List<String> out = new ArrayList<>();
        for (int[] r : runs) {
            out.add(Integer.toHexString(r[0]));
        }
        return out.toString();
    }

    /** The colour of every text run the Secrets HUD element draws, rendered the way SimMapHudTests renders an element. */
    private static List<int[]> hudColours(UiCase c) {
        return c.onClient(mc -> {
            Object element = Mod.staticCall("hud.HudElementRegistry", "byId", "dungeon_info");
            GuiRenderState state = new GuiRenderState();
            GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
            g.pose().pushMatrix();
            try {
                Method render = Mod.cls("hud.HudElement").getMethod("render", GuiGraphicsExtractor.class, int.class, int.class);
                render.invoke(element, g, 0, 0);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("render threw: " + e.getCause(), e);
            } finally {
                g.pose().popMatrix();
            }
            List<int[]> out = new ArrayList<>();
            state.forEachText(t -> out.add(new int[]{textColour(t)}));
            return out;
        });
    }

    private static int textColour(GuiTextRenderState t) {
        try {
            java.lang.reflect.Field f = GuiTextRenderState.class.getDeclaredField("color");
            f.setAccessible(true);
            return f.getInt(t);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("GuiTextRenderState has no int field 'color'", e);
        }
    }
}

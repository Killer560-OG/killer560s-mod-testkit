package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod commissions-1103 (killer560, 2026-11-03, Dwarven Mines). Draw-only features; the session's GrimAC check covers the
 * traffic, and both cases also require that no container click and no interaction was sent.
 * <ul>
 *   <li>1103-mining-commission-mobs: on the Dwarven Mines with "Goblin Slayer: 40%" in the tab's Commissions widget, three
 *       NoAI zombies in plain sight - "[Lv25] Goblin" (name on the mob), a "Knifethrower" whose name rides an armor stand
 *       above it, and a "Glacite Walker" - and a fourth "Goblin" shut in a stone box. With Commission Mobs on, the mod boxes
 *       exactly the two visible goblins (legit rule: not the walled one), and the boxes are drawn; on a cheat jar Through
 *       Walls adds the walled one. With the option off: nothing. With "Goblin Slayer: DONE" and "Glacite Walker Slayer:
 *       10%": only the walker. Control: the zombies exist on the client by those names.</li>
 *   <li>1103-mining-emissary-label: with a DONE commission the Emissary labels are drawn, and the label drawn is a
 *       Component whose text has no section sign and whose distance part is styled gray (the "Emissary §7(186m)" bug).</li>
 * </ul>
 */
final class HxCommissions1103Cases {

    private static final String CFG = "miningqol.MiningQolConfig";
    private static final String STATE = "miningqol.MiningState";
    private static final String MOBS = "miningqol.CommissionMobs";
    private static final String HUD = "miningqol.CommissionHud";

    private HxCommissions1103Cases() {
    }

    static void register(Session s) {
        s.test("1103-mining-commission-mobs", HxCommissions1103Cases::mobs);
        s.test("1103-mining-emissary-label", HxCommissions1103Cases::label);
    }

    private static void mines(Session c, String area, String... coms) {
        c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ " + area, "Purse: 0");
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
        c.waitUntil("Dwarven Mines with " + List.of(coms), mc -> "DWARVEN_MINES".equals(Mod.staticCall(STATE, "island"))
                && ((List<?>) Mod.staticCall(STATE, "commissions")).toString().contains(coms[0].split(":")[0])
                && ((List<?>) Mod.staticCall(STATE, "commissions")).size() == coms.length, 100);
    }

    /** The id of the first entity (armor stands too when {@code stands}) whose custom name contains {@code part}, or -1.
     *  Runs on the client thread (safe inside a waitUntil predicate). */
    private static int idNamed(net.minecraft.client.Minecraft mc, String part, boolean stands) {
        for (Entity e : mc.level.entitiesForRendering()) {
            boolean stand = e instanceof net.minecraft.world.entity.decoration.ArmorStand;
            if (stand == stands && e.hasCustomName() && e.getCustomName().getString().contains(part)) {
                return e.getId();
            }
        }
        return -1;
    }

    /** The unnamed zombie standing at block x, z (scoreboard tags never reach the client), or -1. */
    private static int unnamedZombieAt(net.minecraft.client.Minecraft mc, int x, int z) {
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof net.minecraft.world.entity.monster.zombie.Zombie && !e.hasCustomName()
                    && Math.abs(e.getX() - x) < 1.0 && Math.abs(e.getZ() - z) < 1.0) {
                return e.getId();
            }
        }
        return -1;
    }

    /** CommissionMobs.targetIds(); client thread. */
    @SuppressWarnings("unchecked")
    private static List<Integer> targetsNow() {
        return new ArrayList<>((List<Integer>) Mod.staticCall(MOBS, "targetIds"));
    }

    private static List<Integer> targets(Session c) {
        return c.onClient(mc -> targetsNow());
    }

    private static void mobs(Session c) {
        BlockPos f = c.onClient(mc -> mc.player.blockPosition());
        int x = f.getX();
        int y = f.getY();
        int z = f.getZ();
        String wall = "fill " + (x - 2) + " " + y + " " + (z - 5) + " " + (x + 2) + " " + (y + 3) + " " + (z - 5);
        try (Settings set = new Settings(c).with(CFG, "CommissionHud", true).with(CFG, "CommissionMobs", true)
                .with(CFG, "CommissionMobsEsp", false)) {
            mines(c, "Royal Mines", "Goblin Slayer: 40%");
            String tail = ",NoAI:1b,Silent:1b,PersistenceRequired:1b,Invulnerable:1b}";
            c.server().command("summon zombie " + (x + 4) + " " + y + " " + (z + 4)
                    + " {Tags:[\"k1103\"],CustomName:\"[Lv25] Goblin 100/100\",CustomNameVisible:1b" + tail);
            c.server().command("summon zombie " + (x - 4) + " " + y + " " + (z + 4) + " {Tags:[\"k1103\",\"k1103knife\"]" + tail);
            c.server().command("summon armor_stand " + (x - 4) + " " + (y + 2) + " " + (z + 4)
                    + " {Tags:[\"k1103\"],CustomName:\"[Lv50] Knifethrower 500/500\",CustomNameVisible:1b,Invisible:1b,NoGravity:1b,Marker:1b}");
            c.server().command("summon zombie " + (x + 4) + " " + y + " " + (z - 4)
                    + " {Tags:[\"k1103\"],CustomName:\"[Lv100] Glacite Walker 2000/2000\",CustomNameVisible:1b" + tail);
            // The walled goblin: 7 blocks out behind a 5-wide, 4-high stone wall 5 blocks out (built in the air above
            // the floor, so clearing it with air puts the arena back exactly).
            c.server().command(wall + " stone");
            c.server().command("summon zombie " + x + " " + y + " " + (z - 7)
                    + " {Tags:[\"k1103\"],CustomName:\"[Lv25] Goblin 100/100 walled\",CustomNameVisible:1b" + tail);
            c.waitUntil("the zombies on the client", mc -> idNamed(mc, "walled", false) >= 0
                    && idNamed(mc, "Glacite Walker", false) >= 0 && unnamedZombieAt(mc, x - 4, z + 4) >= 0
                    && idNamed(mc, "Knifethrower", true) >= 0, 80);
            c.ctx().waitTicks(5);
            int[] ids = c.onClient(mc -> {
                int seen = -1;
                for (Entity e : mc.level.entitiesForRendering()) {
                    if (e.hasCustomName() && e.getCustomName().getString().equals("[Lv25] Goblin 100/100")) {
                        seen = e.getId();
                    }
                }
                return new int[]{seen, unnamedZombieAt(mc, x - 4, z + 4), idNamed(mc, "Knifethrower", true),
                        idNamed(mc, "Glacite Walker", false), idNamed(mc, "walled", false)};
            });
            int seen = ids[0];
            int knife = ids[1];
            int stand = ids[2];
            int walker = ids[3];
            int walled = ids[4];
            c.check(seen >= 0, "control: the plain goblin is not on the client");
            c.note("ids: goblin " + seen + ", knifethrower " + knife + " (stand " + stand + "), walker " + walker + ", walled " + walled);
            c.waitUntil("the visible goblins boxed", mc -> {
                List<Integer> now = targetsNow();
                return now.contains(seen) && now.contains(knife);
            }, 60);
            List<Integer> t = targets(c);
            c.check(!t.contains(walker), "a Glacite Walker was boxed for Goblin Slayer: " + t);
            c.check(!t.contains(walled), "the walled goblin was boxed without Through Walls: " + t);
            c.check(!t.contains(stand), "the name-tag stand itself was boxed: " + t);
            c.check(t.size() == 2, "want exactly the two visible goblins, got " + t);
            int frames = intCall(c, MOBS, "frames");
            c.ctx().waitTicks(10);
            c.check(intCall(c, MOBS, "frames") > frames, "the commission mob boxes were never drawn");
            c.note("legit rule: boxed " + t);

            c.ctx().runOnClient(mc -> Mod.set(CFG, "setCommissionMobsEsp", true));
            if (Mod.isCheat()) {
                c.waitUntil("Through Walls adds the walled goblin", mc -> targetsNow().contains(walled), 40);
                c.check(c.onClient(mc -> (Boolean) Mod.staticCall(MOBS, "throughWallsNow")), "boxes not drawn through walls");
                c.check(!targets(c).contains(walker), "Through Walls boxed the walker too");
                c.note("cheat Through Walls: walled goblin boxed");
            } else {
                c.ctx().waitTicks(10);
                c.check(!targets(c).contains(walled), "a legit jar boxed the walled goblin with Through Walls set");
                c.note("legit jar: Through Walls has no effect");
            }
            c.ctx().runOnClient(mc -> Mod.set(CFG, "setCommissionMobsEsp", false));

            c.ctx().runOnClient(mc -> Mod.set(CFG, "setCommissionMobs", false));
            c.waitUntil("nothing boxed with the option off", mc -> targetsNow().isEmpty(), 40);
            c.ctx().runOnClient(mc -> Mod.set(CFG, "setCommissionMobs", true));

            mines(c, "Royal Mines", "Goblin Slayer: DONE", "Glacite Walker Slayer: 10%");
            c.waitUntil("only the walker boxed", mc -> {
                List<Integer> now = targetsNow();
                return now.size() == 1 && now.contains(walker);
            }, 60);
            c.check(c.events("container.click").isEmpty(), "Commission Mobs clicked something");
            c.note("off -> none; Goblin Slayer DONE + Glacite Walker Slayer -> walker only");
        } finally {
            c.server().command("kill @e[tag=k1103]");
            c.server().command(wall + " air");
            hub(c);
        }
    }

    private static int intCall(Session c, String cls, String m) {
        return c.onClient(mc -> ((Number) Mod.staticCall(cls, m)).intValue());
    }

    private static void label(Session c) {
        try (Settings set = new Settings(c).with(CFG, "CommissionHud", true).with(CFG, "CommissionEmissaries", true)) {
            mines(c, "Lava Springs", "Goblin Slayer: DONE");
            int frames = intCall(c, HUD, "labelFrames");
            c.waitUntil("the Emissary labels drawn", mc -> ((Number) Mod.staticCall(HUD, "labelFrames")).intValue() > frames
                    && Mod.staticCall(HUD, "lastLabel") != null, 60);
            Object[] got = c.onClient(mc -> {
                Component l = (Component) Mod.staticCall(HUD, "lastLabel");
                TextColor color = l.getSiblings().isEmpty() ? null : l.getSiblings().get(0).getStyle().getColor();
                String sib = l.getSiblings().isEmpty() ? "" : l.getSiblings().get(0).getString();
                return new Object[]{l.getString(), color, sib};
            });
            String text = (String) got[0];
            c.note("label drawn: '" + text + "', distance part '" + got[2] + "' color " + got[1]);
            c.check(text.indexOf('§') < 0, "the label still carries a section sign: " + text);
            c.check(text.matches("^(Emissary|Lava Springs.*) \\(\\d+m\\)$"), "label text is not 'name (Nm)': " + text);
            c.check(TextColor.fromLegacyFormat(ChatFormatting.GRAY).equals(got[1]), "the distance is not gray: " + got[1]);
            Component direct = c.onClient(mc -> (Component) Mod.staticCall(HUD, "label", "Emissary", 186L));
            c.check("Emissary (186m)".equals(direct.getString()), "label(\"Emissary\", 186) reads " + direct.getString());
        } finally {
            hub(c);
        }
    }
}

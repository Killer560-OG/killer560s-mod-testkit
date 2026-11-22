package dev.testkit.gametest.hx;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.util.List;
import java.util.Map;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * 1114-mineshaft-*: the Glacite Mineshafts card (mod glacitecomm-c-1114) driven through what Hypixel would send: the tab's
 * Area line and a sidebar area code, chat lines, Frozen Corpse armor stands with the helmet's Skyblock id, ladder blocks and
 * Cold values. Each case checks the feature ACTED (state, a chat line, labels drawn, a command sent) before checking the
 * forged or switched-off version did nothing. Run as {@code 100-hx-session:,1114-mineshaft}.
 * <ul>
 *   <li>1114-mineshaft-defaults: every switch starts OFF.</li>
 *   <li>1114-mineshaft-detect: the shaft and its type read from the Area line and sidebar code, the portal line alerted
 *       once, a player's copy ignored, the party share sent.</li>
 *   <li>1114-mineshaft-waypoints: the arrival spot and one label per ladder column drawn, gone with the blocks.</li>
 *   <li>1114-mineshaft-corpse: two corpse stands found by helmet id and boxed, the looted one marked by its loot block,
 *       its value line printed, a player's copy of the header ignored, the party share sent.</li>
 *   <li>1114-mineshaft-hud: time and Cold lines, the warning only at the threshold.</li>
 *   <li>1114-mineshaft-scrap: the button in the Fossil Excavator menu while no scrap is held, a click asks the sack.</li>
 *   <li>1114-mineshaft-alloff: with every switch OFF nothing is drawn, said or sent.</li>
 * </ul>
 */
final class HxMineshaft1114Cases {

    private static final String K = "mineshaft.Mineshaft";
    private static final String[] ALL = {"detect", "detectParty", "waypoints", "corpses", "corpseParty", "profit", "hud", "coldWarn", "scrap"};
    private static final String SCRAP = "mineshaft.MineshaftScrap";

    private HxMineshaft1114Cases() {
    }

    static void register(Session s) {
        s.test("1114-mineshaft-defaults", HxMineshaft1114Cases::defaults);
        s.test("1114-mineshaft-detect", HxMineshaft1114Cases::detect);
        s.test("1114-mineshaft-waypoints", HxMineshaft1114Cases::waypoints);
        s.test("1114-mineshaft-corpse", HxMineshaft1114Cases::corpse);
        s.test("1114-mineshaft-hud", HxMineshaft1114Cases::hud);
        s.test("1114-mineshaft-scrap", HxMineshaft1114Cases::scrap);
        s.test("1114-mineshaft-alloff", HxMineshaft1114Cases::allOff);
        s.test("1121-mineshaft-corpse-scan", HxMineshaft1114Cases::corpseScan);
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private static Object cfg(Session c) {
        return c.onClient(mc -> Mod.field(K, "CFG"));
    }

    /** The named switches ON in memory (restored by the returned Settings). */
    private static Settings flags(Session c, String... keys) {
        Object cfg = cfg(c);
        Settings s = new Settings(c);
        for (String k : keys) {
            boolean was = c.onClient(mc -> (Boolean) Mod.call(cfg, "on", k));
            s = s.custom(() -> Mod.call(cfg, "set", k, true), () -> Mod.call(cfg, "set", k, was));
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Session c, String method) {
        return c.onClient(mc -> (List<String>) Mod.staticCall(K, method));
    }

    private static boolean inside(Session c) {
        return c.onClient(mc -> (Boolean) Mod.staticCall(K, "inside"));
    }

    /** Out of any shaft (Dwarven Mines), then into one whose sidebar carries {@code extra}; returns once it is read. */
    private static void enter(Session c, List<String> extra) {
        sidebar(c, "template", "dwarven_mines");
        tab(c, "template", "area", "area", "Dwarven Mines");
        c.waitUntil("outside the shaft", mc -> !(Boolean) Mod.staticCall(K, "inside"), 60);
        c.ctx().waitTicks(25);
        sidebar(c, "template", "dwarven_mines", "extra", extra);
        tab(c, "template", "area", "area", "Glacite Mineshaft");
        c.waitUntil("Mineshaft.inside() reads the Area line", mc -> (Boolean) Mod.staticCall(K, "inside"), 80);
    }

    private static void leave(Session c) {
        sidebar(c, "template", "dwarven_mines");
        tab(c, "template", "area", "area", "Dwarven Mines");
        c.waitUntil("outside the shaft", mc -> !(Boolean) Mod.staticCall(K, "inside"), 60);
        c.ctx().waitTicks(25);
    }

    private static List<String> chatSince(long mark, String part) {
        List<String> out = new java.util.ArrayList<>();
        for (String line : logLines(mark, "[CHAT]")) {
            if (line.replaceAll("§.", "").contains(part)) {
                out.add(line);
            }
        }
        return out;
    }

    private static void summonCorpse(Session c, String helmet, double x, double z) {
        c.server().command(String.format(java.util.Locale.ROOT, "summon armor_stand %.1f 151 %.1f {Tags:[\"msdecoy\"],NoGravity:1b,"
                + "equipment:{head:{id:\"minecraft:player_head\",count:1,components:{\"minecraft:custom_data\":{id:\"%s\"}}}}}",
                x, z, helmet));
    }

    // ---- cases ----------------------------------------------------------------------------------------------------

    private static void defaults(Session c) {
        if (!Mod.has(K)) {
            c.check(false, "the jar has no " + K);
            return;
        }
        Object cfg = cfg(c);
        for (String k : ALL) {
            c.check(!c.onClient(mc -> (Boolean) Mod.call(cfg, "on", k)), "Mineshaft switch " + k + " does not start OFF");
        }
    }

    private static void detect(Session c) {
        if (!Mod.has(K)) {
            c.check(false, "the jar has no " + K);
            return;
        }
        try (Settings set = flags(c, "detect", "detectParty")) {
            long mark = LogTap.mark();
            enter(c, List.of("TOPA_1"));
            c.waitUntil("the type reads Topaz 1", mc -> "Topaz 1".equals(Mod.staticCall(K, "type")), 80);
            c.check(!chatSince(mark, "You entered a Topaz 1 Mineshaft").isEmpty(), "no chat line named the Mineshaft type");
            String pc = awaitCommand(c, "pc Mineshaft type", 60);
            c.check(pc.contains("Topaz 1"), "party share said '/" + pc + "'");
            long m2 = LogTap.mark();
            c.hx().chat("MINESHAFT! A mineshaft portal spawned nearby!");
            c.waitUntil("the Mineshaft found alert", mc -> !chatSince(m2, "Mineshaft found").isEmpty(), 60);
            c.check(chatSince(m2, "Mineshaft found").size() == 1, "the alert came more than once");
            long m3 = LogTap.mark();
            c.hx().chat("[MVP+] Faker: MINESHAFT! A mineshaft portal spawned nearby!");
            c.ctx().waitTicks(40);
            c.check(chatSince(m3, "Mineshaft found").isEmpty(), "a player's copy of the portal line raised the alert");
        } finally {
            leave(c);
        }
    }

    private static void waypoints(Session c) {
        if (!Mod.has(K)) {
            c.check(false, "the jar has no " + K);
            return;
        }
        try (Settings set = flags(c, "waypoints")) {
            enter(c, List.of("TOPA_1"));
            c.waitUntil("the arrival spot was drawn", mc -> list(c, "drawnLabels").contains("Entrance / Exit"), 100);
            c.check(c.onClient(mc -> Mod.staticCall(K, "entrance")) != null, "no arrival spot was kept");
            c.server().command("fill -415 151 -420 -415 153 -420 minecraft:ladder[facing=west]");
            c.waitUntil("one Ladder label for the column", mc -> list(c, "drawnLabels").stream().filter("Ladder"::equals).count() == 1, 200);
            c.server().command("fill -415 151 -420 -415 153 -420 minecraft:air");
            c.waitUntil("the Ladder label gone with the blocks", mc -> !list(c, "drawnLabels").contains("Ladder"), 200);
        } finally {
            c.server().command("fill -415 151 -420 -415 153 -420 minecraft:air");
            leave(c);
        }
    }

    private static void corpse(Session c) {
        if (!Mod.has(K)) {
            c.check(false, "the jar has no " + K);
            return;
        }
        try (Settings set = flags(c, "corpses", "corpseParty", "profit")) {
            enter(c, List.of("TOPA_1"));
            summonCorpse(c, "LAPIS_ARMOR_HELMET", -417.5, -419.5);
            summonCorpse(c, "ARMOR_OF_YOG_HELMET", -421.5, -419.5);
            c.waitUntil("both corpses found", mc -> list(c, "corpses").size() == 2, 200);
            List<String> found = list(c, "corpses");
            c.check(found.contains("Lapis looted=false shared=true") || found.contains("Lapis looted=false shared=false"),
                    "no Lapis corpse: " + found);
            c.check(found.stream().anyMatch(l -> l.startsWith("Umber looted=false")), "no Umber corpse: " + found);
            c.waitUntil("both corpses labeled and boxed", mc -> list(c, "drawnLabels").contains("Lapis Corpse")
                    && list(c, "drawnLabels").contains("Umber Corpse") && (Integer) Mod.staticCall(K, "drawnBoxes") == 2, 100);
            String pc = awaitCommand(c, "pc x: ", 60);
            c.check(pc.contains("Corpse"), "corpse share said '/" + pc + "'");
            long mark = LogTap.mark();
            c.hx().chat("[MVP+] Faker: LAPIS CORPSE LOOT!");
            c.ctx().waitTicks(20);
            c.check(list(c, "corpses").stream().noneMatch(l -> l.contains("looted=true")), "a player's copy marked a corpse looted");
            c.hx().chat("LAPIS CORPSE LOOT!");
            c.hx().chat("☠ Fine Onyx Gemstone x2");
            c.hx().chat("▬".repeat(40));
            c.waitUntil("the Lapis corpse marked looted", mc -> list(c, "corpses").stream().anyMatch(l -> l.startsWith("Lapis looted=true")), 80);
            c.check(list(c, "corpses").stream().anyMatch(l -> l.startsWith("Umber looted=false")), "the Umber corpse was marked too");
            c.waitUntil("the looted label", mc -> list(c, "drawnLabels").contains("Lapis Corpse (looted)"), 100);
            c.check((Integer) c.onClient(mc -> Mod.staticCall(K, "drawnBoxes")) == 1, "a looted corpse is still boxed");
            c.waitUntil("the loot value line", mc -> !chatSince(mark, "Lapis corpse loot: about").isEmpty(), 80);
            c.check(!chatSince(mark, "of 1 items priced").isEmpty(), "the value line did not count the one loot item");
        } finally {
            c.server().command("kill @e[type=armor_stand,tag=msdecoy]");
            leave(c);
        }
    }

    private static void hud(Session c) {
        if (!Mod.has(K)) {
            c.check(false, "the jar has no " + K);
            return;
        }
        try (Settings set = flags(c, "hud", "coldWarn")) {
            enter(c, List.of("TOPA_1", "Cold: §b-40❄"));
            c.waitUntil("the HUD lines", mc -> list(c, "hudLines").size() == 3, 80);
            List<String> l = list(c, "hudLines");
            c.check(l.get(0).contains("Topaz 1"), "type missing: " + l);
            c.check(l.get(1).contains("Time in shaft"), "time missing: " + l);
            c.check(l.get(2).contains("Cold") && l.get(2).contains("40") && !l.get(2).contains("COLD"), "Cold 40 shows as a warning: " + l);
            sidebar(c, "template", "dwarven_mines", "extra", List.of("TOPA_1", "Cold: §b-75❄"));
            c.waitUntil("the warning line at Cold 75", mc -> list(c, "hudLines").size() == 3 && list(c, "hudLines").get(2).contains("COLD 75"), 80);
        } finally {
            leave(c);
        }
    }

    private static void scrap(Session c) {
        if (!Mod.has(K)) {
            c.check(false, "the jar has no " + K);
            return;
        }
        try (Settings set = flags(c, "scrap")) {
            sidebar(c, "template", "dwarven_mines");
            c.hx().stub(null, "gfs");
            c.hx().menu("Fossil Excavator", 6, Map.of(53, "minecraft:paper"), null);
            c.waitUntil("the Fossil Excavator is open", mc -> McCompat.screen(mc) instanceof AbstractContainerScreen<?> s
                    && s.getTitle().getString().equals("Fossil Excavator"), 200);
            c.waitUntil("the scrap button drew", mc -> Mod.staticCall(SCRAP, "bounds") != null, 100);
            int[] b = c.onClient(mc -> (int[]) Mod.staticCall(SCRAP, "bounds"));
            long before = commandsStarting(c, "gfs ").size();
            // A real mouse press (the screen's click event only runs through MouseHandler), through the mod's Auto Scale.
            double[] at = c.onClient(mc -> {
                float f = 1f;
                try {
                    f = ((Number) Mod.staticCall("hud.AutoScale", "appliedFactor", McCompat.screen(mc))).floatValue();
                } catch (RuntimeException | AssertionError ignored) {
                    // no Auto Scale: plain GUI units
                }
                com.mojang.blaze3d.platform.Window w = mc.getWindow();
                return new double[] {(b[0] + 5) * f * w.getScreenWidth() / (double) w.getGuiScaledWidth(),
                        (b[1] + 5) * f * w.getScreenHeight() / (double) w.getGuiScaledHeight()};
            });
            c.ctx().getInput().setCursorPos(at[0], at[1]);
            c.ctx().waitTicks(2);
            c.ctx().getInput().pressMouse(dev.testkit.compat.McKeys.mouse(0));
            c.ctx().waitTicks(4);
            String gfs = awaitCommand(c, "gfs suspicious_scrap", 60);
            c.check(gfs.endsWith(" 1"), "the button asked for '/" + gfs + "', not one scrap");
            c.check(commandsStarting(c, "gfs ").size() == before + 1, "one click sent more than one request");
        } finally {
            c.ctx().runOnClient(mc -> {
                if (mc.player != null) {
                    mc.player.closeContainer();
                }
            });
        }
    }

    /**
     * corpsescan-1121: ordinary armor stands (no helmet, a head with no Skyblock id) stand near first. Before the fix the
     * helmet lookup threw on them and FeatureGuard switched Mineshaft.tick off, so no corpse was ever found (his 26.3 log
     * 2026-11-21 23:13). Then all four corpse types: two near, Tungsten behind a stone wall, Vanguard 60 blocks out. In a
     * cheat jar this runs with Corpse Scan on (every loaded entity). "You've already looted this corpse!" marks the nearest.
     */
    private static void corpseScan(Session c) {
        if (!Mod.has(K)) {
            c.check(false, "the jar has no " + K);
            return;
        }
        boolean cheat = Mod.isCheat();
        Object cfg = cfg(c);
        if (cheat) {
            c.check(!c.onClient(mc -> (Boolean) Mod.call(cfg, "on", "corpseScan")), "Corpse Scan does not start OFF");
        }
        try (Settings set = cheat ? flags(c, "corpses", "corpseScan") : flags(c, "corpses")) {
            if (cheat) {
                c.check(c.onClient(mc -> (Boolean) Mod.staticCall(K, "scanOn")), "Corpse Scan did not turn on");
            }
            long mark = LogTap.mark();
            enter(c, List.of("TOPA_1"));
            c.server().command("summon armor_stand -418.5 151 -416.5 {Tags:[\"msdecoy\"],NoGravity:1b}");
            c.server().command("summon armor_stand -420.5 151 -416.5 {Tags:[\"msdecoy\"],NoGravity:1b,"
                    + "equipment:{head:{id:\"minecraft:player_head\",count:1}}}");
            c.ctx().waitTicks(30);
            summonCorpse(c, "LAPIS_ARMOR_HELMET", -417.5, -421.5);
            summonCorpse(c, "ARMOR_OF_YOG_HELMET", -423.5, -421.5);
            c.server().command("fill -426 150 -424 -426 154 -414 minecraft:stone");
            summonCorpse(c, "MINERAL_HELMET", -428.5, -419.5);
            summonCorpse(c, "VANGUARD_HELMET", -479.5, -419.5);
            c.waitUntil("all four corpses found", mc -> list(c, "corpses").size() == 4, 300);
            List<String> found = list(c, "corpses");
            for (String t : List.of("Lapis", "Umber", "Tungsten", "Vanguard")) {
                c.check(found.stream().anyMatch(l -> l.startsWith(t + " looted=false")), "no " + t + " corpse: " + found);
            }
            c.check(logLines(mark, "[Mineshaft.tick] threw").isEmpty(), "Mineshaft.tick threw");
            c.waitUntil("four corpses boxed", mc -> (Integer) Mod.staticCall(K, "drawnBoxes") == 4, 100);
            c.hx().chat("You've already looted this corpse!");
            c.waitUntil("the nearest corpse marked looted", mc -> list(c, "corpses").stream().anyMatch(l -> l.startsWith("Lapis looted=true")), 80);
            c.check(list(c, "corpses").stream().filter(l -> l.contains("looted=true")).count() == 1,
                    "more than the nearest corpse marked looted: " + list(c, "corpses"));
        } finally {
            c.server().command("kill @e[type=armor_stand,tag=msdecoy]");
            c.server().command("fill -426 150 -424 -426 154 -414 minecraft:air");
            leave(c);
        }
    }

    private static void allOff(Session c) {
        if (!Mod.has(K)) {
            c.check(false, "the jar has no " + K);
            return;
        }
        long mark = LogTap.mark();
        int cmds = commandsStarting(c, "pc ").size();
        enter(c, List.of("TOPA_1", "Cold: §b-80❄"));
        summonCorpse(c, "LAPIS_ARMOR_HELMET", -417.5, -419.5);
        c.hx().chat("MINESHAFT! A mineshaft portal spawned nearby!");
        c.hx().chat("LAPIS CORPSE LOOT!");
        c.hx().chat("☠ Fine Onyx Gemstone x2");
        c.hx().chat("▬".repeat(40));
        c.ctx().waitTicks(60);
        try {
            c.check(inside(c), "the Area line was not read, so this case proved nothing");
            c.check(list(c, "drawnLabels").isEmpty(), "labels drawn with everything OFF: " + list(c, "drawnLabels"));
            c.check((Integer) c.onClient(mc -> Mod.staticCall(K, "drawnBoxes")) == 0, "boxes drawn with everything OFF");
            c.check(chatSince(mark, "Mineshaft").isEmpty() && chatSince(mark, "corpse loot").isEmpty(), "chat said something with everything OFF");
            c.check(commandsStarting(c, "pc ").size() == cmds, "a party line was sent with everything OFF");
        } finally {
            c.server().command("kill @e[type=armor_stand,tag=msdecoy]");
            leave(c);
        }
    }
}

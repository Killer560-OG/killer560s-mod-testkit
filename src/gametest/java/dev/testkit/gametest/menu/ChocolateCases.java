package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * 754-755: the Chocolate Factory on Hypixel's CURRENT menu layout (killer560, 2026-10-11: "Auto chocolate factory does
 * not work. It will click the cookie but does nothing else ... There should also be a legit chocolate factory setting
 * that is just like skyhannis"). The layout is SkyHanni-REPO's HoppityEggLocations.json: Time Tower 38, Rabbit Shrine
 * 39, Coach Jackrabbit 41, employees 28-34, production 45, and 42 a menu button. The menu here is built so that the
 * old reader's slots hold traps: 39 is the Shrine (no Time Tower lore) and 42 carries a 1-chocolate "Cost" - a click on
 * either is a failure.
 * <ul>
 *   <li>754 Auto Chocolate Factory (cheat jar): the server sees, in order, the Time Tower right-click (38, button 1),
 *       the stray (slot 5, button 0), then chocolate clicks (13, button 1); every upgrade purchase is a middle click on
 *       slot 31 (the best value: +4 raw for 2,000); nothing on 39, 42 or any destructive slot; one click per tick at
 *       most. The tower and the stray are replaced server-side when clicked, as Hypixel does. Legit jar: the getter is
 *       false and nothing is clicked.</li>
 *   <li>755 Chocolate Factory Helper (both jars, the auto OFF): its reading names slot 31 best, slot 5 a stray and the
 *       Time Tower ready with 2 charges; it gives exactly one stray alert and one tower reminder; it sends NO click;
 *       its render runs without a logged failure.</li>
 * </ul>
 */
final class ChocolateCases {

    private static final String CU = "cheatutils.CheatUtilsConfig";
    private static final String CF = "chocolatefactory.ChocolateFactoryConfig";
    private static final String HELPER = "chocolatefactory.ChocolateFactoryHelper";

    private ChocolateCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "754-menu-choc-auto", ChocolateCases::auto);
        MenuSuite.test(s, "755-menu-choc-legit", ChocolateCases::legit);
    }

    private static JsonObject slot(String item, String name, String... lore) {
        JsonObject o = new JsonObject();
        o.addProperty("item", item);
        o.addProperty("name", name);
        com.google.gson.JsonArray l = new com.google.gson.JsonArray();
        for (String line : lore) {
            l.add(line);
        }
        o.add("lore", l);
        return o;
    }

    private static JsonObject rabbit(String name, String cost) {
        return slot("minecraft:player_head", "§a" + name, "§7Produces chocolate.", "", "§7Cost", "§6" + cost + " Chocolate");
    }

    /** The factory as Hypixel lays it out now. {@code actions}: replace the tower and the stray when clicked. */
    static JsonObject factory(boolean actions) {
        JsonObject spec = new JsonObject();
        spec.addProperty("title", "Chocolate Factory");
        spec.addProperty("rows", 6);
        spec.addProperty("fill", true);
        JsonObject slots = new JsonObject();
        slots.add("13", slot("minecraft:cookie", "§650,000 Chocolate", "§7Chocolate Production", "§61,000 §8per second"));
        slots.add("5", slot("minecraft:player_head", "§e§lCLICK ME!", "§7A stray rabbit!"));
        slots.add("28", rabbit("Rabbit Bro", "1,000,000"));
        slots.add("29", rabbit("Rabbit Cousin", "1,000,000"));
        slots.add("30", rabbit("Rabbit Sis", "1,000,000"));
        slots.add("31", rabbit("Rabbit Daddy", "2,000"));
        slots.add("32", rabbit("Rabbit Granny", "9,000,000"));
        slots.add("33", rabbit("Rabbit Uncle", "9,000,000"));
        slots.add("34", rabbit("Rabbit Dog", "9,000,000"));
        slots.add("38", slot("minecraft:clock", "§dTime Tower VI", "§7Status: §c§lINACTIVE", "§7Charges: §e2§7/§a3",
                "", "§7Cost", "§65,000,000 Chocolate", "", "§eRight-click to activate!"));
        // The old reader's Time Tower slot: now the Shrine, no tower lore. A click here is the old bug.
        slots.add("39", slot("minecraft:rabbit_foot", "§dRabbit Shrine III", "§7Cost", "§61 Chocolate"));
        slots.add("41", slot("minecraft:lead", "§aCoach Jackrabbit V", "§7Cost", "§6100,000 Chocolate"));
        // The old reader's Coach slot: now a menu button. Cheap on purpose - buying it is the old bug.
        slots.add("42", slot("minecraft:book", "§aChocolate Shop", "§7Cost", "§61 Chocolate"));
        slots.add("45", slot("minecraft:cocoa_beans", "§6Chocolate Production", "§7Total Multiplier: §62.000x"));
        spec.add("slots", slots);
        if (actions) {
            JsonObject on = new JsonObject();
            JsonObject tower = new JsonObject();
            JsonObject towerSet = new JsonObject();
            towerSet.add("38", slot("minecraft:clock", "§dTime Tower VI", "§7Status: §a§lACTIVE", "§7Charges: §e1§7/§a3",
                    "", "§7Cost", "§65,000,000 Chocolate"));
            tower.add("set", towerSet);
            on.add("38", tower);
            JsonObject stray = new JsonObject();
            JsonObject straySet = new JsonObject();
            straySet.add("5", slot("minecraft:black_stained_glass_pane", " "));
            stray.add("set", straySet);
            on.add("5", stray);
            spec.add("on", on);
        }
        return spec;
    }

    static void auto(Session c) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CU, "ChocolateEnabled", true).set(CU, "CfClickCookie", true).set(CU, "CfAutoUpgrade", true)
                    .set(CU, "CfClaimStrays", true).set(CU, "CfAutoTimeTower", true)
                    .set(CU, "CfMinDelayMs", 100).set(CU, "CfMaxDelayMs", 150).set(CU, "CfUpgradeDelayMs", 400)
                    .set(CF, "Enabled", false);
            boolean on = c.onClient(mc -> (Boolean) Mod.get(CU, "isChocolateEnabled"));
            MenuKit.show(c, factory(true));
            MenuKit.awaitScreen(c, "Chocolate Factory", 100);
            c.ctx().waitTicks(100);
            List<JsonObject> clicks = c.events("container.click");
            if (!MenuKit.cheat()) {
                c.check(!on && clicks.isEmpty(), "legit jar: enabled=" + on + ", " + clicks.size() + " click(s)");
                c.note("legit jar: Auto Chocolate Factory getter false, 0 clicks in 100 ticks");
                return;
            }
            List<String> seq = new ArrayList<>();
            for (JsonObject e : clicks) {
                seq.add(e.get("slot").getAsInt() + "/" + e.get("button").getAsInt() + "/" + e.get("input").getAsString());
            }
            c.note("server saw " + seq.size() + " click(s): " + seq + "; " + MenuKit.cadence(c));
            c.check(seq.size() >= 6, "only " + seq.size() + " click(s) in 100 ticks");
            c.check(!seq.isEmpty() && seq.get(0).equals("38/1/PICKUP"), "first click was not the Time Tower right-click on 38: " + seq);
            c.check(seq.size() > 1 && seq.get(1).equals("5/0/PICKUP"), "second click was not the stray on slot 5: " + seq);
            long towers = seq.stream().filter(x -> x.startsWith("38/")).count();
            long strays = seq.stream().filter(x -> x.startsWith("5/")).count();
            long cookies = seq.stream().filter(x -> x.equals("13/1/PICKUP")).count();
            long buys = seq.stream().filter(x -> x.endsWith("/2/CLONE")).count();
            long goodBuys = seq.stream().filter(x -> x.equals("31/2/CLONE")).count();
            long traps = seq.stream().filter(x -> x.startsWith("39/") || x.startsWith("42/") || x.startsWith("43/")
                    || x.startsWith("47/") || x.startsWith("48/") || x.startsWith("49/") || x.startsWith("50/")
                    || x.startsWith("51/") || x.startsWith("53/")).count();
            c.check(towers == 1, towers + " Time Tower click(s), expected exactly 1 (it reads ACTIVE afterwards)");
            c.check(strays == 1, strays + " stray click(s), expected exactly 1 (the stray is gone afterwards)");
            c.check(cookies >= 3, "only " + cookies + " chocolate click(s)");
            c.check(buys >= 1 && buys == goodBuys, buys + " purchase(s), " + goodBuys + " on slot 31 (the best value)");
            c.check(traps == 0, traps + " click(s) on the Shrine or a menu button: " + seq);
            c.check(MenuKit.maxPerTick(MenuKit.clicksPerTick(c)) <= 1, "more than one click per tick");
            int[] counts = c.onClient(mc -> (int[]) Mod.staticCall("cheatutils.ChocolateFactoryFeature", "clickCounts"));
            c.note(String.format("mod counters: chocolate %d, stray %d, tower %d, upgrade %d", counts[0], counts[1],
                    counts[2], counts[3]));
        } finally {
            MenuKit.reset(c);
        }
    }

    static void legit(Session c) throws Exception {
        MenuKit.reset(c);
        long mark = LogTap.mark();
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            if (MenuKit.cheat()) {
                cfg.set(CU, "ChocolateEnabled", false);
            }
            cfg.set(CF, "Enabled", true).set(CF, "HighlightBestUpgrade", true).set(CF, "HighlightStrays", true)
                    .set(CF, "StrayAlert", true).set(CF, "TimeTowerReminder", true).set(CF, "InfoLine", true);
            int alertsBefore = c.onClient(mc -> (Integer) Mod.staticCall(HELPER, "strayAlerts"));
            int remindBefore = c.onClient(mc -> (Integer) Mod.staticCall(HELPER, "towerReminders"));
            MenuKit.show(c, factory(false));
            MenuKit.awaitScreen(c, "Chocolate Factory", 100);
            c.ctx().waitTicks(40);
            String read = c.onClient(mc -> {
                Object st = Mod.staticCall(HELPER, "state");
                if (st == null) {
                    return "no state";
                }
                Object best = Mod.call(st, "best");
                return "best=" + (best == null ? "none" : Mod.call(best, "slot")) + " strays=" + Mod.call(st, "strays")
                        + " towerReady=" + Mod.call(st, "timeTowerReady") + " charges=" + Mod.call(st, "timeTowerCharges")
                        + " chocolate=" + Mod.call(st, "chocolate") + " line=" + Mod.staticCall(HELPER, "infoLine", st);
            });
            int alerts = c.onClient(mc -> (Integer) Mod.staticCall(HELPER, "strayAlerts")) - alertsBefore;
            int reminders = c.onClient(mc -> (Integer) Mod.staticCall(HELPER, "towerReminders")) - remindBefore;
            List<JsonObject> clicks = c.events("container.click");
            boolean renderFailed = LogTap.since(mark).stream().anyMatch(l -> l.contains("[ChocolateFactory] render failed")
                    || l.contains("[ChocolateFactory] helper tick failed"));
            c.note("helper read: " + read + "; stray alerts " + alerts + ", tower reminders " + reminders + ", clicks "
                    + clicks.size());
            c.check(read.startsWith("best=31 strays=[5] towerReady=true charges=2 chocolate=50000"), "helper read " + read);
            c.check(read.contains("Rabbit Daddy") && read.contains("affordable now"), "info line " + read);
            c.check(alerts == 1, alerts + " stray alert(s), expected 1");
            c.check(reminders == 1, reminders + " tower reminder(s), expected 1");
            c.check(clicks.isEmpty(), "the legit helper clicked: " + clicks);
            c.check(!renderFailed, "the helper logged a failure");
        } finally {
            MenuKit.reset(c);
        }
    }
}

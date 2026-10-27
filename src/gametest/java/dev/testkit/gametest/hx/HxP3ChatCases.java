package dev.testkit.gametest.hx;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod branch p3-chat-1025 (2026-10-25), cases 2140-2142, every input from the Hx bridge as Hypixel sends it:
 * <ul>
 *   <li>2140-hx-p3-status-hud: P3 Status' HUD over a P3 section 1 driven by real chat lines - section and (n/m), the
 *       terminal nearest the named player marked done, a device and a lever, who stands at an unfinished terminal, a
 *       terminal hologram ("Terminal Active" / "Inactive Terminal") overriding, forged party/all-chat lines ignored, the
 *       "Device Done" and "Section Complete" titles really set on the client's Gui, the section advancing after the gate
 *       line, and the HUD really drawn in game (its draw hook, not only its line builder).</li>
 *   <li>2141-hx-p3-titles: Hypixel's completion subtitle AND title replaced by one short subtitle on the client's Gui, a
 *       forged line and an unrelated title left alone, the Energy Laser subtitle dropped, and nothing rewritten with the
 *       setting off.</li>
 *   <li>2142-hx-dungeon-chat-hider: Chat Hider's six dungeon families each hide their real lines (from his own log) and
 *       only with their own switch on; player-typed copies, your own pickup, the Watcher's done line, a Crimson Isle
 *       boss and unrelated lines stay in the chat window; out of a dungeon nothing is hidden.</li>
 * </ul>
 */
final class HxP3ChatCases {

    private static final String CFG = "p3status.P3StatusConfig";
    private static final String P3 = "p3status.P3StatusFeature";
    private static final String TIDY_CFG = "chattidy.ChatTidyConfig";
    private static final String TIDY = "chattidy.ChatTidy";
    private static final String HIDER = "chattidy.DungeonChatHider";
    private static final String GOLDOR = "[BOSS] Goldor: Who dares trespass into my domain?";

    private HxP3ChatCases() {
    }

    static void register(Session s) {
        statusHud(s);
        titles(s);
        chatHider(s);
    }

    @SuppressWarnings("unchecked")
    private static List<String> hud(Session c) {
        return c.onClient(mc -> (List<String>) Mod.staticCall(P3, "testLines"));
    }

    private static String row(List<String> lines, String prefix) {
        for (String l : lines) {
            if (l.startsWith(prefix)) {
                return l;
            }
        }
        return null;
    }

    private static String guiText(Session c, String field) {
        return c.onClient(mc -> {
            Object v = Mod.field(McCompat.hud(mc), field);
            return v == null ? null : ((Component) v).getString().replaceAll("§.", "");
        });
    }

    private static void endP3(Session c) {
        c.hx().chat("The Core entrance is opening!");
        c.waitUntil("P3 over (core opening)", mc -> !(Boolean) Mod.staticCall("leveraura.P3SectionTracker", "running"), 60);
    }

    // ---- 2140 ---------------------------------------------------------------------------------------------------

    private static void statusHud(Session s) {
        s.test("2140-hx-p3-status-hud", c -> {
            dungeon(c);
            String self = self(c);
            String shortSelf = self.length() > 12 ? self.substring(0, 12) : self;
            try (Settings st = new Settings(c)) {
                st.custom(() -> Mod.set(CFG, "setEnabled", true), () -> Mod.set(CFG, "setEnabled", false));
                // Stand him beside S1's Terminal 2 (110, 119, 79).
                // Loaded first (forceload): the console's fill does nothing in an area nobody has loaded, and he was put
                // over no floor and fell - GrimAC Simulation x4 under load (preexisting-1026, the 787 numbers exactly).
                c.server().command("forceload add 108 77 112 81");
                c.server().sync(20);
                c.server().command("fill 108 117 77 112 117 81 minecraft:stone");
                c.server().command("tp " + self + " 110.5 118 79.5");
                c.waitUntil("standing at S1 Terminal 2", mc -> mc.player.position().distanceToSqr(110.5, 118, 79.5) < 0.01
                        && mc.player.onGround() && mc.level.getBlockState(new net.minecraft.core.BlockPos(110, 117, 79))
                        .is(net.minecraft.world.level.block.Blocks.STONE), 100);

                c.hx().chat(GOLDOR);
                c.waitUntil("HUD shows P3 Section 1", mc -> {
                    @SuppressWarnings("unchecked")
                    List<String> l = (List<String>) Mod.staticCall(P3, "testLines");
                    return !l.isEmpty() && l.get(0).startsWith("P3 Section 1");
                }, 60);
                List<String> lines = hud(c);
                c.note("start: " + lines);
                c.check(lines.size() == 9, "S1 HUD has section + 4 terminals + device + 2 levers + gate = 9 rows: " + lines);
                // Who-is-at is refreshed on the client tick after the Goldor line.
                c.waitUntil("Terminal 2 names " + shortSelf + " (who is at)", mc -> {
                    @SuppressWarnings("unchecked")
                    List<String> l = (List<String>) Mod.staticCall(P3, "testLines");
                    return ("Terminal 2 " + shortSelf).equals(row(l, "Terminal 2"));
                }, 40);
                lines = hud(c);
                c.check("Terminal 1 ✘".equals(row(lines, "Terminal 1")), "Terminal 1 not done: " + lines);

                c.hx().chat(self + " activated a terminal! (1/7)");
                c.waitUntil("Terminal 2 done", mc -> {
                    @SuppressWarnings("unchecked")
                    List<String> l = (List<String>) Mod.staticCall(P3, "testLines");
                    return "Terminal 2 ✔".equals(row(l, "Terminal 2"));
                }, 40);
                lines = hud(c);
                c.check(lines.get(0).equals("P3 Section 1 (1/7)"), "header (1/7): " + lines.get(0));
                c.check("Terminal 1 ✘".equals(row(lines, "Terminal 1")) && "Terminal 3 ✘".equals(row(lines, "Terminal 3")),
                        "only the terminal he stood at was marked: " + lines);

                // Forged: what another player can put in chat.
                c.hx().chat("Party > [MVP+] Eve: a activated a terminal! (2/7)");
                c.hx().chat("[VIP] Bob: a completed a device! (2/7)");
                c.ctx().waitTicks(10);
                lines = hud(c);
                c.check(lines.get(0).equals("P3 Section 1 (1/7)") && "Device ✘".equals(row(lines, "Device")),
                        "forged lines changed the HUD: " + lines);

                c.hx().chat("HxMateA completed a device! (2/7)");
                c.waitUntil("Device done", mc -> {
                    @SuppressWarnings("unchecked")
                    List<String> l = (List<String>) Mod.staticCall(P3, "testLines");
                    return "Device ✔".equals(row(l, "Device"));
                }, 40);
                String bigTitle = guiText(c, "title");
                c.check("Device Done".equals(bigTitle), "Gui title after the device line: " + bigTitle);

                c.hx().chat("HxMateB activated a lever! (3/7)");
                c.waitUntil("Lever 1 done", mc -> {
                    @SuppressWarnings("unchecked")
                    List<String> l = (List<String>) Mod.staticCall(P3, "testLines");
                    return "Lever 1 ✔".equals(row(l, "Lever 1"));
                }, 40);

                // Holograms win: Terminal 1's says done with no chat line; Terminal 3's says not done.
                c.hx().stand(110, 113, 73, "Terminal Active");
                c.hx().stand(90, 112, 92, "Inactive Terminal");
                c.waitUntil("Terminal 1 done from its hologram", mc -> {
                    @SuppressWarnings("unchecked")
                    List<String> l = (List<String>) Mod.staticCall(P3, "testLines");
                    return "Terminal 1 ✔".equals(row(l, "Terminal 1"));
                }, 60);
                c.check("Terminal 3 ✘".equals(row(hud(c), "Terminal 3")), "Terminal 3 under 'Inactive Terminal': " + hud(c));

                // Drawn in game, not just built.
                c.waitUntil("HUD drawn in game", mc -> String.valueOf(Mod.staticCall(P3, "testLastDrawn")).contains("P3 Section 1"), 60);
                c.note("drawn: " + String.valueOf((Object) c.onClient(mc -> Mod.staticCall(P3, "testLastDrawn"))).replace('\n', '|'));

                // Finish the section: (7/7) gives Section Complete; the gate line then moves to S2.
                for (int i = 4; i <= 7; i++) {
                    c.hx().chat("HxMateA activated a terminal! (" + i + "/7)");
                }
                c.waitUntil("Section Complete title", mc -> "Section Complete".equals(String.valueOf(Mod.staticCall(P3, "testLastBigTitle"))), 40);
                bigTitle = guiText(c, "title");
                c.check("Section Complete".equals(bigTitle), "Gui title after (7/7): " + bigTitle);
                c.check("Gate ✘".equals(row(hud(c), "Gate")), "gate still up: " + hud(c));
                c.hx().chat("The gate has been destroyed!");
                c.waitUntil("HUD on Section 2", mc -> {
                    @SuppressWarnings("unchecked")
                    List<String> l = (List<String>) Mod.staticCall(P3, "testLines");
                    return !l.isEmpty() && l.get(0).startsWith("P3 Section 2");
                }, 40);
                lines = hud(c);
                c.check(row(lines, "Terminal 5") != null && "Terminal 5 ✘".equals(row(lines, "Terminal 5")),
                        "S2 lists 5 terminals, none done: " + lines);
                c.note("S2: " + lines);

                // Off: nothing listed.
                run(c, () -> Mod.set(CFG, "setShowHud", false));
                try {
                    c.ctx().waitTicks(2);
                    c.check(hud(c).isEmpty(), "Status HUD off still lists rows: " + hud(c));
                } finally {
                    run(c, () -> Mod.set(CFG, "setShowHud", true));
                }
            } finally {
                c.hx().removeStands();
                endP3(c);
                c.server().command("tp " + self + " -419.5 151 -419.5");
                c.server().command("forceload remove 108 77 112 81");
            }
        });
    }

    // ---- 2141 ---------------------------------------------------------------------------------------------------

    private static void titles(Session s) {
        s.test("2141-hx-p3-titles", c -> {
            dungeon(c);
            String self = self(c);
            try (Settings st = new Settings(c)) {
                st.custom(() -> Mod.set(CFG, "setEnabled", true), () -> Mod.set(CFG, "setEnabled", false));
                long replaced0 = c.onClient(mc -> (Long) Mod.staticCall(P3, "testReplaced"));

                c.hx().title("", self + " activated a terminal! (3/7)");
                c.waitUntil("subtitle rewritten", mc -> {
                    Object v = Mod.field(McCompat.hud(mc), "subtitle");
                    return v != null && (self + " Terminal 3/7").equals(((Component) v).getString().replaceAll("§.", ""));
                }, 40);
                c.note("subtitle form -> '" + guiText(c, "subtitle") + "'");

                c.hx().title(self + " completed a device! (4/7)", "");
                c.waitUntil("title-slot line rewritten into the subtitle", mc -> {
                    Object v = Mod.field(McCompat.hud(mc), "subtitle");
                    return v != null && (self + " Device 4/7").equals(((Component) v).getString().replaceAll("§.", ""));
                }, 40);
                String title = guiText(c, "title");
                c.check(title == null || !title.contains("completed a device"), "Hypixel's title still shown: " + title);
                long replaced = c.onClient(mc -> (Long) Mod.staticCall(P3, "testReplaced")) - replaced0;
                c.check(replaced == 2, "2 replacements counted, got " + replaced);

                // Forged and unrelated: left exactly as sent.
                c.hx().title("", "[VIP] Bob: a activated a terminal! (5/7)");
                c.waitUntil("forged subtitle shown as sent", mc -> {
                    Object v = Mod.field(McCompat.hud(mc), "subtitle");
                    return v != null && ((Component) v).getString().equals("[VIP] Bob: a activated a terminal! (5/7)");
                }, 40);
                c.hx().title("Hello", "World");
                c.waitUntil("unrelated title shown", mc -> "Hello".equals(((Component) Mod.field(McCompat.hud(mc), "title")).getString())
                        && "World".equals(((Component) Mod.field(McCompat.hud(mc), "subtitle")).getString()), 40);

                // Energy Laser subtitle dropped: the earlier subtitle stays.
                long dropped0 = c.onClient(mc -> (Long) Mod.staticCall(P3, "testDropped"));
                c.hx().title("", "The Energy Laser is charging up!");
                c.ctx().waitTicks(10);
                String sub = guiText(c, "subtitle");
                c.check("World".equals(sub), "laser subtitle not dropped, subtitle now: " + sub);
                long dropped = c.onClient(mc -> (Long) Mod.staticCall(P3, "testDropped")) - dropped0;
                c.check(dropped == 1, "1 laser subtitle dropped, counted " + dropped);

                // Setting off: Hypixel's line shown verbatim.
                st.custom(() -> Mod.set(CFG, "setRewriteTitles", false), () -> Mod.set(CFG, "setRewriteTitles", true));
                c.hx().title("", self + " activated a lever! (6/7)");
                c.waitUntil("verbatim with Short Progress Titles off", mc -> {
                    Object v = Mod.field(McCompat.hud(mc), "subtitle");
                    return v != null && (self + " activated a lever! (6/7)").equals(((Component) v).getString());
                }, 40);
                c.note("rewrite x2, forged/unrelated untouched, laser dropped, off -> verbatim");
            }
        });
    }

    // ---- 2142 ---------------------------------------------------------------------------------------------------

    /** Real lines (his 26.1.2 Dungeons log, 2026-09..10), as Hx sends them; family -> lines. */
    private static List<String[]> hideLines(String self) {
        List<String[]> out = new ArrayList<>();
        out.add(new String[]{"BOSS", "§4[BOSS] Maxor§c: §cWELL WELL WELL LOOK WHO'S HERE!"});
        out.add(new String[]{"BOSS", "[BOSS] The Watcher: This guy looks like a fighter."});
        out.add(new String[]{"BOSS", "[BOSS] Goldor: You have done it, you destroyed the factory…"});
        out.add(new String[]{"BOSS", "[BOSS] The Professor: I was burdened with terrible news recently..."});
        out.add(new String[]{"BOSS", "[BOSS] Scream Livid: I respect you for making it to here, but I'll be your undoing."});
        out.add(new String[]{"BOSS", "[BOSS] Necron: Finally, I heard so much about you."});
        out.add(new String[]{"MORT", "§e[NPC] §bMort§f: Here, I found this map when I first entered the dungeon."});
        out.add(new String[]{"MORT", "[NPC] Mort: Good luck."});
        out.add(new String[]{"BLESSINGS", "DUNGEON BUFF! You found a Blessing of Life V! (44s)"});
        out.add(new String[]{"BLESSINGS", "     Granted you +1.18x HP and +1.18x ❣ Health Regen."});
        out.add(new String[]{"BLESSINGS", "DUNGEON BUFF! A Blessing of Power V was found! (1m 2s)"});
        out.add(new String[]{"BLESSINGS", "DUNGEON BUFF! TimTaroo found a Blessing of Stone I!"});
        out.add(new String[]{"BLESSINGS", "A Blessing of Wisdom was picked up!"});
        out.add(new String[]{"BLESSINGS", "[MVP+] HxMateA has obtained Blessing of Power!"});
        out.add(new String[]{"PICKUPS", "[MVP+] HxMateA has obtained Wither Key!"});
        out.add(new String[]{"PICKUPS", "HxMateB has obtained Blood Key!"});
        out.add(new String[]{"PICKUPS", "[VIP] HxMateB has obtained Superboom TNT x2!"});
        out.add(new String[]{"PICKUPS", "HxMateA has obtained Revive Stone!"});
        out.add(new String[]{"PICKUPS", "A Wither Key was picked up!"});
        out.add(new String[]{"ABILITY", "This ability is on cooldown for 3s."});
        out.add(new String[]{"ABILITY", "Guided Sheep is now available!"});
        out.add(new String[]{"ABILITY", "Thunderstorm is ready to use! Press DROP to activate it!"});
        out.add(new String[]{"ABILITY", "Used Healing Circle!"});
        out.add(new String[]{"ABILITY", "Your Archer ULTIMATE Rapid Fire is now available!"});
        out.add(new String[]{"ABILITY", "Creeper Veil De-activated! (Expired)"});
        out.add(new String[]{"ABILITY", "◕ HxMateA picked up your Damage Orb!"});
        out.add(new String[]{"ABILITY", "HxMateB formed a tether with you!"});
        out.add(new String[]{"BREAKER", "A mystical force prevents you from digging that block!"});
        out.add(new String[]{"BREAKER", "A mystical force prevents you digging there!"});
        out.add(new String[]{"BREAKER", "A mystical force prevents you digging in this room!"});
        out.add(new String[]{"BREAKER", "You don't have enough charges to break this block right now!"});
        return out;
    }

    private static final String[][] SWITCHES = {{"BOSS", "HideBossDialogue"}, {"MORT", "HideMort"},
            {"BLESSINGS", "HideBlessings"}, {"PICKUPS", "HideOthersPickups"}, {"ABILITY", "HideAbilitySpam"},
            {"BREAKER", "HideDungeonBreaker"}};

    @SuppressWarnings("unchecked")
    private static List<String> chatWindow(Session c, int n) {
        return c.onClient(mc -> (List<String>) Mod.staticCall(TIDY, "testChatLines", n));
    }

    private static String plain(String s) {
        return s.replaceAll("§.", "");
    }

    private static void chatHider(Session s) {
        s.test("2142-hx-dungeon-chat-hider", c -> {
            dungeon(c);
            String self = self(c);
            List<String[]> lines = hideLines(self);
            List<String> keep = List.of(
                    "Party > [MVP+] Eve: [BOSS] Maxor: WELL WELL WELL LOOK WHO'S HERE!",
                    "[287] [MVP+] Eve: A mystical force prevents you from digging that block!",
                    "Guild > Eve [Member]: [NPC] Mort: Good luck.",
                    "[BOSS] The Watcher: You have proven yourself. You may pass.",
                    "[BOSS] Bal: Hi there",
                    self + " has obtained Wither Key!",
                    "[MVP+] " + self + " has obtained Superboom TNT!",
                    "Second Wind Activated! Your Spirit Mask saved your life!",
                    "HxMateA's Wish healed you for 1,000 health and granted you an absorption shield with 500 health!",
                    "P3 hider control line");
            try (Settings st = new Settings(c)) {
                st.custom(() -> Mod.set(TIDY_CFG, "setEnabled", true), () -> Mod.set(TIDY_CFG, "setEnabled", false));
                st.custom(() -> Mod.set(TIDY_CFG, "setStackDuplicates", false), () -> Mod.set(TIDY_CFG, "setStackDuplicates", false));
                st.custom(() -> Mod.set(TIDY_CFG, "setHideDamageMessages", false), () -> Mod.set(TIDY_CFG, "setHideDamageMessages", false));
                for (String[] sw : SWITCHES) {
                    st.custom(() -> Mod.set(TIDY_CFG, "set" + sw[1], false), () -> Mod.set(TIDY_CFG, "set" + sw[1], false));
                }
                // Each family with ONLY its own switch on: its lines hidden, every other family's shown.
                for (String[] sw : SWITCHES) {
                    run(c, () -> Mod.set(TIDY_CFG, "set" + sw[1], true));
                    long before = c.onClient(mc -> (Long) Mod.staticCall(HIDER, "testHidden", sw[0]));
                    int expected = 0;
                    for (String[] l : lines) {
                        String marker = "p3hider marker " + sw[0] + " " + expected + " " + System.nanoTime();
                        c.hx().chat(marker);
                        c.hx().chat(l[1]);
                        c.ctx().waitTicks(2);
                        List<String> w = chatWindow(c, 2);
                        boolean hidden = w.get(0).equals(marker);
                        boolean want = l[0].equals(sw[0]);
                        c.check(hidden == want, (want ? "NOT hidden" : "hidden by the wrong switch") + " with only "
                                + sw[1] + " on: " + l[1] + " (newest: " + w + ")");
                        if (want) {
                            expected++;
                        }
                    }
                    long counted = c.onClient(mc -> (Long) Mod.staticCall(HIDER, "testHidden", sw[0])) - before;
                    c.check(counted == expected, sw[0] + ": " + expected + " hidden, mod counted " + counted);
                    c.note(sw[1] + ": " + expected + " lines hidden, the other " + (lines.size() - expected) + " shown");
                    run(c, () -> Mod.set(TIDY_CFG, "set" + sw[1], false));
                }
                // All on: the keep list still shows, every line exactly as sent.
                for (String[] sw : SWITCHES) {
                    run(c, () -> Mod.set(TIDY_CFG, "set" + sw[1], true));
                }
                for (String k : keep) {
                    c.hx().chat(k);
                    c.ctx().waitTicks(2);
                    List<String> w = chatWindow(c, 1);
                    c.check(plain(w.get(0)).equals(plain(k)), "kept line hidden or changed: " + k + " (newest: " + w + ")");
                }
                c.note("all six on: " + keep.size() + " look-alike / own / kept lines shown");
                // The master switch off: nothing hidden.
                run(c, () -> Mod.set(TIDY_CFG, "setEnabled", false));
                c.hx().chat(lines.get(0)[1]);
                c.ctx().waitTicks(2);
                c.check(plain(chatWindow(c, 1).get(0)).equals(plain(lines.get(0)[1])), "Chat Hider off still hid " + lines.get(0)[1]);
                run(c, () -> Mod.set(TIDY_CFG, "setEnabled", true));
                // Out of a dungeon: shown.
                hub(c);
                for (String l : List.of("[BOSS] Maxor: hi", "This ability is on cooldown for 3s.")) {
                    c.hx().chat(l);
                    c.ctx().waitTicks(2);
                    c.check(chatWindow(c, 1).get(0).equals(l), "hidden outside a dungeon: " + l);
                }
                c.note("master off -> shown; out of a dungeon -> shown");
            } finally {
                dungeon(c);
            }
        });
    }
}

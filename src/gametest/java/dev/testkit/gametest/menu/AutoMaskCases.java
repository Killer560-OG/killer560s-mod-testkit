package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.FrameClock;
import dev.testkit.harness.PacketTrace;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.world.entity.EquipmentSlot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Auto Mask's modes, swap order and gates (killer560, 2026-10-16: "for auto mask have a swap keybind and a full auto
 * mode. It should have an option of swap mask first or swap phoenix first ... Also have a dungeons only toggle, a boss
 * only toggle"; and "change the mask ms timer to be in ticks"; mod branch auto-mask-1016). Cheat jar only.
 * <p>
 * Hypixel is faked in the session's Hx server: {@code /stats} opens "Your Equipment and Stats" with {@code equipHelmet}
 * (a click on a helmet in his own inventory puts it on, as Hypixel's does, and the server records a {@code menu.equip});
 * {@code /pets} opens a Pets menu whose Phoenix, clicked, says "You summoned your Phoenix!" and closes. Masks are
 * player heads with Hypixel's {@code id}s, the procs are Hypixel's own chat lines sent by the server, and the key is a
 * real key press (keypad 8). Every swap is proven by the HELMET the server put on (the client reads it back after the
 * server's update) and by the command and click the server received. Every case runs on the session's GrimAC server and
 * fails on any flag.
 * <ul>
 *   <li>1880 Keybind: no mask worn -> Spirit; Spirit worn -> Bonzo; Bonzo worn -> Spirit; Spirit worn with Bonzo on
 *       cooldown -> Phoenix through the pet menu, helmet unchanged; then everything used -> nothing sent, one chat line.</li>
 *   <li>1881 Full Auto: 60 ticks with no proc -> nothing; Spirit procs -> Bonzo; a proc while chat is open -> nothing
 *       until it closes, then the swap; the key does nothing in Full Auto.</li>
 *   <li>1882 Swap Order: Phoenix First summons Phoenix on a mask proc (no /stats); Mask First swaps the mask (no /pets);
 *       Phoenix First with Phoenix already summoned goes to the other mask.</li>
 *   <li>1883 Dungeons Only outside a dungeon: a proc and a press do nothing; control, the switch off: the press swaps.</li>
 *   <li>1884 Boss Only: during the F7 clear a proc does nothing; after Maxor's line (boss) the proc swaps.</li>
 *   <li>1885 settings survive save + load; an old file (Auto Swap on, 350 ms) loads as Full Auto, on, 7 ticks; ms/50
 *       rounding at the ends; the tab's rows in order, Mode and Swap Order cycling, tooltips on each.</li>
 *   <li>1886 Swap Step Delay waits whole ticks: /stats goes out exactly 7 ticks later at 10 ticks than at 3.</li>
 * </ul>
 */
final class AutoMaskCases {

    private static final String CFG = "maskinvincibility.MaskInvincibilityConfig";
    private static final String FEAT = "maskinvincibility.MaskInvincibilityFeature";
    private static final String SW = "maskinvincibility.MaskSwapper";
    private static final String AM = "maskinvincibility.AutoMask";
    /** GLFW_KEY_KP_8: bound to nothing in vanilla or the mod's defaults. */
    private static final int KEY = 328;

    private static final String SPIRIT = "minecraft:player_head[custom_data={id:\"SPIRIT_MASK\"},"
            + "custom_name={text:\"Spirit Mask\",color:\"blue\",italic:false}]";
    private static final String BONZO = "minecraft:player_head[custom_data={id:\"BONZO_MASK\"},"
            + "custom_name={text:\"Bonzo's Mask\",color:\"blue\",italic:false}]";
    private static final String PHOENIX_PET = "minecraft:player_head[custom_data={id:\"PET\",uuid:\"hx-phoenix\","
            + "petInfo:'{\"type\":\"PHOENIX\",\"tier\":\"LEGENDARY\"}'},custom_name=[{text:\"[Lvl 100] \",color:\"gray\","
            + "italic:false},{text:\"Phoenix\",color:\"gold\",italic:false}],lore=[{text:\"Left-click to summon!\","
            + "color:\"yellow\",italic:false}]]";

    private static final String SPIRIT_PROC = "Second Wind Activated! Your Spirit Mask saved your life!";
    private static final String BONZO_PROC = "Your Bonzo's Mask saved your life!";
    private static final String MAXOR = "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!";

    private AutoMaskCases() {
    }

    static void register(Session s) {
        // Auto Mask is cheat-only (compiled out of legit); on a legit jar these eight read as failures in the legit menu
        // suite (preexisting-1026). Its absence from the legit menu is 511/512's job, as for every cheat tab.
        if (!Mod.isCheat()) {
            return;
        }
        MenuSuite.test(s, "1880-menu-automask-key", AutoMaskCases::keybind);
        MenuSuite.test(s, "1881-menu-automask-fullauto", AutoMaskCases::fullAuto);
        MenuSuite.test(s, "1882-menu-automask-order", AutoMaskCases::order);
        MenuSuite.test(s, "1883-menu-automask-dungeons-only", AutoMaskCases::dungeonsOnly);
        MenuSuite.test(s, "1884-menu-automask-boss-only", AutoMaskCases::bossOnly);
        MenuSuite.test(s, "1885-menu-automask-settings", AutoMaskCases::settings);
        MenuSuite.test(s, "1886-menu-automask-step-ticks", AutoMaskCases::stepTicks);
        MenuSuite.test(s, "1887-menu-automask-rod", AutoMaskCases::rod);
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------------

    private static JsonObject equipmentMenu() {
        return MenuKit.obj("{\"title\":\"Your Equipment and Stats\",\"rows\":6,\"fill\":true,\"equipHelmet\":true,\"on\":{}}");
    }

    private static JsonObject petsMenu() {
        JsonObject spec = MenuKit.obj("{\"title\":\"Pets\",\"rows\":6,\"fill\":true,\"slots\":{},\"on\":{\"10\":"
                + "{\"chat\":\"You summoned your Phoenix!\",\"close\":true}}}");
        spec.getAsJsonObject("slots").addProperty("10", PHOENIX_PET);
        return spec;
    }

    /** Runs {@code body} with Auto Mask on in {@code mode} / {@code order}, the fake menus answering /stats and /pets,
     *  in an F7 dungeon (unless {@code dungeon} is false), with every timer and swap state cleared; puts it all back. */
    private static void with(Session c, String mode, String order, boolean dungeon, Session.Case body) throws Exception {
        with(c, mode, order, dungeon, "PETS", body);
    }

    private static void with(Session c, String mode, String order, boolean dungeon, String phoenixRoute, Session.Case body)
            throws Exception {
        MenuKit.reset(c);
        c.check(Mod.isCheat(), "Auto Mask is cheat-only; this case needs the cheat jar");
        if (dungeon) {
            DungeonMenuCases.inDungeon(c);
        } else {
            DungeonMenuCases.leaveDungeon(c);
        }
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CFG, "Enabled", true).set(CFG, "ShowSpirit", true).set(CFG, "ShowBonzo", true)
                    .set(CFG, "ShowPhoenix", true).set(CFG, "AnnounceToParty", false)
                    .set(CFG, "AutoMaskMode", Mod.enumValue(CFG + "$AutoMaskMode", mode))
                    .set(CFG, "SwapOrder", Mod.enumValue(CFG + "$SwapOrder", order))
                    .set(CFG, "SwapKey", KEY).set(CFG, "AutoMaskDungeonsOnly", true).set(CFG, "AutoMaskBossOnly", false)
                    .set(CFG, "SwapStepDelayTicks", 3)
                    .set(CFG, "PhoenixRoute", Mod.enumValue(CFG + "$PhoenixRoute", phoenixRoute))
                    .set(CFG, "AutoSwapEnabled", true);
            boolean on = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(CFG), "isAutoSwapEnabled")
                    && (Boolean) Mod.call(Mod.cfg(CFG), "isEnabled"));
            c.check(on, "Auto Mask / Mask Invincibility read OFF after switching them on (Skyblock Only gate?)");
            c.hx().call("menu.onCommand", "name", "stats", "menu", equipmentMenu());
            c.hx().call("menu.onCommand", "name", "pets", "menu", petsMenu());
            resetState(c, false);
            body.run(c);
        } finally {
            c.ctx().getInput().releaseKey(KEY);
            c.ctx().runOnClient(mc -> {
                if (McCompat.screen(mc) instanceof ChatScreen) {
                    McCompat.setScreen(mc, null);
                }
            });
            c.hx().call("menu.onCommand", "name", "stats", "menu", null);
            c.hx().call("menu.onCommand", "name", "pets", "menu", null);
            c.waitUntil("MaskSwapper idle at the end", mc -> !(Boolean) Mod.staticCall(SW, "isBusy"), 200);
            wear(c, "minecraft:air", "minecraft:air", "minecraft:air");
            resetState(c, false);
            MenuKit.reset(c);
        }
    }

    /** Clears the proc timers and the swapper's per-world state, its command floor and its Phoenix memory. */
    @SuppressWarnings("unchecked")
    private static void resetState(Session c, boolean keepPhoenixOut) {
        c.waitUntil("MaskSwapper idle", mc -> !(Boolean) Mod.staticCall(SW, "isBusy"), 200);
        c.ctx().runOnClient(mc -> {
            ((Map<Object, Object>) Mod.field(FEAT, "cooldownRemaining")).replaceAll((k, v) -> 0);
            ((Map<Object, Object>) Mod.field(FEAT, "activeRemaining")).replaceAll((k, v) -> 0);
            ((Set<?>) Mod.field(SW, "unavailable")).clear();
            Mod.setField(SW, "lastCommandMs", Long.MIN_VALUE / 4);
            Mod.setField(SW, "lastPhoenixRodThrowMs", 0L);
            Mod.setField(SW, "lastPhoenixConfirmedSummonMs", 0L);
            if (!keepPhoenixOut) {
                Mod.setField(SW, "phoenixSummoned", false);
            }
        });
    }

    /** Puts {@code head} on and {@code slot9}/{@code slot10} in his inventory, server side, and waits for the client. */
    private static void wear(Session c, String head, String slot9, String slot10) {
        c.server().command("item replace entity @a armor.head with " + head);
        c.server().command("item replace entity @a container.9 with " + slot9);
        c.server().command("item replace entity @a container.10 with " + slot10);
        String want = idOf(head);
        c.waitUntil("the client to see helmet " + want, mc -> want.equals(helmet(mc)), 60);
        c.ctx().waitTicks(2);
    }

    private static String idOf(String stack) {
        return stack.contains("SPIRIT_MASK") ? "SPIRIT_MASK" : stack.contains("BONZO_MASK") ? "BONZO_MASK" : "";
    }

    private static String helmet(net.minecraft.client.Minecraft mc) {
        Object id = Mod.staticCall("cheatutils.CheatUtils", "skyblockId", mc.player.getItemBySlot(EquipmentSlot.HEAD));
        return id == null ? "" : String.valueOf(id);
    }

    private static String helmet(Session c) {
        return c.onClient(AutoMaskCases::helmet);
    }

    private static void press(Session c) {
        c.ctx().getInput().holdKeyFor(KEY, 3);
    }

    private static void proc(Session c, String line) {
        c.hx().call("chat", "text", line);
    }

    private static long count(Session c, String command) {
        return c.commands().stream().filter(command::equals).count();
    }

    private static long petClicks(Session c) {
        return c.events("menu.click").stream()
                .filter(e -> "Pets".equals(e.get("title").getAsString()) && e.get("slot").getAsInt() == 10).count();
    }

    private static String lastDecision(Session c) {
        return c.onClient(mc -> String.valueOf(Mod.staticCall(AM, "lastDecision")));
    }

    /** Waits for the swap to put {@code want} on his head and the swapper to finish; proves one /stats and one server
     *  equip of exactly that mask over what was worn. Returns a note. */
    private static String expectMaskSwap(Session c, String label, String want, long statsBefore, int equipsBefore,
                                         String wasWorn) {
        c.waitUntil(label + ": helmet " + want, mc -> want.equals(helmet(mc)), 200);
        c.waitUntil(label + ": swapper idle", mc -> !(Boolean) Mod.staticCall(SW, "isBusy"), 100);
        c.waitUntil(label + ": no screen left", mc -> McCompat.screen(mc) == null, 60);
        List<JsonObject> equips = c.events("menu.equip");
        c.check(count(c, "stats") == statsBefore + 1, label + ": want one /stats, server saw " + c.commands());
        c.check(equips.size() == equipsBefore + 1, label + ": want one equip, got " + equips);
        JsonObject e = equips.get(equips.size() - 1);
        c.check(want.equals(e.get("skyblockId").getAsString()) && wasWorn.equals(e.get("previous").getAsString()),
                label + ": server equipped " + e + ", want " + want + " over '" + wasWorn + "'");
        return label + ": /stats + click on inventory slot " + e.get("inventorySlot").getAsInt() + ", server put on "
                + want + " over '" + wasWorn + "', client helmet " + helmet(c);
    }

    // ---- cases -------------------------------------------------------------------------------------------------------

    static void keybind(Session c) throws Exception {
        with(c, "KEYBIND", "MASK_FIRST", true, s -> {
            List<String> notes = new ArrayList<>();
            // No mask worn: Mask First picks Spirit.
            wear(c, "minecraft:air", SPIRIT, BONZO);
            press(c);
            notes.add(expectMaskSwap(c, "none worn", "SPIRIT_MASK", 0, 0, ""));
            // Spirit worn: the other mask.
            resetState(c, false);
            wear(c, SPIRIT, "minecraft:air", BONZO);
            press(c);
            notes.add(expectMaskSwap(c, "Spirit worn", "BONZO_MASK", 1, 1, "SPIRIT_MASK"));
            resetState(c, false);
            wear(c, BONZO, SPIRIT, "minecraft:air");
            press(c);
            notes.add(expectMaskSwap(c, "Bonzo worn", "SPIRIT_MASK", 2, 2, "BONZO_MASK"));
            // Spirit worn, Bonzo on cooldown (its real proc line; Keybind mode only times it): Phoenix via the pet menu.
            resetState(c, false);
            wear(c, SPIRIT, "minecraft:air", BONZO);
            proc(c, BONZO_PROC);
            c.waitUntil("Bonzo's cooldown running", mc -> {
                for (Map.Entry<?, ?> en : ((Map<?, ?>) Mod.field(FEAT, "cooldownRemaining")).entrySet()) {
                    if (en.getKey().toString().equals("BONZO")) {
                        return ((Number) en.getValue()).intValue() > 0;
                    }
                }
                return false;
            }, 40);
            c.ctx().waitTicks(10);
            c.check(count(c, "stats") == 3 && count(c, "pets") == 0, "a Bonzo proc in Keybind mode swapped: " + c.commands());
            press(c);
            c.waitUntil("Phoenix summoned (Hypixel's line)", mc -> (Boolean) Mod.staticCall(SW, "isPhoenixSummoned"), 200);
            c.waitUntil("swapper idle after Phoenix", mc -> !(Boolean) Mod.staticCall(SW, "isBusy"), 100);
            c.waitUntil("no screen after Phoenix", mc -> McCompat.screen(mc) == null, 60);
            c.check(count(c, "pets") == 1 && count(c, "stats") == 3, "Bonzo on cooldown: want one /pets and no /stats, saw "
                    + c.commands());
            c.check(petClicks(c) == 1, "want one click on the Phoenix (Pets slot 10), got " + petClicks(c));
            c.check("SPIRIT_MASK".equals(helmet(c)), "Phoenix swap changed the helmet: " + helmet(c));
            notes.add("Spirit worn, Bonzo on cooldown: /pets + click on Phoenix, Hypixel's summon line read, helmet still "
                    + helmet(c));
            // Now: Spirit worn, Bonzo on cooldown, Phoenix out -> nothing to swap to; nothing sent.
            c.ctx().runOnClient(mc -> Mod.setField(SW, "lastCommandMs", Long.MIN_VALUE / 4));
            long seq = c.onClient(mc -> (Long) Mod.staticCall(AM, "decisionSeq"));
            press(c);
            c.waitUntil("Auto Mask's decision", mc -> (Long) Mod.staticCall(AM, "decisionSeq") > seq, 40);
            c.ctx().waitTicks(20);
            c.check(lastDecision(c).startsWith("nothing available"), "want 'nothing available', got " + lastDecision(c));
            c.check(count(c, "pets") == 1 && count(c, "stats") == 3, "nothing available still sent: " + c.commands());
            notes.add("then everything used: '" + lastDecision(c) + "', nothing sent (" + count(c, "stats") + " /stats, "
                    + count(c, "pets") + " /pets in total)");
            notes.forEach(c::note);
        });
    }

    static void fullAuto(Session c) throws Exception {
        with(c, "FULL_AUTO", "MASK_FIRST", true, s -> {
            List<String> notes = new ArrayList<>();
            wear(c, SPIRIT, "minecraft:air", BONZO);
            c.ctx().waitTicks(60);
            press(c); // the key means nothing in Full Auto
            c.ctx().waitTicks(20);
            c.check(c.commands().isEmpty() && c.events("menu.equip").isEmpty() && c.events("container.click").isEmpty(),
                    "Full Auto acted without a proc: " + c.commands() + " " + c.events("menu.equip"));
            c.check("SPIRIT_MASK".equals(helmet(c)), "helmet changed without a proc: " + helmet(c));
            notes.add("80 ticks without a proc (one key press among them): 0 commands, 0 clicks, helmet " + helmet(c));
            proc(c, SPIRIT_PROC);
            notes.add(expectMaskSwap(c, "Spirit proc", "BONZO_MASK", 0, 0, "SPIRIT_MASK"));
            // A proc while chat is open: nothing until it closes.
            resetState(c, false);
            wear(c, BONZO, SPIRIT, "minecraft:air");
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, new ChatScreen("", false)));
            c.ctx().waitTicks(2);
            proc(c, BONZO_PROC);
            c.ctx().waitTicks(30);
            c.check(count(c, "stats") == 1 && c.events("menu.equip").size() == 1,
                    "a proc under the chat screen sent: " + c.commands());
            boolean chatStill = c.onClient(mc -> McCompat.screen(mc) instanceof ChatScreen);
            c.check(chatStill, "premise: the chat screen closed by itself during the wait");
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
            notes.add(expectMaskSwap(c, "Bonzo proc under chat, swapped after it closed", "SPIRIT_MASK", 1, 1, "BONZO_MASK")
                    + " (30 ticks under chat: nothing sent)");
            notes.forEach(c::note);
        });
    }

    static void order(Session c) throws Exception {
        with(c, "FULL_AUTO", "PHOENIX_FIRST", true, s -> {
            List<String> notes = new ArrayList<>();
            wear(c, SPIRIT, "minecraft:air", BONZO);
            proc(c, SPIRIT_PROC);
            c.waitUntil("Phoenix summoned", mc -> (Boolean) Mod.staticCall(SW, "isPhoenixSummoned"), 200);
            c.waitUntil("swapper idle", mc -> !(Boolean) Mod.staticCall(SW, "isBusy"), 100);
            c.waitUntil("no screen", mc -> McCompat.screen(mc) == null, 60);
            c.check(count(c, "pets") == 1 && count(c, "stats") == 0, "Phoenix First: want /pets only, saw " + c.commands());
            c.check(petClicks(c) == 1, "Phoenix First: Phoenix clicks " + petClicks(c));
            c.check("SPIRIT_MASK".equals(helmet(c)), "Phoenix First changed the helmet: " + helmet(c));
            notes.add("Phoenix First, Spirit procs: /pets + Phoenix clicked, summon line read, no /stats, helmet " + helmet(c));

            resetState(c, false);
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setSwapOrder", Mod.enumValue(CFG + "$SwapOrder", "MASK_FIRST")));
            wear(c, SPIRIT, "minecraft:air", BONZO);
            proc(c, SPIRIT_PROC);
            notes.add("Mask First, " + expectMaskSwap(c, "Spirit procs", "BONZO_MASK", 0, 0, "SPIRIT_MASK"));
            c.check(count(c, "pets") == 1, "Mask First sent /pets: " + c.commands());

            // Phoenix First with Phoenix already out (Hypixel said so): straight to the other mask.
            resetState(c, true);
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setSwapOrder", Mod.enumValue(CFG + "$SwapOrder", "PHOENIX_FIRST")));
            proc(c, "You summoned your Phoenix!");
            c.waitUntil("Phoenix marked out", mc -> (Boolean) Mod.staticCall(SW, "isPhoenixSummoned"), 40);
            c.ctx().runOnClient(mc -> Mod.setField(SW, "lastPhoenixConfirmedSummonMs", 0L));
            wear(c, BONZO, SPIRIT, "minecraft:air");
            proc(c, BONZO_PROC);
            notes.add("Phoenix First with Phoenix out, " + expectMaskSwap(c, "Bonzo procs", "SPIRIT_MASK", 1, 1, "BONZO_MASK"));
            c.check(count(c, "pets") == 1, "Phoenix out but /pets sent again: " + c.commands());
            // Another pet summoned clears it.
            proc(c, "You summoned your Golden Dragon!");
            c.waitUntil("Phoenix no longer out", mc -> !(Boolean) Mod.staticCall(SW, "isPhoenixSummoned"), 40);
            notes.add("\"You summoned your Golden Dragon!\" -> Phoenix no longer counted as out");
            notes.forEach(c::note);
        });
    }

    static void dungeonsOnly(Session c) throws Exception {
        with(c, "FULL_AUTO", "MASK_FIRST", false, s -> {
            List<String> notes = new ArrayList<>();
            wear(c, SPIRIT, "minecraft:air", BONZO);
            proc(c, SPIRIT_PROC);
            c.ctx().waitTicks(30);
            c.check(c.commands().isEmpty() && "SPIRIT_MASK".equals(helmet(c)), "Full Auto acted outside a dungeon: "
                    + c.commands() + ", helmet " + helmet(c));
            c.check(lastDecision(c).contains("Dungeons Only"), "decision " + lastDecision(c));
            notes.add("outside, Full Auto, Spirit proc: nothing sent, '" + lastDecision(c) + "'");
            resetState(c, false);
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setAutoMaskMode", Mod.enumValue(CFG + "$AutoMaskMode", "KEYBIND")));
            c.ctx().waitTicks(3); // a key already down when the mode changes is not a press
            long seq = c.onClient(mc -> (Long) Mod.staticCall(AM, "decisionSeq"));
            press(c);
            c.waitUntil("Auto Mask's decision on the press", mc -> (Long) Mod.staticCall(AM, "decisionSeq") > seq, 40);
            c.ctx().waitTicks(30);
            c.check(c.commands().isEmpty() && "SPIRIT_MASK".equals(helmet(c)), "the key acted outside a dungeon: " + c.commands());
            c.check(lastDecision(c).equals("blocked: Dungeons Only (key)"), "decision " + lastDecision(c));
            notes.add("outside, Keybind press: nothing sent, '" + lastDecision(c) + "'");
            // Control: Dungeons Only off, same place, same press.
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setAutoMaskDungeonsOnly", false));
            press(c);
            notes.add("control, Dungeons Only off: " + expectMaskSwap(c, "press outside", "BONZO_MASK", 0, 0, "SPIRIT_MASK"));
            notes.forEach(c::note);
        });
    }

    static void bossOnly(Session c) throws Exception {
        with(c, "FULL_AUTO", "MASK_FIRST", true, s -> {
            List<String> notes = new ArrayList<>();
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setAutoMaskBossOnly", true));
            boolean boss = c.onClient(mc -> (Boolean) Mod.staticCall("livemap.LiveMapFeature", "isInBoss"));
            c.check(!boss, "premise: the F7 clear already reads as the boss");
            wear(c, SPIRIT, "minecraft:air", BONZO);
            proc(c, SPIRIT_PROC);
            c.ctx().waitTicks(30);
            c.check(c.commands().isEmpty() && "SPIRIT_MASK".equals(helmet(c)), "Boss Only acted in the clear: " + c.commands());
            c.check(lastDecision(c).contains("Boss Only"), "decision " + lastDecision(c));
            notes.add("F7 clear, Spirit proc: nothing sent, '" + lastDecision(c) + "'");
            proc(c, MAXOR);
            c.waitUntil("the boss phase (Maxor's line)", mc -> (Boolean) Mod.staticCall("livemap.LiveMapFeature", "isInBoss"), 60);
            resetState(c, false);
            proc(c, SPIRIT_PROC);
            notes.add("F7 boss, " + expectMaskSwap(c, "Spirit proc", "BONZO_MASK", 0, 0, "SPIRIT_MASK"));
            notes.forEach(c::note);
            DungeonMenuCases.leaveDungeon(c); // ends the boss phase for the cases after this one
        });
    }

    static void settings(Session c) throws Exception {
        MenuKit.reset(c);
        Path path = c.onClient(mc -> (Path) Mod.field(CFG, "CONFIG_PATH"));
        byte[] original = Files.exists(path) ? Files.readAllBytes(path) : null;
        List<String> notes = new ArrayList<>();
        try {
            // Save + load.
            c.ctx().runOnClient(mc -> {
                Object cfg = Mod.cfg(CFG);
                Mod.call(cfg, "setAutoSwapEnabled", true);
                Mod.call(cfg, "setAutoMaskMode", Mod.enumValue(CFG + "$AutoMaskMode", "KEYBIND"));
                Mod.call(cfg, "setSwapOrder", Mod.enumValue(CFG + "$SwapOrder", "PHOENIX_FIRST"));
                Mod.call(cfg, "setSwapKey", KEY);
                Mod.call(cfg, "setAutoMaskDungeonsOnly", false);
                Mod.call(cfg, "setAutoMaskBossOnly", true);
                Mod.call(cfg, "setSwapStepDelayTicks", 12);
                Mod.call(cfg, "save");
                Mod.staticCall(CFG, "load");
            });
            String read = c.onClient(mc -> {
                Object cfg = Mod.cfg(CFG);
                return Mod.field(cfg, "autoSwapEnabled") + " " + Mod.call(cfg, "getAutoMaskMode") + " "
                        + Mod.call(cfg, "getSwapOrder") + " " + Mod.call(cfg, "getSwapKey") + " "
                        + Mod.call(cfg, "isAutoMaskDungeonsOnly") + " " + Mod.call(cfg, "isAutoMaskBossOnly") + " "
                        + Mod.call(cfg, "getSwapStepDelayTicks");
            });
            c.check(read.equals("true KEYBIND PHOENIX_FIRST " + KEY + " false true 12"), "after save + load: " + read);
            JsonObject saved = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            c.check(saved.has("swapStepDelayTicks") && !saved.has("swapStepDelayMs"),
                    "the file should hold swapStepDelayTicks and no swapStepDelayMs: " + saved);
            notes.add("save + load: " + read + "; file keys swapStepDelayTicks=" + saved.get("swapStepDelayTicks")
                    + ", autoMaskMode=" + saved.get("autoMaskMode"));

            // An old file: one Auto Swap switch, a millisecond delay.
            // 9999 ms is 200 ticks, clamped to the top of the range: 40 since mod ba77f3e0 (killer560 2026-10-18,
            // "0 to 2 seconds"), 20 before.
            String[][] old = {{"350", "7"}, {"150", "3"}, {"1000", "20"}, {"520", "10"}, {"9999", "40"}};
            for (String[] o : old) {
                Files.writeString(path, "{\"enabled\":true,\"autoSwapEnabled\":true,\"swapStepDelayMs\":" + o[0] + "}",
                        StandardCharsets.UTF_8);
                String r = c.onClient(mc -> {
                    Mod.staticCall(CFG, "load");
                    Object cfg = Mod.cfg(CFG);
                    return Mod.field(cfg, "autoSwapEnabled") + " " + Mod.call(cfg, "getAutoMaskMode") + " "
                            + Mod.call(cfg, "getSwapOrder") + " " + Mod.call(cfg, "getSwapKey") + " "
                            + Mod.call(cfg, "isAutoMaskDungeonsOnly") + " " + Mod.call(cfg, "isAutoMaskBossOnly") + " "
                            + Mod.call(cfg, "getSwapStepDelayTicks");
                });
                c.check(r.equals("true FULL_AUTO MASK_FIRST -1 true false " + o[1]), "old file " + o[0] + " ms loads as " + r);
                notes.add("old file (Auto Swap on, " + o[0] + " ms) -> " + r);
            }
            Files.writeString(path, "{\"enabled\":true,\"autoSwapEnabled\":false,\"swapStepDelayMs\":350}", StandardCharsets.UTF_8);
            String off = c.onClient(mc -> {
                Mod.staticCall(CFG, "load");
                Object cfg = Mod.cfg(CFG);
                return Mod.field(cfg, "autoSwapEnabled") + " " + Mod.call(cfg, "getAutoMaskMode");
            });
            c.check(off.equals("false FULL_AUTO"), "old file with Auto Swap off loads as " + off);
            notes.add("old file (Auto Swap off) -> " + off);

            // The tab: rows in order, cycling where it steps, a tooltip on each new row.
            Files.writeString(path, "{\"enabled\":true,\"autoSwapEnabled\":true,\"autoMaskMode\":\"KEYBIND\"}", StandardCharsets.UTF_8);
            List<String> labels = c.onClient(mc -> {
                Mod.staticCall(CFG, "load");
                List<String> out = new ArrayList<>();
                try {
                    Object tab = Mod.cls("gui.tab.AutoMaskTab").getDeclaredConstructor().newInstance();
                    Runnable none = () -> { };
                    List<?> widgets = (List<?>) Mod.call(tab, "buildWidgets", 0, 0, 320, none);
                    for (Object w : widgets) {
                        AbstractWidget aw = (AbstractWidget) w;
                        String cyc = "";
                        try {
                            cyc = (Boolean) Mod.call(aw, "isCycling") ? " [cycling]" : "";
                        } catch (Throwable ignored) {
                            // a slider or a text line has no isCycling
                        }
                        String text = net.minecraft.ChatFormatting.stripFormatting(aw.getMessage().getString());
                        Object tip = Mod.staticCall("gui.SettingTooltips", "describe", "Auto Mask", text);
                        out.add(text + cyc + (tip == null ? " [no tooltip]" : ""));
                    }
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
                return out;
            });
            String joined = String.join(" | ", labels);
            String[] want = {"Auto Mask", "Mode: Keybind [cycling]", "Swap Key", "Swap Order: Mask First [cycling]",
                    "Dungeons Only: ON", "Boss Only: OFF", "Swap Step Delay: 7 ticks"};
            int at = 0;
            for (String w : want) {
                int found = -1;
                for (int i = at; i < labels.size(); i++) {
                    if (labels.get(i).startsWith(w)) {
                        found = i;
                        break;
                    }
                }
                c.check(found >= 0, "the tab is missing '" + w + "' after row " + at + " (in order): " + joined);
                c.check(!labels.get(found).contains("[no tooltip]"), "no tooltip on '" + labels.get(found) + "'");
                at = found + 1;
            }
            notes.add("tab rows: " + joined);
        } finally {
            if (original != null) {
                Files.write(path, original);
            } else {
                Files.deleteIfExists(path);
            }
            c.ctx().runOnClient(mc -> Mod.staticCall(CFG, "load"));
        }
        notes.forEach(c::note);
    }

    /**
     * 1887 (hotbar-select-rest-1016): Phoenix by the ROD route, the only one that touches the hotbar. A fishing rod in
     * slot 6 while he holds slot 1, Phoenix First, the key: the swapper must select the rod (util/HotbarSelect, inside
     * the tick), cast it on a later tick with the rod in the server's hand, and with Return To Previous Slot put slot 1
     * back. The server sees {@code held} 5, a rod {@code use_item}, {@code held} 0; no command; at most one slot a tick;
     * nothing sent outside a tick. GrimAC silent (the session's verdict).
     */
    static void rod(Session c) throws Exception {
        with(c, "KEYBIND", "PHOENIX_FIRST", true, "ROD", s -> {
            boolean returnWas = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(CFG), "isRodReturnToPreviousSlot"));
            try {
                c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setRodReturnToPreviousSlot", true));
                c.server().command("item replace entity @a hotbar.5 with minecraft:fishing_rod");
                c.server().command("item replace entity @a hotbar.0 with minecraft:stick");
                wear(c, SPIRIT, "minecraft:air", BONZO);
                c.waitUntil("the rod in slot 6", mc -> mc.player.getInventory().getItem(5).is(
                        net.minecraft.world.item.Items.FISHING_ROD), 60);
                c.ctx().runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
                c.ctx().waitTicks(5);
                long mark = c.hx().head();
                c.ctx().runOnClient(mc -> PacketTrace.start());
                press(c);
                c.waitUntil("the rod thrown", mc -> ((Number) Mod.field(SW, "lastPhoenixRodThrowMs")).longValue() != 0L, 100);
                c.waitUntil("the swapper idle after the rod", mc -> !(Boolean) Mod.staticCall(SW, "isBusy"), 200);
                c.ctx().waitTicks(5);
                c.ctx().runOnClient(mc -> PacketTrace.stop());
                List<Integer> held = c.hx().events(mark, "held").stream().map(e -> e.get("slot").getAsInt()).toList();
                List<String> uses = c.hx().events(mark, "use_item").stream().map(e -> e.get("item").getAsString()).toList();
                int slotNow = c.onClient(mc -> mc.player.getInventory().getSelectedSlot());
                List<String> outside = PacketTrace.sentOutsideTick();
                c.note("rod route: held " + held + ", use_item " + uses + ", slot now " + (slotNow + 1) + ", commands "
                        + c.commands() + ", sent outside a tick: " + (outside.isEmpty() ? "nothing" : outside));
                c.check(held.equals(List.of(5, 0)), "want the server told slot 6 then slot 1, got " + held);
                c.check(uses.equals(List.of("minecraft:fishing_rod")), "want one rod cast with the rod in the server's hand, "
                        + "got " + uses);
                c.check(slotNow == 0, "Return To Previous Slot left him on slot " + (slotNow + 1));
                c.check(c.commands().isEmpty(), "the rod route sent a command: " + c.commands());
                int shared = 0;
                for (int t = 0; t < PacketTrace.tickCount(); t++) {
                    List<String> out = PacketTrace.sentOn(t);
                    if (out.contains("set_carried_item") && out.contains("use_item")) {
                        shared++;
                    }
                }
                c.check(shared == 0, "the cast went out in the same tick as a swap");
                c.check(PacketTrace.maxPerTick("set_carried_item") <= 1, "a slot went out twice in one tick (BadPacketsA)");
                c.check(outside.isEmpty(), "sent outside a tick (after its client_tick_end, before the next tick): "
                        + outside);
            } finally {
                c.ctx().runOnClient(mc -> {
                    PacketTrace.stop();
                    Mod.call(Mod.cfg(CFG), "setRodReturnToPreviousSlot", returnWas);
                    mc.player.getInventory().setSelectedSlot(0);
                });
                c.server().command("kill @e[type=fishing_bobber]");
                c.server().command("item replace entity @a hotbar.5 with minecraft:air");
                c.server().command("item replace entity @a hotbar.0 with minecraft:air");
            }
        });
    }

    static void stepTicks(Session c) throws Exception {
        with(c, "KEYBIND", "MASK_FIRST", true, s -> {
            AtomicLong statsTick = new AtomicLong(-1);
            PacketTrace.tap = p -> {
                if (p instanceof ServerboundChatCommandPacket cmd && "stats".equals(cmd.command())) {
                    statsTick.compareAndSet(-1, FrameClock.tickCount());
                }
            };
            try {
                long[] gaps = new long[2];
                int[] delays = {3, 10};
                for (int i = 0; i < 2; i++) {
                    int d = delays[i];
                    resetState(c, false);
                    c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setSwapStepDelayTicks", d));
                    wear(c, SPIRIT, "minecraft:air", BONZO);
                    statsTick.set(-1);
                    long before = c.onClient(mc -> FrameClock.tickCount());
                    press(c);
                    c.waitUntil("/stats at delay " + d, mc -> statsTick.get() >= 0, 100);
                    gaps[i] = statsTick.get() - before;
                    expectMaskSwap(c, "delay " + d, "BONZO_MASK", i, i, "SPIRIT_MASK");
                }
                c.check(gaps[1] - gaps[0] == 7, "10 ticks should send /stats exactly 7 ticks later than 3: " + gaps[0]
                        + " vs " + gaps[1]);
                c.check(gaps[0] >= 3 && gaps[0] <= 4, "at 3 ticks /stats left " + gaps[0] + " ticks after the key went down");
                c.note("key down -> /stats: " + gaps[0] + " ticks at Swap Step Delay 3, " + gaps[1] + " at 10 (difference "
                        + (gaps[1] - gaps[0]) + "); both swaps put on Bonzo");
            } finally {
                PacketTrace.tap = null;
            }
        });
    }
}

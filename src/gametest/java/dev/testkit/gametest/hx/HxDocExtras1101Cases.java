package dev.testkit.gametest.hx;

import com.mojang.blaze3d.platform.Window;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.screens.ChatScreen;

import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * To-do Doc items of 2026-11-01 (mod branch doc-extras-1101): Creation Cooldown, Insta Clear Warning, Gyrokinetic Wand
 * Overlay and Chat Tabs. Each case proves the feature ACTED - a timer started and its HUD drew, a command was held back
 * or reached the server, a warning fired and its party line and requeue arrived, the ring was drawn, a real click on a
 * tab switched channel and changed what the chat shows - and that it stays quiet where it must (forged or other
 * players' lines, a key on the ground, a key picked up, too far away, the wand not held, the sky).
 */
final class HxDocExtras1101Cases {

    static final String CC = "creationcooldown.CreationCooldownFeature";
    static final String CC_CFG = "creationcooldown.CreationCooldownConfig";
    static final String ICW = "instaclearwarning.InstaClearWarningFeature";
    static final String ICW_CFG = "instaclearwarning.InstaClearWarningConfig";
    static final String GYRO = "gyrooverlay.GyroOverlayFeature";
    static final String GYRO_CFG = "gyrooverlay.GyroOverlayConfig";
    static final String TABS = "chattabs.ChatTabsFeature";
    static final String TABS_CFG = "chattabs.ChatTabsConfig";

    private HxDocExtras1101Cases() {
    }

    static void register(Session s) {
        s.test("1101-extras-creation", HxDocExtras1101Cases::creation);
        s.test("1101-extras-instaclear", HxDocExtras1101Cases::instaClear);
        s.test("1101-extras-gyro", HxDocExtras1101Cases::gyro);
        s.test("1101-extras-chattabs", HxDocExtras1101Cases::chatTabs);
    }

    private static long remaining(Session c) {
        return c.onClient(mc -> (Long) Mod.staticCall(CC, "remainingMs"));
    }

    private static int blocked(Session c) {
        return c.onClient(mc -> (Integer) Mod.staticCall(CC, "blockedCount"));
    }

    // ---- 1101-extras-creation ---------------------------------------------------------------------------------------

    private static void creation(Session c) {
        try (Settings set = new Settings(c).with(CC_CFG, "Enabled", true).with(CC_CFG, "BlockCreation", true)
                .with(CC_CFG, "OnlyInGreenRoom", true).with(CC_CFG, "ShowOnAllIslands", false)
                .with(CC_CFG, "ChatWhenOver", false)) {
            c.hx().stub(null, "joininstance");
            dungeon(c);
            run(c, () -> Mod.staticCall(CC, "testReset"));
            String me = self(c);
            // Someone else's real line, and a player's chat carrying our line: neither starts it.
            c.hx().chat("§b[MVP+] HxMateA§f §eentered §aThe Catacombs§e, §eFloor VII§e!");
            c.hx().chat("§7[VIP] Bob§7: " + me + " entered The Catacombs, Floor VII!");
            c.ctx().waitTicks(10);
            c.check(remaining(c) == 0, "another player's line or a forged one started the cooldown (" + remaining(c) + " ms)");
            // Ours, in Hypixel's three-line block.
            c.hx().chat("§e§l---\n§r§b[MVP+] " + me + "§r§f §r§eentered §r§aThe Catacombs§r§e, §r§eFloor VII§r§e!\n§e§l---");
            c.waitUntil("the cooldown starts on our own entry line", mc -> (Long) Mod.staticCall(CC, "remainingMs") > 25_000, 40);
            c.waitUntil("the Creation Cooldown HUD draws", mc -> Mod.staticCall(CC, "lastDrawn") != null, 40);
            String drawn = c.onClient(mc -> (String) Mod.staticCall(CC, "lastDrawn"));
            // Block: typed, and the mod's own send path.
            int b0 = blocked(c);
            type(c, "/joininstance catacombs_floor_seven");
            c.waitUntil("the typed /joininstance is blocked", mc -> (Integer) Mod.staticCall(CC, "blockedCount") == b0 + 1, 20);
            boolean sent = c.onClient(mc -> (Boolean) Mod.staticCall("util.ServerCommands", "toServer", "joininstance catacombs_floor_one"));
            c.check(!sent, "ServerCommands.toServer sent /joininstance during the cooldown");
            c.check(blocked(c) == b0 + 2, "the mod's own /joininstance was not counted as blocked");
            assertNoCommand(c, 20, "joininstance");
            // Over: it goes through.
            run(c, () -> Mod.staticCall(CC, "testReset"));
            type(c, "/joininstance catacombs_floor_seven");
            String through = awaitCommand(c, "joininstance", 40);
            // Out of a dungeon: hidden unless Show On All Islands.
            hub(c);
            run(c, () -> {
                Mod.staticCall(CC, "testReset");
                Mod.staticCall(CC, "start", System.currentTimeMillis());
            });
            c.ctx().waitTicks(20);
            String hubOff = c.onClient(mc -> (String) Mod.staticCall(CC, "lastDrawn"));
            c.check(hubOff == null, "the HUD drew in the hub with Show On All Islands off: " + hubOff);
            run(c, () -> Mod.set(CC_CFG, "setShowOnAllIslands", true));
            c.waitUntil("the HUD draws in the hub with Show On All Islands on", mc -> Mod.staticCall(CC, "lastDrawn") != null, 40);
            c.note("forged + mate lines ignored; own line -> " + drawn + " on the HUD; typed and mod /joininstance blocked;"
                    + " after -> /" + through + "; hub: hidden, shown with Show On All Islands");
        } finally {
            run(c, () -> Mod.staticCall(CC, "testReset"));
            dungeon(c);
        }
    }

    // ---- 1101-extras-instaclear -------------------------------------------------------------------------------------

    private static int warnings(Session c) {
        return c.onClient(mc -> (Integer) Mod.staticCall(ICW, "warnings"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> quiet(Session c) {
        return c.onClient(mc -> (List<String>) Mod.staticCall(ICW, "quietEnds"));
    }

    @SuppressWarnings("unchecked")
    private static void instaClear(Session c) {
        String line = "Blood rush room was instacleared we must restart";
        try (Settings set = new Settings(c).with(ICW_CFG, "Enabled", true).with(ICW_CFG, "HudPopup", true)
                .with(ICW_CFG, "PartyMessage", true).with(ICW_CFG, "Message", line).with(ICW_CFG, "Requeue", true)
                .with("dungeonqueue.DungeonQueueConfig", "DelaySeconds", 1)
                .with(CC_CFG, "BlockCreation", false)) {
            c.hx().stub(null, "pc", "joininstance");
            dungeon(c);
            // 1. A key room clears with no key: warns after the grace, party line, popup, requeue after the cooldown.
            run(c, () -> {
                Mod.staticCall(ICW, "testUseFake", true);
                Mod.staticCall(ICW, "testKeys", 0, 0, false, true);
                Mod.staticCall(ICW, "testGuard", "Pillars", 51, false);
                // 5 s of creation cooldown left.
                Mod.staticCall(CC, "start", System.currentTimeMillis() - 25_000L);
            });
            c.ctx().waitTicks(12);
            int w0 = warnings(c);
            run(c, () -> Mod.staticCall(ICW, "testClear", "Pillars"));
            c.ctx().waitTicks(40);
            c.check(warnings(c) == w0, "warned inside the 3 s grace");
            c.waitUntil("the insta clear warning fires", mc -> (Integer) Mod.staticCall(ICW, "warnings") == w0 + 1, 60);
            String room = c.onClient(mc -> (String) Mod.staticCall(ICW, "lastWarnedRoom"));
            String pc = awaitCommand(c, "pc ", 40);
            c.check(pc.equals("pc " + line), "the party line was /" + pc);
            c.waitUntil("the HUD popup draws", mc -> "Pillars".equals(Mod.staticCall(ICW, "lastDrawn")), 40);
            c.check(commandsStarting(c, "joininstance").isEmpty() && c.onClient(mc -> (Boolean) Mod.staticCall(ICW, "requeuePending")),
                    "the requeue did not wait for the creation cooldown");
            String requeue = awaitCommand(c, "joininstance", 200);
            c.check(requeue.equals("joininstance catacombs_floor_seven"), "requeue sent /" + requeue);
            // 2. Its key on the ground: quiet.
            run(c, () -> {
                Mod.staticCall(ICW, "testUseFake", true);
                Mod.staticCall(ICW, "testKeys", 0, 0, true, true);
                Mod.staticCall(ICW, "testGuard", "Diagonal", 61, false);
            });
            c.ctx().waitTicks(12);
            int w1 = warnings(c);
            run(c, () -> Mod.staticCall(ICW, "testClear", "Diagonal"));
            c.waitUntil("the watch ends quietly (key on the ground)", mc -> ((List<String>) Mod.staticCall(ICW, "quietEnds")).stream().anyMatch(q -> q.startsWith("Diagonal: its key is on the ground")), 40);
            // 3. Blood key picked up: quiet.
            run(c, () -> {
                Mod.staticCall(ICW, "testUseFake", true);
                Mod.staticCall(ICW, "testKeys", 0, 0, false, true);
                Mod.staticCall(ICW, "testGuard", "Bridges", 33, true);
            });
            c.ctx().waitTicks(12);
            run(c, () -> {
                Mod.staticCall(ICW, "testClear", "Bridges");
                Mod.staticCall(ICW, "testKeys", 0, 1, false, true);
            });
            c.waitUntil("the watch ends quietly (blood key picked up)", mc -> ((List<String>) Mod.staticCall(ICW, "quietEnds")).stream().anyMatch(q -> q.startsWith("Bridges: its key was picked up")), 40);
            // 4. Too far to see: waits, then warns once he is near.
            run(c, () -> {
                Mod.staticCall(ICW, "testUseFake", true);
                Mod.staticCall(ICW, "testKeys", 0, 0, false, false);
                Mod.staticCall(ICW, "testGuard", "Far Room", 71, false);
            });
            c.ctx().waitTicks(12);
            run(c, () -> Mod.staticCall(ICW, "testClear", "Far Room"));
            c.ctx().waitTicks(100);
            c.check(warnings(c) == w1, "warned about a far room, or about the key/blood cases (" + w1 + " -> " + warnings(c) + ")");
            run(c, () -> Mod.staticCall(ICW, "testKeys", 0, 0, false, true));
            c.waitUntil("the far room warns once he is near", mc -> "Far Room".equals(Mod.staticCall(ICW, "lastWarnedRoom")), 40);
            c.note(room + " cleared keyless -> warned after the grace, /" + pc + ", popup drawn, /" + requeue
                    + " after the cooldown; key on ground / blood key picked up -> quiet; far -> waited, warned when near");
        } finally {
            run(c, () -> {
                Mod.staticCall(ICW, "testUseFake", false);
                Mod.staticCall(CC, "testReset");
            });
        }
    }

    // ---- 1101-extras-gyro -------------------------------------------------------------------------------------------

    private static long frames(Session c) {
        return c.onClient(mc -> (Long) Mod.staticCall(GYRO, "framesDrawn"));
    }

    private static void gyro(Session c) {
        float[] look = c.onClient(mc -> new float[]{mc.player.getXRot(), mc.player.getYRot()});
        try (Settings set = new Settings(c).with(GYRO_CFG, "Enabled", true)
                .with(GYRO_CFG, "Style", Mod.enumValue(GYRO_CFG + "$Style", "CIRCLE_OUTLINE"))) {
            dungeon(c);
            c.server().command("item replace entity @a hotbar.0 with minecraft:blaze_rod[custom_data={id:\"GYROKINETIC_WAND\"}]");
            run(c, () -> {
                mc().player.getInventory().setSelectedSlot(0);
                mc().player.setXRot(70f);
            });
            c.ctx().waitTicks(10);
            long f0 = frames(c);
            c.ctx().waitTicks(20);
            long f1 = frames(c);
            c.check(f1 > f0, "the ring was not drawn while holding the wand and looking at the floor (" + f0 + " -> " + f1 + ")");
            String shape = c.onClient(mc -> (String) Mod.staticCall(GYRO, "lastShape"));
            String centre = c.onClient(mc -> (String) Mod.staticCall(GYRO, "lastCentre"));
            c.check("CIRCLE_OUTLINE".equals(shape), "shape " + shape);
            double cy = Double.parseDouble(centre.split(",")[1]);
            c.check(Math.abs(cy - 151.0) < 0.6, "the ring is not on the platform top (y 151): " + centre);
            run(c, () -> Mod.set(GYRO_CFG, "setStyle", Mod.enumValue(GYRO_CFG + "$Style", "SPHERE")));
            c.waitUntil("the sphere draws", mc -> "SPHERE".equals(Mod.staticCall(GYRO, "lastShape")), 40);
            // The sky: nothing to aim at.
            run(c, () -> mc().player.setXRot(-90f));
            c.ctx().waitTicks(5);
            long s0 = frames(c);
            c.ctx().waitTicks(20);
            c.check(frames(c) == s0, "drew while looking at the sky");
            // Not holding it.
            run(c, () -> mc().player.setXRot(70f));
            c.server().command("item replace entity @a hotbar.0 with minecraft:stick");
            c.ctx().waitTicks(10);
            long n0 = frames(c);
            c.ctx().waitTicks(20);
            c.check(frames(c) == n0, "drew without the wand in hand");
            c.note("wand + floor: " + (f1 - f0) + " frames in 20 ticks, " + shape + " at " + centre + "; Sphere drew;"
                    + " sky and no wand: nothing");
        } finally {
            c.server().command("item replace entity @a hotbar.0 with minecraft:air");
            run(c, () -> {
                mc().player.setXRot(look[0]);
                mc().player.setYRot(look[1]);
            });
        }
    }

    private static net.minecraft.client.Minecraft mc() {
        return net.minecraft.client.Minecraft.getInstance();
    }

    // ---- 1101-extras-chattabs ---------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<String> shown(Session c) {
        return c.onClient(mc -> (List<String>) Mod.staticCall(TABS, "testShownMessages"));
    }

    private static void clickTab(Session c, String tab) {
        double[] at = c.onClient(mc -> {
            int h = mc.getWindow().getGuiScaledHeight();
            String[] xy = ((String) Mod.staticCall(TABS, "tabCenter", tab, h)).split(",");
            Window w = mc.getWindow();
            return new double[]{Double.parseDouble(xy[0]) * w.getScreenWidth() / (double) w.getGuiScaledWidth(),
                    Double.parseDouble(xy[1]) * w.getScreenHeight() / (double) w.getGuiScaledHeight()};
        });
        c.ctx().getInput().setCursorPos(at[0], at[1]);
        c.ctx().waitTicks(2);
        c.ctx().getInput().pressMouse(dev.testkit.compat.McKeys.mouse(0));
        c.ctx().waitTicks(3);
    }

    private static void chatTabs(Session c) {
        try (Settings set = new Settings(c).with(TABS_CFG, "Enabled", true).with(TABS_CFG, "SwitchChannel", true)
                .with(TABS_CFG, "FilterClosedChat", false)) {
            c.hx().stub(null, "chat", "r");
            dungeon(c);
            c.hx().chat("§9Party §8> §b[MVP+] HxMateA§f: tabs party hello");
            c.hx().chat("§2Guild > §7HxMateB§f: tabs guild hello");
            c.hx().chat("§dFrom §b[MVP+] HxMateA§7: tabs dm hello");
            c.hx().chat("§7HxMateB§7: Party > tabs forged party");
            c.ctx().waitTicks(5);
            run(c, () -> McCompat.setScreen(mc(), new ChatScreen("", false)));
            c.ctx().waitTicks(5);
            long fr = c.onClient(mc -> (Long) Mod.staticCall(TABS, "framesDrawn"));
            c.ctx().waitTicks(5);
            c.check(c.onClient(mc -> (Long) Mod.staticCall(TABS, "framesDrawn")) > fr, "the tab row is not drawn in the open chat");
            // Party: a real click.
            clickTab(c, "PARTY");
            String partyCmd = awaitCommand(c, "chat party", 20);
            List<String> party = shown(c);
            c.check(party.stream().anyMatch(s -> s.contains("tabs party hello")), "Party tab hides the party line: " + party);
            c.check(party.stream().noneMatch(s -> s.contains("tabs guild hello") || s.contains("tabs dm hello")
                    || s.contains("tabs forged party")), "Party tab shows other channels: " + party);
            // Messages: no /chat, only the DM, and a typed line goes as /r.
            int before = commandsStarting(c, "chat").size();
            clickTab(c, "MESSAGES");
            c.waitUntil("Messages selected", mc -> "MESSAGES".equals(Mod.staticCall(TABS, "selectedName")), 20);
            List<String> dms = shown(c);
            c.check(dms.stream().anyMatch(s -> s.contains("tabs dm hello")) && dms.stream().noneMatch(s -> s.contains("tabs party hello")),
                    "Messages tab shows " + dms);
            c.check(commandsStarting(c, "chat").size() == before, "the Messages tab sent a /chat command");
            run(c, () -> McCompat.setScreen(mc(), null));
            c.ctx().waitTicks(3);
            List<String> closed = shown(c);
            c.check(closed.stream().anyMatch(s -> s.contains("tabs guild hello")), "closed chat stayed filtered: " + closed);
            type(c, "tabs reply line");
            String reply = awaitCommand(c, "r ", 20);
            c.check(reply.equals("r tabs reply line"), "the typed line went as /" + reply);
            // Typed /chat g selects Guild.
            type(c, "/chat g");
            c.waitUntil("typed /chat g selects Guild", mc -> "GUILD".equals(Mod.staticCall(TABS, "selectedName")), 20);
            // Back to All.
            run(c, () -> McCompat.setScreen(mc(), new ChatScreen("", false)));
            c.ctx().waitTicks(5);
            clickTab(c, "ALL");
            awaitCommand(c, "chat all", 20);
            List<String> all = shown(c);
            c.check(all.stream().anyMatch(s -> s.contains("tabs guild hello")) && all.stream().anyMatch(s -> s.contains("tabs party hello")),
                    "All tab hides lines: " + all);
            c.note("tab row drawn; click Party -> /" + partyCmd + ", only party line shown (forged one hidden); Messages ->"
                    + " only the DM, no /chat, typed line -> /" + reply + "; closed chat unfiltered; /chat g -> Guild; All -> /chat all");
        } finally {
            run(c, () -> McCompat.setScreen(mc(), null));
            run(c, () -> Mod.setField(TABS, "selected", Mod.enumValue(TABS + "$Tab", "ALL")));
            run(c, () -> Mod.staticCall(TABS, "refresh"));
        }
    }
}

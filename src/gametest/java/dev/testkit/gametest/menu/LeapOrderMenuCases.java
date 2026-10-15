package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 670: the custom Spirit Leap menu follows the Leap Order's default order and its pins (killer560, 2026-10-09; mod branch
 * leaporder). A 4-head Spirit Leap menu (LoAnna 11, LoBen 13, LoCara 15, LoDev 17) with classes Mage, Tank, Healer,
 * Berserker while playing Archer: for every default order, keys 1-4 must make the server see a click on the head of the
 * teammate that order puts in that spot (the same answers 666 checks in the editor); then a pin made through the
 * editor's own swap moves the key with it.
 */
final class LeapOrderMenuCases {

    private static final String CFG = "leapmenu.LeapMenuConfig";
    private static final List<String> PARTY = List.of("LoAnna", "LoBen", "LoCara", "LoDev");
    private static final Map<String, Integer> SLOT = Map.of("LoAnna", 11, "LoBen", 13, "LoCara", 15, "LoDev", 17);
    private static final Map<String, String> CLASSES = Map.of("LoAnna", "MAGE", "LoBen", "TANK", "LoCara", "HEALER",
            "LoDev", "BERSERKER");
    /** Worked by hand from Odin's LeapMenu.kt / DungeonEnums.kt and Skyblocker's LeapOverlay.java (see LeapOrderMode). */
    private static final Map<String, List<String>> WANT = Map.of(
            "ODIN", List.of("LoAnna", "LoDev", "LoCara", "LoBen"),
            "CLASS_AZ", List.of("LoDev", "LoCara", "LoAnna", "LoBen"),
            "CLASS_ZA", List.of("LoBen", "LoAnna", "LoCara", "LoDev"),
            "HYPIXEL", List.of("LoAnna", "LoBen", "LoCara", "LoDev"));

    private LeapOrderMenuCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "670-menu-leaporder-leap-menu", LeapOrderMenuCases::leapMenu);
    }

    private static JsonObject spec() {
        JsonObject spec = MenuKit.menu("menus.spirit-leap");
        JsonObject slots = new JsonObject();
        JsonObject on = new JsonObject();
        for (String n : PARTY) {
            JsonObject head = new JsonObject();
            head.addProperty("item", "minecraft:player_head");
            head.addProperty("name", "§a" + n);
            slots.add(String.valueOf(SLOT.get(n)), head);
            JsonObject close = new JsonObject();
            close.addProperty("close", true);
            on.add(String.valueOf(SLOT.get(n)), close);
        }
        spec.add("slots", slots);
        spec.add("on", on);
        return spec;
    }

    /** Opens the menu, presses the key for {@code spot}, returns the slot the server saw clicked. */
    private static int press(Session c, int spot) {
        int before = c.events("menu.action").size();
        MenuKit.show(c, spec());
        MenuKit.awaitScreen(c, "Spirit Leap", 100);
        c.waitUntil("the overlay to hide the vanilla menu", mc ->
                (Boolean) Mod.staticCall("spiritleap.SpiritLeapOverlayFeature", "isHiding", McCompat.screen(mc)), 100);
        c.ctx().waitTicks(10);   // SETTLE_MS before keys act
        c.ctx().getInput().pressKey(49 + spot);   // GLFW_KEY_1..4
        c.waitUntil("a menu.action for key " + (spot + 1), mc -> c.events("menu.action").size() > before, 60);
        List<JsonObject> e = c.events("menu.action");
        JsonObject a = e.get(e.size() - 1);
        MenuKit.reset(c);
        return a.get("slot").getAsInt();
    }

    @SuppressWarnings("unchecked")
    static void leapMenu(Session c) throws Exception {
        MenuKit.reset(c);
        Path path = (Path) Mod.field(CFG, "CONFIG_PATH");
        boolean existed = Files.exists(path);
        byte[] saved = existed ? Files.readAllBytes(path) : null;
        Object archer = Mod.enumValue("dungeonclass.DungeonClass", "ARCHER");
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("spiritleap.SpiritLeapOverlayConfig", "Enabled", true);
            String self = c.onClient(mc -> {
                Object leap = Mod.cfg(CFG);
                Mod.call(leap, "clearClassOrder", archer);
                Mod.call(leap, "setLastEditedClass", archer);
                // The leap menu applies YOUR class's order: the tab list's, else the one last picked in the editor.
                Mod.staticCall("dungeonclass.ClassOverrides", "set", mc.player.getGameProfile().name(), archer);
                CLASSES.forEach((n, k) -> Mod.staticCall("dungeonclass.ClassOverrides", "set", n,
                        Mod.enumValue("dungeonclass.DungeonClass", k)));
                return String.valueOf(Mod.staticCall("leapmenu.LeapMenuFeature", "playingClass"));
            });
            c.check(self.equals("ARCHER"), "playing class is " + self + ", want ARCHER");
            List<String> trail = new ArrayList<>();
            for (String mode : List.of("ODIN", "CLASS_AZ", "CLASS_ZA", "HYPIXEL")) {
                c.onClient(mc -> {
                    Mod.call(Mod.cfg(CFG), "setDefaultOrder", Mod.enumValue("leapmenu.LeapOrderMode", mode));
                    return null;
                });
                List<String> got = new ArrayList<>();
                for (int spot = 0; spot < 4; spot++) {
                    int slot = press(c, spot);
                    String who = SLOT.entrySet().stream().filter(en -> en.getValue() == slot).map(Map.Entry::getKey)
                            .findFirst().orElse("slot " + slot);
                    got.add(who);
                }
                c.check(got.equals(WANT.get(mode)), mode + ": keys 1-4 leapt to " + got + ", want " + WANT.get(mode));
                trail.add(mode + " " + got);
            }
            // A pin, made the way the editor makes one (a swap of spots 0 and 3 in the shown Odin layout).
            List<String> pinned = c.onClient(mc -> {
                Object leap = Mod.cfg(CFG);
                Mod.call(leap, "setDefaultOrder", Mod.enumValue("leapmenu.LeapOrderMode", "ODIN"));
                String[] layout = (String[]) Mod.staticCall("leapmenu.LeapMenuFeature", "layout",
                        Mod.staticCall("leapmenu.PartyTracker", "teammatesInLeapOrder"), archer);
                Mod.call(leap, "swap", archer, layout, 0, 3);
                return List.copyOf((List<String>) Mod.call(leap, "getClassOrder", archer));
            });
            int s0 = press(c, 0);
            int s3 = press(c, 3);
            c.check(s0 == SLOT.get("LoBen") && s3 == SLOT.get("LoAnna"), "after pinning LoBen top-left and LoAnna"
                    + " bottom-right keys 1/4 clicked slots " + s0 + "/" + s3 + " (pins " + pinned + ")");
            c.note("leap menu keys 1-4: " + String.join("; ", trail) + "; Odin with pins " + pinned + " -> key1 slot "
                    + s0 + ", key4 slot " + s3);
        } finally {
            MenuKit.reset(c);
            c.onClient(mc -> {
                for (String n : PARTY) {
                    Mod.staticCall("dungeonclass.ClassOverrides", "clear", n);
                }
                Mod.staticCall("dungeonclass.ClassOverrides", "clear", mc.player.getGameProfile().name());
                ((Collection<String>) Mod.field("leapmenu.PartyTracker", "MEMBERS")).removeIf(PARTY::contains);
                try {
                    if (existed) {
                        Files.write(path, saved);
                    } else {
                        Files.deleteIfExists(path);
                    }
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                Mod.staticCall(CFG, "load");
                return null;
            });
        }
    }
}

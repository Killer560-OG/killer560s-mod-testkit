package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 472: the Storage Overlay's own Scan All button (killer560, 2026-10-07 22:08: "For storage add a Scan All button
 * somewhere that clicks in all the chests"), against Hypixel's Storage menu with locked Ender Chest pages and empty
 * backpack slots ({@code menus.storage-overview-locked-empty}, as 455). Every press is a REAL mouse click on the button
 * where the overlay drew it, and every verdict is from the SERVER's command log:
 * <ol>
 *   <li>a press on the Storage menu starts a storage-only scan; the button then reads "Stop n/8"; a second press on it
 *       (on a page the scan opened) stops it, and no page is opened after that;</li>
 *   <li>a press, then Escape on a page the scan opened: stopped, nothing opened after;</li>
 *   <li>a press, left to finish: the Storage menu once, then exactly Ender Chest 1-3 and backpacks 1-5 - never a locked
 *       page, an empty slot, the wardrobe or the pets menu - and 8/8 pages done.</li>
 * </ol>
 * {@code TESTKIT_POLISH_SHOTS} keeps the button's pictures (Storage menu, mid-scan) at GUI 2.
 */
final class PolishMenuCases {

    private static final String SCAN_ALL = "storagesearch.StorageScanAll";
    private static final String SFEAT = "storageoverlay.StorageOverlayFeature";
    private static final String SCFG = "storageoverlay.StorageOverlayConfig";

    private PolishMenuCases() {
    }

    static void register(Session s) {
        // ---- polish (472) ----
        MenuSuite.test(s, "472-menu-storage-scanall-button", PolishMenuCases::scanAllButton);
    }

    static void scanAllButton(Session c) throws Exception {
        if (!Mod.has(SCAN_ALL) || !hasMethod(Mod.cls(SFEAT), "pressScanAll")) {
            c.check(false, "this jar has no Scan All button in the Storage Overlay (no StorageOverlayFeature.pressScanAll)");
            return;
        }
        MenuKit.reset(c);
        JsonObject overview = MenuKit.menu("menus.storage-overview-locked-empty");
        JsonObject ec = MenuKit.menu("menus.ender-chest-1");
        JsonObject bp = MenuKit.menu("menus.storage-backpack-page");
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c).set(SCFG, "Enabled", true)) {
            c.onClient(mc -> {
                mc.options.guiScale().set(2);
                mc.resizeGui();
                return null;
            });
            c.hx().call("menu.onCommand", MenuKit.obj("{\"name\":\"storage\",\"menu\":" + overview + "}"));
            c.hx().call("menu.onCommand", MenuKit.obj("{\"name\":\"enderchest\",\"menu\":" + ec + "}"));
            c.hx().call("menu.onCommand", MenuKit.obj("{\"name\":\"backpack\",\"menu\":" + bp + "}"));
            c.ctx().waitTicks(5);

            // ---- 1: press on the Storage menu, then press again mid-scan ----
            openStorage(c);
            shot(c, "storage-menu");
            String idle = c.onClient(mc -> (String) Mod.staticCall(SFEAT, "scanButtonLabel"));
            c.note("button on the Storage menu reads \"" + idle + "\" at " + box(c));
            c.check("Scan All".equals(idle), "the idle button reads \"" + idle + "\"");
            int mark = c.commands().size();
            press(c);
            c.waitUntil("Scan All to start from the button", mc -> (Boolean) Mod.staticCall(SCAN_ALL, "isRunning"), 20);
            c.check(c.onClient(mc -> (Boolean) Mod.staticCall(SCAN_ALL, "isStorageOnly")),
                    "the button started a scan with the wardrobe and pets in it");
            awaitPageOpen(c, 2);
            String running = c.onClient(mc -> (String) Mod.staticCall(SFEAT, "scanButtonLabel"));
            shot(c, "mid-scan");
            c.note("mid-scan the button reads \"" + running + "\"");
            c.check(running.matches("Stop [0-9]+/8"), "mid-scan the button reads \"" + running + "\", want \"Stop n/8\"");
            press(c);
            c.waitUntil("the second press to stop it", mc -> !(Boolean) Mod.staticCall(SCAN_ALL, "isRunning"), 10);
            int atStop = c.commands().size();
            c.ctx().waitTicks(60);
            List<String> after = c.commands().subList(atStop, c.commands().size());
            List<String> phase1 = c.commands().subList(mark, atStop);
            c.note("phase 1 commands " + phase1 + "; after the stop " + after);
            c.check(after.isEmpty(), "pages were still opened after the second press: " + after);
            c.check(onlyReal(phase1).isEmpty(), "phase 1 opened what is not a real page: " + onlyReal(phase1));
            String back = c.onClient(mc -> (String) Mod.staticCall(SFEAT, "scanButtonLabel"));
            c.check("Scan All".equals(back), "after stopping the button reads \"" + back + "\"");

            // ---- 2: press, then Escape on a page the scan opened ----
            c.onClient(mc -> {
                if (mc.player != null && McCompat.screen(mc) instanceof AbstractContainerScreen<?>) {
                    mc.player.closeContainer();
                }
                return null;
            });
            MenuKit.awaitNoScreen(c, 40);
            openStorage(c);
            press(c);
            c.waitUntil("Scan All to start again", mc -> (Boolean) Mod.staticCall(SCAN_ALL, "isRunning"), 20);
            awaitPageOpen(c, 1);
            c.ctx().getInput().pressKey(256); // GLFW_KEY_ESCAPE
            c.waitUntil("Escape to stop it", mc -> !(Boolean) Mod.staticCall(SCAN_ALL, "isRunning"), 20);
            int atEscape = c.commands().size();
            c.ctx().waitTicks(60);
            List<String> afterEscape = c.commands().subList(atEscape, c.commands().size());
            c.note("after Escape: " + afterEscape);
            c.check(afterEscape.isEmpty(), "pages were still opened after Escape: " + afterEscape);

            // ---- 3: press and let it finish ----
            MenuKit.awaitNoScreen(c, 40);
            openStorage(c);
            int start = c.commands().size();
            press(c);
            c.waitUntil("Scan All to start a third time", mc -> (Boolean) Mod.staticCall(SCAN_ALL, "isRunning"), 20);
            c.waitUntil("Scan All to finish", mc -> !(Boolean) Mod.staticCall(SCAN_ALL, "isRunning"), 1200);
            List<String> cmds = new ArrayList<>(c.commands().subList(start, c.commands().size()));
            c.note("full run commands: " + cmds);
            Set<Integer> pages = new TreeSet<>();
            Set<Integer> packs = new TreeSet<>();
            int storageOpens = 0;
            List<String> other = new ArrayList<>();
            for (String cmd : cmds) {
                String[] w = cmd.trim().split("\\s+");
                if (w[0].equals("storage")) {
                    storageOpens++;
                } else if (w[0].equals("enderchest") && w.length > 1) {
                    pages.add(Integer.parseInt(w[1]));
                } else if (w[0].equals("backpack") && w.length > 1) {
                    packs.add(Integer.parseInt(w[1]));
                } else {
                    other.add(cmd);
                }
            }
            int done = c.onClient(mc -> (Integer) Mod.staticCall(SCAN_ALL, "pagesDone"));
            int total = c.onClient(mc -> (Integer) Mod.staticCall(SCAN_ALL, "pagesTotal"));
            c.note("Storage menu " + storageOpens + "x; Ender Chest pages " + pages + "; backpacks " + packs + "; other "
                    + other + "; progress " + done + "/" + total);
            c.check(storageOpens == 1, "the Storage menu was opened " + storageOpens + " time(s), expected once");
            c.check(pages.equals(Set.of(1, 2, 3)), "Ender Chest pages visited " + pages + ", expected [1, 2, 3]");
            c.check(packs.equals(Set.of(1, 2, 3, 4, 5)), "backpacks visited " + packs + ", expected [1, 2, 3, 4, 5]");
            c.check(other.isEmpty(), "the button's scan sent other commands (wardrobe/pets?): " + other);
            c.check(done == 8 && total == 8, "progress ended at " + done + "/" + total + ", want 8/8");
            List<JsonObject> clicks = c.events("container.click", "menu.click");
            c.note(clicks.size() + " container click(s) reached the server during the case");
            c.check(clicks.isEmpty(), "a press on the button reached the menu as a slot click: " + clicks);
        } finally {
            try {
                c.onClient(mc -> {
                    Mod.staticCall(SCAN_ALL, "stop", "testkit case ended");
                    if (mc.player != null && McCompat.screen(mc) instanceof AbstractContainerScreen<?>) {
                        mc.player.closeContainer();
                    }
                    mc.options.guiScale().set(oldGui);
                    mc.resizeGui();
                    return null;
                });
            } catch (Throwable ignored) {
                // nothing running
            }
            for (String name : new String[]{"storage", "enderchest", "backpack"}) {
                try {
                    c.hx().call("menu.onCommand", MenuKit.obj("{\"name\":\"" + name + "\",\"menu\":null}"));
                } catch (RuntimeException e) {
                    System.out.println("[menu] onCommand clear " + name + " failed: " + e);
                }
            }
            MenuKit.reset(c);
        }
    }

    /** /storage from the client; waits for the Storage menu and for the overlay to have drawn its button there. */
    private static void openStorage(Session c) {
        c.onClient(mc -> {
            Mod.setField(SFEAT, "lastScanButton", null);
            mc.player.connection.sendCommand("storage");
            return null;
        });
        MenuKit.awaitScreen(c, "Storage", 100);
        c.waitUntil("the overlay to draw its Scan All button", mc -> Mod.field(SFEAT, "lastScanButton") != null, 60);
        c.ctx().waitTicks(3);
    }

    /** Waits until the scan has a page (not the Storage menu) open, just opened, with {@code minDone} pages behind it -
     *  so a press or a key lands while that page is still up (it settles 12 ticks before it is closed). */
    private static void awaitPageOpen(Session c, int minDone) {
        c.waitUntil("the scan to have a page open", mc -> {
            String title = MenuKit.screenTitle(mc);
            boolean page = title != null && !title.equals("Storage");
            boolean justOpened = (Boolean) Mod.field(SCAN_ALL, "opened") && ((Number) Mod.field(SCAN_ALL, "ticks")).intValue() <= 2;
            return page && justOpened && (Integer) Mod.staticCall(SCAN_ALL, "pagesDone") >= minDone
                    && Mod.field(SFEAT, "lastScanButton") != null;
        }, 600);
    }

    /**
     * A real left click in the middle of the button the overlay last drew. The Storage Overlay decorates Hypixel's
     * vanilla container screen, so its boxes are plain GUI units (Auto Scale's {@code scalesScreen} is false for it):
     * window pixel = GUI unit x GUI scale. {@link BazaarReskinCases#pressAt} also multiplies by Auto Scale's factor,
     * which is right for the Bazaar canvas but 0.5 in the 854x480 test window, and put this press on the Search button
     * above (it closed the Storage menu and opened the item search; 2026-10-08, after the bazaar-v3 helper change).
     */
    private static void press(Session c) {
        int[] b = c.onClient(mc -> ((int[]) Mod.field(SFEAT, "lastScanButton")).clone());
        double[] at = c.onClient(mc -> {
            double g = mc.getWindow().getGuiScale();
            return new double[]{(b[0] + b[2] / 2.0) * g, (b[1] + b[3] / 2.0) * g};
        });
        c.ctx().getInput().setCursorPos(at[0], at[1]);
        c.ctx().waitTicks(2);
        c.ctx().getInput().pressMouse(0);
        c.ctx().waitTicks(3);
    }

    private static String box(Session c) {
        int[] b = c.onClient(mc -> (int[]) Mod.field(SFEAT, "lastScanButton"));
        return b == null ? "nowhere" : b[0] + "," + b[1] + " " + b[2] + "x" + b[3] + " units";
    }

    /** Commands in {@code cmds} that open something other than a real page of 455's fixture. */
    private static List<String> onlyReal(List<String> cmds) {
        List<String> wrong = new ArrayList<>();
        for (String cmd : cmds) {
            String[] w = cmd.trim().split("\\s+");
            if (w[0].equals("storage")) {
                continue;
            }
            if (w[0].equals("enderchest") && w.length > 1 && Set.of("1", "2", "3").contains(w[1])) {
                continue;
            }
            if (w[0].equals("backpack") && w.length > 1 && Set.of("1", "2", "3", "4", "5").contains(w[1])) {
                continue;
            }
            wrong.add(cmd);
        }
        return wrong;
    }

    private static void shot(Session c, String label) {
        c.ctx().waitTicks(2);
        String name = c.name() + "-" + label;
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        dev.testkit.harness.Report.screenshot(name, taken);
        String dir = System.getenv("TESTKIT_POLISH_SHOTS");
        if (dir == null || dir.isBlank()) {
            return;
        }
        try {
            String tag = System.getenv("TESTKIT_POLISH_TAG");
            String jar = Mod.isCheat() ? "cheat" : "legit";
            Path out = Path.of(dir).resolve((tag == null || tag.isBlank() ? "" : tag + "-") + name + "-" + jar + ".png");
            Files.createDirectories(out.getParent());
            Files.copy(taken, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception | Error e) {
            c.note("could not copy " + name + ": " + e);
        }
    }

    private static boolean hasMethod(Class<?> cls, String name) {
        for (var m : cls.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }
}

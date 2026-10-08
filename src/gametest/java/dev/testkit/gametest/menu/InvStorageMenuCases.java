package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 455: Storage Search's Scan All against Hypixel's Storage menu with locked Ender Chest pages and empty backpack slots
 * (killer560, 2026-10-07: "if I don't have a backpack in a spot or have an ender chest page unlocked it shouldn't click
 * there"). The server answers {@code /storage} with {@code menus.storage-overview-locked-empty} (pages 1-3 and backpacks
 * 1-5 real, pages 4-9 "Locked Page", backpacks 6-9 "Empty Backpack Slot N", the rest Hypixel's filler) and
 * {@code /enderchest} / {@code /backpack} with a page. Proven from the SERVER's command log: Scan All opened the Storage
 * menu, then visited exactly enderchest 1-3 and backpack 1-5 - each of them, so the run is not vacuous - and never a
 * locked page or an empty slot; and it clicked nothing in the Storage menu.
 */
final class InvStorageMenuCases {

    private static final String SCAN_ALL = "storagesearch.StorageScanAll";

    private InvStorageMenuCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "455-menu-storage-scanall-skips-locked", InvStorageMenuCases::scanAllSkips);
    }

    static void scanAllSkips(Session c) throws Exception {
        if (!Mod.has(SCAN_ALL) || !hasMethod(Mod.cls(SCAN_ALL), "plannedPages")) {
            // A jar from before the fix: run it anyway - the command log below is the verdict either way.
            c.note("this jar has no StorageScanAll.plannedPages (from before the fix); judging by the command log only");
        }
        MenuKit.reset(c);
        JsonObject overview = MenuKit.menu("menus.storage-overview-locked-empty");
        JsonObject ec = MenuKit.menu("menus.ender-chest-1");
        JsonObject bp = MenuKit.menu("menus.storage-backpack-page");
        try {
            c.hx().call("menu.onCommand", MenuKit.obj("{\"name\":\"storage\",\"menu\":" + overview + "}"));
            c.hx().call("menu.onCommand", MenuKit.obj("{\"name\":\"enderchest\",\"menu\":" + ec + "}"));
            c.hx().call("menu.onCommand", MenuKit.obj("{\"name\":\"backpack\",\"menu\":" + bp + "}"));
            c.ctx().waitTicks(5);
            c.onClient(mc -> Mod.staticCall(SCAN_ALL, "start"));
            c.waitUntil("Scan All to start", mc -> (Boolean) Mod.staticCall(SCAN_ALL, "isRunning"), 20);
            // Storage menu + 8 pages at ~20 ticks each, then wardrobe and pets each time out (30 ticks; no menu here).
            c.waitUntil("Scan All to finish", mc -> !(Boolean) Mod.staticCall(SCAN_ALL, "isRunning"), 1200);

            List<String> cmds = c.commands();
            c.note("commands sent: " + cmds);
            Set<Integer> pages = new TreeSet<>();
            Set<Integer> packs = new TreeSet<>();
            int storageOpens = 0;
            for (String cmd : cmds) {
                String[] w = cmd.trim().split("\\s+");
                if (w[0].equals("storage")) {
                    storageOpens++;
                } else if (w[0].equals("enderchest") && w.length > 1) {
                    pages.add(Integer.parseInt(w[1]));
                } else if (w[0].equals("backpack") && w.length > 1) {
                    packs.add(Integer.parseInt(w[1]));
                }
            }
            c.note("Storage menu opened " + storageOpens + "x; Ender Chest pages visited " + pages + "; backpacks visited "
                    + packs);
            Set<Integer> wantPages = new TreeSet<>(List.of(1, 2, 3));
            Set<Integer> wantPacks = new TreeSet<>(List.of(1, 2, 3, 4, 5));
            List<String> wrong = new ArrayList<>();
            for (int p : pages) {
                if (!wantPages.contains(p)) {
                    wrong.add("enderchest " + p + (p >= 4 ? " (a Locked Page)" : ""));
                }
            }
            for (int b : packs) {
                if (!wantPacks.contains(b)) {
                    wrong.add("backpack " + b + (b <= 9 ? " (an Empty Backpack Slot)" : " (nothing there)"));
                }
            }
            c.check(wrong.isEmpty(), "Scan All visited what is not a real page: " + wrong);
            // Not vacuous: every real page WAS visited, and the plan was read from the menu.
            c.check(pages.equals(wantPages), "Ender Chest pages visited " + pages + ", expected " + wantPages);
            c.check(packs.equals(wantPacks), "backpacks visited " + packs + ", expected " + wantPacks
                    + " (5 is 'Locked & Loaded Backpack', a real backpack the anchored patterns must not skip)");
            c.check(storageOpens == 1, "the Storage menu was opened " + storageOpens + " time(s), expected once");
            if (hasMethod(Mod.cls(SCAN_ALL), "plannedPages")) {
                List<?> planned = c.onClient(mc -> (List<?>) Mod.staticCall(SCAN_ALL, "plannedPages"));
                int locked = c.onClient(mc -> (Integer) Mod.staticCall(SCAN_ALL, "skippedLocked"));
                int empty = c.onClient(mc -> (Integer) Mod.staticCall(SCAN_ALL, "skippedEmpty"));
                boolean fromMenu = c.onClient(mc -> (Boolean) Mod.staticCall(SCAN_ALL, "planFromMenu"));
                c.note("plan " + planned + "; skipped " + locked + " locked page(s), " + empty
                        + " empty backpack slot(s); from the Storage menu: " + fromMenu);
                c.check(fromMenu, "the plan did not come from the Storage menu");
                c.check(locked == 6 && empty == 4, "skipped " + locked + " locked / " + empty + " empty, expected 6 / 4");
            }
            // Scan All never clicks the Storage menu's icons; only a wardrobe/pets Next Page is ever clicked.
            List<JsonObject> clicks = c.events("container.click", "menu.click");
            c.note(clicks.size() + " container click(s) during the run");
            c.check(clicks.isEmpty(), "Scan All clicked in a menu: " + clicks);
        } finally {
            for (String name : new String[]{"storage", "enderchest", "backpack"}) {
                try {
                    c.hx().call("menu.onCommand", MenuKit.obj("{\"name\":\"" + name + "\",\"menu\":null}"));
                } catch (RuntimeException e) {
                    System.out.println("[menu] onCommand clear " + name + " failed: " + e);
                }
            }
            try {
                c.onClient(mc -> {
                    Mod.staticCall(SCAN_ALL, "stop", "testkit case ended");
                    return null;
                });
            } catch (Throwable ignored) {
                // nothing running
            }
            MenuKit.reset(c);
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

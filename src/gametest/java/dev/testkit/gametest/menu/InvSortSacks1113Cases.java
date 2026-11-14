package dev.testkit.gametest.menu;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Inventory Sorter "Fetch Missing From Sacks" (invsortsacks-1113, killer560 2026-11-13: "[InvSort] Not in any storage
 * page ...: [ENDER_PEARL, SUPERBOOM_TNT, ...] if something is in my sax that it can get have it do that"):
 * <ul>
 *   <li>1113-invsort-sacks-unknown: a server without /gfs answers "Unknown or incomplete command"; the sorter sends ONE /gfs,
 *   stops asking, and still finishes (runs first: the stub of the next case cannot be undone).</li>
 *   <li>1113-invsort-sacks-split: storage holds the runebook, the sacks the pearl (fake "Moved N ... from your Sacks"), and the
 *   superboom is in neither ("You have no ... in your Sacks!"): the /enderchest visit goes before any /gfs, the pearl is
 *   placed in its layout slot, and only the superboom is named as missing.</li>
 *   <li>1113-invsort-sacks-off: with the setting OFF no /gfs goes out.</li>
 * </ul>
 */
final class InvSortSacks1113Cases {

    private static final String ISC = "invsort.InventorySorterConfig";
    private static final String EXEC = "invsort.InventorySorterExecutor";
    private static final String BOOK = "minecraft:enchanted_book[custom_data={id:\"RUNEBOOK\",enchantments:{smite:6}}]";
    private static final String PEARL = "minecraft:ender_pearl[custom_data={id:\"ENDER_PEARL\"}]";

    private InvSortSacks1113Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1113-invsort-sacks-unknown", c -> body(c, "unknown"));
        MenuSuite.test(s, "1113-invsort-sacks-split", c -> body(c, "split"));
        MenuSuite.test(s, "1113-invsort-sacks-off", c -> body(c, "off"));
    }

    private static void body(dev.testkit.gametest.hx.Session c, String mode) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: the sorter is cheat-only");
            return;
        }
        Path p = c.onClient(mc -> (Path) Mod.field(ISC, "CONFIG_PATH"));
        byte[] old = c.onClient(mc -> {
            try {
                return java.nio.file.Files.exists(p) ? java.nio.file.Files.readAllBytes(p) : null;
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
        });
        try {
            c.ctx().runOnClient(mc -> {
                Mod.call(Mod.cfg(ISC), "setTicksBetweenMoves", 2);
                Mod.call(Mod.cfg(ISC), "setRandomExtraTicks", 0);
                Mod.call(Mod.cfg(ISC), "setInstant", false);
                Mod.call(Mod.cfg(ISC), "setFetchSacks", !mode.equals("off"));
                Mod.setField("util.SackCommands", "dead", false);
            });
            c.hx().sidebar("SKYBLOCK", "Hx sacks line");
            c.waitUntil("a Skyblock sidebar", mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isOnSkyblock"), 120);
            c.hx().stub(null, "enderchest", "backpack");
            if (mode.equals("split")) {
                c.hx().stub(null, "gfs");
            }
            c.hx().give(0, "minecraft:emerald");
            c.hx().give(8, "minecraft:nether_star[custom_data={id:\"SKYBLOCK_MENU\"}]");
            c.waitUntil("the emerald", mc -> mc.player.getInventory().getItem(0).is(Items.EMERALD)
                    && mc.player.getInventory().getItem(8).is(Items.NETHER_STAR), 60);
            String key = c.onClient(mc -> (String) Mod.staticCall("storageoverlay.StorageOverlayFeature", "storageKeyForTitle",
                    "Ender Chest (1/9)"));
            c.ctx().runOnClient(mc -> {
                List<ItemStack> page = new ArrayList<>();
                for (int i = 0; i < 45; i++) {
                    page.add(ItemStack.EMPTY);
                }
                if (mode.equals("split")) {
                    page.set(4, InvSort1103Cases.book("smite", 6));
                }
                Mod.call(Mod.staticCall("storageoverlay.StorageOverlayCache", "getInstance"), "put", key, page);
            });
            Object layout = c.onClient(mc -> {
                try {
                    return Mod.cls("invsort.InventoryLayout").getConstructor(String.class, Map.class, Map.class, Map.class,
                            Set.class, boolean.class, String.class).newInstance("hx-1113-sacks",
                            Map.of(0, "EMERALD", 2, "RUNEBOOK", 3, "ENDER_PEARL", 4, "SUPERBOOM_TNT"), Map.of(), Map.of(),
                            Set.of(), true, null);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            long mark = LogTap.mark();
            long since = c.hx().head();
            c.check(c.onClient(mc -> (Boolean) Mod.staticCall(EXEC, "start", layout)), "start returned false");
            boolean answeredPearl = false;
            boolean answeredBoom = false;
            boolean opened = false;
            for (int i = 0; i < 1200 && c.onClient(mc -> (Boolean) Mod.staticCall(EXEC, "isRunning")); i++) {
                c.ctx().waitTicks(1);
                List<String> cmds = c.hx().commands(since);
                if (!opened && cmds.stream().anyMatch(x -> x.startsWith("enderchest 1"))) {
                    c.hx().menu("Ender Chest (1/9)", 6, Map.of(0, "minecraft:black_stained_glass_pane", 12, BOOK), "storage");
                    opened = true;
                }
                if (mode.equals("split") && !answeredPearl && cmds.stream().anyMatch(x -> x.startsWith("gfs ender_pearl "))) {
                    c.hx().give(20, PEARL);
                    c.ctx().waitTicks(2);
                    c.hx().chat("Moved 16 Ender Pearl from your Sacks to your inventory.");
                    answeredPearl = true;
                }
                if (mode.equals("split") && answeredPearl && !answeredBoom
                        && cmds.stream().anyMatch(x -> x.startsWith("gfs superboom_tnt "))) {
                    c.hx().chat("You have no Superboom TNT in your Sacks!");
                    answeredBoom = true;
                }
            }
            c.check(!(Boolean) c.onClient(mc -> (Boolean) Mod.staticCall(EXEC, "isRunning")), "the sorter never finished");
            List<String> cmds = c.hx().commands(since);
            List<String> gfs = cmds.stream().filter(x -> x.startsWith("gfs ")).toList();
            c.note(mode + ": commands " + cmds);
            switch (mode) {
                case "off" -> c.check(gfs.isEmpty(), "Fetch Missing From Sacks is OFF but /gfs went out: " + gfs);
                case "unknown" -> {
                    c.check(gfs.size() == 1, "want exactly one /gfs before the unknown-command stop: " + gfs);
                    c.check(!LogTap.since(mark).stream().filter(l -> l.contains("no more automatic /gfs")).toList().isEmpty(),
                            "the server never answered unknown command, so the stop was not exercised");
                }
                default -> {
                    int ec = cmds.indexOf(cmds.stream().filter(x -> x.startsWith("enderchest 1")).findFirst().orElse("?"));
                    int g = cmds.indexOf(gfs.isEmpty() ? "?" : gfs.get(0));
                    c.check(ec >= 0 && g > ec, "storage must be visited before the sacks are asked: " + cmds);
                    c.check(answeredPearl && answeredBoom, "pearl answered " + answeredPearl + ", boom answered " + answeredBoom
                            + "; " + gfs);
                    c.check(gfs.size() == 2, "want one /gfs per missing item: " + gfs);
                    c.check(c.onClient(mc -> mc.player.getInventory().getItem(3).is(Items.ENDER_PEARL)),
                            "the fetched pearl is not in layout slot 3");
                    c.check(c.onClient(mc -> mc.player.getInventory().getItem(2).is(Items.ENCHANTED_BOOK)),
                            "the runebook from storage is not in layout slot 2");
                    List<String> done = LogTap.since(mark).stream().filter(l -> l.contains("Sacks done")).toList();
                    c.check(done.size() == 1 && done.get(0).contains("1 fetched") && done.get(0).contains("[SUPERBOOM_TNT]"),
                            "sacks result line: " + done);
                }
            }
        } finally {
            if ((Boolean) c.onClient(mc -> (Boolean) Mod.staticCall(EXEC, "isRunning"))) {
                c.ctx().runOnClient(mc -> Mod.staticCall(EXEC, "requestStop"));
            }
            c.hx().sidebarClear();
            MenuKit.reset(c);
            c.ctx().runOnClient(mc -> {
                try {
                    if (old == null) {
                        java.nio.file.Files.deleteIfExists(p);
                    } else {
                        java.nio.file.Files.write(p, old);
                    }
                } catch (java.io.IOException e) {
                    throw new AssertionError(e);
                }
                Mod.staticCall(ISC, "load");
                Mod.setField("util.SackCommands", "dead", false);
            });
            InventoryCases.clearInv(c);
        }
    }
}

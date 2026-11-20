package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.ui.AutoReclaim1117UiCases;

import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * autoreclaim-1117 (menu session, `200-menu-session:,1117-menu-reclaim`): the /reclaim item rules, and the cheat flow
 * against a server Reclaim Menu shaped like the one in the 2026-11-17 capture (HxMenuSpec "reclaim": shift-click into slot
 * 22, anvil on 25 when accepted, claim chat, item back on close).
 * <ul>
 *   <li>1117-menu-reclaim-rules: each patch item type, and near misses that must not match.</li>
 *   <li>1117-menu-reclaim-flow: Pull And Reclaim pulls a book out of an Ender Chest page, reclaims the two accepted items,
 *       counts the refused one, one click a tick, items back in the inventory.</li>
 *   <li>1117-menu-reclaim-same-{click,swap,use}: the same run with his own container click, hotbar swap or right click
 *       landing in the ticks of the automated clicks (SameTick); the harness fails any GrimAC flag.</li>
 * </ul>
 */
final class AutoReclaim1117Cases {

    private static final String CFG = AutoReclaim1117UiCases.CFG;
    private static final String RULES = "autoreclaim.ReclaimRules";
    private static final String MENU = "autoreclaim.ReclaimMenu";

    private AutoReclaim1117Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1117-menu-reclaim-rules", AutoReclaim1117Cases::rules);
        MenuSuite.test(s, "1117-menu-reclaim-flow", c -> flow(c, null));
        MenuSuite.test(s, "1117-menu-reclaim-same-click", c -> flow(c, SameTick.Act.SCREEN_CLICK));
        MenuSuite.test(s, "1117-menu-reclaim-same-swap", c -> flow(c, SameTick.Act.SWAP));
        MenuSuite.test(s, "1117-menu-reclaim-same-use", c -> flow(c, SameTick.Act.USE));
    }

    private static ItemStack st(Item item, String id, int upgrades, String rarityLine, String... enchants) {
        return AutoReclaim1117UiCases.stack(item, id, upgrades, rarityLine, enchants);
    }

    private static String reason(Session c, ItemStack s) {
        return c.onClient(mc -> (String) Mod.staticCall(RULES, "reason", s));
    }

    static void rules(Session c) throws Exception {
        Object[][] rows = {
                {st(Items.GOLDEN_APPLE, "A1", 1, "VERY SPECIAL ACCESSORY"), "Recombobulator"},
                {st(Items.GOLDEN_APPLE, "A2", 1, "SPECIAL ACCESSORY"), "Recombobulator"},
                {st(Items.GOLDEN_APPLE, "A3", 1, "a VERY SPECIAL ACCESSORY a"), "Recombobulator"},
                {st(Items.GOLDEN_APPLE, "A4", 1, "VERY SPECIAL HATCCESSORY"), "Recombobulator"},
                {st(Items.GOLDEN_APPLE, "N1", 1, "LEGENDARY ACCESSORY"), null},
                {st(Items.GOLDEN_APPLE, "N2", 0, "SPECIAL ACCESSORY"), null},
                {st(Items.GOLDEN_APPLE, "N3", 1, "VERY SPECIAL SWORD"), null},
                {st(Items.GOLDEN_SWORD, "D1", 0, "LEGENDARY SWORD", "divine_gift"), "Divine Gift"},
                {st(Items.ENCHANTED_BOOK, "B1", 0, "EPIC", "chance"), "Chance"},
                {st(Items.ENCHANTED_BOOK, "B2", 0, "EPIC", "luck"), "Luck"},
                {st(Items.ENCHANTED_BOOK, "B3", 0, "EPIC", "chance", "luck"), "Chance, Luck"},
                {st(Items.GOLDEN_APPLE, "C1", 1, "VERY SPECIAL ACCESSORY", "divine_gift"), "Recombobulator, Divine Gift"},
                {st(Items.STONE_SWORD, "N4", 0, "EPIC SWORD", "looting", "luck_of_the_sea"), null},
                {new ItemStack(Items.DIAMOND), null},
                {ItemStack.EMPTY, null}};
        List<String> wrong = new ArrayList<>();
        int matched = 0;
        for (Object[] row : rows) {
            String got = reason(c, (ItemStack) row[0]);
            if (got != null) {
                matched++;
            }
            if (!java.util.Objects.equals(got, row[1])) {
                wrong.add(((ItemStack) row[0]).getItem() + " want " + row[1] + " got " + got);
            }
        }
        c.note(rows.length + " stacks judged, " + matched + " matched; wrong " + wrong);
        c.check(matched == 9, "the rules matched " + matched + " stacks, want 9 (nothing was judged if 0)");
        c.check(wrong.isEmpty(), "wrong answers: " + wrong);
    }

    private static final String ACC = "minecraft:golden_apple[custom_data={id:\"R_ACC\",rarity_upgrades:1},"
            + "lore=[{text:\"VERY SPECIAL ACCESSORY\",italic:false}]]";
    private static final String NO = "minecraft:golden_sword[custom_data={id:\"R_NO\",enchantments:{divine_gift:1}},"
            + "lore=[{text:\"LEGENDARY SWORD\",italic:false}]]";
    private static final String BOOK = "minecraft:enchanted_book[custom_data={id:\"R_BOOK\",enchantments:{chance:3}},"
            + "lore=[{text:\"EPIC\",italic:false}]]";

    private static List<String> chat(Session c) {
        return c.onClient(mc -> {
            @SuppressWarnings("unchecked")
            List<GuiMessage> all = (List<GuiMessage>) Mod.field(dev.testkit.compat.McCompat.chat(mc), "allMessages");
            List<String> out = new ArrayList<>();
            all.forEach(m -> out.add(m.content().getString()));
            return out;
        });
    }

    private static int count(Session c, String type, String key, String value) {
        int n = 0;
        for (JsonObject e : c.events(type)) {
            if (key == null || value.equals(e.get(key).getAsString())) {
                n++;
            }
        }
        return n;
    }

    static void flow(Session c, SameTick.Act act) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Pull And Reclaim does not exist");
            return;
        }
        String key = c.onClient(mc -> (String) Mod.staticCall("storageoverlay.StorageOverlayFeature", "storageKeyForTitle",
                "Ender Chest (1/9)"));
        Object cache = c.onClient(mc -> Mod.staticCall("storageoverlay.StorageOverlayCache", "getInstance"));
        SameTick tick = null;
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CFG, "Enabled", true).set(CFG, "PullAndReclaim", true);
            c.hx().give(0, ACC);
            c.hx().give(1, NO);
            c.hx().give(2, "minecraft:stone_sword[custom_data={id:\"R_PLAIN\"}]");
            c.waitUntil("the three stacks", mc -> mc.player.getInventory().getItem(0).is(Items.GOLDEN_APPLE)
                    && mc.player.getInventory().getItem(1).is(Items.GOLDEN_SWORD)
                    && mc.player.getInventory().getItem(2).is(Items.STONE_SWORD), 60);
            // The Storage Overlay cache knows page 1 holds the book (what the report reads the page list from).
            c.ctx().runOnClient(mc -> {
                List<ItemStack> page = new ArrayList<>();
                for (int i = 0; i < 45; i++) {
                    page.add(i == 3 ? st(Items.ENCHANTED_BOOK, "R_BOOK", 0, "EPIC", "chance") : ItemStack.EMPTY);
                }
                Mod.call(cache, "put", key, page);
            });
            c.hx().stub(null, "enderchest", "backpack", "accessories");
            JsonObject menu = MenuKit.obj("{\"title\":\"Reclaim Menu\",\"rows\":6,\"reclaim\":true,"
                    + "\"accept\":[\"R_ACC\",\"R_BOOK\"],\"slots\":{\"49\":{\"item\":\"minecraft:barrier\",\"name\":\"Close\"},"
                    + "\"50\":{\"item\":\"minecraft:redstone_torch\",\"name\":\"Recently added reclaims\"}}}");
            c.hx().call("menu.onCommand", "name", "reclaim", "menu", menu);
            long since = c.hx().head();
            if (act != null) {
                tick = c.onClient(mc -> SameTick.start(act, 1, p -> p instanceof ServerboundContainerClickPacket, 4, 5));
            }
            c.ctx().runOnClient(mc -> {
                dev.testkit.compat.McCompat.clearChatAndToasts(mc);
                mc.player.connection.sendCommand("autoreclaim");
            });
            boolean opened = false;
            boolean ended = false;
            for (int i = 0; i < 1200 && !ended; i++) {
                c.ctx().waitTicks(1);
                if (!opened && c.hx().commands(since).stream().anyMatch(cmd -> cmd.startsWith("enderchest 1"))) {
                    c.hx().menu("Ender Chest (1/9)", 6, Map.of(0, "minecraft:black_stained_glass_pane", 12, BOOK), "storage");
                    opened = true;
                }
                ended = i > 5 && !(Boolean) c.onClient(mc -> Mod.staticCall(MENU, "isRunning"));
            }
            c.ctx().waitTicks(5);
            int[] counts = c.onClient(mc -> (int[]) Mod.staticCall(MENU, "counts"));
            List<String> cmds = c.hx().commands(since);
            List<String> lines = chat(c);
            c.note("counts [pulled, reclaimed, refused] " + java.util.Arrays.toString(counts) + "; commands " + cmds
                    + "; puts " + count(c, "reclaim.put", null, null) + ", claims " + count(c, "reclaim.claim", null, null)
                    + "; " + MenuKit.cadence(c));
            c.check(ended, "the run never ended");
            c.check(opened, "the run never sent /enderchest 1; commands " + cmds);
            boolean strict = act != SameTick.Act.SCREEN_CLICK; // his own click may spoil one try: the step counts may differ, the END state may not
            c.check(strict ? counts[0] == 1 && counts[1] == 2 && counts[2] == 1 : counts[0] >= 1 && counts[1] == 2 && counts[2] >= 1,
                    "want pulled 1, reclaimed 2, refused 1; got " + java.util.Arrays.toString(counts));
            c.check(strict ? count(c, "reclaim.put", null, null) == 3 : count(c, "reclaim.put", null, null) >= 3,
                    "the server saw " + count(c, "reclaim.put", null, null) + " items put in the Reclaim Menu");
            c.check(count(c, "reclaim.put", "skyblockId", "R_PLAIN") == 0 && count(c, "reclaim.put", "skyblockId", "R_BOOK") >= 1,
                    "a non-matching item went into or the book never reached the Reclaim Menu");
            c.check(count(c, "reclaim.claim", null, null) == 2,
                    "the server saw " + count(c, "reclaim.claim", null, null) + " claims, want 2 (the refused item has no Reclaim item button)");
            c.check(!strict || cmds.stream().filter(cmd -> cmd.equals("reclaim")).count() == 3, "want /reclaim three times: " + cmds);
            c.check(MenuKit.maxPerTick(MenuKit.clicksPerTick(c)) <= 1 || act != null,
                    "more than one automated click in a tick: " + MenuKit.cadence(c));
            c.check(lines.stream().anyMatch(l -> l.contains("Started.")) && lines.stream().anyMatch(l -> l.contains("Stopped:")),
                    "chat should say started and stopped: " + lines);
            int back = c.onClient(mc -> {
                int n = 0;
                for (int i = 0; i < 36; i++) {
                    ItemStack s = mc.player.getInventory().getItem(i);
                    if (!s.isEmpty() && s.getItem() != Items.STONE_SWORD) {
                        n++;
                    }
                }
                return n;
            });
            c.check(strict ? back == 3 : back >= 3, "the three reclaim items should be back in the inventory, found " + back);
            if (tick != null) {
                SameTick.judge(c, tick, "reclaim " + act, SameTick.Expect.SHARE);
                tick = null;
            }
        } finally {
            if (tick != null) {
                SameTick t = tick;
                c.onClient(mc -> {
                    t.close();
                    return null;
                });
            }
            c.hx().call("menu.onCommand", "name", "reclaim", "menu", null);
            c.ctx().runOnClient(mc -> Mod.call(cache, "put", key, new ArrayList<ItemStack>()));
            MenuKit.reset(c);
            InventoryCases.clearInv(c);
        }
    }
}

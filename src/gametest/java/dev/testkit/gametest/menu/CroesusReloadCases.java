package dev.testkit.gametest.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.compat.McCompat;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 1119 (croesusreload-1119, killer560 2026-11-19: "every time i reload the game some of the chests are the unchecked or key
 * but it is fake"). Hypixel sends a Croesus page one set_slot at a time over several frames (his logger, 11-18 8:59 PM and
 * 11:58 PM: 33 packets, the screen open after the first head). The old mod matched and SAVED its run memory on every partial
 * page, so a half page placed runs by the heads that had arrived and the full page then paired heads with other runs: his run
 * 2609a223 (Emerald claimed) read "needs key" under a head that said another chest.
 * <p>
 * Each case: one party's runs remembered on disk (a restart: the memory is reloaded from the file), all with the same
 * hour-only expiry, last seen 30 minutes ago; the list then shows new runs joined in front; the page arrives slot by slot,
 * one per tick, the bottom bar last. Every head's state must be its own run's.
 * <ul>
 *   <li>1119-menu-croesus-reload-partial: two new runs in front, six old runs each claimed from a different chest (the
 *       claimed chest worth a key, the rest not): every old run reads opened, never needs-key.</li>
 *   <li>1119-menu-croesus-reload-partial-same: one new run, eight old runs all claimed from Wood, every other one with a
 *       Bedrock worth a key: each keeps its own needs-key / opened, and every run is remembered at its new place.</li>
 * </ul>
 */
final class CroesusReloadCases {

    private CroesusReloadCases() {
    }

    private static final String SIG = "The Catacombs|Floor VII|Bravo: Mage (39)|Killer560: Mage (40)";
    private static final String[] CHESTS = {"WOOD", "GOLD", "DIAMOND", "EMERALD", "OBSIDIAN", "BEDROCK"};

    static void register(Session s) {
        MenuSuite.test(s, "1119-menu-croesus-reload-partial", c -> run(c, 2, 6, false));
        MenuSuite.test(s, "1119-menu-croesus-reload-partial-same", c -> run(c, 1, 8, true));
    }

    private static String display(String type) {
        return type.charAt(0) + type.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    static void run(Session c, int newRuns, int oldRuns, boolean sameChest) throws Exception {
        long now = System.currentTimeMillis();
        long rem = 2 * CroesusTrackCases.DAY + 3 * CroesusTrackCases.HOUR + 20 * 60_000L;
        long seen = now - 30 * 60_000L;
        JsonArray runs = new JsonArray();
        Map<Integer, Byte> want = new LinkedHashMap<>();
        Map<Integer, JsonObject> heads = new LinkedHashMap<>();
        for (int i = 0; i < newRuns; i++) {
            heads.put(CroesusTrackCases.HEAD_SLOTS[i], CroesusTrackCases.realHead(SIG, "§cNo chests opened yet!",
                    CroesusTrackCases.expiry(rem + 20 * 60_000L)));
            want.put(i, (byte) 1);
        }
        for (int k = 0; k < oldRuns; k++) {
            String claim = sameChest ? "WOOD" : CHESTS[k % CHESTS.length];
            boolean keyWorth = sameChest && k % 2 == 0;
            JsonObject r = new JsonObject();
            r.addProperty("id", "11190000-0000-0000-0000-00000000000" + k);
            r.addProperty("sig", SIG);
            r.addProperty("expiresAt", now + 2 * CroesusTrackCases.DAY + 3 * CroesusTrackCases.HOUR);
            r.addProperty("tolMs", CroesusTrackCases.HOUR);
            r.addProperty("order", k);
            r.addProperty("seenAt", seen);
            JsonObject chests = new JsonObject();
            for (String t : CHESTS) {
                long v = t.equals(claim) ? 5_000_000L : t.equals("BEDROCK") && keyWorth ? 5_000_000L : 1_000L;
                chests.addProperty(t, v);
            }
            r.add("chests", chests);
            r.addProperty("claimType", claim);
            r.addProperty("claimKeyed", false);
            r.addProperty("claimAt", seen - 60_000L);
            runs.add(r);
            heads.put(CroesusTrackCases.HEAD_SLOTS[newRuns + k], CroesusTrackCases.realHead(SIG,
                    "§7Opened Chest: §4" + display(claim), CroesusTrackCases.expiry(rem)));
            want.put(newRuns + k, keyWorth ? (byte) 2 : (byte) 3);
        }
        try (AutoCloseable w = CroesusStableCases.world(c)) {
            // The restart: his memory as the file holds it, read by a fresh load.
            Path p = c.onClient(mc -> CroesusTrackCases.runFile());
            Files.createDirectories(p.getParent());
            CroesusTrackCases.drainRunsWriter();
            JsonObject file = new JsonObject();
            file.add("runs", runs);
            Files.writeString(p, file.toString(), StandardCharsets.UTF_8);
            c.ctx().runOnClient(mc -> Mod.staticCall("croesus.ChestProfitFeature", "reloadRunMemoryForTest"));
            Map<Integer, JsonObject> slots = new LinkedHashMap<>(heads);
            slots.put(49, FlipCroesusCases.item("minecraft:barrier", "§cClose"));
            Map<Integer, JsonObject> on = new LinkedHashMap<>();
            heads.keySet().forEach(k -> on.put(k, new JsonObject()));
            JsonObject list = FlipCroesusCases.menu("(1/1) Croesus", 6, slots, on);
            list.addProperty("fill", false);
            // The fixture menu arrives whole; take its stacks and lay them in again one slot a tick, as Hypixel sends them.
            // The page is shown for the first time only once the heads are emptied: nothing reads it whole before.
            List<Integer> order = new ArrayList<>(slots.keySet());
            Map<Integer, ItemStack> stacks = new LinkedHashMap<>();
            MenuKit.show(c, list);
            MenuKit.awaitScreen(c, "(1/1) Croesus", 100);
            c.ctx().getInput().setCursorPos(2, 2);
            c.onClient(mc -> {
                AbstractContainerScreen<?> cs = (AbstractContainerScreen<?>) McCompat.screen(mc);
                for (int s : order) {
                    stacks.put(s, cs.getMenu().getSlot(s).getItem().copy());
                }
                return null;
            });
            c.check(stacks.values().stream().noneMatch(ItemStack::isEmpty), "premise: the fixture page arrived whole");
            // Reload again: anything the whole-page frame above taught the memory is undone (the file is the fixture).
            CroesusTrackCases.drainRunsWriter();
            Files.writeString(p, file.toString(), StandardCharsets.UTF_8);
            c.onClient(mc -> {
                AbstractContainerScreen<?> cs = (AbstractContainerScreen<?>) McCompat.screen(mc);
                for (int s : order) {
                    cs.getMenu().getSlot(s).set(ItemStack.EMPTY);
                }
                Mod.staticCall("croesus.ChestProfitFeature", "reloadRunMemoryForTest");
                return null;
            });
            c.ctx().waitTicks(2);
            for (int s : order) {
                c.onClient(mc -> {
                    ((AbstractContainerScreen<?>) McCompat.screen(mc)).getMenu().getSlot(s).set(stacks.get(s).copy());
                    return null;
                });
                c.ctx().waitTicks(1);
            }
            c.ctx().waitTicks(6);
            byte[] st = c.onClient(mc -> (byte[]) Mod.staticCall("croesus.ChestProfitFeature", "runStatesForTest"));
            StringBuilder got = new StringBuilder();
            StringBuilder exp = new StringBuilder();
            boolean ok = true;
            for (Map.Entry<Integer, Byte> e : want.entrySet()) {
                int slot = CroesusTrackCases.HEAD_SLOTS[e.getKey()];
                byte g = slot < st.length ? st[slot] : -1;
                got.append(g);
                exp.append(e.getValue());
                ok &= g == e.getValue();
            }
            c.check(ok, "states after the page arrived slot by slot: " + got + " (want " + exp
                    + "; 1 unclaimed, 2 needs key, 3 opened, 5 not looked at)");
            // The memory follows each old run to its new place (the new runs in front pushed it along).
            String orders = c.onClient(mc -> {
                StringBuilder sb = new StringBuilder();
                for (Object r : (List<?>) Mod.field("croesus.CroesusRunMemory", "RUNS")) {
                    String id = (String) Mod.field(r, "id");
                    if (id.startsWith("11190000")) {
                        int k = Integer.parseInt(id.substring(id.length() - 1));
                        int o = ((Number) Mod.field(r, "order")).intValue();
                        if (o != k + newRuns) {
                            sb.append(" run ").append(k).append(" at ").append(o).append(" (want ").append(k + newRuns).append(')');
                        }
                    }
                }
                return sb.toString();
            });
            c.check(orders.isEmpty(), "remembered places after the page:" + orders);
            c.note("states " + got + " (want " + exp + ")");
        }
    }
}

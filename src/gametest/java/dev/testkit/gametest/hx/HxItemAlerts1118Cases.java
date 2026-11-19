package dev.testkit.gametest.hx;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.ui.Frames;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Item Alerts (mod branch bottlealert-1118): Bottle Full Alert and Runebook Rarity Alert. Stacks arrive as the real
 * slot / contents packets through the client's own packet handler (so the mod's mixin marks the inventory dirty), the
 * alert is read from the mod's state and from the text its HUD element drew.
 */
final class HxItemAlerts1118Cases {

    static final String F = "itemalerts.ItemAlertsFeature";
    static final String CFG = "itemalerts.ItemAlertsConfig";
    private static final String[][] TIERS = {
            {"THUNDER", "Thunder in a Bottle", "50,000"},
            {"STORM", "Storm in a Bottle", "500,000"},
            {"HURRICANE", "Hurricane in a Bottle", "5,000,000"}};

    private HxItemAlerts1118Cases() {
    }

    static void register(Session s) {
        s.test("1118-itemalerts-bottle-tiers", HxItemAlerts1118Cases::bottleTiers);
        s.test("1118-itemalerts-bottle-modes", HxItemAlerts1118Cases::bottleModes);
        s.test("1118-itemalerts-runebook", HxItemAlerts1118Cases::runebook);
        s.test("1118-itemalerts-both-and-editor", HxItemAlerts1118Cases::bothAndEditor);
        s.test("1118-itemalerts-settings", HxItemAlerts1118Cases::settings);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static ItemStack bottle(String base, String uuid, boolean fullId, String have, String max) {
        ItemStack st = new ItemStack(Items.PLAYER_HEAD);
        CompoundTag tag = new CompoundTag();
        tag.putString("id", base + (fullId ? "_IN_A_BOTTLE" : "_IN_A_BOTTLE_EMPTY"));
        tag.putString("uuid", uuid);
        st.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        st.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("This bottle is empty."),
                Component.literal("Charge: " + have + "/" + max), Component.literal("EPIC"))));
        return st;
    }

    private static ItemStack runebookStack(String uuid, String rarity) {
        ItemStack st = new ItemStack(Items.PLAYER_HEAD);
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "RUNEBOOK");
        tag.putString("uuid", uuid);
        st.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        st.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Runic kills: 12"), Component.literal(rarity + " ACCESSORY"))));
        return st;
    }

    /** The server sets hotbar slot {@code hotbar} (0-8) of the player's own inventory: the real packet handler runs. */
    private static void give(Session c, int hotbar, ItemStack stack) {
        c.onClient(mc -> {
            mc.getConnection().handleContainerSetSlot(new ClientboundContainerSetSlotPacket(0, 0, InventoryMenu.USE_ROW_SLOT_START + hotbar, stack));
            return null;
        });
        c.ctx().waitTicks(3);
    }

    /** The server moves a stack: both slot packets arrive in one tick, as a click or shift-click sends them. */
    private static void move(Session c, int from, int to, ItemStack stack) {
        c.onClient(mc -> {
            mc.getConnection().handleContainerSetSlot(new ClientboundContainerSetSlotPacket(0, 0, InventoryMenu.USE_ROW_SLOT_START + from, ItemStack.EMPTY));
            mc.getConnection().handleContainerSetSlot(new ClientboundContainerSetSlotPacket(0, 0, InventoryMenu.USE_ROW_SLOT_START + to, stack));
            return null;
        });
        c.ctx().waitTicks(3);
    }

    private static void clear(Session c) {
        for (int i = 0; i < 9; i++) {
            give(c, i, ItemStack.EMPTY);
        }
    }

    private static long fired(Session c) {
        return c.onClient(mc -> (Long) Mod.staticCall(F, "alertsFired"));
    }

    private static int active(Session c, boolean runebook) {
        return c.onClient(mc -> (Integer) Mod.staticCall(F, "activeCount", runebook));
    }

    private static String drawn(Session c, boolean runebook) {
        return c.onClient(mc -> (String) Mod.staticCall(F, "lastDrawn", runebook));
    }

    private static void waitDrawn(Session c, boolean runebook, String want) {
        c.waitUntil("the HUD drew '" + want + "'", mc -> want.equals(Mod.staticCall(F, "lastDrawn", runebook)), 100);
    }

    private static void setCfg(Session c, String setter, Object value) {
        c.onClient(mc -> {
            Mod.set(CFG, setter, value);
            Mod.call(Mod.cfg(CFG), "save");
            return null;
        });
    }

    /** Switches on, sound off, and the baseline (nothing in the inventory) taken. */
    private static Settings on(Session c, boolean bottles, boolean rune) {
        Settings set = new Settings(c).custom(() -> {
            Mod.set(CFG, "setBottleEnabled", bottles);
            Mod.set(CFG, "setRunebookEnabled", rune);
            Mod.set(CFG, "setBottleSound", false);
            Mod.set(CFG, "setRunebookSound", false);
            Mod.set(CFG, "setBottlePersistent", true);
            Mod.set(CFG, "setRunebookPersistent", true);
            Mod.call(Mod.cfg(CFG), "save");
        }, () -> {
            Mod.set(CFG, "setBottleEnabled", false);
            Mod.set(CFG, "setRunebookEnabled", false);
            Mod.set(CFG, "setBottleSound", true);
            Mod.set(CFG, "setRunebookSound", true);
            Mod.set(CFG, "setBottlePersistent", true);
            Mod.set(CFG, "setRunebookPersistent", false);
            Mod.set(CFG, "setBottleSeconds", 10);
            Mod.set(CFG, "setRunebookSeconds", 10);
            Mod.call(Mod.cfg(CFG), "save");
        });
        dungeon(c);
        clear(c);
        run(c, () -> Mod.staticCall(F, "resetForTest"));
        c.ctx().waitTicks(3);
        return set;
    }

    private static void finish(Session c) {
        run(c, () -> McCompat.setScreen(Minecraft.getInstance(), null));
        clear(c);
        run(c, () -> Mod.staticCall(F, "resetForTest"));
    }

    // ---- cases -----------------------------------------------------------------------------------------------------

    private static void bottleTiers(Session c) {
        try (Settings set = on(c, true, false)) {
            for (String[] t : TIERS) {
                String base = t[0], name = t[1], max = t[2];
                long f0 = fired(c);
                // Empty form below max: never alerts, whatever the charge.
                give(c, 0, bottle(base, "u-" + base, false, "1,000", max));
                give(c, 0, bottle(base, "u-" + base, false, "2,000", max));
                c.check(fired(c) == f0 && active(c, false) == 0, name + " alerted below max charge");
                // The charge line reaches max while the id is still the empty one.
                give(c, 0, bottle(base, "u-" + base, false, max, max));
                c.check(fired(c) == f0 + 1, name + ": reaching Charge " + max + "/" + max + " fired " + (fired(c) - f0) + " alerts, wanted 1");
                waitDrawn(c, false, name + " is full!");
                // Re-sending the same full stack (every later slot update) does not alert again.
                give(c, 0, bottle(base, "u-" + base, false, max, max));
                give(c, 0, bottle(base, "u-" + base, true, max, max));
                c.check(fired(c) == f0 + 1, name + " re-alerted on the full id / a repeated update: " + (fired(c) - f0));
                give(c, 0, ItemStack.EMPTY);
                c.waitUntil(name + " alert leaves with the bottle", mc -> (Integer) Mod.staticCall(F, "activeCount", false) == 0, 40);
                // The other form: the id itself changes to the full item (new item below max first).
                String u2 = "v-" + base;
                give(c, 1, bottle(base, u2, false, "49", max));
                give(c, 1, bottle(base, u2, true, max, max));
                c.check(fired(c) == f0 + 2, name + ": the id turning full fired " + (fired(c) - f0 - 1) + " alerts, wanted 1");
                waitDrawn(c, false, name + " is full!");
                give(c, 1, ItemStack.EMPTY);
                c.waitUntil(name + " 2nd alert leaves", mc -> (Integer) Mod.staticCall(F, "activeCount", false) == 0, 40);
            }
            // A full bottle that was never seen filling (first sight) is not an alert.
            long f1 = fired(c);
            give(c, 2, bottle("HURRICANE", "w-1", true, "5,000,000", "5,000,000"));
            c.check(fired(c) == f1, "a bottle that appeared already full alerted");
            c.note("Thunder 50,000 / Storm 500,000 / Hurricane 5,000,000: below max silent, Charge N/N fires once (empty-id form and id-change form), "
                    + "repeat updates silent, HUD text drew, alert leaves with the bottle, an already-full first sight is silent");
        } finally {
            finish(c);
        }
    }

    private static void bottleModes(Session c) {
        try (Settings set = on(c, true, false)) {
            String max = "5,000,000";
            long f0 = fired(c);
            give(c, 0, bottle("HURRICANE", "m-1", false, "4,883,811", max));
            give(c, 0, bottle("HURRICANE", "m-1", false, max, max));
            c.check(fired(c) == f0 + 1 && active(c, false) == 1, "no alert for the bottle that filled");
            // Moving it between slots does not fire again and keeps the alert.
            move(c, 0, 5, bottle("HURRICANE", "m-1", false, max, max));
            c.check(fired(c) == f0 + 1 && active(c, false) == 1, "moving the bottle re-fired or dropped it: fired " + (fired(c) - f0) + ", live " + active(c, false));
            // Opening a screen (an inventory) and closing it.
            run(c, () -> McCompat.setScreen(Minecraft.getInstance(),
                    new net.minecraft.client.gui.screens.inventory.InventoryScreen(Minecraft.getInstance().player)));
            c.ctx().waitTicks(4);
            run(c, () -> McCompat.setScreen(Minecraft.getInstance(), null));
            c.ctx().waitTicks(4);
            c.check(fired(c) == f0 + 1 && active(c, false) == 1, "opening and closing the inventory re-fired or dropped it");
            // The bottle on the cursor (picked up) is still in the inventory for the alert.
            c.onClient(mc -> {
                mc.getConnection().handleContainerSetSlot(new ClientboundContainerSetSlotPacket(-1, 0, 0, mc.player.getInventory().getItem(5).copy()));
                mc.getConnection().handleContainerSetSlot(new ClientboundContainerSetSlotPacket(0, 0, InventoryMenu.USE_ROW_SLOT_START + 5, ItemStack.EMPTY));
                return null;
            });
            c.ctx().waitTicks(4);
            c.check(active(c, false) == 1 && fired(c) == f0 + 1, "a bottle held on the cursor dropped its alert or re-fired");
            c.onClient(mc -> {
                mc.getConnection().handleContainerSetSlot(new ClientboundContainerSetSlotPacket(-1, 0, 0, ItemStack.EMPTY));
                mc.getConnection().handleContainerSetSlot(new ClientboundContainerSetSlotPacket(0, 0, InventoryMenu.USE_ROW_SLOT_START + 5,
                        bottle("HURRICANE", "m-1", false, max, max)));
                return null;
            });
            c.ctx().waitTicks(4);
            // World change: a join, then the server's full contents: the persistent alert stays and nothing re-fires.
            run(c, () -> Mod.staticCall(F, "joinForTest"));
            c.ctx().waitTicks(3);
            c.check(active(c, false) == 1, "a join dropped the persistent alert before the inventory arrived");
            c.onClient(mc -> {
                var menu = mc.player.inventoryMenu;
                mc.getConnection().handleContainerContent(new ClientboundContainerSetContentPacket(0, menu.getStateId(), menu.getItems(), menu.getCarried()));
                return null;
            });
            c.ctx().waitTicks(4);
            c.check(active(c, false) == 1 && fired(c) == f0 + 1, "the world change dropped or re-fired the alert: live " + active(c, false)
                    + ", fired " + (fired(c) - f0));
            // Persistent: still there after a real-clock 2 s with Seconds 1.
            setCfg(c, "setBottleSeconds", 1);
            long t0 = System.currentTimeMillis();
            c.waitUntil("2 s of real time", mc -> System.currentTimeMillis() - t0 >= 2000, 200);
            c.check(active(c, false) == 1, "a Persistent alert timed out");
            // Timed: Seconds 1 takes it away while the bottle stays in the inventory.
            setCfg(c, "setBottlePersistent", false);
            c.waitUntil("a Timed alert leaves while the bottle stays", mc -> (Integer) Mod.staticCall(F, "activeCount", false) == 0, 200);
            c.check(!c.onClient(mc -> mc.player.getInventory().getItem(5).isEmpty()),
                    "the bottle was gone from the inventory");
            // A freshly filled bottle in Timed mode shows, then goes after its seconds.
            give(c, 1, bottle("STORM", "m-2", false, "1", "500,000"));
            give(c, 1, bottle("STORM", "m-2", false, "500,000", "500,000"));
            c.check(active(c, false) == 1, "the Timed alert did not show");
            long t1 = System.currentTimeMillis();
            c.waitUntil("Timed alert (1 s) gone", mc -> (Integer) Mod.staticCall(F, "activeCount", false) == 0, 200);
            c.check(System.currentTimeMillis() - t1 >= 600, "the Timed alert vanished at once");
            // Already full at join: a join and contents with a full bottle that was never seen filling stay silent.
            long f2 = fired(c);
            run(c, () -> Mod.staticCall(F, "resetForTest"));
            run(c, () -> Mod.staticCall(F, "joinForTest"));
            c.onClient(mc -> {
                var menu = mc.player.inventoryMenu;
                mc.getConnection().handleContainerContent(new ClientboundContainerSetContentPacket(0, menu.getStateId(), menu.getItems(), menu.getCarried()));
                return null;
            });
            c.ctx().waitTicks(4);
            c.check(fired(c) == f2 && active(c, false) == 0, "a bottle already full at join alerted");
            c.note("Hurricane fires once; move, inventory open/close, cursor carry and world change keep it and never re-fire; Persistent outlives 2 s at Seconds 1;"
                    + " Timed hides after its seconds with the bottle still held; already full at join is silent");
        } finally {
            finish(c);
        }
    }

    private static void runebook(Session c) {
        try (Settings set = on(c, false, true)) {
            long f0 = fired(c);
            give(c, 3, runebookStack("rb-1", "COMMON"));
            c.check(fired(c) == f0 && active(c, true) == 0, "first sight of the Runebook alerted");
            give(c, 3, runebookStack("rb-1", "COMMON"));
            c.check(fired(c) == f0, "the same rarity alerted");
            give(c, 3, runebookStack("rb-1", "UNCOMMON"));
            c.check(fired(c) == f0 + 1, "UNCOMMON fired " + (fired(c) - f0) + " alerts, wanted 1");
            waitDrawn(c, true, "Runebook upgraded to UNCOMMON!");
            give(c, 3, runebookStack("rb-1", "UNCOMMON"));
            c.check(fired(c) == f0 + 1, "an unchanged UNCOMMON re-fired");
            // Moved to another slot: not an upgrade.
            move(c, 3, 6, runebookStack("rb-1", "UNCOMMON"));
            c.check(fired(c) == f0 + 1, "moving the Runebook fired");
            give(c, 6, runebookStack("rb-1", "RARE"));
            c.check(fired(c) == f0 + 2, "RARE fired " + (fired(c) - f0 - 1) + " alerts, wanted 1");
            waitDrawn(c, true, "Runebook upgraded to RARE!");
            give(c, 6, runebookStack("rb-1", "EPIC"));
            waitDrawn(c, true, "Runebook upgraded to EPIC!");
            give(c, 6, runebookStack("rb-1", "LEGENDARY"));
            waitDrawn(c, true, "Runebook upgraded to LEGENDARY!");
            c.check(fired(c) == f0 + 4 && active(c, true) == 1, "each tier is one alert and a new tier replaces the line: fired " + (fired(c) - f0)
                    + ", live " + active(c, true));
            // A lower rarity never fires.
            give(c, 6, runebookStack("rb-1", "RARE"));
            c.check(fired(c) == f0 + 4, "a rarity going down fired");
            // Persistent until the Runebook leaves.
            give(c, 6, ItemStack.EMPTY);
            c.waitUntil("the Runebook alert leaves with the item", mc -> (Integer) Mod.staticCall(F, "activeCount", true) == 0, 40);
            c.note("first sight and the same rarity silent; UNCOMMON, RARE, EPIC, LEGENDARY one alert each with the rarity in the text; "
                    + "moving it and a lower rarity silent; the alert leaves with the item");
        } finally {
            finish(c);
        }
    }

    private static void bothAndEditor(Session c) {
        try (Settings set = on(c, true, true)) {
            give(c, 0, bottle("HURRICANE", "b-1", false, "1", "5,000,000"));
            give(c, 3, runebookStack("rb-2", "COMMON"));
            give(c, 0, bottle("HURRICANE", "b-1", false, "5,000,000", "5,000,000"));
            give(c, 3, runebookStack("rb-2", "UNCOMMON"));
            c.check(active(c, false) == 1 && active(c, true) == 1, "two alerts at once: bottle " + active(c, false) + ", runebook " + active(c, true));
            waitDrawn(c, false, "Hurricane in a Bottle is full!");
            waitDrawn(c, true, "Runebook upgraded to UNCOMMON!");
            // The HUD editor lists both elements and draws them (example lines when nothing is live).
            run(c, () -> Mod.staticCall(F, "resetForTest"));
            c.ctx().waitTicks(3);
            c.check(active(c, false) == 0 && active(c, true) == 0, "reset left alerts");
            for (String id : new String[]{"bottle_full_alert", "runebook_rarity_alert"}) {
                c.check(c.onClient(mc -> Mod.staticCall("hud.HudElementRegistry", "byId", id)) != null, "HUD element " + id + " is not registered");
            }
            c.onClient(mc -> {
                try {
                    McCompat.setScreen(mc, (Screen) Mod.cls("hud.HudEditorScreen").getConstructor(Screen.class).newInstance((Object) null));
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
                return null;
            });
            c.ctx().waitTicks(4);
            c.onClient(mc -> {
                Frames.extract(mc, McCompat.screen(mc), -1, -1);
                return null;
            });
            for (String id : new String[]{"bottle_full_alert", "runebook_rarity_alert"}) {
                boolean seen = c.onClient(mc -> (Boolean) Mod.staticCall("hud.HudSeen", "drawnRecently", id));
                c.check(seen, "the HUD editor did not draw " + id);
            }
            c.note("a bottle and a Runebook alert live together and each drew its own HUD text; both HUD elements are registered and drawn in the HUD editor");
        } finally {
            finish(c);
        }
    }

    private static void settings(Session c) {
        try (Settings set = on(c, false, false)) {
            run(c, () -> {
                Mod.set(CFG, "setBottleEnabled", true);
                Mod.set(CFG, "setBottlePersistent", false);
                Mod.set(CFG, "setBottleSeconds", 17);
                Mod.set(CFG, "setBottleSound", false);
                Mod.set(CFG, "setRunebookEnabled", true);
                Mod.set(CFG, "setRunebookPersistent", true);
                Mod.set(CFG, "setRunebookSeconds", 999);
                Mod.call(Mod.cfg(CFG), "save");
                Mod.staticCall(CFG, "load");
            });
            Object cfg = c.onClient(mc -> Mod.cfg(CFG));
            c.check((Boolean) Mod.call(cfg, "isBottleEnabledRaw"), "bottle switch lost on reload");
            c.check(!(Boolean) Mod.call(cfg, "isBottlePersistent"), "bottle Timed lost on reload");
            c.check((Integer) Mod.call(cfg, "getBottleSeconds") == 17, "bottle seconds lost on reload");
            c.check(!(Boolean) Mod.call(cfg, "isBottleSound"), "bottle sound lost on reload");
            c.check((Boolean) Mod.call(cfg, "isRunebookEnabledRaw") && (Boolean) Mod.call(cfg, "isRunebookPersistent"), "runebook settings lost on reload");
            c.check((Integer) Mod.call(cfg, "getRunebookSeconds") == 60, "seconds not clamped to 60: " + Mod.call(cfg, "getRunebookSeconds"));
            c.note(String.format(Locale.ROOT, "switches, Persistent/Timed, sound and seconds (17; 999 clamps to 60) saved and reloaded"));
        } finally {
            finish(c);
        }
    }
}

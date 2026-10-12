package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 883 (croesus-flash, 2026-10-12). killer560: "whenever I open the menu it does this quick flash showing the old run
 * stuff. Can it not do that." In the real game frames outrun ticks: Croesus's content packet lands in a frame, and the
 * run states used to be read only at the next tick, so the frames in between drew the opened runs unhidden (and
 * unhighlighted) before Hide took them away. A gametest renders one frame per tick, after the tick, so the case puts the
 * page's items in from an END_CLIENT_TICK listener registered after the mod's - the content lands after the mod's tick,
 * before that frame, exactly as between two real ticks - and records, every frame, what the mod decides for each run
 * slot ({@code hidesSlot}, the very call its slot mixin makes, and on a fixed jar {@code runStateForTest}).
 * <p>
 * Page A (10 unclaimed, 11 done, 12 done) settles first; then page B opens empty (a new container) and gets 10 done,
 * 11 unclaimed, 12 done three ticks later. Asserted: no frame of B before its content carries any state (nothing of A
 * leaks), and from the very frame B's content lands, every done run is hidden and run 11 is unclaimed. On the jar before
 * the fix the content frame draws runs 10 and 12 unhidden - the flash.
 */
final class CroesusFlashCases {

    private CroesusFlashCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "883-menu-croesus-no-flash-on-open", CroesusFlashCases::flash);
    }

    record Frame(int n, boolean content, String items, String hidden, String states) {
    }

    static ItemStack headStack(String player, String stateLine) {
        ItemStack s = new ItemStack(Items.PLAYER_HEAD);
        s.set(DataComponents.CUSTOM_NAME, Component.literal("§cThe Catacombs"));
        s.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Floor VII"), Component.literal(""),
                Component.literal("§7Players:"), Component.literal(" §a" + player + "§f: §eMage (39)"), Component.literal(""),
                Component.literal(stateLine), Component.literal("§8Chests expire in 1d 12h!"))));
        return s;
    }

    static void flash(Session c) throws Exception {
        try (AutoCloseable w = CroesusUiCases.cfg(c, true, true)) {
            boolean hasState = c.onClient(mc -> {
                try {
                    Mod.cls("croesus.ChestProfitFeature").getMethod("runStateForTest", AbstractContainerScreen.class, int.class);
                    return true;
                } catch (NoSuchMethodException e) {
                    return false;
                }
            });
            // Page A: settled, with done runs at 11 and 12 (hidden) and an unclaimed run at 10.
            JsonObject a = CroesusUiCases.page("(1/1) Croesus", 1, 0);
            a.getAsJsonObject("slots").add("11", CroesusUiCases.head("§aNo more chests to open!"));
            a.getAsJsonObject("slots").add("12", CroesusUiCases.head("§aNo more chests to open!"));
            MenuKit.show(c, a);
            MenuKit.awaitScreen(c, "(1/1) Croesus", 100);
            c.ctx().waitTicks(10);
            int aId = c.onClient(MenuKit::containerId);
            // Page B: a new container, empty on arrival; its items land three ticks later, after the mod's tick.
            JsonObject b = FlipCroesusCases.menu("(1/1) Croesus", 6, Map.of(), Map.of());
            b.addProperty("fill", false);
            MenuKit.show(c, b);
            c.waitUntil("page B's container", mc -> MenuKit.containerId(mc) != aId, 100);
            List<Frame> frames = new ArrayList<>();
            AtomicBoolean injected = new AtomicBoolean(false);
            AtomicInteger ticks = new AtomicInteger();
            AtomicBoolean recording = new AtomicBoolean(true);
            c.ctx().runOnClient(mc -> {
                AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) McCompat.screen(mc);
                Map<Integer, ItemStack> content = new LinkedHashMap<>();
                content.put(10, headStack("Delta", "§aNo more chests to open!"));
                content.put(11, headStack("Alpha", "§cNo chests opened yet!"));
                content.put(12, headStack("Echo", "§aNo more chests to open!"));
                ClientTickEvents.END_CLIENT_TICK.register(client -> {
                    if (!injected.get() && McCompat.screen(client) == screen && ticks.incrementAndGet() == 3) {
                        content.forEach((slot, stack) -> screen.getMenu().slots.get(slot).set(stack));
                        injected.set(true);
                    }
                });
                ScreenEvents.afterExtract(screen).register((s, g, mx, my, d) -> {
                    if (!recording.get()) {
                        return;
                    }
                    StringBuilder items = new StringBuilder();
                    StringBuilder hidden = new StringBuilder();
                    StringBuilder states = new StringBuilder();
                    for (int slot : new int[]{10, 11, 12}) {
                        var sl = screen.getMenu().slots.get(slot);
                        items.append(sl.getItem().isEmpty() ? '.' : 'x');
                        hidden.append((Boolean) Mod.staticCall("croesus.ChestProfitFeature", "hidesSlot", screen, sl) ? 'H' : '-');
                        states.append(hasState ? String.valueOf(Mod.staticCall("croesus.ChestProfitFeature", "runStateForTest", screen, slot)) : "?");
                    }
                    frames.add(new Frame(frames.size(), injected.get(), items.toString(), hidden.toString(), states.toString()));
                });
            });
            c.waitUntil("page B's content", mc -> injected.get(), 100);
            c.ctx().waitTicks(6);
            recording.set(false);
            List<Frame> got = c.onClient(mc -> List.copyOf(frames));
            List<String> log = new ArrayList<>();
            got.forEach(f -> log.add(f.n() + (f.content() ? "*" : "") + ":" + f.items() + "/" + f.hidden() + "/" + f.states()));
            int before = 0, after = 0;
            Frame first = null;
            for (Frame f : got) {
                if (f.items().equals("...")) {
                    before++;
                    c.check(f.hidden().equals("---") && (f.states().equals("000") || f.states().equals("???")),
                            "frame " + f.n() + " before B's content carries state " + f.hidden() + "/" + f.states() + " - from page A");
                } else {
                    after++;
                    if (first == null) {
                        first = f;
                    }
                    c.check(f.hidden().equals("H-H"), "frame " + f.n() + " with B's items drew hidden=" + f.hidden()
                            + " (want H-H: done runs 10 and 12 hidden from their first frame) - the flash");
                    if (hasState) {
                        c.check(f.states().equals("414"), "frame " + f.n() + " states " + f.states() + ", want 4 1 4");
                    }
                }
            }
            c.check(before >= 2 && after >= 3, "frames before/after content: " + before + "/" + after + " - " + log);
            c.note("frames (n*=content in; items/hidden/states for runs 10,11,12): " + log);
        }
    }
}

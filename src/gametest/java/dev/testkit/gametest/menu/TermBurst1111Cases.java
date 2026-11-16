package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.WireCapture;

import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ContainerInput;

import java.util.ArrayList;
import java.util.List;

/**
 * termburst-1111 (mod branch termburst-1111): Auto Terminals' Burst Finish. Once the clicks left on a board are at most
 * Burst Size (5 here, its highest), every one of them goes out in ONE client tick, the moment the server has answered the
 * clicks before. Each case asserts the burst acted: the mod's burst counter, the wire (ContainerClick packets between two
 * ClientTickEnd packets), and the server's own record (solved, no wrong clicks, at most 5 clicks in a server tick).
 * <ul>
 *   <li>1111-menu-term-burst-&lt;type&gt;: every clicked terminal type (Melody has no burst).</li>
 *   <li>1111-menu-term-burst-sametick: his hotbar swap and his own container click in EVERY tick of the
 *       terminal, and the solver run three extra times per tick (a high frame rate asking for a second burst): the burst
 *       tick must carry his swap and click, and no tick may carry two bursts.</li>
 *   <li>1111-menu-term-burst-drop: the server drops the final click (Hypixel's lost click); the burst re-sends it.</li>
 *   <li>1111-menu-term-burst-off-&lt;type&gt;: the same boards with Burst Finish off, for the solve time comparison.</li>
 * </ul>
 */
final class TermBurst1111Cases {

    static final String CONFIG = TerminalCases.CONFIG;
    static final String FEATURE = "terminals.TerminalSolverFeature";
    static final List<TerminalCases.Spec> SPECS = List.of(TerminalCases.SPECS.get(0), TerminalCases.SPECS.get(1),
            TerminalCases.NUMBERS_10, TerminalCases.SPECS.get(3), TerminalCases.SPECS.get(4));

    private TermBurst1111Cases() {
    }

    static void register(Session s) {
        for (TerminalCases.Spec spec : SPECS) {
            String n = spec.type().toLowerCase().replace("_", "");
            MenuSuite.test(s, "1111-menu-term-burst-" + n, c -> burst(c, spec, true, false, 5, false));
            MenuSuite.test(s, "1111-menu-term-burst-off-" + n, c -> burst(c, spec, false, false, 5, false));
        }
        MenuSuite.test(s, "1111-menu-term-burst-sametick", c -> burst(c, TerminalCases.SPECS.get(4), true, true, 5, false));
        MenuSuite.test(s, "1111-menu-term-burst-drop", c -> burst(c, new TerminalCases.Spec("SELECT",
                new Object[]{"seed", 17, "color", "RED", "count", 6, "dropFinal", true}, "AutoSelectEnabled"), true, false, 5, false));
    }

    /** The same-tick player actions, run at the start of every client tick while set (client thread only). */
    static volatile Runnable perTick;
    /** Run at END_CLIENT_TICK, just after the tick-end packet: the first packets of the next wire tick. */
    static volatile Runnable perTickEnd;
    private static boolean hooked;

    static void hook() {
        if (hooked) return;
        hooked = true;
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            Runnable r = perTick;
            if (r != null) r.run();
        });
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            Runnable r = perTickEnd;
            if (r != null) r.run();
        });
    }

    private static int num(Session c, String field) {
        return c.onClient(mc -> ((Number) Mod.field(FEATURE, field)).intValue());
    }

    static void burst(Session c, TerminalCases.Spec spec, boolean on, boolean sameTick, int size, boolean useToo) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Auto Terminals and Burst Finish are cheat-only");
            boolean legitOn = c.onClient(mc -> (Boolean) Mod.get(CONFIG, "isBurstFinish"));
            c.check(!legitOn, "legit jar: isBurstFinish() is true");
            return;
        }
        boolean drop = List.of(spec.args()).contains("dropFinal");
        WireCapture.describe = p -> p.getClass().getSimpleName();
        boolean[] acting = {false};
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", true).set(CONFIG, spec.auto(), true)
                    .set(CONFIG, "AutoStreamSafe", false).set(CONFIG, "HoverTerminalsEnabled", false)
                    .set(CONFIG, "QueueTerms", false).set(CONFIG, "BurstFinish", on).set(CONFIG, "BurstSize", size);
            int bursts0 = num(c, "burstsSent");
            boolean[] open = {true};
            boolean[] seenOpen = {false};
            if (sameTick) {
                // Installed BEFORE the open: at a size above the board the whole board bursts on the first click, which can
                // come before a test thread back from awaitScreen (1112-term-burst-size-21-sametick read [21, 0, 0]).
                // Run at END_CLIENT_TICK, right after the tick-end packet, so his actions open every wire tick: the solver
                // sends from render frames after a tick end (and in the next client tick), always after them. From
                // START_CLIENT_TICK a frame burst came first, the server closed the solved terminal before the next tick
                // started and the burst tick carried neither (full session, [8, 0, 0] / [5, 0, 0]).
                c.ctx().runOnClient(mc -> mc.player.getInventory().clearContent());
                c.onClient(mc -> {
                    hook();
                    perTickEnd = () -> {
                        if (!(dev.testkit.compat.McCompat.screen(mc)
                                instanceof net.minecraft.client.gui.screens.inventory.ContainerScreen s)) {
                            if (seenOpen[0]) open[0] = false;
                            return;
                        }
                        seenOpen[0] = true;
                        mc.player.getInventory().setSelectedSlot((mc.player.getInventory().getSelectedSlot() + 1) % 9);
                        int own = s.getMenu().slots.size() - 1;
                        mc.gameMode.handleContainerInput(s.getMenu().containerId, own, 0, ContainerInput.PICKUP, mc.player);
                        if (useToo) {
                            // His own right click (use_item) and swing in the same tick (termplus-1112).
                            mc.gameMode.useItem(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND);
                            dev.testkit.compat.McCompat.swingArm(mc.player);
                        }
                        for (int i = 0; i < 3; i++) {
                            Mod.staticCall(FEATURE, "refreshState");
                        }
                    };
                    return null;
                });
            }
            WireCapture.start();
            JsonObject t = TerminalCases.open(c, spec);
            MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
            if (sameTick) {
                acting[0] = true;
                int[] n = {0};
                // Every client tick while the terminal is open: his hotbar swap, his own click on an empty slot of his
                // inventory (below the terminal grid), and three extra solver frames. No movement key: with a screen open he moves
                // only through Inventory Walk, whose Stop To Click holds every click while he moves (1108-iw-termwalk).
                // The actions run from a client tick hook, so no client tick can pass without them: a test-thread
                // loop (onClient + waitTicks) skipped a tick under load and the burst landed in it (termship-1115,
                // 1112-term-burst-size-10-sametick read [10, 0, 0] with every click clean on experimental Grim).
                while (acting[0] && open[0] && n[0]++ < 200) {
                    c.ctx().waitTicks(1);
                }
                perTickEnd = null;
                c.ctx().runOnClient(mc -> mc.options.keyUp.setDown(false));
            }
            JsonObject solved = MenuKit.awaitEvent(c, "terminal.solved", drop ? 300 : 200);
            MenuKit.awaitNoScreen(c, 40);
            c.ctx().waitTicks(10);
            int bursts = num(c, "burstsSent") - bursts0;
            int last = num(c, "lastBurstClicks");
            // Wire: ContainerClick packets per client tick (ticks end at ServerboundClientTickEndPacket).
            List<int[]> ticks = new ArrayList<>(); // {terminal clicks, his clicks, swaps}
            int[] cur = new int[3];
            int termClicks = 0;
            for (WireCapture.Sent w : WireCapture.snapshot()) {
                if (!w.phase().equals("play")) continue;
                String n = w.className() + " " + w.type();
                if (n.contains("ClientTickEnd")) {
                    ticks.add(cur);
                    cur = new int[3];
                } else if (n.contains("ContainerClick")) {
                    cur[0]++;
                    termClicks++;
                } else if (n.contains("SetCarriedItem")) {
                    cur[2]++;
                }
            }
            ticks.add(cur);
            int maxTick = 0;
            int[] burstTick = null;
            for (int[] k : ticks) {
                if (k[0] > maxTick) {
                    maxTick = k[0];
                    burstTick = k;
                }
            }
            long took = solved.get("solvedTick").getAsLong() - solved.get("openedTick").getAsLong();
            long fromFirst = solved.get("solvedTick").getAsLong() - solved.get("firstTick").getAsLong();
            int rows = t.has("rows") ? t.get("rows").getAsInt() : 6;
            // Only the terminal's own grid: his own click (a same-tick case keeps clicking his inventory until the screen closes)
            // is not the solver clicking on after the solve.
            int after = (int) c.events("container.click").stream()
                    .filter(e -> e.get("tick").getAsLong() > solved.get("solvedTick").getAsLong()
                            && e.get("slot").getAsInt() < rows * 9).count();
            int gridWrong = 0;
            for (JsonObject e : c.events("terminal.click")) {
                if (e.get("slot").getAsInt() < rows * 9 && e.has("correct") && !e.get("correct").getAsBoolean()) gridWrong++;
            }
            c.note(spec.type() + (on ? " burst " + size : " no burst") + (sameTick ? " same-tick" : "") + (drop ? " drop" : "")
                    + ": solved in " + took + " ticks from open (" + fromFirst + " from first click, " + took * 50
                    + " ms), server " + solved.get("clicks") + " clicks, max " + solved.get("maxClicksPerTick")
                    + " a server tick, " + gridWrong + " wrong in the grid; bursts " + bursts + " (last " + last
                    + " clicks); wire max " + maxTick + " container clicks in one client tick of " + termClicks
                    + "; clicks after solve " + after);
            c.check(solved.get("solved").getAsBoolean(), "the terminal was not solved");
            c.check(after == 0, after + " click(s) after the server solved the terminal");
            if (!spec.type().equals("RUBIX")) {
                c.check(gridWrong == 0, "the server rejected " + gridWrong + " grid click(s)");
            }
            if (!on) {
                c.check(bursts == 0, "Burst Finish off but " + bursts + " burst(s) were sent");
                c.check(maxTick <= 1 + (sameTick ? 1 : 0), "Burst Finish off but " + maxTick + " clicks in one tick");
                return;
            }
            c.check(bursts >= 1 && (drop || last >= 2), "no burst acted (bursts " + bursts + ", last " + last + ")");
            c.check(drop ? bursts >= 2 : true, "the dropped final click was never re-sent by a second burst");
            int expect = drop ? size : last;
            if (!sameTick) {
                c.check(maxTick == Math.max(expect, last), "wire: the burst of " + last + " was not one client tick (max "
                        + maxTick + ")");
            } else {
                // The burst tick also carries his own click and his hotbar swap.
                c.check(maxTick >= last + 1 && burstTick[2] > 0, "wire: the burst tick did not carry his click and swap: "
                        + java.util.Arrays.toString(burstTick) + ", burst " + last);
                c.check(maxTick <= size + 1, "wire: more than one burst in a client tick (" + maxTick + " clicks)");
            }
            c.check(maxTick <= size + (sameTick ? 1 : 0), "more than " + size + " burst clicks in one tick: " + maxTick);
        } finally {
            acting[0] = false;
            perTickEnd = null;
            WireCapture.stop();
            WireCapture.describe = null;
            c.ctx().runOnClient(mc -> mc.options.keyUp.setDown(false));
            MenuKit.reset(c);
        }
    }
}

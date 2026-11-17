package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.WireCapture;

import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * termburstonly-1116 (mod branch termburstonly-1116): Auto Terminals "Mode: Burst". Every click goes out in bursts of at
 * most Max Clicks Per Burst in ONE client tick, at least Delay Between Bursts apart; no paced single clicks.
 * <ul>
 *   <li>1116-term-bmode-&lt;type&gt;-&lt;N&gt;: N = 1, 5, 10, 21 at delay 0 on Panes (15), Select (24), Numbers (14), Starts With
 *       and Rubix. Solved, no wrong grid click, nothing after the solve, every click tick is a burst, the wire never has more
 *       than N clicks in a client tick and the first burst is exactly min(N, board).</li>
 *   <li>1116-term-bmode-same-&lt;act&gt;-&lt;N&gt;: his own container click (CLICK, SCREEN_CLICK), right click (USE), attack,
 *       hotbar swap, a teleport accept, in every tick of the terminal; some server tick must carry both.</li>
 *   <li>1116-term-bmode-open2-&lt;N&gt;: a second terminal opens over the first mid-burst; the second still finishes clean.</li>
 *   <li>1116-term-bmode-drop / -parts / -delay / -persist / -legit.</li>
 * </ul>
 */
final class TermBurstMode1116Cases {

    static final String CONFIG = TerminalCases.CONFIG;
    static final String FEATURE = TermBurst1111Cases.FEATURE;
    static final int[] SIZES = {1, 5, 10, 21};
    static final TerminalCases.Spec PANES15 = new TerminalCases.Spec("PANES", new Object[]{"seed", 61, "count", 15}, "AutoPanesEnabled");
    static final TerminalCases.Spec NUMBERS14 = new TerminalCases.Spec("NUMBERS", new Object[]{"seed", 62, "count", 14}, "AutoNumbersEnabled");
    static final TerminalCases.Spec STARTS = new TerminalCases.Spec("STARTS_WITH",
            new Object[]{"seed", 63, "letter", "S", "count", 21}, "AutoStartsWithEnabled");
    static final TerminalCases.Spec RUBIX = new TerminalCases.Spec("RUBIX", new Object[]{"seed", 64}, "AutoRubixEnabled");
    static final List<TerminalCases.Spec> TYPES = List.of(PANES15, TermPlus1112Cases.SELECT24, NUMBERS14, STARTS, RUBIX);

    private TermBurstMode1116Cases() {
    }

    static void register(Session s) {
        for (TerminalCases.Spec spec : TYPES) {
            String n = spec.type().toLowerCase(Locale.ROOT).replace("_", "");
            for (int size : SIZES) {
                MenuSuite.test(s, "1116-term-bmode-" + n + "-" + size, c -> run(c, spec, size, 0, null));
            }
        }
        for (int size : new int[]{1, 5, 21}) {
            for (TerminalCases.Spec spec : List.of(PANES15, TermPlus1112Cases.SELECT24)) {
                MenuSuite.test(s, "1117-term-bmode-catchup-" + spec.type().toLowerCase(Locale.ROOT).replace("_", "") + "-" + size,
                        c -> run(c, spec, size, 0, null, true));
            }
        }
        for (SameTick.Act a : new SameTick.Act[]{SameTick.Act.CLICK, SameTick.Act.SCREEN_CLICK, SameTick.Act.USE,
                SameTick.Act.ATTACK, SameTick.Act.SWAP, SameTick.Act.TP}) {
            for (int size : new int[]{5, 21}) {
                MenuSuite.test(s, "1116-term-bmode-same-" + a.name().toLowerCase(Locale.ROOT).replace('_', '-') + "-" + size,
                        c -> run(c, TermPlus1112Cases.SELECT24, size, 0, a));
            }
        }
        for (int size : new int[]{5, 21}) {
            MenuSuite.test(s, "1116-term-bmode-open2-" + size, c -> open2(c, size));
        }
        MenuSuite.test(s, "1116-term-bmode-drop", c -> run(c, new TerminalCases.Spec("SELECT",
                new Object[]{"seed", 65, "color", "RED", "count", 12, "dropClicks", 2}, "AutoSelectEnabled"), 5, 0, null));
        MenuSuite.test(s, "1116-term-bmode-parts", c -> run(c, new TerminalCases.Spec("PANES",
                new Object[]{"seed", 66, "count", 12, "parts", 3, "partGap", 20}, "AutoPanesEnabled"), 5, 0, null));
        MenuSuite.test(s, "1116-term-bmode-delay", c -> run(c, TermPlus1112Cases.SELECT24, 3, 250, null));
        MenuSuite.test(s, "1116-term-bmode-persist", TermBurstMode1116Cases::persist);
    }

    private static void config(MenuKit.Cfg cfg, TerminalCases.Spec spec, int size, int delay) {
        cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutoTerminalsEnabled", true).set(CONFIG, spec.auto(), true)
                .set(CONFIG, "AutoStreamSafe", false).set(CONFIG, "HoverTerminalsEnabled", false)
                .set(CONFIG, "QueueTerms", false).set(CONFIG, "BurstFinish", false)
                .set(CONFIG, "BurstMode", true).set(CONFIG, "BurstClicks", size).set(CONFIG, "BurstDelayMs", delay);
    }

    private static int num(Session c, String field) {
        return c.onClient(mc -> ((Number) Mod.field(FEATURE, field)).intValue());
    }

    private static boolean legit(Session c) {
        if (MenuKit.cheat()) return false;
        boolean on = c.onClient(mc -> (Boolean) Mod.get(CONFIG, "isBurstMode"));
        c.check(!on, "legit jar: isBurstMode() is true");
        c.note("legit jar: Auto Terminals and Burst mode are cheat-only");
        return true;
    }

    /** Container clicks per client tick on the wire, with the nanos of each tick's first click (ticks with none: 0). */
    private static List<long[]> wireTicks() {
        List<long[]> ticks = new ArrayList<>();
        long[] cur = new long[2];
        for (WireCapture.Sent w : WireCapture.snapshot()) {
            if (!w.phase().equals("play")) continue;
            String n = w.className() + " " + w.type();
            if (n.contains("ClientTickEnd")) {
                ticks.add(cur);
                cur = new long[2];
            } else if (n.contains("ContainerClick")) {
                if (cur[0]++ == 0) cur[1] = w.nanos();
            }
        }
        ticks.add(cur);
        return ticks;
    }

    private static void prepare(Session c, SameTick.Act act) {
        // Hotbar slots 0 and 1 hold sticks (his use, his swap); hotbar 9 (the menu's last slot) stays empty for his click.
        c.server().command("clear @a");
        c.server().command("item replace entity @a hotbar.0 with minecraft:stick");
        c.server().command("item replace entity @a hotbar.1 with minecraft:stick");
        c.ctx().runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
        if (act == SameTick.Act.ATTACK) {
            // A stand straight ahead at reach 2, so his crosshair is on it with the terminal open.
            c.server().command("kill @e[type=armor_stand,tag=tbm1116]");
            c.server().command("execute as @a at @s run tp @s ~ ~ ~ 0 0");
            c.ctx().waitTicks(3);
            c.server().command("execute as @a at @s run summon armor_stand ^ ^ ^2 {Tags:[\"tbm1116\"],NoGravity:1b,Invulnerable:1b}");
        }
        c.ctx().waitTicks(5);
    }

    static void run(Session c, TerminalCases.Spec spec, int size, int delay, SameTick.Act act) throws Exception {
        run(c, spec, size, delay, act, false);
    }

    /** {@code stall}: every 4th client tick the client thread sleeps 130 ms, so the game runs 2-3 catch-up ticks back to back. */
    static void run(Session c, TerminalCases.Spec spec, int size, int delay, SameTick.Act act, boolean stall) throws Exception {
        MenuKit.reset(c);
        if (legit(c)) return;
        boolean drop = List.of(spec.args()).contains("dropClicks");
        WireCapture.describe = p -> p.getClass().getSimpleName();
        SameTick[] st = {null};
        Runnable[] attack = {null};
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            config(cfg, spec, size, delay);
            // Block Input While Auto Clicking (default ON) swallows his real press on the screen: let it through so it lands.
            if (act == SameTick.Act.SCREEN_CLICK) cfg.set(CONFIG, "BlockInputWhileAutoClicking", false);
            if (act != null) prepare(c, act);
            int bursts0 = num(c, "burstsSent");
            WireCapture.start();
            if (act != null) {
                st[0] = c.onClient(mc -> SameTick.start(act, 1, p -> p instanceof ServerboundContainerClickPacket, 0, 1));
                if (act == SameTick.Act.ATTACK) {
                    // With a menu up vanilla handles no attack key, so his attack goes straight to gameMode: the swing-hit he
                    // lands on the stand in the tick the screen is still open (SameTick marks the attack packet as his). At the
                    // START of the tick, where vanilla's key handling attacks (before the move): sent after the move it drew Post.
                    TermBurst1111Cases.hook();
                    TermBurst1111Cases.perTick = () -> {
                        var mc = net.minecraft.client.Minecraft.getInstance();
                        if (dev.testkit.compat.McCompat.screen(mc) == null) return;
                        if (mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult eh) {
                            mc.gameMode.attack(mc.player, eh.getEntity());
                            dev.testkit.compat.McCompat.swingArm(mc.player);
                        }
                    };
                }
            }
            if (stall) {
                TermBurst1111Cases.hook();
                int[] n = {0};
                TermBurst1111Cases.perTick = () -> {
                    if (++n[0] % 4 == 0) {
                        try {
                            Thread.sleep(130);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                    }
                };
            }
            JsonObject t = TerminalCases.open(c, spec);
            MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
            if (act == SameTick.Act.TP) {
                for (int i = 0; i < 200 && c.onClient(mc -> dev.testkit.compat.McCompat.screen(mc) != null); i++) {
                    // Every tick: bursts keep 75+ ms apart now (every second client tick), so a teleport every other tick could
                    // fall on the ticks between them and never share a server tick with one.
                    c.server().command("execute as @a at @s run tp @s ~ ~ ~");
                    c.ctx().waitTick();
                }
            }
            JsonObject solved = MenuKit.awaitEvent(c, "terminal.solved", 300);
            MenuKit.awaitNoScreen(c, 40);
            TermBurst1111Cases.perTick = null;
            c.ctx().waitTicks(10);
            String name = spec.type() + " burst mode " + size + (delay > 0 ? " delay " + delay : "")
                    + (act != null ? " same-tick " + act : "") + (drop ? " drop" : "") + (stall ? " catch-up" : "");
            if (st[0] != null) {
                SameTick.judge(c, st[0], name, SameTick.Expect.SHARE);
                st[0] = null;
            }
            int bursts = num(c, "burstsSent") - bursts0;
            List<long[]> ticks = wireTicks();
            long maxTick = 0;
            long first = 0;
            int clickTicks = 0;
            long minGapMs = Long.MAX_VALUE;
            long lastNanos = 0;
            for (long[] k : ticks) {
                if (k[0] == 0) continue;
                if (first == 0) first = k[0];
                clickTicks++;
                maxTick = Math.max(maxTick, k[0]);
                if (lastNanos != 0) minGapMs = Math.min(minGapMs, (k[1] - lastNanos) / 1_000_000L);
                lastNanos = k[1];
            }
            int rows = t.has("rows") ? t.get("rows").getAsInt() : 6;
            long solvedTick = solved.get("solvedTick").getAsLong();
            int after = (int) c.events("container.click").stream()
                    .filter(e -> e.get("tick").getAsLong() > solvedTick && e.get("slot").getAsInt() < rows * 9).count();
            int gridWrong = 0;
            for (JsonObject e : c.events("terminal.click")) {
                if (e.get("slot").getAsInt() < rows * 9 && e.has("correct") && !e.get("correct").getAsBoolean()) gridWrong++;
            }
            int serverClicks = solved.get("clicks").getAsInt();
            long took = solvedTick - solved.get("openedTick").getAsLong();
            c.note(name + ": solved in " + took + " ticks (" + took * 50 + " ms), server " + serverClicks + " clicks, max "
                    + solved.get("maxClicksPerTick") + " a server tick, " + gridWrong + " wrong; bursts " + bursts
                    + "; wire: " + clickTicks + " click ticks, first " + first + ", max " + maxTick
                    + (minGapMs == Long.MAX_VALUE ? "" : ", min gap " + minGapMs + " ms") + "; clicks after solve " + after);
            c.check(solved.get("solved").getAsBoolean(), "the terminal was not solved");
            c.check(after == 0, after + " click(s) after the server solved the terminal");
            if (!spec.type().equals("RUBIX")) {
                c.check(gridWrong == 0, "the server rejected " + gridWrong + " grid click(s)");
            }
            c.check(bursts >= 1, "Burst mode sent no burst");
            boolean his = act == SameTick.Act.CLICK || act == SameTick.Act.SCREEN_CLICK;
            c.check(maxTick <= size + (his ? 1 : 0), "wire: " + maxTick + " clicks in one client tick, Max Clicks " + size);
            // The server's own count per server tick is only noted: two client ticks 44 ms apart still land in one tick of a
            // loaded test server (select-1 read 2 there), which no client timing controls. Bursts keep at least 40 ms apart
            // (catch-up ticks under load came 6-25 ms apart and put two bursts in one server tick). The floor is kept at the
            // decision; the wire time is taken at encode on the IO thread, which can shorten a 40 ms gap to ~22 ms under load.
            if (act == null) {
                // The real invariant: no SERVER tick carries clicks of two different bursts. Server clicks (in arrival order,
                // each stamped with the server tick) are dealt to the wire bursts by their sizes.
                List<Long> srvTicks = new ArrayList<>();
                for (JsonObject e : c.events("container.click")) srvTicks.add(e.get("tick").getAsLong());
                int wireClicks = 0;
                for (long[] k : ticks) wireClicks += (int) k[0];
                if (srvTicks.size() != wireClicks) {
                    c.check(drop, "server saw " + srvTicks.size() + " clicks, the wire sent " + wireClicks);
                } else {
                    int at = 0;
                    long lastBurstLastTick = Long.MIN_VALUE;
                    int shared = 0;
                    int bursts2 = 0;
                    for (long[] k : ticks) {
                        if (k[0] == 0) continue;
                        long lo = srvTicks.get(at);
                        long hi = lo;
                        for (int i = 0; i < k[0]; i++) hi = Math.max(hi, srvTicks.get(at++));
                        if (bursts2++ > 0 && lo <= lastBurstLastTick) shared++;
                        lastBurstLastTick = hi;
                    }
                    c.note(name + ": " + bursts2 + " bursts on " + new java.util.TreeSet<>(srvTicks).size()
                            + " server ticks, " + shared + " burst(s) shared a server tick with the one before");
                    c.check(shared == 0, shared + " burst(s) landed in the same SERVER tick as the previous burst");
                }
                // Every tick with a click is a burst: no paced single click went out beside them.
                c.check(clickTicks == bursts, "wire: " + clickTicks + " ticks carried clicks but " + bursts + " bursts were sent");
                if (!drop && !List.of(spec.args()).contains("parts")) {
                    int want = Math.min(size, serverClicks);
                    c.check(first == want, "wire: the first burst was " + first + " clicks, not min(Max Clicks " + size
                            + ", " + serverClicks + ")");
                }
            }
            if (delay > 0) {
                c.check(clickTicks >= 2, "the delay case needs two bursts (" + clickTicks + ")");
                c.check(minGapMs >= delay - 5, "two bursts went " + minGapMs + " ms apart, under Delay Between Bursts " + delay);
            }
        } finally {
            if (st[0] != null) {
                SameTick s0 = st[0];
                c.onClient(mc -> {
                    s0.close();
                    return null;
                });
            }
            TermBurst1111Cases.perTick = null;
            WireCapture.stop();
            WireCapture.describe = null;
            if (act == SameTick.Act.ATTACK) c.server().command("kill @e[type=armor_stand,tag=tbm1116]");
            MenuKit.reset(c);
        }
    }

    /** A second terminal opens over the first one mid-burst (a re-open or the next terminal): the second ends clean. */
    private static void open2(Session c, int size) throws Exception {
        MenuKit.reset(c);
        if (legit(c)) return;
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            config(cfg, TermPlus1112Cases.SELECT24, size, 0);
            cfg.set(CONFIG, PANES15.auto(), true);
            int bursts0 = num(c, "burstsSent");
            JsonObject t1 = TerminalCases.open(c, TermPlus1112Cases.SELECT24);
            MenuKit.awaitScreen(c, t1.get("title").getAsString(), 100);
            for (int i = 0; i < 100 && num(c, "burstsSent") == bursts0; i++) {
                c.ctx().waitTick();
            }
            int mid = num(c, "burstsSent");
            c.check(mid > bursts0, "no burst went out on the first terminal");
            JsonObject t2 = TerminalCases.open(c, PANES15);
            MenuKit.awaitScreen(c, t2.get("title").getAsString(), 100);
            JsonObject solved = MenuKit.awaitEvent(c, "terminal.solved", 300);
            MenuKit.awaitNoScreen(c, 40);
            c.ctx().waitTicks(10);
            int rows = t2.has("rows") ? t2.get("rows").getAsInt() : 5;
            long solvedTick = solved.get("solvedTick").getAsLong();
            int after = (int) c.events("container.click").stream()
                    .filter(e -> e.get("tick").getAsLong() > solvedTick && e.get("slot").getAsInt() < rows * 9).count();
            int gridWrong = 0;
            for (JsonObject e : c.events("terminal.click")) {
                if (e.get("slot").getAsInt() < rows * 9 && e.has("correct") && !e.get("correct").getAsBoolean()) gridWrong++;
            }
            c.note("open2 burst " + size + ": " + (mid - bursts0) + " burst(s) on the first, then " + (num(c, "burstsSent") - mid)
                    + " on the second; solved " + solved.get("type") + " with " + solved.get("clicks") + " clicks, " + gridWrong
                    + " wrong, " + after + " after the solve");
            c.check(solved.get("solved").getAsBoolean(), "the second terminal was not solved");
            c.check(num(c, "burstsSent") > mid, "no burst went out on the second terminal");
            c.check(gridWrong == 0, "the server rejected " + gridWrong + " grid click(s)");
            c.check(after == 0, after + " click(s) after the server solved the second terminal");
        } finally {
            MenuKit.reset(c);
        }
    }

    /** The three settings survive a save and load; a file from before Burst mode loads as Normal, 5 clicks, 0 ms. */
    private static void persist(Session c) throws Exception {
        MenuKit.reset(c);
        Path path = c.onClient(mc -> (Path) Mod.field(CONFIG, "CONFIG_PATH"));
        byte[] before = Files.exists(path) ? Files.readAllBytes(path) : null;
        try {
            c.ctx().runOnClient(mc -> {
                Object cfg = Mod.staticCall(CONFIG, "getInstance");
                Mod.call(cfg, "setBurstMode", true);
                Mod.call(cfg, "setBurstClicks", 13);
                Mod.call(cfg, "setBurstDelayMs", 120);
                Mod.call(cfg, "save");
                Mod.staticCall(CONFIG, "load");
            });
            boolean raw = c.onClient(mc -> (Boolean) Mod.get(CONFIG, "isBurstModeRaw"));
            int clicks = c.onClient(mc -> ((Number) Mod.get(CONFIG, "getBurstClicks")).intValue());
            int delay = c.onClient(mc -> ((Number) Mod.get(CONFIG, "getBurstDelayMs")).intValue());
            c.note("after save and load: mode " + (raw ? "Burst" : "Normal") + ", " + clicks + " clicks, " + delay + " ms");
            c.check(raw && clicks == 13 && delay == 120, "Burst mode settings did not survive a save and load");
            JsonObject obj = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            obj.remove("burstMode");
            obj.remove("burstClicks");
            obj.remove("burstDelayMs");
            Files.writeString(path, obj.toString(), StandardCharsets.UTF_8);
            c.ctx().runOnClient(mc -> Mod.staticCall(CONFIG, "load"));
            raw = c.onClient(mc -> (Boolean) Mod.get(CONFIG, "isBurstModeRaw"));
            clicks = c.onClient(mc -> ((Number) Mod.get(CONFIG, "getBurstClicks")).intValue());
            delay = c.onClient(mc -> ((Number) Mod.get(CONFIG, "getBurstDelayMs")).intValue());
            c.note("old file: mode " + (raw ? "Burst" : "Normal") + ", " + clicks + " clicks, " + delay + " ms");
            c.check(!raw && clicks == 5 && delay == 0, "a file without the Burst mode keys did not load as Normal, 5, 0 ms");
        } finally {
            if (before != null) {
                Files.write(path, before);
            } else {
                Files.deleteIfExists(path);
            }
            c.ctx().runOnClient(mc -> Mod.staticCall(CONFIG, "load"));
            MenuKit.reset(c);
        }
    }
}

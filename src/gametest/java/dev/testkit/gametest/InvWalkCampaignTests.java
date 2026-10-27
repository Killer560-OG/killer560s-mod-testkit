package dev.testkit.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Hx;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Latency;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1027-iw-*: the Inventory Walk GrimAC campaign (killer560, 2026-10-27: "Do lots of tests on inventory walk ... Make sure
 * nothing flags; test them under every circumstance imaginable"). Every row proves three things before its Grim verdict
 * counts: he MOVED while the screen was open (client path and the server's own position, read through Hx player.state
 * without waiting a tick), every click REACHED the server (the testmod's container.click events, one per packet, each
 * carrying the server's view of his input when it was handled), and the items ended where the client predicted (the
 * server's menu slots against a snapshot taken in the same client task as the last click, before any server answer could
 * resync it). A row then reads GrimAC since its own mark; the controls (Stop To Click off) must flag, or the detector
 * proved nothing.
 *
 * <p>Latencies: {@code -PiwLatencies=0,50,150,300} (default all four), every movement row runs at each.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class InvWalkCampaignTests implements FabricClientGameTest {

    static final String IW_CFG = "invwalk.InventoryWalkConfig";
    static final String IW_SCREENS = "invwalk.InventoryWalkConfig$Screens";
    static final String IW = "invwalk.InventoryWalkFeature";
    static final int SURFACE_Y = 151;
    private static final Pattern CHECK = Pattern.compile("failed ([A-Za-z]+)");

    static final Set<String> FWD = Set.of("key.forward");
    static final Set<String> SPRINT = Set.of("key.forward", "key.sprint");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        AssertionError first = null;
        for (Consumer<ClientGameTestContext> c : List.<Consumer<ClientGameTestContext>>of(
                InvWalkCampaignTests::legitAbsent, InvWalkCampaignTests::controls, InvWalkCampaignTests::containers,
                InvWalkCampaignTests::clickKinds, InvWalkCampaignTests::timing, InvWalkCampaignTests::moves,
                InvWalkCampaignTests::terrain, InvWalkCampaignTests::interop, InvWalkCampaignTests::warp)) {
            try {
                c.accept(ctx);
            } catch (AssertionError e) {
                first = first == null ? e : first;
            }
        }
        if (first != null) {
            throw first;
        }
    }

    // =================================================================================================== helpers

    static int[] latencies() {
        String p = System.getProperty("testkit.iwLatencies", "0,50,150,300");
        List<Integer> out = new ArrayList<>();
        for (String s : p.split(",")) {
            if (!s.isBlank()) {
                out.add(Integer.parseInt(s.trim()));
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    static void arena(TestServer server, int x, int z) {
        TestMap.on(server)
                .platform(x, SURFACE_Y - 1, z, 40)
                .walls(x, SURFACE_Y, z, 40, 3)
                .catchFloor(120)
                .survival()
                .clearInventory()
                .spawn(x + 0.5, z + 0.5, -90f)
                .build();
    }

    static void check(boolean ok, String why) {
        if (!ok) {
            throw new AssertionError(why);
        }
    }

    static String checks(List<String> flags) {
        Set<String> names = new LinkedHashSet<>();
        for (String f : flags) {
            Matcher m = CHECK.matcher(f);
            if (m.find()) {
                names.add(m.group(1));
            } else {
                names.add(f.substring(Math.max(0, f.length() - 80)));
            }
        }
        return String.join(",", names);
    }

    /** One part of a multi-part case: flags since its own mark, then a fresh mark. */
    static final class Rows {
        final List<String> rows = new ArrayList<>();
        final List<String> bad = new ArrayList<>();
        final TestServer server;
        final Scenario scenario;
        final ClientGameTestContext ctx;

        Rows(ClientGameTestContext ctx, TestServer server, Scenario scenario) {
            this.ctx = ctx;
            this.server = server;
            this.scenario = scenario;
        }

        void begin() {
            server.mark();
        }

        /** {@code expect}: null = must be clean; otherwise the check name that MUST appear (a control). */
        void end(String label, boolean acted, String detail, String expect) {
            ctx.waitTicks(15);
            List<String> flags = scenario.flags();
            String names = checks(flags);
            boolean ok;
            String verdict;
            if (expect == null) {
                ok = acted && flags.isEmpty();
                verdict = flags.isEmpty() ? "clean" : flags.size() + " flag(s): " + names;
            } else {
                ok = acted && names.contains(expect);
                verdict = flags.isEmpty() ? "NO FLAG (control expected " + expect + ")" : flags.size() + " flag(s) "
                        + "(control, expected " + expect + "): " + names;
            }
            String row = String.format(Locale.ROOT, "%-34s %s %s; %s", label, acted ? "acted" : "DID NOT ACT", detail, verdict);
            rows.add(row);
            scenario.log("ROW " + row);
            for (String f : flags.subList(0, Math.min(4, flags.size()))) {
                scenario.log("    " + f.substring(Math.max(0, f.length() - 220)));
            }
            if (!ok) {
                bad.add(label + (acted ? "" : " (did not act)") + (expect == null && !flags.isEmpty() ? " (" + names + ")" : "")
                        + (expect != null && !names.contains(expect) ? " (control did not flag)" : ""));
            }
            server.mark();
        }

        void finish(String name) {
            scenario.log("SUMMARY " + name + ": " + rows.size() + " row(s), bad " + bad.size() + " " + bad);
            for (String r : rows) {
                scenario.log("  " + r);
            }
            check(!rows.isEmpty(), name + " ran no rows");
            check(bad.isEmpty(), name + ": " + bad);
        }
    }

    static boolean gateOff(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            boolean gate = (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled");
            Mod.staticCall("util.SkyblockGate", "setEnabled", false);
            return gate;
        });
    }

    static void iwOn(ClientGameTestContext ctx, boolean stopToClick, int pause) {
        ctx.runOnClient(mc -> {
            Mod.set(IW_CFG, "setEnabled", true);
            Mod.set(IW_CFG, "setScreens", Mod.enumValue(IW_SCREENS, "CONTAINERS"));
            Mod.set(IW_CFG, "setJump", true);
            Mod.set(IW_CFG, "setSprint", true);
            Mod.set(IW_CFG, "setPauseAfterClickTicks", pause);
            Mod.set(IW_CFG, "setStopToClick", stopToClick);
            Mod.setField(IW, "heldForTest", Set.of());
        });
    }

    static void keys(ClientGameTestContext ctx, Set<String> held) {
        ctx.runOnClient(mc -> Mod.setField(IW, "heldForTest", held));
    }

    static void tearDown(ClientGameTestContext ctx, boolean gate) {
        ctx.runOnClient(mc -> {
            Mod.setField(IW, "heldForTest", null);
            Mod.set(IW_CFG, "setEnabled", false);
            Mod.set(IW_CFG, "setStopToClick", true);
            Mod.set(IW_CFG, "setPauseAfterClickTicks", 0);
            if (McCompat.screen(mc) != null) {
                McCompat.screen(mc).onClose();
            }
            Latency.remove();
            Mod.staticCall("util.SkyblockGate", "setEnabled", gate);
        });
        ctx.getInput().releaseKey(o -> o.keyUp);
        ctx.getInput().releaseKey(o -> o.keySprint);
        ctx.getInput().releaseKey(o -> o.keyJump);
        ctx.getInput().releaseKey(o -> o.keyShift);
        ctx.waitTicks(10);
    }

    static void latency(ClientGameTestContext ctx, int ms) {
        ctx.runOnClient(mc -> {
            if (ms <= 0) {
                Latency.remove();
            } else {
                Latency.install(ms);
            }
        });
        ctx.waitTicks(10);
    }

    static JsonObject state(Hx hx) {
        return hx.call("player.state").getAsJsonObject();
    }

    static double[] spos(JsonObject s) {
        return new double[]{s.get("x").getAsDouble(), s.get("y").getAsDouble(), s.get("z").getAsDouble()};
    }

    static double[] cpos(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
    }

    static double flat(double[] a, double[] b) {
        return Math.hypot(a[0] - b[0], a[2] - b[2]);
    }

    static double dist3(double[] a, double[] b) {
        return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
    }

    static String stackText(ItemStack st) {
        return st == null || st.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(st.getItem()) + "*" + st.getCount();
    }

    /** The open menu's slots + carried, as the server's player.state writes them. */
    static List<String> clientSlots(Minecraft mc) {
        List<String> out = new ArrayList<>();
        AbstractContainerMenu m = mc.player.containerMenu;
        for (var s : m.slots) {
            out.add(stackText(s.getItem()));
        }
        out.add("carried:" + stackText(m.getCarried()));
        return out;
    }

    static List<String> serverSlots(JsonObject st) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : st.getAsJsonArray("slots")) {
            out.add(e.getAsString());
        }
        out.add("carried:" + st.get("carried").getAsString());
        return out;
    }

    /** Differences between two slot lists, ignoring {@code ignore} indexes (result slots the server fills). */
    static String diff(List<String> a, List<String> b, Set<Integer> ignore) {
        if (a.size() != b.size()) {
            return "size " + a.size() + " vs " + b.size();
        }
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < a.size(); i++) {
            if (!ignore.contains(i) && !a.get(i).equals(b.get(i))) {
                d.append(i).append(":").append(a.get(i)).append("/").append(b.get(i)).append(" ");
            }
        }
        return d.toString().trim();
    }

    /** One container click as the screen sends it. */
    record Click(int slot, int button, ContainerInput input) {
        static Click left(int s) {
            return new Click(s, 0, ContainerInput.PICKUP);
        }

        static Click right(int s) {
            return new Click(s, 1, ContainerInput.PICKUP);
        }

        static Click shift(int s) {
            return new Click(s, 0, ContainerInput.QUICK_MOVE);
        }

        static Click swap(int s, int hotbar) {
            return new Click(s, hotbar, ContainerInput.SWAP);
        }

        static Click drop(int s, boolean stack) {
            return new Click(s, stack ? 1 : 0, ContainerInput.THROW);
        }

        static Click collect(int s) {
            return new Click(s, 0, ContainerInput.PICKUP_ALL);
        }

        static Click drag(int s, int header) {
            return new Click(s, header, ContainerInput.QUICK_CRAFT);
        }

        String text() {
            return input + "(" + slot + "," + button + ")";
        }
    }

    /** Sends the clicks now on the client thread; returns the client's prediction right after them. */
    static List<String> click(ClientGameTestContext ctx, List<Click> clicks) {
        return ctx.computeOnClient(mc -> {
            AbstractContainerMenu m = mc.player.containerMenu;
            for (Click c : clicks) {
                mc.gameMode.handleContainerInput(m.containerId, c.slot(), c.button(), c.input(), mc.player);
            }
            return clientSlots(mc);
        });
    }

    /** What a walk did. */
    static final class Walk {
        double clientPath;
        double serverMax;
        double clientMax;
        boolean screenAll = true;
        boolean ivActive = true;
        int ticks;
        List<String> prediction;
        int clicksSent;

        String text() {
            return String.format(Locale.ROOT, "walked %d tick(s): client path %.2f (max %.2f from start), server max %.2f, screen "
                    + "open throughout %s, IW active %s", ticks, clientPath, clientMax, serverMax, screenAll, ivActive);
        }
    }

    /** A segment of a walk: keys held (by the feature's test hook) for some ticks. */
    record Seg(Set<String> keys, int ticks) {
    }

    /**
     * Walks the segments with the screen open, sending {@code clicks[i]} at tick {@code at[i]} (relative to the start).
     * The server's position is read through Hx every 3 ticks (no tick waited) for the largest distance from the start.
     */
    static Walk walk(ClientGameTestContext ctx, Hx hx, List<Seg> segs, int[] at, List<List<Click>> clicks) {
        Walk w = new Walk();
        double[] c0 = cpos(ctx);
        double[] s0 = spos(state(hx));
        double[] prev = c0;
        int t = 0;
        int ci = 0;
        for (Seg seg : segs) {
            keys(ctx, seg.keys());
            for (int i = 0; i < seg.ticks(); i++, t++) {
                while (ci < at.length && at[ci] == t) {
                    w.prediction = click(ctx, clicks.get(ci));
                    w.clicksSent += clicks.get(ci).size();
                    ci++;
                }
                ctx.waitTicks(1);
                double[] now = cpos(ctx);
                w.clientPath += flat(prev, now);
                w.clientMax = Math.max(w.clientMax, flat(c0, now));
                prev = now;
                boolean[] sa = ctx.computeOnClient(mc -> new boolean[]{McCompat.screen(mc) instanceof AbstractContainerScreen<?>,
                        (Boolean) Mod.staticCall(IW, "isActive")});
                w.screenAll &= sa[0];
                w.ivActive &= sa[1];
                if (t % 3 == 2) {
                    w.serverMax = Math.max(w.serverMax, flat(s0, spos(state(hx))));
                }
            }
        }
        while (ci < at.length) {
            w.prediction = click(ctx, clicks.get(ci));
            w.clicksSent += clicks.get(ci).size();
            ci++;
        }
        w.serverMax = Math.max(w.serverMax, flat(s0, spos(state(hx))));
        w.ticks = t;
        return w;
    }

    /** Waits (ticking, keys as they are) until the server has handled {@code n} clicks since {@code since}. */
    static List<JsonObject> awaitClicks(ClientGameTestContext ctx, Hx hx, long since, int n) {
        List<JsonObject> ev = List.of();
        for (int i = 0; i < 60; i++) {
            ev = hx.events(since, "container.click");
            if (ev.size() >= n) {
                break;
            }
            ctx.waitTicks(1);
        }
        return ev;
    }

    /** Clicks the server handled while it had him moving, sprinting or sneaking (what MultiActionsC flags). */
    static int movingClicks(List<JsonObject> ev) {
        int n = 0;
        for (JsonObject e : ev) {
            if (moving(e)) {
                n++;
            }
        }
        return n;
    }

    static boolean moving(JsonObject e) {
        String in = e.has("serverInput") ? e.get("serverInput").getAsString() : "";
        boolean keys = in.contains("forward") || in.contains("back") || in.contains("left") || in.contains("right")
                || in.contains("jump") || in.contains("sneak");
        return keys || (e.has("sprinting") && e.get("sprinting").getAsBoolean()) || (e.has("shift") && e.get("shift").getAsBoolean());
    }

    /** Closes the open screen the way Escape does (onClose: the close packet, then the screen goes). */
    static void closeScreen(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            Screen s = McCompat.screen(mc);
            if (s != null) {
                s.onClose();
            }
        });
    }

    static void openInventory(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> McCompat.setScreen(mc, new InventoryScreen(mc.player)));
        ctx.waitTicks(1);
    }

    /** Turns to (tx,ty,tz) by a relative yaw step (never across the seam as an absolute jump). */
    static void look(ClientGameTestContext ctx, double tx, double ty, double tz) {
        ctx.runOnClient(mc -> {
            double dx = tx - mc.player.getX();
            double dy = ty - mc.player.getEyeY();
            double dz = tz - mc.player.getZ();
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
            mc.player.setYRot(mc.player.getYRot() + Mth.wrapDegrees(yaw - mc.player.getYRot()));
            mc.player.setXRot(Mth.clamp(pitch, -90f, 90f));
        });
    }

    static void face(ClientGameTestContext ctx, float yaw) {
        ctx.runOnClient(mc -> {
            mc.player.setYRot(mc.player.getYRot() + Mth.wrapDegrees(yaw - mc.player.getYRot()));
            mc.player.setXRot(0f);
        });
    }

    /** Right-clicks the block at (x,y,z) (looking at its center first); waits for a container screen. */
    static boolean openBlock(ClientGameTestContext ctx, int x, int y, int z) {
        look(ctx, x + 0.5, y + 0.5, z + 0.5);
        ctx.waitTicks(2);
        ctx.getInput().pressKey(o -> o.keyUse);
        for (int i = 0; i < 40; i++) {
            ctx.waitTicks(1);
            if (ctx.computeOnClient(mc -> McCompat.screen(mc) instanceof AbstractContainerScreen<?>)) {
                return true;
            }
        }
        return false;
    }

    static void tp(TestServer server, ClientGameTestContext ctx, double x, double y, double z, float yaw) {
        server.command(String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f %.1f 0", name(ctx), x, y, z, yaw));
        ctx.waitTicks(20);
    }

    static String name(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> mc.player.getName().getString());
    }

    /** A walk that stays near where it started: forward+sprint, back, left, right, diagonals. */
    static List<Seg> shuttle(int each) {
        return List.of(new Seg(SPRINT, each), new Seg(Set.of("key.back"), each + 4),
                new Seg(Set.of("key.left"), each), new Seg(Set.of("key.right"), each),
                new Seg(Set.of("key.forward", "key.left", "key.sprint"), each), new Seg(Set.of("key.back", "key.right"), each + 4));
    }

    /** Clicks every {@code gap} ticks from tick {@code from}. */
    static int[] spread(int n, int from, int gap) {
        int[] at = new int[n];
        for (int i = 0; i < n; i++) {
            at[i] = from + i * gap;
        }
        return at;
    }

    static List<List<Click>> singles(List<Click> cs) {
        List<List<Click>> out = new ArrayList<>();
        for (Click c : cs) {
            out.add(List.of(c));
        }
        return out;
    }

    /**
     * The common row body: with the screen already open, walk {@code segs} sending {@code clicks}, wait for the server to
     * handle them all, compare the server's menu to the prediction, close the screen WHILE STILL WALKING (keys held),
     * and record the row.
     */
    static void walkRow(Rows rows, ClientGameTestContext ctx, Hx hx, String label, List<Seg> segs, int[] at,
                        List<List<Click>> clicks, Set<Integer> ignore, double minMove, String expect, boolean itemsMove) {
        long since = hx.head();
        List<String> before = ctx.computeOnClient(InvWalkCampaignTests::clientSlots);
        Walk w = walk(ctx, hx, segs, at, clicks);
        List<JsonObject> ev = awaitClicks(ctx, hx, since, w.clicksSent);
        JsonObject st = state(hx);
        List<String> server = serverSlots(st);
        String d = w.prediction == null ? "no prediction" : diff(w.prediction, server, ignore);
        boolean changed = !diff(before, server, ignore).isEmpty();
        // Close while still walking: the last segment's keys are still held.
        long closeSince = hx.head();
        closeScreen(ctx);
        ctx.waitTicks(3);
        keys(ctx, Set.of());
        List<JsonObject> closes = List.of();
        for (int i = 0; i < 40; i++) {
            closes = hx.events(closeSince, "container.close");
            if (!closes.isEmpty()) {
                break;
            }
            ctx.waitTicks(1);
        }
        boolean movingClose = closes.stream().anyMatch(InvWalkCampaignTests::moving);
        int mc = movingClicks(ev);
        boolean acted = w.screenAll && w.clientPath >= minMove && w.serverMax >= minMove * 0.5 && ev.size() == w.clicksSent
                && (!itemsMove || (d.isEmpty() && changed)) && !closes.isEmpty();
        String detail = String.format(Locale.ROOT, "%s; clicks sent %d, server handled %d (%d while it had him moving), items %s%s; "
                        + "close reached server %s%s", w.text(), w.clicksSent, ev.size(), mc,
                itemsMove ? (d.isEmpty() ? (changed ? "as predicted" : "UNCHANGED") : "DIFFER [" + d + "]") : "n/a",
                itemsMove ? "" : "", !closes.isEmpty(), movingClose ? " (while moving)" : "");
        // A click or close handled while the server had him moving is exactly MultiActionsC/D: count it as a fault even if
        // Grim's own view (packet order) happened to differ, unless this is a control.
        if (expect == null && (mc > 0 || movingClose)) {
            acted = false;
            detail += " [MOVING CLICK/CLOSE]";
        }
        rows.end(label, acted, detail, expect);
    }

    // =================================================================================================== cases

    /** 1027-iw-legit-absent: on a legit jar Inventory Walk does nothing (SKIP on cheat). */
    private static void legitAbsent(ClientGameTestContext ctx) {
        String nm = "1027-iw-legit-absent";
        if (Mod.isCheat()) {
            Scenario.skipped(nm, "cheat jar (this case is for the legit jar)");
            return;
        }
        Scenario.run(ctx, nm, (server, scenario) -> arena(server, -9000, -9000), (server, scenario) -> {
            boolean gate = gateOff(ctx);
            try (Hx hx = Hx.connect()) {
                boolean has = Mod.has(IW);
                if (has) {
                    iwOn(ctx, true, 0);
                    openInventory(ctx);
                    keys(ctx, SPRINT);
                    double[] a = cpos(ctx);
                    ctx.waitTicks(40);
                    double[] b = cpos(ctx);
                    boolean active = ctx.computeOnClient(mc -> (Boolean) Mod.staticCall(IW, "isActive"));
                    scenario.log(String.format(Locale.ROOT, "legit: class present, forced on: active %s, moved %.2f", active, flat(a, b)));
                    check(!active && flat(a, b) < 0.5, "Inventory Walk acted on a legit jar");
                } else {
                    scenario.log("legit: no " + IW + " class in the jar");
                }
            } finally {
                tearDown(ctx, gate);
            }
        });
    }

    /**
     * 1027-iw-controls: (a) feature OFF: in the inventory he does not move; (b) Stop To Click OFF: clicks made while
     * walking MUST draw MultiActionsC (the positive control - the detector sees what Stop To Click prevents); (c) a chat
     * screen is never walked in; (d) Pause After Click 3 still clean.
     */
    private static void controls(ClientGameTestContext ctx) {
        if (!Mod.isCheat()) {
            Scenario.skipped("1027-iw-controls", "legit jar");
            return;
        }
        int x = -9100;
        int z = -9000;
        Scenario.runExpectingFlags(ctx, "1027-iw-controls", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = gateOff(ctx);
            Rows rows = new Rows(ctx, server, scenario);
            try (Hx hx = Hx.connect()) {
                give(server, ctx);
                // (a) off
                rows.begin();
                ctx.runOnClient(mc -> Mod.setField(IW, "heldForTest", SPRINT));
                openInventory(ctx);
                double[] a = cpos(ctx);
                ctx.waitTicks(30);
                double[] b = cpos(ctx);
                closeScreen(ctx);
                ctx.waitTicks(5);
                rows.end("feature off: no walk", flat(a, b) < 0.5, String.format(Locale.ROOT, "moved %.2f", flat(a, b)), null);
                // (b) raw clicks
                iwOn(ctx, false, 0);
                tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
                rows.begin();
                openInventory(ctx);
                long since = hx.head();
                Walk w = walk(ctx, hx, List.of(new Seg(SPRINT, 30)), spread(3, 8, 6),
                        singles(List.of(Click.left(9), Click.left(11), Click.left(9))));
                List<JsonObject> ev = awaitClicks(ctx, hx, since, 3);
                keys(ctx, Set.of());
                ctx.waitTicks(5);
                closeScreen(ctx);
                // GrimAC cancels a click it flags (MultiActionsC modifies packets), so the server may handle none of them.
                rows.end("Stop To Click OFF (control)", w.clientPath > 4, w.text() + ", server handled " + ev.size() + " of 3 "
                        + "click(s) (" + movingClicks(ev) + " while moving; GrimAC drops the ones it flags)", "MultiActionsC");
                // (c) chat screen
                iwOn(ctx, true, 0);
                tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
                rows.begin();
                ctx.runOnClient(mc -> McCompat.setScreen(mc, new ChatScreen("", false)));
                ctx.waitTicks(2);
                a = cpos(ctx);
                ctx.waitTicks(30);
                b = cpos(ctx);
                boolean act = ctx.computeOnClient(mc -> (Boolean) Mod.staticCall(IW, "isActive"));
                ctx.runOnClient(mc -> McCompat.setScreen(mc, null));
                keys(ctx, Set.of());
                rows.end("chat screen: no walk", !act && flat(a, b) < 0.5, String.format(Locale.ROOT, "active %s, moved %.2f", act,
                        flat(a, b)), null);
                // (d) pause after click 3
                iwOn(ctx, true, 3);
                tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
                give(server, ctx);
                rows.begin();
                openInventory(ctx);
                walkRow(rows, ctx, hx, "Pause After Click 3", List.of(new Seg(SPRINT, 40)), spread(4, 6, 8),
                        singles(List.of(Click.left(9), Click.left(11), Click.right(10), Click.left(12))), Set.of(0), 4, null, true);
            } finally {
                tearDown(ctx, gate);
            }
            rows.finish("1027-iw-controls");
        });
    }

    /** Fills his inventory for the click scripts (InventoryMenu indexes: 9 stone*32, 10 dirt*16, 13/14 stone*5, 36 log*8). */
    static void give(TestServer server, ClientGameTestContext ctx) {
        String n = name(ctx);
        server.command("clear " + n);
        server.command("item replace entity " + n + " inventory.0 with minecraft:stone 32");
        server.command("item replace entity " + n + " inventory.1 with minecraft:dirt 16");
        server.command("item replace entity " + n + " inventory.4 with minecraft:stone 5");
        server.command("item replace entity " + n + " inventory.5 with minecraft:stone 5");
        server.command("item replace entity " + n + " hotbar.0 with minecraft:oak_log 8");
        server.sync(10);
        ctx.waitTicks(10);
    }

    // -------------------------------------------------------------------------------------------- containers

    /** A container under test: how to build and open it, its click script and the slots the server fills on its own. */
    record Box(String name, String setup, int[] pos, List<Click> script, Set<Integer> ignore, boolean itemsMove) {
    }

    /**
     * 1027-iw-containers: player inventory, chest, double chest, ender chest, crafting table, furnace, anvil (whose
     * rename box has focus: Inventory Walk stays off there by design - the row checks it is OFF and clean), a Skyblock-style
     * server menu and a terminal (PANES), each walked with clicks at every latency.
     */
    private static void containers(ClientGameTestContext ctx) {
        if (!Mod.isCheat()) {
            return;
        }
        int x = -9200;
        int z = -9000;
        Scenario.runExpectingFlags(ctx, "1027-iw-containers", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = gateOff(ctx);
            Rows rows = new Rows(ctx, server, scenario);
            try (Hx hx = Hx.connect()) {
                iwOn(ctx, true, 0);
                int by = SURFACE_Y;
                int bx = x + 2;
                // Warm the server's first block use (testkit lesson: GrimAC times the first place/use against a cold path).
                server.command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:chest", bx, by, z));
                server.sync(10);
                ctx.waitTicks(10);
                if (openBlock(ctx, bx, by, z)) {
                    ctx.waitTicks(5);
                    closeScreen(ctx);
                }
                ctx.waitTicks(20);
                server.mark();
                for (int lat : latencies()) {
                    latency(ctx, lat);
                    // Player inventory: a full shuttle with the generic script on InventoryMenu.
                    containerRow(rows, ctx, server, hx, x, z, lat, "inventory", null, List.of(Click.left(9), Click.left(19),
                            Click.right(10), Click.left(20), Click.shift(19), Click.swap(20, 2)), Set.of(0), true);
                    int[] p = {bx, by, z};
                    containerRow(rows, ctx, server, hx, x, z, lat, "chest", new Box("chest",
                            "setblock %d %d %d minecraft:chest[facing=west]", p, null, Set.of(), true), null, Set.of(), true);
                    containerRow(rows, ctx, server, hx, x, z, lat, "double chest", new Box("double",
                            "double", p, null, Set.of(), true), null, Set.of(), true);
                    containerRow(rows, ctx, server, hx, x, z, lat, "ender chest", new Box("ender",
                            "setblock %d %d %d minecraft:ender_chest[facing=west]", p, null, Set.of(), true), null, Set.of(), true);
                    containerRow(rows, ctx, server, hx, x, z, lat, "crafting table", new Box("craft",
                            "setblock %d %d %d minecraft:crafting_table", p, null, Set.of(0), true), null, Set.of(0), true);
                    containerRow(rows, ctx, server, hx, x, z, lat, "furnace", new Box("furnace",
                            "setblock %d %d %d minecraft:furnace[facing=west]", p, null, Set.of(2), true), null, Set.of(2), true);
                    anvilRow(rows, ctx, server, hx, x, z, lat, p);
                    menuRow(rows, ctx, server, hx, x, z, lat);
                    terminalRow(rows, ctx, server, hx, x, z, lat);
                }
            } finally {
                tearDown(ctx, gate);
            }
            rows.finish("1027-iw-containers");
        });
    }

    static void containerRow(Rows rows, ClientGameTestContext ctx, TestServer server, Hx hx, int x, int z, int lat, String what,
                             Box box, List<Click> invScript, Set<Integer> ignore, boolean itemsMove) {
        tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
        give(server, ctx);
        String n = name(ctx);
        if (box != null) {
            int[] p = box.pos();
            server.command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", p[0], p[1], p[2] - 1, p[0], p[1], p[2] + 1));
            if (box.setup().equals("double")) {
                server.command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:chest[facing=west,type=left]", p[0], p[1], p[2]));
                server.command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:chest[facing=west,type=right]", p[0], p[1], p[2] - 1));
            } else {
                server.command(String.format(Locale.ROOT, box.setup(), p[0], p[1], p[2]));
            }
            if (box.name().equals("ender")) {
                server.command("item replace entity " + n + " enderchest.0 with minecraft:stone 32");
                server.command("item replace entity " + n + " enderchest.1 with minecraft:dirt 16");
            } else if (box.name().equals("chest") || box.name().equals("double")) {
                server.command(String.format(Locale.ROOT, "item replace block %d %d %d container.0 with minecraft:stone 32", p[0], p[1], p[2]));
                server.command(String.format(Locale.ROOT, "item replace block %d %d %d container.1 with minecraft:dirt 16", p[0], p[1], p[2]));
            }
            server.sync(10);
            ctx.waitTicks(10);
        }
        rows.begin();
        String label = "L" + lat + " " + what;
        boolean opened;
        if (box == null) {
            openInventory(ctx);
            opened = true;
        } else {
            opened = openBlock(ctx, box.pos()[0], box.pos()[1], box.pos()[2]);
        }
        if (!opened) {
            rows.end(label, false, "the container never opened", null);
            return;
        }
        ctx.waitTicks(3);
        // Slot layout from the open menu: n container slots, then 27 main and 9 hotbar.
        int nSlots = ctx.computeOnClient(mc -> mc.player.containerMenu.slots.size());
        int c = nSlots - 36;
        List<Click> script;
        if (invScript != null) {
            script = invScript;
        } else if (box.name().equals("craft")) {
            // grid 1-2 from main inventory (dirt at main 1 = c+1).
            script = List.of(Click.left(c + 1), Click.left(1), Click.right(1), Click.left(2), Click.shift(1), Click.shift(2));
        } else if (box.name().equals("furnace")) {
            script = List.of(Click.left(c + 1), Click.left(0), Click.right(0), Click.left(c + 10), Click.shift(0), Click.swap(c + 10, 3));
        } else {
            // chest-like: slot 0 stone*32, 1 dirt*16 in the container.
            script = List.of(Click.left(0), Click.left(c + 10), Click.right(1), Click.left(c + 11), Click.shift(c + 10),
                    Click.swap(1, 0));
        }
        walkRow(rows, ctx, hx, label, shuttle(7), spread(script.size(), 4, 6), singles(script), ignore, 4, null, itemsMove);
        ctx.waitTicks(5);
    }

    /** Anvil: its rename box has focus when it opens, so Inventory Walk must stay OFF (no walk) - and nothing flags. */
    static void anvilRow(Rows rows, ClientGameTestContext ctx, TestServer server, Hx hx, int x, int z, int lat, int[] p) {
        tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
        give(server, ctx);
        server.command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", p[0], p[1], p[2] - 1, p[0], p[1], p[2] + 1));
        server.command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:anvil[facing=north]", p[0], p[1], p[2]));
        server.sync(10);
        ctx.waitTicks(10);
        rows.begin();
        String label = "L" + lat + " anvil";
        if (!openBlock(ctx, p[0], p[1], p[2])) {
            rows.end(label, false, "the anvil never opened", null);
            return;
        }
        ctx.waitTicks(3);
        long since = hx.head();
        keys(ctx, SPRINT);
        double[] a = cpos(ctx);
        int c = ctx.computeOnClient(mc -> mc.player.containerMenu.slots.size()) - 36;
        List<String> pred = null;
        for (int i = 0; i < 4; i++) {
            ctx.waitTicks(5);
            pred = click(ctx, List.of(i % 2 == 0 ? Click.left(c + 1) : Click.left(0)));
        }
        ctx.waitTicks(5);
        double[] b = cpos(ctx);
        boolean active = ctx.computeOnClient(mc -> (Boolean) Mod.staticCall(IW, "isActive"));
        List<JsonObject> ev = awaitClicks(ctx, hx, since, 4);
        String d = diff(pred, serverSlots(state(hx)), Set.of(2));
        closeScreen(ctx);
        keys(ctx, Set.of());
        ctx.waitTicks(5);
        rows.end(label, !active && flat(a, b) < 0.5 && ev.size() == 4 && d.isEmpty(), String.format(Locale.ROOT,
                "IW active %s (rename box focused: off by design), moved %.2f, server handled %d click(s), items %s", active,
                flat(a, b), ev.size(), d.isEmpty() ? "as predicted" : "DIFFER [" + d + "]"), null);
    }

    /** A Skyblock-style server menu (clicks never move items; the server records each one). */
    static void menuRow(Rows rows, ClientGameTestContext ctx, TestServer server, Hx hx, int x, int z, int lat) {
        tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
        rows.begin();
        String label = "L" + lat + " skyblock menu";
        long since0 = hx.head();
        hx.menu("Ender Chest (1/9)", 6, Map.of(10, "minecraft:diamond", 12, "minecraft:emerald", 14, "minecraft:gold_ingot",
                16, "minecraft:iron_ingot", 49, "minecraft:barrier"), null);
        boolean open = false;
        for (int i = 0; i < 40 && !open; i++) {
            ctx.waitTicks(1);
            open = ctx.computeOnClient(mc -> McCompat.screen(mc) instanceof AbstractContainerScreen<?>);
        }
        if (!open) {
            rows.end(label, false, "the menu never opened", null);
            return;
        }
        List<Click> script = List.of(Click.left(10), Click.right(12), Click.shift(14), Click.left(16), Click.swap(10, 1),
                Click.left(49));
        walkRow(rows, ctx, hx, label, shuttle(7), spread(script.size(), 4, 6), singles(script), Set.of(), 4, null, false);
        List<JsonObject> mc = hx.events(since0, "menu.click");
        rows.scenario.log("  menu.click events " + mc.size());
    }

    /** A terminal (Correct all the panes!): click every red pane while walking; the server must count it solved. */
    static void terminalRow(Rows rows, ClientGameTestContext ctx, TestServer server, Hx hx, int x, int z, int lat) {
        tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
        rows.begin();
        String label = "L" + lat + " terminal panes";
        hx.call("menu.terminal", "type", "PANES", "seed", 7 + lat, "count", 5, "closeOnSolve", false, "interval", 0);
        boolean open = false;
        for (int i = 0; i < 40 && !open; i++) {
            ctx.waitTicks(1);
            open = ctx.computeOnClient(mc -> McCompat.screen(mc) instanceof AbstractContainerScreen<?>);
        }
        if (!open) {
            rows.end(label, false, "the terminal never opened", null);
            return;
        }
        ctx.waitTicks(3);
        List<Integer> red = ctx.computeOnClient(mc -> {
            List<Integer> out = new ArrayList<>();
            var m = mc.player.containerMenu;
            for (int i = 0; i < m.slots.size() - 36; i++) {
                if (stackText(m.slots.get(i).getItem()).startsWith("minecraft:red_stained_glass_pane")) {
                    out.add(i);
                }
            }
            return out;
        });
        List<Click> script = new ArrayList<>();
        for (int s : red) {
            script.add(Click.left(s));
        }
        walkRow(rows, ctx, hx, label, shuttle(7), spread(script.size(), 4, 6), singles(script), Set.of(), 4, null, false);
        JsonObject t = hx.call("menu.terminal.state").getAsJsonObject();
        boolean solved = t.has("solved") && t.get("solved").getAsBoolean();
        rows.scenario.log(String.format(Locale.ROOT, "  terminal: %d red pane(s) clicked, solved %s, clicks %s, wrong %s", red.size(),
                solved, t.get("clicks"), t.get("wrong")));
        if (!solved || red.isEmpty()) {
            rows.bad.add(label + " (terminal not solved)");
        }
    }

    // -------------------------------------------------------------------------------------------- click kinds

    /** The full script on the player inventory, every kind of click (see the class comment for the starting layout). */
    static List<Click> fullScript() {
        return List.of(
                Click.left(9), Click.left(11),                 // left pick up and put down
                Click.right(10), Click.left(12),               // right: half
                Click.shift(11),                               // shift-click to the hotbar
                Click.swap(12, 2),                             // number key 3
                Click.drop(10, false), Click.drop(38, true),   // Q and ctrl+Q
                Click.left(13), Click.collect(13), Click.left(15), // double-click collect
                Click.left(10), Click.drag(-999, 0), Click.drag(20, 1), Click.drag(21, 1), Click.drag(22, 1),
                Click.drag(-999, 2), Click.left(23),           // left drag split
                Click.swap(15, 40),                            // offhand swap (F)
                Click.left(20), Click.drag(-999, 4), Click.drag(24, 5), Click.drag(25, 5), Click.drag(-999, 6),
                Click.left(20));                               // right drag (one each)
    }

    /**
     * 1027-iw-clicks: every kind of click (left, right, shift, number key, Q, ctrl+Q, double-click collect, left drag, right
     * drag, offhand) while sprinting a shuttle, spread one every 2 ticks, at every latency; then the same script all in
     * ONE tick (spam) and in bursts of three.
     */
    private static void clickKinds(ClientGameTestContext ctx) {
        if (!Mod.isCheat()) {
            return;
        }
        int x = -9300;
        int z = -9000;
        Scenario.runExpectingFlags(ctx, "1027-iw-clicks", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = gateOff(ctx);
            Rows rows = new Rows(ctx, server, scenario);
            try (Hx hx = Hx.connect()) {
                iwOn(ctx, true, 0);
                List<Click> s = fullScript();
                for (int lat : latencies()) {
                    latency(ctx, lat);
                    for (String mode : new String[]{"spread", "one tick", "bursts"}) {
                        tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
                        give(server, ctx);
                        rows.begin();
                        openInventory(ctx);
                        int[] at;
                        List<List<Click>> cl;
                        if (mode.equals("spread")) {
                            at = spread(s.size(), 3, 2);
                            cl = singles(s);
                        } else if (mode.equals("one tick")) {
                            at = new int[]{10};
                            cl = List.of(s);
                        } else {
                            cl = new ArrayList<>();
                            for (int i = 0; i < s.size(); i += 3) {
                                cl.add(s.subList(i, Math.min(s.size(), i + 3)));
                            }
                            at = spread(cl.size(), 3, 5);
                        }
                        walkRow(rows, ctx, hx, "L" + lat + " all kinds, " + mode, shuttle(9), at, cl, Set.of(0), 4, null, true);
                        ctx.waitTicks(5);
                    }
                }
            } finally {
                tearDown(ctx, gate);
            }
            rows.finish("1027-iw-clicks");
        });
    }

    // -------------------------------------------------------------------------------------------- timing

    /**
     * 1027-iw-timing: a click the tick the screen opens; clicks then close in the same tick; close while sprinting with no
     * click; close and reopen at once and click; ten open/close cycles while walking with a click each.
     */
    private static void timing(ClientGameTestContext ctx) {
        if (!Mod.isCheat()) {
            return;
        }
        int x = -9400;
        int z = -9000;
        Scenario.runExpectingFlags(ctx, "1027-iw-timing", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = gateOff(ctx);
            Rows rows = new Rows(ctx, server, scenario);
            try (Hx hx = Hx.connect()) {
                iwOn(ctx, true, 0);
                for (int lat : latencies()) {
                    latency(ctx, lat);
                    // (1) click on the open tick: sprinting by real keys, open and click in one client task.
                    tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
                    give(server, ctx);
                    rows.begin();
                    ctx.getInput().holdKey(o -> o.keyUp);
                    ctx.getInput().holdKey(o -> o.keySprint);
                    ctx.waitTicks(8);
                    keys(ctx, SPRINT);
                    long since = hx.head();
                    List<String> pred = ctx.computeOnClient(mc -> {
                        McCompat.setScreen(mc, new InventoryScreen(mc.player));
                        var m = mc.player.containerMenu;
                        mc.gameMode.handleContainerInput(m.containerId, 9, 0, ContainerInput.PICKUP, mc.player);
                        mc.gameMode.handleContainerInput(m.containerId, 19, 0, ContainerInput.PICKUP, mc.player);
                        return clientSlots(mc);
                    });
                    ctx.getInput().releaseKey(o -> o.keyUp);
                    ctx.getInput().releaseKey(o -> o.keySprint);
                    walkRow(rows, ctx, hx, "L" + lat + " click on the open tick", shuttle(5), new int[]{2},
                            singles(List.of(Click.right(10))), Set.of(0), 3, null, true);
                    List<JsonObject> ev = hx.events(since, "container.click");
                    rows.scenario.log("  open-tick: " + ev.size() + " click(s) on the server, " + movingClicks(ev) + " while moving");
                    if (movingClicks(ev) > 0) {
                        rows.bad.add("L" + lat + " open-tick click handled while moving");
                    }

                    // (2) clicks then close in the same tick, while sprinting.
                    tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
                    give(server, ctx);
                    rows.begin();
                    openInventory(ctx);
                    keys(ctx, SPRINT);
                    ctx.waitTicks(10);
                    since = hx.head();
                    double[] a = cpos(ctx);
                    pred = ctx.computeOnClient(mc -> {
                        var m = mc.player.containerMenu;
                        mc.gameMode.handleContainerInput(m.containerId, 9, 0, ContainerInput.PICKUP, mc.player);
                        mc.gameMode.handleContainerInput(m.containerId, 19, 0, ContainerInput.PICKUP, mc.player);
                        List<String> p = clientSlots(mc);
                        McCompat.screen(mc).onClose();
                        return p;
                    });
                    ctx.waitTicks(10);
                    keys(ctx, Set.of());
                    ev = awaitClicks(ctx, hx, since, 2);
                    List<JsonObject> closes = hx.events(since, "container.close");
                    String inv = invDiff(pred, state(hx));
                    boolean ok = ev.size() == 2 && closes.size() == 1 && inv.isEmpty() && flat(a, cpos(ctx)) > 1;
                    rows.end("L" + lat + " clicks + close same tick", ok && movingClicks(ev) == 0
                                    && closes.stream().noneMatch(InvWalkCampaignTests::moving),
                            String.format(Locale.ROOT, "server handled %d click(s) (%d moving), %d close(s) (%s), inventory %s",
                                    ev.size(), movingClicks(ev), closes.size(), closes.stream().anyMatch(InvWalkCampaignTests::moving)
                                            ? "MOVING" : "still", inv.isEmpty() ? "as predicted" : "DIFFER [" + inv + "]"), null);

                    // (3) close while sprinting, no click.
                    tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
                    rows.begin();
                    openInventory(ctx);
                    keys(ctx, SPRINT);
                    ctx.waitTicks(15);
                    since = hx.head();
                    closeScreen(ctx);
                    ctx.waitTicks(10);
                    keys(ctx, Set.of());
                    closes = hx.events(since, "container.close");
                    rows.end("L" + lat + " close while sprinting", closes.size() == 1 && closes.stream().noneMatch(InvWalkCampaignTests::moving),
                            closes.size() + " close(s) on the server" + (closes.stream().anyMatch(InvWalkCampaignTests::moving)
                                    ? " handled while MOVING" : ", handled still"), null);

                    // (4) close and reopen at once, click, walking throughout; ten cycles.
                    tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
                    give(server, ctx);
                    rows.begin();
                    openInventory(ctx);
                    keys(ctx, SPRINT);
                    since = hx.head();
                    a = cpos(ctx);
                    int cycles = 10;
                    for (int i = 0; i < cycles; i++) {
                        ctx.waitTicks(3);
                        int slot = i % 2 == 0 ? 9 : 19;
                        int other = i % 2 == 0 ? 19 : 9;
                        pred = ctx.computeOnClient(mc -> {
                            var m = mc.player.containerMenu;
                            mc.gameMode.handleContainerInput(m.containerId, slot, 0, ContainerInput.PICKUP, mc.player);
                            mc.gameMode.handleContainerInput(m.containerId, other, 0, ContainerInput.PICKUP, mc.player);
                            List<String> p = clientSlots(mc);
                            McCompat.screen(mc).onClose();
                            McCompat.setScreen(mc, new InventoryScreen(mc.player));
                            return p;
                        });
                        // vanilla releases the keys on open; the feature puts them back next tick.
                    }
                    ctx.waitTicks(10);
                    ev = awaitClicks(ctx, hx, since, 2 * cycles);
                    closes = hx.events(since, "container.close");
                    double moved = flat(a, cpos(ctx));
                    closeScreen(ctx);
                    keys(ctx, Set.of());
                    ctx.waitTicks(10);
                    inv = invDiff(pred, state(hx));
                    rows.end("L" + lat + " reopen x10 with clicks", ev.size() == 2 * cycles && closes.size() >= cycles && moved > 2
                                    && inv.isEmpty() && movingClicks(ev) == 0 && closes.stream().noneMatch(InvWalkCampaignTests::moving),
                            String.format(Locale.ROOT, "moved %.2f, %d click(s) (%d moving), %d close(s) (%d moving), inventory %s",
                                    moved, ev.size(), movingClicks(ev), closes.size(),
                                    closes.stream().filter(InvWalkCampaignTests::moving).count(), inv.isEmpty() ? "as predicted"
                                            : "DIFFER [" + inv + "]"), null);
                }
            } finally {
                tearDown(ctx, gate);
            }
            rows.finish("1027-iw-timing");
        });
    }

    /** Compares an InventoryMenu prediction (slot 9-44 main+hotbar, 45 offhand) to the server's 41 inventory slots. */
    static String invDiff(List<String> pred, JsonObject st) {
        JsonArray inv = st.getAsJsonArray("inventory");
        StringBuilder d = new StringBuilder();
        // InventoryMenu 9..35 = inventory 9..35, 36..44 = inventory 0..8, 45 = inventory 40.
        for (int m = 9; m <= 45; m++) {
            int i = m <= 35 ? m : m <= 44 ? m - 36 : 40;
            String p = pred.get(m);
            String s = inv.get(i).getAsString();
            if (!p.equals(s)) {
                d.append(m).append(":").append(p).append("/").append(s).append(" ");
            }
        }
        return d.toString().trim();
    }

    // -------------------------------------------------------------------------------------------- moves

    /** 1027-iw-moves: walk, sprint, sneak held, jump, strafe, diagonal, backward, sprint-jump; clicks in each; all latencies. */
    private static void moves(ClientGameTestContext ctx) {
        if (!Mod.isCheat()) {
            return;
        }
        int x = -9500;
        int z = -9000;
        Scenario.runExpectingFlags(ctx, "1027-iw-moves", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = gateOff(ctx);
            Rows rows = new Rows(ctx, server, scenario);
            List<Object[]> styles = List.of(
                    new Object[]{"walk", List.of(new Seg(FWD, 20), new Seg(Set.of("key.back"), 20))},
                    new Object[]{"sprint", List.of(new Seg(SPRINT, 20), new Seg(Set.of("key.back"), 24))},
                    new Object[]{"sneak key held", List.of(new Seg(FWD, 20), new Seg(Set.of("key.back"), 20))},
                    new Object[]{"jump", List.of(new Seg(Set.of("key.forward", "key.jump"), 20), new Seg(Set.of("key.back", "key.jump"), 20))},
                    new Object[]{"sprint-jump", List.of(new Seg(Set.of("key.forward", "key.jump", "key.sprint"), 20),
                            new Seg(Set.of("key.back", "key.jump"), 26))},
                    new Object[]{"strafe", List.of(new Seg(Set.of("key.left"), 20), new Seg(Set.of("key.right"), 20))},
                    new Object[]{"diagonal", List.of(new Seg(Set.of("key.forward", "key.left", "key.sprint"), 20),
                            new Seg(Set.of("key.back", "key.right"), 24))},
                    new Object[]{"backward", List.of(new Seg(Set.of("key.back"), 20), new Seg(FWD, 20))});
            try (Hx hx = Hx.connect()) {
                iwOn(ctx, true, 0);
                for (int lat : latencies()) {
                    latency(ctx, lat);
                    for (Object[] st : styles) {
                        String style = (String) st[0];
                        @SuppressWarnings("unchecked")
                        List<Seg> segs = (List<Seg>) st[1];
                        tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
                        give(server, ctx);
                        rows.begin();
                        if (style.startsWith("sneak")) {
                            ctx.getInput().holdKey(o -> o.keyShift);
                            ctx.waitTicks(5);
                        }
                        openInventory(ctx);
                        if (style.startsWith("sneak")) {
                            // He keeps shift down in the screen (shift-clicks): the screen gets it, nobody sneaks.
                            ctx.getInput().holdKey(o -> o.keyShift);
                        }
                        List<Click> script = List.of(Click.left(9), Click.left(19), Click.shift(19), Click.right(10), Click.left(20));
                        walkRow(rows, ctx, hx, "L" + lat + " " + style, segs, spread(script.size(), 4, 7), singles(script),
                                Set.of(0), 2, null, true);
                        ctx.getInput().releaseKey(o -> o.keyShift);
                        ctx.waitTicks(5);
                    }
                }
            } finally {
                tearDown(ctx, gate);
            }
            rows.finish("1027-iw-moves");
        });
    }

    // -------------------------------------------------------------------------------------------- terrain

    record Lane(String name, String block, int y1, int y2, Set<String> keys, double minMove) {
    }

    /** 1027-iw-terrain: ice, blue ice, soul sand, slabs, stairs, ladder, water, off an edge (in the air); all latencies. */
    private static void terrain(ClientGameTestContext ctx) {
        if (!Mod.isCheat()) {
            return;
        }
        int x = -9600;
        int z0 = -9000;
        Scenario.runExpectingFlags(ctx, "1027-iw-terrain", (server, scenario) -> arena(server, x, z0), (server, scenario) -> {
            int fy = SURFACE_Y - 1;
            List<Lane> lanes = List.of(
                    new Lane("ice", "minecraft:ice", fy, fy, SPRINT, 3),
                    new Lane("blue ice", "minecraft:blue_ice", fy, fy, SPRINT, 3),
                    new Lane("soul sand", "minecraft:soul_sand", fy, fy, SPRINT, 1),
                    new Lane("slabs", "slabs", 0, 0, Set.of("key.forward", "key.jump", "key.sprint"), 2),
                    new Lane("stairs", "stairs", 0, 0, SPRINT, 2),
                    new Lane("ladder", "ladder", 0, 0, FWD, 0.3),
                    new Lane("water", "minecraft:water", fy - 2, fy, Set.of("key.forward", "key.jump", "key.sprint"), 1),
                    new Lane("off an edge (air)", "edge", 0, 0, SPRINT, 2));
            // Built with him standing here (vanilla fill fails on chunks nobody loaded).
            for (int i = 0; i < lanes.size(); i++) {
                buildLane(server, lanes.get(i), x, z0 - 30 + 6 * i);
            }
            server.sync(10);
            ctx.waitTicks(20);
            boolean gate = gateOff(ctx);
            Rows rows = new Rows(ctx, server, scenario);
            try (Hx hx = Hx.connect()) {
                iwOn(ctx, true, 0);
                for (int lat : latencies()) {
                    latency(ctx, lat);
                    for (int i = 0; i < lanes.size(); i++) {
                        Lane lane = lanes.get(i);
                        int lz = z0 - 30 + 6 * i;
                        tp(server, ctx, x - 14.5, SURFACE_Y + (lane.name().equals("ladder") ? 0 : 0), lz + 0.5, -90f);
                        give(server, ctx);
                        rows.begin();
                        openInventory(ctx);
                        List<Click> script = List.of(Click.left(9), Click.left(19), Click.right(10), Click.left(20), Click.shift(19));
                        walkRow(rows, ctx, hx, "L" + lat + " " + lane.name(), List.of(new Seg(lane.keys(), 26)),
                                spread(script.size(), 3, 5), singles(script), Set.of(0), lane.minMove(), null, true);
                        ctx.waitTicks(10);
                    }
                }
            } finally {
                tearDown(ctx, gate);
            }
            rows.finish("1027-iw-terrain");
        });
    }

    static void buildLane(TestServer s, Lane lane, int x, int lz) {
        switch (lane.block()) {
            case "slabs" -> {
                for (int i = -12; i <= 15; i += 2) {
                    s.command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone_slab", x + i, SURFACE_Y, lz - 1, x + i,
                            SURFACE_Y, lz + 1));
                }
            }
            case "stairs" -> {
                for (int i = 0; i < 6; i++) {
                    s.command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone_brick_stairs[facing=east]", x - 11 + i,
                            SURFACE_Y + i, lz - 1, x - 11 + i, SURFACE_Y + i, lz + 1));
                    if (i > 0) {
                        s.command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 11 + i, SURFACE_Y, lz - 1,
                                x - 11 + i, SURFACE_Y + i - 1, lz + 1));
                    }
                }
                s.command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 5, SURFACE_Y, lz - 1, x + 15,
                        SURFACE_Y + 5, lz + 1));
            }
            case "ladder" -> {
                s.command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 13, SURFACE_Y, lz - 1, x - 13,
                        SURFACE_Y + 14, lz + 1));
                s.command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:ladder[facing=west]", x - 14, SURFACE_Y, lz,
                        x - 14, SURFACE_Y + 14, lz));
            }
            case "edge" -> {
                s.command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 12, SURFACE_Y - 8, lz - 1, x + 15,
                        SURFACE_Y - 1, lz + 1));
                s.command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 12, SURFACE_Y - 9, lz - 1, x + 15,
                        SURFACE_Y - 9, lz + 1));
            }
            default -> s.command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", x - 15, lane.y1(), lz - 1, x + 15, lane.y2(),
                    lz + 1, lane.block()));
        }
    }

    // -------------------------------------------------------------------------------------------- interop

    /**
     * 1027-iw-interop: a Blink hold over the walk and its clicks; Blink Charge on while he walks in a chest; knockback mid
     * walk (a zombie-sourced /damage); a server teleport mid walk; a setback-shaped tp back; at every latency.
     */
    private static void interop(ClientGameTestContext ctx) {
        if (!Mod.isCheat()) {
            return;
        }
        int x = -9700;
        int z = -9000;
        Scenario.runExpectingFlags(ctx, "1027-iw-interop", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = gateOff(ctx);
            Rows rows = new Rows(ctx, server, scenario);
            String n = name(ctx);
            server.command("effect give " + n + " minecraft:resistance 600 4 true");
            server.command("effect give " + n + " minecraft:regeneration 600 4 true");
            try (Hx hx = Hx.connect()) {
                iwOn(ctx, true, 0);
                List<Click> script = List.of(Click.left(9), Click.left(19), Click.right(10), Click.left(20), Click.shift(19));
                for (int lat : latencies()) {
                    latency(ctx, lat);
                    // Blink hold (Hold Ticks 20) across the walk and its clicks.
                    tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
                    give(server, ctx);
                    BlinkTests.setUp(ctx, 20);
                    try {
                        rows.begin();
                        openInventory(ctx);
                        keys(ctx, SPRINT);
                        ctx.waitTicks(5);
                        BlinkTests.holdDown(ctx);
                        walkRow(rows, ctx, hx, "L" + lat + " blink hold", List.of(new Seg(SPRINT, 12), new Seg(Set.of("key.back"), 14)),
                                spread(script.size(), 2, 4), singles(script), Set.of(0), 3, null, true);
                        BlinkTests.holdUp(ctx);
                        ctx.waitTicks(10);
                        // Blink Charge on, standing in the inventory then walking with clicks.
                        tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
                        give(server, ctx);
                        rows.begin();
                        BlinkTests.chargeOn(ctx);
                        openInventory(ctx);
                        ctx.waitTicks(25);
                        long bank = BlinkTests.cnum(ctx, "banked");
                        walkRow(rows, ctx, hx, "L" + lat + " blink charge (bank " + bank + ")", shuttle(6),
                                spread(script.size(), 3, 5), singles(script), Set.of(0), 3, null, true);
                        BlinkTests.chargeOff(ctx);
                        ctx.waitTicks(20);
                    } finally {
                        ctx.runOnClient(mc -> {
                            Mod.staticCall(BlinkTests.FEATURE, "end", "test teardown");
                            Mod.staticCall(BlinkTests.CHARGE, "reset", "test teardown");
                            Mod.setField(BlinkTests.FEATURE, "keyHeldForTest", null);
                            Mod.setField(BlinkTests.CHARGE, "keyHeldForTest", null);
                            Mod.set(BlinkTests.CFG, "setEnabled", false);
                        });
                        ctx.waitTicks(5);
                    }
                    // Knockback mid walk.
                    tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
                    give(server, ctx);
                    server.command("kill @e[type=zombie]");
                    server.command(String.format(Locale.ROOT, "summon zombie %d %d %d {NoAI:1b,Silent:1b,Tags:[\"iwkb\"]}", x + 3, SURFACE_Y, z + 3));
                    server.sync(10);
                    rows.begin();
                    openInventory(ctx);
                    keys(ctx, SPRINT);
                    ctx.waitTicks(6);
                    ctx.runOnClient(mc -> dev.testkit.harness.PacketTrace.start());
                    server.command("damage " + n + " 1 minecraft:mob_attack by @e[tag=iwkb,limit=1]");
                    walkRow(rows, ctx, hx, "L" + lat + " knockback", List.of(new Seg(SPRINT, 10), new Seg(Set.of("key.back"), 16)),
                            spread(script.size(), 1, 4), singles(script), Set.of(0), 2, null, true);
                    ctx.runOnClient(mc -> dev.testkit.harness.PacketTrace.stop());
                    int kb = dev.testkit.harness.PacketTrace.received("set_entity_motion")
                            + dev.testkit.harness.PacketTrace.received("hurt_animation");
                    rows.scenario.log("  knockback: " + dev.testkit.harness.PacketTrace.received("set_entity_motion")
                            + " motion packet(s), " + dev.testkit.harness.PacketTrace.received("damage_event") + " damage event(s)");
                    if (dev.testkit.harness.PacketTrace.received("damage_event") == 0) {
                        rows.bad.add("L" + lat + " knockback: no damage reached him (the row tested nothing)");
                    }
                    server.command("kill @e[type=zombie]");
                    // Server teleport mid walk (5 blocks sideways), clicks right after.
                    tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
                    give(server, ctx);
                    rows.begin();
                    openInventory(ctx);
                    keys(ctx, SPRINT);
                    ctx.waitTicks(8);
                    server.command("execute as " + n + " at @s run tp @s ~ ~ ~5");
                    walkRow(rows, ctx, hx, "L" + lat + " teleport", List.of(new Seg(SPRINT, 10), new Seg(Set.of("key.back"), 16)),
                            spread(script.size(), 1, 4), singles(script), Set.of(0), 2, null, true);
                    // Setback-shaped: a tp one block back along his walk, then clicks.
                    tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
                    give(server, ctx);
                    rows.begin();
                    openInventory(ctx);
                    keys(ctx, SPRINT);
                    ctx.waitTicks(8);
                    server.command("execute as " + n + " at @s run tp @s ~-1 ~ ~");
                    walkRow(rows, ctx, hx, "L" + lat + " setback (tp back)", List.of(new Seg(SPRINT, 10), new Seg(Set.of("key.back"), 16)),
                            spread(script.size(), 1, 4), singles(script), Set.of(0), 2, null, true);
                }
            } finally {
                tearDown(ctx, gate);
            }
            rows.finish("1027-iw-interop");
        });
    }

    // -------------------------------------------------------------------------------------------- etherwarp

    static final String AOTV = "minecraft:diamond_shovel[custom_data={id:\"ASPECT_OF_THE_VOID\",ethermerge:1,tuned_transmission:4},unbreakable={}]";

    /**
     * 1027-iw-warp: an etherwarp sent, the inventory opened before it lands (150/300 ms), the landing (a server teleport)
     * arrives with the screen open while he walks and clicks. With Zero Ping Etherwarp off and on.
     */
    private static void warp(ClientGameTestContext ctx) {
        if (!Mod.isCheat()) {
            return;
        }
        int x = -9800;
        int z = -9000;
        Scenario.runExpectingFlags(ctx, "1027-iw-warp", (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = gateOff(ctx);
            Rows rows = new Rows(ctx, server, scenario);
            try (Hx hx = Hx.connect()) {
                hx.call("dungeon.abilities", "enabled", true);
                iwOn(ctx, true, 0);
                for (int lat : latencies()) {
                    for (boolean zp : new boolean[]{false, true}) {
                        latency(ctx, Math.max(lat, 0));
                        tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
                        give(server, ctx);
                        // The AOTV goes in hotbar 1 (slot 0 holds the log the script does not touch).
                        hx.give(1, AOTV);
                        ctx.runOnClient(mc -> {
                            mc.player.getInventory().setSelectedSlot(1);
                            Mod.set("zpew.ZeroPingEtherwarpConfig", "setEnabled", zp);
                        });
                        ctx.waitTicks(10);
                        rows.begin();
                        JsonObject before = hx.call("dungeon.stats").getAsJsonObject();
                        ctx.getInput().holdKey(o -> o.keyShift);
                        ctx.waitTicks(4);
                        look(ctx, x + 14.5, SURFACE_Y - 0.05, z + 0.5);
                        ctx.waitTicks(1);
                        double[] s0 = spos(state(hx));
                        ctx.getInput().pressKey(o -> o.keyUse);
                        // Open the inventory at once: the landing arrives under it.
                        ctx.getInput().releaseKey(o -> o.keyShift);
                        openInventory(ctx);
                        List<Click> script = List.of(Click.left(9), Click.left(19), Click.right(10), Click.left(20));
                        walkRow(rows, ctx, hx, "L" + lat + " etherwarp lands mid-screen" + (zp ? " (ZPEW on)" : ""),
                                List.of(new Seg(Set.of("key.left"), 10), new Seg(Set.of("key.right"), 12)), spread(script.size(), 2, 4),
                                singles(script), Set.of(0), 1, null, true);
                        JsonObject after = hx.call("dungeon.stats").getAsJsonObject();
                        int warps = after.get("etherwarps").getAsInt() - before.get("etherwarps").getAsInt();
                        double[] s1 = spos(state(hx));
                        rows.scenario.log(String.format(Locale.ROOT, "  warp: %d server etherwarp(s), server moved %.2f", warps, flat(s0, s1)));
                        if (warps != 1 || flat(s0, s1) < 8) {
                            rows.bad.add("L" + lat + (zp ? " ZPEW" : "") + " the etherwarp never landed (" + warps + ")");
                        }
                        ctx.runOnClient(mc -> Mod.set("zpew.ZeroPingEtherwarpConfig", "setEnabled", false));
                        ctx.waitTicks(10);
                    }
                }
                hx.call("dungeon.abilities", "enabled", false);
            } finally {
                tearDown(ctx, gate);
            }
            rows.finish("1027-iw-warp");
        });
    }
}

package dev.testkit.gametest.boss;

import com.google.gson.JsonObject;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.hx.HxKit;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.util.ArrayList;
import java.util.List;

/**
 * melodyprogress-now-1124 (killer560, 2026-11-23: Send Melody Progress lines must go out AT ONCE, even with the Melody
 * terminal open; held, 16 lines went out 5-6 s late) and melodycustom-1124 (2026-11-24: the message is customizable).
 * <ul>
 *   <li>1124-melody-progress-now: a row advances with the terminal open: the "pc Melody n/R - p%" command reaches the
 *       server within a few ticks, the terminal still open; another mod line asked for then is still held.</li>
 *   <li>1124-melody-progress-custom: a custom template, an empty one, one with a leading slash and a newline: the exact text on the wire.</li>
 *   <li>1124-same-melody-progress: Auto Melody clicking while his clicks land every tick: a server tick carries a
 *       container click and the progress command; GrimAC (default and experimental) stays silent.</li>
 * </ul>
 */
final class MelodyProgressNow1124Cases {

    private static final String CFG = "terminals.TerminalSolverConfig";

    private MelodyProgressNow1124Cases() {
    }

    static void register(Session s) {
        s.test("1124-melody-progress-now", MelodyProgressNow1124Cases::now);
        s.test("1124-melody-progress-custom", MelodyProgressNow1124Cases::custom);
        s.test("1124-same-melody-progress", MelodyProgressNow1124Cases::sameTick);
    }

    private static HxKit.Settings settings(Session c) {
        return new HxKit.Settings(c).with(CFG, "Enabled", true).with(CFG, "MelodyEnabled", true)
                .with(CFG, "MelodySendProgress", true).with(CFG, "AutoTerminalsEnabled", false)
                .with("melody.MelodyHudConfig", "ShareProgress", false);
    }

    private static boolean screenOpen(net.minecraft.client.Minecraft mc) {
        return dev.testkit.compat.McCompat.screen(mc) instanceof AbstractContainerScreen<?>;
    }

    private static void closeAll(Session c) {
        c.ctx().runOnClient(mc -> {
            if (mc.player != null && screenOpen(mc)) mc.player.closeContainer();
        });
        c.hx().call("menu.close");
        c.ctx().waitTicks(6);
    }

    /** Opens a frozen Melody terminal and lights clay row 3 (two rows done), as one row advance. */
    private static void openAndAdvance(Session c) {
        closeAll(c);
        JsonObject t = c.hx().call("menu.terminal", "type", "MELODY", "seed", 31, "interval", 10, "bandRows", 3,
                "freeze", true, "target", 5).getAsJsonObject();
        String title = t.get("title").getAsString();
        c.waitUntil("the Melody terminal", mc -> dev.testkit.compat.McCompat.screen(mc) instanceof AbstractContainerScreen<?> scr
                && title.equals(scr.getTitle().getString()), 100);
        c.ctx().waitTicks(5);
        c.hx().call("menu.terminal.melody", "row", 3, "lime", 4, "target", 2);
    }

    private static List<JsonObject> pcLines(Session c, long head) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonObject e : c.hx().events(head, "cmd")) {
            if (e.get("command").getAsString().startsWith("pc ")) out.add(e);
        }
        return out;
    }

    private static int rows(Session c) {
        return c.onClient(mc -> (Integer) Mod.staticCall("terminals.TerminalLayouts", "melodyRows"));
    }

    private static void now(Session c) throws Exception {
        try (HxKit.Settings cfg = settings(c)) {
            TeamMelodyRelayCases.inP3(c);
            long head = c.hx().head();
            long at = c.hx().call("server.tick").getAsLong();
            openAndAdvance(c);
            c.waitUntil("the progress line at the server", mc -> !pcLines(c, head).isEmpty(), 60);
            JsonObject first = pcLines(c, head).get(0);
            String want = "pc Melody 2/" + rows(c) + " - " + Math.round(200f / rows(c)) + "%";
            boolean open = c.onClient(mc -> screenOpen(mc));
            long took = first.get("tick").getAsLong() - at;
            c.note("progress line '" + first.get("command").getAsString() + "' at the server " + took
                    + " tick(s) after the terminal opened; terminal still open: " + open);
            c.check(open, "the terminal closed, the line was not tested while it was open");
            c.check(first.get("command").getAsString().equals(want), "want '" + want + "', got " + first.get("command").getAsString());
            // Another mod line asked for in the open terminal is still held.
            c.onClient(mc -> Mod.staticCall("util.OutgoingChat", "command", "pc Hx other line 1124"));
            c.ctx().waitTicks(20);
            c.check((Integer) c.onClient(mc -> Mod.staticCall("util.OutgoingChat", "pending")) == 1, "the other mod line was not held");
            c.check(pcLines(c, head).stream().noneMatch(e -> e.get("command").getAsString().contains("other line 1124")),
                    "another mod line went out while the terminal was open");
        } finally {
            closeAll(c);
        }
    }

    private static String send(Session c, String template) {
        c.onClient(mc -> Mod.with(CFG, "MelodyProgressMessage", template));
        long head = c.hx().head();
        openAndAdvance(c);
        c.waitUntil("the progress line for template " + template, mc -> !pcLines(c, head).isEmpty(), 60);
        String got = pcLines(c, head).get(0).get("command").getAsString();
        closeAll(c);
        return got;
    }

    private static void custom(Session c) throws Exception {
        try (HxKit.Settings cfg = settings(c)) {
            TeamMelodyRelayCases.inP3(c);
            int r = rows(c);
            int pct = Math.round(200f / r);
            String def = "pc Melody 2/" + r + " - " + pct + "%";
            String a = send(c, "Mel {done} of {rows} = {pct} percent");
            c.check(a.equals("pc Mel 2 of " + r + " = " + pct + " percent"), "custom text on the wire: " + a);
            String b = send(c, "");
            c.check(b.equals(def), "empty template: want default '" + def + "', got " + b);
            String d = send(c, "/pc evil\n{done}/{rows}");
            c.check(d.equals("pc pc evil 2/" + r), "slash and newline: got " + d);
            c.check(!d.contains("\n") && !d.startsWith("pc /"), "unsanitized line " + d);
            c.note("custom '" + a + "', empty '" + b + "', sanitized '" + d + "'");
        } finally {
            closeAll(c);
        }
    }

    private static void sameTick(Session c) throws Exception {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Melody is cheat-only");
            return;
        }
        SameTick st = null;
        try (HxKit.Settings cfg = settings(c)) {
            cfg.with(CFG, "AutoTerminalsEnabled", true).with(CFG, "AutoMelodyEnabled", true)
                    .with(CFG, "HoverTerminalsEnabled", false).with(CFG, "HoldToClickEnabled", false)
                    .with(CFG, "AutoStreamSafe", false).with(CFG, "BlockInputWhileAutoClicking", false);
            TeamMelodyRelayCases.inP3(c);
            closeAll(c);
            long head = c.hx().head();
            int solved = c.events("terminal.solved").size();
            st = c.onClient(mc -> SameTick.start(SameTick.Act.SCREEN_CLICK, 1,
                    p -> p instanceof net.minecraft.network.protocol.game.ServerboundChatCommandPacket, 4, 5));
            com.google.gson.JsonArray ts = new com.google.gson.JsonArray();
            for (int t : new int[]{5, 3, 3}) ts.add(t);
            JsonObject args = new JsonObject();
            args.addProperty("type", "MELODY");
            args.addProperty("seed", 1124);
            args.addProperty("interval", 10);
            args.addProperty("bandRows", 3);
            args.add("targets", ts);
            c.hx().call("menu.terminal", args);
            c.waitUntil("Melody solved", mc -> c.events("terminal.solved").size() > solved, 2400);
            c.ctx().waitTicks(10);
            c.check(!pcLines(c, head).isEmpty(), "Auto Melody played but no progress line went out (nothing tested)");
            SameTick.judge(c, st, "progress line with his container clicks", SameTick.Expect.SHARE);
            st = null;
        } finally {
            if (st != null) {
                SameTick s2 = st;
                c.onClient(mc -> {
                    s2.close();
                    return null;
                });
            }
            closeAll(c);
        }
    }
}

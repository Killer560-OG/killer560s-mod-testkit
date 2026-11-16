package dev.testkit.gametest.boss;

import com.google.gson.JsonObject;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.network.protocol.game.ServerboundInteractPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * 1115-ap3-termdouble (mod ap3termdouble-1115): AP3's Term Aura node sent its terminal click TWICE when the server's
 * window open took more than the node's 2-tick retry gap (S4 sim trace: clicks at tick 31 and 35, opens at 34 and 36,
 * then "Stopped: a screen opened"). Here the Hx terminal opens {@code openDelay} server ticks after the interact; the
 * chain is TERM_AURA then TERMINAL. The server must see exactly ONE interact, the window must open once, AP3 must still
 * be running the chain (TERMINAL active, no stop) and finish on the completion line. The variants run the player's own
 * USE, ATTACK, SWAP and TP in the tick of the node's click (default and experimental Grim, zero lines expected).
 */
final class Ap3TermDouble1115Cases {

    private static final int OPEN_DELAY = 4;

    private Ap3TermDouble1115Cases() {
    }

    static void register(Session s) {
        if (!Mod.isCheat()) {
            return;
        }
        s.test("1115-ap3-termdouble", c -> run(c, null, false));
        s.test("1115-ap3-termdouble-again", c -> run(c, null, true));
        for (SameTick.Act a : new SameTick.Act[]{SameTick.Act.USE, SameTick.Act.ATTACK, SameTick.Act.SWAP, SameTick.Act.TP, SameTick.Act.OPEN_CLICK}) {
            s.test("1115-ap3-termdouble-" + (a == SameTick.Act.OPEN_CLICK ? "click" : a.name().toLowerCase(java.util.Locale.ROOT)), c -> run(c, a, false));
        }
    }

    private static void run(Session c, SameTick.Act act, boolean again) throws Exception {
        String ex = Ap3TermAuraCases.EXEC;
        String cfg = TerminalAuraCases.CONFIG;
        double px = Ap3TermAuraCases.X - 1.5;
        double pz = Ap3TermAuraCases.Z + 0.5;
        int g = Ap3TermAuraCases.G;
        List<AutoCloseable> undo = new ArrayList<>();
        SameTick st = null;
        try {
            c.hx().call("boss.term.build", "x", Ap3TermAuraCases.X, "z", Ap3TermAuraCases.Z, "groundY", g);
            TerminalAuraCases.tp(c, px, g + 3, pz, 90f);
            c.ctx().waitTicks(10);
            SameTickCases.sticks(c);
            int standId = c.hx().call("boss.term.spawn").getAsJsonObject().get("standId").getAsInt();
            c.hx().call("boss.term.config", "openDelay", OPEN_DELAY);
            c.waitUntil("the client to have the terminal stand", mc -> mc.level.getEntity(standId) != null, 200);
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
            c.hx().chat(TerminalAuraCases.GOLDOR);
            c.waitUntil("Floor7Tracker phase P3",
                    mc -> "P3".equals(String.valueOf(Mod.staticCall(TerminalAuraCases.TRACKER, "getPhase"))), 100);
            undo.add(c.onClient(mc -> Mod.with(cfg, "Enabled", false)));
            undo.add(c.onClient(mc -> Mod.with(cfg, "Range", 3.0)));
            undo.add(c.onClient(mc -> Mod.with(cfg, "TurnToTerminal", true)));
            undo.add(c.onClient(mc -> Mod.with(Ap3TermAuraCases.AP3_CONFIG, "Enabled", true)));
            c.onClient(mc -> {
                Mod.staticCall(ex, "stop", "test lane");
                Mod.staticCall(Ap3TermAuraCases.FEATURE, "setForceDungeon", true);
                Ap3TermAuraCases.setChain();
                return null;
            });
            TerminalAuraCases.closeScreen(c);
            if (act == SameTick.Act.ATTACK) {
                c.server().command("summon zombie " + (px - 2.5) + " " + (g + 3) + " " + pz
                        + " {NoAI:1b,Invulnerable:1b,Silent:1b,Tags:[\"sametick\"]}");
                c.waitUntil("the zombie under his crosshair", mc -> mc.hitResult instanceof
                        net.minecraft.world.phys.EntityHitResult, 100);
            }
            int before = c.events("term.interact").size();
            Object termAura = Ap3TermAuraCases.node("TERM_AURA", px, g + 3, pz);
            Object terminal = Ap3TermAuraCases.node("TERMINAL", px, g + 3, pz);
            if (act != null) {
                st = SameTickCases.start(c, act, p -> p instanceof ServerboundInteractPacket);
            }
            c.onClient(mc -> {
                Ap3TermAuraCases.setChain(termAura, terminal);
                return null;
            });
            for (int i = 0; i < 80 && c.events("term.interact").size() <= before; i++) {
                if (act == SameTick.Act.TP && i % 2 == 0) { // his teleport accepts share ticks with the node's click
                    c.server().command("execute as @a at @s run tp @s ~ ~ ~");
                }
                c.ctx().waitTick();
            }
            c.check(c.events("term.interact").size() > before, "the node's click never reached the server");
            if (act == SameTick.Act.OPEN_CLICK) { // his open-click-close would shut the terminal window: judge it before the window arrives
                SameTickCases.judge(c, st, "ap3 term click");
                st = null;
            }
            if (again) { // a second approach inside the first click's wait: out of the box and in again (a new run)
                c.onClient(mc -> {
                    Mod.staticCall(ex, "stop", "test lane");
                    Ap3TermAuraCases.setChain();
                    return null;
                });
                TerminalAuraCases.tp(c, px - 8, g + 3, pz, 90f);
                c.ctx().waitTick();
                c.onClient(mc -> {
                    Ap3TermAuraCases.setChain(termAura, terminal);
                    return null;
                });
                TerminalAuraCases.tp(c, px, g + 3, pz, 90f);
            }
            c.waitUntil("the terminal screen", mc -> dev.testkit.compat.McCompat.screen(mc) != null, 60);
            c.ctx().waitTicks(2 * OPEN_DELAY + 10); // a second click would land inside this window
            List<JsonObject> ev = c.events("term.interact");
            c.check(ev.size() - before == 1, "expected exactly one terminal click, the server saw " + (ev.size() - before));
            c.check(c.hx().call("boss.term.state").getAsJsonObject().get("opens").getAsInt() == 1,
                    "the terminal window was opened more than once: " + c.hx().call("boss.term.state"));
            String reason = c.onClient(mc -> String.valueOf(Mod.staticCall(ex, "stopReason")));
            c.check((Boolean) c.onClient(mc -> Mod.staticCall(ex, "isRunning")) && "test lane".equals(reason),
                    "AP3 stopped after the terminal click: '" + reason + "'");
            String self = c.onClient(mc -> mc.player.getGameProfile().name());
            c.hx().chat(self + " completed a terminal! (1/7)");
            c.waitUntil("the TERMINAL node to finish", mc -> !(Boolean) Mod.staticCall(ex, "isRunning"), 60);
            String after = c.onClient(mc -> String.valueOf(Mod.staticCall(ex, "stopReason")));
            c.check("test lane".equals(after), "the chain ended by a stop, not by finishing: " + after);
            if (st != null) {
                SameTickCases.judge(c, st, "ap3 term click");
                st = null;
            }
        } finally {
            if (st != null) {
                SameTick s2 = st;
                c.onClient(mc -> {
                    s2.close();
                    return null;
                });
            }
            TerminalAuraCases.closeScreen(c);
            c.onClient(mc -> {
                try {
                    Mod.staticCall(ex, "stop", "test end");
                    Ap3TermAuraCases.setChain();
                    Mod.staticCall(Ap3TermAuraCases.FEATURE, "setForceDungeon", false);
                } catch (Throwable t) {
                    System.out.println("[ap3-termdouble] AP3 reset failed: " + t);
                }
                BossAuraTurnCases.restore(undo);
                return null;
            });
            c.hx().call("boss.term.clear");
            c.server().command("kill @e[tag=sametick]");
            c.server().command("clear @a stick");
            c.hx().sidebarClear();
            GrimTickCases.home(c);
        }
    }
}

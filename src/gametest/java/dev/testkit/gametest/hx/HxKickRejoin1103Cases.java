package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Kick Rejoin Timer (mod branch kickhub-1103, killer560 2026-11-03: "it should be for kicked to spawn. when you have to
 * wait 1m to rejoin"), 1103-kickrejoin-*. Hypixel's own lines (as in his logs) and a lobby / SkyBlock sidebar:
 * <ul>
 *   <li>lobby: kicked while joining, moved to a lobby: the HUD counts down from the Rejoin Wait, a "Queue join in
 *       cooldown" refusal mid-wait changes nothing, one at the end puts 15 s back, then the "Rejoin SkyBlock now!" title
 *       and one sound; back on SkyBlock it clears. No command is ever sent.</li>
 *   <li>kickline: "A kick occurred ... SkyBlock lobby!" starts it with the configured wait (30 s here).</li>
 *   <li>stated: a line that states the seconds sets the timer to them.</li>
 *   <li>stay: a kick line while he stays on SkyBlock (a failed warp) clears after 20 s with no title.</li>
 *   <li>forged / limbo / off: player-typed copies, a limbo, or the switch off start nothing.</li>
 *   <li>reload: every setting survives a save and a load; the wait is clamped to 5..300.</li>
 * </ul>
 */
final class HxKickRejoin1103Cases {

    private static final String CFG = "kickrejoin.KickRejoinConfig";
    private static final String F = "kickrejoin.KickRejoinFeature";

    private HxKickRejoin1103Cases() {
    }

    static void register(Session s) {
        s.test("1103-kickrejoin-lobby", HxKickRejoin1103Cases::lobby);
        s.test("1103-kickrejoin-kickline", HxKickRejoin1103Cases::kickline);
        s.test("1103-kickrejoin-stated", HxKickRejoin1103Cases::stated);
        s.test("1103-kickrejoin-stay", HxKickRejoin1103Cases::stay);
        s.test("1103-kickrejoin-forged", HxKickRejoin1103Cases::forged);
        s.test("1103-kickrejoin-off", HxKickRejoin1103Cases::off);
        s.test("1103-kickrejoin-reload", HxKickRejoin1103Cases::reload);
    }

    // ---- shared -----------------------------------------------------------------------------------------------------

    private static Settings settings(Session c, boolean enabled, int wait) {
        return new Settings(c).with(CFG, "Enabled", enabled)
                .with(CFG, "ReadyTitle", true)
                .with(CFG, "ReadySound", true)
                .with(CFG, "CooldownSeconds", wait);
    }

    private static void onSkyblock(Session c) {
        sidebar(c, "template", "hub");
        c.hx().tab(List.of("Area: Hub", "Profile: Test", ""), null, null);
        c.waitUntil("on SkyBlock", mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isOnSkyblock"), 200);
        run(c, () -> Mod.staticCall(F, "testReset"));
    }

    private static void toLobby(Session c) {
        c.hx().sidebar("§e§lHYPIXEL", "§fRank: §aVIP", "§ewww.hypixel.net");
        c.hx().tabClear();
        c.waitUntil("off SkyBlock", mc -> !(Boolean) Mod.staticCall("util.SkyblockGate", "isOnSkyblock"), 200);
    }

    private static boolean running(Session c) {
        return c.onClient(mc -> (Boolean) Mod.staticCall(F, "running"));
    }

    private static long remaining(Session c) {
        return c.onClient(mc -> (Long) Mod.staticCall(F, "remainingMs"));
    }

    private static String title(Session c) {
        return c.onClient(mc -> (String) Mod.staticCall(F, "lastTitle"));
    }

    private static String hud(Session c) {
        return c.onClient(mc -> (String) Mod.staticCall(F, "lastHudText"));
    }

    private static void age(Session c, long ms) {
        run(c, () -> Mod.staticCall(F, "testAge", ms));
    }

    // ---- cases ------------------------------------------------------------------------------------------------------

    private static void lobby(Session c) {
        try (Settings set = settings(c, true, 60)) {
            onSkyblock(c);
            long head = c.hx().head();
            int sounds0 = c.onClient(mc -> (Integer) Mod.staticCall(F, "sounds"));
            // His log, 2026-07-12: warping, then these two lines in the same second, then the Prototype Lobby.
            c.hx().chat("§cYou were kicked while joining that server!");
            c.hx().chat("§cYou tried to rejoin too fast, please try again in a moment!");
            c.waitUntil("the timer running", mc -> (Boolean) Mod.staticCall(F, "running"), 40);
            toLobby(c);
            c.waitUntil("the HUD drawn", mc -> Mod.staticCall(F, "lastHudText") != null, 60);
            String first = hud(c);
            c.check(first.startsWith("Kicked from SkyBlock ") && first.contains(" ago - rejoin in "), "HUD '" + first + "'");
            long left = remaining(c);
            c.check(left > 45_000 && left <= 60_000, "remaining after the kick " + left + " ms, want ~60 s");
            c.check(title(c) == null, "a title before the wait is over: " + title(c));

            // A refusal mid-wait changes nothing.
            c.hx().chat("§cCannot join SkyBlock for a moment! (Queue join in cooldown)");
            c.ctx().waitTicks(10);
            long mid = remaining(c);
            c.check(Math.abs(mid - (left - 500)) < 3000, "a mid-wait refusal moved the timer: " + left + " -> " + mid);

            // Near the end, a refusal puts 15 s back.
            age(c, mid - 3000);
            c.ctx().waitTicks(5);
            c.check(remaining(c) < 4000, "aged timer " + remaining(c));
            c.hx().chat("§cCannot join SkyBlock for a moment! (Queue join in cooldown)");
            c.waitUntil("the refusal's 15 s", mc -> (Long) Mod.staticCall(F, "remainingMs") > 12_000, 40);
            long extended = remaining(c);
            c.check(title(c) == null, "a title while still refused: " + title(c));

            // The end: the title, one sound, the HUD says now.
            age(c, extended + 500);
            c.waitUntil("the rejoin title", mc -> (Boolean) Mod.staticCall(F, "readyFired"), 40);
            c.check("§aRejoin SkyBlock now!".equals(title(c)), "title " + title(c));
            c.ctx().waitTicks(10);
            int sounds = c.onClient(mc -> (Integer) Mod.staticCall(F, "sounds")) - sounds0;
            c.check(sounds == 1, "sounds " + sounds);
            String done = hud(c);
            c.check(done.endsWith("rejoin now!"), "HUD at the end '" + done + "'");

            // Back on SkyBlock: cleared.
            sidebar(c, "template", "hub");
            c.waitUntil("cleared on SkyBlock", mc -> !(Boolean) Mod.staticCall(F, "running"), 200);
            c.check(c.hx().commands(head).isEmpty(), "commands sent: " + c.hx().commands(head));
            c.note(String.format(Locale.ROOT, "HUD '%s' (%d ms left); mid refusal %d ms; end refusal -> %d ms; title '%s', "
                    + "%d sound, HUD '%s'; cleared on SkyBlock; no command", first, left, mid, extended, title(c), sounds, done));
            run(c, () -> Mod.staticCall(F, "testReset"));
        }
    }

    private static void kickline(Session c) {
        try (Settings set = settings(c, true, 30)) {
            onSkyblock(c);
            toLobby(c);
            c.hx().chat("§cA kick occurred in your connection, so you were put in the SkyBlock lobby!");
            c.waitUntil("the timer running", mc -> (Boolean) Mod.staticCall(F, "running"), 40);
            long left = remaining(c);
            c.check(left > 25_000 && left <= 30_000, "remaining " + left + " ms, want ~30 s (Rejoin Wait 30)");
            c.waitUntil("the HUD drawn", mc -> Mod.staticCall(F, "lastHudText") != null, 60);
            c.note("kick-to-lobby line with Rejoin Wait 30: " + left + " ms, HUD '" + hud(c) + "'");
            onSkyblock(c);
        }
    }

    private static void stated(Session c) {
        try (Settings set = settings(c, true, 60)) {
            onSkyblock(c);
            toLobby(c);
            c.hx().chat("§cYou must wait 20 seconds before joining SkyBlock!");
            c.waitUntil("the timer running", mc -> (Boolean) Mod.staticCall(F, "running"), 40);
            long left = remaining(c);
            c.check(left > 17_000 && left <= 20_000, "stated 20 s -> " + left + " ms");
            c.note("stated 20 s -> " + left + " ms");
            onSkyblock(c);
        }
    }

    private static void stay(Session c) {
        try (Settings set = settings(c, true, 60)) {
            onSkyblock(c);
            c.hx().chat("§cYou were kicked while joining that server!");
            c.waitUntil("the timer running", mc -> (Boolean) Mod.staticCall(F, "running"), 40);
            age(c, 21_000);
            c.waitUntil("cleared (never left SkyBlock)", mc -> !(Boolean) Mod.staticCall(F, "running"), 40);
            c.check(title(c) == null, "a title although he never left SkyBlock: " + title(c));
            c.check(hud(c) == null, "the HUD drew on SkyBlock: " + hud(c));
            c.note("kick line while staying on SkyBlock: cleared after 20 s, no title, no HUD");
        }
    }

    private static void forged(Session c) {
        try (Settings set = settings(c, true, 60)) {
            onSkyblock(c);
            toLobby(c);
            String[] typed = {
                    "§7[312] §b[MVP§c+§b] HxMateA§f: You were kicked while joining that server!",
                    "§9Party §8> §b[MVP§c+§b] HxMateA§f: A kick occurred in your connection, so you were put in the SkyBlock lobby!",
                    "§dFrom §b[MVP§c+§b] HxMateB§7: Cannot join SkyBlock for a moment! (Queue join in cooldown)",
                    "§2Guild > §b[MVP§c+§b] HxMateA §e[Member]§f: You must wait 20 seconds before joining SkyBlock!",
                    "§a[VIP] HxMateA§f: You tried to rejoin too fast, please try again in a moment!",
                    "§cYou were spawned in Limbo.",
            };
            for (String line : typed) {
                c.hx().chat(line);
                c.ctx().waitTicks(6);
            }
            c.ctx().waitTicks(40);
            c.check(!running(c), "a typed copy (or a limbo) started the timer");
            c.check(title(c) == null && hud(c) == null, "title " + title(c) + " HUD " + hud(c));
            c.note("5 player-typed copies and a limbo: no timer, no HUD, no title");
            onSkyblock(c);
        }
    }

    private static void off(Session c) {
        try (Settings set = settings(c, false, 60)) {
            onSkyblock(c);
            toLobby(c);
            c.hx().chat("§cYou were kicked while joining that server!");
            c.hx().chat("§cA kick occurred in your connection, so you were put in the SkyBlock lobby!");
            c.ctx().waitTicks(60);
            c.check(!running(c), "off: the timer started");
            c.check(hud(c) == null && title(c) == null, "off: HUD " + hud(c) + " title " + title(c));
            c.note("switch off: both kick lines -> nothing");
            onSkyblock(c);
        }
    }

    private static void reload(Session c) {
        Object cfg = c.onClient(mc -> Mod.cfg(CFG));
        Object[] saved = c.onClient(mc -> new Object[]{Mod.call(cfg, "isEnabled"), Mod.call(cfg, "isReadyTitle"),
                Mod.call(cfg, "isReadySound"), Mod.call(cfg, "getCooldownSeconds")});
        try {
            run(c, () -> {
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setReadyTitle", false);
                Mod.call(cfg, "setReadySound", false);
                Mod.call(cfg, "setCooldownSeconds", 77);
                Mod.call(cfg, "save");
                Mod.staticCall(CFG, "load");
            });
            Object fresh = c.onClient(mc -> Mod.cfg(CFG));
            String got = c.onClient(mc -> Mod.call(fresh, "isEnabled") + " " + Mod.call(fresh, "isReadyTitle") + " "
                    + Mod.call(fresh, "isReadySound") + " " + Mod.call(fresh, "getCooldownSeconds"));
            c.check("true false false 77".equals(got), "after reload: " + got);
            run(c, () -> Mod.call(fresh, "setCooldownSeconds", 999));
            Object hi = c.onClient(mc -> Mod.call(fresh, "getCooldownSeconds"));
            run(c, () -> Mod.call(fresh, "setCooldownSeconds", 1));
            Object lo = c.onClient(mc -> Mod.call(fresh, "getCooldownSeconds"));
            c.check("300".equals(String.valueOf(hi)) && "5".equals(String.valueOf(lo)), "clamped " + hi + "/" + lo);
            c.note("save + load: " + got + "; clamped 999/1 -> " + hi + "/" + lo);
        } finally {
            run(c, () -> {
                Object f = Mod.cfg(CFG);
                Mod.call(f, "setEnabled", saved[0]);
                Mod.call(f, "setReadyTitle", saved[1]);
                Mod.call(f, "setReadySound", saved[2]);
                Mod.call(f, "setCooldownSeconds", saved[3]);
                Mod.call(f, "save");
            });
        }
    }
}

package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Kick Rejoin Timer (mod branch kickhub-1103, killer560 2026-11-03: "it should be for kicked to spawn. when you have to
 * wait 1m to rejoin", then "have it be 60 seconds no matter what ... also have an option to send a message to party chat
 * whenever it happens and the player can customize their message"), 1103-kickrejoin-*. Hypixel's own lines (as in his
 * logs) and a lobby / SkyBlock sidebar, judged by the mod's state and the commands the SERVER received:
 * <ul>
 *   <li>lobby: kicked while joining, moved to a lobby: the HUD counts down from 60 s; "Queue join in cooldown" refusals
 *       (mid-wait and at the very end) change nothing; at 60 s the "Rejoin SkyBlock now!" title and one sound; back on
 *       SkyBlock it clears. Party Message off: no command at all.</li>
 *   <li>restart: a second kick line 20 s in restarts the 60 s.</li>
 *   <li>party: in a party with Party Message on and his own text: exactly one "pc &lt;text&gt;" (section signs and the
 *       newline stripped), not again when a second kick line restarts the wait; not in a party ("You are not in a party
 *       right now."): none.</li>
 *   <li>stay: a kick line while he stays on SkyBlock (a failed warp) clears after 20 s with no title and no message.</li>
 *   <li>forged / off: player-typed copies, a limbo, or the switch off start nothing.</li>
 *   <li>reload: every setting survives a save and a load; the text is cleaned and capped at 100.</li>
 * </ul>
 */
final class HxKickRejoin1103Cases {

    private static final String CFG = "kickrejoin.KickRejoinConfig";
    private static final String F = "kickrejoin.KickRejoinFeature";
    private static final String TEXT = "HxKick back in a minute";

    private HxKickRejoin1103Cases() {
    }

    static void register(Session s) {
        s.test("1103-kickrejoin-lobby", HxKickRejoin1103Cases::lobby);
        s.test("1103-kickrejoin-restart", HxKickRejoin1103Cases::restart);
        s.test("1103-kickrejoin-party", HxKickRejoin1103Cases::party);
        s.test("1103-kickrejoin-stay", HxKickRejoin1103Cases::stay);
        s.test("1103-kickrejoin-forged", HxKickRejoin1103Cases::forged);
        s.test("1103-kickrejoin-off", HxKickRejoin1103Cases::off);
        s.test("1103-kickrejoin-reload", HxKickRejoin1103Cases::reload);
    }

    // ---- shared -----------------------------------------------------------------------------------------------------

    private static Settings settings(Session c, boolean enabled, boolean party) {
        return new Settings(c).with(CFG, "Enabled", enabled)
                .with(CFG, "ReadyTitle", true)
                .with(CFG, "ReadySound", true)
                .with(CFG, "PartyMessage", party)
                .with(CFG, "PartyText", TEXT);
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

    private static void joinParty(Session c) {
        c.hx().chat("§eYou have joined §b[MVP§c+§b] HxMateA's §eparty!");
        c.waitUntil("PartyTracker lists HxMateA", mc -> {
            @SuppressWarnings("unchecked")
            List<String> mates = (List<String>) Mod.staticCall("leapmenu.PartyTracker", "teammates");
            return mates.contains("HxMateA");
        }, 60);
    }

    private static void leaveParty(Session c) {
        c.hx().chat("§eYou left the party.");
        c.waitUntil("PartyTracker empty", mc -> ((List<?>) Mod.staticCall("leapmenu.PartyTracker", "teammates")).isEmpty(), 60);
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

    private static int partySends(Session c) {
        return c.onClient(mc -> (Integer) Mod.staticCall(F, "partySends"));
    }

    private static void age(Session c, long ms) {
        run(c, () -> Mod.staticCall(F, "testAge", ms));
    }

    private static List<String> pcs(Session c, long head) {
        return c.hx().commands(head).stream().filter(x -> x.startsWith("pc ")).toList();
    }

    // ---- cases ------------------------------------------------------------------------------------------------------

    private static void lobby(Session c) {
        try (Settings set = settings(c, true, false)) {
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
            c.check(left > 50_000 && left <= 60_000, "remaining after the kick " + left + " ms, want ~60 s");
            c.check(title(c) == null, "a title before the wait is over: " + title(c));

            // A refusal mid-wait changes nothing.
            c.hx().chat("§cCannot join SkyBlock for a moment! (Queue join in cooldown)");
            c.ctx().waitTicks(10);
            long mid = remaining(c);
            c.check(mid < left && left - mid < 3000, "a mid-wait refusal moved the timer: " + left + " -> " + mid);

            // Near the end, a refusal changes nothing either (Hypixel's 60 s, whatever it says).
            age(c, mid - 2000);
            c.ctx().waitTicks(5);
            long nearEnd = remaining(c);
            c.check(nearEnd < 2500, "aged timer " + nearEnd);
            c.hx().chat("§cCannot join SkyBlock for a moment! (Queue join in cooldown)");
            c.hx().chat("§cYou tried to rejoin too fast, please try again in a moment!");
            c.ctx().waitTicks(5);
            long afterRefusal = remaining(c);
            c.check(afterRefusal <= nearEnd, "an end refusal moved the timer: " + nearEnd + " -> " + afterRefusal);

            // The end: the title, one sound, the HUD says now.
            c.waitUntil("the rejoin title", mc -> (Boolean) Mod.staticCall(F, "readyFired"), 100);
            c.check("§aRejoin SkyBlock now!".equals(title(c)), "title " + title(c));
            c.ctx().waitTicks(10);
            int sounds = c.onClient(mc -> (Integer) Mod.staticCall(F, "sounds")) - sounds0;
            c.check(sounds == 1, "sounds " + sounds);
            String done = hud(c);
            c.check(done.endsWith("rejoin now!"), "HUD at the end '" + done + "'");

            // Back on SkyBlock: cleared.
            sidebar(c, "template", "hub");
            c.waitUntil("cleared on SkyBlock", mc -> !(Boolean) Mod.staticCall(F, "running"), 200);
            c.check(c.hx().commands(head).isEmpty(), "commands sent with Party Message off: " + c.hx().commands(head));
            c.note(String.format(Locale.ROOT, "HUD '%s' (%d ms left); mid refusal %d ms; end refusals %d -> %d ms; title "
                    + "'%s', %d sound, HUD '%s'; cleared on SkyBlock; no command", first, left, mid, nearEnd, afterRefusal,
                    title(c), sounds, done));
            run(c, () -> Mod.staticCall(F, "testReset"));
        }
    }

    private static void restart(Session c) {
        try (Settings set = settings(c, true, false)) {
            onSkyblock(c);
            toLobby(c);
            c.hx().chat("§cA kick occurred in your connection, so you were put in the SkyBlock lobby!");
            c.waitUntil("the timer running", mc -> (Boolean) Mod.staticCall(F, "running"), 40);
            age(c, 20_000);
            long before = remaining(c);
            c.check(before < 41_000, "aged 20 s: " + before);
            c.hx().chat("§cYou were kicked while joining that server!");
            c.waitUntil("the restart", mc -> (Long) Mod.staticCall(F, "remainingMs") > 55_000, 40);
            long after = remaining(c);
            c.check(after > 55_000 && after <= 60_000, "after the second kick line " + after);
            c.note("kick-to-lobby line, 20 s later a second kick line: " + before + " -> " + after + " ms");
            onSkyblock(c);
        }
    }

    private static void party(Session c) {
        try (Settings set = settings(c, true, true).with(CFG, "PartyText", "§c" + TEXT + "\n!")) {
            // The setter cleans what he typed: no section sign, no newline.
            String stored = c.onClient(mc -> (String) Mod.call(Mod.cfg(CFG), "getPartyText"));
            c.check((TEXT + "!").equals(stored), "stored text '" + stored + "'");

            // In a party: exactly one "pc <text>", not again on a restart.
            onSkyblock(c);
            joinParty(c);
            int sends0 = partySends(c);
            long head = c.hx().head();
            c.hx().chat("§cYou were kicked while joining that server!");
            toLobby(c);
            c.waitUntil("the party line at the server", mc -> !pcs(c, head).isEmpty(), 100);
            c.hx().chat("§cYou were kicked while joining that server!"); // the failed rejoin's own kick: restarts the wait
            c.ctx().waitTicks(60);
            List<String> got = pcs(c, head);
            c.check(got.equals(List.of("pc " + TEXT + "!")), "party lines " + got + ", want exactly [pc " + TEXT + "!]");
            c.check(partySends(c) - sends0 == 1, "partySends " + (partySends(c) - sends0));
            c.check(remaining(c) > 55_000, "the second kick line did not restart the wait: " + remaining(c));
            String inParty = "in a party: " + got + " once, not again on the restart";

            // Not in a party: nothing.
            onSkyblock(c);
            leaveParty(c);
            long head2 = c.hx().head();
            c.hx().chat("§cYou were kicked while joining that server!");
            c.hx().chat("§cYou are not in a party right now.");
            toLobby(c);
            c.ctx().waitTicks(60);
            c.check(running(c), "the timer did not start");
            c.check(pcs(c, head2).isEmpty(), "not in a party, sent " + pcs(c, head2));

            // In a party but Party Message off: nothing.
            onSkyblock(c);
            joinParty(c);
            run(c, () -> Mod.call(Mod.cfg(CFG), "setPartyMessage", false));
            long head3 = c.hx().head();
            c.hx().chat("§cYou were kicked while joining that server!");
            toLobby(c);
            c.ctx().waitTicks(60);
            c.check(pcs(c, head3).isEmpty(), "Party Message off, sent " + pcs(c, head3));
            c.note(inParty + "; not in a party: none; Party Message off: none");
            leaveParty(c);
            onSkyblock(c);
        }
    }

    private static void stay(Session c) {
        try (Settings set = settings(c, true, true)) {
            onSkyblock(c);
            joinParty(c);
            long head = c.hx().head();
            c.hx().chat("§cYou were kicked while joining that server!");
            c.waitUntil("the timer running", mc -> (Boolean) Mod.staticCall(F, "running"), 40);
            c.ctx().waitTicks(40);
            age(c, 21_000);
            c.waitUntil("cleared (never left SkyBlock)", mc -> !(Boolean) Mod.staticCall(F, "running"), 40);
            c.check(title(c) == null, "a title although he never left SkyBlock: " + title(c));
            c.check(hud(c) == null, "the HUD drew on SkyBlock: " + hud(c));
            c.check(pcs(c, head).isEmpty(), "a party line although he never left SkyBlock: " + pcs(c, head));
            c.note("kick line while staying on SkyBlock: cleared after 20 s, no title, no HUD, no party line");
            leaveParty(c);
        }
    }

    private static void forged(Session c) {
        try (Settings set = settings(c, true, true)) {
            onSkyblock(c);
            joinParty(c);
            toLobby(c);
            long head = c.hx().head();
            String[] typed = {
                    "§7[312] §b[MVP§c+§b] HxMateA§f: You were kicked while joining that server!",
                    "§9Party §8> §b[MVP§c+§b] HxMateA§f: A kick occurred in your connection, so you were put in the SkyBlock lobby!",
                    "§dFrom §b[MVP§c+§b] HxMateB§7: You were kicked while joining that server!",
                    "§2Guild > §b[MVP§c+§b] HxMateA §e[Member]§f: An exception occurred in your connection, so you were put in the SkyBlock Lobby!",
                    "§a[VIP] HxMateA§f: You were kicked while joining that server!",
                    "§cYou were spawned in Limbo.",
            };
            for (String line : typed) {
                c.hx().chat(line);
                c.ctx().waitTicks(6);
            }
            c.ctx().waitTicks(40);
            c.check(!running(c), "a typed copy (or a limbo) started the timer");
            c.check(title(c) == null && hud(c) == null, "title " + title(c) + " HUD " + hud(c));
            c.check(pcs(c, head).isEmpty(), "typed copies sent " + pcs(c, head));
            c.note("5 player-typed copies and a limbo: no timer, no HUD, no title, no party line");
            leaveParty(c);
            onSkyblock(c);
        }
    }

    private static void off(Session c) {
        try (Settings set = settings(c, false, true)) {
            onSkyblock(c);
            joinParty(c);
            toLobby(c);
            long head = c.hx().head();
            c.hx().chat("§cYou were kicked while joining that server!");
            c.hx().chat("§cA kick occurred in your connection, so you were put in the SkyBlock lobby!");
            c.ctx().waitTicks(60);
            c.check(!running(c), "off: the timer started");
            c.check(hud(c) == null && title(c) == null, "off: HUD " + hud(c) + " title " + title(c));
            c.check(c.hx().commands(head).isEmpty(), "off: sent " + c.hx().commands(head));
            c.note("switch off: both kick lines -> nothing");
            leaveParty(c);
            onSkyblock(c);
        }
    }

    private static void reload(Session c) {
        Object cfg = c.onClient(mc -> Mod.cfg(CFG));
        Object[] saved = c.onClient(mc -> new Object[]{Mod.call(cfg, "isEnabled"), Mod.call(cfg, "isReadyTitle"),
                Mod.call(cfg, "isReadySound"), Mod.call(cfg, "isPartyMessage"), Mod.call(cfg, "getPartyText")});
        try {
            run(c, () -> {
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setReadyTitle", false);
                Mod.call(cfg, "setReadySound", false);
                Mod.call(cfg, "setPartyMessage", true);
                Mod.call(cfg, "setPartyText", "reload text 1103");
                Mod.call(cfg, "save");
                Mod.staticCall(CFG, "load");
            });
            Object fresh = c.onClient(mc -> Mod.cfg(CFG));
            String got = c.onClient(mc -> Mod.call(fresh, "isEnabled") + " " + Mod.call(fresh, "isReadyTitle") + " "
                    + Mod.call(fresh, "isReadySound") + " " + Mod.call(fresh, "isPartyMessage") + " "
                    + Mod.call(fresh, "getPartyText"));
            c.check("true false false true reload text 1103".equals(got), "after reload: " + got);
            run(c, () -> Mod.call(fresh, "setPartyText", "x".repeat(150)));
            int len = c.onClient(mc -> ((String) Mod.call(fresh, "getPartyText")).length());
            run(c, () -> Mod.call(fresh, "setPartyText", "  "));
            String blank = c.onClient(mc -> (String) Mod.call(fresh, "getPartyText"));
            String def = c.onClient(mc -> (String) Mod.field(CFG, "DEFAULT_PARTY_TEXT"));
            c.check(len == 100, "150 characters stored as " + len);
            c.check(def.equals(blank), "blank stored as '" + blank + "'");
            c.note("save + load: " + got + "; 150 chars -> " + len + "; blank -> default '" + def + "'");
        } finally {
            run(c, () -> {
                Object f = Mod.cfg(CFG);
                Mod.call(f, "setEnabled", saved[0]);
                Mod.call(f, "setReadyTitle", saved[1]);
                Mod.call(f, "setReadySound", saved[2]);
                Mod.call(f, "setPartyMessage", saved[3]);
                Mod.call(f, "setPartyText", saved[4]);
                Mod.call(f, "save");
            });
        }
    }
}

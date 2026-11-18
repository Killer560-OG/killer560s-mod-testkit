package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Party Commands answers "!" lines in officer chat like guild chat (mod officercmd-1118): the line is
 * "Officer > [rank] Name [GuildRank]: !help", the reply goes out as /oc. The channel is OFF by default.
 */
final class HxOfficerCmd1118Cases {

    private static final String PCC = "partycommands.PartyCommandsConfig";
    private static final String OFFICER_LINE = "§3Officer > §b[MVP§c+§b] ";

    private HxOfficerCmd1118Cases() {
    }

    static void register(Session s) {
        s.test("1118-partycmd-officer-reply", HxOfficerCmd1118Cases::reply);
        s.test("1118-partycmd-officer-off", HxOfficerCmd1118Cases::off);
        s.test("1118-partycmd-officer-spoof", HxOfficerCmd1118Cases::spoof);
        s.test("1118-partycmd-officer-command-off", HxOfficerCmd1118Cases::commandOff);
        s.test("1118-partycmd-officer-persist", HxOfficerCmd1118Cases::persist);
    }

    private static Settings settings(Session c, boolean officer, boolean help) {
        Object cfg = c.onClient(mc -> Mod.cfg(PCC));
        Object ch = c.onClient(mc -> Mod.enumValue(PCC + "$Channel", "OFFICER"));
        Object cmd = c.onClient(mc -> Mod.enumValue(PCC + "$Command", "HELP"));
        boolean wasCh = c.onClient(mc -> (Boolean) Mod.call(cfg, "isChannelOn", ch));
        boolean wasCmd = c.onClient(mc -> (Boolean) Mod.call(cfg, "isOn", cmd));
        return new Settings(c).with(PCC, "Enabled", true).custom(() -> {
            Mod.call(cfg, "setChannelOn", ch, officer);
            Mod.call(cfg, "setOn", cmd, help);
        }, () -> {
            Mod.call(cfg, "setChannelOn", ch, wasCh);
            Mod.call(cfg, "setOn", cmd, wasCmd);
        });
    }

    private static void prepare(Session c) {
        c.hx().stub(null, "oc");
        c.hx().stub(null, "gc");
        dungeon(c);
    }

    private static List<String> oc(Session c) {
        return commandsStarting(c, "oc ");
    }

    private static void reply(Session c) {
        try (Settings set = settings(c, true, true)) {
            prepare(c);
            c.hx().chat(OFFICER_LINE + self(c) + " §2[Officer]§f: !help");
            c.waitUntil("an /oc reply", mc -> !oc(c).isEmpty(), 200);
            c.check(oc(c).get(0).startsWith("oc Commands:"), "reply was " + oc(c));
            c.check(commandsStarting(c, "gc ").isEmpty() && commandsStarting(c, "pc ").isEmpty(), "reply left by another channel");
            c.note("officer reply " + oc(c).get(0));
        }
    }

    private static void off(Session c) {
        try (Settings set = settings(c, false, true)) {
            prepare(c);
            c.hx().chat(OFFICER_LINE + self(c) + "§f: !help");
            c.ctx().waitTicks(40);
            c.check(oc(c).isEmpty(), "officer switch OFF still answered " + oc(c));
        }
    }

    private static void spoof(Session c) {
        try (Settings set = settings(c, true, true)) {
            prepare(c);
            // a player's All chat line that only contains the prefix, and a plain all-chat line
            c.hx().chat("§7" + self(c) + "§f: Officer > " + self(c) + ": !help");
            c.hx().chat("§7" + self(c) + "§f: hi Officer > x: !help");
            c.ctx().waitTicks(40);
            c.check(oc(c).isEmpty(), "a spoofed line was answered in officer chat " + oc(c));
        }
    }

    private static void commandOff(Session c) {
        try (Settings set = settings(c, true, false)) {
            prepare(c);
            c.hx().chat(OFFICER_LINE + self(c) + "§f: !help");
            c.ctx().waitTicks(40);
            c.check(oc(c).isEmpty(), "disabled !help still answered " + oc(c));
        }
    }

    private static void persist(Session c) {
        Object cfg = c.onClient(mc -> Mod.cfg(PCC));
        Object ch = c.onClient(mc -> Mod.enumValue(PCC + "$Channel", "OFFICER"));
        boolean was = c.onClient(mc -> (Boolean) Mod.call(cfg, "isChannelOn", ch));
        try {
            c.onClient(mc -> Mod.call(cfg, "setChannelOn", ch, true));
            c.onClient(mc -> Mod.call(cfg, "save"));
            c.onClient(mc -> Mod.staticCall(PCC, "load"));
            Object loaded = c.onClient(mc -> Mod.cfg(PCC));
            c.check(c.onClient(mc -> (Boolean) Mod.call(loaded, "isChannelOn", ch)), "officer switch not saved");
            c.onClient(mc -> Mod.call(cfg, "setChannelOn", ch, false));
            c.onClient(mc -> Mod.call(cfg, "save"));
            c.onClient(mc -> Mod.staticCall(PCC, "load"));
            Object off = c.onClient(mc -> Mod.cfg(PCC));
            c.check(!c.onClient(mc -> (Boolean) Mod.call(off, "isChannelOn", ch)), "officer OFF not saved");
        } finally {
            c.onClient(mc -> Mod.call(cfg, "setChannelOn", ch, was));
            c.onClient(mc -> Mod.call(cfg, "save"));
        }
    }
}

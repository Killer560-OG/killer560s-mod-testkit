package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * killer560's 2026-11-03 "whenever someone joins a party in dungeons have a chat line below their name for me to pv
 * them" (mod pvjoin-1104, dungeonqueue.PvJoinLink). 1104-ui-pvjoin-link: a Party Finder join line and a party join line
 * in a dungeon each add exactly one local "[PV]" line whose click runs "/pv name"; a party join outside a dungeon adds
 * nothing. 1104-ui-pvjoin-forged: a player's chat copy of either text adds nothing. 1104-ui-pvjoin-off: the switch OFF
 * adds nothing (and the default is ON).
 */
final class PvJoinCases {

    static final String[] CASES = {"1104-ui-pvjoin-link", "1104-ui-pvjoin-forged", "1104-ui-pvjoin-off", "1108-ui-pvjoin-whole"};

    private static final String CFG = "dungeonqueue.DungeonQueueConfig";
    private static final String PF = "Party Finder > Zedname joined the dungeon group! (Mage Level 40)";
    private static final String PARTY = "[MVP+] Zedname joined the party.";

    private PvJoinCases() {
    }

    @SuppressWarnings("unchecked")
    private static List<GuiMessage> all(net.minecraft.client.Minecraft mc) {
        return (List<GuiMessage>) Mod.field(dev.testkit.compat.McCompat.chat(mc), "allMessages");
    }

    /** The run_command of the clickable part of a message, or null. */
    private static String clickCommand(Component m) {
        List<Component> parts = new ArrayList<>();
        parts.add(m);
        parts.addAll(m.getSiblings());
        for (Component p : parts) {
            if (p.getStyle().getClickEvent() instanceof ClickEvent.RunCommand rc) {
                return rc.command();
            }
        }
        return null;
    }

    private static void chat(UiCase c, String text) {
        c.onClient(mc -> {
            Mod.staticCall("util.ChatObserver", "onChatComponentAdd", Component.literal(text));
            return null;
        });
        c.ticks(8);
    }

    /** The "[PV]" lines among the chat lines added since {@code before}, as click commands (null = no click). */
    private static List<String> pvLines(UiCase c, int before) {
        return c.onClient(mc -> {
            List<GuiMessage> all = all(mc);
            List<String> out = new ArrayList<>();
            for (int i = Math.min(all.size() - before, all.size()) - 1; i >= 0; i--) {
                Component m = all.get(i).content();
                if (m.getString().contains("[PV]")) {
                    out.add(String.valueOf(clickCommand(m)));
                }
            }
            return out;
        });
    }

    private static int size(UiCase c) {
        return c.onClient(mc -> all(mc).size());
    }

    private static void setup(UiCase c, boolean dungeon, boolean on) {
        c.onClient(mc -> {
            Mod.staticCall("util.SkyblockGate", "setEnabled", false);
            Mod.staticCall("secrets.DungeonState", "setRoomSim", dungeon);
            // A queued-floor detection left by an earlier case (a Group Builder read) also lets a party join through
            // outside a dungeon (PvJoinLink: isInDungeon() || hasDetection()), so the premise needs it cleared.
            Mod.staticCall("autokick.QueuedFloorDetector", "reset");
            Mod.call(Mod.cfg(CFG), "setPvLinkOnJoin", on);
            return null;
        });
    }

    private static void teardown(UiCase c, Object[] gate) {
        c.onClient(mc -> {
            Mod.staticCall("secrets.DungeonState", "setRoomSim", false);
            Mod.call(Mod.cfg(CFG), "setPvLinkOnJoin", true);
            if (gate[0] != null) {
                Mod.staticCall("util.SkyblockGate", "setEnabled", gate[0]);
            }
            return null;
        });
    }

    private static Object[] gateState(UiCase c) {
        return new Object[]{c.onClient(mc -> Mod.staticCall("util.SkyblockGate", "isEnabled"))};
    }

    static void link(UiCase c) {
        Object[] gate = gateState(c);
        try {
            boolean def = c.onClient(mc -> {
                Mod.staticCall("dungeonqueue.DungeonQueueConfig", "load");
                return (Boolean) Mod.call(Mod.cfg(CFG), "isPvLinkOnJoin");
            });
            setup(c, true, true);
            int b = size(c);
            chat(c, PF);
            List<String> got = pvLines(c, b);
            c.note("Party Finder join -> " + got);
            c.check(got.equals(List.of("/pv Zedname")), "a Party Finder join should add one [PV] line running /pv Zedname, got " + got);

            b = size(c);
            chat(c, PARTY);
            got = pvLines(c, b);
            c.note("party join in a dungeon -> " + got);
            c.check(got.equals(List.of("/pv Zedname")), "a party join in a dungeon should add one [PV] line, got " + got);

            setup(c, false, true);
            b = size(c);
            chat(c, PARTY);
            got = pvLines(c, b);
            c.note("party join outside a dungeon -> " + got);
            c.check(got.isEmpty(), "a party join outside a dungeon should add nothing, got " + got);
            // OFF by default since defoff-1104 (killer560 2026-11-04).
            c.check(!def, "PV Link on Join should default OFF");
        } finally {
            teardown(c, gate);
        }
    }

    /** 1108: the click and hover sit on the root of the whole line, so any word of it opens the viewer. */
    static void whole(UiCase c) {
        Object[] gate = gateState(c);
        try {
            setup(c, true, true);
            int b = size(c);
            chat(c, PF);
            List<String> got = c.onClient(mc -> {
                List<GuiMessage> all = all(mc);
                List<String> out = new ArrayList<>();
                for (int i = Math.min(all.size() - b, all.size()) - 1; i >= 0; i--) {
                    Component m = all.get(i).content();
                    if (m.getString().contains("[PV]")) {
                        out.add(m.getStyle().getClickEvent() instanceof ClickEvent.RunCommand rc ? rc.command() : "none");
                        out.add(m.getStyle().getHoverEvent() == null ? "nohover" : "hover");
                        out.add(m.getStyle().isBold() ? "bold" : "plain");
                        out.add(m.getString().contains("Zedname") ? "name" : "noname");
                    }
                }
                return out;
            });
            c.note("root style -> " + got);
            c.check(got.equals(List.of("/pv Zedname", "hover", "bold", "name")),
                    "the whole [PV] line should be one clickable bold component naming the player, got " + got);
        } finally {
            teardown(c, gate);
        }
    }

    static void forged(UiCase c) {
        Object[] gate = gateState(c);
        try {
            setup(c, true, true);
            // The acting proof: the real line does add one, so the forged ones adding none means something.
            int b = size(c);
            chat(c, PF);
            c.check(pvLines(c, b).size() == 1, "the control line (real Party Finder join) added no [PV] line");
            for (String forged : new String[]{"[VIP] Bob: " + PF, "[MVP+] Bob: " + PARTY, "Bob: Zedname joined the party.",
                    "[Guild > Bob] Bob: " + PF, "Party > [VIP] Bob: Zedname joined the party.",
                    "Party Finder > Zedname joined the dungeon group! (Mage Level 40) extra",
                    "Party Finder > Zed name joined the dungeon group! (Mage Level 40)"}) {
                b = size(c);
                chat(c, forged);
                List<String> got = pvLines(c, b);
                c.check(got.isEmpty(), "forged line \"" + forged + "\" added " + got);
            }
            c.note("every forged copy added nothing");
        } finally {
            teardown(c, gate);
        }
    }

    static void off(UiCase c) {
        Object[] gate = gateState(c);
        try {
            setup(c, true, true);
            int b = size(c);
            chat(c, PF);
            c.check(pvLines(c, b).size() == 1, "the control (switch ON) added no [PV] line");
            setup(c, true, false);
            b = size(c);
            chat(c, PF);
            chat(c, PARTY);
            List<String> got = pvLines(c, b);
            c.note("switch OFF -> " + got);
            c.check(got.isEmpty(), "with the switch OFF a join added " + got);
        } finally {
            teardown(c, gate);
        }
    }
}

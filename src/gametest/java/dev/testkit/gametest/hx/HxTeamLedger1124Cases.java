package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Mod idea-teamledger-1124 (idea 4, killer560 "sure good idea"): Teammate Ledger reads the saved run history for one
 * player. 1124-hx-teamledger stores four runs (two finished F7 with Zedname, one unfinished M7 with Zedname, one F7 without
 * him), then asserts the numbers (3 runs, F7 average of the two finished ones, deaths and the most common cause, one
 * unfinished), that the UUID matches after a rename, that a stranger reads "no saved runs", and the chat: a real Party
 * Finder join prints the line, a forged player copy, the switch OFF, the join switch OFF and a stranger print none.
 */
final class HxTeamLedger1124Cases {

    private static final String L = "teamledger.TeammateLedger";
    private static final String STORE = "runsummary.RunHistoryStore";
    private static final String UUID_A = "11111111-2222-4333-8444-555555555555";
    private static final String PF = "Party Finder > Zedname joined the dungeon group! (Mage Level 40)";

    private HxTeamLedger1124Cases() {
    }

    static void register(Session s) {
        s.test("1124-hx-teamledger", HxTeamLedger1124Cases::ledger);
    }

    private static Object member(String uuid, String name, List<String> causes) throws ReflectiveOperationException {
        Class<?> k = Mod.cls("runsummary.RunRecord$PartyMember");
        return k.getDeclaredConstructor(String.class, String.class, String.class, int.class, int.class, int.class, int.class,
                int.class, List.class, List.class, List.class)
                .newInstance(uuid, name, "Mage", 40, 1, 0, 2, causes.size(), List.of(), List.of(), causes);
    }

    private static Object run(String floor, String time, long ms, List<Object> party) throws ReflectiveOperationException {
        Class<?> k = Mod.cls("runsummary.RunRecord");
        java.lang.reflect.RecordComponent[] comps = k.getRecordComponents();
        Object[] args = new Object[comps.length];
        Class<?>[] types = new Class<?>[comps.length];
        for (int i = 0; i < comps.length; i++) {
            Class<?> t = comps[i].getType();
            String n = comps[i].getName();
            types[i] = t;
            args[i] = t == boolean.class ? false : t == int.class ? -1
                    : t == long.class ? (n.equals("totalMs") ? ms : n.equals("chestProfit") ? Long.MIN_VALUE : 0L)
                    : t == List.class ? (n.equals("party") ? party : List.of())
                    : t == String.class ? (n.equals("floor") ? floor : n.equals("hypixelTime") ? time : null) : null;
        }
        return k.getDeclaredConstructor(types).newInstance(args);
    }

    @SuppressWarnings("unchecked")
    private static List<String> chatLines(Session c) {
        return c.onClient(mc -> {
            List<GuiMessage> all = (List<GuiMessage>) Mod.field(dev.testkit.compat.McCompat.chat(mc), "allMessages");
            List<String> out = new ArrayList<>();
            for (GuiMessage g : all) {
                out.add(g.content().getString());
            }
            return out;
        });
    }

    /** The "Name: ... with you" ledger lines added by feeding {@code line}. */
    private static List<String> feed(Session c, String line) {
        int before = chatLines(c).size();
        c.ctx().runOnClient(mc -> Mod.staticCall("util.ChatObserver", "onChatComponentAdd", Component.literal(line)));
        c.ctx().waitTicks(10);
        List<String> now = chatLines(c);
        List<String> out = new ArrayList<>();
        for (String s : now.subList(0, Math.max(0, now.size() - before))) {
            if (s.contains(">> ")) {
                out.add(s);
            }
        }
        return out;
    }

    private static void ledger(Session c) throws Exception {
        boolean gate = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        Object cfg = Mod.field(L, "CFG");
        try {
            c.ctx().runOnClient(mc -> {
                Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "set", "onJoin", true);
            });
            List<Object> withZed = List.of(member("aaaaaaaa-0000-4000-8000-000000000001", "Me", List.of()),
                    member(UUID_A, "Zedname", List.of("was killed by Goldor's lasers")));
            List<Object> withZed2 = List.of(member(UUID_A, "Zedname", List.of("was killed by Goldor's lasers", "fell to their death")));
            List<Object> withZed3 = List.of(member(UUID_A, "Zedname", List.of("was killed by Goldor's lasers")));
            List<Object> without = List.of(member("aaaaaaaa-0000-4000-8000-000000000001", "Me", List.of()));
            List<Object> runs = List.of(run("F7", "5m 0s", 300000L, withZed), run("F7", "6m 0s", 360000L, withZed2),
                    run("M7", null, 100000L, withZed3), run("F7", "5m 0s", 300000L, without));
            c.ctx().runOnClient(mc -> Mod.staticCall(STORE, "setRunsForTest", runs));

            Object s = c.onClient(mc -> Mod.staticCall(L, "summarize", runs, "Zedname", ""));
            String text = c.onClient(mc -> (String) Mod.staticCall(L, "describe", s));
            String avg = c.onClient(mc -> (String) Mod.staticCall("runsummary.RunRecord", "formatTime", 330000L));
            c.note("summary -> " + text);
            c.check(text.equals("3 runs with you, avg " + avg + " F7 (2 runs), 4 deaths (3 to goldor's lasers), 1 unfinished"),
                    "the ledger line read: " + text);

            Object renamed = c.onClient(mc -> Mod.staticCall(L, "summarize", runs, "SomeNewName", UUID_A));
            String t2 = c.onClient(mc -> (String) Mod.staticCall(L, "describe", renamed));
            c.check(t2.equals(text), "a renamed player matched by UUID read: " + t2);
            Object stranger = c.onClient(mc -> Mod.staticCall(L, "summarize", runs, "Nobody", ""));
            c.check(c.onClient(mc -> (String) Mod.staticCall(L, "describe", stranger)).equals("no saved runs with you"),
                    "a stranger did not read 'no saved runs with you'");

            // Chat: the real join prints it (the acting proof), the rest print nothing.
            List<String> got = feed(c, PF);
            c.note("join -> " + got);
            c.check(got.size() == 1 && got.get(0).contains("Zedname: 3 runs with you"), "the Party Finder join printed " + got);
            for (String forged : new String[]{"[VIP] Bob: " + PF, "Party Finder > Nobody joined the dungeon group! (Mage Level 40)"}) {
                List<String> none = feed(c, forged);
                c.check(none.isEmpty(), "\"" + forged + "\" printed " + none);
            }
            c.ctx().runOnClient(mc -> Mod.call(cfg, "set", "onJoin", false));
            List<String> off = feed(c, PF);
            c.check(off.isEmpty(), "the join switch OFF still printed " + off);
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "set", "onJoin", true);
                Mod.call(cfg, "setEnabled", false);
            });
            List<String> disabled = feed(c, PF);
            c.check(disabled.isEmpty(), "the card OFF still printed " + disabled);
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.staticCall(STORE, "clear");
                Mod.call(cfg, "load");
                Mod.staticCall("util.SkyblockGate", "setEnabled", gate);
            });
        }
    }
}

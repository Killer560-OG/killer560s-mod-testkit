package dev.testkit.gametest.ui;

import com.google.gson.JsonObject;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.PacketTrace;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * killer560's 2026-10-18 to-do item 1, the Custom Party Finder (mod branch todo-partyfinder-1018, package {@code lfg}).
 * The relay is offline in the testkit, so its answers are fed through the feature's test hooks, exactly the JSON the
 * relay sends (killer560s-mod-relay test/lfg-smoke.mjs proves the relay's half). Every party command is read off the
 * wire ({@link PacketTrace#tap}), not off the mod's own record.
 * <p>1018-pf-leader-accept: a join request reaches the leader's menu with nothing sent; his real click on Accept sends
 * exactly {@code /party invite <joiner>} and answers the relay; a repeated answer, eight more requests and a burst of
 * accepts stay inside the hard limits; a request whose name is not a Minecraft name is dropped.
 * <p>1018-pf-joiner-accept: a real click on Join asks the relay; forged invite lines (party, all, private chat, another
 * leader, an acceptance for a request he never made) send nothing; the accepting leader's framed Hypixel invite sends
 * {@code /party accept <leader>} once; an invite that beats the relay poll is accepted when the poll lands.
 * <p>1018-pf-dual-list: a Group Builder pick plus Hypixel's queued line lists the party (Dungeons M4, source hypixel);
 * a forged queued line, or Dual-List off, does not; "You left the party." takes it down (a forged copy does not); a full
 * party's answer drops it.
 * <p>1018-pf-requirements: his own stats under a listing's Cata Level stop the Join (no relay call); stats that meet it
 * send it; the leader sees a joiner's checks.
 * <p>1018-pf-nokuudra: the finder has no Kuudra tab, category, requirement, queue detector or dual-list hook (killer560,
 * 2026-10-18: "kuudra should not be implemented yet"); only Dungeons, Diana and Fishing are there.
 * <p>1018-pf-settings: the switch and three options survive save/load and a profile reload, every row has a tooltip, and
 * the tab sits in the Dungeon category with its switch first.
 */
final class PartyFinder1018Cases {

    private static final String F = "lfg.LfgFeature";
    private static final String GATE = "lfg.LfgCommandGate";
    private static final String RELAY = "lfg.LfgRelay";
    private static final String CFG = "lfg.LfgConfig";
    private static final String CAT = "lfg.LfgCategory";

    private PartyFinder1018Cases() {
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static List<String> tapCommands() {
        List<String> sent = new CopyOnWriteArrayList<>();
        PacketTrace.tap = p -> {
            if (p instanceof ServerboundChatCommandPacket cmd) {
                sent.add("/" + cmd.command());
            }
        };
        return sent;
    }

    private static void reset(UiCase c) {
        c.onClient(mc -> {
            Mod.staticCall(F, "resetForTest");
            Mod.staticCall(GATE, "resetForTest");
            Mod.staticCall(RELAY, "clearCallsForTest");
            Object cfg = Mod.cfg(CFG);
            Mod.call(cfg, "setEnabled", true);
            Mod.call(cfg, "setDualList", true);
            Mod.call(cfg, "setAutoAcceptInvite", true);
            Mod.call(cfg, "setCheckRequirements", true);
            Mod.call(cfg, "setLastTab", Mod.enumValue(CAT, "DUNGEONS"));
            if (gateWas == null) {
                gateWas = (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled");
            }
            Mod.staticCall("util.SkyblockGate", "setEnabled", false);
            return null;
        });
    }

    /** "Skyblock Only" as it was before the case (singleplayer is not Skyblock, so the case turns it off). */
    private static Boolean gateWas;

    private static void restoreGate() {
        if (gateWas != null) {
            Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas);
            gateWas = null;
        }
    }

    private static String self(UiCase c) {
        return c.onClient(mc -> mc.player.getGameProfile().name());
    }

    private static void chat(UiCase c, String text) {
        c.onClient(mc -> {
            Mod.staticCall("util.ChatObserver", "onChatComponentAdd", Component.literal(text));
            return null;
        });
        c.ticks(2);
    }

    @SuppressWarnings("unchecked")
    private static List<String> calls(UiCase c) {
        return c.onClient(mc -> (List<String>) Mod.staticCall(RELAY, "calls"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> events(UiCase c) {
        return c.onClient(mc -> (List<String>) Mod.staticCall(F, "events"));
    }

    private static String listingJson(String id, String leader, String activity, String reqs, int size, int max, String source) {
        return "{\"id\":\"" + id + "\",\"leader\":\"" + leader + "\",\"leaderUuid\":\"00000000-0000-0000-0000-000000000001\","
                + "\"verified\":true,\"category\":\"dungeons\",\"activity\":\"" + activity + "\",\"note\":\"s+ only\",\"reqs\":"
                + reqs + ",\"size\":" + size + ",\"max\":" + max + ",\"source\":\"" + source + "\",\"ageMs\":100}";
    }

    private static String request(String id, String joiner) {
        return "{\"id\":\"" + id + "\",\"joiner\":\"" + joiner + "\",\"joinerUuid\":\"x\",\"verified\":true,\"ageMs\":5}";
    }

    private static void me(UiCase c, String json) {
        c.onClient(mc -> {
            Mod.staticCall(F, "onMeForTest", json);
            return null;
        });
        c.ticks(2);
    }

    private static Screen openScreen(UiCase c) {
        c.onClient(mc -> {
            try {
                Constructor<?> k = Mod.cls("lfg.LfgScreen").getConstructor(Screen.class);
                McCompat.setScreen(mc, (Screen) k.newInstance(new Object[]{null}));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            return null;
        });
        c.ticks(4);
        return c.onClient(McCompat::screen);
    }

    /** Every widget on the screen whose plain label is exactly {@code label}. */
    private static List<AbstractWidget> widgets(UiCase c, Screen s, String label) {
        return c.onClient(mc -> {
            List<AbstractWidget> out = new ArrayList<>();
            for (GuiEventListener l : s.children()) {
                if (l instanceof AbstractWidget w && ModScreenDriver.label(w).equals(label)) {
                    out.add(w);
                }
            }
            return out;
        });
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void putStats(String name, double cata, int secrets) {
        try {
            Class<?> rec = Mod.cls("partyfinder.PartyFinderStatsApi$PlayerStats");
            Object st = rec.getDeclaredConstructor(double.class, int.class, double.class, JsonObject.class,
                    JsonObject.class, long.class).newInstance(cata, secrets, 8.0, new JsonObject(), new JsonObject(),
                    System.currentTimeMillis());
            @SuppressWarnings("unchecked")
            Map<String, Object> cache = (Map<String, Object>) Mod.field("partyfinder.PartyFinderStatsApi", "CACHE");
            cache.put(name.toLowerCase(Locale.ROOT), st);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // ---- 1018-pf-leader-accept --------------------------------------------------------------------------------------

    static void leaderAccept(UiCase c) throws Exception {
        List<String> sent = tapCommands();
        try {
            reset(c);
            String me = self(c);
            c.onClient(mc -> {
                Map<String, Integer> reqs = new LinkedHashMap<>();
                reqs.put("cata", 40);
                Object spec = Mod.staticCall(F, "spec", Mod.enumValue(CAT, "DUNGEONS"), "M7", "s+ only", reqs, 5, false);
                Mod.staticCall(F, "list", spec);
                return null;
            });
            String listing = listingJson("aaaaaaaa0001", me, "M7", "{\"cata\":40}", 1, 5, "mod");
            c.onClient(mc -> {
                Mod.staticCall(F, "onUpsertForTest", "{\"listing\":" + listing + "}");
                return null;
            });
            List<String> callsAfterList = calls(c);
            c.check(callsAfterList.stream().anyMatch(s -> s.startsWith("POST /lfg/listing") && s.contains("\"activity\":\"M7\"")
                    && s.contains("\"cata\":40") && s.contains("\"size\":1")), "the listing was not sent to the relay: " + callsAfterList);
            me(c, "{\"listing\":" + listing + ",\"requests\":[" + request("bbbbbbbb0001", "Bob_J") + ","
                    + request("bbbbbbbb0002", "Bad Name") + "," + request("bbbbbbbb0003", "Eve;x") + "],\"outgoing\":[]}");
            int inbox = c.onClient(mc -> ((List<?>) Mod.staticCall(F, "inbox")).size());
            c.note("inbox after a /me with Bob_J and two bad names: " + inbox + "; commands so far " + sent);
            c.check(inbox == 1, "the inbox should hold Bob_J only (the two non-Minecraft names dropped), holds " + inbox);
            c.check(sent.isEmpty(), "a command went before the leader clicked anything: " + sent);

            Screen s = openScreen(c);
            List<AbstractWidget> accepts = widgets(c, s, "Accept");
            c.check(accepts.size() == 1, "the menu should show one Accept button, shows " + accepts.size());
            if (accepts.size() == 1) {
                InvBatchCases.clickWidget(c, s, accepts.get(0));
            }
            c.ticks(3);
            List<String> afterClick = new ArrayList<>(sent);
            List<String> callsAfterClick = calls(c);
            c.note("after the real click: commands " + afterClick + "; relay calls " + callsAfterClick);
            c.check(afterClick.equals(List.of("/party invite Bob_J")), "the click did not send exactly /party invite Bob_J: " + afterClick);
            c.check(callsAfterClick.stream().anyMatch(x -> x.equals("POST /lfg/respond {\"requestId\":\"bbbbbbbb0001\",\"accept\":true}")),
                    "the relay was not told the request was accepted: " + callsAfterClick);

            // The relay has not caught up yet: the same /me again must not bring the button (or a second invite) back.
            me(c, "{\"listing\":" + listing + ",\"requests\":[" + request("bbbbbbbb0001", "Bob_J") + "],\"outgoing\":[]}");
            Screen s2 = c.onClient(McCompat::screen);
            c.ticks(3);
            int againButtons = widgets(c, s2, "Accept").size();
            c.check(againButtons == 0, "an answered request came back with an Accept button");

            // Eight more requests (the relay's cap): nothing is sent until clicked.
            StringBuilder many = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                many.append(i == 0 ? "" : ",").append(request("cccccccc000" + i, "Crowd" + i));
            }
            me(c, "{\"listing\":" + listing + ",\"requests\":[" + many + "],\"outgoing\":[]}");
            int sizeBefore = sent.size();
            c.check(sizeBefore == 1, "eight requests made the client send something: " + sent);
            // A burst of accepts straight away: the 3 s gap lets none through (Bob's invite was just sent).
            List<String> burst = c.onClient(mc -> {
                List<String> why = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    why.add(String.valueOf(Mod.staticCall(F, "accept", "cccccccc000" + i)));
                }
                return why;
            });
            c.ticks(3);
            c.note("burst of 8 accepts within 3 s: " + burst + "; commands " + sent);
            c.check(sent.size() == 1, "a burst of accepts got past the 3 s gap: " + sent);
            // Spaced 3.1 s apart: six invites a minute at most (Bob's is the first of them).
            int burstSent = 0;
            for (int i = 0; i < 8; i++) {
                sleep(3100);
                String id = "cccccccc000" + i;
                String why = c.onClient(mc -> String.valueOf(Mod.staticCall(F, "accept", id)));
                if ("null".equals(why)) {
                    burstSent++;
                }
                c.ticks(1);
            }
            c.ticks(3);
            long invites = sent.stream().filter(x -> x.startsWith("/party invite ")).count();
            c.note("8 accepts 3.1 s apart: " + burstSent + " let through; invites on the wire " + invites + " " + sent);
            c.check(invites == 6, "the six-a-minute cap did not hold: " + invites + " invites in the minute");
            c.check(sent.stream().allMatch(x -> x.matches("^/party invite [A-Za-z0-9_]{1,16}$")), "a malformed command: " + sent);
        } finally {
            PacketTrace.tap = null;
            c.onClient(mc -> {
                Mod.staticCall(F, "resetForTest");
                restoreGate();
                return null;
            });
        }
    }

    // ---- 1018-pf-joiner-accept --------------------------------------------------------------------------------------

    static void joinerAccept(UiCase c) throws Exception {
        List<String> sent = tapCommands();
        try {
            reset(c);
            String alice = listingJson("dddddddd0001", "Alice_L", "F7", "{}", 2, 5, "mod");
            String carl = listingJson("dddddddd0002", "Carl_Z", "M3", "{}", 3, 5, "hypixel");
            c.onClient(mc -> {
                Mod.staticCall(F, "onListingsForTest", "dungeons", "{\"listings\":[" + alice + "," + carl + "]}");
                return null;
            });
            Screen s = openScreen(c);
            List<AbstractWidget> joins = widgets(c, s, "Join");
            c.check(joins.size() == 2, "the Dungeons tab should show two Join buttons, shows " + joins.size());
            if (!joins.isEmpty()) {
                InvBatchCases.clickWidget(c, s, joins.get(0));
            }
            c.ticks(3);
            List<String> cl = calls(c);
            c.note("relay calls after the real Join click: " + cl);
            c.check(cl.contains("POST /lfg/join {\"listingId\":\"dddddddd0001\"}"), "the Join click did not ask the relay: " + cl);
            c.check(sent.isEmpty(), "Join sent a command to the server: " + sent);
            c.onClient(mc -> {
                Mod.staticCall(F, "onJoinReplyForTest", "dddddddd0001",
                        "{\"request\":{\"id\":\"eeeeeeee0001\",\"listingId\":\"dddddddd0001\",\"leader\":\"Alice_L\",\"joiner\":\"x\",\"state\":\"pending\",\"ageMs\":0}}");
                return null;
            });

            // Forgeries while pending: other players' lines carrying the invite text, and a real-shaped invite from a
            // leader he never asked.
            for (String forged : new String[]{
                    "Party > [VIP] Eve: [MVP+] Alice_L has invited you to join their party!",
                    "[VIP] Eve: Alice_L has invited you to join their party!",
                    "[123] [MVP+] Eve: Alice_L has invited you to join their party!",
                    "From [MVP+] Eve: Alice_L has invited you to join their party!",
                    "Guild > Eve: Alice_L has invited you to join their party!",
                    "[MVP+] Eve_T has invited you to join their party!"}) {
                chat(c, forged);
            }
            c.check(sent.isEmpty(), "a forged or unasked invite sent something while the request was pending: " + sent);
            // The relay says a request he never made was accepted by Eve_T; Eve_T's real invite must still send nothing.
            me(c, "{\"listing\":null,\"requests\":[],\"outgoing\":["
                    + "{\"id\":\"eeeeeeee0001\",\"listingId\":\"dddddddd0001\",\"leader\":\"Alice_L\",\"category\":\"dungeons\",\"activity\":\"F7\",\"state\":\"pending\",\"ageMs\":10},"
                    + "{\"id\":\"ffffffff0009\",\"listingId\":\"dddddddd0009\",\"leader\":\"Eve_T\",\"category\":\"dungeons\",\"activity\":\"F7\",\"state\":\"accepted\",\"ageMs\":10}]}");
            chat(c, "[MVP+] Eve_T has invited you to join their party!");
            c.check(sent.isEmpty(), "an acceptance for a request he never made armed an accept: " + sent);

            // Alice accepts: her framed invite is accepted once.
            me(c, "{\"listing\":null,\"requests\":[],\"outgoing\":["
                    + "{\"id\":\"eeeeeeee0001\",\"listingId\":\"dddddddd0001\",\"leader\":\"Alice_L\",\"category\":\"dungeons\",\"activity\":\"F7\",\"state\":\"accepted\",\"ageMs\":900}]}");
            c.check(sent.isEmpty(), "the acceptance alone sent something (no invite has arrived): " + sent);
            String framed = "-----------------------------------------------------\n[MVP+] Alice_L has invited you to join their party!\n"
                    + "You have 60 seconds to accept. Click here to join!\n-----------------------------------------------------";
            chat(c, framed);
            c.ticks(2);
            List<String> afterInvite = new ArrayList<>(sent);
            sleep(5200);
            chat(c, framed);
            chat(c, "[MVP+] Alice_L has invited you to join their party!");
            c.note("after Alice's framed invite: " + afterInvite + "; after two more of her invites: " + sent);
            c.check(afterInvite.equals(List.of("/party accept Alice_L")), "Alice's invite was not accepted exactly once: " + afterInvite);
            c.check(sent.equals(List.of("/party accept Alice_L")), "a second invite from Alice was accepted again: " + sent);

            // The race: Carl's invite arrives before the relay poll says he accepted.
            Screen s2 = openScreen(c);
            List<AbstractWidget> joins2 = widgets(c, s2, "Join");
            c.check(joins2.size() == 1, "Carl's listing should still offer Join (Alice's shows Accepted): " + joins2.size());
            sleep(2100);
            if (!joins2.isEmpty()) {
                InvBatchCases.clickWidget(c, s2, joins2.get(0));
            }
            c.ticks(3);
            c.onClient(mc -> {
                Mod.staticCall(F, "onJoinReplyForTest", "dddddddd0002",
                        "{\"request\":{\"id\":\"eeeeeeee0002\",\"listingId\":\"dddddddd0002\",\"leader\":\"Carl_Z\",\"joiner\":\"x\",\"state\":\"pending\",\"ageMs\":0}}");
                return null;
            });
            sleep(5200);
            chat(c, "[VIP] Carl_Z has invited you to join their party!");
            int beforePoll = sent.size();
            me(c, "{\"listing\":null,\"requests\":[],\"outgoing\":["
                    + "{\"id\":\"eeeeeeee0002\",\"listingId\":\"dddddddd0002\",\"leader\":\"Carl_Z\",\"category\":\"dungeons\",\"activity\":\"M3\",\"state\":\"accepted\",\"ageMs\":300}]}");
            c.note("Carl: before the poll " + beforePoll + " command(s), after " + sent);
            c.check(beforePoll == 1, "Carl's invite was accepted before the relay said he accepted");
            c.check(sent.equals(List.of("/party accept Alice_L", "/party accept Carl_Z")), "Carl's early invite was not accepted when the poll landed: " + sent);
            c.note("events: " + events(c));
        } finally {
            PacketTrace.tap = null;
            c.onClient(mc -> {
                Mod.staticCall(F, "resetForTest");
                restoreGate();
                return null;
            });
        }
    }

    // ---- 1018-pf-dual-list ------------------------------------------------------------------------------------------

    static void dualList(UiCase c) throws Exception {
        List<String> sent = tapCommands();
        String queued = "Party Finder > Your party has been queued in the dungeon finder!";
        try {
            reset(c);
            c.onClient(mc -> {
                Mod.staticCall("autokick.QueuedFloorDetector", "reset");
                Mod.staticCall("autokick.QueuedFloorDetector", "onBuilderItems",
                        List.of("Currently Selected: Master Mode The Catacombs"), List.of("Currently Selected: Floor IV"));
                return null;
            });
            // Forged queued lines first.
            chat(c, "[VIP] Eve: Party Finder > Your party has been queued in the dungeon finder!");
            chat(c, "Party > [MVP+] Eve: " + queued);
            String afterForged = c.onClient(mc -> (String) Mod.staticCall(F, "wantedForTest"));
            // Dual-List off: the real line lists nothing.
            c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setDualList", false);
                return null;
            });
            chat(c, queued);
            String afterOff = c.onClient(mc -> (String) Mod.staticCall(F, "wantedForTest"));
            c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setDualList", true);
                return null;
            });
            sleep(400); // ChatObserver drops an identical line within 250 ms as a duplicate
            chat(c, queued);
            String wanted = c.onClient(mc -> (String) Mod.staticCall(F, "wantedForTest"));
            List<String> cl = calls(c);
            c.note("forged: " + afterForged + "; Dual-List off: " + afterOff + "; on: " + wanted + "; relay calls " + cl);
            c.check(afterForged == null, "a forged queued line listed the party: " + afterForged);
            c.check(afterOff == null, "Dual-List off still listed the party: " + afterOff);
            c.check(wanted != null && wanted.contains("\"category\":\"dungeons\"") && wanted.contains("\"activity\":\"M4\"")
                    && wanted.contains("\"source\":\"hypixel\""), "the Hypixel queue was not dual-listed as Dungeons M4: " + wanted);
            c.check(cl.stream().anyMatch(x -> x.startsWith("POST /lfg/listing") && x.contains("\"activity\":\"M4\"")),
                    "the dual listing never reached the relay call: " + cl);

            // Ending: a forged leave line keeps it, the real one removes it.
            chat(c, "[VIP] Eve: You left the party.");
            String afterForgedLeave = c.onClient(mc -> (String) Mod.staticCall(F, "wantedForTest"));
            chat(c, "You left the party.");
            String afterLeave = c.onClient(mc -> (String) Mod.staticCall(F, "wantedForTest"));
            List<String> cl2 = calls(c);
            c.check(afterForgedLeave != null, "a forged 'You left the party.' took the listing down");
            c.check(afterLeave == null && cl2.contains("DELETE /lfg/listing"), "leaving the party did not unlist: " + afterLeave + " " + cl2);

            // Full: the relay answers listing null, removed full.
            sleep(400); // past ChatObserver's 250 ms duplicate window
            chat(c, queued); // the builder pick is still M4
            sleep(3600);
            String relisted = c.onClient(mc -> (String) Mod.staticCall(F, "wantedForTest"));
            c.onClient(mc -> {
                Mod.staticCall(F, "onUpsertForTest", "{\"listing\":null,\"removed\":\"full\"}");
                return null;
            });
            String afterFull = c.onClient(mc -> (String) Mod.staticCall(F, "wantedForTest"));
            c.check(relisted != null && afterFull == null, "a full party's listing was kept: " + relisted + " -> " + afterFull);
            c.check(sent.isEmpty(), "dual listing sent a command to the server: " + sent);
        } finally {
            PacketTrace.tap = null;
            c.onClient(mc -> {
                Mod.staticCall(F, "resetForTest");
                Mod.staticCall("autokick.QueuedFloorDetector", "reset");
                restoreGate();
                return null;
            });
        }
    }

    // ---- 1018-pf-requirements ---------------------------------------------------------------------------------------

    static void requirements(UiCase c) throws Exception {
        List<String> sent = tapCommands();
        try {
            reset(c);
            String me = self(c);
            c.onClient(mc -> {
                putStats(me, 35.4, 3000);
                putStats("Strong_J", 45.0, 9000);
                return null;
            });
            String hard = listingJson("aaaabbbb0001", "Alice_L", "M7", "{\"cata\":40,\"secrets\":2000}", 2, 5, "mod");
            String easy = listingJson("aaaabbbb0002", "Carl_Z", "F7", "{\"cata\":30,\"secrets\":2000}", 2, 5, "mod");
            c.onClient(mc -> {
                Mod.staticCall(F, "onListingsForTest", "dungeons", "{\"listings\":[" + hard + "," + easy + "]}");
                return null;
            });
            Screen s = openScreen(c);
            List<AbstractWidget> joins = widgets(c, s, "Join");
            c.check(joins.size() == 2, "two Join buttons expected, " + joins.size());
            if (joins.size() == 2) {
                InvBatchCases.clickWidget(c, s, joins.get(0));
            }
            c.ticks(3);
            List<String> afterHard = calls(c);
            sleep(2100);
            Screen s2 = c.onClient(McCompat::screen);
            List<AbstractWidget> joins2 = widgets(c, s2, "Join");
            if (joins2.size() == 2) {
                InvBatchCases.clickWidget(c, s2, joins2.get(1));
            }
            c.ticks(3);
            List<String> afterEasy = calls(c);
            c.note("calls after Join on cata 40 (he has 35): " + afterHard + "; after cata 30: " + afterEasy);
            c.check(afterHard.stream().noneMatch(x -> x.startsWith("POST /lfg/join")), "Join went out below the Cata Level requirement");
            c.check(afterEasy.contains("POST /lfg/join {\"listingId\":\"aaaabbbb0002\"}"), "Join did not go out when he meets the requirements");
            String checks = c.onClient(mc -> {
                Map<String, Integer> reqs = new LinkedHashMap<>();
                reqs.put("cata", 40);
                reqs.put("secrets", 10000);
                List<?> list = (List<?>) Mod.staticCall("lfg.LfgRequirements", "check", Mod.enumValue(CAT, "DUNGEONS"), reqs, "Strong_J");
                List<String> out = new ArrayList<>();
                for (Object o : list) {
                    out.add(Mod.call(o, "result") + " " + Mod.call(o, "key"));
                }
                return String.join(", ", out);
            });
            c.note("Strong_J (45, 9000 secrets) against cata 40, secrets 10000: " + checks);
            c.check(checks.equals("MET cata, UNMET secrets"), "requirement checks wrong: " + checks);
            c.check(sent.isEmpty(), "a command went out: " + sent);
        } finally {
            PacketTrace.tap = null;
            c.onClient(mc -> {
                Mod.staticCall(F, "resetForTest");
                restoreGate();
                return null;
            });
        }
    }

    // ---- 1018-pf-nokuudra -------------------------------------------------------------------------------------------

    static void noKuudra(UiCase c) throws Exception {
        try {
            reset(c);
            List<String> cats = c.onClient(mc -> {
                List<String> out = new ArrayList<>();
                for (Object o : Mod.cls(CAT).getEnumConstants()) {
                    out.add(((Enum<?>) o).name());
                }
                return out;
            });
            c.note("categories: " + cats);
            c.check(cats.equals(List.of("DUNGEONS", "DIANA", "FISHING")), "the finder's categories are not exactly Dungeons, Diana, Fishing: " + cats);
            Screen s = openScreen(c);
            List<String> tabs = c.onClient(mc -> {
                List<String> out = new ArrayList<>();
                for (GuiEventListener l : s.children()) {
                    if (l instanceof AbstractWidget w) {
                        String t = ModScreenDriver.label(w);
                        if (t.equals("Kuudra") || t.equals("Dungeons") || t.equals("Diana") || t.equals("Fishing")) {
                            out.add(t);
                        }
                    }
                }
                return out;
            });
            c.note("tab buttons on the menu: " + tabs);
            c.check(tabs.equals(List.of("Dungeons", "Diana", "Fishing")), "the menu's tab buttons are not Dungeons, Diana, Fishing: " + tabs);
            boolean detector = c.onClient(mc -> {
                try {
                    Class.forName("com.killer560.hub.lfg.KuudraQueueDetector");
                    return true;
                } catch (ClassNotFoundException e) {
                    return false;
                }
            });
            c.check(!detector, "KuudraQueueDetector still exists");
            boolean hook = c.onClient(mc -> {
                for (java.lang.reflect.Method m : Mod.cls(F).getDeclaredMethods()) {
                    if (m.getName().toLowerCase(Locale.ROOT).contains("kuudra")) {
                        return true;
                    }
                }
                return false;
            });
            c.check(!hook, "LfgFeature still has a Kuudra method");
            String tip = c.onClient(mc -> String.valueOf(Mod.staticCall("gui.SettingTooltips", "describeScoped", "Custom Party Finder", "Dungeon", "Dual-List Hypixel Queues: ON")));
            c.check(!tip.toLowerCase(Locale.ROOT).contains("kuudra"), "the dual-list tooltip still mentions Kuudra: " + tip);
        } finally {
            c.onClient(mc -> {
                Mod.staticCall(F, "resetForTest");
                restoreGate();
                return null;
            });
        }
    }

    // ---- 1022-pf-dropdown / 1022-pf-no-label (mod pf-dropdown-1022) -------------------------------------------------

    private static final String DD = "gui.DropdownWidget";

    /** The Dungeons tab, entered by a real click on its start-screen button. */
    private static Screen openDungeons(UiCase c) {
        Screen s = openScreen(c);
        List<AbstractWidget> dungeons = widgets(c, s, "Dungeons");
        c.check(dungeons.size() == 1, "the start screen should have one Dungeons button, has " + dungeons.size());
        if (!dungeons.isEmpty()) {
            InvBatchCases.clickWidget(c, s, dungeons.get(0));
        }
        c.ticks(2);
        return c.onClient(McCompat::screen);
    }

    private static boolean isOpen(UiCase c, AbstractWidget d) {
        return c.onClient(mc -> (Boolean) Mod.call(d, "isOpen"));
    }

    /**
     * 1022-pf-dropdown: every choice on the Dungeons tab is a drop-down (no cycling button left); a real click opens the
     * "Show" list under its button, a real click on "only parties that meet my requirements" sets Meets Only, saves it and
     * closes the list; a click outside and Escape each close an open list with nothing changed and the menu still open;
     * no click on the list reached the Join buttons under it.
     */
    static void dropdown(UiCase c) throws Exception {
        Path file = c.onClient(mc -> (Path) Mod.field(CFG, "CONFIG_PATH"));
        byte[] saved = java.nio.file.Files.exists(file) ? java.nio.file.Files.readAllBytes(file) : null;
        try {
            reset(c);
            c.onClient(mc -> {
                Object cfg = Mod.cfg(CFG);
                Mod.call(cfg, "setMeetsOnly", false);
                Mod.call(cfg, "setHideFull", false);
                Mod.call(cfg, "setSort", Mod.enumValue("lfg.LfgFilter$Sort", "NEWEST"));
                Mod.call(cfg, "setActivityFilter", Mod.enumValue(CAT, "DUNGEONS"), "");
                Mod.call(cfg, "save");
                String a = listingJson("abab00000001", "Alice_L", "F7", "{}", 2, 5, "mod");
                String b = listingJson("abab00000002", "Carl_Z", "M3", "{}", 3, 5, "mod");
                Mod.staticCall(F, "onListingsForTest", "dungeons", "{\"listings\":[" + a + "," + b + "]}");
                return null;
            });
            Screen s = openDungeons(c);
            Class<?> dd = c.onClient(mc -> Mod.cls(DD));
            List<String> kinds = c.onClient(mc -> {
                List<String> out = new ArrayList<>();
                for (GuiEventListener l : s.children()) {
                    if (l instanceof AbstractWidget w) {
                        boolean cyc = false;
                        try {
                            cyc = Boolean.TRUE.equals(Mod.call(w, "isCycling"));
                        } catch (Throwable ignored) {
                            // not a SettingsButtonWidget
                        }
                        if (dd.isInstance(w) || cyc) {
                            out.add((dd.isInstance(w) ? "dropdown " : "CYCLING ") + ModScreenDriver.label(w));
                        }
                    }
                }
                return out;
            });
            c.note("choice controls on the Dungeons tab: " + kinds);
            c.check(kinds.stream().noneMatch(k -> k.startsWith("CYCLING")), "a cycling button is left on the tab: " + kinds);
            c.check(kinds.equals(List.of("dropdown Floor: any", "dropdown All parties", "dropdown Full: shown", "dropdown Sort: Newest")),
                    "the tab's drop-downs are not Floor, Show, Full and Sort: " + kinds);

            List<AbstractWidget> show = widgets(c, s, "All parties");
            c.check(show.size() == 1 && dd.isInstance(show.get(0)), "no 'All parties' drop-down");
            if (show.size() != 1) {
                return;
            }
            AbstractWidget meets = show.get(0);
            List<String> callsBefore = calls(c);

            // Open, then pick the second choice by a real click on it.
            InvBatchCases.clickWidget(c, s, meets);
            boolean opened = isOpen(c, meets);
            int[] list = c.onClient(mc -> (int[]) Mod.call(meets, "listBounds"));
            @SuppressWarnings("unchecked")
            List<String> labels = c.onClient(mc -> (List<String>) Mod.call(meets, "optionLabels"));
            int[] opt = c.onClient(mc -> (int[]) Mod.call(meets, "optionBounds", 1));
            c.note("after one click: open " + opened + ", list " + java.util.Arrays.toString(list) + ", choices " + labels
                    + ", button at y " + meets.getY() + "+" + meets.getHeight());
            c.check(opened, "a click on the drop-down did not open its list");
            c.check(labels.equals(List.of("Show: all parties", "Show: only parties that meet my requirements")),
                    "the Show choices are not written as a selection: " + labels);
            c.check(list != null && list[1] == meets.getY() + meets.getHeight(), "the list does not open right under its button");
            c.check(opt != null, "the second choice is not in view");
            if (opt == null) {
                return;
            }
            InvBatchCases.click(c, s, opt[0] + opt[2] / 2.0, opt[1] + opt[3] / 2.0);
            Object[] after = c.onClient(mc -> new Object[]{Mod.call(Mod.cfg(CFG), "isMeetsOnly"), Mod.call(meets, "isOpen"),
                    ModScreenDriver.label(meets), McCompat.screen(mc) == s});
            c.note("after the click on 'only parties that meet my requirements': meetsOnly " + after[0] + ", open " + after[1]
                    + ", button '" + after[2] + "', same screen " + after[3]);
            c.check(Boolean.TRUE.equals(after[0]), "picking 'only parties that meet my requirements' did not set Meets Only");
            c.check(Boolean.FALSE.equals(after[1]), "the list stayed open after a choice");
            c.check("Reqs I meet".equals(after[2]), "the button does not show the new choice: " + after[2]);
            c.check(Boolean.TRUE.equals(after[3]), "the menu was rebuilt or closed by the choice");

            // A click outside closes without a change.
            InvBatchCases.clickWidget(c, s, meets);
            boolean reopened = isOpen(c, meets);
            int[] panel = c.onClient(mc -> (int[]) Mod.call(s, "themedPanelBounds"));
            InvBatchCases.click(c, s, panel[0] + panel[2] / 2.0, panel[1] + 8); // the header, no widget there
            Object[] outside = c.onClient(mc -> new Object[]{Mod.call(Mod.cfg(CFG), "isMeetsOnly"), Mod.call(meets, "isOpen")});
            c.note("re-opened " + reopened + "; after a click on the header: meetsOnly " + outside[0] + ", open " + outside[1]);
            c.check(reopened, "a second click did not open the list again");
            c.check(Boolean.TRUE.equals(outside[0]) && Boolean.FALSE.equals(outside[1]), "a click outside changed the choice or left the list open");

            // Escape closes the list, not the menu.
            InvBatchCases.clickWidget(c, s, meets);
            boolean third = isOpen(c, meets);
            c.ctx().getInput().pressKey(256);
            c.ticks(2);
            Object[] esc = c.onClient(mc -> new Object[]{Mod.call(Mod.cfg(CFG), "isMeetsOnly"), Mod.call(meets, "isOpen"),
                    McCompat.screen(mc) == s});
            c.note("opened " + third + "; after Escape: meetsOnly " + esc[0] + ", open " + esc[1] + ", menu still up " + esc[2]);
            c.check(third && Boolean.FALSE.equals(esc[1]), "Escape did not close the list");
            c.check(Boolean.TRUE.equals(esc[0]), "Escape changed the choice");
            c.check(Boolean.TRUE.equals(esc[2]), "Escape closed the whole menu instead of the list");

            // Screenshots of the two longest lists open over the listing rows (the list must cover the Join buttons).
            for (String which : new String[]{"Floor: any", "Sort: Newest"}) {
                List<AbstractWidget> dw = widgets(c, s, which);
                if (dw.size() == 1) {
                    InvBatchCases.clickWidget(c, s, dw.get(0));
                    int[] lb = c.onClient(mc -> (int[]) Mod.call(dw.get(0), "listBounds"));
                    c.ticks(2);
                    Path shot = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name() + "-" + which.substring(0, 4).toLowerCase(Locale.ROOT)));
                    c.note("'" + which + "' open, list " + java.util.Arrays.toString(lb) + ", screenshot " + shot);
                    c.ctx().getInput().pressKey(256);
                    c.ticks(2);
                    c.check(!isOpen(c, dw.get(0)), "Escape left '" + which + "' open");
                }
            }

            // The choice was saved: a fresh load from the file reads it.
            boolean onDisk = c.onClient(mc -> {
                Mod.staticCall(CFG, "load");
                return (Boolean) Mod.call(Mod.cfg(CFG), "isMeetsOnly");
            });
            c.check(onDisk, "Meets Only was not saved to the config file");
            List<String> callsAfter = calls(c);
            c.check(callsAfter.stream().skip(callsBefore.size()).noneMatch(x -> x.startsWith("POST /lfg/join")),
                    "a click on the list reached a Join button: " + callsAfter);
        } finally {
            if (saved == null) {
                java.nio.file.Files.deleteIfExists(file);
            } else {
                java.nio.file.Files.write(file, saved);
            }
            c.onClient(mc -> {
                Mod.staticCall(CFG, "load");
                Mod.staticCall(F, "resetForTest");
                restoreGate();
                McCompat.setScreen(mc, null);
                return null;
            });
        }
    }

    /** 1022-pf-no-label: with no listing live the header's top-right draws nothing (it said "/pf"); "Listing..." and
     *  "Listed: ..." still show; the /pf and /partyfinder commands are still registered. */
    static void noLabel(UiCase c) throws Exception {
        try {
            reset(c);
            Screen s = openScreen(c);
            c.ticks(2);
            String start = c.onClient(mc -> (String) Mod.call(s, "headerRightForTest"));
            Screen d = openDungeons(c);
            c.ticks(2);
            String tab = c.onClient(mc -> (String) Mod.call(d, "headerRightForTest"));
            String me = self(c);
            c.onClient(mc -> {
                Object spec = Mod.staticCall(F, "spec", Mod.enumValue(CAT, "DUNGEONS"), "M7", "", new LinkedHashMap<String, Integer>(), 5, false);
                Mod.staticCall(F, "list", spec);
                return null;
            });
            c.ticks(2);
            String listing = c.onClient(mc -> (String) Mod.call(McCompat.screen(mc), "headerRightForTest"));
            String json = listingJson("acac00000001", me, "M7", "{}", 1, 5, "mod");
            c.onClient(mc -> {
                Mod.staticCall(F, "onUpsertForTest", "{\"listing\":" + json + "}");
                return null;
            });
            me(c, "{\"listing\":" + json + ",\"requests\":[],\"outgoing\":[]}");
            c.ticks(2);
            String listed = c.onClient(mc -> (String) Mod.call(McCompat.screen(mc), "headerRightForTest"));
            boolean commands = c.onClient(mc -> {
                var d2 = net.fabricmc.fabric.impl.command.client.ClientCommandInternals.getActiveDispatcher();
                return d2 != null && d2.getRoot().getChild("pf") != null && d2.getRoot().getChild("partyfinder") != null;
            });
            c.note("top-right: start '" + start + "', Dungeons tab '" + tab + "', listing '" + listing + "', listed '" + listed
                    + "'; /pf and /partyfinder registered " + commands);
            c.check("".equals(start), "the start screen's top-right is not empty: '" + start + "'");
            c.check("".equals(tab), "the Dungeons tab's top-right is not empty: '" + tab + "'");
            c.check("Listing...".equals(listing), "a listing on its way does not say Listing...: '" + listing + "'");
            c.check(listed != null && listed.startsWith("Listed: Dungeons M7"), "a live listing does not say Listed: '" + listed + "'");
            c.check(commands, "/pf or /partyfinder is no longer registered");
        } finally {
            c.onClient(mc -> {
                Mod.staticCall(F, "resetForTest");
                restoreGate();
                McCompat.setScreen(mc, null);
                return null;
            });
        }
    }

    // ---- 1018-pf-settings -------------------------------------------------------------------------------------------

    static void settings(UiCase c) throws Exception {
        Path file = c.onClient(mc -> (Path) Mod.field(CFG, "CONFIG_PATH"));
        byte[] saved = java.nio.file.Files.exists(file) ? java.nio.file.Files.readAllBytes(file) : null;
        try {
            Object[] back = c.onClient(mc -> {
                Object cfg = Mod.cfg(CFG);
                Mod.call(cfg, "setEnabled", false);
                Mod.call(cfg, "setDualList", false);
                Mod.call(cfg, "setAutoAcceptInvite", false);
                Mod.call(cfg, "setCheckRequirements", false);
                Mod.call(cfg, "save");
                Mod.staticCall(CFG, "load");
                Object r = Mod.cfg(CFG);
                Object[] a = {Mod.call(r, "isEnabledRaw"), Mod.call(r, "isDualList"), Mod.call(r, "isAutoAcceptInvite"),
                        Mod.call(r, "isCheckRequirements")};
                Mod.call(r, "setEnabled", true);
                Mod.call(r, "save");
                try {
                    java.nio.file.Files.writeString(file, "{\"enabled\":false}");
                } catch (java.io.IOException e) {
                    throw new AssertionError(e);
                }
                Mod.staticCall("profiles.ProfileManager", "reloadAllConfigs");
                Object p = Mod.cfg(CFG);
                return new Object[]{a[0], a[1], a[2], a[3], Mod.call(p, "isEnabledRaw"), Mod.call(p, "isDualList")};
            });
            c.note("after save/load: " + java.util.Arrays.toString(back));
            c.check(Boolean.FALSE.equals(back[0]) && Boolean.FALSE.equals(back[1]) && Boolean.FALSE.equals(back[2])
                    && Boolean.FALSE.equals(back[3]), "a setting did not survive save/load");
            c.check(Boolean.FALSE.equals(back[4]) && Boolean.TRUE.equals(back[5]),
                    "a profile reload did not re-read the file (enabled false, dualList default true): " + back[4] + " " + back[5]);
            List<String> missing = c.onClient(mc -> {
                List<String> out = new ArrayList<>();
                for (String label : new String[]{"Custom Party Finder: ON", "Open Party Finder", "Dual-List Hypixel Queues: ON",
                        "Auto Accept Invite: ON", "Check Requirements: ON"}) {
                    Object d = Mod.staticCall("gui.SettingTooltips", "describeScoped", "Custom Party Finder", "Dungeon", label);
                    if (d == null) {
                        out.add(label);
                    }
                }
                return out;
            });
            c.check(missing.isEmpty(), "rows without a tooltip: " + missing);
            String firstRow = c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setEnabled", true);
                try {
                    Object tab = Mod.cls("gui.tab.CustomPartyFinderTab").getConstructor().newInstance();
                    List<?> built = (List<?>) Mod.call(tab, "buildWidgets", 0, 0, 300, (Runnable) () -> { });
                    return ModScreenDriver.label((AbstractWidget) built.get(0)) + " | " + built.size() + " rows";
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            c.note("tab: " + firstRow);
            c.check(firstRow.startsWith("Custom Party Finder: ON") && firstRow.endsWith("5 rows"), "the tab's first row is not its switch: " + firstRow);
            boolean inDungeon = c.onClient(mc -> {
                try {
                    Object folder = Mod.cls("gui.tab.DungeonTab").getConstructor().newInstance();
                    return String.valueOf(Mod.field(folder, "subTabs")).contains("CustomPartyFinderTab");
                } catch (Throwable t) {
                    return String.valueOf(t).contains("CustomPartyFinderTab");
                }
            });
            c.check(inDungeon, "the tab is not in the Dungeon category");
        } finally {
            if (saved == null) {
                java.nio.file.Files.deleteIfExists(file);
            } else {
                java.nio.file.Files.write(file, saved);
            }
            c.onClient(mc -> {
                Mod.staticCall(CFG, "load");
                return null;
            });
        }
    }
}

package dev.testkit.gametest.ui;

import com.google.gson.JsonObject;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.PacketTrace;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
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
 * <p>2026-10-18 fixes (mod branch todo-pf-fix-1018): 1018-pf-autoaccept-leader (Auto Accept Requests invites a joiner who
 * meets the listing with no click, not one under it or with unknown stats); 1018-pf-cata-gate-leader / -joiner (under the
 * Cata Level: rejected in the leader's menu with no Accept and the relay told declined; his own stats unknown: Join never
 * reaches the relay); 1018-pf-pb-gate (S+ PB 5:30 against 5:00 rejected, 4:40 accepted); 1018-pf-search / -filter / -sort;
 * 1018-pf-start-screen (start screen, Dungeons only, Back); 1018-pf-misc.
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
            if (Mod.has("lfg.LfgFilter")) {
                // The older cases click Accept themselves: Auto Accept Requests (2026-10-18) would invite first.
                Mod.call(cfg, "setAutoAcceptRequests", false);
                Mod.call(cfg, "setSort", Mod.enumValue("lfg.LfgFilter$Sort", "NEWEST"));
                Mod.call(cfg, "setMeetsOnly", false);
                Mod.call(cfg, "setHideFull", true);
                Mod.call(cfg, "setActivityFilter", Mod.enumValue(CAT, "DUNGEONS"), "");
            }
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

    /** /cpf's screen: since 2026-10-18 the start screen, where he picks the activity. */
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

    /** Opens /cpf's screen and really clicks {@code label} ("Dungeons", "Misc"...) on the start screen. */
    private static Screen openActivity(UiCase c, String label) {
        Screen s = openScreen(c);
        List<AbstractWidget> b = widgets(c, s, label);
        c.check(b.size() == 1, "the start screen should have one " + label + " button, has " + b.size());
        if (!b.isEmpty()) {
            InvBatchCases.clickWidget(c, s, b.get(0));
        }
        c.ticks(3);
        return c.onClient(McCompat::screen);
    }

    private static Object tabOf(UiCase c, Screen s) {
        return c.onClient(mc -> Mod.call(s, "tabForTest"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> shownLeaders(UiCase c, Screen s) {
        return c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            for (Object l : (List<Object>) Mod.call(s, "shownForTest")) {
                out.add(String.valueOf(Mod.call(l, "leader")));
            }
            return out;
        });
    }

    private static String listingJson(String id, String leader, String category, String activity, String reqs, String note,
                                      int size, int max, long ageMs) {
        return "{\"id\":\"" + id + "\",\"leader\":\"" + leader + "\",\"leaderUuid\":\"00000000-0000-0000-0000-000000000001\","
                + "\"verified\":true,\"category\":\"" + category + "\",\"activity\":\"" + activity + "\",\"note\":\"" + note
                + "\",\"reqs\":" + reqs + ",\"size\":" + size + ",\"max\":" + max + ",\"source\":\"mod\",\"ageMs\":" + ageMs + "}";
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
        putStats(name, cata, secrets, new JsonObject());
    }

    /** {@code pbMaster} as the stats cache holds it: {"s_plus": {"floor_7": "4:40"}}. */
    private static void putStats(String name, double cata, int secrets, JsonObject pbMaster) {
        try {
            Class<?> rec = Mod.cls("partyfinder.PartyFinderStatsApi$PlayerStats");
            Object st = rec.getDeclaredConstructor(double.class, int.class, double.class, JsonObject.class,
                    JsonObject.class, long.class).newInstance(cata, secrets, 8.0, new JsonObject(), pbMaster,
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
                putStats("Bob_J", 45.0, 9000); // a request is acceptable only with known stats that meet the listing
                for (int i = 0; i < 8; i++) {
                    putStats("Crowd" + i, 45.0, 9000);
                }
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
            Screen s = openActivity(c, "Dungeons");
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
            Screen s2 = openActivity(c, "Dungeons");
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
            Screen s = openActivity(c, "Dungeons");
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
            c.check(cats.equals(List.of("DUNGEONS", "DIANA", "FISHING", "MISC")), "the finder's categories are not exactly Dungeons, Diana, Fishing, Misc: " + cats);
            Screen s = openScreen(c);
            List<String> tabs = c.onClient(mc -> {
                List<String> out = new ArrayList<>();
                for (GuiEventListener l : s.children()) {
                    if (l instanceof AbstractWidget w) {
                        String t = ModScreenDriver.label(w);
                        if (t.equals("Kuudra") || t.equals("Dungeons") || t.equals("Diana") || t.equals("Fishing") || t.equals("Misc")) {
                            out.add(t);
                        }
                    }
                }
                return out;
            });
            c.note("tab buttons on the menu: " + tabs);
            c.check(tabs.equals(List.of("Dungeons", "Diana", "Fishing", "Misc")), "the start screen's buttons are not Dungeons, Diana, Fishing, Misc: " + tabs);
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
                Mod.call(cfg, "setAutoAcceptRequests", false);
                Mod.call(cfg, "save");
                Mod.staticCall(CFG, "load");
                Object r = Mod.cfg(CFG);
                Object[] a = {Mod.call(r, "isEnabledRaw"), Mod.call(r, "isDualList"), Mod.call(r, "isAutoAcceptInvite"),
                        Mod.call(r, "isCheckRequirements"), Mod.call(r, "isAutoAcceptRequests")};
                Mod.call(r, "setEnabled", true);
                Mod.call(r, "save");
                try {
                    java.nio.file.Files.writeString(file, "{\"enabled\":false}");
                } catch (java.io.IOException e) {
                    throw new AssertionError(e);
                }
                Mod.staticCall("profiles.ProfileManager", "reloadAllConfigs");
                Object p = Mod.cfg(CFG);
                return new Object[]{a[0], a[1], a[2], a[3], Mod.call(p, "isEnabledRaw"), Mod.call(p, "isDualList"), a[4]};
            });
            c.note("after save/load: " + java.util.Arrays.toString(back));
            c.check(Boolean.FALSE.equals(back[0]) && Boolean.FALSE.equals(back[1]) && Boolean.FALSE.equals(back[2])
                    && Boolean.FALSE.equals(back[3]) && Boolean.FALSE.equals(back[6]), "a setting did not survive save/load");
            c.check(Boolean.FALSE.equals(back[4]) && Boolean.TRUE.equals(back[5]),
                    "a profile reload did not re-read the file (enabled false, dualList default true): " + back[4] + " " + back[5]);
            List<String> missing = c.onClient(mc -> {
                List<String> out = new ArrayList<>();
                for (String label : new String[]{"Custom Party Finder: ON", "Open Party Finder", "Dual-List Hypixel Queues: ON",
                        "Auto Accept Requests: ON", "Auto Accept Invite: ON", "Check Requirements: ON"}) {
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
            c.check(firstRow.startsWith("Custom Party Finder: ON") && firstRow.endsWith("6 rows"), "the tab's first row is not its switch: " + firstRow);
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

    // ==== 2026-10-18 fixes (mod branch todo-pf-fix-1018) ===========================================================

    private static void listAs(UiCase c, String activity, Map<String, Integer> reqs, String id) {
        String me = self(c);
        c.onClient(mc -> {
            Object spec = Mod.staticCall(F, "spec", Mod.enumValue(CAT, "DUNGEONS"), activity, "", reqs, 5, false);
            Mod.staticCall(F, "list", spec);
            StringBuilder r = new StringBuilder("{");
            reqs.forEach((k, v) -> r.append(r.length() > 1 ? "," : "").append('"').append(k).append("\":").append(v));
            r.append('}');
            Mod.staticCall(F, "onUpsertForTest", "{\"listing\":" + listingJson(id, me, activity, r.toString(), 1, 5, "mod") + "}");
            return null;
        });
    }

    private static String listingOf(UiCase c, String id, String activity, String reqs) {
        return listingJson(id, self(c), activity, reqs, 1, 5, "mod");
    }

    private static JsonObject m7(String splus) {
        JsonObject floors = new JsonObject();
        floors.addProperty("floor_7", splus);
        JsonObject o = new JsonObject();
        o.add("s_plus", floors);
        return o;
    }

    @SuppressWarnings("unchecked")
    private static List<String> inboxVerdicts(UiCase c) {
        return c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            for (Object row : (List<Object>) Mod.staticCall(F, "inboxRows")) {
                out.add(Mod.call(Mod.call(row, "request"), "joiner") + " " + Mod.call(row, "verdict"));
            }
            return out;
        });
    }

    // ---- 1018-pf-autoaccept-leader -----------------------------------------------------------------------------------

    /**
     * killer560's "for the custom party the auto accept didnt work": his own log (26.1.2 Untested Cheat, 19:45:06) has a
     * request from AutoBallz that sat 47 s with nothing sent until he unlisted - only a click on Accept could answer one.
     * With Auto Accept Requests on, a request that meets every checked requirement is invited with no click, through the
     * command gate; one under them, and one whose stats are not known yet, are not.
     */
    static void autoAcceptLeader(UiCase c) throws Exception {
        List<String> sent = tapCommands();
        try {
            reset(c);
            c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setAutoAcceptRequests", true);
                putStats("Good_G", 35.2, 4000);
                putStats("Low_L", 25.0, 4000);
                return null;
            });
            Map<String, Integer> reqs = new LinkedHashMap<>();
            reqs.put("cata", 30);
            listAs(c, "F7", reqs, "aaaa00000001");
            String listing = listingOf(c, "aaaa00000001", "F7", "{\"cata\":30}");
            me(c, "{\"listing\":" + listing + ",\"requests\":[" + request("bbbb00000001", "Good_G") + ","
                    + request("bbbb00000002", "Low_L") + "," + request("bbbb00000003", "Unk_U") + "],\"outgoing\":[]}");
            c.ticks(5);
            List<String> cl = calls(c);
            c.note("no click: commands " + sent + "; relay calls " + cl + "; inbox " + inboxVerdicts(c) + "; events " + events(c));
            c.check(sent.equals(List.of("/party invite Good_G")), "Auto Accept Requests did not invite exactly Good_G: " + sent);
            c.check(cl.contains("POST /lfg/respond {\"requestId\":\"bbbb00000001\",\"accept\":true}"),
                    "the relay was not told Good_G was accepted: " + cl);
            c.check(cl.contains("POST /lfg/respond {\"requestId\":\"bbbb00000002\",\"accept\":false}"),
                    "the relay was not told Low_L was turned away: " + cl);
            c.check(!cl.contains("POST /lfg/respond {\"requestId\":\"bbbb00000003\",\"accept\":true}"), "Unk_U (stats unknown) was accepted");
            // Off: a fresh request that meets it waits for a click (3.3 s on, so the gate's spacing is not the reason).
            c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setAutoAcceptRequests", false);
                putStats("Late_L", 40.0, 100);
                return null;
            });
            sleep(3300);
            me(c, "{\"listing\":" + listing + ",\"requests\":[" + request("bbbb00000004", "Late_L") + "],\"outgoing\":[]}");
            c.ticks(5);
            c.check(sent.size() == 1, "with Auto Accept Requests off a request was invited without a click: " + sent);
        } finally {
            PacketTrace.tap = null;
            c.onClient(mc -> {
                Mod.staticCall(F, "resetForTest");
                restoreGate();
                return null;
            });
        }
    }

    // ---- 1018-pf-cata-gate-leader -----------------------------------------------------------------------------------

    /** "if i put the cata level as 30 and someone tries to join without that cata then it shouldnt be able to send it to
     *  me": the leader's menu shows such a request rejected with no Accept, a direct accept sends nothing, and the relay is
     *  told "declined"; a joiner who meets it keeps his Accept button. */
    static void cataGateLeader(UiCase c) throws Exception {
        List<String> sent = tapCommands();
        try {
            reset(c);
            c.onClient(mc -> {
                putStats("Good_G", 35.2, 4000);
                putStats("Low_L", 25.0, 4000);
                return null;
            });
            Map<String, Integer> reqs = new LinkedHashMap<>();
            reqs.put("cata", 30);
            listAs(c, "F7", reqs, "aaaa00000002");
            String listing = listingOf(c, "aaaa00000002", "F7", "{\"cata\":30}");
            me(c, "{\"listing\":" + listing + ",\"requests\":[" + request("cccc00000001", "Low_L") + ","
                    + request("cccc00000002", "Good_G") + "],\"outgoing\":[]}");
            c.ticks(3);
            Screen s = openScreen(c);
            c.ticks(3);
            int accepts = widgets(c, s, "Accept").size();
            List<String> verdicts = inboxVerdicts(c);
            String direct = c.onClient(mc -> String.valueOf(Mod.staticCall(F, "accept", "cccc00000001")));
            c.ticks(2);
            List<String> cl = calls(c);
            c.note("inbox " + verdicts + "; Accept buttons " + accepts + "; direct accept of Low_L: " + direct + "; commands "
                    + sent + "; relay calls " + cl);
            c.check(verdicts.contains("Low_L REJECTED"), "Low_L (Cata 25 against 30) is not shown rejected: " + verdicts);
            c.check(verdicts.contains("Good_G OK"), "Good_G (Cata 35) is not acceptable: " + verdicts);
            c.check(accepts == 1, "the menu should offer one Accept (Good_G's), offers " + accepts);
            c.check(!"null".equals(direct) && sent.isEmpty(), "accepting Low_L went through: " + direct + " " + sent);
            c.check(cl.contains("POST /lfg/respond {\"requestId\":\"cccc00000001\",\"accept\":false}"), "the relay was not told Low_L was declined: " + cl);
            String drawn = c.onClient(mc -> String.valueOf(Mod.call(s, "inboxForTest")));
            c.check(drawn.contains("Low_L") && drawn.contains("Cata Level 30"), "the rejected row does not say why: " + drawn);
        } finally {
            PacketTrace.tap = null;
            c.onClient(mc -> {
                Mod.staticCall(F, "resetForTest");
                restoreGate();
                return null;
            });
        }
    }

    // ---- 1018-pf-cata-gate-joiner -----------------------------------------------------------------------------------

    /** His own stats not known yet: Join on a listing with a Cata Level requirement does not reach the relay, and says why. */
    static void cataGateJoiner(UiCase c) throws Exception {
        List<String> sent = tapCommands();
        try {
            reset(c);
            String me = self(c);
            c.onClient(mc -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> cache = (Map<String, Object>) Mod.field("partyfinder.PartyFinderStatsApi", "CACHE");
                cache.remove(me.toLowerCase(Locale.ROOT));
                Mod.staticCall(F, "onListingsForTest", "dungeons", "{\"listings\":["
                        + listingJson("dddd00000001", "Alice_L", "F7", "{\"cata\":30}", 2, 5, "mod") + "]}");
                return null;
            });
            Screen s = openActivity(c, "Dungeons");
            List<AbstractWidget> joins = widgets(c, s, "Join");
            if (!joins.isEmpty()) {
                InvBatchCases.clickWidget(c, s, joins.get(0));
            }
            c.ticks(3);
            List<String> cl = calls(c);
            String status = c.onClient(mc -> String.valueOf(Mod.field(McCompat.screen(mc), "message")));
            c.note("Join buttons " + joins.size() + "; relay calls " + cl + "; message '" + status + "'");
            c.check(joins.size() == 1, "one Join button expected, " + joins.size());
            c.check(cl.stream().noneMatch(x -> x.startsWith("POST /lfg/join")), "Join went to the relay with his stats unknown: " + cl);
            c.check(status.contains("stats"), "the menu did not say his stats are not known: " + status);
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

    // ---- 1018-pf-pb-gate --------------------------------------------------------------------------------------------

    /** A PB requirement (M7 S+ at most 5:00): a joiner at 5:30 is rejected with no Accept, one at 4:40 can be accepted. */
    static void pbGate(UiCase c) throws Exception {
        List<String> sent = tapCommands();
        try {
            reset(c);
            c.onClient(mc -> {
                putStats("Slow_S", 45.0, 9000, m7("5:30"));
                putStats("Quick_Q", 45.0, 9000, m7("4:40"));
                return null;
            });
            Map<String, Integer> reqs = new LinkedHashMap<>();
            reqs.put("pb", 300);
            listAs(c, "M7", reqs, "aaaa00000003");
            String listing = listingOf(c, "aaaa00000003", "M7", "{\"pb\":300}");
            me(c, "{\"listing\":" + listing + ",\"requests\":[" + request("eeee00000001", "Slow_S") + ","
                    + request("eeee00000002", "Quick_Q") + "],\"outgoing\":[]}");
            c.ticks(3);
            Screen s = openScreen(c);
            c.ticks(3);
            List<String> verdicts = inboxVerdicts(c);
            List<AbstractWidget> accepts = widgets(c, s, "Accept");
            c.note("inbox " + verdicts + "; Accept buttons " + accepts.size());
            c.check(verdicts.contains("Slow_S REJECTED") && verdicts.contains("Quick_Q OK"), "PB verdicts wrong: " + verdicts);
            c.check(accepts.size() == 1, "one Accept (Quick_Q's) expected, " + accepts.size());
            if (accepts.size() == 1) {
                InvBatchCases.clickWidget(c, s, accepts.get(0));
            }
            c.ticks(3);
            c.check(sent.equals(List.of("/party invite Quick_Q")), "the Accept click did not invite exactly Quick_Q: " + sent);
            String shown = c.onClient(mc -> {
                Map<String, Integer> r = new LinkedHashMap<>();
                r.put("pb", 300);
                List<?> list = (List<?>) Mod.staticCall("lfg.LfgRequirements", "check", Mod.enumValue(CAT, "DUNGEONS"), "M7", r, "Slow_S");
                return list.isEmpty() ? "" : String.valueOf(Mod.call(list.get(0), "plain"));
            });
            c.check(shown.equals("S+ PB 5:00 (has 5:30)"), "the PB requirement reads wrong: " + shown);
        } finally {
            PacketTrace.tap = null;
            c.onClient(mc -> {
                Mod.staticCall(F, "resetForTest");
                restoreGate();
                return null;
            });
        }
    }

    // ---- 1018-pf-search / -filter / -sort ---------------------------------------------------------------------------

    private static Screen browseFixture(UiCase c) {
        reset(c);
        String me = self(c);
        c.onClient(mc -> {
            putStats(me, 32.0, 5000);
            Mod.staticCall(F, "onListingsForTest", "dungeons", "{\"listings\":["
                    + listingJson("ffff00000001", "Alice_L", "dungeons", "F7", "{\"cata\":40}", "carry wanted", 2, 5, 100) + ","
                    + listingJson("ffff00000002", "Carl_Z", "dungeons", "M3", "{\"cata\":20}", "chill run", 3, 5, 9000) + ","
                    + listingJson("ffff00000003", "Dora_D", "dungeons", "F7", "{\"cata\":30}", "s+ only", 1, 5, 4000) + "]}");
            return null;
        });
        return openActivity(c, "Dungeons");
    }

    /** Search matches the leader's name, the activity and the note. */
    static void search(UiCase c) throws Exception {
        try {
            Screen s = browseFixture(c);
            List<String> before = shownLeaders(c, s);
            EditBox box = c.onClient(mc -> {
                for (GuiEventListener l : s.children()) {
                    if (l instanceof EditBox e) {
                        return e;
                    }
                }
                return null;
            });
            c.check(box != null, "no search box on the Dungeons page");
            List<String> byName = typeSearch(c, s, box, "carl");
            List<String> byActivity = typeSearch(c, s, box, "f7");
            List<String> byNote = typeSearch(c, s, box, "s+ only");
            c.note("all " + before + "; 'carl' " + byName + "; 'f7' " + byActivity + "; 's+ only' " + byNote);
            c.check(before.size() == 3, "three listings expected before searching: " + before);
            c.check(byName.equals(List.of("Carl_Z")), "searching a leader's name: " + byName);
            c.check(byActivity.size() == 2 && byActivity.containsAll(List.of("Alice_L", "Dora_D")), "searching an activity: " + byActivity);
            c.check(byNote.equals(List.of("Dora_D")), "searching a note: " + byNote);
        } finally {
            c.onClient(mc -> {
                Mod.staticCall(F, "resetForTest");
                restoreGate();
                return null;
            });
        }
    }

    private static List<String> typeSearch(UiCase c, Screen s, EditBox box, String text) {
        if (box == null) {
            return List.of();
        }
        c.onClient(mc -> {
            box.setValue(text);
            return null;
        });
        c.ticks(2);
        return shownLeaders(c, s);
    }

    /** "I meet": only parties whose requirements he is not under (Cata 32 against 40 / 20 / 30); saved and reloaded. */
    static void filter(UiCase c) throws Exception {
        try {
            Screen s = browseFixture(c);
            List<AbstractWidget> b = widgets(c, s, "Any reqs");
            c.check(b.size() == 1, "one 'Any reqs' filter button expected, " + b.size());
            if (!b.isEmpty()) {
                InvBatchCases.clickWidget(c, s, b.get(0));
            }
            c.ticks(2);
            List<String> meets = shownLeaders(c, s);
            boolean saved = c.onClient(mc -> {
                Mod.staticCall(CFG, "load");
                return (Boolean) Mod.call(Mod.cfg(CFG), "isMeetsOnly");
            });
            c.note("'I meet' with Cata 32: " + meets + "; saved " + saved);
            c.check(meets.size() == 2 && meets.containsAll(List.of("Carl_Z", "Dora_D")), "the 'I meet' filter kept the wrong parties: " + meets);
            c.check(saved, "the filter did not survive a reload");
        } finally {
            c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setMeetsOnly", false);
                Mod.call(Mod.cfg(CFG), "save");
                Mod.staticCall(F, "resetForTest");
                restoreGate();
                return null;
            });
        }
    }

    /** Newest first by default; one click on the sort makes it oldest first, again lowest Cata requirement first; saved. */
    static void sort(UiCase c) throws Exception {
        try {
            Screen s = browseFixture(c);
            List<String> newest = shownLeaders(c, s);
            List<AbstractWidget> b = widgets(c, s, "Newest");
            c.check(b.size() == 1, "one 'Newest' sort button expected, " + b.size());
            if (!b.isEmpty()) {
                InvBatchCases.clickWidget(c, s, b.get(0));
            }
            c.ticks(2);
            List<String> oldest = shownLeaders(c, s);
            List<AbstractWidget> b2 = widgets(c, s, "Oldest");
            if (!b2.isEmpty()) {
                InvBatchCases.clickWidget(c, s, b2.get(0));
            }
            c.ticks(2);
            List<String> lowest = shownLeaders(c, s);
            String saved = c.onClient(mc -> {
                Mod.staticCall(CFG, "load");
                return String.valueOf(Mod.call(Mod.cfg(CFG), "getSort"));
            });
            c.note("newest " + newest + "; oldest " + oldest + "; lowest req " + lowest + "; saved " + saved);
            c.check(newest.equals(List.of("Alice_L", "Dora_D", "Carl_Z")), "newest first is wrong: " + newest);
            c.check(oldest.equals(List.of("Carl_Z", "Dora_D", "Alice_L")), "oldest first is wrong: " + oldest);
            c.check(lowest.equals(List.of("Carl_Z", "Dora_D", "Alice_L")), "lowest Cata requirement first is wrong: " + lowest);
            c.check(saved.equals("LOWEST_REQ"), "the sort did not survive a reload: " + saved);
        } finally {
            c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setSort", Mod.enumValue("lfg.LfgFilter$Sort", "NEWEST"));
                Mod.call(Mod.cfg(CFG), "save");
                Mod.staticCall(F, "resetForTest");
                restoreGate();
                return null;
            });
        }
    }

    // ---- 1018-pf-start-screen / 1018-pf-misc ------------------------------------------------------------------------

    /** /cpf opens on the start screen; Dungeons shows only Dungeons parties; Back returns to the start screen. */
    static void startScreen(UiCase c) throws Exception {
        try {
            reset(c);
            c.onClient(mc -> {
                Mod.staticCall(F, "onListingsForTest", "dungeons", "{\"listings\":["
                        + listingJson("abab00000001", "Alice_L", "dungeons", "F7", "{}", "", 2, 5, 100) + ","
                        + listingJson("abab00000002", "Fred_F", "fishing", "Fishing", "{}", "", 2, 5, 100) + ","
                        + listingJson("abab00000003", "Mona_M", "misc", "Slayer T4", "{}", "", 2, 5, 100) + "]}");
                return null;
            });
            Screen start = openScreen(c);
            Object startTab = tabOf(c, start);
            int joinsOnStart = widgets(c, start, "Join").size();
            Screen s = openActivity(c, "Dungeons");
            Object tab = tabOf(c, s);
            List<String> shown = shownLeaders(c, s);
            List<AbstractWidget> back = widgets(c, s, "< Back");
            if (!back.isEmpty()) {
                InvBatchCases.clickWidget(c, s, back.get(0));
            }
            c.ticks(3);
            Object after = tabOf(c, c.onClient(McCompat::screen));
            int dungeonButtons = widgets(c, c.onClient(McCompat::screen), "Dungeons").size();
            c.note("start tab " + startTab + " (Join buttons " + joinsOnStart + "); after Dungeons: " + tab + " " + shown
                    + "; after Back: " + after + ", Dungeons button " + dungeonButtons);
            c.check(startTab == null && joinsOnStart == 0, "/cpf did not open on the start screen: " + startTab);
            c.check(String.valueOf(tab).equals("DUNGEONS") && shown.equals(List.of("Alice_L")), "Dungeons did not show only Dungeons parties: " + tab + " " + shown);
            c.check(back.size() == 1, "no Back button on the Dungeons page");
            c.check(after == null && dungeonButtons == 1, "Back did not return to the start screen: " + after);
        } finally {
            c.onClient(mc -> {
                Mod.staticCall(F, "resetForTest");
                restoreGate();
                return null;
            });
        }
    }

    /** Misc is on the start screen and shows only Misc parties; its Create page takes a typed activity. */
    static void misc(UiCase c) throws Exception {
        try {
            reset(c);
            c.onClient(mc -> {
                Mod.staticCall(F, "onListingsForTest", "misc", "{\"listings\":["
                        + listingJson("acac00000001", "Mona_M", "misc", "Slayer T4", "{}", "", 2, 5, 100) + ","
                        + listingJson("acac00000002", "Alice_L", "dungeons", "F7", "{}", "", 2, 5, 100) + "]}");
                return null;
            });
            Screen s = openActivity(c, "Misc");
            Object tab = tabOf(c, s);
            List<String> shown = shownLeaders(c, s);
            List<AbstractWidget> create = widgets(c, s, "Create");
            if (!create.isEmpty()) {
                InvBatchCases.clickWidget(c, s, create.get(0));
            }
            c.ticks(3);
            Screen form = c.onClient(McCompat::screen);
            boolean typed = c.onClient(mc -> Mod.field(form, "activityBox") != null);
            c.note("Misc page: " + tab + " " + shown + "; Create has a typed activity box: " + typed);
            c.check(String.valueOf(tab).equals("MISC"), "the Misc button did not open Misc: " + tab);
            c.check(shown.equals(List.of("Mona_M")), "Misc did not show only Misc parties: " + shown);
            c.check(typed, "Misc's Create page has no activity box");
        } finally {
            c.onClient(mc -> {
                Mod.staticCall(F, "resetForTest");
                restoreGate();
                return null;
            });
        }
    }
}

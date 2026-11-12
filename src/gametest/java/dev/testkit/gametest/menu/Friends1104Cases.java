package dev.testkit.gametest.menu;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.PacketTrace;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Friends List "Filter by" drop-down and per-friend /pv button (mod friendsfilter-1104; killer560, 2026-11-03: "inside the
 * custom friends list add a filter by button that filters by areas they are in ... only show areas that i actively have
 * friends in").
 * <ul>
 *   <li>1104-menu-friends-area-filter: six fake friends, three online areas, one offline and one with a stale area line
 *       from before they went offline. The drop-down lists exactly All plus the three areas (sorted); picking each with
 *       real clicks shows exactly the friends there; when the last friend of an area goes offline the area leaves the
 *       list, and a filter set to it falls back to All.</li>
 *   <li>1104-menu-friends-pv: a real click on a row's /pv button sends exactly one "/pv HxD" and nothing else.</li>
 * </ul>
 */
final class Friends1104Cases {

    private static final String FL_SCREEN = "social.FriendsListScreen";
    private static final String CFG = "social.FriendsListConfig";

    private Friends1104Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1104-menu-friends-area-filter", Friends1104Cases::areaFilter);
        MenuSuite.test(s, "1104-menu-friends-pv", Friends1104Cases::pv);
        MenuSuite.test(s, "1110-menu-friends-party-top", Friends1104Cases::partyTop);
        MenuSuite.test(s, "1108-menu-friends-scan-rows", Friends1104Cases::scanRows);
    }

    /** name, area (null: no line), online hint. */
    private static final Object[][] FRIENDS = {
            {"HxPalA", "SkyBlock - Hub", true},
            {"HxPalB", "SkyBlock - Hub", true},
            {"HxPalC", "SkyBlock - Garden", true},
            {"HxPalD", "Bedwars - Solo", true},
            {"HxPalE", "Offline", false},
            {"HxPalF", "SkyBlock - Mines", false}, // an old area line: offline now, so it must not make an area
    };

    private static void seed(Session c) {
        c.ctx().runOnClient(mc -> {
            List<String> names = new ArrayList<>();
            for (Object[] f : FRIENDS) {
                names.add((String) f[0]);
            }
            List<?> made = (List<?>) Mod.call(Mod.cfg(CFG), "applyRealSync", names, false);
            try {
                for (int i = 0; i < made.size(); i++) {
                    Object f = made.get(i);
                    f.getClass().getField("activity").set(f, FRIENDS[i][1]);
                    f.getClass().getField("activityAtMs").setLong(f, System.currentTimeMillis());
                    f.getClass().getField("onlineHint").set(f, FRIENDS[i][2]);
                }
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            Mod.setField("social.FriendsListScreen", "areaFilter", "All");
        });
    }

    private static AbstractWidget dropdown(Session c) {
        return c.onClient(mc -> {
            for (Object w : SocialCases.current(c).children()) {
                if (w.getClass().getSimpleName().equals("DropdownWidget")) {
                    return (AbstractWidget) w;
                }
            }
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private static List<String> labels(Session c) {
        AbstractWidget d = dropdown(c);
        c.check(d != null, "the Filter by drop-down is not on the screen");
        return d == null ? List.of() : c.onClient(mc -> (List<String>) Mod.call(d, "optionLabels"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> shownNames(Session c) {
        return c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            for (Object f : (List<Object>) Mod.field(SocialCases.current(c), "visible")) {
                out.add((String) Mod.field(f, "name"));
            }
            return out;
        });
    }

    private static void clickGui(Session c, double gx, double gy) {
        double s = c.onClient(mc -> mc.getWindow().getGuiScale());
        c.ctx().getInput().setCursorPos(gx * s, gy * s);
        c.ctx().waitTicks(2);
        c.ctx().getInput().pressMouse(dev.testkit.compat.McKeys.mouse(0));
        c.ctx().waitTicks(3);
    }

    /** Opens the drop-down with a real click and picks choice {@code index} with another. */
    private static void pick(Session c, int index) {
        AbstractWidget d = dropdown(c);
        clickGui(c, d.getX() + d.getWidth() / 2.0, d.getY() + d.getHeight() / 2.0);
        int[] box = c.onClient(mc -> (int[]) Mod.call(d, "optionBounds", index));
        c.check(box != null, "choice " + index + " is not in the open list");
        if (box != null) {
            clickGui(c, box[0] + box[2] / 2.0, box[1] + box[3] / 2.0);
        }
    }

    static void areaFilter(Session c) throws Exception {
        try (SocialCases.Env env = new SocialCases.Env(c)) {
            seed(c);
            SocialCases.open(c, FL_SCREEN);
            c.ctx().waitTicks(4);
            List<String> labels = labels(c);
            c.check(labels.equals(List.of("All", "Bedwars - Solo", "SkyBlock - Garden", "SkyBlock - Hub")),
                    "the drop-down should list All and the three online areas only, got " + labels);
            c.check(shownNames(c).size() == 6, "All should show all six friends, got " + shownNames(c));

            pick(c, 3);
            c.check(shownNames(c).equals(List.of("HxPalA", "HxPalB")), "Hub should show A and B, got " + shownNames(c));
            pick(c, 2);
            c.check(shownNames(c).equals(List.of("HxPalC")), "Garden should show C, got " + shownNames(c));
            pick(c, 1);
            c.check(shownNames(c).equals(List.of("HxPalD")), "Bedwars should show D, got " + shownNames(c));

            // D goes offline: Bedwars leaves the list and the filter (still on Bedwars) falls back to All.
            c.ctx().runOnClient(mc -> {
                for (Object f : (List<?>) Mod.call(Mod.cfg(CFG), "friends")) {
                    if ("HxPalD".equals(Mod.field(f, "name"))) {
                        try {
                            f.getClass().getField("onlineHint").set(f, Boolean.FALSE);
                        } catch (ReflectiveOperationException e) {
                            throw new AssertionError(e);
                        }
                    }
                }
            });
            c.ctx().waitTicks(8);
            labels = labels(c);
            c.check(labels.equals(List.of("All", "SkyBlock - Garden", "SkyBlock - Hub")),
                    "Bedwars should leave the list once nobody is there, got " + labels);
            c.check(shownNames(c).size() == 6, "the filter should fall back to All, got " + shownNames(c));
            c.note("drop-down: All + 3 areas, Hub=2 Garden=1 Bedwars=1 friends, Bedwars gone after its friend left");
        }
    }

    static void pv(Session c) throws Exception {
        try (SocialCases.Env env = new SocialCases.Env(c)) {
            seed(c);
            SocialCases.open(c, FL_SCREEN);
            c.ctx().waitTicks(4);
            pick(c, 1); // Bedwars: only HxPalD, row 0
            c.check(shownNames(c).equals(List.of("HxPalD")), "Bedwars should show D, got " + shownNames(c));
            Screen s = SocialCases.current(c);
            int listX = SocialCases.intField(s, "listX");
            int listY = SocialCases.intField(s, "listY");
            int listW = SocialCases.intField(s, "listW");
            int rowRight = listX + listW - 8 - 2;
            double px = rowRight - 3 - 40 - 3 - 12;
            List<String> sent = new CopyOnWriteArrayList<>();
            PacketTrace.tap = p -> {
                if (p instanceof ServerboundChatCommandPacket cmd) {
                    sent.add(cmd.command());
                }
            };
            try {
                clickGui(c, px, listY + 11);
                c.ctx().waitTicks(6);
            } finally {
                PacketTrace.stop();
            }
            Screen now = SocialCases.current(c);
            String open = now == null ? "none" : now.getClass().getSimpleName();
            c.check(sent.isEmpty(), "the /pv click must send nothing to the server (it is a client command), sent " + sent);
            c.check(open.equals("ProfileViewerScreen"), "the /pv click should open the mod's profile viewer, screen is " + open);
            c.note("/pv click sent " + sent + ", screen " + open);
        }
    }

    /** 1108: rows appear while a /fl scan is still walking (killer560: "it should update as it goes"). */
    static void scanRows(Session c) throws Exception {
        try (SocialCases.Env env = new SocialCases.Env(c)) {
            c.ctx().runOnClient(mc -> {
                Mod.call(Mod.cfg(CFG), "applyRealSync", new ArrayList<String>(), false);
                Mod.setField("social.FriendsListSync", "walking", true);
                Mod.setField("social.FriendsListSync", "awaitingSinceMs", System.currentTimeMillis());
                Mod.setField("social.FriendsListScreen", "areaFilter", "All");
            });
            SocialCases.open(c, FL_SCREEN);
            c.ctx().waitTicks(4);
            try {
                feed(c, "Friends (Page 1 of 3) >>");
                feed(c, "ScanAlpha is currently offline");
                c.ctx().waitTicks(3);
                c.check(shownNames(c).equals(List.of("ScanAlpha")), "first row should show mid-scan, got " + shownNames(c));
                feed(c, "ScanBeta is in SkyBlock - Hub");
                c.ctx().waitTicks(3);
                c.check(shownNames(c).equals(List.of("ScanAlpha", "ScanBeta")), "second row should show mid-scan, got " + shownNames(c));
                boolean walking = c.onClient(mc -> (Boolean) Mod.staticCall("social.FriendsListSync", "isSyncing"));
                c.check(walking, "the scan must still be walking while the rows show (premise)");
                c.note("rows during scan: " + shownNames(c));
            } finally {
                c.ctx().runOnClient(mc -> Mod.staticCall("social.FriendsListSync", "endWalk"));
            }
        }
    }

    private static void feed(Session c, String line) {
        c.ctx().runOnClient(mc -> Mod.staticCall("social.FriendsListSync", "onGameLine", net.minecraft.network.chat.Component.literal(line)));
    }

    /** friendparty-1110: friends in the party sit first under an "In Party" header and a "Friends" divider; a party
     *  member who is not a friend is not listed; the setting OFF and an empty party both restore the plain order. */
    static void partyTop(Session c) throws Exception {
        try (SocialCases.Env env = new SocialCases.Env(c)) {
            seed(c);
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setPartyFriendsOnTop", true));
            SocialCases.open(c, FL_SCREEN);
            List<String> plain = List.of("HxPalA", "HxPalB", "HxPalC", "HxPalD", "HxPalE", "HxPalF");
            c.check(shownNames(c).equals(plain), "no party: the list should be the plain order, got " + shownNames(c));
            SocialCases.join(c, java.util.UUID.nameUUIDFromBytes("HxPalC".getBytes()), "HxPalC");
            SocialCases.join(c, java.util.UUID.nameUUIDFromBytes("HxPalE".getBytes()), "HxPalE");
            SocialCases.join(c, java.util.UUID.nameUUIDFromBytes("HxStranger".getBytes()), "HxStranger");
            c.waitUntil("friends regrouped after the party joins", mc -> shownNames(c).contains("In Party"), 80);
            List<String> want = List.of("In Party", "HxPalC", "HxPalE", "Friends", "HxPalA", "HxPalB", "HxPalD", "HxPalF");
            c.check(shownNames(c).equals(want), "party friends first under the header (and no stranger), got " + shownNames(c));
            List<String> drawn = c.onClient(mc -> SocialCases.texts(mc, SocialCases.current(c)).stream().map(SocialCases.Txt::s).toList());
            c.check(drawn.contains("In Party") && drawn.contains("Friends"), "both headers should be drawn, texts " + drawn);
            SocialCases.shot(c, "fl-party-top");
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setPartyFriendsOnTop", false));
            SocialCases.closeScreen(c);
            SocialCases.open(c, FL_SCREEN);
            c.check(shownNames(c).equals(plain), "setting OFF should restore the plain order, got " + shownNames(c));
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setPartyFriendsOnTop", true));
            SocialCases.leaveParty(c);
            c.waitUntil("friends ungrouped after the party left", mc -> !shownNames(c).contains("In Party"), 80);
            c.check(shownNames(c).equals(plain), "party left: plain order again, got " + shownNames(c));
        }
    }
}

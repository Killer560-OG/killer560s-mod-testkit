package dev.testkit.gametest.boss;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.FakeRelay;
import dev.testkit.gametest.hx.HxKit;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Team Melody over the relay (mod doc-terms-1101, to-do Doc #48; testkit tkdebt-1101). killer560, 2026-11-01: "if someone
 * is sharing as a part of our mod then have it show the actual melody screen they are seeing". Until this the relay half
 * was unproven (doc-audit-1101: "Not tested: the relay round trip between two clients and the HUD with real teammates").
 * Both cases run in a faked F7 (sidebar + dungeon tab list with HxMateA and HxMateB, {@link HxKit#dungeon}) on a platform
 * of their own inside the F7 boss area at P3's height, in Phase 3 (Goldor's line), with the mod's relay client put on a
 * {@link FakeRelay} room. (Boss suite, not menu: Floor7Tracker's phase needs him inside the boss room's coordinates.)
 * <ul>
 *   <li><b>1102-boss-melody-relay-roundtrip</b> - two senders on one room. Sender 1 is this client: a real Melody terminal
 *       is opened on the server and moved to row 2, lime column 4, target column 2 ({@code menu.terminal.melody}); the
 *       client's own tracker must read that board and the mod must SEND it as {@code dg.v1.melody} (clay 2, purple 1, pane
 *       3), with Party Dungeon Data and Interop's relay data both OFF (killer560 2026-11-01: Melody shares "no matter what
 *       setting on or off"). Sender 2 is HxMateA, a member whose copy of the mod sends back what this one sent
 *       ({@link FakeRelay#mirrorAs}): those packets come in through the real socket handler and must land as HxMateA's
 *       board, cell for cell what the screen showed. A third sender not on the team (Intruder_Z) is dropped.</li>
 *   <li><b>1102-boss-melody-hud-boards</b> - the HUD: two teammates' boards arrive over the relay (HxMateA row 2 / target 1
 *       / moving 3, HxMateB row 1 / target 4 / moving 0) and the Team Melody HUD must DRAW each as its board, checked in a
 *       screenshot at every cell: the target column purple in the top strip, rows before the active one green, the active
 *       row's moving cell green-yellow, its button lit, later buttons red. With the HUD off the same cells show none of it.</li>
 * </ul>
 */
final class TeamMelodyRelayCases {

    static final String HUD_CFG = "melody.MelodyHudConfig";
    static final String TRACKER = "melody.MelodyTrackerFeature";
    static final String INTEL = "bridge.MelodyIntel";
    static final String KEY = "dg.v1.melody";
    static final String GOLDOR = "[BOSS] Goldor: Who dares trespass into my domain?";

    /** TeamMelodyHudElement's colors (MelodyTrackerFeature). */
    static final int TARGET = 0xD24BD2;
    static final int MOVING = 0x7CE04A;
    static final int ROW_DONE = 0x3F8F3F;
    static final int BUTTON_WAIT = 0xA33A2A;
    static final int CELL_EMPTY = 0x3A2A1C;

    private TeamMelodyRelayCases() {
    }

    static void register(Session s) {
        s.test("1102-boss-melody-relay-roundtrip", TeamMelodyRelayCases::roundTrip);
        s.test("1102-boss-melody-hud-boards", TeamMelodyRelayCases::hudBoards);
    }

    /** The case's own floor inside F7's boss area (x, z > -7) at P3's height. */
    static final int PX = 60;
    static final int PZ = 30;

    /** F7 with HxMateA/HxMateB in the tab list, on the P3 platform, then Phase 3; Team Melody's store starts empty. */
    private static void inP3(Session c) {
        // Loaded first, then filled, then he is put on it (787/832: a tp onto a floor as it appears flags Simulation).
        c.server().command("forceload add " + (PX - 4) + " " + (PZ - 4) + " " + (PX + 4) + " " + (PZ + 4));
        c.server().sync(20);
        c.server().command("fill " + (PX - 3) + " 119 " + (PZ - 3) + " " + (PX + 3) + " 119 " + (PZ + 3) + " stone");
        c.hx().call("dungeon.tp", "x", PX + 0.5, "y", 120.0, "z", PZ + 0.5, "yaw", 0f, "pitch", 0f);
        c.waitUntil("the client to have the platform", mc -> mc.level.getBlockState(new net.minecraft.core.BlockPos(PX, 119, PZ))
                .is(net.minecraft.world.level.block.Blocks.STONE), 200);
        c.waitUntil("him standing on the platform", mc -> mc.player.onGround() && Math.abs(mc.player.getY() - 120.0) < 1e-3
                && Math.abs(mc.player.getX() - (PX + 0.5)) < 0.3, 200);
        HxKit.dungeon(c);
        c.hx().chat(GOLDOR);
        c.waitUntil("Floor7Tracker phase P3",
                mc -> "P3".equals(String.valueOf(Mod.staticCall("fastleap.Floor7Tracker", "getPhase"))), 100);
        c.ctx().waitTicks(2);
        c.onClient(mc -> Mod.staticCall(INTEL, "clear"));
    }

    private static FakeRelay relay(Session c) throws Exception {
        c.waitUntil("ModChatFeature to want this party's relay room", mc -> FakeRelay.wantedRoom().startsWith("party:"), 200);
        FakeRelay fake = FakeRelay.install(List.of(HxKit.self(c), "HxMateA", "HxMateB"));
        c.waitUntil("PartyDataFeature to see a party room", mc -> (Boolean) Mod.staticCall("relay.RelayClient", "isConnected")
                && String.valueOf(Mod.staticCall("relay.RelayClient", "room")).startsWith("party:"), 40);
        c.note("fake relay room " + fake.room());
        return fake;
    }

    private static JsonObject payload(int type, int slot) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("slot", slot);
        return o;
    }

    /** {clay, target, current} MelodyIntel holds for {@code who}, or null. */
    private static int[] intel(String who) {
        Object p = Mod.staticCall(INTEL, "get", who);
        if (p == null || !(Boolean) Mod.call(p, "hasBoard")) {
            return null;
        }
        return new int[]{(Integer) Mod.call(p, "clayRow"), (Integer) Mod.call(p, "target"), (Integer) Mod.call(p, "current")};
    }

    /** The last value of each Melody type this client sent: {clay, purple, pane} (-1 = never). */
    private static int[] lastSent(FakeRelay fake) {
        int[] out = {-1, -1, -1};
        for (JsonObject v : fake.sentData(KEY)) {
            int type = v.get("type").getAsInt();
            int slot = v.get("slot").getAsInt();
            if (type == 1) {
                out[0] = slot;
            } else if (type == 2) {
                out[1] = slot;
            } else if (type == 5) {
                out[2] = slot;
            }
        }
        return out;
    }

    private static String fmt(int[] a) {
        return a == null ? "none" : "clay " + a[0] + " / target " + a[1] + " / moving " + a[2];
    }

    // ---- 1102-boss-melody-relay-roundtrip ------------------------------------------------------------------------

    static void roundTrip(Session c) throws Exception {
        closeAll(c);
        List<String> notes = new ArrayList<>();
        FakeRelay fake = null;
        try (HxKit.Settings cfg = new HxKit.Settings(c)) {
            // Melody's own switch on; every other relay data switch off (mod/Quiet's state, and what he may run with).
            cfg.with(HUD_CFG, "ShareProgress", true).with(HUD_CFG, "HudEnabled", true)
                    .with("partydata.PartyDataConfig", "ShareEnabled", false)
                    .with("interop.InteropConfig", "RelayData", false);
            inP3(c);
            fake = relay(c);
            final FakeRelay room = fake;
            room.mirrorAs("HxMateA");

            JsonObject t = c.hx().call("menu.terminal", "type", "MELODY", "seed", 31, "interval", 10, "bandRows", 3,
                    "freeze", true, "target", 5).getAsJsonObject();
            String title = t.get("title").getAsString();
            c.waitUntil("the Melody terminal open on the client", mc -> dev.testkit.compat.McCompat.screen(mc)
                    instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> scr
                    && title.equals(scr.getTitle().getString()), 100);
            // The board he is looking at: band row 2, the moving pane in chest column 4, the target in chest column 2.
            c.hx().call("menu.terminal.melody", "row", 2, "lime", 4, "target", 2);
            int[] want = {2, 1, 3};
            c.waitUntil("the client's own tracker to read row 2 / target 1 / moving 3", mc -> {
                Object self = Mod.staticCall(TRACKER, "selfSnapshot");
                return self != null && (Integer) Mod.call(self, "clayRow") == 2 && (Integer) Mod.call(self, "target") == 1
                        && (Integer) Mod.call(self, "current") == 3;
            }, 100);
            notes.add("screen read by the tracker: " + fmt(want));

            // Sender 1, this client: the board leaves over the relay although Party Dungeon Data and relay data are off.
            try {
                c.waitUntil("this client to send clay 2, purple 1 and pane 3 as dg.v1.melody",
                        mc -> java.util.Arrays.equals(lastSent(room), want), 120);
            } catch (AssertionError e) {
                throw new AssertionError("[" + c.name() + "] the mod did not send this client's Melody board over the relay "
                        + "with Party Dungeon Data and relay data off (sent dg.v1.melody: " + fake.sentData(KEY) + "; all sent: "
                        + fake.sent().size() + " packet(s)) - Team Melody must share 'no matter what setting on or off'", e);
            }
            List<JsonObject> wire = fake.sentData(KEY);
            notes.add("sent " + wire.size() + " dg.v1.melody packet(s): " + wire);
            for (JsonObject v : wire) {
                c.check(v.keySet().equals(java.util.Set.of("type", "slot")),
                        "a dg.v1.melody payload carries more than type and slot: " + v);
            }

            // Sender 2, HxMateA: the same packets back through the socket handler, as HxMateA's board.
            c.waitUntil("HxMateA's board from the relay", mc -> java.util.Arrays.equals(intel("HxMateA"), want), 100);
            notes.add("HxMateA's board from the relay: " + fmt(c.onClient(mc -> intel("HxMateA"))));

            // Not on the team: dropped, however well-formed.
            fake.mirrorAs(null);
            fake.deliver("Intruder_Z", KEY, payload(1, 3));
            fake.deliver("Intruder_Z", KEY, payload(2, 4));
            c.ctx().waitTicks(10);
            c.check(c.onClient(mc -> Mod.staticCall(INTEL, "get", "Intruder_Z")) == null,
                    "a Melody packet from Intruder_Z (not in the party or tab list) was taken");
            notes.add("Intruder_Z dropped");

            // The HUD row for HxMateA: a board, with the screen's values.
            closeAll(c);
            c.ctx().waitTicks(3);
            Map<String, int[]> rows = hudRows(c);
            notes.add("HUD rows: " + describe(rows));
            int[] row = rows.get("HxMateA");
            c.check(row != null && row[3] == 1, "the Team Melody HUD has no BOARD row for HxMateA: " + describe(rows));
            c.check(row[0] == 2 && row[1] == 1 && row[2] == 3, "HxMateA's HUD board is " + fmt(row) + ", the screen was "
                    + fmt(want));
        } finally {
            if (fake != null) {
                fake.close();
            }
            closeAll(c);
            c.onClient(mc -> Mod.staticCall(INTEL, "clear"));
            leave(c);
            c.note(String.join("; ", notes));
        }
    }

    // ---- 1102-boss-melody-hud-boards -----------------------------------------------------------------------------

    static void hudBoards(Session c) throws Exception {
        closeAll(c);
        List<String> notes = new ArrayList<>();
        FakeRelay fake = null;
        try (HxKit.Settings cfg = new HxKit.Settings(c)) {
            cfg.with(HUD_CFG, "ShareProgress", true).with(HUD_CFG, "HudEnabled", true);
            inP3(c);
            fake = relay(c);
            // Two teammates' screens, as their mods send them (Odin's types: 1 clay row, 2 target, 5 moving pane).
            int[][] boards = {{2, 1, 3}, {1, 4, 0}};
            String[] mates = {"HxMateA", "HxMateB"};
            for (int i = 0; i < 2; i++) {
                fake.deliver(mates[i], KEY, payload(1, boards[i][0]));
                fake.deliver(mates[i], KEY, payload(2, boards[i][1]));
                fake.deliver(mates[i], KEY, payload(5, boards[i][2]));
            }
            c.waitUntil("both boards in Team Melody's store", mc -> java.util.Arrays.equals(intel("HxMateA"), boards[0])
                    && java.util.Arrays.equals(intel("HxMateB"), boards[1]), 100);
            c.ctx().waitTicks(3);
            Map<String, int[]> rows = hudRows(c);
            notes.add("HUD rows: " + describe(rows));
            c.check(rows.size() == 2 && rows.get("HxMateA") != null && rows.get("HxMateA")[3] == 1
                    && rows.get("HxMateB") != null && rows.get("HxMateB")[3] == 1,
                    "the HUD should have one board row per teammate: " + describe(rows));
            int drawnBefore = c.onClient(mc -> (Integer) Mod.field(TRACKER, "boardsDrawn"));
            c.ctx().waitTicks(5);
            int drawnAfter = c.onClient(mc -> (Integer) Mod.field(TRACKER, "boardsDrawn"));
            c.check(drawnAfter > drawnBefore, "no Team Melody board was drawn in 5 ticks (" + drawnBefore + " -> " + drawnAfter + ")");

            Geometry g = geometry(c);
            notes.add(String.format(java.util.Locale.ROOT, "HUD at %d,%d scale %.2f, gui %.2f px/unit, %d board rows",
                    g.x, g.y, g.scale, g.px, g.rows));
            Path on = shot(c, "hud-on");
            c.onClient(mc -> {
                Mod.set(HUD_CFG, "setHudEnabled", false);
                return null;
            });
            c.ctx().waitTicks(5);
            Path off = shot(c, "hud-off");
            c.onClient(mc -> {
                Mod.set(HUD_CFG, "setHudEnabled", true);
                return null;
            });
            BufferedImage imgOn = javax.imageio.ImageIO.read(on.toFile());
            BufferedImage imgOff = javax.imageio.ImageIO.read(off.toFile());
            List<String> bad = new ArrayList<>();
            int checked = 0;
            int lineY = 0;
            for (int i = 0; i < 2; i++) {
                int[] b = boards[i];
                int boardY = lineY + 10;
                int active = b[0] - 1;
                for (int col = 0; col < 5; col++) {
                    int want = col == b[1] ? TARGET : CELL_EMPTY;
                    checked += cell(imgOn, imgOff, g, col * 6, boardY, want, mates[i] + " target strip col " + col, bad);
                }
                for (int r = 0; r < g.rows; r++) {
                    int ry = boardY + (r + 1) * 6;
                    for (int col = 0; col < 5; col++) {
                        int want = r < active ? ROW_DONE : (r == active && col == b[2] ? MOVING : CELL_EMPTY);
                        checked += cell(imgOn, imgOff, g, col * 6, ry, want, mates[i] + " row " + (r + 1) + " col " + col, bad);
                    }
                    int button = r < active ? ROW_DONE : r == active ? MOVING : BUTTON_WAIT;
                    checked += cell(imgOn, imgOff, g, 5 * 6 + 2, ry, button, mates[i] + " row " + (r + 1) + " button", bad);
                }
                lineY += 10 + (g.rows + 1) * 6 + 3;
            }
            notes.add(checked + " board cells checked against the screenshot, " + bad.size() + " wrong");
            c.check(bad.isEmpty(), "the HUD does not show the teammates' boards as sent: " + String.join("; ",
                    bad.subList(0, Math.min(8, bad.size()))));
        } finally {
            if (fake != null) {
                fake.close();
            }
            c.onClient(mc -> Mod.staticCall(INTEL, "clear"));
            leave(c);
            c.note(String.join("; ", notes));
        }
    }

    /** Out of the dungeon and back on the session's spawn platform, the case's floor removed. */
    private static void leave(Session c) {
        HxKit.hub(c);
        c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 0f, "pitch", 0f);
        c.waitUntil("him back on the spawn platform", mc -> mc.player.onGround() && Math.abs(mc.player.getY() - 151.0) < 1e-3
                && Math.abs(mc.player.getX() + 619.5) < 0.5, 200);
        c.server().command("fill " + (PX - 3) + " 119 " + (PZ - 3) + " " + (PX + 3) + " 119 " + (PZ + 3) + " air");
        c.server().command("forceload remove " + (PX - 4) + " " + (PZ - 4) + " " + (PX + 4) + " " + (PZ + 4));
    }

    /** Close whatever is open on both sides. */
    private static void closeAll(Session c) {
        c.ctx().runOnClient(mc -> {
            if (mc.player != null && dev.testkit.compat.McCompat.screen(mc)
                    instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) {
                mc.player.closeContainer();
            }
        });
        c.hx().call("menu.close");
        c.ctx().waitTicks(4);
    }

    /** Where the HUD draws: its top-left in GUI units, its scale, screenshot pixels per GUI unit, the board's rows. */
    record Geometry(int x, int y, float scale, double px, int rows, int windowWidth) {
    }

    private static Geometry geometry(Session c) {
        return c.onClient(mc -> {
            Object hud = Mod.field(TRACKER, "HUD");
            int[] pos = (int[]) Mod.staticCall("hud.HudElementRegistry", "resolvePosition", hud);
            float scale = (Float) Mod.staticCall("hud.HudElementRegistry", "resolveScale", hud);
            double px = mc.getWindow().getWidth() / (double) mc.getWindow().getGuiScaledWidth();
            int rows = Math.max(1, Math.min(4, (Integer) Mod.staticCall("terminals.TerminalLayouts", "melodyRows")));
            return new Geometry(pos[0], pos[1], scale, px, rows, mc.getWindow().getWidth());
        });
    }

    /** Checks the 5x5 cell whose top-left is ({@code lx}, {@code ly}) in the element's own units. */
    private static int cell(BufferedImage on, BufferedImage off, Geometry g, int lx, int ly, int want, String what,
                            List<String> bad) {
        double sx = on.getWidth() / (double) g.windowWidth; // 1 unless the shot is not the window's size
        int px = (int) Math.round((g.x + g.scale * (lx + 2.5)) * g.px * sx);
        int py = (int) Math.round((g.y + g.scale * (ly + 2.5)) * g.px * sx);
        if (px < 0 || py < 0 || px >= on.getWidth() || py >= on.getHeight()) {
            bad.add(what + " off the screenshot at " + px + "," + py);
            return 1;
        }
        int got = on.getRGB(px, py) & 0xFFFFFF;
        int gotOff = off.getRGB(px, py) & 0xFFFFFF;
        if (!near(got, want, 10)) {
            bad.add(String.format(java.util.Locale.ROOT, "%s at %d,%d is #%06X, should be #%06X", what, px, py, got, want));
        } else if (near(gotOff, want, 10) && want != CELL_EMPTY) {
            bad.add(String.format(java.util.Locale.ROOT, "%s at %d,%d is #%06X with the HUD OFF too", what, px, py, gotOff));
        }
        return 1;
    }

    private static boolean near(int a, int b, int tol) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) <= tol && Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)) <= tol
                && Math.abs((a & 0xFF) - (b & 0xFF)) <= tol;
    }

    private static Path shot(Session c, String label) {
        Path p = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name() + "-" + label));
        dev.testkit.harness.Report.screenshot(c.name(), p);
        return p;
    }

    /** The HUD's rows: name -> {clay, target, current, board 1/0}. */
    private static Map<String, int[]> hudRows(Session c) {
        return c.onClient(mc -> {
            Map<String, int[]> out = new LinkedHashMap<>();
            Object[] rows = (Object[]) Mod.field(TRACKER, "ROWS");
            int n = (Integer) Mod.field(TRACKER, "rowCount");
            for (int i = 0; i < n; i++) {
                Object r = rows[i];
                out.put((String) Mod.field(r, "name"), new int[]{(Integer) Mod.field(r, "clay"), (Integer) Mod.field(r, "target"),
                        (Integer) Mod.field(r, "current"), (Boolean) Mod.field(r, "board") ? 1 : 0});
            }
            return out;
        });
    }

    private static String describe(Map<String, int[]> rows) {
        StringBuilder sb = new StringBuilder();
        rows.forEach((k, v) -> sb.append(sb.isEmpty() ? "" : ", ").append(k).append(" ").append(v[3] == 1 ? "board " : "bar ")
                .append(fmt(v)));
        return sb.isEmpty() ? "(none)" : sb.toString();
    }
}

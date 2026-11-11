package dev.testkit.gametest;

import com.google.gson.JsonObject;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.coop.CoopSync;
import dev.testkit.gametest.coop.FakeGateway;
import dev.testkit.gametest.coop.GuestPlayer;
import dev.testkit.gametest.coop.SimRoomsStub;
import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Co-op sim phase 2, Sim Multiplayer (coopsim-1109): the menu entry, Create / Join / Session pages, the relay room list
 * (against {@link SimRoomsStub}), tickets, UPnP (against {@link FakeGateway}, never a real router) and guest requests.
 * Only when named.
 *
 * <ul>
 * <li>{@code 1109-mp-menu}: the Multiplayer entry sits apart from the builders (its own separator, above Back); it opens
 * Create; Join fetches the list; a locked room asks for its password, a wrong one is refused and the right one gets a
 * ticket; a full room and another protocol are refused.</li>
 * <li>{@code 1109-mp-host}: Create from the title opens the F7 boss and publishes; the fake gateway maps the port and the
 * room is listed with that public address; a ticketless fake guest is kicked after ~12 s, a ticketed one admitted, a
 * guest over Max refused; requests run for the sender only and a switched-off kind is refused (saved); Kick on the
 * Session page; the pause menu's quit and Close Room warn "1 player will be disconnected."; Close deletes the listing and
 * the port mapping.</li>
 * <li>{@code 1109-mp-upnp}: the no-router messages (UPnP off in a test client, no answer, the router refusing).</li>
 * <li>{@code 1109-mp-two}: two real clients ({@code run-coop.sh -s 1109-mp-two}): the guest finds the room on the Join page,
 * a wrong password is refused, the right one joins with a ticket; the Session page (pause menu "Sim Session"); a speed
 * request honored, then refused once the owner switched it off; owner Kick shows "Session ended: the owner kicked you";
 * a rejoin, then the owner leaves and the guest sees "Session ended: the owner left".</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class CoopMultiplayerTests implements FabricClientGameTest {

    static final String MENU = "1109-mp-menu";
    static final String HOST = "1109-mp-host";
    static final String UPNP = "1109-mp-upnp";
    static final String TWO = "1109-mp-two";
    /** Phase 3: invites through a local wrangler dev relay (run-coop.sh -w). */
    static final String INVITE = "1109-mp-invite";
    /** coopnet-1110: no UPnP on the host, the guest joins through the local relay's tunnel (run-coop.sh -w). */
    static final String TUNNEL = "1110-mp-tunnel";
    private static final String R = "com.killer560.hub.roomsim.";
    private static final String HOSTC = R + "SimHost";
    private static final String REQ = R + "SimRequests";
    private static final String CFG = R + "SimCoopConfig";
    private static final String RELAY = "killer560.net.simrooms-relay";
    private static final String SSDP = "killer560.simcoop.ssdp";
    private static final String SIM = "com.killer560.hub.bosssim.BossSim";
    private static final String FIGHT = "com.killer560.hub.bosssim.BossFight";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank()) {
            return;
        }
        List<String> failed = new ArrayList<>();
        for (String name : new String[]{MENU, UPNP, HOST, TWO, INVITE, TUNNEL}) {
            if (Scenario.skip(name)) {
                continue;
            }
            boolean two = name.equals(TWO) || name.equals(INVITE) || name.equals(TUNNEL);
            if (!two && CoopSync.role().equals("guest")) {
                Scenario.skipped(name, "the guest side of a two-client run (the host runs it)");
                continue;
            }
            if (two && !CoopSync.active()) {
                Scenario.skipped(name, "two-client case: run it with run-coop.sh -s " + name + (name.equals(TWO) ? "" : " -w"));
                continue;
            }
            ModUnderTest.require("killer560smod");
            LogTap.install();
            ctx.waitTicks(20);
            List<String> problems = new ArrayList<>();
            List<String> facts = new ArrayList<>();
            Report.caseStarted(name);
            try {
                switch (name) {
                    case MENU -> menu(ctx, problems, facts);
                    case UPNP -> upnp(ctx, problems, facts);
                    case HOST -> host(ctx, problems, facts);
                    case INVITE -> {
                        if (CoopSync.role().equals("host")) {
                            inviteHost(ctx, problems, facts);
                        } else {
                            inviteGuest(ctx, problems, facts);
                        }
                    }
                    case TUNNEL -> {
                        if (CoopSync.role().equals("host")) {
                            tunnelHost(ctx, problems, facts);
                        } else {
                            tunnelGuest(ctx, problems, facts);
                        }
                    }
                    default -> {
                        if (CoopSync.role().equals("host")) {
                            twoHost(ctx, problems, facts);
                        } else {
                            twoGuest(ctx, problems, facts);
                        }
                    }
                }
            } catch (Throwable t) {
                problems.add("threw: " + t);
                t.printStackTrace(System.out);
            } finally {
                if (two && !problems.isEmpty()) {
                    CoopSync.fail(String.join("; ", problems));
                }
                cleanup(ctx);
            }
            for (String f : facts) {
                println(name, "FACT " + f);
            }
            String verdict = problems.isEmpty() ? "PASS" : "FAIL";
            Report.caseFinished(name, verdict, "n/a (singleplayer)", String.join("; ", problems.isEmpty() ? facts : problems),
                    List.of(), List.of());
            SuiteVerdict.finished(name);
            for (String p : problems) {
                println(name, "PROBLEM " + p);
            }
            println(name, verdict);
            if (!problems.isEmpty()) {
                failed.add(name + ": " + String.join("; ", problems));
            }
        }
        if (!failed.isEmpty()) {
            throw new AssertionError(String.join(" | ", failed));
        }
    }

    // ============================================================================================ 1109-mp-menu

    private static void menu(ClientGameTestContext ctx, List<String> f, List<String> facts) throws Exception {
        SimRoomsStub stub = new SimRoomsStub();
        try {
            relay(ctx, stub);
            String mcv = (String) ModUnderTest.staticCall(R + "SimGuest", "mc", new Class<?>[]{}, new Object[]{});
            int protocol = ((Number) field(R + "SimCoop", "PROTOCOL")).intValue();
            String locked = stub.addRoom(UUID.randomUUID().toString(), "TkOwnerA", "Tk Locked Room", "sesame", 4, mcv, protocol, null);
            stub.addRoom(UUID.randomUUID().toString(), "TkOwnerB", "Tk Full Room", null, 2, mcv, protocol, null);
            String fullOwner = stub.calls().isEmpty() ? null : null;
            stub.addRoom(UUID.randomUUID().toString(), "TkOwnerC", "Tk Old Room", null, 4, mcv, protocol - 1, null);
            String otherMc = mcv.equals("26.3") ? "26.2" : "26.3";
            stub.addRoom(UUID.randomUUID().toString(), "TkOwnerD", "Tk Other Version Room", null, 4, otherMc, protocol, null);

            // The sim menu: Multiplayer between its own separator and Back, set apart from F7 Boss above it.
            ctx.runOnClient(mc -> McCompat.setScreen(mc, (Screen) ModUnderTest.staticCall(R + "SimMenuNav", "open",
                    new Class<?>[]{Screen.class}, new Object[]{new TitleScreen()})));
            ctx.waitTicks(5);
            int[] ys = ctx.computeOnClient(mc -> {
                AbstractWidget mp = widget(mc, "Multiplayer"), boss = widget(mc, "F7 Boss"), back = widget(mc, "Back");
                return mp == null || boss == null || back == null ? null
                        : new int[]{boss.getY() + boss.getHeight(), mp.getY(), mp.getY() + mp.getHeight(), back.getY()};
            });
            if (ys == null) {
                f.add("no Multiplayer / F7 Boss / Back entry in the sim menu: " + DesignerKit.labels(ctx));
                return;
            }
            facts.add("F7 Boss ends y " + ys[0] + ", Multiplayer y " + ys[1] + "-" + ys[2] + ", Back y " + ys[3]);
            expect(f, facts, "Multiplayer set apart below F7 Boss (gap > the 3 px item gap)", ys[1] - ys[0] > 6, true);
            expect(f, facts, "Multiplayer 12 px above Back (its separator between)", ys[3] - ys[2], 12);
            expect(f, facts, "clicked Multiplayer", DesignerKit.click(ctx, "Multiplayer"), true);
            ctx.waitTicks(3);
            expect(f, facts, "screen after Multiplayer", screenName(ctx), "SimMultiplayerScreen");
            expect(f, facts, "Create page open first", has(ctx, "> Create <") && has(ctx, "Create Room"), true);

            int lists0 = stub.calls("GET /simrooms/list").size();
            DesignerKit.click(ctx, "Join");
            boolean rows = paceUntil(ctx, () -> rowLabel(ctx, "[Locked] Tk Locked Room") != null, 10_000);
            expect(f, facts, "Join fetched the list", stub.calls("GET /simrooms/list").size() - lists0 >= 1, true);
            expect(f, facts, "locked room row", rows ? rowLabel(ctx, "[Locked] Tk Locked Room") : null,
                    "[" + mcv + "] [Locked] Tk Locked Room  -  TkOwnerA  1/4  F7 Boss  v1.1.0");
            // coopnet-1110: every version is listed (all=1), a room on another version says so and Join is refused.
            expect(f, facts, "list asked for every version", stub.calls("GET /simrooms/list").stream()
                    .allMatch(c -> c.contains("all=1")), true);
            expect(f, facts, "other-version row", rowLabel(ctx, "Tk Other Version Room"),
                    "[" + otherMc + " - other version] Tk Other Version Room  -  TkOwnerD  1/4  F7 Boss  v1.1.0");
            int joinsBefore = stub.calls("POST /simrooms/join").size();
            clickRow(ctx, "Tk Other Version Room");
            expect(f, facts, "other version: Join refused", message(ctx), "Host is on " + otherMc + " - open " + otherMc + " to join.");
            expect(f, facts, "other version: no ticket asked", stub.calls("POST /simrooms/join").size() - joinsBefore, 0);
            int lists1 = stub.calls("GET /simrooms/list").size();
            DesignerKit.click(ctx, "Refresh");
            paceUntil(ctx, () -> stub.calls("GET /simrooms/list").size() > lists1, 5_000);
            expect(f, facts, "Refresh fetched again", stub.calls("GET /simrooms/list").size() - lists1, 1);

            // Locked: the password box; wrong refused, right gets a ticket (LAN-only room: no endpoint to connect to).
            clickRow(ctx, "[Locked] Tk Locked Room");
            expect(f, facts, "password prompt", message(ctx), "Type the password for Tk Locked Room.");
            expect(f, facts, "password box shown", setBox(ctx, "Password", "nope"), true);
            DesignerKit.click(ctx, "Join");
            paceUntil(ctx, () -> message(ctx).equals("Wrong password."), 5_000);
            expect(f, facts, "wrong password", message(ctx), "Wrong password.");
            setBox(ctx, "Password", "sesame");
            DesignerKit.click(ctx, "Join");
            String noWay = "Could not reach Tk Locked Room: the relay tunnel is out of capacity for today";
            paceUntil(ctx, () -> message(ctx).startsWith(noWay), 5_000);
            expect(f, facts, "right password accepted (ticket; no endpoint and the stub's tunnel is off)", message(ctx), noWay
                    + "; try the owner's address or the LAN list.");
            expect(f, facts, "join calls (403 then 200)", stub.calls("POST /simrooms/join").size(), 2);
            facts.add("locked room " + locked + ", fullOwner " + fullOwner);

            // Full: max 2 with the owner and another player's live ticket.
            String fullId = idOf(ctx, stub, "Tk Full Room");
            stub.issueById(fullId, UUID.randomUUID().toString(), "TkOther");
            clickRow(ctx, "Tk Full Room");
            paceUntil(ctx, () -> message(ctx).equals("That room is full."), 5_000);
            expect(f, facts, "full room", message(ctx), "That room is full.");
            clickRow(ctx, "Tk Old Room");
            expect(f, facts, "other protocol", message(ctx), "That room runs a different sim version (protocol " + (protocol - 1) + ").");
            // Join by address validates first.
            setBox(ctx, "Address (host:port)", "host:notaport");
            List<String> joins = labels(ctx, "Join");
            facts.add("Join buttons: " + joins.size());
            clickLast(ctx, "Join");
            expect(f, facts, "bad address", message(ctx), "Type an address like 192.168.1.20:25565.");
        } finally {
            stub.stop();
        }
    }

    private static String idOf(ClientGameTestContext ctx, SimRoomsStub stub, String name) {
        return stub.idByName(name);
    }

    // ============================================================================================ 1109-mp-upnp

    private static void upnp(ClientGameTestContext ctx, List<String> f, List<String> facts) throws Exception {
        System.clearProperty(SSDP);
        String off = map(ctx, 25999);
        expect(f, facts, "test client without a gateway", off, "Your router did not open the port (UPnP is off in this test"
                + " client): friends on your network can still join; others need port forwarding of TCP port 25999.");
        try (DatagramSocket mute = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            System.setProperty(SSDP, "127.0.0.1:" + mute.getLocalPort());
            String none = map(ctx, 25998);
            expect(f, facts, "silent gateway", none.startsWith("Your router did not open the port (no router answered UPnP)")
                    && none.endsWith("TCP port 25998."), true);
            facts.add(none);
        }
        FakeGateway gw = new FakeGateway();
        try {
            gw.refuse = true;
            System.setProperty(SSDP, "127.0.0.1:" + gw.ssdpPort());
            String refused = map(ctx, 25997);
            expect(f, facts, "router refused", refused.startsWith("Your router did not open the port (it refused: error 718"
                    + " conflictinmappingentry)"), true);
            facts.add(refused);
            gw.refuse = false;
            String ok = map(ctx, 25996);
            expect(f, facts, "router mapped", ok, "Port 25996 is open on " + FakeGateway.EXTERNAL_IP);
            expect(f, facts, "mapped port", ModUnderTest.staticCall(R + "SimUpnp", "mappedPortForTest"), 25996);
            ModUnderTest.staticCall(R + "SimUpnp", "unmap");
            paceUntil(ctx, () -> gw.actions().contains("DeletePortMapping 25996"), 5_000);
            expect(f, facts, "gateway calls", gw.actions(), List.of("AddPortMapping 25997", "AddPortMapping 25996",
                    "GetExternalIPAddress ", "DeletePortMapping 25996"));
        } finally {
            gw.stop();
            System.clearProperty(SSDP);
        }
    }

    private static String map(ClientGameTestContext ctx, int port) {
        @SuppressWarnings("unchecked")
        CompletableFuture<Object> fut = (CompletableFuture<Object>) ModUnderTest.staticCall(R + "SimUpnp", "map",
                new Class<?>[]{int.class}, new Object[]{port});
        paceUntil(ctx, fut::isDone, 15_000);
        Object r = fut.getNow(null);
        return r == null ? "no answer in 15 s" : (String) ModUnderTest.call(r, "message", new Class<?>[]{}, new Object[]{});
    }

    // ============================================================================================ 1109-mp-host

    private static void host(ClientGameTestContext ctx, List<String> f, List<String> facts) throws Exception {
        SimRoomsStub stub = new SimRoomsStub();
        FakeGateway gw = new FakeGateway();
        try {
            String hostUuid = relay(ctx, stub);
            System.setProperty(SSDP, "127.0.0.1:" + gw.ssdpPort());
            if (!createRoom(ctx, f, facts, "Tk Host Room", "pw-host", 3)) {
                return;
            }
            boolean listed = paceUntil(ctx, () -> ModUnderTest.staticCall(HOSTC, "roomIdForTest") != null, 15_000);
            int port = (Integer) ModUnderTest.staticCall(HOSTC, "port");
            expect(f, facts, "listed on the relay", listed, true);
            expect(f, facts, "world published on the room's port", ctx.computeOnClient(mc -> mc.getSingleplayerServer().isPublished()
                    && mc.getSingleplayerServer().getPort() == port), true);
            expect(f, facts, "router line", ModUnderTest.staticCall(HOSTC, "upnpLine"), "Port " + port + " is open on "
                    + FakeGateway.EXTERNAL_IP + ": anyone can join.");
            expect(f, facts, "public address", ModUnderTest.staticCall(HOSTC, "publicAddress"), FakeGateway.EXTERNAL_IP + ":" + port);
            expect(f, facts, "list line", ModUnderTest.staticCall(HOSTC, "listLine"), "Listed in the room list.");
            List<String> creates = stub.calls("POST /simrooms/create");
            expect(f, facts, "one create", creates.size(), 1);
            String c = creates.isEmpty() ? "" : creates.get(0);
            expect(f, facts, "create carries name, password, max 3 and the public endpoint", c.contains("\"name\":\"Tk Host Room\"")
                    && c.contains("\"password\":\"pw-host\"") && c.contains("\"max\":3")
                    && c.contains("\"endpoint\":{\"host\":\"" + FakeGateway.EXTERNAL_IP + "\",\"port\":" + port + "}"), true);
            expect(f, facts, "gateway mapped the port", gw.actions().contains("AddPortMapping " + port), true);

            MinecraftServer s = ctx.computeOnClient(Minecraft::getSingleplayerServer);
            UUID host = ctx.computeOnClient(mc -> mc.player.getUUID());
            Vec3 at = ctx.computeOnClient(mc -> mc.player.position());
            int[] k0 = counts();

            // Ticketed guest B: the relay hands its ticket to the host's next refresh; its ack names it.
            GuestPlayer b = GuestPlayer.joinBesideHost(ctx, s, host, "TkTicket", at.add(2, 0, 0));
            String nonce = stub.issue(hostUuid, b.entity().getUUID().toString(), "TkTicket");
            long mark = LogTap.mark();
            GuestPlayer.onServer(ctx, s, () -> ModUnderTest.staticCall(HOSTC, "acked", new Class<?>[]{ServerPlayer.class, String.class},
                    new Object[]{b.entity(), nonce}));
            boolean admitted = paceUntil(ctx, () -> logSeen(mark, "[SimCoop] TkTicket admitted with a ticket"), 15_000);
            expect(f, facts, "ticketed guest admitted", admitted, true);
            // Ticketless guest A.
            long markA = LogTap.mark();
            GuestPlayer a = GuestPlayer.joinBesideHost(ctx, s, host, "TkNoTicket", at.add(-2, 0, 0));
            // Over Max 3: C is refused at join.
            GuestPlayer c3 = GuestPlayer.joinBesideHost(ctx, s, host, "TkOverMax", at.add(0, 0, 2));
            until(ctx, () -> counts()[1] - k0[1] >= 1 && !c3.listed(), 60);
            expect(f, facts, "guest over Max refused", counts()[1] - k0[1], 1);
            expect(f, facts, "refused guest not listed", c3.listed(), false);
            expect(f, facts, "its refusal line", logSeen(markA, "[SimCoop] TkOverMax refused: the room is full (3)"), true);
            boolean kicked = until(ctx, () -> !a.listed(), 400);
            expect(f, facts, "ticketless guest kicked", kicked, true);
            expect(f, facts, "its kick line", logSeen(markA, "[SimCoop] kicked TkNoTicket: Join through the Multiplayer menu."), true);
            expect(f, facts, "ticketed guest still in after the admit window", b.listed(), true);
            expect(f, facts, "Session list", guestsKey(), "TkTicket:true");

            // Requests run for the SENDER only.
            ServerPlayer hp = GuestPlayer.onServer(ctx, s, () -> s.getPlayerList().getPlayer(host));
            double hostSpeed = GuestPlayer.onServer(ctx, s, () -> hp.getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue());
            boolean hostFly = GuestPlayer.onServer(ctx, s, () -> hp.getAbilities().mayfly);
            int hostItems = GuestPlayer.onServer(ctx, s, () -> filled(hp));
            int bItems = GuestPlayer.onServer(ctx, s, () -> filled(b.entity()));
            expect(f, facts, "speed request", request(ctx, s, b, "SPEED", "300"), "Speed set to 300.");
            expect(f, facts, "fly request", request(ctx, s, b, "FLY", "on"), "Flight ON.");
            @SuppressWarnings("unchecked")
            List<String> items = (List<String>) ModUnderTest.staticCall(REQ, "items");
            String item = items.isEmpty() ? "TERMINATOR" : items.get(0);
            expect(f, facts, "item request", request(ctx, s, b, "ITEM", item), "Gave " + item + ".");
            expect(f, facts, "loadout request", request(ctx, s, b, "LOADOUT", item), "Loadout applied (1 slot).");
            b.moveTo(at.add(8, 0, 0), 0f, 0f);
            expect(f, facts, "teleport request", request(ctx, s, b, "TELEPORT", "owner"), "Teleported to the owner.");
            expect(f, facts, "guest's speed", GuestPlayer.onServer(ctx, s, () -> b.entity().getAttribute(Attributes.MOVEMENT_SPEED)
                    .getBaseValue()), 0.3);
            expect(f, facts, "guest may fly", GuestPlayer.onServer(ctx, s, () -> b.entity().getAbilities().mayfly), true);
            expect(f, facts, "guest got the item", GuestPlayer.onServer(ctx, s, () -> filled(b.entity())) - bItems, 1);
            expect(f, facts, "guest beside the owner", GuestPlayer.onServer(ctx, s, () -> b.entity().position()
                    .distanceTo(hp.position()) < 1.5), true);
            expect(f, facts, "owner's speed untouched", GuestPlayer.onServer(ctx, s, () -> hp.getAttribute(Attributes.MOVEMENT_SPEED)
                    .getBaseValue()), hostSpeed);
            expect(f, facts, "owner's flight untouched", GuestPlayer.onServer(ctx, s, () -> hp.getAbilities().mayfly), hostFly);
            expect(f, facts, "owner's inventory untouched", GuestPlayer.onServer(ctx, s, () -> filled(hp)), hostItems);
            expect(f, facts, "request from the owner refused", GuestPlayer.onServer(ctx, s, () -> handle(hp, "SPEED", "400")),
                    "Requests only work for guests in a hosted sim.");

            // The owner switches speed requests off on the Session page; it is refused and the switch is saved.
            openSession(ctx);
            expect(f, facts, "owner Session page", has(ctx, "Close Room") && has(ctx, "Kick")
                    && has(ctx, "Guests May Change Speed: ON"), true);
            // coop262's Move row: every widget of the owner's page is on screen and none overlaps another.
            expect(f, facts, "Session page fits (on screen, no overlap)", ctx.computeOnClient(mc -> layoutFault(mc)), "");
            DesignerKit.click(ctx, "Guests May Change Speed: ON");
            ctx.waitTicks(2);
            expect(f, facts, "switch shows OFF", has(ctx, "Guests May Change Speed: OFF"), true);
            ModUnderTest.staticCall(CFG, "load");
            expect(f, facts, "speed switch saved OFF (reloaded)", allows("SPEED"), false);
            expect(f, facts, "speed request now", request(ctx, s, b, "SPEED", "200"), "The owner does not let guests change speed.");
            expect(f, facts, "guest's speed unchanged", GuestPlayer.onServer(ctx, s, () -> b.entity().getAttribute(Attributes.MOVEMENT_SPEED)
                    .getBaseValue()), 0.3);
            setAllowed("SPEED", true);

            // Kick on the Session page.
            int kicks0 = counts()[0];
            long markK = LogTap.mark();
            openSession(ctx);
            DesignerKit.click(ctx, "Kick");
            expect(f, facts, "Kick removed the guest", until(ctx, () -> !b.listed(), 100), true);
            expect(f, facts, "kicks", counts()[0] - kicks0, 1);
            expect(f, facts, "kick line", logSeen(markK, "[SimCoop] kicked TkTicket: The owner kicked you."), true);

            // A guest in: the pause menu's quit asks first, and so does Close Room.
            GuestPlayer d = GuestPlayer.joinBesideHost(ctx, s, host, "TkLast", at.add(0, 0, -2));
            String n2 = stub.issue(hostUuid, d.entity().getUUID().toString(), "TkLast");
            GuestPlayer.onServer(ctx, s, () -> ModUnderTest.staticCall(HOSTC, "acked", new Class<?>[]{ServerPlayer.class, String.class},
                    new Object[]{d.entity(), n2}));
            until(ctx, () -> guestsKey().equals("TkLast:true"), 300);
            expect(f, facts, "second guest admitted", guestsKey(), "TkLast:true");
            ctx.runOnClient(mc -> McCompat.setScreen(mc, new PauseScreen(true)));
            ctx.waitTicks(3);
            String quit = CommonComponents.disconnectButtonLabel(true).getString();
            expect(f, facts, "clicked " + quit, DesignerKit.click(ctx, quit), true);
            ctx.waitTicks(2);
            expect(f, facts, "quit asks first", confirmText(ctx), "Leave the sim? 1 player will be disconnected.");
            DesignerKit.click(ctx, CommonComponents.GUI_NO.getString());
            ctx.waitTicks(3);
            expect(f, facts, "No keeps the world", ctx.computeOnClient(mc -> mc.level != null && mc.getSingleplayerServer() != null), true);
            openSession(ctx);
            DesignerKit.click(ctx, "Close Room");
            ctx.waitTicks(2);
            expect(f, facts, "Close Room asks first", confirmText(ctx), "Close the room? 1 player will be disconnected.");
            DesignerKit.click(ctx, CommonComponents.GUI_YES.getString());
            expect(f, facts, "closed", until(ctx, () -> !(Boolean) ModUnderTest.staticCall(HOSTC, "hosting"), 100), true);
            expect(f, facts, "guest disconnected", until(ctx, () -> !d.listed(), 100), true);
            expect(f, facts, "world unpublished", ctx.computeOnClient(mc -> mc.getSingleplayerServer() != null
                    && !mc.getSingleplayerServer().isPublished()), true);
            paceUntil(ctx, () -> stub.rooms() == 0 && gw.actions().contains("DeletePortMapping " + port), 10_000);
            expect(f, facts, "listing deleted", stub.rooms(), 0);
            expect(f, facts, "port mapping removed", gw.actions().contains("DeletePortMapping " + port), true);
            expect(f, facts, "refreshes sent while hosting", !stub.calls("POST /simrooms/refresh").isEmpty(), true);
        } finally {
            setAllowed("SPEED", true);
            stub.stop();
            gw.stop();
        }
    }

    private static String request(ClientGameTestContext ctx, MinecraftServer s, GuestPlayer g, String kind, String arg) {
        return GuestPlayer.onServer(ctx, s, () -> handle(g.entity(), kind, arg));
    }

    private static String handle(ServerPlayer sp, String kind, String arg) {
        Object k = ModUnderTest.enumValue(REQ + "$Kind", kind);
        return (String) ModUnderTest.staticCall(REQ, "handle", new Class<?>[]{ServerPlayer.class, int.class, String.class},
                new Object[]{sp, ((Enum<?>) k).ordinal(), arg});
    }

    private static int filled(ServerPlayer p) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    // ============================================================================================ 1109-mp-two

    private static final String ROOM = "Tk Coop Room";
    private static final String PW = "letmein";

    private static void twoHost(ClientGameTestContext ctx, List<String> f, List<String> facts) throws Exception {
        SimRoomsStub stub = new SimRoomsStub();
        try {
            CoopSync.put("mp-host-up", String.valueOf(stub.port()));
            CoopSync.await(ctx, "mp-guest-up", 900);
            String hostUuid = relay(ctx, stub);
            System.clearProperty(SSDP);
            if (!createRoom(ctx, f, facts, ROOM, PW, 3)) {
                return;
            }
            paceUntil(ctx, () -> ModUnderTest.staticCall(HOSTC, "roomIdForTest") != null, 15_000);
            expect(f, facts, "router line (no UPnP in a test client)", String.valueOf(ModUnderTest.staticCall(HOSTC, "upnpLine"))
                    .contains("UPnP is off in this test client"), true);
            expect(f, facts, "list line", ModUnderTest.staticCall(HOSTC, "listLine"), "Listed in the room list (joins go through the relay).");
            int port = (Integer) ModUnderTest.staticCall(HOSTC, "port");
            // Both test clients are offline accounts: like CoopSimTests.publish, take the published server off online mode
            // (the mod keeps vanilla's authentication; real accounts pass it).
            facts.add("online mode was " + ctx.computeOnClient(mc -> {
                boolean was = mc.getSingleplayerServer().usesAuthentication();
                mc.getSingleplayerServer().setUsesAuthentication(false);
                return was;
            }));
            JsonObject ep = new JsonObject();
            ep.addProperty("host", "127.0.0.1");
            ep.addProperty("port", port);
            stub.joinEndpoint = ep;
            String hostName = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
            facts.add("host " + hostName + " " + hostUuid + " on port " + port);
            long mark = LogTap.mark();
            CoopSync.put("mp-room", hostName);

            String guest = CoopSync.await(ctx, "mp-guest-in", 240).trim();
            boolean admitted = paceUntil(ctx, () -> logSeen(mark, "[SimCoop] " + guest + " admitted with a ticket"), 20_000);
            expect(f, facts, "guest admitted with its ticket", admitted, true);
            int kicks0 = counts()[0];
            CoopSync.pace(ctx, 14_000);
            expect(f, facts, "guest still in after the admit window", onServer(ctx, guest), true);
            expect(f, facts, "no kick", counts()[0] - kicks0, 0);
            CoopSync.put("mp-admit-checked", "1");

            CoopSync.await(ctx, "mp-guest-speed1", 120);
            MinecraftServer s = ctx.computeOnClient(Minecraft::getSingleplayerServer);
            boolean set = paceUntil(ctx, () -> Math.abs(speedOf(ctx, s, guest) - 0.25) < 1e-6, 10_000);
            expect(f, facts, "guest's speed set to 250 by its request", set, true);
            expect(f, facts, "owner's own speed", speedOf(ctx, s, hostName) != 0.25, true);
            openSession(ctx);
            DesignerKit.click(ctx, "Guests May Change Speed: ON");
            ctx.waitTicks(2);
            expect(f, facts, "speed switched OFF", allows("SPEED"), false);
            CoopSync.put("mp-speed-off", "1");
            CoopSync.await(ctx, "mp-guest-speed2", 120);
            CoopSync.pace(ctx, 1000);
            expect(f, facts, "refused request left the speed", speedOf(ctx, s, guest), 0.25);
            expect(f, facts, "last request", String.valueOf(ModUnderTest.staticCall(REQ, "lastForTest"))
                    .endsWith("-> The owner does not let guests change speed."), true);
            DesignerKit.click(ctx, "Guests May Change Speed: OFF");
            ctx.waitTicks(2);

            // Owner kick from the Session page.
            CoopSync.pace(ctx, 500);
            openSession(ctx);
            expect(f, facts, "clicked Kick", DesignerKit.click(ctx, "Kick"), true);
            expect(f, facts, "guest gone after Kick", paceUntil(ctx, () -> !onServer(ctx, guest), 10_000), true);
            ctx.runOnClient(mc -> McCompat.setScreen(mc, null));
            CoopSync.put("mp-kicked", "1");

            CoopSync.await(ctx, "mp-guest-in2", 240);
            long mark2 = LogTap.mark();
            expect(f, facts, "rejoined guest admitted", paceUntil(ctx, () -> logSeen(mark2, "[SimCoop] " + guest + " admitted")
                    || guestsKey().equals(guest + ":true"), 20_000), true);

            // The owner leaves: pause, quit, the warning, Yes.
            ctx.runOnClient(mc -> McCompat.setScreen(mc, new PauseScreen(true)));
            CoopSync.pace(ctx, 300);
            DesignerKit.click(ctx, CommonComponents.disconnectButtonLabel(true).getString());
            CoopSync.pace(ctx, 300);
            expect(f, facts, "quit asks first", confirmText(ctx), "Leave the sim? 1 player will be disconnected.");
            DesignerKit.click(ctx, CommonComponents.GUI_YES.getString());
            boolean left = paceUntil(ctx, () -> ctx.computeOnClient(mc -> mc.level == null), 30_000);
            expect(f, facts, "owner left the world", left, true);
            CoopSync.put("mp-host-left", "1");
            paceUntil(ctx, () -> stub.rooms() == 0, 10_000);
            expect(f, facts, "listing deleted on leave", stub.rooms(), 0);
            String verdict = CoopSync.await(ctx, "mp-guest-done", 180).trim();
            facts.add("guest verdict: " + verdict);
            expect(f, facts, "guest's own checks", verdict, "PASS");
        } finally {
            for (String c : stub.calls("POST /simrooms/join")) {
                facts.add("stub " + c);
            }
            setAllowed("SPEED", true);
            stub.stop();
        }
    }

    // ============================================================================================ 1109-mp-invite

    private static final String IROOM = "Tk Invite Room";
    private static final String REACH = R + "SimReach";
    /** The TOKEN_SECRET run-coop.sh -w gives its wrangler dev relay (never a real one). */
    private static final String E2E_SECRET = "simreach-e2e-secret-not-real";

    /** A relay bearer token exactly as the relay's src/token.ts makes one: base64url(payload).base64url(hmac). */
    private static String mint(String uuid, String name) throws Exception {
        var b64 = java.util.Base64.getUrlEncoder().withoutPadding();
        String body = b64.encodeToString(("{\"uuid\":\"" + uuid + "\",\"name\":\"" + name + "\",\"exp\":"
                + (System.currentTimeMillis() + 3_600_000L) + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(E2E_SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        return body + "." + b64.encodeToString(mac.doFinal(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /** Points the client at run-coop.sh's local relay with its own token, and has SimReach connect again. */
    private static boolean localRelay(ClientGameTestContext ctx, List<String> f, String uuid, String name) throws Exception {
        String port = CoopSync.get("relay-port");
        if (port == null || port.isBlank()) {
            f.add("no local relay: run it with run-coop.sh -s " + INVITE + " -w");
            return false;
        }
        System.setProperty(RELAY, "http://127.0.0.1:" + port.trim());
        token(mint(uuid, name));
        ModUnderTest.staticCall(REACH, "reconnectForTest");
        return true;
    }

    private static boolean reachConnected() { return (Boolean) ModUnderTest.staticCall(REACH, "connectedForTest"); }

    private static int invitesReceived() { return (Integer) ModUnderTest.staticCall(REACH, "receivedForTest"); }

    /** Focuses a box and presses Enter (its wired button runs). */
    private static boolean enterIn(ClientGameTestContext ctx, String label) {
        boolean focused = ctx.computeOnClient(mc -> {
            Screen s = McCompat.screen(mc);
            for (var ch : s == null ? List.<net.minecraft.client.gui.components.events.GuiEventListener>of() : s.children()) {
                if (ch instanceof EditBox b && b.getMessage().getString().equals(label)) {
                    s.setFocused(b);
                    return true;
                }
            }
            return false;
        });
        if (focused) {
            ctx.getInput().pressKey(dev.testkit.compat.McKeys.nat(257)); // GLFW_KEY_ENTER
            ctx.waitTicks(2);
        }
        return focused;
    }

    private static void inviteHost(ClientGameTestContext ctx, List<String> f, List<String> facts) throws Exception {
        FakeGateway gw = new FakeGateway();
        try {
            CoopSync.put("inv-host-up", "1");
            CoopSync.await(ctx, "inv-guest-up", 900);
            String uuid = ctx.computeOnClient(mc -> mc.getUser().getProfileId().toString());
            String hostName = ctx.computeOnClient(mc -> mc.getUser().getName());
            if (!localRelay(ctx, f, uuid, hostName)) {
                return;
            }
            // UPnP against the fake gateway, whose "public" address is loopback here, so the relay's join answer
            // carries an endpoint the guest can really connect to (no LAN pick).
            FakeGateway.externalIp = "127.0.0.1";
            System.setProperty(SSDP, "127.0.0.1:" + gw.ssdpPort());
            if (!createRoom(ctx, f, facts, IROOM, PW, 3)) {
                return;
            }
            expect(f, facts, "listed on the local relay", paceUntil(ctx, () -> ModUnderTest.staticCall(HOSTC, "roomIdForTest") != null,
                    20_000), true);
            int port = (Integer) ModUnderTest.staticCall(HOSTC, "port");
            expect(f, facts, "public address", ModUnderTest.staticCall(HOSTC, "publicAddress"), "127.0.0.1:" + port);
            ctx.runOnClient(mc -> mc.getSingleplayerServer().setUsesAuthentication(false));
            CoopSync.put("inv-room", hostName);
            String guest = CoopSync.await(ctx, "inv-off", 300).trim();
            // Guest's setting OFF: not on the reach list, and an invite by name is refused.
            ModUnderTest.staticCall("com.killer560.hub.modusers.ModUsers", "onPresence", new Class<?>[]{String.class, String.class},
                    new Object[]{guest, "cheat"});
            openSession(ctx);
            expect(f, facts, "clicked Invite Players", DesignerKit.click(ctx, "Invite Players"), true);
            CoopSync.pace(ctx, 300);
            expect(f, facts, "Invite page has the by-name box", has(ctx, "Invite by name"), true);
            CoopSync.pace(ctx, 3000);
            expect(f, facts, "OFF: the reach was asked", String.valueOf(ModUnderTest.staticCall(R + "SimRooms", "calls")).contains("POST /simrooms/reach"), true);
            expect(f, facts, "OFF: not reachable", String.valueOf(ModUnderTest.staticCall(R + "SimMultiplayerScreen", "reachableForTest")), "[]");
            setBox(ctx, "Invite by name", guest);
            expect(f, facts, "Enter in the by-name box", enterIn(ctx, "Invite by name"), true);
            boolean refused = paceUntil(ctx, () -> message(ctx).contains("cannot take an invite now"), 10_000);
            expect(f, facts, "OFF: invite refused", refused ? "refused" : message(ctx), "refused");
            CoopSync.put("inv-off-checked", "1");

            // ON: the reach list shows the guest (a mod user here), and its Invite button sends.
            CoopSync.await(ctx, "inv-on", 120);
            CoopSync.pace(ctx, 31_000); // Refresh asks at most once per 30 s
            DesignerKit.click(ctx, "Refresh");
            boolean listed = paceUntil(ctx, () -> String.valueOf(ModUnderTest.staticCall(R + "SimMultiplayerScreen", "reachableForTest"))
                    .equals("[" + guest + "]"), 10_000);
            expect(f, facts, "ON: guest reachable", listed ? "listed" : String.valueOf(ModUnderTest.staticCall(R + "SimMultiplayerScreen",
                    "reachableForTest")), "listed");
            expect(f, facts, "clicked the row's Invite", DesignerKit.click(ctx, "Invite"), true);
            boolean sent = paceUntil(ctx, () -> message(ctx).equals("Invited " + guest + "."), 10_000);
            expect(f, facts, "first invite sent", sent ? "sent" : message(ctx), "sent");
            expect(f, facts, "row now reads Invited", has(ctx, "Invited"), true);
            CoopSync.put("inv-sent1", "1");

            // Declined (nothing comes back); a second invite by name after the 30 s re-invite window.
            CoopSync.await(ctx, "inv-declined", 120);
            expect(f, facts, "guest still not in the room after Decline", onServer(ctx, guest), false);
            CoopSync.pace(ctx, 31_000);
            setBox(ctx, "Invite by name", guest);
            enterIn(ctx, "Invite by name");
            boolean sent2 = paceUntil(ctx, () -> message(ctx).equals("Invited " + guest + "."), 10_000);
            expect(f, facts, "second invite sent", sent2 ? "sent" : message(ctx), "sent");
            long mark = LogTap.mark();
            CoopSync.put("inv-sent2", "1");

            CoopSync.await(ctx, "inv-joined", 240);
            boolean admitted = paceUntil(ctx, () -> logSeen(mark, "[SimCoop] " + guest + " admitted with a ticket")
                    || guestsKey().equals(guest + ":true"), 20_000);
            expect(f, facts, "invited guest admitted with its ticket (no password)", admitted, true);
            CoopSync.put("inv-host-done", "1");
            String verdict = CoopSync.await(ctx, "inv-guest-done", 180).trim();
            facts.add("guest verdict: " + verdict);
            expect(f, facts, "guest's own checks", verdict, "PASS");
        } finally {
            FakeGateway.externalIp = FakeGateway.EXTERNAL_IP;
            System.clearProperty(SSDP);
            gw.stop();
        }
    }

    private static void inviteGuest(ClientGameTestContext ctx, List<String> f, List<String> facts) throws Exception {
        try {
            CoopSync.put("inv-guest-up", "1");
            CoopSync.await(ctx, "inv-host-up", 900);
            String me = ctx.computeOnClient(mc -> mc.getUser().getName());
            UUID offline = net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(me);
            if (!localRelay(ctx, f, offline.toString(), me)) {
                return;
            }
            expect(f, facts, "Receive Sim Invites defaults ON", invitesOn(), true);
            expect(f, facts, "socket up while ON", paceUntil(ctx, SimInviteWait::connected, 20_000), true);
            CoopSync.await(ctx, "inv-room", 600);

            // OFF: the socket closes, the setting survives a reload, nothing arrives.
            setInvites(ctx, false);
            ModUnderTest.staticCall(CFG, "load");
            expect(f, facts, "OFF survives a reload", invitesOn(), false);
            expect(f, facts, "socket closed while OFF", paceUntil(ctx, () -> !SimInviteWait.connected(), 10_000), true);
            CoopSync.put("inv-off", me);
            CoopSync.await(ctx, "inv-off-checked", 180);
            expect(f, facts, "nothing received while OFF", invitesReceived(), 0);

            setInvites(ctx, true);
            expect(f, facts, "socket back when ON", paceUntil(ctx, SimInviteWait::connected, 20_000), true);
            ctx.runOnClient(mc -> McCompat.setScreen(mc, new TitleScreen()));
            CoopSync.put("inv-on", "1");

            // First invite: the prompt on the title screen, Decline.
            boolean got = paceUntil(ctx, () -> invitesReceived() >= 1, 120_000);
            expect(f, facts, "first invite received", got, true);
            expect(f, facts, "prompt shown", paceUntil(ctx, () -> "ConfirmScreen".equals(screenName(ctx)), 5_000), true);
            expect(f, facts, "prompt text", confirmText(ctx).contains(" invited you to " + IROOM), true);
            expect(f, facts, "clicked Decline", DesignerKit.click(ctx, "Decline"), true);
            CoopSync.pace(ctx, 500);
            expect(f, facts, "Decline: nothing pending", String.valueOf(ModUnderTest.staticCall(REACH, "pending")), "[]");
            expect(f, facts, "Decline: still out of a world", ctx.computeOnClient(mc -> mc.level == null), true);
            CoopSync.put("inv-declined", "1");

            // Second invite: Join, no password asked.
            got = paceUntil(ctx, () -> invitesReceived() >= 2, 120_000);
            expect(f, facts, "second invite received", got, true);
            expect(f, facts, "prompt again", paceUntil(ctx, () -> "ConfirmScreen".equals(screenName(ctx)), 5_000), true);
            ModUnderTest.staticCall(R + "SimRooms", "clearCallsForTest");
            expect(f, facts, "clicked Join", DesignerKit.click(ctx, "Join"), true);
            if (!inWorld(ctx)) {
                f.add("did not join with the invite: " + message(ctx) + " / " + screenName(ctx));
                return;
            }
            expect(f, facts, "isGuest", until(ctx, () -> Boolean.TRUE.equals(ctx.computeOnClient(mc -> ModUnderTest.staticCall(
                    R + "SimState", "isGuest", new Class<?>[]{Minecraft.class}, new Object[]{mc}))), 200), true);
            facts.add("relay calls " + ModUnderTest.staticCall(R + "SimRooms", "calls"));
            CoopSync.put("inv-joined", me);
            CoopSync.await(ctx, "inv-host-done", 120);
        } finally {
            try {
                setInvites(ctx, true);
            } catch (Throwable ignored) {
                // cleanup
            }
            CoopSync.put("inv-guest-done", f.isEmpty() ? "PASS" : "FAIL: " + String.join("; ", f));
            CoopSync.pace(ctx, 1000);
        }
    }

    // ============================================================================================ 1110-mp-tunnel

    private static final String TROOM = "Tk Tunnel Room";

    /** No UPnP (no gateway named): the room lists with no endpoint and keeps a relay tunnel socket open. */
    private static void tunnelHost(ClientGameTestContext ctx, List<String> f, List<String> facts) throws Exception {
        CoopSync.put("tun-host-up", "1");
        CoopSync.await(ctx, "tun-guest-up", 900);
        String uuid = ctx.computeOnClient(mc -> mc.getUser().getProfileId().toString());
        String hostName = ctx.computeOnClient(mc -> mc.getUser().getName());
        if (!localRelay(ctx, f, uuid, hostName)) {
            return;
        }
        System.clearProperty(SSDP);
        long mark = LogTap.mark();
        if (!createRoom(ctx, f, facts, TROOM, PW, 3)) {
            return;
        }
        expect(f, facts, "listed on the local relay", paceUntil(ctx, () -> ModUnderTest.staticCall(HOSTC, "roomIdForTest") != null,
                20_000), true);
        expect(f, facts, "no public address (UPnP off)", ModUnderTest.staticCall(HOSTC, "publicAddress"), null);
        expect(f, facts, "list line", ModUnderTest.staticCall(HOSTC, "listLine"), "Listed in the room list (joins go through the relay).");
        expect(f, facts, "tunnel socket up", paceUntil(ctx, () -> logSeen(mark, "[SimTunnel] host socket up"), 20_000), true);
        ctx.runOnClient(mc -> mc.getSingleplayerServer().setUsesAuthentication(false));
        CoopSync.put("tun-room", hostName);
        String guest = CoopSync.await(ctx, "tun-guest-in", 300).trim();
        expect(f, facts, "guest admitted with its ticket", paceUntil(ctx, () -> logSeen(mark, "[SimCoop] " + guest + " admitted with a ticket")
                || guestsKey().equals(guest + ":true"), 20_000), true);
        MinecraftServer s = ctx.computeOnClient(Minecraft::getSingleplayerServer);
        double[] p0 = posOf(ctx, s, guest);
        CoopSync.put("tun-move", p0 == null ? "none" : "1");
        CoopSync.await(ctx, "tun-moved", 120);
        double[] p1 = posOf(ctx, s, guest);
        double moved = p0 == null || p1 == null ? -1 : Math.hypot(p1[0] - p0[0], p1[2] - p0[2]);
        facts.add(String.format("guest moved %.2f blocks on the host's server", moved));
        expect(f, facts, "guest moved on the host's server (through the tunnel)", moved > 1.0, true);
        CoopSync.put("tun-host-done", "1");
        String verdict = CoopSync.await(ctx, "tun-guest-done", 180).trim();
        facts.add("guest verdict: " + verdict);
        expect(f, facts, "guest's own checks", verdict, "PASS");
    }

    private static void tunnelGuest(ClientGameTestContext ctx, List<String> f, List<String> facts) throws Exception {
        try {
            CoopSync.put("tun-guest-up", "1");
            CoopSync.await(ctx, "tun-host-up", 900);
            String me = ctx.computeOnClient(mc -> mc.getUser().getName());
            UUID offline = net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(me);
            if (!localRelay(ctx, f, offline.toString(), me)) {
                return;
            }
            String hostName = CoopSync.await(ctx, "tun-room", 600).trim();
            ctx.runOnClient(mc -> McCompat.setScreen(mc, multiplayer(new TitleScreen())));
            CoopSync.pace(ctx, 300);
            DesignerKit.click(ctx, "Join");
            String want = "[" + SimMcv.mc() + "] [Locked] " + TROOM + "  -  " + hostName;
            boolean row = paceUntil(ctx, () -> {
                String l = rowLabel(ctx, "[Locked] " + TROOM);
                if (l == null) {
                    DesignerKit.click(ctx, "Refresh");
                }
                return l != null && l.startsWith(want);
            }, 30_000);
            expect(f, facts, "room row (own version tag)", row ? "found" : rowLabel(ctx, "[Locked] " + TROOM), "found");
            ModUnderTest.staticCall(R + "SimRooms", "clearCallsForTest");
            long mark = LogTap.mark();
            clickRow(ctx, "[Locked] " + TROOM);
            setBox(ctx, "Password", PW);
            DesignerKit.click(ctx, "Join");
            facts.add("after Join: " + message(ctx));
            if (!inWorld(ctx)) {
                f.add("did not join through the tunnel: " + message(ctx) + " / " + screenName(ctx));
                return;
            }
            String server = ctx.computeOnClient(mc -> mc.getCurrentServer() == null ? null : mc.getCurrentServer().ip);
            facts.add("connected to " + server);
            expect(f, facts, "connected through the local tunnel port", server != null && server.startsWith("127.0.0.1:"), true);
            expect(f, facts, "no direct address to try (logged nothing direct)", logSeen(mark, "direct address not reachable"), false);
            expect(f, facts, "isGuest", until(ctx, () -> Boolean.TRUE.equals(ctx.computeOnClient(mc -> ModUnderTest.staticCall(
                    R + "SimState", "isGuest", new Class<?>[]{Minecraft.class}, new Object[]{mc}))), 200), true);
            CoopSync.put("tun-guest-in", me);
            String go = CoopSync.await(ctx, "tun-move", 120).trim();
            expect(f, facts, "host sees me", go, "1");
            ctx.runOnClient(mc -> {
                McCompat.setScreen(mc, null);
                mc.options.keyUp.setDown(true);
            });
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> mc.options.keyUp.setDown(false));
            ctx.waitTicks(10);
            expect(f, facts, "still connected after walking", ctx.computeOnClient(mc -> mc.level != null), true);
            CoopSync.put("tun-moved", "1");
            CoopSync.await(ctx, "tun-host-done", 120);
        } finally {
            ctx.runOnClient(mc -> mc.options.keyUp.setDown(false));
            CoopSync.put("tun-guest-done", f.isEmpty() ? "PASS" : "FAIL: " + String.join("; ", f));
            CoopSync.pace(ctx, 1000);
        }
    }

    private static double[] posOf(ClientGameTestContext ctx, MinecraftServer s, String name) {
        return GuestPlayer.onServer(ctx, s, () -> {
            ServerPlayer p = s.getPlayerList().getPlayerByName(name);
            return p == null ? null : new double[]{p.getX(), p.getY(), p.getZ()};
        });
    }

    private static boolean invitesOn() {
        return (Boolean) ModUnderTest.call(ModUnderTest.staticCall(CFG, "get"), "receiveInvites", new Class<?>[]{}, new Object[]{});
    }

    private static void setInvites(ClientGameTestContext ctx, boolean on) {
        ctx.runOnClient(mc -> ModUnderTest.call(ModUnderTest.staticCall(CFG, "get"), "setReceiveInvites",
                new Class<?>[]{boolean.class}, new Object[]{on}));
    }

    /** Small named suppliers for paceUntil. */
    private static final class SimInviteWait {
        static boolean connected() { return reachConnected(); }
    }

    private static void twoGuest(ClientGameTestContext ctx, List<String> f, List<String> facts) {
        CoopSync.put("mp-guest-up", "1");
        int stubPort = Integer.parseInt(CoopSync.await(ctx, "mp-host-up", 900).trim());
        System.setProperty(RELAY, "http://127.0.0.1:" + stubPort);
        String me = ctx.computeOnClient(mc -> mc.getUser().getName());
        UUID offline = net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(me);
        token(offline.toString());
        String hostName = CoopSync.await(ctx, "mp-room", 600).trim();
        try {
            // Join page: the room, a wrong password, the right one.
            ctx.runOnClient(mc -> McCompat.setScreen(mc, multiplayer(new TitleScreen())));
            CoopSync.pace(ctx, 300);
            DesignerKit.click(ctx, "Join");
            String want = "[" + SimMcv.mc() + "] [Locked] " + ROOM + "  -  " + hostName + "  1/3";
            boolean row = paceUntil(ctx, () -> {
                String l = rowLabel(ctx, "[Locked] " + ROOM);
                if (l == null) {
                    DesignerKit.click(ctx, "Refresh");
                }
                return l != null && l.startsWith(want);
            }, 30_000);
            expect(f, facts, "room row on the Join page", row ? "found" : rowLabel(ctx, "[Locked] " + ROOM), "found");
            clickRow(ctx, "[Locked] " + ROOM);
            setBox(ctx, "Password", "wrong-one");
            DesignerKit.click(ctx, "Join");
            paceUntil(ctx, () -> message(ctx).equals("Wrong password."), 10_000);
            expect(f, facts, "wrong password", message(ctx), "Wrong password.");
            expect(f, facts, "password box for the right one", setBox(ctx, "Password", PW), true);
            expect(f, facts, "clicked Join (right password)", DesignerKit.click(ctx, "Join"), true);
            facts.add("after the click: " + message(ctx) + " / " + screenName(ctx));
            if (!inWorld(ctx)) {
                f.add("did not join with the right password: " + message(ctx) + " / " + screenName(ctx));
                return;
            }
            // The hello comes a few ticks after the world opens.
            expect(f, facts, "isGuest", until(ctx, () -> Boolean.TRUE.equals(ctx.computeOnClient(mc -> ModUnderTest.staticCall(
                    R + "SimState", "isGuest", new Class<?>[]{Minecraft.class}, new Object[]{mc}))), 200), true);
            CoopSync.put("mp-guest-in", me);
            CoopSync.await(ctx, "mp-admit-checked", 120);
            expect(f, facts, "still connected after the admit window", ctx.computeOnClient(mc -> mc.level != null), true);

            // Session page through the pause menu's "Sim Session".
            ctx.runOnClient(mc -> McCompat.setScreen(mc, new PauseScreen(true)));
            CoopSync.pace(ctx, 300);
            expect(f, facts, "pause menu has Sim Session", DesignerKit.click(ctx, "Sim Session"), true);
            CoopSync.pace(ctx, 300);
            expect(f, facts, "Session page", screenName(ctx), "SimMultiplayerScreen");
            expect(f, facts, "guest buttons", has(ctx, "Teleport to Owner") && has(ctx, "Fly ON") && has(ctx, "Apply My Loadout")
                    && has(ctx, "Leave") && !has(ctx, "Close Room") && !has(ctx, "Create Room"), true);
            long mark = LogTap.mark();
            setBox(ctx, "Speed (100-500)", "250");
            DesignerKit.click(ctx, "Set");
            expect(f, facts, "request sent", message(ctx), "Asked the owner: Set.");
            expect(f, facts, "owner's answer", paceUntil(ctx, () -> chatSeen(mark, "[Sim] Speed set to 250."), 10_000), true);
            CoopSync.put("mp-guest-speed1", "1");
            CoopSync.await(ctx, "mp-speed-off", 120);
            long mark2 = LogTap.mark();
            setBox(ctx, "Speed (100-500)", "300");
            DesignerKit.click(ctx, "Set");
            expect(f, facts, "refused", paceUntil(ctx, () -> chatSeen(mark2, "[Sim] The owner does not let guests change speed."),
                    10_000), true);
            CoopSync.put("mp-guest-speed2", "1");

            // Kicked: the ended screen, then back to the sim menu.
            boolean ended = paceUntil(ctx, () -> "SimSessionEndedScreen".equals(screenName(ctx)), 60_000);
            expect(f, facts, "ended screen after the kick", ended, true);
            expect(f, facts, "kick reason", ModUnderTest.staticCall(R + "SimGuest", "endedForTest"), "the owner kicked you");
            expect(f, facts, "clicked Back to Sim Menu", DesignerKit.click(ctx, "Back to Sim Menu"), true);
            CoopSync.pace(ctx, 300);
            String after = screenName(ctx);
            expect(f, facts, "a sim menu after Back", after != null && !after.equals("SimSessionEndedScreen")
                    && !after.equals("TitleScreen"), true);
            facts.add("after Back: " + after);
            CoopSync.await(ctx, "mp-kicked", 60);

            // Rejoin, then the owner leaves.
            ctx.runOnClient(mc -> McCompat.setScreen(mc, multiplayer(new TitleScreen())));
            CoopSync.pace(ctx, 300);
            DesignerKit.click(ctx, "Join");
            paceUntil(ctx, () -> rowLabel(ctx, "[Locked] " + ROOM) != null, 20_000);
            clickRow(ctx, "[Locked] " + ROOM);
            setBox(ctx, "Password", PW);
            DesignerKit.click(ctx, "Join");
            if (!inWorld(ctx)) {
                f.add("rejoin failed: " + message(ctx) + " / " + screenName(ctx));
                return;
            }
            CoopSync.put("mp-guest-in2", me);
            boolean ended2 = paceUntil(ctx, () -> "SimSessionEndedScreen".equals(screenName(ctx)), 90_000);
            expect(f, facts, "ended screen after the owner left", ended2, true);
            expect(f, facts, "leave reason", ModUnderTest.staticCall(R + "SimGuest", "endedForTest"), "the owner left");
            String line = ctx.computeOnClient(mc -> {
                Screen sc = McCompat.screen(mc);
                return sc == null ? null : (String) ModUnderTest.call(sc, "line", new Class<?>[]{}, new Object[]{});
            });
            expect(f, facts, "ended line", line, "Session ended: the owner left");
        } finally {
            CoopSync.put("mp-guest-done", f.isEmpty() ? "PASS" : "FAIL: " + String.join("; ", f));
            CoopSync.pace(ctx, 1000);
        }
    }

    private static boolean inWorld(ClientGameTestContext ctx) {
        return paceUntil(ctx, () -> ctx.computeOnClient(mc -> mc.player != null && mc.level != null)
                && ctx.computeOnClient(mc -> McCompat.screen(mc) == null || !(McCompat.screen(mc)
                instanceof net.minecraft.client.gui.screens.ConnectScreen)), 60_000)
                && ctx.computeOnClient(mc -> mc.player != null);
    }

    private static boolean onServer(ClientGameTestContext ctx, String name) {
        return ctx.computeOnClient(mc -> mc.getSingleplayerServer() != null
                && mc.getSingleplayerServer().getPlayerList().getPlayerByName(name) != null);
    }

    private static double speedOf(ClientGameTestContext ctx, MinecraftServer s, String name) {
        return GuestPlayer.onServer(ctx, s, () -> {
            ServerPlayer p = s.getPlayerList().getPlayerByName(name);
            return p == null ? -1 : p.getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue();
        });
    }

    // ============================================================================================ shared

    /** Points the room list at the stub and logs in as this client's own uuid (the stub's token). Returns the uuid. */
    private static String relay(ClientGameTestContext ctx, SimRoomsStub stub) {
        System.setProperty(RELAY, stub.base());
        String uuid = ctx.computeOnClient(mc -> mc.getUser().getProfileId().toString());
        stub.name(uuid, ctx.computeOnClient(mc -> mc.getUser().getName()));
        token(uuid);
        return uuid;
    }

    private static void token(String t) {
        ModUnderTest.staticCall("com.killer560.hub.relay.RelayCalls", "setTokenForTest", new Class<?>[]{String.class}, new Object[]{t});
    }

    /** Create from the title: the Create page's boxes and Max, Create Room, the F7 boss opening, hosting. */
    private static boolean createRoom(ClientGameTestContext ctx, List<String> f, List<String> facts, String name, String pw, int max) {
        ctx.runOnClient(mc -> McCompat.setScreen(mc, multiplayer(new TitleScreen())));
        ctx.waitTicks(3);
        setBox(ctx, "Room Name", name);
        setBox(ctx, "Password (optional)", pw);
        // A 2..5 slider since coopsim-ui-1110: click the track at the value's place (4 stops, knob 6 px wide).
        double[] box = ctx.computeOnClient(mc -> {
            AbstractWidget w = first(mc, l -> l.startsWith("Max Players: "));
            return w == null ? null : new double[]{w.getX(), w.getY() + w.getHeight() / 2.0, w.getWidth()};
        });
        if (box != null) {
            DesignerKit.clickAt(ctx, box[0] + 3 + (box[2] - 6) * (max - 2) / 3.0, box[1], 0);
        }
        expect(f, facts, "Max Players", rowLabel(ctx, "Max Players: "), "Max Players: " + max);
        long mark = LogTap.mark();
        DesignerKit.click(ctx, "Create Room");
        String msg = message(ctx);
        if (!msg.isEmpty()) {
            f.add("Create Room said: " + msg);
            return false;
        }
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        boolean up = paceUntil(ctx, () -> (Boolean) ModUnderTest.staticCall(HOSTC, "hosting"), 120_000);
        expect(f, facts, "hosting after Create", up, true);
        expect(f, facts, "boss sim running", (Boolean) ModUnderTest.staticCall(FIGHT, "running"), true);
        expect(f, facts, "hosting line", logSeen(mark, "[SimCoop] hosting \"" + name + "\" on port"), true);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 600);
        return up;
    }

    private static Screen multiplayer(Screen parent) {
        try {
            return (Screen) Class.forName(R + "SimMultiplayerScreen").getConstructor(Screen.class).newInstance(parent);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no SimMultiplayerScreen", e);
        }
    }

    private static void openSession(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> McCompat.setScreen(mc, multiplayer(null)));
        ctx.waitTicks(3);
    }

    private static int[] counts() { return (int[]) ModUnderTest.staticCall(HOSTC, "countsForTest"); }

    private static String guestsKey() {
        StringBuilder b = new StringBuilder();
        for (Object g : (List<?>) ModUnderTest.staticCall(HOSTC, "guests")) {
            if (b.length() > 0) {
                b.append(',');
            }
            b.append(ModUnderTest.call(g, "name", new Class<?>[]{}, new Object[]{})).append(':')
                    .append(ModUnderTest.call(g, "admitted", new Class<?>[]{}, new Object[]{}));
        }
        return b.toString();
    }

    private static Object kind(String k) { return ModUnderTest.enumValue(REQ + "$Kind", k); }

    private static boolean allows(String k) {
        Object cfg = ModUnderTest.staticCall(CFG, "get");
        return (Boolean) ModUnderTest.call(cfg, "allows", new Class<?>[]{kind(k).getClass()}, new Object[]{kind(k)});
    }

    private static void setAllowed(String k, boolean on) {
        try {
            Object cfg = ModUnderTest.staticCall(CFG, "get");
            ModUnderTest.call(cfg, "setAllowed", new Class<?>[]{kind(k).getClass(), boolean.class}, new Object[]{kind(k), on});
        } catch (Throwable ignored) {
            // cleanup
        }
    }

    private static Object field(String cls, String name) throws ReflectiveOperationException {
        var fl = Class.forName(cls).getDeclaredField(name);
        fl.setAccessible(true);
        return fl.get(null);
    }

    /** "" when every visible widget of the open screen lies inside it and no two overlap, else the first fault. */
    private static String layoutFault(Minecraft mc) {
        Screen s = McCompat.screen(mc);
        if (s == null) return "no screen";
        List<AbstractWidget> ws = new java.util.ArrayList<>();
        for (var ch : s.children()) {
            if (ch instanceof AbstractWidget w && w.visible) ws.add(w);
        }
        if (ws.size() < 4) return "only " + ws.size() + " widgets";
        for (int i = 0; i < ws.size(); i++) {
            AbstractWidget a = ws.get(i);
            if (a.getX() < 0 || a.getY() < 0 || a.getX() + a.getWidth() > s.width || a.getY() + a.getHeight() > s.height) {
                return "off screen: '" + DesignerKit.label(a) + "' at " + a.getX() + "," + a.getY() + " " + a.getWidth() + "x" + a.getHeight()
                        + " in " + s.width + "x" + s.height;
            }
            for (int k = i + 1; k < ws.size(); k++) {
                AbstractWidget b = ws.get(k);
                if (a.getX() < b.getX() + b.getWidth() && b.getX() < a.getX() + a.getWidth()
                        && a.getY() < b.getY() + b.getHeight() && b.getY() < a.getY() + a.getHeight()) {
                    return "overlap: '" + DesignerKit.label(a) + "' and '" + DesignerKit.label(b) + "'";
                }
            }
        }
        return "";
    }

    private static AbstractWidget widget(Minecraft mc, String label) {
        Screen s = McCompat.screen(mc);
        if (s == null) {
            return null;
        }
        for (var ch : s.children()) {
            if (ch instanceof AbstractWidget w && w.visible && DesignerKit.label(w).equals(label)) {
                return w;
            }
        }
        return null;
    }

    private static boolean has(ClientGameTestContext ctx, String label) {
        return ctx.computeOnClient(mc -> widget(mc, label) != null);
    }

    private static List<String> labels(ClientGameTestContext ctx, String label) {
        return ctx.computeOnClient(mc -> {
            List<String> out = new ArrayList<>();
            Screen s = McCompat.screen(mc);
            if (s != null) {
                for (var ch : s.children()) {
                    if (ch instanceof AbstractWidget w && DesignerKit.label(w).equals(label)) {
                        out.add(label);
                    }
                }
            }
            return out;
        });
    }

    /** The first visible control whose label starts with {@code prefix}, or null. */
    /** A room row's label without its leading Minecraft version tag ("[26.3] ", "[26.2 - other version] "). */
    private static String untagged(String l) {
        return l.replaceFirst("^\\[(\\d+(\\.\\d+)*|\\?)( - other version)?\\] ", "");
    }

    private static final class SimMcv {
        static String mc() { return (String) ModUnderTest.staticCall(R + "SimGuest", "mc", new Class<?>[]{}, new Object[]{}); }
    }

    private static String rowLabel(ClientGameTestContext ctx, String prefix) {
        return ctx.computeOnClient(mc -> {
            AbstractWidget w = first(mc, l -> untagged(l).startsWith(prefix));
            return w == null ? null : DesignerKit.label(w);
        });
    }

    private static AbstractWidget first(Minecraft mc, Predicate<String> p) {
        Screen s = McCompat.screen(mc);
        if (s == null) {
            return null;
        }
        for (var ch : s.children()) {
            if (ch instanceof AbstractWidget w && w.visible && !(w instanceof EditBox) && p.test(DesignerKit.label(w))) {
                return w;
            }
        }
        return null;
    }

    private static void clickRow(ClientGameTestContext ctx, String prefix) {
        double[] c = ctx.computeOnClient(mc -> {
            AbstractWidget w = first(mc, l -> untagged(l).startsWith(prefix));
            return w == null ? null : new double[]{w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0};
        });
        if (c != null) {
            DesignerKit.clickAt(ctx, c[0], c[1], 0);
        }
    }

    private static void clickLast(ClientGameTestContext ctx, String label) {
        double[] c = ctx.computeOnClient(mc -> {
            AbstractWidget last = null;
            Screen s = McCompat.screen(mc);
            for (var ch : s.children()) {
                if (ch instanceof AbstractWidget w && w.visible && DesignerKit.label(w).equals(label)) {
                    last = w;
                }
            }
            return last == null ? null : new double[]{last.getX() + last.getWidth() / 2.0, last.getY() + last.getHeight() / 2.0};
        });
        if (c != null) {
            DesignerKit.clickAt(ctx, c[0], c[1], 0);
        }
    }

    /** Types {@code value} into the box labeled {@code label} (its hint). False when there is none. */
    private static boolean setBox(ClientGameTestContext ctx, String label, String value) {
        return ctx.computeOnClient(mc -> {
            Screen s = McCompat.screen(mc);
            if (s == null) {
                return false;
            }
            for (var ch : s.children()) {
                if (ch instanceof EditBox b && b.getMessage().getString().equals(label)) {
                    b.setValue(value);
                    return true;
                }
            }
            return false;
        });
    }

    private static String message(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            Screen s = McCompat.screen(mc);
            return s != null && s.getClass().getSimpleName().equals("SimMultiplayerScreen")
                    ? (String) ModUnderTest.call(s, "messageForTest", new Class<?>[]{}, new Object[]{}) : "";
        });
    }

    private static String screenName(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> McCompat.screen(mc) == null ? null : McCompat.screen(mc).getClass().getSimpleName());
    }

    private static String confirmText(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> McCompat.screen(mc) instanceof ConfirmScreen c
                ? c.getNarrationMessage().getString().replace("?.", "?").replace("\n", " ").replaceAll("\\s+", " ").trim() : "not a ConfirmScreen: "
                + (McCompat.screen(mc) == null ? null : McCompat.screen(mc).getClass().getSimpleName()));
    }

    private static void cleanup(ClientGameTestContext ctx) {
        try {
            if ((Boolean) ModUnderTest.staticCall(HOSTC, "hosting")) {
                ctx.runOnClient(mc -> ModUnderTest.staticCall(HOSTC, "close", new Class<?>[]{Minecraft.class}, new Object[]{mc}));
            }
            MinecraftServer s = ctx.computeOnClient(Minecraft::getSingleplayerServer);
            if (s != null) {
                GuestPlayer.removeAll(ctx, s);
            }
        } catch (Throwable ignored) {
            // never let cleanup replace the verdict
        }
        System.clearProperty(RELAY);
        System.clearProperty(SSDP);
        try {
            token(null);
        } catch (Throwable ignored) {
            // cleanup
        }
        if (ctx.computeOnClient(mc -> mc.level != null)) {
            if (ctx.computeOnClient(mc -> mc.getSingleplayerServer() == null)) {
                ctx.runOnClient(mc -> mc.disconnectWithProgressScreen());
                ctx.waitFor(mc -> mc.level == null, 600);
                ctx.waitTicks(20);
            } else {
                SimPuzzleSolveTests.teardown(ctx);
            }
        }
        if (ctx.computeOnClient(mc -> mc.level == null && !(McCompat.screen(mc) instanceof TitleScreen))) {
            ctx.setScreen(TitleScreen::new);
        }
    }

    private static boolean logSeen(long mark, String text) {
        for (String l : LogTap.since(mark)) {
            if (l.contains(text)) {
                return true;
            }
        }
        return false;
    }

    private static boolean chatSeen(long mark, String text) {
        for (String l : LogTap.since(mark)) {
            if (l.contains("[CHAT]") && l.contains(text)) {
                return true;
            }
        }
        return false;
    }

    /** Polls {@code p} on the test thread once a client tick, up to {@code ticks}. */
    private static boolean until(ClientGameTestContext ctx, java.util.function.BooleanSupplier p, int ticks) {
        for (int i = 0; i < ticks; i++) {
            if (p.getAsBoolean()) {
                return true;
            }
            ctx.waitTick();
        }
        return false;
    }

    private static boolean paceUntil(ClientGameTestContext ctx, java.util.function.BooleanSupplier p, int ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (p.getAsBoolean()) {
                return true;
            }
            CoopSync.pace(ctx, 50);
        }
        return p.getAsBoolean();
    }

    private static void expect(List<String> f, List<String> facts, String what, Object got, Object want) {
        boolean ok = String.valueOf(want).equals(String.valueOf(got));
        facts.add(what + ": " + got + (ok ? "" : " (want " + want + ")"));
        if (!ok) {
            f.add(what + ": " + got + ", want " + want);
        }
    }

    private static void println(String name, String line) {
        System.out.println("[" + name + "] " + line);
    }

    static String fmt(double d) { return String.format(Locale.ROOT, "%.2f", d); }
}

package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.coop.CoopSync;
import dev.testkit.gametest.coop.GuestPlayer;
import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Co-op sim phase 5 (coopsim-p5): the whole sim in co-op, on a generated F7 floor.
 * <ul>
 * <li>{@code 1109-coopfloor-fake}: a fake guest ({@link GuestPlayer}) on the host's floor. Its wither key opens a door,
 * its essence click is its own secret, its death counts for the team and for it, walking into a room clears it, a
 * Tic Tac Toe cell it clicks is played, it gets the Hypixel-format dungeon map (calibratable: a 16 px entrance run on
 * the 20 px pitch), and a floor rebuild lands it beside the host.</li>
 * <li>{@code 1109-coopfloor-two}: two real clients ({@code run-coop.sh -s 1109-coopfloor-two}). The guest joins the
 * host's floor; a rebuild lands it at the entrance with the host; its tab shows the run's clock moving and the party;
 * its own Dungeon Map calibrates from the map item; and it steps into Blood's portal and both reach the boss.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class CoopFloorTests implements FabricClientGameTest {

    static final String FAKE = "1109-coopfloor-fake";
    static final String TWO = "1109-coopfloor-two";
    static final String ICE = "1109-coopfloor-ice";
    private static final String R = "com.killer560.hub.roomsim.";
    private static final String GUEST_NAME = "TkFloorGuest";
    private static final int MAP_ID = 1_234_560;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank()) {
            return;
        }
        List<String> failed = new ArrayList<>();
        for (String name : new String[]{FAKE, ICE, TWO}) {
            if (Scenario.skip(name)) {
                continue;
            }
            boolean guestSide = CoopSync.role().equals("guest");
            if (!name.equals(TWO) && guestSide) {
                Scenario.skipped(name, "the guest side of a two-client run (the host runs it)");
                continue;
            }
            if (name.equals(TWO) && !CoopSync.active()) {
                Scenario.skipped(name, "two-client case: run it with run-coop.sh (no testkit.coop.role here)");
                continue;
            }
            ModUnderTest.require("killer560smod");
            LogTap.install();
            if (!guestSide && !SimPuzzleSolveTests.seedRooms(ctx)) {
                Scenario.skipped(name, "needs real room captures and room database");
                if (name.equals(TWO)) {
                    CoopSync.fail("host has no room captures");
                }
                continue;
            }
            ctx.waitTicks(20);
            ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
            List<String> problems = new ArrayList<>();
            List<String> facts = new ArrayList<>();
            Report.caseStarted(name);
            try {
                if (name.equals(FAKE)) {
                    fake(ctx, problems, facts);
                } else if (name.equals(ICE)) {
                    ice(ctx, problems, facts);
                } else if (!guestSide) {
                    twoHost(ctx, problems, facts);
                } else {
                    twoGuest(ctx, problems, facts);
                }
            } catch (Throwable t) {
                problems.add("threw: " + t);
                t.printStackTrace(System.out);
            } finally {
                if (name.equals(TWO) && !problems.isEmpty()) {
                    CoopSync.fail(String.join("; ", problems));
                }
                cleanup(ctx);
            }
            String verdict = problems.isEmpty() ? "PASS" : "FAIL";
            for (String f : facts) {
                println(name, "FACT " + f);
            }
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

    // ===================================================================================== 1109-coopfloor-fake

    private static void fake(ClientGameTestContext ctx, List<String> f, List<String> facts) {
        String code = buildFloor(ctx, f);
        if (code == null) {
            return;
        }
        MinecraftServer s = ctx.computeOnClient(Minecraft::getSingleplayerServer);
        UUID host = ctx.computeOnClient(mc -> mc.player.getUUID());
        Vec3 hostAt = hostNow(ctx);
        GuestPlayer g = GuestPlayer.joinBesideHost(ctx, s, host, GUEST_NAME, hostAt.add(1, 0, 0));
        UUID guest = g.entity().getUUID();
        g.run(p -> p.getAbilities().invulnerable = true); // void damage below still reaches it
        expect(f, facts, "run started", until(ctx, mc -> (Boolean) call("SimRun", "hasStarted"), 400), true);
        expect(f, facts, "SimCoop sees one guest", until(ctx, mc -> ((List<?>) call("SimCoop", "guestSpots")).size() == 1, 40), true);
        expect(f, facts, "the guest's tab row reads the sim's only class", until(ctx, mc -> tabHas(mc, "] " + GUEST_NAME
                + " (Mage L)"), 200), true);

        // ---- the guests' dungeon map: an item in slot 9 and a picture the scanner calibrates on ----
        int sends0 = num(call("SimCoopMap", "sendsForTest"));
        boolean sent = until(ctx, mc -> num(call("SimCoopMap", "sendsForTest")) > sends0, 60);
        expect(f, facts, "map sent to the guest", sent, true);
        facts.add("map sends before/after " + sends0 + "/" + num(call("SimCoopMap", "sendsForTest")));
        expect(f, facts, "guest slot 9 holds the guests' map", g.ask(p -> {
            MapId id = p.getInventory().getItem(8).get(DataComponents.MAP_ID);
            return id != null && id.id() == MAP_ID;
        }), true);
        byte[] pic = ctx.computeOnClient(mc -> (byte[]) call("SimCoopMap", "paint"));
        int[] run = entranceRun(pic);
        facts.add("entrance run start " + (run[0] & 127) + "," + (run[0] >> 7) + " length " + run[1]);
        expect(f, facts, "entrance run is a 16 px room on the 20 px pitch from 5,5",
                run[1] == 16 && ((run[0] & 127) - 5) % 20 == 0 && ((run[0] >> 7) - 5) % 20 == 0, true);

        // ---- a room the guest walks into is cleared (the host never went there) ----
        Object[] room = ctx.computeOnClient(mc -> otherMobRoom());
        if (room == null) {
            f.add("no mob room away from the host on this floor");
        } else {
            String name = (String) room[0];
            BlockPos c = (BlockPos) room[1];
            expect(f, facts, "room " + name + " cleared before the guest went in", call("SimRoomState", "isCleared",
                    new Class<?>[]{String.class}, name), false);
            g.moveTo(new Vec3(c.getX() + 0.5, hostAt.y + 30, c.getZ() + 0.5), 0, 0);
            g.run(p -> p.getAbilities().flying = true);
            expect(f, facts, "room " + name + " cleared by the guest's visit", until(ctx, mc -> (Boolean) call("SimRoomState",
                    "isCleared", new Class<?>[]{String.class}, name), 60), true);
            g.moveTo(hostAt.add(1, 0, 0), 0, 0);
        }

        // ---- a wither essence the guest clicks is its secret ----
        BlockPos skull = GuestPlayer.onServer(ctx, s, () -> {
            ServerLevel lv = (ServerLevel) g.entity().level();
            BlockPos at = BlockPos.containing(g.entity().position()).offset(0, 0, 2);
            lv.setBlockAndUpdate(at, Blocks.PLAYER_HEAD.defaultBlockState());
            @SuppressWarnings("unchecked")
            java.util.Set<BlockPos> placed = (java.util.Set<BlockPos>) field("SimSecrets", "PLACED_WITHER");
            placed.add(at.immutable());
            return at.immutable();
        });
        int found0 = num(call("SimScore", "secretsFound"));
        g.useOn(skull, Direction.UP);
        expect(f, facts, "essence counted", until(ctx, mc -> num(call("SimScore", "secretsFound")) > found0, 40), true);
        expect(f, facts, "secrets credited to the guest", num(call("SimScore", "secretsBy", new Class<?>[]{UUID.class}, guest)), 1);
        expect(f, facts, "secrets credited to the host", num(call("SimScore", "secretsBy", new Class<?>[]{UUID.class}, host)), 0);

        // ---- the guest's wither key opens a door ----
        door(ctx, s, g, f, facts);

        // ---- a death counts for the team and for the guest ----
        int deaths0 = num(call("SimScore", "deaths"));
        g.run(p -> {
            p.hurtServer((ServerLevel) p.level(), p.damageSources().fellOutOfWorld(), 1000f);
        });
        ctx.waitTicks(2);
        expect(f, facts, "team deaths after the guest's death", num(call("SimScore", "deaths")) - deaths0, 1);
        expect(f, facts, "deaths credited to the guest", num(call("SimScore", "deathsBy", new Class<?>[]{UUID.class}, guest)), 1);
        expect(f, facts, "deaths credited to the host", num(call("SimScore", "deathsBy", new Class<?>[]{UUID.class}, host)), 0);
        expect(f, facts, "guest alive after its death", g.ask(ServerPlayer::isAlive), true);

        // ---- Tic Tac Toe answers the guest's click ----
        ticTacToe(ctx, s, g, f, facts);

        // ---- a rebuild lands the guest with the host ----
        g.moveTo(hostAt.add(40, 20, 40), 0, 0);
        int landings0 = num(call("SimBuilder", "partyLandingsForTest"));
        rebuild(ctx, code);
        expect(f, facts, "party landings after the rebuild", until(ctx, mc -> num(call("SimBuilder", "partyLandingsForTest")) > landings0, 400), true);
        ctx.waitTicks(10);
        Vec3 h = hostNow(ctx);
        Vec3 gp = g.position();
        facts.add("after the rebuild host " + fmt(h) + " guest " + fmt(gp));
        expect(f, facts, "guest landed within 2 blocks of the host", gp.distanceTo(h) < 2, true);
        g.remove();
    }

    private static void door(ClientGameTestContext ctx, MinecraftServer s, GuestPlayer g, List<String> f, List<String> facts) {
        Vec3 hostAt = hostNow(ctx);
        int cell = ctx.computeOnClient(mc -> nearestDoorCell(hostAt));
        facts.add("door cell nearest the host " + cell);
        if (cell >= 0) {
            ctx.runOnClient(mc -> call("SimDoors", "makeWitherDoor", new Class<?>[]{Minecraft.class, int.class}, mc, cell));
            ctx.waitTicks(20);
        }
        @SuppressWarnings("unchecked")
        List<BlockPos> doors = ctx.computeOnClient(mc -> (List<BlockPos>) call("SimDoors", "doorBlocksForTest",
                new Class<?>[]{int.class}, 2));
        facts.add("shut wither doors " + doors.size());
        if (doors.isEmpty()) {
            f.add("no shut wither door on the floor");
            return;
        }
        BlockPos d = doors.get(0);
        // Stand beside the door column where there is air to stand in and floor under it.
        Vec3 stand = GuestPlayer.onServer(ctx, s, () -> {
            ServerLevel lv = (ServerLevel) g.entity().level();
            // The door's bottom block: the column goes down while it is the same block as the one registered.
            net.minecraft.world.level.block.Block door = lv.getBlockState(d).getBlock();
            int y = d.getY();
            while (y > d.getY() - 8 && lv.getBlockState(new BlockPos(d.getX(), y - 1, d.getZ())).is(door)) {
                y--;
            }
            for (int[] o : new int[][]{{2, 0}, {-2, 0}, {0, 2}, {0, -2}}) {
                BlockPos feet = new BlockPos(d.getX() + o[0], y, d.getZ() + o[1]);
                if (lv.getBlockState(feet).isAir() && lv.getBlockState(feet.above()).isAir()) {
                    return new Vec3(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                }
            }
            return null;
        });
        if (stand == null) {
            f.add("no room to stand beside the door at " + d.toShortString());
            return;
        }
        g.moveTo(stand, 0, 0);
        g.hold(ctx.computeOnClient(mc -> (ItemStack) call("SimDoors", "createWitherKey")));
        long mark = LogTap.mark();
        g.useOn(d, Direction.UP);
        boolean open = false;
        for (int i = 0; i < 120 && !open; i++) {
            open = GuestPlayer.onServer(ctx, s, () -> g.entity().level().getBlockState(d).isAir());
            if (!open) ctx.waitTick();
        }
        expect(f, facts, "door at " + d.toShortString() + " opened by the guest's key", open, true);
        expect(f, facts, "the guest's key was used", g.ask(p -> p.getMainHandItem().isEmpty()), true);
        facts.add("open line seen " + logSeen(mark, "opened a WITHER door") + " (a held key says nothing on Hypixel)");
    }

    /** The 11x11 gap cell of a layout door (not the entrance's) nearest {@code at}, or -1. Client thread. */
    private static int nearestDoorCell(Vec3 at) {
        Object layout = ModUnderTest.staticCall("com.killer560.hub.livemap.DungeonLayout", "current");
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int idx = 0; idx < 121; idx++) {
            int gx = idx % 11;
            int gz = idx / 11;
            if (gx % 2 == gz % 2) continue;
            int type = num(ModUnderTest.call(layout, "doorType", new Class<?>[]{int.class}, new Object[]{idx}));
            if (type != 1) continue; // a normal door: made wither here
            BlockPos b = (BlockPos) ModUnderTest.staticCall("com.killer560.hub.livemap.DungeonLayout", "doorBlock",
                    new Class<?>[]{int.class}, new Object[]{idx});
            if (b == null) continue;
            double d = at.distanceToSqr(Vec3.atCenterOf(b));
            if (d < bestD) {
                bestD = d;
                best = idx;
            }
        }
        return best;
    }

    private static void ticTacToe(ClientGameTestContext ctx, MinecraftServer s, GuestPlayer g, List<String> f, List<String> facts) {
        BlockPos origin = BlockPos.containing(g.position()).offset(3, 2, -1);
        ctx.runOnClient(mc -> mc.execute(() -> call("puzzles.SimTicTacToePuzzle", "build",
                new Class<?>[]{Minecraft.class, BlockPos.class}, mc, origin)));
        BlockPos[] cell = {null};
        until(ctx, mc -> (cell[0] = (BlockPos) call("puzzles.SimTicTacToePuzzle", "emptyCellForTest")) != null, 60);
        if (cell[0] == null) {
            f.add("the Tic Tac Toe board was never built");
            return;
        }
        ctx.waitTicks(10);
        int marks0 = num(call("puzzles.SimTicTacToePuzzle", "playerMarksForTest"));
        g.moveTo(g.position(), 0, 0); // acks the death's teleport, as a real client does; until then uses are dropped
        g.useOn(cell[0], Direction.WEST);
        expect(f, facts, "the guest's Tic Tac Toe move was played", until(ctx, mc ->
                num(call("puzzles.SimTicTacToePuzzle", "playerMarksForTest")) > marks0, 40), true);
        ctx.runOnClient(mc -> call("puzzles.SimTicTacToePuzzle", "reset"));
    }

    // ===================================================================================== 1109-coopfloor-ice

    /** The Ice Path silverfish is put back when a GUEST is near it, though the host is far away (server-side check). */
    private static void ice(ClientGameTestContext ctx, List<String> f, List<String> facts) {
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(R + "SimBuilder", "buildSingleRoom",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, "Ice Path"})));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        ctx.waitTicks(40);
        expect(f, facts, "client stands on the room", until(ctx, mc -> mc.player.onGround()
                && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir(), 1800), true);
        MinecraftServer s = ctx.computeOnClient(Minecraft::getSingleplayerServer);
        UUID host = ctx.computeOnClient(mc -> mc.player.getUUID());
        Vec3 hostAt = hostNow(ctx);
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(hostAt, hostAt).inflate(48, 24, 48);
        Vec3 fishAt = GuestPlayer.onServer(ctx, s, () -> {
            var list = s.overworld().getEntitiesOfClass(net.minecraft.world.entity.monster.Silverfish.class, box);
            return list.isEmpty() ? null : list.get(0).position();
        });
        if (fishAt == null) {
            f.add("no sim silverfish near the host to start from");
            return;
        }
        GuestPlayer g = GuestPlayer.joinBesideHost(ctx, s, host, GUEST_NAME, fishAt.add(0, 1, 0));
        g.run(p -> p.getAbilities().invulnerable = true);
        // The host flies 60 blocks away (over RESPAWN_RANGE 24); the guest stays on the fish's cell.
        GuestPlayer.onServer(ctx, s, () -> {
            ServerPlayer hp = s.getPlayerList().getPlayer(host);
            hp.getAbilities().invulnerable = true;
            hp.getAbilities().mayfly = true;
            hp.getAbilities().flying = true;
            return call("SimArrival", "teleport", new Class<?>[]{ServerPlayer.class, ServerLevel.class, double.class,
                    double.class, double.class, java.util.Set.class, float.class, float.class},
                    hp, (ServerLevel) hp.level(), fishAt.x + 60, fishAt.y + 20, fishAt.z, java.util.Set.of(), 0f, 0f);
        });
        ctx.waitTicks(10);
        double far = hostNow(ctx).distanceTo(fishAt);
        facts.add("host " + (int) far + " blocks from the fish, guest on it");
        expect(f, facts, "host is beyond the respawn range", far > 30, true);
        int gone = GuestPlayer.onServer(ctx, s, () -> {
            var list = s.overworld().getEntitiesOfClass(net.minecraft.world.entity.monster.Silverfish.class, box);
            list.forEach(net.minecraft.world.entity.Entity::discard);
            return list.size();
        });
        expect(f, facts, "a fish was there to remove", gone >= 1, true);
        boolean back = false;
        for (int i = 0; i < 40 && !back; i++) {
            ctx.waitTicks(10);
            back = GuestPlayer.onServer(ctx, s, () -> !s.overworld().getEntitiesOfClass(
                    net.minecraft.world.entity.monster.Silverfish.class, box, e -> e.isAlive()).isEmpty());
        }
        expect(f, facts, "the guest's presence put the silverfish back", back, true);
        g.remove();
    }

    // ===================================================================================== 1109-coopfloor-two

    private static void twoHost(ClientGameTestContext ctx, List<String> f, List<String> facts) {
        CoopSync.put("host-up", "1");
        CoopSync.await(ctx, "guest-up", 900);
        String code = buildFloor(ctx, f);
        if (code == null) {
            return;
        }
        String hostName = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
        String published = CoopSimTests.publish(ctx, CoopSync.port());
        facts.add(published);
        if (!published.startsWith("published")) {
            f.add(published);
            return;
        }
        CoopSync.put("host-ready", CoopSync.port() + "\n" + hostName);
        String guestName = CoopSync.await(ctx, "guest-in", 180).trim();
        expect(f, facts, "guest in the PlayerList", until(ctx, mc -> mc.getSingleplayerServer().getPlayerList()
                .getPlayerByName(guestName) != null, 200), true);
        CoopSync.await(ctx, "guest-settled", 120);

        // Party chat: the host's /pc reaches the guest (the guest checks its log), the guest's /pc reaches the host.
        long pcMark = LogTap.mark();
        ctx.runOnClient(mc -> mc.player.connection.sendCommand("pc hello guest"));
        CoopSync.put("host-pc", "1");
        CoopSync.await(ctx, "guest-pc-sent", 60);
        expect(f, facts, "host sees its own /pc in party format", until(ctx, mc -> logSeen(pcMark,
                "Party > " + hostName + ": hello guest"), 100), true);
        expect(f, facts, "host sees the guest's /pc in party format", until(ctx, mc -> logSeen(pcMark,
                "Party > " + guestName + ": hello host"), 200), true);
        expect(f, facts, "a guest's /pc is no unknown command on the host", logSeen(pcMark, "Unknown or incomplete"), false);

        // A rebuild lands everyone at the entrance.
        int landings0 = num(call("SimBuilder", "partyLandingsForTest"));
        rebuild(ctx, code);
        expect(f, facts, "party landings after the rebuild", until(ctx, mc -> num(call("SimBuilder", "partyLandingsForTest")) > landings0, 600), true);
        expect(f, facts, "run started after the rebuild", until(ctx, mc -> (Boolean) call("SimRun", "hasStarted"), 400), true);
        ctx.waitTicks(20);
        Vec3 h = hostNow(ctx);
        CoopSync.put("host-landed", String.format(Locale.ROOT, "%.2f %.2f %.2f", h.x, h.y, h.z));
        expect(f, facts, "map sent to the guest", until(ctx, mc -> num(call("SimCoopMap", "sendsForTest")) > 0, 100), true);
        CoopSync.await(ctx, "guest-checked", 180);

        // Into the boss: the host stands in Blood, the guest is put on the portal (the host's server moves it).
        int[] p = portal(ctx, f);
        if (p == null) {
            return;
        }
        MinecraftServer s = ctx.computeOnClient(Minecraft::getSingleplayerServer);
        long mark = LogTap.mark();
        GuestPlayer.onServer(ctx, s, () -> {
            ServerPlayer gp = s.getPlayerList().getPlayerByName(guestName);
            return call("SimArrival", "teleport", new Class<?>[]{ServerPlayer.class, ServerLevel.class, double.class,
                    double.class, double.class, java.util.Set.class, float.class, float.class},
                    gp, (ServerLevel) gp.level(), p[0] + 0.5, p[1] + 1.0, p[2] + 0.5, java.util.Set.of(), 0f, 0f);
        });
        boolean in = until(ctx, mc -> logSeen(mark, "into the boss through the Blood room's portal (" + guestName + ")"), 400);
        expect(f, facts, "the guest's step into the portal was taken", in, true);
        expect(f, facts, "the boss fight started", until(ctx, mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.bosssim.BossFight", "running"), 600), true);
        CoopSync.put("host-boss", "1");
        String guestVerdict = CoopSync.await(ctx, "guest-done", 240).trim();
        facts.add("guest verdict: " + guestVerdict);
        expect(f, facts, "guest's own checks", guestVerdict, "PASS");
    }

    private static void twoGuest(ClientGameTestContext ctx, List<String> f, List<String> facts) {
        CoopSync.put("guest-up", "1");
        String[] ready = CoopSync.await(ctx, "host-ready", 1200).split("\n");
        String joined = CoopSimTests.joinHost(ctx, "127.0.0.1:" + Integer.parseInt(ready[0].trim()));
        String hostName = ready[1].trim();
        facts.add(joined);
        if (!joined.startsWith("joined")) {
            f.add(joined);
            CoopSync.put("guest-done", "FAIL: " + joined);
            return;
        }
        String me = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
        long pcMark = LogTap.mark();
        CoopSync.put("guest-in", me);
        CoopSync.pace(ctx, 2000);
        CoopSync.put("guest-settled", "1");

        // Party chat from the host arrives in party format; the guest's own /pc goes up and comes back the same way.
        CoopSync.await(ctx, "host-pc", 60);
        int pc0 = num(call("SimPartyChat", "sentForTest"));
        expect(f, facts, "guest sees the host's /pc in party format", paceUntil(ctx, () -> logSeen(pcMark,
                "Party > " + hostName + ": hello guest"), 10_000), true);
        ctx.runOnClient(mc -> mc.player.connection.sendCommand("pc hello host"));
        expect(f, facts, "guest's /pc went up as the co-op payload", paceUntil(ctx, () -> num(call("SimPartyChat",
                "sentForTest")) == pc0 + 1, 5_000), true);
        expect(f, facts, "guest sees its own /pc in party format", paceUntil(ctx, () -> logSeen(pcMark,
                "Party > " + me + ": hello host"), 10_000), true);
        expect(f, facts, "a guest's /pc is no unknown command", logSeen(pcMark, "Unknown or incomplete"), false);
        CoopSync.put("guest-pc-sent", "1");

        String[] at = CoopSync.await(ctx, "host-landed", 300).trim().split(" ");
        Vec3 hostAt = new Vec3(Double.parseDouble(at[0]), Double.parseDouble(at[1]), Double.parseDouble(at[2]));
        boolean landed = paceUntil(ctx, () -> ctx.computeOnClient(mc -> mc.player != null && McCompat.screen(mc) == null
                && mc.player.position().distanceTo(hostAt) < 3), 30_000);
        facts.add("guest at " + fmt(ctx.computeOnClient(mc -> mc.player.position())) + ", host landed at " + fmt(hostAt));
        expect(f, facts, "guest landed with the host after the rebuild", landed, true);

        // The run's numbers are the host's, read off the tab like Hypixel's.
        expect(f, facts, "guest's tab shows Party (2) with both names", paceUntil(ctx, () -> ctx.computeOnClient(mc ->
                tabHas(mc, "Party (2)") && tabHas(mc, "] " + hostName + " (") && tabHas(mc, "] " + me + " (")), 15_000), true);
        String t0 = ctx.computeOnClient(mc -> tabLine(mc, " Time: "));
        CoopSync.pace(ctx, 2500);
        String t1 = ctx.computeOnClient(mc -> tabLine(mc, " Time: "));
        facts.add("tab clock " + t0 + " -> " + t1);
        expect(f, facts, "guest's tab clock moves", t0 != null && t1 != null && !t0.equals(t1), true);
        expect(f, facts, "guest's tab class text is the sim's only class", ctx.computeOnClient(mc ->
                tabHas(mc, "] " + me + " (Mage L)")), true);
        expect(f, facts, "guest's tab has the secrets line", ctx.computeOnClient(mc -> tabLine(mc, " Secrets Found: ") != null), true);

        // Its own map features read the map item the host sends.
        boolean cal = paceUntil(ctx, () -> ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.livemap.LiveMapReads", "mapItemCalibrated")), 20_000);
        expect(f, facts, "guest's dungeon map calibrated from the host's map item", cal, true);
        expect(f, facts, "guest holds the map in slot 9", ctx.computeOnClient(mc -> {
            MapId id = mc.player.getInventory().getItem(8).get(DataComponents.MAP_ID);
            return id != null && id.id() == MAP_ID;
        }), true);
        CoopSync.put("guest-checked", "1");

        CoopSync.await(ctx, "host-boss", 600);
        boolean boss = paceUntil(ctx, () -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.secrets.DungeonState", "isSimBoss"), 30_000);
        expect(f, facts, "guest reached the boss", boss, true);
        CoopSync.put("guest-done", f.isEmpty() ? "PASS" : "FAIL: " + String.join("; ", f));
        CoopSync.pace(ctx, 1000);
    }

    // ================================================================================================ helpers

    /** Plans and builds an F7 in the sim world and waits for the build; returns its code, or null with a problem. */
    private static String buildFloor(ClientGameTestContext ctx, List<String> f) {
        String code = null;
        for (int i = 0; i < 6 && code == null; i++) {
            code = SimBossTests.planF7(ctx);
        }
        if (code == null) {
            f.add("no F7 could be planned");
            return null;
        }
        String c = code;
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            java.util.function.Consumer<Minecraft> build = cl -> ModUnderTest.staticCall(R + "SimBuilder", "build",
                    new Class<?>[]{Minecraft.class, String.class}, new Object[]{cl, c});
            ModUnderTest.staticCall(R + "SimWorld", "open", new Class<?>[]{Minecraft.class, String.class,
                    java.util.function.Consumer.class, String.class}, new Object[]{mc, c, build, "Generating Floor 7"});
        }));
        ctx.waitFor(mc -> mc.level != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitTicks(40);
        return code;
    }

    /** Rebuilds the same floor in the open sim world (no new world: the guest stays connected). */
    private static void rebuild(ClientGameTestContext ctx, String code) {
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            java.util.function.Consumer<Minecraft> build = cl -> ModUnderTest.staticCall(R + "SimBuilder", "build",
                    new Class<?>[]{Minecraft.class, String.class}, new Object[]{cl, code});
            ModUnderTest.staticCall(R + "SimWorld", "open", new Class<?>[]{Minecraft.class, String.class,
                    java.util.function.Consumer.class, String.class}, new Object[]{mc, code, build, "Generating Floor 7"});
        }));
        Scenario.awaitSimBuild(ctx, before);
    }

    private static int[] portal(ClientGameTestContext ctx, List<String> f) {
        ctx.runOnClient(mc -> ModUnderTest.staticCall(R + "SimTeleportCommands", "goTo",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, "Blood"}));
        if (!until(ctx, mc -> "Blood".equals(call("SimState", "currentRoomName")), 200)) {
            f.add("/goto Blood never put the host in the Blood room");
            return null;
        }
        if (!until(ctx, mc -> bossPortal() != null, 400)) {
            f.add("no portal opened in Blood");
            return null;
        }
        return ctx.computeOnClient(mc -> bossPortal());
    }

    /** BossPortal.portal(): the class is package-private, so reflection needs setAccessible. */
    private static int[] bossPortal() {
        try {
            java.lang.reflect.Method m = Class.forName("com.killer560.hub.bosssim.BossPortal").getDeclaredMethod("portal");
            m.setAccessible(true);
            return (int[]) m.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("BossPortal.portal(): " + e, e);
        }
    }

    /** A mob room ({name, center}) that is not the room the host stands in. Client thread. */
    private static Object[] otherMobRoom() {
        Object layout = ModUnderTest.staticCall("com.killer560.hub.livemap.DungeonLayout", "current");
        int rooms = num(ModUnderTest.call(layout, "roomCount", new Class<?>[0], new Object[0]));
        String here = (String) call("SimState", "currentRoomName");
        for (int r = 0; r < rooms; r++) {
            String name = (String) ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{r});
            Object entry = ModUnderTest.call(layout, "entry", new Class<?>[]{int.class}, new Object[]{r});
            if (name == null || entry == null || name.equals(here) || "Unknown".equals(name)) {
                continue;
            }
            String type;
            try {
                Object t = entry.getClass().getField("type").get(entry);
                type = t == null ? "" : t.toString().toUpperCase(Locale.ROOT);
            } catch (ReflectiveOperationException e) {
                continue;
            }
            if (type.equals("ENTRANCE") || type.equals("PUZZLE") || type.equals("BLOOD") || type.equals("FAIRY") || type.equals("TRAP")) {
                continue;
            }
            int[] tiles = (int[]) ModUnderTest.call(layout, "tiles", new Class<?>[]{int.class}, new Object[]{r});
            if (tiles == null || tiles.length == 0) {
                continue;
            }
            BlockPos c = (BlockPos) ModUnderTest.staticCall("com.killer560.hub.livemap.DungeonLayout", "cellCenter",
                    new Class<?>[]{int.class}, new Object[]{tiles[0]});
            return new Object[]{name, c};
        }
        return null;
    }

    /** DungeonMapScanner.calibrate's read: {start pixel, length} of the first run of color 30 at least 16 long. */
    private static int[] entranceRun(byte[] c) {
        int len = 0;
        int start = 0;
        for (int i = 0; i < c.length; i++) {
            if (c[i] == 30) {
                if (len == 0) {
                    start = i;
                }
                len++;
            } else {
                if (len >= 16) {
                    return new int[]{start, len};
                }
                len = 0;
            }
        }
        return new int[]{start, len};
    }

    private static String tabLine(Minecraft mc, String prefix) {
        if (mc.getConnection() == null) {
            return null;
        }
        for (var info : mc.getConnection().getOnlinePlayers()) {
            String t = info.getTabListDisplayName() == null ? "" : info.getTabListDisplayName().getString();
            if (t.startsWith(prefix)) {
                return t;
            }
        }
        return null;
    }

    private static boolean tabHas(Minecraft mc, String part) {
        if (mc.getConnection() == null) {
            return false;
        }
        for (var info : mc.getConnection().getOnlinePlayers()) {
            String t = info.getTabListDisplayName() == null ? "" : info.getTabListDisplayName().getString();
            if (t.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private static void cleanup(ClientGameTestContext ctx) {
        try {
            MinecraftServer s = ctx.computeOnClient(Minecraft::getSingleplayerServer);
            if (s != null) {
                GuestPlayer.removeAll(ctx, s);
            }
        } catch (Throwable ignored) {
            // never let cleanup replace the verdict
        }
        if (ctx.computeOnClient(mc -> mc.level != null)) {
            if (ctx.computeOnClient(mc -> mc.getSingleplayerServer() == null)) {
                ctx.runOnClient(mc -> mc.disconnectWithProgressScreen());
                ctx.waitFor(mc -> mc.level == null, 600);
                ctx.waitTicks(20);
                ctx.setScreen(TitleScreen::new);
            } else {
                SimPuzzleSolveTests.teardown(ctx);
            }
        }
        if (ctx.computeOnClient(mc -> mc.level == null && !(McCompat.screen(mc) instanceof TitleScreen))) {
            ctx.setScreen(TitleScreen::new);
        }
    }

    private static Object call(String cls, String method) {
        return ModUnderTest.staticCall(R + cls, method);
    }

    private static Object call(String cls, String method, Class<?>[] types, Object... args) {
        return ModUnderTest.staticCall(R + cls, method, types, args);
    }

    private static Object field(String cls, String name) {
        try {
            return Class.forName(R + cls).getField(name).get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no field " + cls + "." + name + ": " + e, e);
        }
    }

    private static int num(Object o) {
        return ((Number) o).intValue();
    }

    private static Vec3 hostNow(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            ServerPlayer sp = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
            return sp == null ? mc.player.position() : sp.position();
        });
    }

    private static boolean until(ClientGameTestContext ctx, java.util.function.Predicate<Minecraft> p, int ticks) {
        for (int i = 0; i < ticks; i++) {
            if (ctx.computeOnClient(p::test)) {
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

    private static boolean logSeen(long mark, String text) {
        for (String l : LogTap.since(mark)) {
            if (l.contains(text)) {
                return true;
            }
        }
        return false;
    }

    private static void expect(List<String> f, List<String> facts, String what, Object got, Object want) {
        boolean ok = String.valueOf(want).equals(String.valueOf(got));
        facts.add(what + ": " + got + (ok ? "" : " (want " + want + ")"));
        if (!ok) {
            f.add(what + ": " + got + ", want " + want);
        }
    }

    private static String fmt(Vec3 v) {
        return v == null ? "null" : String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", v.x, v.y, v.z);
    }

    private static void println(String name, String line) {
        System.out.println("[" + name + "] " + line);
    }
}

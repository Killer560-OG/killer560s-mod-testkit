package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>1122-sim-ac-killplan</b> (mod ackilllogic-1122, killer560 2026-11-22: Hyperion teleports that hit several mob groups
 * instead of an etherwarp for every group, "built into the logic", no new switch) - Auto Clear's per-room kill plan.
 * <p>
 * One fixed sim F7 (the 6th floor of the before/after measurement, killer560s-mod-logs/ackilllogic-1122-before.md), the
 * sim's live starred mobs on the rooms' known spawn boxes, Auto Clear alone with the Hyperion (1020-sim-ac-speed's
 * Hyperion loadout). Asserts on the kills: every room cleared, and Steps - where origin e75a34113 took 3 trips and 4 weapon
 * uses (7 actions, 61-62 ticks, one a 50-tick walk) - cleared with its 3 starred kills in at most 5 actions, and the room plan
 * acted (AutoClearFeature.killPlanMoves went up). Cheat only (Auto Clear is cheat-only).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimAcKillPlanTests implements FabricClientGameTest {

    private static final String NAME = "1122-sim-ac-killplan";
    private static final String AUTO = "com.killer560.hub.autoclear.AutoClearFeature";
    private static final String CODE = "MC2:CxYIRW50cmFuY2UHQnV0dG9ucwVGbGFncwVGYWlyeQhNdXNocm9vbQdBcmNod2F5BUJsb29kBVN0ZXBzCE5ldyBUcmFwDUNyZWVwZXIgQmVhbXMGTXVzZXVtB0JyaWRnZXMFQ3J5cHQNVGVsZXBvcnQgTWF6ZQRRdWl6A0RpcAdSYWNjb29uBldpemFyZAlPdmVyZ3Jvd24GQ2hhaW5zBENlbGwOUmFyZSBPdmVyZ3Jvd24ACQIAAAAABQEBAAAABgEDAAAABwAAAAAAEQMBAAAAEAIBAAAAAAABAAAAAAAABgEAAAAAAAAAAAABAAAAAAABAAAACAEAAAAABAAAAAAABgEAAAAADwMAAAAAFAEAAAAADQEBAAAAAAABAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAADQEAAwMAAwMAAwMBAAAACgMAAAAADAAADAAADAABAAAADQEAAwMAAwMAAwMAAAAAAAAAAAABAAAAAAAAAAAAAAABAAAAAwMAAwMAAwMBAAAACwIACwIACwIAAAAAFgABAAAAEwABAAAAAAAAAAAAAAAACwIACwIACwIAAAAAAAAAAAABAAAAAgAAAgAAAgAAAAAACwIACwIACwIBAAAADgMAAAAAFQEAAgAAAgAAAgAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAgAAAgAAAgAEAAAAAQIAAAAAEgIAEgIAEgIAEgIAEgI";
    /** Steps on origin: 3 trips + 4 weapon uses. */
    private static final int STEPS_MAX_ACTIONS = 5;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.runOnClient(mc -> AcTickProbe.install());
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(NAME, "needs real room captures and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "isReady"), 2400);
        ctx.runOnClient(mc -> {
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                    "setAutoPuzzlesMasterEnabled", false);
            Object map = ModUnderTest.config("com.killer560.hub.livemap.LiveMapConfig");
            ModUnderTest.set(map, "setEnabled", true);
            ModUnderTest.set(map, "setInteractiveMapEnabled", true);
        });
        String[][] props = {{"testkit.acsHype", "true"}, {"testkit.acsHypeWeapon", "true"}, {"testkit.acsMobs", "3"},
                {"testkit.acsMinutes", "4"}};
        String[] old = new String[props.length];
        for (int i = 0; i < props.length; i++) {
            old[i] = System.getProperty(props[i][0]);
            System.setProperty(props[i][0], props[i][1]);
        }
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        ctx.runOnClient(mc -> mc.options.renderDistance().set(16));
        int movesBefore = moves(ctx);
        List<SimAutoClearSpeedTests.RoomTime> rooms = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        try {
            println("SUMMARY " + SimAutoClearSpeedTests.oneFloor(ctx, 1, CODE, rooms, problems));
        } finally {
            SimAutoClearSpeedTests.leave(ctx);
            ctx.runOnClient(mc -> mc.options.renderDistance().set(renderBefore));
            for (int i = 0; i < props.length; i++) {
                if (old[i] == null) {
                    System.clearProperty(props[i][0]);
                } else {
                    System.setProperty(props[i][0], old[i]);
                }
            }
        }
        int movesAfter = moves(ctx);
        println("room plan moves: " + movesBefore + " -> " + movesAfter);
        long notCleared = rooms.stream().filter(r -> !r.cleared()).count();
        if (rooms.size() < 10 || notCleared > 0) {
            problems.add(rooms.size() + " room line(s), " + notCleared + " not cleared - the floor was not cleared");
        }
        SimAutoClearSpeedTests.RoomTime steps = rooms.stream().filter(r -> r.room().equals("Steps")).findFirst().orElse(null);
        if (steps == null) {
            problems.add("no room time line for Steps");
        } else {
            int actions = steps.trips() + steps.uses();
            println("Steps: " + steps.ticks() + " ticks, " + steps.trips() + " trip(s) + " + steps.uses() + " weapon use(s) = "
                    + actions + " action(s), " + steps.kills() + " kill(s), " + steps.how());
            if (!steps.cleared() || steps.kills() < 3) {
                problems.add("Steps not cleared with its 3 starred kills (" + steps.how() + ", kills " + steps.kills() + ")");
            }
            if (actions > STEPS_MAX_ACTIONS) {
                problems.add("Steps took " + actions + " actions (" + steps.trips() + " trips + " + steps.uses()
                        + " uses), want at most " + STEPS_MAX_ACTIONS + " (origin: 3 + 4 = 7)");
            }
        }
        if (movesBefore < 0 || movesAfter <= movesBefore) {
            problems.add("the room kill plan never acted (moves " + movesBefore + " -> " + movesAfter + ")");
        }
        if (!problems.isEmpty()) {
            throw new AssertionError(problems.size() + " problem(s): " + String.join("; ", problems));
        }
        println("PASS");
    }

    private static int moves(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            try {
                return (Integer) ModUnderTest.staticCall(AUTO, "killPlanMoves");
            } catch (RuntimeException | Error e) {
                return -1;   // a jar without the room kill plan
            }
        });
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}

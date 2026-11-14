package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * 1110-sim-partychat (mod simpc-1110, killer560 2026-11-10 screenshot: "Unknown or incomplete command ... pc at ss<--[HERE]"
 * in the Dungeon Sim). A mod-sent "/pc at ss" (OutgoingChat, as Posmsg sends it) in the sim shows the "Party >" line and no
 * unknown-command error; "/gc hello" is silent. The acted premise: the party line must appear.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimPartyChatTests implements FabricClientGameTest {

    private static final String NAME = "1110-sim-partychat";
    private static final String ROOM = "Chambers";
    private static final String M = "com.killer560.hub.";
    private static final String BUILDER = M + "roomsim.SimBuilder";
    private static final String ROOM_LIBRARY = M + "roomsim.RoomLibrary";
    private static final String SIM_STATE = M + "roomsim.SimState";
    private static final String SERVER_COMMANDS = M + "util.ServerCommands";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(20);
        SimMapTests.copyRoomsForOthers();
        if (Scenario.ensureRoomDatabase(ctx) == 0) {
            Scenario.skipped(NAME, "needs the room database");
            return;
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitTicks(2);
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        boolean have = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isUsable",
                new Class<?>[]{String.class}, new Object[]{ROOM}));
        if (!have) {
            Scenario.skipped(NAME, "needs a usable " + ROOM + " capture");
            return;
        }
        String fail = null;
        try {
            long before = Scenario.simBuildCount(ctx);
            ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "buildSingleRoom",
                    new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, ROOM})));
            ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
            Scenario.awaitSimBuild(ctx, before);
            ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
            ctx.waitTicks(40);
            long mark = LogTap.mark();
            boolean sent = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SERVER_COMMANDS, "toServer",
                    new Class<?>[]{String.class}, new Object[]{"pc at ss"}));
            ctx.computeOnClient(mc -> ModUnderTest.staticCall(SERVER_COMMANDS, "toServer",
                    new Class<?>[]{String.class}, new Object[]{"gc hello"}));
            ctx.waitTicks(20);
            List<String> lines = LogTap.since(mark);
            long party = lines.stream().filter(l -> l.contains("[CHAT]") && l.contains("Party > ") && l.contains(": at ss")).count();
            long unknown = lines.stream().filter(l -> l.contains("Unknown or incomplete command")).count();
            long gc = lines.stream().filter(l -> l.contains("hello") && l.contains("[CHAT]")).count();
            System.out.println("[" + NAME + "] sent=" + sent + " partyLines=" + party + " unknown=" + unknown + " gcLines=" + gc);
            if (!sent) fail = "toServer returned false";
            else if (party != 1) fail = "party line count " + party + " (wanted 1)";
            else if (unknown != 0) fail = "unknown-command line shown " + unknown + "x";
            else if (gc != 0) fail = "gc line was not silent";
            if (fail == null) {
                // typed commands: the client dispatcher must parse each sim root cleanly (no red, no error line) and the
                // typed party chat must still be answered by the sim
                long typedMark = LogTap.mark();
                String bad = ctx.computeOnClient(mc -> {
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    com.mojang.brigadier.CommandDispatcher<Object> d = (com.mojang.brigadier.CommandDispatcher)
                            net.fabricmc.fabric.impl.command.client.ClientCommandInternals.getActiveDispatcher();
                    StringBuilder sb = new StringBuilder();
                    for (String c : new String[]{"pc hi", "pc", "pchat hi there", "p chat hi", "party chat hi", "ac hi", "gc hi",
                            "oc hi", "cc hi", "msg Bob hi there", "w Bob hi", "r hi", "tell Bob hi", "whisper Bob hi", "shout hi"}) {
                        var r = d.parse(c, null);
                        if (r.getReader().canRead() || !r.getExceptions().isEmpty()) sb.append(c).append("; ");
                    }
                    try {
                        d.execute("pc typed line", null);
                    } catch (Exception e) {
                        sb.append("execute: ").append(e.getMessage());
                    }
                    return sb.toString();
                });
                ctx.waitTicks(10);
                List<String> typed = LogTap.since(typedMark);
                long typedParty = typed.stream().filter(l -> l.contains("[CHAT]") && l.contains("Party > ") && l.contains(": typed line")).count();
                System.out.println("[" + NAME + "] typed: unparsed='" + bad + "' typedPartyLines=" + typedParty);
                if (!bad.isEmpty()) fail = "client dispatcher does not parse: " + bad;
                else if (typedParty != 1) fail = "typed /pc party line count " + typedParty + " (wanted 1)";
            }
        } catch (Throwable t) {
            fail = "threw: " + t;
        } finally {
            ctx.runOnClient(mc -> {
                try {
                    ModUnderTest.staticCall(SIM_STATE, "leave", new Class<?>[]{}, new Object[]{});
                } catch (Throwable ignored) {
                    // cleanup must not replace the verdict
                }
            });
            ctx.runOnClient(mc -> mc.execute(() -> {
                if (mc.level != null) {
                    mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                    mc.disconnectWithSavingScreen();
                }
            }));
            ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 1200);
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> mc.execute(() ->
                    McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
            ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen, 400);
        }
        if (fail == null) {
            System.out.println("[" + NAME + "] PASS - /pc at ss in the sim shows the Party > line, and every sim chat command parses on the client dispatcher");
        } else {
            System.out.println("[" + NAME + "] FAIL: " + fail);
            SuiteVerdict.failCase(NAME, new AssertionError(fail));
        }
    }
}

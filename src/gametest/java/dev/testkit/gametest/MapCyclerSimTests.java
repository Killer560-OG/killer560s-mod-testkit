package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * 1118-sim-mapcycler-live (mod roomcycle-1118, Map Cycler): on a real sim F7 floor, the live map's bounds
 * ({@code RushBounds.of(DungeonLayout.current(), floor)}) always contain the true rooms between the Entrance and Blood (read
 * from the planned map code) and close to exactly it once the floor is scanned; then the cycler itself, judging that live
 * map, keeps the floor when Max Rooms Before Blood equals the truth (no command) and re-rolls it once when it is one less
 * (one /joininstance on the wire, nothing more until it is stopped).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class MapCyclerSimTests implements FabricClientGameTest {

    private static final String NAME = "1118-sim-mapcycler-live";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String FEAT = "mapcycler.MapCyclerFeature";
    private static final String CFG = "mapcycler.MapCyclerConfig";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME) || Scenario.legitSkip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (Scenario.ensureRoomDatabase(ctx) == 0) {
            Scenario.skipped(NAME, "needs the room database (killer560smod-roomdata in " + Machine.roomsInstance() + ")");
            return;
        }
        List<String> problems = new ArrayList<>();
        Object mapCfg = ctx.computeOnClient(mc -> ModUnderTest.config("com.killer560.hub.livemap.LiveMapConfig"));
        boolean oldMap = ctx.computeOnClient(mc -> ModUnderTest.getBoolean(mapCfg, "isEnabled"));
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        Object cfg = ctx.computeOnClient(mc -> Mod.cfg(CFG));
        ctx.runOnClient(mc -> {
            mc.options.renderDistance().set(16);
            ModUnderTest.set(mapCfg, "setEnabled", true);
            ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class}, new Object[]{"gametest"});
        });
        Object floor = ModUnderTest.enumValue("com.killer560.hub.roomsim.SimFloorGen$Floor", "F7");
        Object planned = ctx.computeOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimFloorGen",
                "plan", new Class<?>[]{floor.getClass(), int.class, int.class}, new Object[]{floor, 3, 5}));
        if (planned == null) {
            throw new AssertionError("SimFloorGen.plan laid out no F7");
        }
        String code = (String) Mod.call(planned, "code");
        Object dec = Mod.call(planned, "decoded");
        int truth = dev.testkit.gametest.logic.MapCycler1118Cases.truthFromCells((String[]) Mod.call(dec, "nameTable"),
                (int[]) Mod.call(dec, "cellRoom"), (int[]) Mod.call(dec, "cellDoor"));
        System.out.println("[" + NAME + "] floor " + code + ": " + truth + " room(s) between Entrance and Blood (plan said "
                + Mod.call(planned, "bloodDistance") + " not counting the Fairy)");
        if (truth < 2) {
            throw new AssertionError("the planned floor's truth is " + truth + " (no Entrance/Blood by name?)");
        }
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            ModUnderTest.staticCall(SIM_STATE, "setFloorLabel", new Class<?>[]{String.class}, new Object[]{"F7"});
            java.util.function.Consumer<Minecraft> build = cl -> ModUnderTest.staticCall(
                    "com.killer560.hub.roomsim.SimBuilder", "build", new Class<?>[]{Minecraft.class, String.class},
                    new Object[]{cl, code});
            ModUnderTest.staticCall("com.killer560.hub.roomsim.SimWorld", "open",
                    new Class<?>[]{Minecraft.class, String.class, java.util.function.Consumer.class, String.class},
                    new Object[]{mc, code, build, "Generating Floor 7"});
        }));
        ctx.waitFor(mc -> mc.level != null);
        dev.testkit.harness.WireCapture.describe = p -> p instanceof net.minecraft.network.protocol.game.ServerboundChatCommandPacket cp
                ? "cmd:" + cp.command() : "-";
        try {
            Scenario.awaitSimBuild(ctx, before);
            ctx.waitTicks(60);
            // The live map's bounds while the floor is scanned: always around the truth, exact in the end.
            int[] last = {0, Integer.MAX_VALUE};
            List<String> seen = new ArrayList<>();
            for (int i = 0; i < 90 && last[0] != last[1]; i++) {
                int[] b = ctx.computeOnClient(mc -> (int[]) Mod.staticCall("mapcycler.RushBounds", "of",
                        Mod.staticCall("livemap.DungeonLayout", "current"),
                        Mod.staticCall("secrets.DungeonState", "getFloor")));
                String s = b[0] + ".." + (b[1] == Integer.MAX_VALUE ? "?" : b[1]);
                if (seen.isEmpty() || !seen.get(seen.size() - 1).equals(s)) {
                    seen.add(s);
                }
                if (b[0] > truth || b[1] < truth) {
                    problems.add("bounds " + s + " do not contain the truth " + truth);
                }
                last = b;
                if (b[0] != b[1]) {
                    ctx.waitTicks(10);
                }
            }
            System.out.println("[" + NAME + "] live bounds over the scan: " + seen);
            if (last[0] != truth || last[1] != truth) {
                problems.add("the live bounds never settled on the truth " + truth + " (last " + seen.get(seen.size() - 1) + ")");
            }

            // The cycler on that live map: Max Rooms = truth keeps it, no command.
            int sentBefore = ctx.computeOnClient(mc -> (Integer) Mod.field(FEAT, "sent"));
            ctx.runOnClient(mc -> {
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setMaxRooms", truth);
                Mod.call(cfg, "setDecideWithinS", 60);
                Mod.call(cfg, "setRerollWhenUnsure", true);
                Mod.call(cfg, "setMaxRerolls", 0);
                Mod.staticCall(FEAT, "setRunning", true);
            });
            ctx.waitFor(mc -> !(Boolean) Mod.staticCall(FEAT, "isRunning"), 60);
            String said = ctx.computeOnClient(mc -> String.valueOf(Mod.field(FEAT, "lastSaid")));
            if (!said.contains("Found a map: " + truth + " room")) {
                problems.add("Max Rooms " + truth + " did not keep the floor: '" + said + "'");
            }
            // Max Rooms = truth - 1: one re-roll, then nothing more until stopped.
            dev.testkit.harness.WireCapture.start();
            ctx.runOnClient(mc -> {
                Mod.call(cfg, "setMaxRooms", truth - 1);
                Mod.setField(FEAT, "lastSendMs", 0L);
                // The sim's own "entered" line starts Hypixel's 30 s creation cooldown, which a re-roll waits out.
                Mod.setField("creationcooldown.CreationCooldownFeature", "endsAtMs", 0L);
                Mod.staticCall(FEAT, "setRunning", true);
            });
            ctx.waitFor(mc -> (Integer) Mod.field(FEAT, "sent") > sentBefore, 60);
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> Mod.staticCall(FEAT, "setRunning", false));
            ctx.waitTicks(40);
            dev.testkit.harness.WireCapture.stop();
            int sent = ctx.computeOnClient(mc -> (Integer) Mod.field(FEAT, "sent")) - sentBefore;
            String cmd = ctx.computeOnClient(mc -> String.valueOf(Mod.field(FEAT, "lastCommand")));
            long wire = dev.testkit.harness.WireCapture.snapshot().stream()
                    .filter(w -> w.text().startsWith("cmd:joininstance")).count();
            if (sent != 1 || wire != 1 || !cmd.equals("joininstance master_catacombs_floor_seven")) {
                problems.add("Max Rooms " + (truth - 1) + ": want one /joininstance master_catacombs_floor_seven, got "
                        + sent + " sent, " + wire + " on the wire, last '" + cmd + "'");
            }
            if (problems.isEmpty()) {
                System.out.println("[" + NAME + "] PASS - live bounds " + seen + " settle on " + truth
                        + "; Max Rooms " + truth + " kept it, " + (truth - 1) + " re-rolled once (" + cmd + ")");
            }
        } finally {
            dev.testkit.harness.WireCapture.stop();
            dev.testkit.harness.WireCapture.describe = null;
            ctx.runOnClient(mc -> {
                Mod.staticCall(FEAT, "setRunning", false);
                Mod.call(cfg, "setEnabled", false);
                ModUnderTest.set(mapCfg, "setEnabled", oldMap);
                mc.options.renderDistance().set(renderBefore);
            });
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "leave"));
            ctx.runOnClient(mc -> mc.execute(() -> {
                if (mc.level != null) {
                    mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                    mc.disconnectWithSavingScreen();
                }
            }));
            ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null);
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> mc.execute(() ->
                    McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
            ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen);
        }
        if (!problems.isEmpty()) {
            throw new AssertionError("map cycler live:\n    " + String.join("\n    ", problems));
        }
    }
}

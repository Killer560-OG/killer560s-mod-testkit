package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Are the sim's secrets in the right place?
 *
 * <h2>The claim being tested</h2>
 *
 * <p>A captured room already contains the real chests its database entry describes - the capture is a copy of
 * a real Catacombs room, secret chests and all. The sim pastes the room first and places its secrets second.
 * So a secret chest whose coordinates were translated CORRECTLY must land on a chest that is already standing
 * there, and one translated with the wrong rotation must land somewhere else.
 *
 * <p>That makes the fraction of chest secrets landing on an already-pasted chest a direct measurement of
 * whether the database rotation is right, with no Hypixel, no eyeballing and nothing to take on trust.
 *
 * <h2>Why this scenario exists</h2>
 *
 * <p>Found on 2026-09-29: the sim handed {@code toRealCoord} the rotation the room was PASTED at, as though
 * every capture had been taken with the room already in its canonical orientation. They are not - a capture
 * carries whatever quarter turn the room happened to be at when he walked through it, and only 34 of his 135
 * captures are canonical. In the other 88 identifiable rooms every secret went into the wrong corner. It was
 * invisible in square rooms because a wrong corner is still inside the room.
 *
 * <p>{@code RoomCaptureRotation} recovers that turn from the capture's own blue terracotta roof marker, with
 * the database's chest and lever positions breaking ties. This scenario is the control on it. It deliberately
 * does NOT assert 100%: a handful of database secrets sit below the captured y band (29 of his 167), a few
 * rooms have captures of the wrong size, and Hypixel's data is not perfect either. It asserts a MAJORITY,
 * which is a threshold the broken version fails outright and a working one clears comfortably.
 */
public class SimSecretRotationTests implements FabricClientGameTest {

    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String BUILD_QUEUE = "com.killer560.hub.roomsim.SimBuildQueue";
    private static final String SECRETS = "com.killer560.hub.roomsim.SimSecrets";

    private static final String SOURCE_ROOMS =
            ModUnderTest.instanceConfig("C:/Users/Hunter/AppData/Roaming/PrismLauncher/instances/26.1.2 (Mod Only Test)"
                    + "/minecraft/config", "killer560smod-rooms");

    /**
     * The bar.
     *
     * <p>Chosen from the offline measurement over all 135 captures rather than from what the run happens to
     * produce: 122 of 135 rooms resolve to a single rotation, so a working translation should land the large
     * majority of its chest secrets. A floor that manages less than this has rooms going into the wrong
     * corner again. Picking the threshold after seeing the number would make this test agree with whatever
     * the code does, which is worth nothing.
     */
    private static final double MIN_HIT_RATE = 0.70;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip("82-sim-secret-rotation")) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff(
                "com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));

        if (copyDir(SOURCE_ROOMS, "killer560smod-rooms", ".json") < 20
                || copyDir(Path.of(SOURCE_ROOMS).resolveSibling("killer560smod-roomdata").toString(),
                        "killer560smod-roomdata", "") == 0) {
            System.out.println("[82-sim-secret-rotation] SKIPPED - needs his rooms and room database");
            return;
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(
                "com.killer560.hub.roomdatabase.RoomDatabase", "ensureLoading"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomdatabase.RoomDatabase", "isReady"));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"));

        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter",
                new Class<?>[]{String.class}, new Object[]{"gametest"}));
        try {
            // Three floors, not one. Each is a different set of rooms at different rotations, and a single
            // floor could clear the bar on luck - the generator picks 21 rooms out of 135.
            int hits = 0;
            int checked = 0;
            int uncorrected = 0;
            for (int run = 1; run <= 3; run++) {
                buildFloor(ctx);
                int[] audit = (int[]) ModUnderTest.staticCall(SECRETS, "chestAudit");
                // println, not printf. The gametest client's stdout bridge only carries println, so every
                // printf diagnostic in this suite was being silently dropped - including, for one run, the
                // numbers this whole scenario exists to report while it printed PASS.
                System.out.println(String.format(
                        "[82-sim-secret-rotation] floor %d: %d of %d chest secret(s) landed on a chest the "
                                + "capture had already pasted (old translation: %d)",
                        run, audit[0], audit[1], audit[2]));
                hits += audit[0];
                checked += audit[1];
                uncorrected += audit[2];
            }

            if (checked == 0) {
                throw new AssertionError("no chest secrets were placed at all across three floors, so this "
                        + "measured nothing - the database or the room library did not load");
            }
            double rate = hits / (double) checked;
            double was = uncorrected / (double) checked;
            System.out.println(String.format(
                    "[82-sim-secret-rotation] overall %d of %d = %.0f%%  (before the capture rotation: "
                            + "%d of %d = %.0f%%)", hits, checked, rate * 100, uncorrected, checked, was * 100));
            if (rate < MIN_HIT_RATE) {
                throw new AssertionError(String.format(
                        "only %d of %d chest secrets (%.0f%%) landed on the chest the room's own capture "
                                + "already had there, against a bar of %.0f%% - database coordinates are being "
                                + "rotated wrongly, so secrets are in the wrong corner of their rooms",
                        hits, checked, rate * 100, MIN_HIT_RATE * 100));
            }
            // The control. Without this, a bar of 70% could be cleared by a translation that was already
            // fine, and the scenario would report a fix that changed nothing as proof of a fix.
            if (hits <= uncorrected) {
                throw new AssertionError(String.format(
                        "the capture rotation changed nothing: %d of %d with it and %d without. Either it is "
                                + "not being applied, or it was never the problem - do not report it as a fix",
                        hits, checked, uncorrected));
            }
            System.out.println("[82-sim-secret-rotation] PASS - secrets land where the rooms' own geometry "
                    + "says they belong, and measurably better than before");
        } finally {
            teardown(ctx);
        }
    }

    private static void buildFloor(ClientGameTestContext ctx) {
        long simBuildBefore = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
            ModUnderTest.staticCall(FLOOR_GEN, "generate",
                    new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                    new Object[]{mc, floor, 3, 4});
        }));
        ctx.waitFor(mc -> mc.level != null);
        Scenario.awaitSimBuild(ctx, simBuildBefore);
        ctx.waitTicks(60);
    }

    private static void teardown(ClientGameTestContext ctx) {
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

    private static int copyDir(String from, String into, String suffix) {
        try {
            Path source = Path.of(from);
            if (!Files.isDirectory(source)) {
                return 0;
            }
            Path target = ModUnderTest.modConfig(into);
            Files.createDirectories(target);
            int n = 0;
            try (var s = Files.list(source)) {
                for (Path f : s.toList()) {
                    if (Files.isRegularFile(f) && f.toString().endsWith(suffix)) {
                        Files.copy(f, target.resolve(f.getFileName()),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        n++;
                    }
                }
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }
}

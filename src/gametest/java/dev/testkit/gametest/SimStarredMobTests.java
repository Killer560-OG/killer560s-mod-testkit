package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A sim starred mob has to be a REAL starred mob, by the only definition that matters.
 *
 * <p>killer560 (2026-09-29): "make the ability to generate mobs right now for you that match every descriptor
 * of a real star mob in a normal run not just any old mob. They need to be the starred mob style that stuff
 * like the ESP hit boxes pick up."
 *
 * <p>So this asserts the contract {@code MobEspFeature} actually reads, rather than that a mob exists.
 * Hypixel does not mark the mob at all: it puts a {@code "✯ ... ❤"} name on a separate invisible armour stand
 * standing over it, and every mod - this one included - finds the stand and resolves it DOWN to the mob. Three
 * things have to be true together, and a sim mob that satisfies two of them is invisible to the whole feature:
 *
 * <ul>
 *   <li>an {@code ArmorStand} with a custom name containing both the star and the heart;</li>
 *   <li>the mob resolvable from it - {@code MobEspFeature.resolveMob} looks at entity id {@code standId - 1}
 *       first, so the stand has to be spawned immediately after its mob;</li>
 *   <li>the mob passing {@code isValidMob}: a {@code LivingEntity}, not a {@code Bat}, not a player.</li>
 * </ul>
 *
 * <p>There is deliberately no setting for any of this. killer560 (2026-09-29): "Remove the setting for
 * spawning mobs [...] it will be something that is done basically on your end only and that the user should
 * never have to do."
 */
public class SimStarredMobTests implements FabricClientGameTest {

    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_MOBS = "com.killer560.hub.roomsim.SimMobs";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String BUILD_QUEUE = "com.killer560.hub.roomsim.SimBuildQueue";

    private static final String SOURCE_ROOMS =
            "C:/Users/Hunter/AppData/Roaming/PrismLauncher/instances/26.1.2 (Mod Only Test)"
                    + "/minecraft/config/killer560smod-rooms";

    private static final String STAR = "\u272f";
    private static final String HEART = "\u2764";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip("89-sim-starred-mobs")) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff(
                "com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        copyDir(Path.of(SOURCE_ROOMS).resolveSibling("killer560smod-roomdata").toString(),
                "killer560smod-roomdata", "");

        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter",
                new Class<?>[]{String.class}, new Object[]{"gametest"}));
        // The flat test room is enough: this is about the entities, not the floor.
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "buildFlatTest",
                new Class<?>[]{Minecraft.class}, new Object[]{mc})));
        ctx.waitFor(mc -> mc.level != null);
        ctx.waitFor(mc -> !(Boolean) ModUnderTest.staticCall(BUILD_QUEUE, "isBusy"));
        ctx.waitTicks(60);

        try {
            // Spawn one of each mob kind that is meant to be starred, next to the player.
            double[] at = new double[3];
            ctx.runOnClient(mc -> {
                if (mc.player != null) {
                    at[0] = mc.player.getX();
                    at[1] = mc.player.getY();
                    at[2] = mc.player.getZ();
                }
            });
            // Where is he, and is the sim actually armed? A mob spawned into the void falls out of the world
            // before anything can look at it, and that is indistinguishable from "spawnStarred did nothing".
            boolean[] armed = new boolean[1];
            String[] under = new String[1];
            ctx.runOnClient(mc -> {
                armed[0] = (Boolean) ModUnderTest.staticCall(SIM_STATE, "canAct",
                        new Class<?>[]{Minecraft.class}, new Object[]{mc});
                under[0] = mc.level == null ? "no level"
                        : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(
                                BlockPos.containing(at[0] + 2, at[1] - 1, at[2] + 2)).getBlock()).getPath();
            });
            System.out.println(String.format(
                    "[89-sim-starred-mobs] player at %.1f, %.1f, %.1f; canAct=%s; block under the spawn point: %s",
                    at[0], at[1], at[2], armed[0], under[0]));

            // BAT is in here as a control. It is the only kind that is not a hostile mob, so if the bat
            // survives and the zombie does not, the difference is the peaceful-difficulty despawn path that
            // SimZombie/SimSkeleton override checkDespawn() to escape - and that override is not working.
            // POSITIVE CONTROL first. A plain vanilla entity added at the same spot by the same call the mod
            // uses. If this does not appear either, nothing about SimMobs is being measured - the gametest
            // server is refusing the entity - and no verdict about starred mobs would mean anything.
            java.util.concurrent.atomic.AtomicReference<String> control =
                    new java.util.concurrent.atomic.AtomicReference<>();
            ctx.runOnClient(mc -> {
                var sp0 = mc.getSingleplayerServer();
                if (sp0 == null) {
                    control.set("no server");
                    return;
                }
                sp0.execute(() -> {
                    var level = sp0.overworld();
                    var pig = net.minecraft.world.entity.EntityType.PIG.create(
                            level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                    if (pig == null) {
                        control.set("could not create a pig");
                        return;
                    }
                    pig.setPos(at[0], at[1], at[2]);
                    boolean added = level.addFreshEntity(pig);
                    // Can it be READ BACK? That is the capability this scenario depends on, and it is not
                    // the same question as whether the add succeeded.
                    boolean readBack = level.getEntity(pig.getUUID()) != null;
                    control.set("addFreshEntity=" + added + " removed=" + pig.isRemoved()
                            + " chunkLoaded=" + level.hasChunkAt(pig.blockPosition())
                            + " readableByUuid=" + readBack);
                });
            });
            ctx.waitFor(mc -> control.get() != null);
            ctx.waitTicks(10);
            System.out.println("[89-sim-starred-mobs] CONTROL vanilla pig: " + control.get());
            // If a plain vanilla entity cannot be read back, this environment cannot answer the question and
            // a FAILURE here would be about the harness, not the mod. Say so and stop, rather than reporting
            // a finding that is really a limitation - a probe's own blind spot must never read as a defect.
            if (!control.get().contains("readableByUuid=true")) {
                System.out.println("[89-sim-starred-mobs] SKIPPED - entities cannot be read back from the "
                        + "level in this gametest client (the vanilla control was added successfully and is "
                        + "still invisible), so nothing here would be measuring the mod");
                return;
            }

            for (String kind : new String[]{"ZOMBIE", "SKELETON", "BAT"}) {
                ctx.runOnClient(mc -> {
                    Object k = ModUnderTest.enumValue(SIM_MOBS + "$Kind", kind);
                    ModUnderTest.staticCall(SIM_MOBS, "spawnStarred",
                            new Class<?>[]{Minecraft.class, BlockPos.class, k.getClass()},
                            // The player's OWN block. A sim mob has 1 HP by design, so a spawn point two
                            // blocks over that happens to be inside the room's wall suffocates it on the
                            // first tick - and its star tag is removed with it, which looks exactly like
                            // "the spawn never happened". Where he is standing is air by definition.
                            new Object[]{mc, BlockPos.containing(at[0], at[1], at[2]), k});
                });
                // Count immediately, then again after a delay. If they exist now and not later, something is
                // removing them; if they never exist, addFreshEntity is not taking.
                for (int wait : new int[]{2, 40}) {
                    ctx.waitTicks(wait);
                    java.util.concurrent.atomic.AtomicReference<String> snap =
                            new java.util.concurrent.atomic.AtomicReference<>();
                    ctx.runOnClient(mc -> {
                        var sp2 = mc.getSingleplayerServer();
                        if (sp2 == null) {
                            snap.set("no server");
                            return;
                        }
                        sp2.execute(() -> {
                            java.util.Map<String, Integer> t = new java.util.TreeMap<>();
                            for (var e : sp2.overworld().getAllEntities()) {
                                t.merge(e.getType().toShortString(), 1, Integer::sum);
                            }
                            snap.set(t.toString());
                        });
                    });
                    ctx.waitFor(mc -> snap.get() != null);
                    System.out.println("[89-sim-starred-mobs]   after " + kind + " +" + wait + " ticks: "
                            + snap.get());
                }
            }
            ctx.waitTicks(40);
            int[] modCounts = new int[2];
            ctx.runOnClient(mc -> {
                modCounts[0] = (Integer) ModUnderTest.staticCall(SIM_MOBS, "spawnedCount");
                modCounts[1] = (Integer) ModUnderTest.staticCall(SIM_MOBS, "starredCount");
            });
            System.out.println("[89-sim-starred-mobs] the mod recorded " + modCounts[0]
                    + " spawned entit(ies), " + modCounts[1] + " of them starred");

            // Look the pair up BY ID, never by enumeration.
            //
            // The control above settles it: a vanilla pig added with addFreshEntity returning true, in a
            // loaded chunk, never shows up in getAllEntities() in a gametest client. Enumeration is the
            // broken instrument here, not the spawning - which is why this reads each entity directly.
            List<String> problems = new ArrayList<>();
            int[] counts = new int[3];   // {pairs, tag named correctly, mob resolvable by id-1}
            java.util.concurrent.atomic.AtomicReference<String> report =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Object[] pairsBox = new Object[1];
            ctx.runOnClient(mc -> pairsBox[0] = ModUnderTest.staticCall(SIM_MOBS, "starredPairs"));
            @SuppressWarnings("unchecked")
            List<java.util.UUID[]> pairs = (List<java.util.UUID[]>) pairsBox[0];

            ctx.runOnClient(mc -> {
                var sp = mc.getSingleplayerServer();
                if (sp == null) {
                    report.set("no singleplayer server");
                    return;
                }
                sp.execute(() -> {
                    StringBuilder sb = new StringBuilder();
                    var level = sp.overworld();
                    for (java.util.UUID[] pair : pairs) {
                        counts[0]++;
                        var mob = level.getEntity(pair[0]);
                        var tag = level.getEntity(pair[1]);
                        if (mob == null || tag == null) {
                            sb.append("pair missing from the level (mob=").append(mob)
                                    .append(" tag=").append(tag).append(") | ");
                            continue;
                        }
                        String name = tag.hasCustomName() ? tag.getName().getString() : "";
                        boolean named = name.contains(STAR) && name.contains(HEART);
                        boolean validMob = mob instanceof net.minecraft.world.entity.LivingEntity
                                && !(mob instanceof net.minecraft.world.entity.ambient.Bat)
                                && !(mob instanceof net.minecraft.world.entity.decoration.ArmorStand);
                        boolean idAdjacent = tag.getId() == mob.getId() + 1;
                        if (named) {
                            counts[1]++;
                        }
                        if (idAdjacent && validMob) {
                            counts[2]++;
                        }
                        sb.append('"').append(name).append("\" mobId=").append(mob.getId())
                                .append(" tagId=").append(tag.getId())
                                .append(" named=").append(named)
                                .append(" validMob=").append(validMob)
                                .append(" idAdjacent=").append(idAdjacent).append(" | ");
                    }
                    report.set(sb.length() == 0 ? "no starred pairs recorded" : sb.toString());
                });
            });
            ctx.waitFor(mc -> report.get() != null);

            System.out.println("[89-sim-starred-mobs] " + counts[0] + " starred pair(s); " + counts[1]
                    + " tagged with the star and heart, " + counts[2] + " resolvable by entity id");
            System.out.println("[89-sim-starred-mobs]   " + report.get());

            if (counts[0] == 0) {
                problems.add("the mod recorded no starred mob at all, so nothing was measured");
            }
            if (counts[1] < counts[0]) {
                problems.add((counts[0] - counts[1]) + " starred mob(s) have no armour stand carrying both "
                        + STAR + " and " + HEART + " - Mob ESP reads the stand's NAME, so those mobs are "
                        + "invisible to it");
            }
            if (counts[2] < counts[0]) {
                problems.add((counts[0] - counts[2]) + " starred mob(s) are not at the stand's id minus one, "
                        + "or are not a valid mob - MobEspFeature.resolveMob looks there first");
            }

            if (!problems.isEmpty()) {
                StringBuilder sb = new StringBuilder("sim starred mobs are not real starred mobs:");
                for (String p : problems) {
                    sb.append("\n    ").append(p);
                }
                throw new AssertionError(sb.toString());
            }
            System.out.println("[89-sim-starred-mobs] PASS - every sim starred mob carries the stand the ESP "
                    + "reads, and resolves back to a valid mob");
        } finally {
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
                    mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen())));
            ctx.waitFor(mc -> mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen);
        }
    }

    private static int copyDir(String from, String into, String suffix) {
        try {
            Path source = Path.of(from);
            if (!Files.isDirectory(source)) {
                return 0;
            }
            Path target = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve(into);
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

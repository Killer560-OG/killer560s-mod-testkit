package dev.testkit.gametest;

import dev.testkit.harness.PacketWatch;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Two things killer560 took from QUOI's Secret Aura (mod aura-quoi, 2026-10-12), on a real GrimAC. Cheat jar; a legit
 * jar has no Secret Aura and is judged by nothing going out. Both run with {@link Scenario#run}, so any GrimAC line fails.
 * <ul>
 *   <li>841-aura-world-change: after a WORLD change (nether and back) Secret Aura sends nothing for 20 ticks, then
 *       clicks. Two controls in the same run say the 20 is the world change's: the first lever, with the aura switched on
 *       beside it, and a same-world teleport onto a second lever, are both clicked well inside 20 ticks. The world change
 *       is timed by the client's own level object being replaced, the click by the use packet's tick.</li>
 *   <li>842-aura-skull-range: a Wither Essence skull (Hypixel's profile id) 4.8 blocks from the eye to its centre is
 *       not clicked; at 4.6 it is. Skull Range is set to 4.7 first and must read back 4.7 (until aura-quoi it clamped to
 *       4.5, measured to the box, which clicked both).</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class AuraQuoiTests implements FabricClientGameTest {

    private static final String MOD_ID = "killer560smod";
    private static final String CHEAT_CFG = "com.killer560.hub.cheatutils.CheatUtilsConfig";
    private static final String DUNGEON_LABEL = "localhost.p3sim.net:25565";
    private static final int SURFACE_Y = 151;
    private static final int BASE_Z = -300;
    /** Hypixel's Wither Essence skull profile id 2865274b-3097-394e-8149-ec629c72d850 as an int array. */
    private static final String ESSENCE_ID = "[I;677717835,815216974,-2125861790,-1670195120]";
    /** The Redstone Key skin's profile id fed95410-aba1-39df-9b95-1d4f361eb66e as an int array. */
    private static final String REDSTONE_KEY_ID = "[I;-19311600,-1415497249,-1684726449,907982446]";
    /** A decorative head: any other profile (11111111-2222-3333-4444-555555555555). */
    private static final String DECOR_ID = "[I;286331153,572666675,1145328981,1431655765]";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        worldChange(ctx);
        skullRange(ctx);
        redstoneKey(ctx);
        keyPlace(ctx);
    }

    private static boolean cheat() {
        try {
            return Class.forName("com.killer560.hub.BuildVariant").getField("CHEAT_FEATURES_ENABLED").getBoolean(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no BuildVariant in the mod under test", e);
        }
    }

    /** A wall lever at (x, y+1, z+3) on stone behind it, facing a player who stands at (x+0.5, z+0.5) with yaw 0. */
    private static String[] lever(int x, int z) {
        return new String[]{
                "setblock " + x + " " + (SURFACE_Y + 1) + " " + (z + 4) + " minecraft:stone",
                "setblock " + x + " " + (SURFACE_Y + 1) + " " + (z + 3) + " minecraft:lever[face=wall,facing=north]"};
    }

    private static void auraOn(ClientGameTestContext ctx, boolean levers, boolean essence) {
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(CHEAT_CFG);
            ModUnderTest.set(cfg, "setAuraChests", false);
            ModUnderTest.set(cfg, "setAuraLevers", levers);
            ModUnderTest.set(cfg, "setAuraEssence", essence);
            ModUnderTest.set(cfg, "setAuraPauseWhileSneaking", false);
            ModUnderTest.set(cfg, "setAuraRange", 4.5);
            ModUnderTest.set(cfg, "setSecretAuraEnabled", true);
            if (cheat() && !ModUnderTest.getBoolean(cfg, "isSecretAuraEnabled")) {
                throw new AssertionError("isSecretAuraEnabled() is false - needs the cheat jar");
            }
        });
    }

    /** Ticks until the first use packet goes out, or -1 within {@code max}. */
    private static int ticksToFirstUse(ClientGameTestContext ctx, int max) {
        int before = PacketWatch.totalUses();
        for (int t = 1; t <= max; t++) {
            ctx.waitTick();
            if (PacketWatch.totalUses() > before) {
                return t;
            }
        }
        return -1;
    }

    private static boolean powered(ClientGameTestContext ctx, BlockPos pos) {
        return ctx.computeOnClient(mc -> {
            var s = mc.level.getBlockState(pos);
            return s.hasProperty(LeverBlock.POWERED) && s.getValue(LeverBlock.POWERED);
        });
    }

    // ---- 841 ----

    private void worldChange(ClientGameTestContext ctx) {
        String name = "841-aura-world-change";
        if (Scenario.skip(name)) {
            return;
        }
        ModUnderTest.require(MOD_ID);
        final int ax = -600, bx = -620, cx = -640;
        BlockPos leverA = new BlockPos(ax, SURFACE_Y + 1, BASE_Z + 3);
        BlockPos leverB = new BlockPos(bx, SURFACE_Y + 1, BASE_Z + 3);
        BlockPos leverC = new BlockPos(cx, SURFACE_Y + 1, BASE_Z + 3);
        Scenario.labelServerAs(DUNGEON_LABEL);
        Scenario.run(ctx, name,
                (server, scenario) -> {
                    TestMap map = TestMap.on(server)
                            .floor(cx - 3, SURFACE_Y - 1, BASE_Z - 3, ax + 3, BASE_Z + 6)
                            .catchFloor(120)
                            .survival()
                            .clearInventory()
                            .skyblockSidebar("F7");
                    for (int x : new int[]{ax, bx, cx}) {
                        for (String c : lever(x, BASE_Z)) {
                            map.command(c);
                        }
                    }
                    // A bare obsidian pad in the nether to stand on between the two halves.
                    map.command("execute in minecraft:the_nether run forceload add 0 0")
                            .command("execute in minecraft:the_nether run fill -3 99 -3 3 99 3 minecraft:obsidian")
                            .command("execute in minecraft:the_nether run fill -3 100 -3 3 104 3 minecraft:air")
                            .spawn(ax + 0.5, BASE_Z + 0.5, 0f)
                            .build();
                },
                (server, scenario) -> {
                    scenario.assertDetectorWorks();
                    ctx.waitTicks(30);
                    PacketWatch.start();
                    List<String> facts = new ArrayList<>();

                    // Control 1: switched on beside lever A.
                    auraOn(ctx, true, false);
                    int a = ticksToFirstUse(ctx, 60);
                    ctx.waitTicks(10);
                    facts.add("A (switched on beside it): first use after " + a + " tick(s), lever "
                            + (powered(ctx, leverA) ? "flipped" : "not flipped"));

                    // Control 2: a same-world teleport onto lever B (ActionGate's 6-tick teleport settle).
                    server.command(String.format(Locale.ROOT, "tp @p %.2f %d %.2f 0 0", bx + 0.5, SURFACE_Y, BASE_Z + 0.5));
                    long[] tpAt = {-1};
                    int b = -1;
                    int beforeB = PacketWatch.totalUses();
                    for (int t = 1; t <= 80; t++) {
                        ctx.waitTick();
                        if (tpAt[0] < 0 && ctx.computeOnClient(mc -> Math.abs(mc.player.getX() - (bx + 0.5)) < 0.1)) {
                            tpAt[0] = t;
                        }
                        if (PacketWatch.totalUses() > beforeB) {
                            b = tpAt[0] < 0 ? -1 : (int) (t - tpAt[0]);
                            break;
                        }
                    }
                    ctx.waitTicks(10);
                    facts.add("B (same-world teleport): first use " + b + " tick(s) after the client stood there, lever "
                            + (powered(ctx, leverB) ? "flipped" : "not flipped"));

                    // The world change: into the nether, then back onto lever C.
                    Object overworldLevel = ctx.computeOnClient(mc -> (Object) mc.level);
                    server.command("execute in minecraft:the_nether run tp @p 0.5 100 0.5 0 0");
                    ctx.waitFor(mc -> mc.level != null && mc.level != overworldLevel && mc.player != null
                            && Math.abs(mc.player.getX() - 0.5) < 0.1, 600);
                    ctx.waitTicks(60);
                    Object netherLevel = ctx.computeOnClient(mc -> (Object) mc.level);
                    int beforeC = PacketWatch.totalUses();
                    server.command(String.format(Locale.ROOT,
                            "execute in minecraft:overworld run tp @p %.2f %d %.2f 0 0", cx + 0.5, SURFACE_Y, BASE_Z + 0.5));
                    int changeAt = -1;
                    int c = -1;
                    int usesInPause = 0;
                    for (int t = 1; t <= 200; t++) {
                        ctx.waitTick();
                        if (changeAt < 0 && ctx.computeOnClient(mc -> mc.level != null && mc.level != netherLevel)) {
                            changeAt = t;
                        }
                        int uses = PacketWatch.totalUses() - beforeC;
                        if (changeAt >= 0 && t - changeAt < 20) {
                            usesInPause = uses;
                        }
                        if (uses > 0 && c < 0) {
                            c = changeAt < 0 ? -1 : t - changeAt;
                            break;
                        }
                    }
                    ctx.waitTicks(10);
                    boolean flippedC = powered(ctx, leverC);
                    Vec3 at = ctx.computeOnClient(mc -> mc.player.position());
                    facts.add("C (world change, nether -> overworld): level replaced " + changeAt
                            + " tick(s) after the tp, uses in its first 20 ticks " + usesInPause + ", first use "
                            + c + " tick(s) after the change, lever " + (flippedC ? "flipped" : "not flipped")
                            + String.format(Locale.ROOT, ", player at %.2f %.2f %.2f", at.x, at.y, at.z));
                    PacketWatch.stop();
                    ctx.runOnClient(mc -> ModUnderTest.turnOff(CHEAT_CFG, "setSecretAuraEnabled"));
                    for (String f : facts) {
                        scenario.log("RESULT " + f);
                    }
                    scenario.log(PacketWatch.summary());
                    if (!cheat()) {
                        if (PacketWatch.totalUses() != 0) {
                            throw new AssertionError("a legit jar clicked a lever by itself");
                        }
                        scenario.log("legit jar: Secret Aura is compiled out and nothing was clicked");
                        return;
                    }
                    if (a < 0 || a >= 20 || !powered(ctx, leverA)) {
                        throw new AssertionError("control A failed - the aura did not click a lever beside it within 20 ticks");
                    }
                    if (b < 0 || b >= 20 || !powered(ctx, leverB)) {
                        throw new AssertionError("control B failed - a same-world teleport held the aura 20+ ticks (" + b + ")");
                    }
                    if (changeAt < 0) {
                        throw new AssertionError("premise - the client never changed level");
                    }
                    if (usesInPause != 0 || c < 20) {
                        throw new AssertionError("Secret Aura clicked " + c + " tick(s) after a world change (" + usesInPause
                                + " use(s) inside the 20-tick pause)");
                    }
                    if (!flippedC) {
                        throw new AssertionError("clicks did not resume after the pause - lever C never flipped");
                    }
                });
    }

    // ---- 842 ----

    private void skullRange(ClientGameTestContext ctx) {
        String name = "842-aura-skull-range";
        if (Scenario.skip(name)) {
            return;
        }
        ModUnderTest.require(MOD_ID);
        final int sx = -680;
        final int sz = BASE_Z + 6;
        BlockPos skull = new BlockPos(sx, SURFACE_Y + 1, sz);
        Scenario.labelServerAs(DUNGEON_LABEL);
        Scenario.run(ctx, name,
                (server, scenario) -> TestMap.on(server)
                        .platform(sx, SURFACE_Y - 1, sz - 3, 6)
                        .catchFloor(120)
                        .survival()
                        .clearInventory()
                        .skyblockSidebar("F7")
                        .command("setblock " + sx + " " + (SURFACE_Y + 1) + " " + sz
                                + " minecraft:player_head[rotation=8]{profile:{id:" + ESSENCE_ID + ",name:\"WitherEssence\"}}")
                        .spawn(sx + 0.5, sz - 5.5, 0f)
                        .build(),
                (server, scenario) -> {
                    scenario.assertDetectorWorks();
                    ctx.waitTicks(30);
                    boolean essence = ctx.computeOnClient(mc -> {
                        try {
                            return (Boolean) Class.forName("com.killer560.hub.secrets.SecretsFeature")
                                    .getMethod("isWitherEssence", net.minecraft.world.level.BlockGetter.class, BlockPos.class)
                                    .invoke(null, mc.level, skull);
                        } catch (ReflectiveOperationException e) {
                            throw new AssertionError(e);
                        }
                    });
                    if (!essence) {
                        throw new AssertionError("premise - the client does not read the skull as Wither Essence");
                    }
                    List<String> facts = new ArrayList<>();
                    double[] want = {4.8, 4.6};
                    int[] uses = new int[want.length];
                    ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(CHEAT_CFG), "setAuraSkullRange", 4.7));
                    double readBack = ctx.computeOnClient(mc -> {
                        try {
                            Object cfg = ModUnderTest.config(CHEAT_CFG);
                            return (Double) cfg.getClass().getMethod("getAuraSkullRange").invoke(cfg);
                        } catch (ReflectiveOperationException e) {
                            throw new AssertionError(e);
                        }
                    });
                    facts.add("Skull Range set to 4.7, reads back " + readBack);
                    PacketWatch.start();
                    for (int i = 0; i < want.length; i++) {
                        // Eye 1.62 over the feet, the skull block's centre 1.5 over them: solve the horizontal leg.
                        double h = Math.sqrt(want[i] * want[i] - 0.12 * 0.12);
                        double z = sz + 0.5 - h;
                        ctx.runOnClient(mc -> ModUnderTest.turnOff(CHEAT_CFG, "setSecretAuraEnabled"));
                        server.command(String.format(Locale.ROOT, "tp @p %.4f %d %.4f 0 0", sx + 0.5, SURFACE_Y, z));
                        ctx.waitFor(mc -> Math.abs(mc.player.getZ() - z) < 0.01 && mc.player.onGround(), 200);
                        ctx.waitTicks(10);
                        double[] d = ctx.computeOnClient(mc -> {
                            Vec3 eye = mc.player.getEyePosition();
                            double bx = Math.max(skull.getX(), Math.min(eye.x, skull.getX() + 1)) - eye.x;
                            double by = Math.max(skull.getY(), Math.min(eye.y, skull.getY() + 1)) - eye.y;
                            double bz = Math.max(skull.getZ(), Math.min(eye.z, skull.getZ() + 1)) - eye.z;
                            return new double[]{eye.distanceTo(Vec3.atCenterOf(skull)), Math.sqrt(bx * bx + by * by + bz * bz)};
                        });
                        int before = PacketWatch.totalUses();
                        auraOn(ctx, false, true);
                        ctx.waitTicks(60);
                        uses[i] = PacketWatch.totalUses() - before;
                        facts.add(String.format(Locale.ROOT, "eye to center %.3f (box %.3f): %d use(s) in 60 ticks",
                                d[0], d[1], uses[i]));
                        if (Math.abs(d[0] - want[i]) > 0.02) {
                            throw new AssertionError("premise - stood " + d[0] + " from the center, wanted " + want[i]);
                        }
                    }
                    PacketWatch.stop();
                    ctx.runOnClient(mc -> ModUnderTest.turnOff(CHEAT_CFG, "setSecretAuraEnabled"));
                    for (String f : facts) {
                        scenario.log("RESULT " + f);
                    }
                    scenario.log(PacketWatch.summary());
                    if (!cheat()) {
                        if (PacketWatch.totalUses() != 0) {
                            throw new AssertionError("a legit jar clicked the skull by itself");
                        }
                        return;
                    }
                    if (Math.abs(readBack - 4.7) > 1e-9) {
                        throw new AssertionError("Skull Range reads back " + readBack + ", not 4.7");
                    }
                    if (uses[0] != 0) {
                        throw new AssertionError("the skull 4.8 from the eye was clicked (" + uses[0] + " use(s))");
                    }
                    if (uses[1] == 0) {
                        throw new AssertionError("the skull 4.6 from the eye was never clicked");
                    }
                });
    }

    // ---- 847 ----

    /**
     * 847-aura-redstone-key (mod aura-2): Secret Aura picks up a Redstone Key skull 4.6 from the eye to its centre, and the
     * key is gone afterwards - the test server takes it away on the click as Hypixel does (testmod RedstoneKeyPickup). A
     * decorative head (another profile) at the same 4.6 is never clicked. Essence is switched OFF, so only the key option
     * can click it. GrimAC clean.
     */
    private void redstoneKey(ClientGameTestContext ctx) {
        String name = "847-aura-redstone-key";
        if (Scenario.skip(name)) {
            return;
        }
        ModUnderTest.require(MOD_ID);
        final int dx = -720;
        final int kx = -710;
        final int sz = BASE_Z + 6;
        BlockPos decor = new BlockPos(dx, SURFACE_Y + 1, sz);
        BlockPos key = new BlockPos(kx, SURFACE_Y + 1, sz);
        Scenario.labelServerAs(DUNGEON_LABEL);
        Scenario.run(ctx, name,
                (server, scenario) -> TestMap.on(server)
                        .floor(dx - 4, SURFACE_Y - 1, sz - 9, kx + 4, sz + 3)
                        .catchFloor(120)
                        .survival()
                        .clearInventory()
                        .skyblockSidebar("F7")
                        .command("setblock " + dx + " " + (SURFACE_Y + 1) + " " + sz
                                + " minecraft:player_head[rotation=8]{profile:{id:" + DECOR_ID + ",name:\"Decoration\"}}")
                        .command("setblock " + kx + " " + (SURFACE_Y + 1) + " " + sz
                                + " minecraft:player_head[rotation=8]{profile:{id:" + REDSTONE_KEY_ID + ",name:\"RedstoneKey\"}}")
                        .spawn(dx + 0.5, sz - 5.5, 0f)
                        .build(),
                (server, scenario) -> {
                    scenario.assertDetectorWorks();
                    ctx.waitTicks(30);
                    boolean[] kinds = ctx.computeOnClient(mc -> {
                        try {
                            var m = Class.forName("com.killer560.hub.secrets.SecretsFeature")
                                    .getMethod("isRedstoneKey", net.minecraft.world.level.BlockGetter.class, BlockPos.class);
                            return new boolean[]{(Boolean) m.invoke(null, mc.level, key), (Boolean) m.invoke(null, mc.level, decor)};
                        } catch (ReflectiveOperationException e) {
                            throw new AssertionError(e);
                        }
                    });
                    if (!kinds[0] || kinds[1]) {
                        throw new AssertionError("premise - the client reads key=" + kinds[0] + " decoration=" + kinds[1]);
                    }
                    List<String> facts = new ArrayList<>();
                    int[] uses = new int[2];
                    BlockPos[] heads = {decor, key};
                    PacketWatch.start();
                    for (int i = 0; i < 2; i++) {
                        BlockPos head = heads[i];
                        double h = Math.sqrt(4.6 * 4.6 - 0.12 * 0.12);
                        double z = sz + 0.5 - h;
                        ctx.runOnClient(mc -> ModUnderTest.turnOff(CHEAT_CFG, "setSecretAuraEnabled"));
                        server.command(String.format(Locale.ROOT, "tp @p %.4f %d %.4f 0 0", head.getX() + 0.5, SURFACE_Y, z));
                        ctx.waitFor(mc -> Math.abs(mc.player.getZ() - z) < 0.01 && Math.abs(mc.player.getX() - (head.getX() + 0.5)) < 0.01
                                && mc.player.onGround(), 200);
                        ctx.waitTicks(10);
                        double centre = ctx.computeOnClient(mc -> mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(head)));
                        int before = PacketWatch.totalUses();
                        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(CHEAT_CFG), "setAuraRedstoneKey", true));
                        auraOn(ctx, false, false);
                        ctx.waitTicks(60);
                        uses[i] = PacketWatch.totalUses() - before;
                        String what = i == 0 ? "decorative head" : "redstone key";
                        boolean gone = ctx.computeOnClient(mc -> mc.level.getBlockState(head).isAir());
                        facts.add(String.format(Locale.ROOT, "%s at %.3f from the eye to its center: %d use(s) in 60 ticks, "
                                + "block %s afterwards", what, centre, uses[i], gone ? "GONE" : "still there"));
                        if (Math.abs(centre - 4.6) > 0.02) {
                            throw new AssertionError("premise - stood " + centre + " from the " + what + "'s center");
                        }
                    }
                    PacketWatch.stop();
                    ctx.runOnClient(mc -> ModUnderTest.turnOff(CHEAT_CFG, "setSecretAuraEnabled"));
                    boolean serverSaw = server.logged("redstone key at " + kx + "," + (SURFACE_Y + 1) + "," + sz + " picked up");
                    boolean keyGone = ctx.computeOnClient(mc -> mc.level.getBlockState(key).isAir());
                    boolean decorThere = ctx.computeOnClient(mc -> !mc.level.getBlockState(decor).isAir());
                    facts.add("server logged the pickup: " + serverSaw);
                    for (String f : facts) {
                        scenario.log("RESULT " + f);
                    }
                    scenario.log(PacketWatch.summary());
                    if (!cheat()) {
                        if (PacketWatch.totalUses() != 0) {
                            throw new AssertionError("a legit jar clicked a head by itself");
                        }
                        return;
                    }
                    if (uses[0] != 0 || !decorThere) {
                        throw new AssertionError("the decorative head was clicked (" + uses[0] + " use(s))");
                    }
                    if (uses[1] == 0 || !serverSaw || !keyGone) {
                        throw new AssertionError("the redstone key was not picked up: " + uses[1] + " use(s), server pickup "
                                + serverSaw + ", gone on the client " + keyGone);
                    }
                });
    }

    // ---- 848 ----

    /**
     * 848-aura-redstone-key-place (mod key-place-2): the Redstone Key end to end on the test server, which plays it as
     * Hypixel does (testmod RedstoneKeyPickup: a key found by anyone; a click on a redstone block's AIR-facing side sets
     * the key's head on TOP of it; every use on the block is logged with its face).
     * <ol>
     *   <li>A key skull 11 blocks from the eye (to its centre) vanishes: not counted. He is then put by the redstone
     *       block - Place Redstone Key on - and nothing is clicked.</li>
     *   <li>Another key 9 blocks away vanishes (a teammate's pickup, {@code testkitkey found}): counted. By the block with
     *       Place OFF: 0 uses. Place ON: exactly one use, the key's head on top of the block.</li>
     * </ol>
     * The face of the block the eye-to-centre ray meets (north, toward him) has stone against it, so the aura must pick
     * another face open to air (west); the server log must show no use on a covered face. GrimAC clean.
     */
    private void keyPlace(ClientGameTestContext ctx) {
        String name = "848-aura-redstone-key-place";
        if (Scenario.skip(name)) {
            return;
        }
        ModUnderTest.require(MOD_ID);
        final int kx = -760;
        final int sz = BASE_Z + 6;
        BlockPos node = new BlockPos(kx + 1, SURFACE_Y + 1, sz - 2);
        BlockPos cover = node.north();
        BlockPos keyFar = new BlockPos(kx, SURFACE_Y + 1, sz + 16);
        BlockPos keyNear = new BlockPos(kx + 2, SURFACE_Y + 1, sz + 16);
        String keyNbt = " minecraft:player_head[rotation=8]{profile:{id:" + REDSTONE_KEY_ID + ",name:\"RedstoneKey\"}}";
        Scenario.labelServerAs(DUNGEON_LABEL);
        Scenario.run(ctx, name,
                (server, scenario) -> TestMap.on(server)
                        .floor(kx - 4, SURFACE_Y - 1, sz - 9, kx + 6, sz + 18)
                        .catchFloor(120)
                        .survival()
                        .clearInventory()
                        .skyblockSidebar("F7")
                        .command("setblock " + node.getX() + " " + node.getY() + " " + node.getZ() + " minecraft:redstone_block")
                        .command("setblock " + cover.getX() + " " + cover.getY() + " " + cover.getZ() + " minecraft:stone")
                        .spawn(kx + 0.5, sz - 5.5, 0f)
                        .build(),
                (server, scenario) -> {
                    scenario.assertDetectorWorks();
                    List<String> facts = new ArrayList<>();
                    String placedLine = "redstone key placed at " + node.getX() + "," + node.getY() + "," + node.getZ();
                    ctx.runOnClient(mc -> {
                        Object cfg = ModUnderTest.config(CHEAT_CFG);
                        ModUnderTest.set(cfg, "setAuraRedstoneKey", false); // the keys here vanish, they are never clicked
                        ModUnderTest.set(cfg, "setAuraPlaceRedstoneKey", true);
                    });
                    auraOn(ctx, false, false);
                    PacketWatch.start();

                    // 1. A key 11 blocks away vanishes: not counted.
                    double dFar = standFrom(ctx, server, keyFar, 11.0);
                    server.command("setblock " + keyFar.getX() + " " + keyFar.getY() + " " + keyFar.getZ() + keyNbt);
                    ctx.waitFor(mc -> mc.level.getBlockState(keyFar).getBlock()
                            instanceof net.minecraft.world.level.block.AbstractSkullBlock, 100);
                    ctx.waitTicks(10);
                    server.command("setblock " + keyFar.getX() + " " + keyFar.getY() + " " + keyFar.getZ() + " minecraft:air");
                    ctx.waitFor(mc -> mc.level.getBlockState(keyFar).isAir(), 100);
                    ctx.waitTicks(5);
                    double dNode1 = standByNode(ctx, server, node);
                    int before1 = PacketWatch.totalUses();
                    ctx.waitTicks(40);
                    int uses1 = PacketWatch.totalUses() - before1;
                    facts.add(String.format(Locale.ROOT, "1 (key vanished %.2f away): by the block (%.2f to its center) "
                            + "%d use(s) in 40 ticks", dFar, dNode1, uses1));

                    // 2. A key 9 blocks away vanishes (a teammate's pickup): counted. Place OFF, then ON.
                    double dNear = standFrom(ctx, server, keyNear, 9.0);
                    server.command("setblock " + keyNear.getX() + " " + keyNear.getY() + " " + keyNear.getZ() + keyNbt);
                    ctx.waitFor(mc -> mc.level.getBlockState(keyNear).getBlock()
                            instanceof net.minecraft.world.level.block.AbstractSkullBlock, 100);
                    ctx.waitTicks(10);
                    server.command("testkitkey found");
                    server.command("setblock " + keyNear.getX() + " " + keyNear.getY() + " " + keyNear.getZ() + " minecraft:air");
                    ctx.waitFor(mc -> mc.level.getBlockState(keyNear).isAir(), 100);
                    ctx.waitTicks(5);
                    ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(CHEAT_CFG), "setAuraPlaceRedstoneKey", false));
                    standByNode(ctx, server, node);
                    int before2 = PacketWatch.totalUses();
                    ctx.waitTicks(40);
                    int usesOff = PacketWatch.totalUses() - before2;
                    ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(CHEAT_CFG), "setAuraPlaceRedstoneKey", true));
                    int before3 = PacketWatch.totalUses();
                    int waited = 0;
                    for (; waited < 60 && !server.logged(placedLine); waited += 5) {
                        ctx.waitTicks(5);
                    }
                    ctx.waitTicks(40);
                    int usesOn = PacketWatch.totalUses() - before3;
                    PacketWatch.stop();
                    ctx.runOnClient(mc -> ModUnderTest.turnOff(CHEAT_CFG, "setSecretAuraEnabled"));
                    boolean placed = server.logged(placedLine);
                    boolean coveredTried = server.logged(" covered,");
                    List<String> faces = server.since().stream().filter(l -> l.contains("redstone block used at")).toList();
                    boolean headOnTop = ctx.computeOnClient(mc -> mc.level.getBlockState(node.above()).getBlock()
                            instanceof net.minecraft.world.level.block.AbstractSkullBlock);
                    facts.add(String.format(Locale.ROOT, "2 (key vanished %.2f away): Place off %d use(s) in 40 ticks; Place on "
                            + "%d use(s), placed %s after ~%d ticks, key head on top %s", dNear, usesOff, usesOn, placed, waited,
                            headOnTop));
                    facts.add("uses on the redstone block, server side: " + faces);
                    for (String f : facts) {
                        scenario.log("RESULT " + f);
                    }
                    scenario.log(PacketWatch.summary());
                    if (!cheat()) {
                        if (PacketWatch.totalUses() != 0) {
                            throw new AssertionError("a legit jar clicked by itself");
                        }
                        return;
                    }
                    if (uses1 != 0) {
                        throw new AssertionError("a key that vanished 11 blocks away was counted: " + facts.get(0));
                    }
                    if (usesOff != 0) {
                        throw new AssertionError("Place Redstone Key off, yet the block was clicked: " + facts.get(1));
                    }
                    if (coveredTried) {
                        throw new AssertionError("a covered face of the redstone block was clicked: " + faces);
                    }
                    if (usesOn != 1 || !placed || !headOnTop) {
                        throw new AssertionError("wanted exactly one placement with the head on top: " + facts.get(1));
                    }
                });
    }

    /** Put him so his eye is {@code d} from the skull block's centre, due south of it; returns the distance he stands at. */
    private static double standFrom(ClientGameTestContext ctx, TestServer server, BlockPos skull, double d) {
        double z = skull.getZ() + 0.5 - Math.sqrt(d * d - 0.12 * 0.12);
        server.command(String.format(Locale.ROOT, "tp @p %.4f %d %.4f 0 0", skull.getX() + 0.5, SURFACE_Y, z));
        ctx.waitFor(mc -> Math.abs(mc.player.getZ() - z) < 0.01 && mc.player.onGround(), 200);
        ctx.waitTicks(5);
        return ctx.computeOnClient(mc -> mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(skull)));
    }

    /** By the redstone block, south-west of it, its north face (toward him on the eye-to-centre line) covered. */
    private static double standByNode(ClientGameTestContext ctx, TestServer server, BlockPos node) {
        double x = node.getX() - 0.5;
        double z = node.getZ() - 2.098;
        server.command(String.format(Locale.ROOT, "tp @p %.4f %d %.4f 0 0", x, SURFACE_Y, z));
        ctx.waitFor(mc -> Math.abs(mc.player.getZ() - z) < 0.01 && Math.abs(mc.player.getX() - x) < 0.01
                && mc.player.onGround(), 200);
        ctx.waitTicks(5);
        return ctx.computeOnClient(mc -> mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(node)));
    }
}

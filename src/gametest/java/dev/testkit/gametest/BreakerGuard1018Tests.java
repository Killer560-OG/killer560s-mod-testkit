package dev.testkit.gametest;

import dev.testkit.harness.PacketWatch;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * killer560s-mod's Dungeon Breaker guard (killer560, 2026-10-18: "make an option for no breaking secrets and add in
 * the fairy room stuff but not for puzzles"). Three scenarios, each proving the breaker ACTED in its unguarded case:
 * <ul>
 *   <li>{@code 1018-breaker-secrets-on} - "Don't break secrets" on: a breaker click digs the snow and never the
 *       lever or chest beside it.</li>
 *   <li>{@code 1018-breaker-secrets-off} - the same arena with the option off: all three are dug (the control; the
 *       option is what makes the difference).</li>
 *   <li>{@code 1018-breaker-fairy} - a breaker click on snow outside a Fairy room goes out; the same click on snow
 *       inside a Fairy room does not. No setting involved.</li>
 * </ul>
 * The clicks go through {@code MultiPlayerGameMode.startDestroyBlock}, where the Zero Ping hook sits. Breaker Aura is
 * not used: its own blacklist already refuses levers and chests, and it is only awake in a boss fight, where there are
 * no Fairy rooms.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class BreakerGuard1018Tests implements FabricClientGameTest {

    private static final String MOD_ID = "killer560smod";
    private static final String BREAKER_CONFIG = "com.killer560.hub.dungeonbreaker.DungeonBreakerConfig";
    private static final String DUNGEON_STATE = "com.killer560.hub.secrets.DungeonState";
    private static final String LIVE_MAP = "com.killer560.hub.livemap.LiveMapFeature";

    private static final int SURFACE_Y = 151;
    private static final int SECRET_CX = 90;
    private static final int SECRET_CZ = 60;
    /** Grid cell (0, 0) of the dungeon map: START_X / START_Z are -185, and a block within 16 of that is in it. */
    private static final int FAIRY_CX = -185;
    private static final int FAIRY_CZ = -185;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        secrets(ctx, "1018-breaker-secrets-on", true);
        secrets(ctx, "1018-breaker-secrets-off", false);
        fairy(ctx);
        dungeonsOnly(ctx);
    }

    private static boolean cheat(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            try {
                return Class.forName("com.killer560.hub.BuildVariant").getField("CHEAT_FEATURES_ENABLED").getBoolean(null);
            } catch (ReflectiveOperationException e) {
                return false;
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ dungeons only

    /**
     * {@code 1104-breaker-dungeons-only} (mod brkdun-1104, killer560 2026-11-04: "make dungeon breaker have a dungeons
     * only option to it for zero ping"): outside a dungeon, Zero Ping's {@code onStartDestroyBlock} leaves the client's
     * block with Dungeons Only ON (and logs the skip) and predicts air with it OFF (the control: the hook acts), and the
     * setting survives a save + reload. Cheat jar only (Zero Ping is cheat-only).
     */
    private void dungeonsOnly(ClientGameTestContext ctx) {
        String name = "1104-breaker-dungeons-only";
        if (Scenario.skip(name)) {
            return;
        }
        ModUnderTest.require(MOD_ID);
        BlockPos on = new BlockPos(SECRET_CX, SURFACE_Y, SECRET_CZ + 2);
        BlockPos off = new BlockPos(SECRET_CX + 1, SURFACE_Y, SECRET_CZ + 2);
        Scenario.run(ctx, name,
                (server, scenario) -> TestMap.on(server)
                        .platform(SECRET_CX, SURFACE_Y - 1, SECRET_CZ, 6)
                        .catchFloor(120)
                        .survival()
                        .clearInventory()
                        .give("diamond_shovel", 1)
                        .fill(on.getX(), on.getY(), on.getZ(), off.getX(), off.getY(), off.getZ(), "snow_block")
                        .spawn(SECRET_CX + 0.5, SECRET_CZ + 0.5, -90f)
                        .build(),
                (server, scenario) -> {
                    if (!cheat(ctx)) {
                        scenario.log("legit jar: Zero Ping is cheat-only, nothing to check");
                        return;
                    }
                    for (BlockPos p : List.of(on, off)) {
                        if (isAir(ctx, p)) {
                            throw new AssertionError("block " + p + " was never built - nothing would be measured");
                        }
                    }
                    String feature = "com.killer560.hub.dungeonbreaker.DungeonBreakerFeature";
                    boolean wasEnabled = ctx.computeOnClient(mc -> ModUnderTest.getBoolean(ModUnderTest.config(BREAKER_CONFIG), "isEnabled"));
                    try {
                        boolean inDungeon = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(DUNGEON_STATE, "isInDungeon"));
                        if (inDungeon) {
                            throw new AssertionError("DungeonState reads in a dungeon on the test map - Dungeons Only cannot be tested");
                        }
                        stampBreaker(ctx);
                        // ON: the hook runs (charged breaker in hand, Zero Ping on) and leaves the block.
                        boolean airOn = ctx.computeOnClient(mc -> {
                            Object cfg = ModUnderTest.config(BREAKER_CONFIG);
                            ModUnderTest.set(cfg, "setEnabled", true);
                            ModUnderTest.set(cfg, "setFatigueOnly", false);
                            ModUnderTest.set(cfg, "setDungeonsOnly", true);
                            ModUnderTest.staticCall(feature, "onStartDestroyBlock", new Class<?>[]{BlockPos.class}, new Object[]{on});
                            return mc.level.getBlockState(on).isAir();
                        });
                        ctx.waitTicks(5);
                        java.nio.file.Path log = ctx.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("logs/latest.log"));
                        boolean logged;
                        try {
                            logged = java.nio.file.Files.readString(log).contains("zero ping skipped: pos=" + on.toShortString()
                                    + " tick=");
                        } catch (java.io.IOException e) {
                            throw new AssertionError("cannot read " + log + ": " + e);
                        }
                        scenario.log("Dungeons Only ON outside a dungeon: block air=" + airOn + ", skip line logged=" + logged);
                        if (airOn) {
                            throw new AssertionError("Dungeons Only is ON outside a dungeon but Zero Ping cleared " + on);
                        }
                        if (!logged) {
                            throw new AssertionError("no \"[Breaker] zero ping skipped ... Dungeons Only\" line for " + on);
                        }
                        // OFF (the control): the same call predicts air, so the ON result is the option's doing.
                        stampBreaker(ctx);
                        boolean airOff = ctx.computeOnClient(mc -> {
                            ModUnderTest.set(ModUnderTest.config(BREAKER_CONFIG), "setDungeonsOnly", false);
                            ModUnderTest.staticCall(feature, "onStartDestroyBlock", new Class<?>[]{BlockPos.class}, new Object[]{off});
                            return mc.level.getBlockState(off).isAir();
                        });
                        scenario.log("Dungeons Only off: block air=" + airOff);
                        if (!airOff) {
                            throw new AssertionError("with Dungeons Only off Zero Ping did not clear " + off
                                    + " - the hook never acted, so the ON result proves nothing");
                        }
                        // Survives a save + reload.
                        boolean reloaded = ctx.computeOnClient(mc -> {
                            ModUnderTest.set(ModUnderTest.config(BREAKER_CONFIG), "setDungeonsOnly", true);
                            ModUnderTest.call(ModUnderTest.config(BREAKER_CONFIG), "save", new Class<?>[0], new Object[0]);
                            ModUnderTest.set(ModUnderTest.config(BREAKER_CONFIG), "setDungeonsOnly", false);
                            ModUnderTest.staticCall(BREAKER_CONFIG, "load");
                            return ModUnderTest.getBoolean(ModUnderTest.config(BREAKER_CONFIG), "isDungeonsOnly");
                        });
                        scenario.log("Dungeons Only after save + load: " + reloaded);
                        if (!reloaded) {
                            throw new AssertionError("Dungeons Only ON did not survive a save + reload");
                        }
                    } finally {
                        ctx.runOnClient(mc -> {
                            Object cfg = ModUnderTest.config(BREAKER_CONFIG);
                            ModUnderTest.set(cfg, "setDungeonsOnly", false);
                            ModUnderTest.set(cfg, "setEnabled", wasEnabled);
                            ModUnderTest.call(cfg, "save", new Class<?>[0], new Object[0]);
                        });
                        cleanup(ctx);
                    }
                });
    }

    // ------------------------------------------------------------------------------------------------ secrets

    private void secrets(ClientGameTestContext ctx, String name, boolean optionOn) {
        if (Scenario.skip(name)) {
            return;
        }
        ModUnderTest.require(MOD_ID);
        int fy = SURFACE_Y - 1;
        BlockPos snow = new BlockPos(SECRET_CX, SURFACE_Y, SECRET_CZ + 2);
        BlockPos lever = new BlockPos(SECRET_CX + 1, SURFACE_Y, SECRET_CZ + 2);
        BlockPos chest = new BlockPos(SECRET_CX - 1, SURFACE_Y, SECRET_CZ + 2);
        Scenario.run(ctx, name,
                (server, scenario) -> TestMap.on(server)
                        .platform(SECRET_CX, fy, SECRET_CZ, 6)
                        .catchFloor(120)
                        .survival()
                        .clearInventory()
                        .give("diamond_shovel", 1)
                        .command("enchant @p minecraft:efficiency 5")
                        .command("effect give @p minecraft:haste 99999 4 true")
                        .command("effect clear @p minecraft:speed")
                        .fill(snow.getX(), snow.getY(), snow.getZ(), snow.getX(), snow.getY(), snow.getZ(), "snow_block")
                        .command("setblock " + lever.getX() + " " + lever.getY() + " " + lever.getZ()
                                + " minecraft:lever[face=floor,facing=north,powered=false]")
                        .command("setblock " + chest.getX() + " " + chest.getY() + " " + chest.getZ()
                                + " minecraft:chest")
                        .spawn(SECRET_CX + 0.5, SECRET_CZ + 0.5, -90f)
                        .build(),
                (server, scenario) -> {
                    scenario.assertDetectorWorks();
                    for (BlockPos p : List.of(snow, lever, chest)) {
                        if (isAir(ctx, p)) {
                            throw new AssertionError("block " + p + " was never built - nothing would be measured");
                        }
                    }
                    try {
                        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(BREAKER_CONFIG),
                                "setNoBreakSecrets", optionOn));
                        stampBreaker(ctx);
                        boolean cfgOn = ctx.computeOnClient(mc -> ModUnderTest.getBoolean(
                                ModUnderTest.config(BREAKER_CONFIG), "isNoBreakSecrets"));
                        boolean blocksLever = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                                "com.killer560.hub.dungeonbreaker.DungeonBreakerFeature", "shouldBlockStart",
                                new Class<?>[]{BlockPos.class}, new Object[]{lever}));
                        scenario.log("setting reads " + cfgOn + ", shouldBlockStart(lever) = " + blocksLever);
                        if (!cheat(ctx)) {
                            // The guard is cheat-only (shouldBlockStart is false on a legit jar whatever the option).
                            if (blocksLever) {
                                throw new AssertionError("a legit jar's shouldBlockStart(lever) is true");
                            }
                            scenario.log("legit jar: the breaker guard is cheat-only, it blocks nothing");
                            return;
                        }
                        if (cfgOn != optionOn || blocksLever != optionOn) {
                            throw new AssertionError("setting " + cfgOn + " / shouldBlockStart(lever) " + blocksLever
                                    + " do not match the option " + optionOn);
                        }
                        PacketWatch.start();
                        // The breaker click on each block, as the attack key does. (Breaker Aura never digs a lever or
                        // chest at all - its own blacklist - so the click path is where this option acts.)
                        click(ctx, snow);
                        ctx.waitTicks(10);
                        click(ctx, lever);
                        ctx.waitTicks(10);
                        click(ctx, chest);
                        ctx.waitTicks(10);
                        PacketWatch.stop();
                        List<BlockPos> dug = PacketWatch.digPositions();
                        scenario.log("option " + (optionOn ? "ON" : "off") + ": digs " + dug);
                        // The breaker must have acted in BOTH cases, on the plain block - otherwise "the secrets were
                        // left alone" only means the click path never ran.
                        if (!dug.contains(snow)) {
                            throw new AssertionError("the breaker click on the snow at " + snow + " sent no dig (digs "
                                    + dug + ") - the run proves nothing about the secrets");
                        }
                        if (!isAir(ctx, snow)) {
                            throw new AssertionError("the dig on the snow at " + snow + " went out but the snow still stands");
                        }
                        if (optionOn) {
                            if (dug.contains(lever) || dug.contains(chest) || isAir(ctx, lever) || isAir(ctx, chest)) {
                                throw new AssertionError("Don't break secrets is ON but a secret was dug or broken: digs "
                                        + dug);
                            }
                        } else if (!dug.contains(lever) || !dug.contains(chest)) {
                            throw new AssertionError("Don't break secrets is off and the lever/chest were not both dug"
                                    + " (digs " + dug + ") - the control does not show the option makes the difference");
                        }
                    } finally {
                        PacketWatch.stop();
                        cleanup(ctx);
                    }
                });
    }

    // ------------------------------------------------------------------------------------------------ fairy

    private void fairy(ClientGameTestContext ctx) {
        String name = "1018-breaker-fairy";
        if (Scenario.skip(name)) {
            return;
        }
        ModUnderTest.require(MOD_ID);
        int fy = SURFACE_Y - 1;
        BlockPos control = new BlockPos(FAIRY_CX, SURFACE_Y, FAIRY_CZ + 2);
        BlockPos inFairy = new BlockPos(FAIRY_CX + 1, SURFACE_Y, FAIRY_CZ + 2);
        Scenario.run(ctx, name,
                (server, scenario) -> TestMap.on(server)
                        .platform(FAIRY_CX, fy, FAIRY_CZ, 6)
                        .catchFloor(120)
                        .survival()
                        .clearInventory()
                        .give("diamond_shovel", 1)
                        .command("enchant @p minecraft:efficiency 5")
                        .command("effect give @p minecraft:haste 99999 4 true")
                        .command("effect clear @p minecraft:speed")
                        .fill(control.getX(), control.getY(), control.getZ(), control.getX(), control.getY(),
                                control.getZ(), "snow_block")
                        .fill(inFairy.getX(), inFairy.getY(), inFairy.getZ(), inFairy.getX(), inFairy.getY(),
                                inFairy.getZ(), "snow_block")
                        .spawn(FAIRY_CX + 0.5, FAIRY_CZ + 0.5, -90f)
                        .build(),
                (server, scenario) -> {
                    scenario.assertDetectorWorks();
                    for (BlockPos p : List.of(control, inFairy)) {
                        if (isAir(ctx, p)) {
                            throw new AssertionError("block " + p + " was never built - nothing would be measured");
                        }
                    }
                    try {
                        // Room TYPES come from the room database, which cannot be downloaded offline: copy his.
                        if (Scenario.ensureRoomDatabase(ctx) == 0) {
                            throw new AssertionError("no room database on this machine - a Fairy room cannot be published");
                        }
                        ctx.runOnClient(mc -> ModUnderTest.staticCall(DUNGEON_STATE, "setRoomSim",
                                new Class<?>[]{boolean.class}, new Object[]{true}));
                        stampBreaker(ctx);
                        // ---- control: in a dungeon clear, but no Fairy room published - the click must go out
                        String before = roomType(ctx, control);
                        if ("FAIRY".equals(before)) {
                            throw new AssertionError("the control block already reads as a Fairy room");
                        }
                        PacketWatch.start();
                        click(ctx, control);
                        ctx.waitTicks(10);
                        PacketWatch.stop();
                        List<BlockPos> dug = PacketWatch.digPositions();
                        scenario.log("control (room type " + before + "): digs " + dug);
                        if (!dug.contains(control)) {
                            throw new AssertionError("the breaker click outside a Fairy room sent no dig for "
                                    + control + " - the click path did not run, so the Fairy result means nothing");
                        }
                        // ---- the Fairy room: publish it over the block's cell, then click the second snow block
                        ctx.runOnClient(mc -> ModUnderTest.staticCall(LIVE_MAP, "publishSimFloor",
                                new Class<?>[]{int[].class, int[].class, String[].class, int[][].class},
                                new Object[]{fairyCells(), new int[0], new String[]{"Fairy"}, null}));
                        String after = roomType(ctx, inFairy);
                        scenario.log("room type over the Fairy block: " + after);
                        if (!"FAIRY".equals(after)) {
                            throw new AssertionError("the published room reads as " + after + ", not FAIRY - the "
                                    + "scenario would not be testing a Fairy room");
                        }
                        stampBreaker(ctx);
                        PacketWatch.start();
                        click(ctx, inFairy);
                        ctx.waitTicks(10);
                        PacketWatch.stop();
                        List<BlockPos> dug2 = PacketWatch.digPositions();
                        scenario.log("inside the Fairy room: digs " + dug2);
                        if (dug2.contains(inFairy) || isAir(ctx, inFairy)) {
                            throw new AssertionError("the breaker broke (or dug) " + inFairy + " inside a Fairy room");
                        }
                    } finally {
                        PacketWatch.stop();
                        cleanup(ctx);
                    }
                });
    }

    /** Room cell indices of the 11x11 grid: only cell 0 (the tile over FAIRY_CX / FAIRY_CZ) is room 0. */
    private static int[] fairyCells() {
        int[] cells = new int[121];
        java.util.Arrays.fill(cells, -1);
        cells[0] = 0;
        return cells;
    }

    /** The type of the room the player stands in (he spawns in the Fairy cell, beside both blocks). */
    private static String roomType(ClientGameTestContext ctx, BlockPos ignored) {
        return ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(LIVE_MAP, "currentRoomType"));
    }

    /**
     * A real left click on the block: the camera turns onto it (the mouse), waits two ticks so the server has HEARD that
     * look, then the attack key is pressed, so vanilla's startAttack digs the crosshair block and swings, as a player
     * does (the mod's HEAD hook sees that startDestroyBlock). Calling startDestroyBlock straight from a test task sent a
     * dig after the tick's movement packet with no swing and a look off the block: experimental GrimAC read it as
     * NoSwingBreak* and RotationBreak* post-flying (brksec-1105) - the test's click, not the mod.
     */
    private static void click(ClientGameTestContext ctx, BlockPos pos) {
        ctx.runOnClient(mc -> {
            var shape = mc.level.getBlockState(pos).getShape(mc.level, pos);
            Vec3 to = shape.isEmpty() ? Vec3.atCenterOf(pos) : shape.bounds().getCenter().add(pos.getX(), pos.getY(), pos.getZ());
            Vec3 eye = mc.player.getEyePosition();
            double dx = to.x - eye.x, dy = to.y - eye.y, dz = to.z - eye.z;
            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
            mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        });
        ctx.waitTicks(2);
        // The server's inventory sync wipes the client-side stamp within a tick or two, so stamp right before the click.
        // Aim first and swing after, as the attack key does: a bare startDestroyBlock drew GrimAC's experimental
        // NoSwingBreak and RotationBreak (a dig with no swing and a look off the block), a test artifact (ship4fu-1105).
        ctx.runOnClient(mc -> mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, new net.minecraft.world.phys.Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)));
        ctx.waitTicks(3);
        stampBreaker(ctx);
        String aimed = ctx.computeOnClient(mc -> mc.hitResult instanceof BlockHitResult b
                && b.getType() == HitResult.Type.BLOCK ? b.getBlockPos().toShortString() : String.valueOf(mc.hitResult));
        if (!aimed.equals(pos.toShortString())) {
            throw new AssertionError("the crosshair is on " + aimed + ", not " + pos.toShortString()
                    + " - the click would not be on the block under test");
        }
        ctx.getInput().pressKey(o -> o.keyAttack);
        System.out.println("[1018-breaker] click " + pos + " (attack key, crosshair on it)");
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static void cleanup(ClientGameTestContext ctx) {
        try {
            ctx.runOnClient(mc -> {
                ModUnderTest.turnOff(BREAKER_CONFIG, "setNoBreakSecrets");
                ModUnderTest.staticCall(DUNGEON_STATE, "setRoomSim", new Class<?>[]{boolean.class},
                        new Object[]{false});
                if (Boolean.TRUE.equals(ModUnderTest.staticCall(DUNGEON_STATE, "isSimOverrideActive"))) {
                    ModUnderTest.staticCall(DUNGEON_STATE, "toggleSimOverride");
                }
            });
        } catch (Throwable ignored) {
            // cleanup must not become the failure
        }
    }

    private static boolean isAir(ClientGameTestContext ctx, BlockPos pos) {
        return ctx.computeOnClient(mc -> mc.level.getBlockState(pos).isAir());
    }

    /** The held shovel read as a Hypixel Dungeon Breaker with charges, client-side (the server never sees it). */
    private static void stampBreaker(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            mc.player.getInventory().setSelectedSlot(0);
            ItemStack stack = mc.player.getInventory().getItem(0);
            if (stack.isEmpty()) {
                return;
            }
            CompoundTag tag = new CompoundTag();
            tag.putString("id", "DUNGEONBREAKER");
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Charges: 5000/5000"))));
        });
    }
}

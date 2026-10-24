package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import net.minecraft.world.phys.Vec3;

/**
 * Mod branch trigger-delays (killer560, 2026-10-11: "The trigger bots should all have their own unique specific timer
 * for the player to select their delay they prefer"). Each case aims at the trigger bot's target twice, at delay 0 and
 * at a longer delay, and reads on the server's clock ({@link TriggerTiming}) that the click moved by the delay.
 *
 * <ul>
 *   <li>797 Secret Triggerbot ({@code Delay}): a chest on the Hx platform, the floor in "dungeon" (room sim) and the
 *   server labeled as Hypixel; 0 vs 400 ms, read off the server's {@code use_block}.</li>
 *   <li>798 Auto Door Opener in Triggerbot mode ({@code Triggerbot Delay}, new): a wither door where Door Helpers
 *   scans for one (a coal door with its roof at y 73, at the -185 + 16n grid), armed by Hypixel's "RIGHT CLICK on a
 *   WITHER door" line; 0 vs 500 ms, {@code use_block} on the coal.</li>
 * </ul>
 * Legit jar: neither exists, so each case checks nothing is clicked.
 */
final class HxTriggerDelayCases {

    private HxTriggerDelayCases() {
    }

    static void register(Session s) {
        s.test("797-hx-trigger-delay-secret", HxTriggerDelayCases::secret);
        s.test("798-hx-trigger-delay-door", HxTriggerDelayCases::door);
    }

    /** Dungeon gates on: the room sim's "in a dungeon", a Hypixel address, the Skyblock gate off. */
    private static HxKit.Settings dungeon(Session c) {
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        String ipWas = c.onClient(mc -> mc.getCurrentServer().ip);
        return new HxKit.Settings(c)
                .custom(() -> Mod.staticCall("util.SkyblockGate", "setEnabled", false),
                        () -> Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas))
                .custom(() -> Mod.staticCall("secrets.DungeonState", "setRoomSim", true),
                        () -> Mod.staticCall("secrets.DungeonState", "setRoomSim", false))
                .custom(() -> net.minecraft.client.Minecraft.getInstance().getCurrentServer().ip = "mc.hypixel.net",
                        () -> net.minecraft.client.Minecraft.getInstance().getCurrentServer().ip = ipWas);
    }

    private static void closeScreen(Session c) {
        c.onClient(mc -> {
            if (mc.player.containerMenu != mc.player.inventoryMenu) {
                mc.player.closeContainer();
            }
            return null;
        });
    }

    // ---- 797 --------------------------------------------------------------------------------------------------------

    private static final String SEC_CFG = "secrettrigger.SecretTriggerbotConfig";

    private static void secret(Session c) {
        int[][] chests = {{-417, 151, -421}, {-417, 151, -418}};
        try (HxKit.Settings d = dungeon(c);
             HxKit.Settings on = new HxKit.Settings(c).with(SEC_CFG, "Enabled", true).with(SEC_CFG, "SwapEnabled", false)
                     .with(SEC_CFG, "DelayMs", 0)) {
            c.hx().call("dungeon.tp", "x", -419.5, "y", 151.0, "z", -419.5, "yaw", 0f, "pitch", -90f);
            for (int[] p : chests) {
                c.server().command("setblock " + p[0] + " " + p[1] + " " + p[2] + " chest");
            }
            c.waitUntil("the client to have both chests", mc -> mc.level.getBlockState(
                    new net.minecraft.core.BlockPos(chests[1][0], chests[1][1], chests[1][2])).is(
                    net.minecraft.world.level.block.Blocks.CHEST), 100);
            TriggerTiming.lookAway(c);
            c.ctx().waitTicks(10);
            long[] t = new long[2];
            int[] delays = {0, 400};
            for (int i = 0; i < 2; i++) {
                int delay = delays[i];
                int[] p = chests[i];
                c.onClient(mc -> {
                    Mod.call(Mod.cfg(SEC_CFG), "setDelayMs", delay);
                    return null;
                });
                t[i] = TriggerTiming.ticksToFire(c, new Vec3(p[0] + 0.5, p[1] + 0.4, p[2] + 0.5), "use_block",
                        e -> e.getAsJsonArray("pos").get(0).getAsInt() == p[0]
                                && e.getAsJsonArray("pos").get(2).getAsInt() == p[2], null, 80);
                c.ctx().waitTicks(3);
                closeScreen(c);
                TriggerTiming.lookAway(c);
                c.ctx().waitTicks(15); // past the 250 ms cooldown
            }
            if (Mod.isCheat()) {
                TriggerTiming.judge(c, "Secret Triggerbot", t[0], t[1], delays[1]);
            } else {
                c.check(t[0] < 0 && t[1] < 0, "legit jar: a secret was clicked by itself");
            }
        } finally {
            closeScreen(c);
            c.server().command("fill -417 151 -421 -417 151 -418 air");
            c.hx().call("dungeon.tp", "x", -419.5, "y", 151.0, "z", -419.5, "yaw", 0f, "pitch", 0f);
        }
    }

    // ---- 798 --------------------------------------------------------------------------------------------------------

    private static final String DOOR_CFG = "doorhelpers.DoorHelpersConfig";
    /** A door cell of Door Helpers' grid (gx 1, gz 0): x = -185 + 16, z = -185. */
    private static final int DX = -169;
    private static final int DZ = -185;

    private static void door(Session c) {
        try (HxKit.Settings d = dungeon(c);
             HxKit.Settings on = new HxKit.Settings(c).with(DOOR_CFG, "AutoDoorEnabled", true)
                     .with(DOOR_CFG, "AutoDoorSwing", false)) {
            boolean cheat = Mod.isCheat();
            Object modeWas = c.onClient(mc -> Mod.call(Mod.cfg(DOOR_CFG), "getAutoDoorMode"));
            on.custom(() -> Mod.call(Mod.cfg(DOOR_CFG), "setAutoDoorMode",
                            Mod.enumValue("doorhelpers.DoorHelpersConfig$OpenerMode", "TRIGGERBOT")),
                    () -> Mod.call(Mod.cfg(DOOR_CFG), "setAutoDoorMode", modeWas));
            on.with(DOOR_CFG, "AutoDoorTriggerDelayMs", 0);
            // Loaded first (forceload): the console's fill refuses an area nobody has loaded, and putting him there in
            // mid-air to load it drew a GrimAC Simulation line when the floor appeared under his fall.
            c.server().command("forceload add " + (DX - 4) + " " + (DZ - 6) + " " + (DX + 4) + " " + (DZ + 2));
            c.ctx().waitTicks(20);
            // The session's world has terrain here (stone up past y 76 on 2026-10-11): clear the air above, or the
            // door's column tops out above 73 and Door Helpers never calls it a door.
            c.server().command("fill " + (DX - 4) + " 69 " + (DZ - 6) + " " + (DX + 4) + " 140 " + (DZ + 2) + " air");
            c.server().command("fill " + (DX - 4) + " 68 " + (DZ - 6) + " " + (DX + 4) + " 68 " + (DZ + 2) + " stone");
            c.server().command("fill " + (DX - 1) + " 69 " + DZ + " " + (DX + 1) + " 72 " + DZ + " coal_block");
            c.server().command("fill " + (DX - 1) + " 73 " + DZ + " " + (DX + 1) + " 73 " + DZ + " stone");
            c.ctx().waitTicks(10);
            c.hx().call("dungeon.tp", "x", DX + 0.5, "y", 69.0, "z", DZ - 2.5, "yaw", 0f, "pitch", -90f);
            c.waitUntil("the player at the door", mc -> mc.player.position().distanceTo(new Vec3(DX + 0.5, 69.0, DZ - 2.5))
                    < 0.1 && mc.player.onGround(), 200);
            c.waitUntil("the client to have the door", mc -> mc.level.getBlockState(
                    new net.minecraft.core.BlockPos(DX, 69, DZ)).is(net.minecraft.world.level.block.Blocks.COAL_BLOCK)
                    && mc.level.getBlockState(new net.minecraft.core.BlockPos(DX, 73, DZ)).is(
                    net.minecraft.world.level.block.Blocks.STONE), 300);
            // The console's fills can land after the chunk reached the client, and a column Door Helpers scanned
            // before the coal arrived is cached as "not a door": rescan from scratch now that the client has it all.
            c.onClient(mc -> {
                Mod.staticCall("doorhelpers.DoorScanner", "reset");
                return null;
            });
            if (cheat) { // a legit jar never scans: Door Helpers' only feature is cheat-only
                try {
                    c.waitUntil("Door Helpers to find the locked wither door", mc -> !((java.util.List<?>) Mod.staticCall(
                            "doorhelpers.DoorScanner", "lockedDoors", mc)).isEmpty(), 100);
                } catch (AssertionError e) {
                    c.note("door scan diag: " + c.onClient(mc -> {
                        StringBuilder col = new StringBuilder();
                        for (int y = 66; y <= 76; y++) {
                            col.append(y).append('=').append(mc.level.getBlockState(new net.minecraft.core.BlockPos(DX, y, DZ))
                                    .getBlock().getName().getString()).append(' ');
                        }
                        Object[] cells = (Object[]) Mod.field("doorhelpers.DoorScanner", "cells");
                        return "column " + col + "; cell " + cells[1] + ", wasInClear "
                                + Mod.field("doorhelpers.DoorHelpersFeature", "wasInClear") + ", inDungeon "
                                + Mod.staticCall("secrets.DungeonState", "isInDungeon") + ", inBoss "
                                + Mod.staticCall("livemap.LiveMapFeature", "isInBoss") + ", dungeonServer "
                                + Mod.staticCall("cheatutils.CheatUtils", "isOnDungeonServer", mc) + ", enabled "
                                + Mod.call(Mod.cfg(DOOR_CFG), "isAutoDoorEnabled");
                    }));
                    throw e;
                }
            } else {
                c.ctx().waitTicks(30);
            }
            Vec3 face = new Vec3(DX + 0.5, 70.5, DZ - 0.01);
            long[] t = new long[2];
            int[] delays = {0, 500};
            for (int i = 0; i < 2; i++) {
                int delay = delays[i];
                c.onClient(mc -> {
                    Mod.call(Mod.cfg(DOOR_CFG), "setAutoDoorTriggerDelayMs", delay);
                    return null;
                });
                TriggerTiming.lookAway(c);
                c.ctx().waitTicks(5);
                t[i] = TriggerTiming.ticksToFire(c, face, "use_block",
                        e -> e.getAsJsonArray("pos").get(2).getAsInt() == DZ,
                        () -> c.hx().chat("RIGHT CLICK on a WITHER door to open it. This key can only be used to open 1 door!"),
                        60);
                if (t[i] < 0) {
                    c.note("door diag: " + c.onClient(mc -> "pending " + Mod.field("doorhelpers.AutoDoorOpenerFeature",
                            "pendingTicks") + ", aim " + Mod.field("doorhelpers.AutoDoorOpenerFeature", "triggerAimDoor")
                            + ", lastClick " + Mod.field("doorhelpers.AutoDoorOpenerFeature", "lastClick") + ", hit "
                            + (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bh ? bh.getBlockPos() : mc.hitResult)
                            + ", pos " + mc.player.position() + ", doors " + Mod.staticCall("doorhelpers.DoorScanner",
                            "lockedDoors", mc) + ", enabled " + Mod.call(Mod.cfg(DOOR_CFG), "isAutoDoorEnabled")
                            + ", mode " + Mod.call(Mod.cfg(DOOR_CFG), "getAutoDoorMode")));
                }
                c.ctx().waitTicks(30); // past the 500 ms retry delay
            }
            if (cheat) {
                TriggerTiming.judge(c, "Auto Door Opener (Triggerbot)", t[0], t[1], delays[1]);
            } else {
                c.check(t[0] < 0 && t[1] < 0, "legit jar: a door was clicked by itself");
            }
        } finally {
            c.server().command("fill " + (DX - 4) + " 68 " + (DZ - 6) + " " + (DX + 4) + " 73 " + (DZ + 2) + " air");
            c.server().command("forceload remove " + (DX - 4) + " " + (DZ - 6) + " " + (DX + 4) + " " + (DZ + 2));
            c.hx().call("dungeon.tp", "x", -419.5, "y", 151.0, "z", -419.5, "yaw", 0f, "pitch", 0f);
        }
    }
}

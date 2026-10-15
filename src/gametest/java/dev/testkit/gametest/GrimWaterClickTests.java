package dev.testkit.gametest;

import dev.testkit.harness.PacketWatch;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code 1015-grim-water-clickwarp} (mod branch water-late-1015): Auto Water's no-wait packet pattern against a real
 * GrimAC. Since that branch the auto (1) turns the BODY onto the next lever ahead of its time, so the click itself needs
 * no turn tick; (2) in the tick of a lever click, after the click, turns the body to the warp's look and puts sneak down,
 * so that tick's movement and input packets report both; (3) sends the warp's use the very next tick; (4) on the landing
 * lets go of sneak and turns onto the next lever, clicking the tick after. The two things a server-side check could see
 * in that are a block click followed in the same tick by a movement packet looking elsewhere (RotationPlace post-flying)
 * and a use a tick after a click. Played here with the mod's own primitives ({@code AutoPuzzleUtil.rotateCamera},
 * {@code gateWorldClick}, {@code interactBlock}, {@code useItemRotated}) in exactly that tick order, on three wall levers
 * at Water Board's height (two above the feet) - a vanilla server has no etherwarp, so the "landing" is the next tick.
 * <p>
 * Not vacuous: every lever must end powered, three uses must have gone out, and GrimAC must say nothing
 * ({@link Scenario#run}, after its positive control).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class GrimWaterClickTests implements FabricClientGameTest {

    private static final String NAME = "1015-grim-water-clickwarp";
    private static final String UTIL = "com.killer560.hub.autopuzzles.AutoPuzzleUtil";
    private static final String DUNGEON_LABEL = "localhost.p3sim.net:25565";
    private static final int SURFACE_Y = 151;
    private static final int BASE_X = -640;
    private static final int BASE_Z = -300;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        List<BlockPos> levers = List.of(new BlockPos(BASE_X - 2, SURFACE_Y + 2, BASE_Z + 3),
                new BlockPos(BASE_X, SURFACE_Y + 2, BASE_Z + 3), new BlockPos(BASE_X + 2, SURFACE_Y + 2, BASE_Z + 3));
        Scenario.labelServerAs(DUNGEON_LABEL);
        Scenario.run(ctx, NAME,
                (server, scenario) -> {
                    TestMap map = TestMap.on(server)
                            .platform(BASE_X, SURFACE_Y - 1, BASE_Z, 10)
                            .walls(5)
                            .catchFloor(120)
                            .survival()
                            .clearInventory()
                            .give("stick", 1)
                            .skyblockSidebar("F7")
                            .command("fill " + (BASE_X - 4) + " " + (SURFACE_Y + 1) + " " + (BASE_Z + 4) + " "
                                    + (BASE_X + 4) + " " + (SURFACE_Y + 3) + " " + (BASE_Z + 4) + " minecraft:stone");
                    for (BlockPos l : levers) {
                        map.command("setblock " + l.getX() + " " + l.getY() + " " + l.getZ()
                                + " minecraft:lever[face=wall,facing=north]");
                    }
                    map.spawn(BASE_X + 0.5, BASE_Z + 0.5, 0f).build();
                },
                (server, scenario) -> {
                    scenario.assertDetectorWorks();
                    ctx.waitTicks(30);
                    PacketWatch.start();
                    List<String> steps = new ArrayList<>();
                    // The warp's look: a floor block 7 blocks behind him, like Water's ~12 degree-down etherwarp aims.
                    Vec3 warpTarget = new Vec3(BASE_X + 0.5, SURFACE_Y, BASE_Z - 6.5);
                    aimAt(ctx, levers.get(0));
                    ctx.waitTick();
                    for (BlockPos lever : levers) {
                        // (2) the click on the tick it is due, then the turn and the sneak in the same tick
                        boolean clicked = false;
                        for (int i = 0; i < 20 && !clicked; i++) {
                            clicked = ctx.computeOnClient(mc -> {
                                if (!(Boolean) ModUnderTest.staticCall(UTIL, "gateWorldClick",
                                        new Class<?>[]{Minecraft.class, BlockPos.class}, new Object[]{mc, lever})) {
                                    return false;
                                }
                                ModUnderTest.staticCall(UTIL, "interactBlock",
                                        new Class<?>[]{Minecraft.class, BlockPos.class}, new Object[]{mc, lever});
                                float[] warp = direction(mc.player, warpTarget, 1.27);
                                ModUnderTest.staticCall(UTIL, "rotateCamera", new Class<?>[]{LocalPlayer.class,
                                        float.class, float.class}, new Object[]{mc.player, warp[0], warp[1]});
                                mc.options.keyShift.setDown(true);
                                return true;
                            });
                            if (!clicked) {
                                steps.add("lever " + lever.toShortString() + ": the gate held the click a tick");
                            }
                            ctx.waitTick();
                        }
                        if (!clicked) {
                            throw new AssertionError("the click on " + lever.toShortString() + " never went (20 ticks)");
                        }
                        // (3) the use, the very next tick
                        boolean used = ctx.computeOnClient(mc -> {
                            float[] warp = direction(mc.player, warpTarget, 1.27);
                            return (Boolean) ModUnderTest.staticCall(UTIL, "useItemRotated", new Class<?>[]{
                                    Minecraft.class, LocalPlayer.class, float.class, float.class},
                                    new Object[]{mc, mc.player, warp[0], warp[1]});
                        });
                        steps.add("lever " + lever.toShortString() + ": clicked, use the next tick " + (used ? "sent" : "HELD"));
                        if (!used) {
                            throw new AssertionError("useItemRotated held the use the tick after the click on "
                                    + lever.toShortString() + " - the look was not heard yet");
                        }
                        ctx.waitTick();
                        // (4) "landed": sneak up, body onto the next lever (or back to the first for the last one)
                        ctx.runOnClient(mc -> mc.options.keyShift.setDown(false));
                        int next = levers.indexOf(lever) + 1;
                        aimAt(ctx, levers.get(next < levers.size() ? next : 0));
                        ctx.waitTick();
                    }
                    ctx.waitTicks(10);
                    PacketWatch.stop();
                    int powered = 0;
                    for (BlockPos l : levers) {
                        if (ctx.computeOnClient(mc -> mc.level.getBlockState(l).getValue(LeverBlock.POWERED))) {
                            powered++;
                        }
                    }
                    for (String s : steps) {
                        scenario.log(s);
                    }
                    scenario.log(PacketWatch.summary());
                    scenario.log(String.format(Locale.ROOT, "RESULT %d of %d levers powered, %d item use(s), GrimAC %d line(s)",
                            powered, levers.size(), PacketWatch.itemUses(), scenario.flags().size()));
                    if (powered != levers.size()) {
                        throw new AssertionError(powered + " of " + levers.size() + " levers powered - the clicks did not land");
                    }
                    if (PacketWatch.itemUses() < levers.size()) {
                        throw new AssertionError("only " + PacketWatch.itemUses() + " item use(s) went out");
                    }
                });
    }

    /** Turns the body (standing eye) onto the lever's surface point, as Auto Water's aimBody does. */
    private static void aimAt(ClientGameTestContext ctx, BlockPos lever) {
        ctx.runOnClient(mc -> {
            Vec3 eye = new Vec3(mc.player.getX(), mc.player.getY() + 1.62, mc.player.getZ());
            BlockHitResult hit = (BlockHitResult) ModUnderTest.staticCall("com.killer560.hub.util.BlockHits", "surface",
                    new Class<?>[]{net.minecraft.world.level.BlockGetter.class, BlockPos.class, Vec3.class},
                    new Object[]{mc.level, lever, eye});
            Vec3 to = hit != null ? hit.getLocation() : Vec3.atCenterOf(lever);
            float[] d = direction(mc.player, to, 1.62);
            ModUnderTest.staticCall(UTIL, "rotateCamera", new Class<?>[]{LocalPlayer.class, float.class, float.class},
                    new Object[]{mc.player, d[0], d[1]});
        });
    }

    private static float[] direction(LocalPlayer p, Vec3 to, double eyeHeight) {
        double dx = to.x - p.getX();
        double dy = to.y - (p.getY() + eyeHeight);
        double dz = to.z - p.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new float[]{yaw, pitch};
    }
}

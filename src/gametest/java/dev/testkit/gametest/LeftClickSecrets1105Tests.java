package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.PacketTrace;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/**
 * 1105-leftsecret-* (mod leftsecret-1105, killer560 2026-11-05: "make an option for left click to also open secrets ...
 * left click a chest and it wouldn't mine it but would instead right click it", "It should work with left click on any
 * item though"). Left Click Opens Secrets, cheat build only: in a dungeon the player's own left click (the real attack
 * key, pressed through the gametest input) on a chest or lever is ONE right click on it. Each held item case
 * ({@code -hand}, {@code -sword}, {@code -breaker}: a Dungeon Breaker with Don't break secrets on) proves the server opened
 * the chest (open_screen) and flipped the lever, that exactly one use_item_on and no dig, hotbar or other action packet
 * went out, that a held button sends nothing more, and that neither block broke; GrimAC must stay silent (run once on a
 * default-config checkout and once with experimental-checks: true). {@code -off} is the control: the switch off, the same
 * breaker click is refused by the guard as before - no use, no dig, nothing opened. A legit jar has none of it: the
 * class is there but the switch does nothing, which {@code -hand} checks on legit.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class LeftClickSecrets1105Tests implements FabricClientGameTest {

    private static final String CFG = "leftclicksecrets.LeftClickSecretsConfig";
    private static final String FEATURE = "leftclicksecrets.LeftClickSecrets";
    private static final String BREAKER = "dungeonbreaker.DungeonBreakerConfig";
    private static final String DUNGEON_LABEL = "localhost.p3sim.net:25565";
    private static final int SURFACE_Y = 151;
    private static final int Z = -3500;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        AssertionError first = null;
        int bx = -6100;
        for (String held : List.of("hand", "sword", "breaker", "off")) {
            try {
                run(ctx, held, bx);
            } catch (AssertionError e) {
                first = first == null ? e : first;
            }
            bx -= 100;
        }
        if (first != null) {
            throw first;
        }
    }

    private static void check(boolean ok, String why) {
        if (!ok) {
            throw new AssertionError(why);
        }
    }

    private static void run(ClientGameTestContext ctx, String held, int bx) {
        String name = "1105-leftsecret-" + held;
        if (Scenario.skip(name)) {
            return;
        }
        BlockPos lever = new BlockPos(bx, SURFACE_Y + 1, Z);
        BlockPos chest = new BlockPos(bx, SURFACE_Y, Z + 2);
        boolean on = !held.equals("off");
        String item = held.equals("sword") ? "diamond_sword" : held.equals("hand") ? null : "diamond_shovel";
        Scenario.labelServerAs(DUNGEON_LABEL);
        Scenario.run(ctx, name, (server, scenario) -> {
            TestMap map = TestMap.on(server)
                    .platform(bx, SURFACE_Y - 1, Z, 10)
                    .catchFloor(120)
                    .survival()
                    .clearInventory()
                    .skyblockSidebar("F7")
                    .command("fill " + (bx + 1) + " " + SURFACE_Y + " " + (Z - 2) + " " + (bx + 1) + " " + (SURFACE_Y + 3)
                            + " " + (Z + 4) + " minecraft:stone")
                    .command("setblock " + bx + " " + (SURFACE_Y + 1) + " " + Z + " minecraft:lever[face=wall,facing=west]")
                    .command("setblock " + bx + " " + SURFACE_Y + " " + (Z + 2) + " minecraft:chest[facing=west]")
                    .spawn(bx - 2.5, Z + 1.0, -90f);
            if (item != null) {
                map.give(item, 1);
            }
            return map.build();
        }, (server, scenario) -> {
            scenario.assertDetectorWorks();
            ctx.waitTicks(20);
            boolean cheat = ctx.computeOnClient(mc -> Mod.isCheat());
            try {
                ctx.runOnClient(mc -> {
                    Mod.set("secrettrigger.SecretTriggerbotConfig", "setEnabled", false);
                    Mod.set("cheatutils.CheatUtilsConfig", "setSecretAuraEnabled", false);
                    Mod.set(CFG, "setEnabled", on);
                    Mod.set(BREAKER, "setNoBreakSecrets", "breaker".equals(held) || "off".equals(held));
                    if (item != null && !"sword".equals(held)) {
                        Mod.set(BREAKER, "setEnabled", true);
                    }
                });
                boolean inDungeon = ctx.computeOnClient(mc -> (Boolean) Mod.staticCall("secrets.DungeonState", "isInDungeon"));
                check(inDungeon, "DungeonState does not read a dungeon on the F7 sidebar - the feature's gate stays shut");
                long usesBefore = ctx.computeOnClient(mc -> ((Number) Mod.staticCall(FEATURE, "uses")).longValue());

                // ---- the chest: one press
                aim(ctx, chest, new Vec3(bx + 0.5, SURFACE_Y + 0.5, Z + 2.5));
                PacketTrace.start();
                stampBreaker(ctx, held);
                ctx.getInput().pressKey(o -> o.keyAttack);
                ctx.waitTicks(20);
                PacketTrace.stop();
                int chestUses = PacketTrace.sent("use_item_on");
                int chestDigs = PacketTrace.sent("player_action");
                int chestSwaps = PacketTrace.sent("set_carried_item");
                int opened = PacketTrace.received("open_screen");
                boolean screen = ctx.computeOnClient(mc -> McCompat.screen(mc)
                        instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>);
                ctx.runOnClient(mc -> {
                    if (mc.player.containerMenu != mc.player.inventoryMenu) {
                        mc.player.closeContainer();
                    }
                });
                ctx.waitTicks(5);
                boolean chestThere = ctx.computeOnClient(mc -> mc.level.getBlockState(chest).is(Blocks.CHEST));
                scenario.log(String.format(Locale.ROOT, "%s chest: %d use_item_on, %d player_action, %d set_carried_item, "
                        + "%d open_screen, container screen %s, chest still there %s", held, chestUses, chestDigs,
                        chestSwaps, opened, screen, chestThere));

                // ---- the lever: one press, then held for 15 ticks
                aim(ctx, lever, new Vec3(bx + 0.8, SURFACE_Y + 1.5, Z + 0.5));
                boolean before = ctx.computeOnClient(mc -> mc.level.getBlockState(lever).getValue(LeverBlock.POWERED));
                PacketTrace.start();
                stampBreaker(ctx, held);
                ctx.getInput().pressKey(o -> o.keyAttack);
                ctx.waitTicks(20);
                boolean afterPress = ctx.computeOnClient(mc -> mc.level.getBlockState(lever).getValue(LeverBlock.POWERED));
                stampBreaker(ctx, held);
                ctx.getInput().holdKey(o -> o.keyAttack);
                ctx.waitTicks(15);
                ctx.getInput().releaseKey(o -> o.keyAttack);
                ctx.waitTicks(15);
                PacketTrace.stop();
                int leverUses = PacketTrace.sent("use_item_on");
                int leverDigs = PacketTrace.sent("player_action");
                int leverSwaps = PacketTrace.sent("set_carried_item");
                boolean afterHold = ctx.computeOnClient(mc -> mc.level.getBlockState(lever).getValue(LeverBlock.POWERED));
                boolean leverThere = ctx.computeOnClient(mc -> mc.level.getBlockState(lever).is(Blocks.LEVER));
                long uses = ctx.computeOnClient(mc -> ((Number) Mod.staticCall(FEATURE, "uses")).longValue()) - usesBefore;
                List<String> flags = scenario.flags();
                scenario.log(String.format(Locale.ROOT, "%s lever: powered %s -> %s after the press -> %s after press+hold; "
                        + "%d use_item_on, %d player_action, %d set_carried_item, lever still there %s; feature uses %d; "
                        + "GrimAC %d line(s) %s", held, before, afterPress, afterHold, leverUses, leverDigs, leverSwaps,
                        leverThere, uses, flags.size(), flags));

                check(chestThere && leverThere, "a secret was broken");
                check(chestSwaps == 0 && leverSwaps == 0, "a hotbar change went out");
                if (!on || !cheat) {
                    // The control (and every legit jar): nothing opened, nothing flipped, no use from the left click.
                    check(uses == 0, "the feature acted with its switch off or on a legit jar");
                    check(chestUses == 0 && leverUses == 0, "a use went out with the feature off");
                    check(opened == 0 && before == afterHold, "a secret opened with the feature off");
                    if ("off".equals(held) && cheat) {
                        check(chestDigs == 0 && leverDigs == 0, "the breaker guard let a dig through on a secret");
                    }
                    return;
                }
                check(chestUses == 1 && opened >= 1, "the left click did not open the chest with ONE use (uses " + chestUses
                        + ", open_screen " + opened + ")");
                check(chestDigs == 0, "a dig/action packet went out on the chest");
                check(afterPress != before, "the server did not flip the lever on the left click");
                // The hold starts with a press of its own (one more use, the lever back off), then sends nothing more.
                check(afterHold == before && leverUses == 2, "the held button clicked the lever again (or its press did "
                        + "not click it): uses " + leverUses + ", powered " + before + " -> " + afterPress + " -> "
                        + afterHold);
                check(leverDigs == 0, "a dig/action packet went out on the lever");
                check(uses == 3, "the feature counted " + uses + " uses, expected 3");
                check(flags.isEmpty(), "GrimAC flagged: " + flags);
            } finally {
                PacketTrace.stop();
                ctx.runOnClient(mc -> {
                    Mod.set(CFG, "setEnabled", false);
                    Mod.set(BREAKER, "setNoBreakSecrets", false);
                });
            }
        });
    }

    /** Turns the camera onto {@code target} (the harness, not the mod) and waits for the server to hear the look. */
    private static void aim(ClientGameTestContext ctx, BlockPos block, Vec3 target) {
        ctx.runOnClient(mc -> {
            Vec3 d = target.subtract(mc.player.getEyePosition());
            float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
            mc.player.setYRot(mc.player.getYRot() + Mth.wrapDegrees(yaw - mc.player.getYRot()));
            mc.player.setXRot(pitch);
        });
        ctx.waitTicks(5);
        boolean onIt = ctx.computeOnClient(mc -> mc.hitResult instanceof BlockHitResult b
                && b.getType() == HitResult.Type.BLOCK && b.getBlockPos().equals(block));
        check(onIt, "the crosshair is not on " + block + " - nothing would be tested");
    }

    /** The held shovel read as a charged Dungeon Breaker client-side (the server never sees the stamp). */
    private static void stampBreaker(ClientGameTestContext ctx, String held) {
        if (!"breaker".equals(held) && !"off".equals(held)) {
            return;
        }
        ctx.runOnClient(mc -> {
            ItemStack stack = mc.player.getInventory().getItem(mc.player.getInventory().getSelectedSlot());
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

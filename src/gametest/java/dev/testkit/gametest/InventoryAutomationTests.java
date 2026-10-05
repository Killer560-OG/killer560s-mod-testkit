package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import dev.testkit.harness.PacketWatch;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Auto Sell, and with it the third class of packet this mod automates.
 *
 * <p>Breaker Aura sends a dig and Secret Triggerbot sends a block-use; both were flagged for arriving after
 * the tick's movement packet, and both are now fixed. Menu automation is a genuinely different case and worth
 * its own measurement rather than an assumption: a server validates a window click against its own copy of the
 * open menu, so it is not obvious in advance that the ordering check applies to it at all. Either answer is
 * useful - it either confirms the fix on a third packet type, or it says the check is specific to world
 * interaction, which is worth knowing before anyone leans on a clean result here.
 *
 * <p>Auto Sell was picked because it needs nothing from Hypixel. It has no dungeon gate at all: it wants its
 * own toggle on, a non-empty sell list, and a container screen whose title matches a regex it exposes as a
 * setting. Pointing that regex at a plain chest is configuration, not a workaround, and it means no scoreboard,
 * no server label and no item NBT are involved.
 */
public class InventoryAutomationTests implements FabricClientGameTest {

    private static final String MOD_ID = "killer560smod";
    private static final String CONFIG = "com.killer560.hub.autosell.AutoSellConfig";
    private static final String IDENTITY = "com.killer560.hub.autoroutes.ItemIdentity";

    private static final int SURFACE_Y = 151;
    private static final int BASE_X = -300;
    private static final int BASE_Z = -300;

    /** Distinct items, because Auto Sell sells each identity once per session and then never again. */
    private static final String[] GOODS = {
            "diamond", "emerald", "gold_ingot", "iron_ingot",
            "lapis_lazuli", "redstone", "coal", "copper_ingot",
    };

    @Override
    public void runTest(ClientGameTestContext ctx) {
        autoSell(ctx);
    }

    private void autoSell(ClientGameTestContext ctx) {
        String name = "61-auto-sell";
        if (Scenario.skip(name)) {
            return;
        }
        ModUnderTest.require(MOD_ID);
        Scenario.runExpectingFlags(ctx, name,
                (server, scenario) -> {
                    TestMap map = TestMap.on(server)
                            .platform(BASE_X, SURFACE_Y - 1, BASE_Z, 12)
                            .walls(4)
                            .catchFloor(120)
                            .survival()
                            .clearInventory();
                    for (String item : GOODS) {
                        map.give(item, 16);
                    }
                    // The chest sits at eye height, so the crosshair is already on it and the scenario can open
                    // it without moving or aiming.
                    map.command("setblock " + BASE_X + " " + (SURFACE_Y + 1) + " " + BASE_Z + " minecraft:chest")
                            .spawn(BASE_X - 2.5, BASE_Z + 0.5, -90f)
                            .build();
                },
                (server, scenario) -> {
                    scenario.assertDetectorWorks();

                    // Open the chest the way a player would: one use against the block under the crosshair.
                    ctx.runOnClient(mc -> {
                        if (!(mc.hitResult instanceof BlockHitResult hit)) {
                            throw new AssertionError("the crosshair is not on the chest, so there is no menu "
                                    + "to sell into and this scenario would measure nothing");
                        }
                        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                    });
                    ctx.waitTicks(20);
                    String screen = ctx.computeOnClient(mc -> McCompat.screen(mc) == null
                            ? "none" : McCompat.screen(mc).getClass().getSimpleName()
                            + " titled \"" + McCompat.screen(mc).getTitle().getString() + "\"");
                    scenario.log("open screen: " + screen);
                    if (screen.equals("none")) {
                        throw new AssertionError("the chest did not open, so Auto Sell has no menu to act in");
                    }

                    List<String> sellable = configure(ctx, scenario);

                    // Auto Sell does not arm itself: it waits for /autosell or its menu button. Its own
                    // start() reports why it refused, so the scenario asks rather than assuming - the first
                    // run set every gate correctly, never started, and looked exactly like a clean result.
                    ctx.runOnClient(mc -> {
                        Object refusal = ModUnderTest.staticCall(
                                "com.killer560.hub.autosell.AutoSellFeature", "start");
                        if (refusal != null) {
                            throw new AssertionError("Auto Sell refused to start: " + refusal);
                        }
                    });

                    PacketWatch.start();
                    ctx.waitTicks(220);
                    PacketWatch.stop();

                    int moved = ctx.computeOnClient(mc -> {
                        int n = 0;
                        for (int slot = 0; slot < 27; slot++) {
                            if (!mc.player.containerMenu.getSlot(slot).getItem().isEmpty()) {
                                n++;
                            }
                        }
                        return n;
                    });

                    scenario.log(PacketWatch.summary());
                    long post = scenario.flags().stream()
                            .filter(line -> line.contains("failed Post")).count();
                    long other = scenario.flags().size() - post;
                    scenario.log(String.format(Locale.ROOT,
                            "RESULT Auto Sell: %d container click(s), %d of them after that tick's movement "
                                    + "packet, %d stack(s) landed in the chest -> anticheat said %d Post, "
                                    + "%d other",
                            PacketWatch.totalContainerClicks(), PacketWatch.clicksAfterMove(), moved, post,
                            other));
                    if (post > 0) {
                        scenario.log("FINDING: the packet-ordering check applies to container clicks too.");
                    } else if (PacketWatch.clicksAfterMove() > 0) {
                        scenario.log("NOTE: clicks did go out after the movement packet and the anticheat did "
                                + "NOT object, so its ordering check is specific to world interaction. Worth "
                                + "remembering before quoting a clean menu-automation result.");
                    }
                    for (String flag : scenario.flags().stream().distinct().limit(3).toList()) {
                        scenario.log("    " + flag);
                    }

                    if (PacketWatch.totalContainerClicks() == 0) {
                        throw new AssertionError("Auto Sell sent no container clicks in 220 ticks despite "
                                + sellable.size() + " sellable identities being on its list, so its clean "
                                + "anticheat result means nothing. Check the client log for its own reason.");
                    }
                });
    }

    /**
     * Switch Auto Sell on, and build its sell list out of what the player is actually holding.
     *
     * <p>The identities are read back from the mod's own {@code ItemIdentity.of(stack)} rather than guessed
     * from the item names. A guessed identity that does not match produces an empty sell list, no clicks, and a
     * spotless anticheat log - which is the failure this whole harness exists to avoid printing.
     */
    private List<String> configure(ClientGameTestContext ctx, Scenario scenario) {
        List<String> identities = ctx.computeOnClient(mc -> {
            Object cfg = ModUnderTest.config(CONFIG);
            ModUnderTest.set(cfg, "setEnabled", true);
            // Its own setting, pointed at a plain chest instead of a Hypixel "Sell" menu.
            try {
                Object rejected = cfg.getClass().getMethod("setScreenTitlePattern", String.class)
                        .invoke(cfg, "(?i).*chest.*");
                if (rejected != null) {
                    throw new AssertionError("the screen-title pattern was rejected: " + rejected);
                }
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("could not set the screen title pattern", e);
            }
            List<String> found = new ArrayList<>();
            try {
                var of = Class.forName(IDENTITY).getMethod("of", net.minecraft.world.item.ItemStack.class);
                var add = cfg.getClass().getMethod("addSellIdentity", String.class);
                for (int slot = 0; slot < 9; slot++) {
                    Object id = of.invoke(null, mc.player.getInventory().getItem(slot));
                    if (id != null) {
                        add.invoke(cfg, id.toString());
                        found.add(id.toString());
                    }
                }
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("could not read item identities from the mod", e);
            }
            if (!ModUnderTest.getBoolean(cfg, "isEnabled")) {
                throw new AssertionError("isEnabled() is still false - needs the cheat jar (a legit build "
                        + "folds this to a constant false and deletes the branch)");
            }
            return found;
        });
        scenario.log("Auto Sell on, selling " + identities.size() + " identities: " + identities);
        if (identities.isEmpty()) {
            throw new AssertionError("no item identities could be read, so the sell list is empty and "
                    + "nothing would happen");
        }
        return identities;
    }
}

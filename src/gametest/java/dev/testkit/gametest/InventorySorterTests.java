package dev.testkit.gametest;

import dev.testkit.harness.PacketWatch;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Auto Inventory Sorter, the fourth class of packet this mod automates and the second menu-clicker measured
 * here (after Auto Sell in {@link InventoryAutomationTests}). Its engine is
 * {@code com.killer560.hub.invsort.InventorySorterExecutor}: it requires the player's own real vanilla
 * Inventory screen to be open - opening one itself if it is not - is started by the public static
 * {@code start(InventoryLayout)} (never automatically), and swaps slots with real
 * {@code ContainerInput.PICKUP} clicks through {@code handleContainerInput}, same click category Auto Sell
 * already sends.
 *
 * <p>Unlike Auto Sell, this executor already ticks on {@code START_CLIENT_TICK} (per the mod's own
 * {@code CLAUDE.md}, which lists Breaker Aura and Secret Triggerbot as the two features fixed onto START and
 * flags roughly twenty more still on END) - so this scenario is also a second, independent read on whether
 * the packet-ordering check applies to container clicks the way {@code InventoryAutomationTests} found it
 * does not, this time against a module that was never on the wrong hook to begin with.
 *
 * <p>The layout under test asks the executor to swap two slots it can already see are wrong: read the real
 * identity of two occupied inventory slots with the mod's own {@code ItemIdentity.of(ItemStack)} (never
 * guessed - a wrong identity string produces an empty-looking "nothing to fix" run that reads as clean for
 * the worst reason) and build a two-entry {@code InventoryLayout} that wants them the other way round. That
 * is the smallest input that forces at least one real swap - the executor only clicks when a target slot is
 * provably wrong.
 */
public class InventorySorterTests implements FabricClientGameTest {

    private static final String MOD_ID = "killer560smod";
    private static final String EXECUTOR = "com.killer560.hub.invsort.InventorySorterExecutor";
    private static final String LAYOUT = "com.killer560.hub.invsort.InventoryLayout";
    private static final String IDENTITY = "com.killer560.hub.autoroutes.ItemIdentity";

    private static final int SURFACE_Y = 151;
    private static final int BASE_X = -300;
    private static final int BASE_Z = -300;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        autoInventorySorter(ctx);
    }

    private void autoInventorySorter(ClientGameTestContext ctx) {
        String name = "66-auto-inventory-sorter";
        if (Scenario.skip(name)) {
            return;
        }
        ModUnderTest.require(MOD_ID);
        Scenario.runExpectingFlags(ctx, name,
                (server, scenario) -> TestMap.on(server)
                        .platform(BASE_X, SURFACE_Y - 1, BASE_Z, 12)
                        .walls(4)
                        .catchFloor(120)
                        .survival()
                        .clearInventory()
                        // Two distinct, easily identified stacks so the layout has something real to swap.
                        .give("diamond", 5)
                        .give("emerald", 5)
                        .spawn(BASE_X + 0.5, BASE_Z + 0.5, 0f)
                        .build(),
                (server, scenario) -> {
                    scenario.assertDetectorWorks();

                    int[] slots = findTwoOccupiedSlots(ctx, scenario);
                    int slotA = slots[0];
                    int slotB = slots[1];

                    boolean started = arm(ctx, scenario, slotA, slotB);
                    if (!started) {
                        throw new AssertionError("InventorySorterExecutor.start() returned false - refused to "
                                + "start, so nothing about it would be measured");
                    }

                    // Give it time to open its own Inventory screen (WAIT_SCREEN, up to 2000ms) and then run
                    // the swap cycle (120-280ms cosmetic delay per click, plus the gate's own one-per-tick
                    // floor) to completion.
                    PacketWatch.start();
                    ctx.waitTicks(160);
                    PacketWatch.stop();

                    String screen = ctx.computeOnClient(mc -> mc.screen == null
                            ? "none" : mc.screen.getClass().getSimpleName());
                    scenario.log("screen at end of measurement: " + screen);

                    String afterA = identityAt(ctx, slotA);
                    String afterB = identityAt(ctx, slotB);
                    scenario.log(String.format(Locale.ROOT,
                            "slot %d now holds %s, slot %d now holds %s (swap intended)",
                            slotA, afterA, slotB, afterB));

                    scenario.log(PacketWatch.summary());
                    long post = scenario.flags().stream()
                            .filter(line -> line.contains("failed Post")).count();
                    long other = scenario.flags().size() - post;
                    scenario.log(String.format(Locale.ROOT,
                            "RESULT Auto Inventory Sorter: %d container click(s), max %d on one tick, %d of "
                                    + "them after that tick's movement packet -> anticheat said %d Post, %d "
                                    + "other",
                            PacketWatch.totalContainerClicks(), PacketWatch.maxClicksOnOneTick(),
                            PacketWatch.clicksAfterMove(), post, other));
                    if (post > 0) {
                        scenario.log("FINDING: the packet-ordering check applies to this module's container "
                                + "clicks too.");
                    } else if (PacketWatch.clicksAfterMove() > 0) {
                        scenario.log("NOTE: clicks did go out after the movement packet and the anticheat did "
                                + "NOT object - consistent with Auto Sell's finding that this check is "
                                + "specific to world interaction, now on a module that never used the END "
                                + "hook to begin with.");
                    }
                    for (String flag : scenario.flags().stream().distinct().limit(3).toList()) {
                        scenario.log("    " + flag);
                    }

                    if (PacketWatch.totalContainerClicks() == 0) {
                        throw new AssertionError("Auto Inventory Sorter sent no container clicks despite a "
                                + "layout that asked it to swap two known-wrong slots, so its clean anticheat "
                                + "result means nothing. Check the client log for InvSort's own stop reason.");
                    }
                });
    }

    /** Two distinct occupied inventory slots (0-35), read via the mod's own {@code ItemIdentity.of}. */
    private int[] findTwoOccupiedSlots(ClientGameTestContext ctx, Scenario scenario) {
        int[] found = ctx.computeOnClient(mc -> {
            int[] result = {-1, -1};
            try {
                var of = Class.forName(IDENTITY).getMethod("of", ItemStack.class);
                for (int slot = 0; slot < 36 && result[1] == -1; slot++) {
                    Object id = of.invoke(null, mc.player.getInventory().getItem(slot));
                    if (id != null) {
                        if (result[0] == -1) {
                            result[0] = slot;
                        } else if (slot != result[0]) {
                            result[1] = slot;
                        }
                    }
                }
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("could not read item identities from the mod", e);
            }
            return result;
        });
        if (found[0] == -1 || found[1] == -1) {
            throw new AssertionError("could not find two distinct occupied inventory slots to build a swap "
                    + "layout from - check that give() actually populated the hotbar");
        }
        scenario.log("swap candidates: slot " + found[0] + " and slot " + found[1]);
        return found;
    }

    /**
     * Build a two-entry {@code InventoryLayout} wanting {@code slotA}'s and {@code slotB}'s current
     * identities swapped, then call the executor's real {@code start(InventoryLayout)}.
     */
    private boolean arm(ClientGameTestContext ctx, Scenario scenario, int slotA, int slotB) {
        return ctx.computeOnClient(mc -> {
            try {
                var of = Class.forName(IDENTITY).getMethod("of", ItemStack.class);
                String idA = (String) of.invoke(null, mc.player.getInventory().getItem(slotA));
                String idB = (String) of.invoke(null, mc.player.getInventory().getItem(slotB));
                if (idA == null || idB == null || idA.equalsIgnoreCase(idB)) {
                    throw new AssertionError("slot " + slotA + " (" + idA + ") and slot " + slotB + " (" + idB
                            + ") are not two distinct identities - a swap layout built from them would ask "
                            + "for nothing");
                }
                scenario.log("layout wants slot " + slotA + " -> " + idB + ", slot " + slotB + " -> " + idA);

                Map<Integer, String> entries = new LinkedHashMap<>();
                entries.put(slotA, idB);
                entries.put(slotB, idA);

                Class<?> layoutClass = Class.forName(LAYOUT);
                Object layout = layoutClass.getConstructor(String.class, Map.class)
                        .newInstance("testkit-swap", entries);

                Class<?> executorClass = Class.forName(EXECUTOR);
                var start = executorClass.getMethod("start", layoutClass);
                return (Boolean) start.invoke(null, layout);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("could not call " + EXECUTOR + ".start(InventoryLayout) - it is the "
                        + "only public entry point into this feature, so a rename here means this scenario is "
                        + "dead", e);
            }
        });
    }

    private String identityAt(ClientGameTestContext ctx, int slot) {
        return ctx.computeOnClient(mc -> {
            try {
                var of = Class.forName(IDENTITY).getMethod("of", ItemStack.class);
                Object id = of.invoke(null, mc.player.getInventory().getItem(slot));
                return String.valueOf(id);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("could not read item identity at slot " + slot, e);
            }
        });
    }
}

package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Locale;

/**
 * Custom Items (mod {@code armourdye/ArmourDye}) with any entry saved, and a plain player head in the world.
 *
 * <p>Crash 2026-10-18 (Mod Only Test): a StackOverflowError on the render thread while extracting an armour stand
 * that wore a player head. {@code PlayerHeadItem.getName} reads {@code minecraft:profile} through
 * {@code DataComponentHolder#get}, which Custom Items overrides; the override looked the stack up by identity, an
 * id-less stack's identity is its display name, and the display name is {@code PlayerHeadItem.getName} again.
 *
 * <p>Checks, in order, so a broken jar fails on an assertion before anything is drawn: the feature is ACTIVE (an
 * entry saved, master on, gate open - otherwise the mixin returns at once and nothing is tested); the name path
 * still works (a named player head finds its entry by name and gets its colour, so the guard did not simply switch
 * the feature off); {@code getHoverName} on a bare player head returns; then a real armour stand wearing a player
 * head is summoned in front of the player and drawn for 40 ticks, and the client must still answer.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class ArmourHeadStandTest implements FabricClientGameTest {

    private static final String NAME = "1018-armour-headstand";
    private static final String CFG = "com.killer560.hub.armourdye.ArmourDyeConfig";
    private static final String DYE = "com.killer560.hub.armourdye.ArmourDye";
    private static final String KEY = "TESTKIT_HEADSTAND_1018";
    private static final int COLOUR = 0x00AB12CD;

    private static final int X = 310;
    private static final int Y = 120;
    private static final int Z = -1018;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        Scenario.run(ctx, NAME,
                (server, scenario) -> TestMap.on(server)
                        .platform(X, Y - 1, Z, 8)
                        .catchFloor(90)
                        .survival()
                        .clearInventory()
                        .spawn(X + 0.5, Z + 0.5, 0f)
                        .build(),
                (server, scenario) -> {
                    boolean gateWas = ctx.computeOnClient(mc ->
                            (Boolean) ModUnderTest.staticCall("com.killer560.hub.util.SkyblockGate", "isEnabled"));
                    try {
                        ctx.runOnClient(mc -> configure(true));
                        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.util.SkyblockGate",
                                "setEnabled", new Class<?>[]{boolean.class}, new Object[]{false}));
                        boolean active = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(DYE, "active"));
                        scenario.log("Custom Items active with one entry: " + active);
                        if (!active) {
                            throw new AssertionError("Custom Items is not active, so the mixin returns before "
                                    + "anything is tested");
                        }

                        // Premise: the display-name path the fix guards still finds an entry and acts on it.
                        String named = ctx.computeOnClient(mc -> {
                            ItemStack s = new ItemStack(Items.PLAYER_HEAD);
                            s.set(DataComponents.CUSTOM_NAME, Component.literal("Testkit Headstand 1018"));
                            try {
                                Object entry = ModUnderTest.staticCall(DYE, "entryFor",
                                        new Class<?>[]{ItemStack.class}, new Object[]{s});
                                int c = (Integer) ModUnderTest.staticCall(DYE, "dyeColor",
                                        new Class<?>[]{ItemStack.class, int.class}, new Object[]{s, 0});
                                return (entry != null) + " " + Integer.toHexString(c & 0xFFFFFF);
                            } catch (Throwable t) {
                                return "threw " + rootName(t);
                            }
                        });
                        scenario.log("named player head: entry found + color -> " + named);
                        if (!named.equals("true " + Integer.toHexString(COLOUR))) {
                            throw new AssertionError("a player head named after the entry did not get it ("
                                    + named + "), so the name path no longer works");
                        }

                        // The crash itself, without drawing anything: a bare player head's name.
                        String bare = ctx.computeOnClient(mc -> {
                            try {
                                return "ok: " + new ItemStack(Items.PLAYER_HEAD).getHoverName().getString();
                            } catch (StackOverflowError e) {
                                return "StackOverflowError";
                            } catch (Throwable t) {
                                return "threw " + rootName(t);
                            }
                        });
                        scenario.log("bare player head getHoverName -> " + bare);
                        if (!bare.startsWith("ok: ")) {
                            throw new AssertionError("getHoverName on a bare player head with Custom Items on: "
                                    + bare);
                        }

                        // The crash as he hit it: an armour stand wearing a player head, drawn in front of him.
                        server.command(String.format(Locale.ROOT,
                                "summon minecraft:armor_stand %d %d %d {NoGravity:1b,Invulnerable:1b,"
                                        + "Tags:[\"tk1018\"]}", X, Y, Z + 3));
                        server.command("item replace entity @e[type=minecraft:armor_stand,tag=tk1018,limit=1] "
                                + "armor.head with minecraft:player_head");
                        String head = "";
                        for (int i = 0; i < 100 && !head.endsWith("player_head"); i++) {
                            ctx.waitTicks(2);
                            head = ctx.computeOnClient(mc -> {
                                var stands = mc.level.getEntitiesOfClass(ArmorStand.class,
                                        mc.player.getBoundingBox().inflate(16));
                                return stands.isEmpty() ? "no stand"
                                        : stands.get(0).getItemBySlot(EquipmentSlot.HEAD).getItem().toString();
                            });
                        }
                        scenario.log("client armor stand wears: " + head);
                        if (!head.endsWith("player_head")) {
                            throw new AssertionError("the armor stand never reached the client wearing a player "
                                    + "head (" + head + "), so nothing was drawn");
                        }
                        ctx.waitTicks(40);
                        boolean hookSeen = ctx.computeOnClient(mc ->
                                (Boolean) ModUnderTest.staticCall(DYE, "renderHookSeen"));
                        boolean failed = ctx.computeOnClient(mc ->
                                (Boolean) ModUnderTest.staticCall(DYE, "hasFailed"));
                        scenario.log(String.format(Locale.ROOT,
                                "drew the stand for 40 ticks: client alive, render hook seen %s, latched off %s",
                                hookSeen, failed));
                        if (!hookSeen) {
                            throw new AssertionError("Custom Items' render hook never ran, so the draw tested nothing");
                        }
                        if (failed) {
                            throw new AssertionError("Custom Items latched itself off after render failures");
                        }
                        scenario.log("PASS");
                    } finally {
                        server.command("kill @e[type=minecraft:armor_stand,tag=tk1018]");
                        try {
                            ctx.runOnClient(mc -> configure(false));
                        } catch (Throwable ignored) {
                            // cleanup must not become the failure
                        }
                        try {
                            ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.util.SkyblockGate",
                                    "setEnabled", new Class<?>[]{boolean.class}, new Object[]{gateWas}));
                        } catch (Throwable ignored) {
                            // cleanup must not become the failure
                        }
                    }
                });
    }

    /** Adds (or removes) one colour entry keyed on the name identity and turns the master switch on (or off). */
    private static void configure(boolean on) {
        Object cfg = ModUnderTest.config(CFG);
        ModUnderTest.call(cfg, "remove", new Class<?>[]{String.class}, new Object[]{KEY});
        if (on) {
            Object entry = ModUnderTest.call(cfg, "getOrCreate", new Class<?>[]{String.class, String.class},
                    new Object[]{KEY, "Testkit Headstand 1018"});
            try {
                entry.getClass().getField("colorEnabled").setBoolean(entry, true);
                entry.getClass().getField("color").setInt(entry, 0xFF000000 | COLOUR);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("could not set the Custom Items entry's color", e);
            }
        }
        ModUnderTest.set(cfg, "setEnabled", on);
        ModUnderTest.staticCall(DYE, "resetFailures");
        ModUnderTest.call(cfg, "save", new Class<?>[0], new Object[0]);
    }

    private static String rootName(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) {
            r = r.getCause();
        }
        return r.getClass().getSimpleName();
    }
}

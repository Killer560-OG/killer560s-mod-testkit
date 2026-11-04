package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;

/**
 * 1104-ui-helditem-stuck (sndheld-1104). killer560 2026-11-04, blood camp while spamming the sword: "sometimes item
 * animations get stuck out like this" - the first-person sword far out at the lower right. This drives his Held Item
 * settings (swing speed 0.3, swing range 0.5, no hand sway, a small offset item) through rapid attack-key presses (most
 * of them at air), right clicks and hotbar swaps, samples the pose inputs every tick (swing progress, the swing, the
 * hand height the renderer lowers the item by, the swap scale it rises with) and asserts the item is held up while the
 * clicks go on and back at rest once they stop.
 */
final class HeldItemStuckCases {

    private HeldItemStuckCases() {
    }

    static final String CLS = "helditem.HeldItemConfig";

    /** {swingAnim, swinging(0/1), mainHandHeight, swapScale, attackStrength}. */
    private static float[] sample(Minecraft mc) {
        try {
            // 26.3: LocalPlayer.firstPersonHandsAndItems(); 26.2: GameRenderer.itemInHandRenderer. Reflection, so one source
            // compiles for both.
            Object hands;
            try {
                hands = mc.player.getClass().getMethod("firstPersonHandsAndItems").invoke(mc.player);
            } catch (NoSuchMethodException e) {
                Field f = mc.gameRenderer.getClass().getDeclaredField("itemInHandRenderer");
                f.setAccessible(true);
                hands = f.get(mc.gameRenderer);
            }
            Field h = hands.getClass().getDeclaredField("mainHandHeight");
            h.setAccessible(true);
            float swap;
            try {
                swap = (Float) mc.player.getClass().getMethod("getItemSwapScale", float.class).invoke(mc.player, 1f);
            } catch (NoSuchMethodException e) {
                swap = Float.NaN;
            }
            float swing = (Float) Mod.staticCall("compat.McRender", "swingProgress", mc.player, 1f);
            return new float[]{swing, swinging(mc) ? 1f : 0f, h.getFloat(hands), swap,
                    mc.player.getAttackStrengthScale(1f)};
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean swinging(Minecraft mc) throws ReflectiveOperationException {
        try {
            return (Boolean) net.minecraft.world.entity.LivingEntity.class.getMethod("isSwinging").invoke(mc.player);
        } catch (NoSuchMethodException e) {
            return net.minecraft.world.entity.LivingEntity.class.getField("swinging").getBoolean(mc.player);
        }
    }

    static void stuck(UiCase c) throws Exception {
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, CLS);
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        try {
            c.onClient(mc -> {
                Object cfg = Mod.cfg(CLS);
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setNoSwing", false);
                Mod.call(cfg, "setNoEquip", false);
                Mod.call(cfg, "setNoHandSway", true);
                Mod.call(cfg, "setSwingSpeed", 0.3f);
                Mod.call(cfg, "setSwingRange", 0.5f);
                Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                mc.player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
                mc.player.getInventory().setItem(1, new ItemStack(Items.STICK));
                mc.player.getInventory().setItem(2, new ItemStack(Items.IRON_SWORD));
                mc.player.getInventory().setSelectedSlot(0);
                // Look up at the sky: every attack is a miss, as a sword swung between blood mobs mostly is.
                mc.player.setXRot(-90f);
                return null;
            });
            c.ticks(30);
            float[] rest0 = c.onClient(HeldItemStuckCases::sample);
            c.note(String.format("at rest before: swing %.2f height %.2f swap %.2f strength %.2f", rest0[0], rest0[2],
                    rest0[3], rest0[4]));
            // Spam: an attack press every tick (as an autoclicker / a held key at 20 CPS), a slot swap every 15 ticks.
            float minHeight = 1f;
            float sumHeight = 0f;
            int n = 0;
            int lowTicks = 0;
            for (int t = 0; t < 80; t++) {
                c.ctx().getInput().pressKey(o -> o.keyAttack);
                if (t % 15 == 14) {
                    int slot = (t / 15) % 2 == 0 ? 2 : 0;
                    c.onClient(mc -> {
                        mc.player.getInventory().setSelectedSlot(slot);
                        return null;
                    });
                }
                c.ticks(1);
                float[] s = c.onClient(HeldItemStuckCases::sample);
                minHeight = Math.min(minHeight, s[2]);
                sumHeight += s[2];
                n++;
                // A real item change drops the hand to 0 and raises it 0.4 a tick (as on 1.8): ticks 0-2 after a swap
                // are allowed low. Every other low tick is the attack cooldown holding it down.
                if (s[2] < 0.5f && !(t % 15 >= 14 || (t >= 15 && t % 15 <= 1))) {
                    lowTicks++;
                }
                if (t % 10 == 0) {
                    c.note(String.format("t%d swing %.2f swinging %d height %.2f swap %.2f strength %.2f", t, s[0],
                            (int) s[1], s[2], s[3], s[4]));
                }
                if (t == 40) {
                    c.ctx().takeScreenshot("1104-ui-helditem-stuck-spam");
                }
            }
            float avg = sumHeight / n;
            c.note(String.format("spam: hand height min %.2f avg %.2f, %d of %d ticks under 0.5 outside a swap", minHeight, avg, lowTicks, n));
            c.ticks(40);
            float[] rest = c.onClient(HeldItemStuckCases::sample);
            c.ctx().takeScreenshot("1104-ui-helditem-stuck-after");
            c.note(String.format("40 ticks after: swing %.2f swinging %d height %.2f", rest[0], (int) rest[1], rest[2]));
            c.check(rest[1] == 0f && rest[0] == 0f, "the swing is still running 40 ticks after the last click (" + rest[0] + ")");
            c.check(rest[2] > 0.99f, "the item is still lowered 40 ticks after the last click (height " + rest[2] + ")");
            // Swinging at air must not hold the item down: Hypixel (1.8) has no attack cooldown, so 26.x's cooldown dip
            // after every missed swing is what left the sword sitting low while he spammed it.
            c.check(lowTicks == 0 && avg > 0.8f, "the item sat lowered in " + lowTicks + " of " + n + " spam ticks outside a swap (min height "
                    + minHeight + ", avg " + avg + ")");
        } finally {
            c.onClient(mc -> {
                Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas);
                mc.player.setXRot(0f);
                for (int i = 0; i < 3; i++) {
                    mc.player.getInventory().setItem(i, ItemStack.EMPTY);
                }
                mc.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                return null;
            });
            DungeonB2Cases.restoreConfig(c, saved, CLS);
        }
    }
}

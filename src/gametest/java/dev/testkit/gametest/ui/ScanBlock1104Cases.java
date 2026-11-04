package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.lang.reflect.Constructor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * 1104-ui-block-colorpicker-drag (mod scanblock-1104): the color picker's onChange callbacks, whose 60 callers write a
 * config file each, ran on every drag frame. A drag of 60 steps inside one 150 ms window must now call it at most a few
 * times, the release must deliver the final value, and the drag must really have moved the color (the control).
 */
final class ScanBlock1104Cases {

    private ScanBlock1104Cases() {
    }

    static void colorPickerDrag(UiCase c) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        int[] last = {0};
        IntConsumer cb = argb -> {
            calls.incrementAndGet();
            last[0] = argb;
        };
        Object screen = c.onClient(mc -> {
            try {
                Constructor<?> k = Mod.cls("gui.ColorPickerScreen")
                        .getConstructor(Screen.class, String.class, int.class, int.class, IntConsumer.class);
                Screen s = (Screen) k.newInstance(null, "t", 0xFFFF0000, 0xFFFF0000, cb);
                McCompat.setScreen(mc, s);
                return s;
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        c.ticks(3);
        String err = c.onClient(mc -> {
            Screen s = (Screen) screen;
            int x = (Integer) R.get(s, "svX");
            int y = (Integer) R.get(s, "svY");
            int size = (Integer) R.get(s, "svSize");
            var info = new MouseButtonInfo(dev.testkit.compat.McKeys.mouse(0), 0);
            calls.set(0);
            s.mouseClicked(new MouseButtonEvent(x + 10, y + 10, info), false);
            final int steps = 60;
            for (int i = 1; i <= steps; i++) {
                double mx = x + 10 + (size - 20) * (i / (double) steps);
                s.mouseDragged(new MouseButtonEvent(mx, y + 10 + i, info), 1, 1);
            }
            int during = calls.get();
            int beforeRelease = last[0];
            s.mouseReleased(new MouseButtonEvent(x + size - 10, y + 70, info));
            int total = calls.get();
            Object cur = R.get(s, "argb");
            if (during > 3) {
                return "onChange ran " + during + " times during a " + steps + "-step drag (want <= 3)";
            }
            if (total < 2 || last[0] != (Integer) cur || last[0] == beforeRelease && during == total) {
                return "release did not deliver the final color: total " + total + ", last " + Integer.toHexString(last[0])
                        + ", current " + Integer.toHexString((Integer) cur);
            }
            if (last[0] == 0xFFFF0000) {
                return "the drag never moved the color";
            }
            System.out.println("[1104-ui-block-colorpicker-drag] onChange calls during drag " + during + ", total " + total);
            return null;
        });
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        if (err != null) {
            throw new AssertionError(err);
        }
    }
}

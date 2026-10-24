package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared driving for the Design a Map scenarios (75-sim-designer-*, mod sim-designer-1015): open the real screen, press
 * its real controls with the mouse, read its state by reflection, and read back the text runs one frame draws.
 */
final class DesignerKit {

    static final String EDITOR = "com.killer560.hub.roomsim.SimMapEditorScreen";
    static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";

    /** The labels the floor button cycles through, in its order. */
    static final String[] FLOOR_LABELS = {"Entrance", "Floor 1", "Floor 2", "Floor 3", "Floor 4", "Floor 5", "Floor 6",
            "Floor 7"};

    /**
     * Real Hypixel floor sizes {across, down} in room slots, per {@link #FLOOR_LABELS}: the live map's table
     * ({@code MapPainter.floorRooms}, from {@code DungeonMapScanner.calibrate}'s start corners and FloorSizeLog samples).
     * Written out here as the scenario's own expectation, not read from the mod under test.
     */
    static final int[][] FLOOR_SIZES = {{4, 4}, {4, 5}, {5, 5}, {5, 5}, {6, 5}, {6, 6}, {6, 6}, {6, 6}};

    private DesignerKit() {
    }

    /** One drawn text run: its string and its advance box. */
    record Run(String text, int x, int y, int w, int h) {
    }

    static void prepare(ClientGameTestContext ctx) {
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"));
    }

    static void openEditor(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.execute(() -> {
            try {
                Class<?> cls = Class.forName(EDITOR);
                McCompat.setScreen(mc, (Screen) cls.getConstructor(Screen.class).newInstance((Object) null));
            } catch (Exception e) {
                throw new RuntimeException("could not open the map designer", e);
            }
        }));
        ctx.waitTicks(10);
    }

    static boolean isEditor(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> McCompat.screen(mc) != null
                && McCompat.screen(mc).getClass().getName().equals(EDITOR));
    }

    static void closeScreen(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.execute(() -> McCompat.setScreen(mc, null)));
        ctx.waitTicks(5);
    }

    static String label(AbstractWidget w) {
        return w.getMessage().getString().replaceAll("§.", "").trim();
    }

    /** Every active control's label on the open screen. */
    static List<String> labels(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            List<String> out = new ArrayList<>();
            Screen s = McCompat.screen(mc);
            if (s != null) {
                for (var ch : s.children()) {
                    if (ch instanceof AbstractWidget w && w.active && w.visible) {
                        out.add(label(w));
                    }
                }
            }
            return out;
        });
    }

    /** The floor button's label: whichever control reads as one of {@link #FLOOR_LABELS}, or null. */
    static String floorLabel(ClientGameTestContext ctx) {
        for (String l : labels(ctx)) {
            for (String f : FLOOR_LABELS) {
                if (l.equals(f)) {
                    return l;
                }
            }
        }
        return null;
    }

    /** Clicks the visible control labeled exactly {@code text} with the real mouse. False when there is none. */
    static boolean click(ClientGameTestContext ctx, String text) {
        double[] at = ctx.computeOnClient(mc -> {
            Screen s = McCompat.screen(mc);
            if (s == null) {
                return null;
            }
            for (var ch : s.children()) {
                if (ch instanceof AbstractWidget w && w.active && w.visible && label(w).equals(text)) {
                    return toWindow(mc, s, w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0);
                }
            }
            return null;
        });
        if (at == null) {
            return false;
        }
        press(ctx, at);
        return true;
    }

    /** Clicks a screen point (GUI units of the open screen) with the real mouse; button 0 left, 1 right. */
    static void clickAt(ClientGameTestContext ctx, double sx, double sy, int button) {
        double[] at = ctx.computeOnClient(mc -> toWindow(mc, McCompat.screen(mc), sx, sy));
        ctx.getInput().setCursorPos(at[0], at[1]);
        ctx.waitTicks(2);
        ctx.getInput().pressMouse(button);
        ctx.waitTicks(4);
    }

    private static void press(ClientGameTestContext ctx, double[] at) {
        ctx.getInput().setCursorPos(at[0], at[1]);
        ctx.waitTicks(2);
        ctx.getInput().pressMouse(0);
        ctx.waitTicks(4);
    }

    /** Screen GUI units to window pixels, through the mod's Auto Scale the screen is drawn at. */
    private static double[] toWindow(Minecraft mc, Screen s, double sx, double sy) {
        float f = 1f;
        try {
            f = ((Number) ModUnderTest.staticCall("com.killer560.hub.hud.AutoScale", "appliedFactor",
                    new Class<?>[]{Screen.class}, new Object[]{s})).floatValue();
        } catch (RuntimeException | AssertionError ignored) {
            // no Auto Scale in this jar: plain GUI units
        }
        Window win = mc.getWindow();
        return new double[]{sx * f * win.getScreenWidth() / (double) win.getGuiScaledWidth(),
                sy * f * win.getScreenHeight() / (double) win.getGuiScaledHeight()};
    }

    /**
     * The top of the designer's room list and grid: its listTop() since the sim-menu redo (2026-10-15) put the designer
     * inside the Dungeon Sim menu's frame, panelY + 66 on a jar from before it.
     */
    static int listTop(Object designer) {
        try {
            var m = designer.getClass().getDeclaredMethod("listTop");
            m.setAccessible(true);
            return (Integer) m.invoke(designer);
        } catch (ReflectiveOperationException e) {
            return (Integer) reflect(designer, "panelY") + 66;
        }
    }

    static Object field(ClientGameTestContext ctx, String name) {
        return ctx.computeOnClient(mc -> reflect(McCompat.screen(mc), name));
    }

    static Object reflect(Object target, String field) {
        try {
            var f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no field " + field + " on " + target.getClass().getSimpleName(), e);
        }
    }

    /** The generated plan on the grid ({@code SimFloorGen.Planned}), or null. */
    static Object generated(ClientGameTestContext ctx) {
        return field(ctx, "generated");
    }

    static String code(ClientGameTestContext ctx, Object plan) {
        return ctx.computeOnClient(mc -> (String) ModUnderTest.call(plan, "code", new Class<?>[]{}, new Object[]{}));
    }

    /** The plan's 11x11 cell-to-room array. */
    static int[] cellRoom(ClientGameTestContext ctx, Object plan) {
        return ctx.computeOnClient(mc -> {
            Object decoded = ModUnderTest.call(plan, "decoded", new Class<?>[]{}, new Object[]{});
            return (int[]) ModUnderTest.call(decoded, "cellRoom", new Class<?>[]{}, new Object[]{});
        });
    }

    /**
     * What is wrong with a plan as a floor of {@code across x down} room slots, or null: every room tile inside that
     * rectangle at the grid's north-west corner, and every one of its slots holding a room - the compact shape a real
     * floor has, not rooms scattered over the 6x6.
     */
    static String rectangleProblem(int[] cellRoom, int across, int down) {
        int outside = 0;
        int filled = 0;
        int minX = 99;
        int minZ = 99;
        int maxX = -1;
        int maxZ = -1;
        for (int z = 0; z < 6; z++) {
            for (int x = 0; x < 6; x++) {
                int id = cellRoom[(z * 2) * 11 + x * 2];
                if (id < 0) {
                    continue;
                }
                minX = Math.min(minX, x);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxZ = Math.max(maxZ, z);
                if (x >= across || z >= down) {
                    outside++;
                } else {
                    filled++;
                }
            }
        }
        String box = maxX < 0 ? "no rooms" : "rooms span x " + minX + ".." + maxX + ", z " + minZ + ".." + maxZ;
        if (outside > 0) {
            return outside + " room slot(s) outside the " + across + "x" + down + " floor (" + box + ")";
        }
        if (filled != across * down) {
            return "only " + filled + " of the " + across * down + " slots of the " + across + "x" + down
                    + " floor hold a room (" + box + ")";
        }
        return null;
    }

    /** The text runs one full frame of the open screen draws, mouse parked off the panel. */
    static List<Run> texts(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            Screen screen = McCompat.screen(mc);
            List<Run> out = new ArrayList<>();
            if (screen == null) {
                return out;
            }
            GuiRenderState state = new GuiRenderState();
            GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, 0, 0);
            screen.extractRenderStateWithTooltipAndSubtitles(g, 0, 0, 0.5f);
            state.forEachText(t -> {
                ScreenRectangle b = TextRuns.box(t);
                out.add(new Run(TextRuns.string(t), b.left(), b.top(), b.width(), b.height()));
            });
            return out;
        });
    }
}

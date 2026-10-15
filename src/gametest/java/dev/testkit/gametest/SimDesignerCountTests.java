package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 75-sim-designer-count: Design a Map draws no "N of M rooms" (mod sim-designer-1015).
 *
 * <p>killer560 (2026-10-15): "Remove the 134 of 134 rooms in the top right as well."
 *
 * <p>Reads back every text run one real frame of the designer draws - with the room database loaded, so the room list
 * is full - and fails on any "N of M rooms" or bare "N/M" count. The positive control is the title "DESIGN A MAP" and at
 * least one listed room name in the same runs, so an extract that drew nothing cannot pass.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimDesignerCountTests implements FabricClientGameTest {

    private static final String NAME = "75-sim-designer-count";
    private static final Pattern COUNT = Pattern.compile("^\\d+ of \\d+ rooms.*|^\\d+/\\d+$");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        DesignerKit.prepare(ctx);
        List<String> failures = new ArrayList<>();
        try {
            DesignerKit.openEditor(ctx);
            if (!DesignerKit.isEditor(ctx)) {
                throw new AssertionError("the map designer did not open");
            }
            ctx.waitTicks(10);
            @SuppressWarnings("unchecked")
            List<String> listed = new ArrayList<>((List<String>) DesignerKit.field(ctx, "listed"));
            List<DesignerKit.Run> runs = DesignerKit.texts(ctx);
            List<String> strings = new ArrayList<>();
            for (DesignerKit.Run r : runs) {
                strings.add(r.text());
            }
            boolean title = strings.contains("DESIGN A MAP");
            boolean aRoom = !listed.isEmpty() && strings.contains(listed.get(0));
            System.out.println("[" + NAME + "] one frame drew " + runs.size() + " text run(s); title " + title
                    + ", first listed room '" + (listed.isEmpty() ? "-" : listed.get(0)) + "' drawn " + aRoom
                    + ", " + listed.size() + " rooms listed");
            if (!title || !aRoom) {
                failures.add("the frame read back has no title or no room name - the extract measured nothing");
            }
            for (DesignerKit.Run r : runs) {
                if (COUNT.matcher(r.text().trim()).matches()) {
                    failures.add("drawn: '" + r.text() + "' at " + r.x() + "," + r.y());
                }
            }
            // The designer from the Dungeon Sim menu's Create a New Map (the sim-pages menu, 2026-10-15): the real nav
            // button opens it, it draws, and its Back returns to the menu.
            DesignerKit.closeScreen(ctx);
            ctx.runOnClient(mc -> mc.execute(() -> {
                try {
                    Class<?> cls = Class.forName("com.killer560.hub.roomsim.SimMenuScreen");
                    McCompat.setScreen(mc, (Screen) cls.getConstructor(Screen.class).newInstance((Object) null));
                } catch (Exception e) {
                    throw new RuntimeException("could not open the Dungeon Sim menu", e);
                }
            }));
            ctx.waitTicks(10);
            boolean clicked = DesignerKit.click(ctx, "Create a New Map");
            boolean opened = DesignerKit.isEditor(ctx);
            boolean draws = opened && DesignerKit.texts(ctx).stream().anyMatch(r -> r.text().equals("DESIGN A MAP"));
            boolean back = opened && DesignerKit.click(ctx, "Back");
            String after = ctx.computeOnClient(mc -> McCompat.screen(mc) == null ? "none"
                    : McCompat.screen(mc).getClass().getSimpleName());
            System.out.println("[" + NAME + "] from the sim menu: Create a New Map clicked " + clicked + ", designer open "
                    + opened + ", draws its title " + draws + ", Back -> " + after);
            if (!clicked || !opened || !draws || !"SimMenuScreen".equals(after)) {
                failures.add("Create a New Map on the Dungeon Sim menu did not open a working designer that returns to "
                        + "the menu (clicked " + clicked + ", open " + opened + ", draws " + draws + ", Back -> " + after
                        + ")");
            }
        } finally {
            DesignerKit.closeScreen(ctx);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s):\n  " + String.join("\n  ", failures));
        }
        System.out.println("[" + NAME + "] PASS - no room count drawn");
    }
}

package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import dev.testkit.compat.McCompat;
import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * <b>1118-sim-help-shots</b> (mod simhelp-polish-1118): screenshots for the Dungeon Sim Help polish, not a behavior check. At
 * 1280x720 GUI 2 inside a real sim: every help page (into {@code before/} on a jar without the help pictures, {@code after/}
 * with them), the screens the help pages talk about (designer, Previous Runs, Sim Settings, items, boss start, Multiplayer,
 * the chat command list); first an in-world F7 floor with mobs and the F7 boss (HUD hidden, and the floor once with it), into {@code src/}. Folder:
 * killer560s-mod-logs/simhelp-polish-1118/. It fails only if a shot could not be taken.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimHelpShotsTests implements FabricClientGameTest {

    static final String NAME = "1118-sim-help-shots";
    private static final String SCREEN = "com.killer560.hub.roomsim.SimHelpScreen";
    private static final Path DIR = Path.of("C:/Users/Hunter/killer560s-mod-logs/simhelp-polish-1118");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        List<String> problems = new ArrayList<>();
        List<String> facts = new ArrayList<>();
        Report.caseStarted(NAME);
        try {
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
            run(ctx, problems, facts);
        } catch (Throwable t) {
            problems.add("threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            SimMobFloorTests.leave(ctx);
        }
        Report.caseFinished(NAME, problems.isEmpty() ? "PASS" : "FAIL", "n/a (singleplayer)",
                problems.isEmpty() ? String.join("; ", facts) : String.join("; ", problems), List.of(), List.of());
        SuiteVerdict.finished(NAME);
        if (!problems.isEmpty()) {
            throw new AssertionError(NAME + ": " + String.join(" | ", problems));
        }
    }

    private void run(ClientGameTestContext ctx, List<String> problems, List<String> facts) throws Exception {
        ctx.waitFor(mc -> McCompat.screen(mc) != null, 1200);
        setWindow(ctx, 1280, 720, 2);
        // First, from the title screen (the path 1101-sim-mobfloor takes): an F7 floor with mobs and the F7 boss, in world.
        List<String> worldNotes = new ArrayList<>();
        // FloorGen.generate from the title screen opened no world (mc.level null for 2400 ticks, nothing logged), so plan
        // the floor here and hand buildMobFloor its map code (its SimWorld.open path). The plan needs the room database.
        Scenario.ensureRoomDatabase(ctx);
        String code = ctx.computeOnClient(mc -> {
            Object floor = ModUnderTest.enumValue("com.killer560.hub.roomsim.SimFloorGen$Floor", "F7");
            Object planned = ModUnderTest.staticCall("com.killer560.hub.roomsim.SimFloorGen", "plan",
                    new Class<?>[]{floor.getClass(), int.class, int.class}, new Object[]{floor, 2, 6});
            return planned == null ? "" : String.valueOf(ModUnderTest.call(planned, "code", new Class<?>[0], new Object[0]));
        });
        facts.add("planned F7 code " + (code.isEmpty() ? "none" : code.length() + " chars"));
        System.setProperty("testkit.mobfloorCode", code);
        if (!code.isEmpty() && SimMobFloorTests.buildMobFloor(ctx, worldNotes, facts)) {
            ctx.runOnClient(mc -> McCompat.setScreen(mc, null));
            ctx.waitTicks(80);
            shot(ctx, "src", "floor-gui");
            worldShot(ctx, "floor");
            ctx.runOnClient(mc -> mc.player.connection.sendCommand("simboss p1"));
            ctx.waitTicks(300);
            ctx.runOnClient(mc -> {
                McCompat.setScreen(mc, null);
                McCompat.chat(mc).clearMessages(false);
            });
            ctx.waitTicks(20);
            shot(ctx, "src", "boss");
        } else {
            facts.add("no floor world: " + worldNotes);
            ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimWorld", "open",
                    new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, "gametest"})));
            ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        }
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimState", "canAct",
                new Class<?>[]{Minecraft.class}, new Object[]{mc}), 600);
        ctx.waitTicks(60);

        // The help pages.
        ctx.runOnClient(mc -> mc.player.connection.sendCommand("help"));
        ctx.waitFor(mc -> McCompat.screen(mc) != null && McCompat.screen(mc).getClass().getName().equals(SCREEN), 200);
        boolean after = ctx.computeOnClient(mc -> {
            for (var m : McCompat.screen(mc).getClass().getMethods()) {
                if (m.getName().equals("picturesShown")) {
                    return true;
                }
            }
            return false;
        });
        String set = after ? "after" : "before";
        int pages = ctx.computeOnClient(mc -> (Integer) ModUnderTest.call(McCompat.screen(mc), "pageCount", new Class<?>[0], new Object[0]));
        for (int p = 0; p < pages; p++) {
            final int page = p;
            ctx.runOnClient(mc -> ModUnderTest.call(McCompat.screen(mc), "showPage", new Class<?>[]{int.class}, new Object[]{page}));
            ctx.waitTicks(after ? 30 : 4);
            shot(ctx, set, "page" + (p + 1));
        }
        facts.add(pages + " help pages into " + set + "/");
        ctx.runOnClient(mc -> McCompat.setScreen(mc, null));
        ctx.waitTicks(5);

        // The screens the pages talk about.
        screen(ctx, "designer", mc -> nav("DESIGNER"));
        screen(ctx, "previous", mc -> nav("PREVIOUS"));
        screen(ctx, "simsettings", mc -> (Screen) ModUnderTest.staticCall("com.killer560.hub.gui.ModScreen", "atTab",
                new Class<?>[]{Screen.class, String.class}, new Object[]{null, "Sim Settings"}));
        screen(ctx, "items", mc -> make("com.killer560.hub.roomsim.SimItemsScreen"));
        screen(ctx, "bossstart", mc -> make("com.killer560.hub.bosssim.BossStartScreen"));
        screen(ctx, "coop", mc -> make("com.killer560.hub.roomsim.SimMultiplayerScreen"));
        ctx.runOnClient(mc -> McCompat.chat(mc).clearMessages(false));
        screen(ctx, "chat", mc -> new net.minecraft.client.gui.screens.ChatScreen("/goto ", false));
        facts.add("7 screen shots into src/");
    }

    /** A world shot with the HUD hidden (F1), into src/. */
    private static void worldShot(ClientGameTestContext ctx, String name) throws Exception {
        ctx.runOnClient(mc -> McCompat.setHudHidden(mc, true));
        ctx.waitTicks(4);
        shot(ctx, "src", name);
        ctx.runOnClient(mc -> McCompat.setHudHidden(mc, false));
    }

    private static Screen nav(String page) {
        try {
            Class<?> navCls = Class.forName("com.killer560.hub.roomsim.SimMenuNav");
            Class<?> pageCls = Class.forName("com.killer560.hub.roomsim.SimMenuNav$Page");
            var m = navCls.getDeclaredMethod("screenFor", pageCls, Screen.class);
            m.setAccessible(true);
            return (Screen) m.invoke(null, ModUnderTest.enumValue("com.killer560.hub.roomsim.SimMenuNav$Page", page), null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Screen make(String cls) {
        try {
            return (Screen) Class.forName(cls).getConstructor(Screen.class).newInstance((Screen) null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void screen(ClientGameTestContext ctx, String name, Function<Minecraft, Screen> make) throws Exception {
        ctx.runOnClient(mc -> McCompat.setScreen(mc, make.apply(mc)));
        ctx.waitTicks(30);
        shot(ctx, "src", name);
        ctx.runOnClient(mc -> McCompat.setScreen(mc, null));
        ctx.waitTicks(5);
    }

    private static void shot(ClientGameTestContext ctx, String set, String name) throws Exception {
        Path shot = ctx.takeScreenshot(Report.fileName(NAME + "-" + set + "-" + name));
        Report.screenshot(NAME, shot);
        Files.createDirectories(DIR.resolve(set));
        Files.copy(shot, DIR.resolve(set).resolve(name + ".png"), StandardCopyOption.REPLACE_EXISTING);
    }

    static void setWindow(ClientGameTestContext ctx, int w, int h, int gui) {
        ctx.getInput().resizeWindow(w, h);
        ctx.waitTicks(3);
        ctx.runOnClient(mc -> {
            mc.options.guiScale().set(gui);
            mc.resizeGui();
            var sc = McCompat.screen(mc);
            if (sc != null) {
                sc.resize(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
            }
        });
        ctx.waitTicks(6);
    }
}

package dev.testkit.gametest;

import com.google.gson.JsonObject;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Hx;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Latency;
import dev.testkit.harness.PacketTrace;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1025-invwalk-* and 1025-zpew-* (killer560, 2026-10-25: "can you try making inventory walk and 0 ping etherwarping as
 * well"). Inventory Walk keeps the movement keys live while a menu is open; every case proves he moved (client AND the
 * server's view) with the screen still open before quoting Grim, and a control shows that without the feature he does not.
 * Zero Ping Etherwarp draws the camera at the predicted landing the moment a warp is sent; every case runs the server's
 * real etherwarp (hx/dungeon/HxAbilities) under latency, proves the camera was at the prediction while the player was not
 * there yet, and that the server landed him where the prediction said. Cheat build only.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class InvWalkZpewTests implements FabricClientGameTest {

    private static final String IW_CFG = "invwalk.InventoryWalkConfig";
    private static final String IW_SCREENS = "invwalk.InventoryWalkConfig$Screens";
    private static final String IW = "invwalk.InventoryWalkFeature";
    private static final String ZP_CFG = "zpew.ZeroPingEtherwarpConfig";
    private static final String ZP = "zpew.ZeroPingEtherwarp";
    private static final int SURFACE_Y = 151;
    private static final String AOTV = "minecraft:diamond_shovel[custom_data={id:\"ASPECT_OF_THE_VOID\",ethermerge:1,"
            + "tuned_transmission:4},unbreakable={}]";
    private static final Pattern POS = Pattern.compile("has the following entity data: \\[(-?[0-9.E-]+)d, (-?[0-9.E-]+)d, (-?[0-9.E-]+)d\\]");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        AssertionError first = null;
        for (Consumer<ClientGameTestContext> c : List.<Consumer<ClientGameTestContext>>of(
                InvWalkZpewTests::iwSetting, InvWalkZpewTests::iwControl, InvWalkZpewTests::iwInventory,
                InvWalkZpewTests::iwJump, InvWalkZpewTests::iwChest, InvWalkZpewTests::zpSetting, InvWalkZpewTests::zpSingle,
                InvWalkZpewTests::zpChain, InvWalkZpewTests::zpWorksFor)) {
            try {
                c.accept(ctx);
            } catch (AssertionError e) {
                first = first == null ? e : first;
            }
        }
        if (first != null) {
            throw first;
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static void arena(TestServer server, int x, int z, int radius) {
        TestMap.on(server)
                .platform(x, SURFACE_Y - 1, z, radius)
                .catchFloor(120)
                .survival()
                .clearInventory()
                .spawn(x + 0.5, z + 0.5, -90f)
                .build();
    }

    private static void check(boolean ok, String why) {
        if (!ok) {
            throw new AssertionError(why);
        }
    }

    private static String fmt(double[] p) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", p[0], p[1], p[2]);
    }

    private static String fmt(Vec3 p) {
        return p == null ? "-" : String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", p.x, p.y, p.z);
    }

    private static double flat(double[] a, double[] b) {
        return Math.hypot(a[0] - b[0], a[2] - b[2]);
    }

    private static long num(ClientGameTestContext ctx, String cls, String getter) {
        return ctx.computeOnClient(mc -> ((Number) Mod.staticCall(cls, getter)).longValue());
    }

    private static String playerName(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> mc.player.getName().getString());
    }

    /** The server's own idea of where the player is, from {@code data get entity}. */
    private static double[] serverPos(TestServer server, ClientGameTestContext ctx, String name) {
        int before = count(server);
        server.command("data get entity " + name + " Pos");
        for (int i = 0; i < 40; i++) {
            ctx.waitTicks(1);
            if (count(server) > before) {
                Matcher m = POS.matcher(server.tail(60));
                double[] out = null;
                while (m.find()) {
                    out = new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)),
                            Double.parseDouble(m.group(3))};
                }
                if (out != null) {
                    return out;
                }
            }
        }
        throw new AssertionError("the server never answered data get entity " + name + " Pos");
    }

    private static int count(TestServer server) {
        Matcher m = POS.matcher(server.tail(400));
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static boolean gateOff(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            boolean gate = (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled");
            Mod.staticCall("util.SkyblockGate", "setEnabled", false);
            return gate;
        });
    }

    private static void gateBack(ClientGameTestContext ctx, boolean gate) {
        ctx.runOnClient(mc -> Mod.staticCall("util.SkyblockGate", "setEnabled", gate));
    }

    // ---- Inventory Walk -----------------------------------------------------------------------------------------

    private static void iwOn(ClientGameTestContext ctx, boolean on, Set<String> held) {
        ctx.runOnClient(mc -> {
            Mod.set(IW_CFG, "setEnabled", on);
            Mod.set(IW_CFG, "setScreens", Mod.enumValue(IW_SCREENS, "CONTAINERS"));
            Mod.set(IW_CFG, "setJump", true);
            Mod.set(IW_CFG, "setSprint", true);
            Mod.set(IW_CFG, "setPauseAfterClickTicks", 0);
            Mod.set(IW_CFG, "setStopToClick", !Boolean.getBoolean("testkit.invwalkRawClicks"));
            Mod.setField(IW, "heldForTest", held);
        });
    }

    private static void iwOff(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            Mod.setField(IW, "heldForTest", null);
            Mod.set(IW_CFG, "setEnabled", false);
            McCompat.setScreen(mc, null);
            mc.options.keyUp.setDown(false);
            mc.options.keySprint.setDown(false);
            mc.options.keyJump.setDown(false);
        });
        ctx.waitTicks(5);
    }

    /** 1025-invwalk-setting: OFF by default, every setting saved and loaded from disk. */
    private static void iwSetting(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1025-invwalk-setting", (server, scenario) -> arena(server, -5000, -3000, 8), (server, scenario) -> {
            String[] got = ctx.computeOnClient(mc -> {
                Mod.staticCall(IW_CFG, "load");
                Object f = Mod.cfg(IW_CFG);
                String defaults = Mod.call(f, "isEnabled") + " " + Mod.call(f, "getScreens") + " " + Mod.call(f, "isJump")
                        + " " + Mod.call(f, "isSprint") + " " + Mod.call(f, "getPauseAfterClickTicks") + " "
                        + Mod.call(f, "isStopToClick");
                Mod.set(IW_CFG, "setEnabled", true);
                Mod.set(IW_CFG, "setScreens", Mod.enumValue(IW_SCREENS, "ALL"));
                Mod.set(IW_CFG, "setJump", false);
                Mod.set(IW_CFG, "setSprint", false);
                Mod.set(IW_CFG, "setPauseAfterClickTicks", 7);
                Mod.set(IW_CFG, "setStopToClick", false);
                Mod.call(Mod.cfg(IW_CFG), "save");
                Mod.set(IW_CFG, "setEnabled", false);
                Mod.staticCall(IW_CFG, "load");
                Object l = Mod.cfg(IW_CFG);
                String loaded = Mod.call(l, "isEnabled") + " " + Mod.call(l, "getScreens") + " " + Mod.call(l, "isJump")
                        + " " + Mod.call(l, "isSprint") + " " + Mod.call(l, "getPauseAfterClickTicks") + " "
                        + Mod.call(l, "isStopToClick");
                Mod.set(IW_CFG, "setEnabled", false);
                Mod.set(IW_CFG, "setScreens", Mod.enumValue(IW_SCREENS, "CONTAINERS"));
                Mod.set(IW_CFG, "setJump", true);
                Mod.set(IW_CFG, "setSprint", true);
                Mod.set(IW_CFG, "setPauseAfterClickTicks", 0);
                Mod.set(IW_CFG, "setStopToClick", true);
                Mod.call(Mod.cfg(IW_CFG), "save");
                return new String[]{defaults, loaded};
            });
            scenario.log("invwalk defaults " + got[0] + "; loaded " + got[1]);
            check(got[0].equals("false CONTAINERS true true 0 true"), "defaults wrong: " + got[0]);
            check(got[1].equals("true ALL false false 7 false"), "settings did not come back from disk: " + got[1]);
        });
    }

    /** Opens the player inventory, holds the keys, waits; returns {client moved, server moved}. */
    private static double[] walkInInventory(TestServer server, Scenario scenario, ClientGameTestContext ctx, int ticks,
                                            int clicks) {
        String name = playerName(ctx);
        double[] c0 = scenario.playerPosition();
        double[] s0 = serverPos(server, ctx, name);
        ctx.runOnClient(mc -> McCompat.setScreen(mc, new InventoryScreen(mc.player)));
        ctx.waitTicks(2);
        int clicked = 0;
        for (int t = 0; t < ticks; t++) {
            ctx.waitTicks(1);
            if (clicks > 0 && t % (ticks / clicks) == ticks / clicks / 2 && clicked < clicks) {
                // Pick up and put back an empty slot: a real container click while walking.
                int slot = 9 + clicked;
                ctx.runOnClient(mc -> {
                    var s = mc.player.inventoryMenu;
                    mc.gameMode.handleContainerInput(s.containerId, slot, 0, ContainerInput.PICKUP, mc.player);
                });
                clicked++;
            }
        }
        boolean stillOpen = ctx.computeOnClient(mc -> McCompat.screen(mc) instanceof InventoryScreen);
        double[] c1 = scenario.playerPosition();
        double[] s1 = serverPos(server, ctx, name);
        scenario.log(String.format(Locale.ROOT, "inventory open %s for %d ticks: client %s -> %s (%.2f), server %s -> %s (%.2f), "
                        + "%d click(s)", stillOpen, ticks, fmt(c0), fmt(c1), flat(c0, c1), fmt(s0), fmt(s1), flat(s0, s1), clicked));
        check(stillOpen, "the inventory was not open the whole time: nothing was tested");
        return new double[]{flat(c0, c1), flat(s0, s1)};
    }

    /** 1025-invwalk-control: Inventory Walk OFF - in the inventory he does not move (the premise of the feature). */
    private static void iwControl(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1025-invwalk-control", (server, scenario) -> arena(server, -5050, -3000, 30), (server, scenario) -> {
            boolean gate = gateOff(ctx);
            try {
                iwOn(ctx, false, Set.of("key.forward", "key.sprint"));
                ctx.getInput().holdKey(o -> o.keyUp);
                ctx.waitTicks(5);
                double[] r = walkInInventory(server, scenario, ctx, 40, 0);
                ctx.getInput().releaseKey(o -> o.keyUp);
                check(r[0] < 1.0, "with Inventory Walk off he still moved " + r[0] + " blocks in the inventory");
            } finally {
                iwOff(ctx);
                gateBack(ctx, gate);
            }
        });
    }

    /** 1025-invwalk-inventory: forward + sprint for 60 ticks in the player inventory, with five slot clicks. */
    private static void iwInventory(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1025-invwalk-inventory", (server, scenario) -> arena(server, -5100, -3000, 30), (server, scenario) -> {
            boolean gate = gateOff(ctx);
            try {
                iwOn(ctx, true, Set.of("key.forward", "key.sprint"));
                ctx.runOnClient(mc -> PacketTrace.start());
                long heldBefore = num(ctx, IW, "clicksHeld");
                double[] r = walkInInventory(server, scenario, ctx, 60, 5);
                ctx.waitTicks(5);
                ctx.runOnClient(mc -> PacketTrace.stop());
                long held = num(ctx, IW, "clicksHeld") - heldBefore;
                int sentClicks = PacketTrace.sent("container_click");
                int setbacks = PacketTrace.received("player_position");
                scenario.log("clicks held for a still tick " + held + ", container clicks on the wire " + sentClicks
                        + ", setbacks " + setbacks);
                check(r[0] > 8 && r[1] > 8, "he did not walk in the inventory (client " + r[0] + ", server " + r[1] + ")");
                check(sentClicks >= 5, "fewer than five clicks reached the wire: " + sentClicks);
                check(Boolean.getBoolean("testkit.invwalkRawClicks") || held >= 1,
                        "no click was ever made while walking: Stop To Click was not tested");
                check(setbacks == 0, "the server set him back " + setbacks + " time(s)");
            } finally {
                iwOff(ctx);
                gateBack(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    /** 1025-invwalk-jump: forward + jump for 60 ticks in the player inventory. */
    private static void iwJump(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1025-invwalk-jump", (server, scenario) -> arena(server, -5150, -3000, 30), (server, scenario) -> {
            boolean gate = gateOff(ctx);
            try {
                iwOn(ctx, true, Set.of("key.forward", "key.jump", "key.sprint"));
                double[] r = walkInInventory(server, scenario, ctx, 60, 0);
                check(r[0] > 8 && r[1] > 8, "he did not move in the inventory (client " + r[0] + ", server " + r[1] + ")");
            } finally {
                iwOff(ctx);
                gateBack(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    /** 1025-invwalk-chest: a server chest menu (Hx menu) open, walking 60 ticks with five slot clicks. */
    private static void iwChest(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1025-invwalk-chest", (server, scenario) -> arena(server, -5200, -3000, 30), (server, scenario) -> {
            boolean gate = gateOff(ctx);
            try (Hx hx = Hx.connect()) {
                iwOn(ctx, true, Set.of("key.forward", "key.sprint"));
                String name = playerName(ctx);
                double[] c0 = scenario.playerPosition();
                double[] s0 = serverPos(server, ctx, name);
                int id = hx.menu("Blink Test Menu", 3, Map.of(11, "minecraft:stone", 13, "minecraft:dirt", 15,
                        "minecraft:oak_log"), null);
                ctx.waitTicks(10);
                boolean open = ctx.computeOnClient(mc -> McCompat.screen(mc) instanceof
                        net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>);
                long heldBefore = num(ctx, IW, "clicksHeld");
                ctx.runOnClient(mc -> PacketTrace.start());
                for (int t = 0; t < 60; t++) {
                    ctx.waitTicks(1);
                    if (t % 12 == 6) {
                        int slot = 11 + (t / 12 % 3) * 2;
                        ctx.runOnClient(mc -> {
                            var menu = mc.player.containerMenu;
                            mc.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.PICKUP, mc.player);
                        });
                    }
                }
                ctx.waitTicks(5);
                ctx.runOnClient(mc -> PacketTrace.stop());
                boolean stillOpen = ctx.computeOnClient(mc -> McCompat.screen(mc) instanceof
                        net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>);
                double[] c1 = scenario.playerPosition();
                double[] s1 = serverPos(server, ctx, name);
                long held = num(ctx, IW, "clicksHeld") - heldBefore;
                int clicks = PacketTrace.sent("container_click");
                int setbacks = PacketTrace.received("player_position");
                scenario.log(String.format(Locale.ROOT, "chest %d open %s/%s: client moved %.2f, server moved %.2f, %d click(s) "
                        + "on the wire, %d held for a still tick, %d setback(s)", id, open, stillOpen, flat(c0, c1), flat(s0, s1),
                        clicks, held, setbacks));
                check(open && stillOpen, "the chest menu was not open the whole time: nothing was tested");
                check(flat(c0, c1) > 8 && flat(s0, s1) > 8, "he did not walk with the chest open");
                check(clicks >= 4, "fewer than four clicks reached the wire: " + clicks);
                check(setbacks == 0, "the server set him back " + setbacks + " time(s)");
            } finally {
                iwOff(ctx);
                gateBack(ctx, gate);
            }
            ctx.waitTicks(40);
        });
    }

    // ---- Zero Ping Etherwarp ------------------------------------------------------------------------------------

    /** 1025-zpew-setting: OFF by default, every setting saved and loaded from disk. */
    private static void zpSetting(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1025-zpew-setting", (server, scenario) -> arena(server, -5300, -3000, 8), (server, scenario) -> {
            String[] got = ctx.computeOnClient(mc -> {
                Mod.staticCall(ZP_CFG, "load");
                Object f = Mod.cfg(ZP_CFG);
                String defaults = Mod.call(f, "isEnabled") + " " + Mod.call(f, "getTimeoutMs") + " " + Mod.call(f, "isChain")
                        + " " + Mod.call(f, "isOverlayFromPrediction");
                Mod.set(ZP_CFG, "setEnabled", true);
                Mod.set(ZP_CFG, "setTimeoutMs", 750);
                Mod.set(ZP_CFG, "setChain", false);
                Mod.set(ZP_CFG, "setOverlayFromPrediction", false);
                Mod.call(Mod.cfg(ZP_CFG), "save");
                Mod.set(ZP_CFG, "setEnabled", false);
                Mod.staticCall(ZP_CFG, "load");
                Object l = Mod.cfg(ZP_CFG);
                String loaded = Mod.call(l, "isEnabled") + " " + Mod.call(l, "getTimeoutMs") + " " + Mod.call(l, "isChain")
                        + " " + Mod.call(l, "isOverlayFromPrediction");
                Mod.set(ZP_CFG, "setEnabled", false);
                Mod.set(ZP_CFG, "setTimeoutMs", 1000);
                Mod.set(ZP_CFG, "setChain", true);
                Mod.set(ZP_CFG, "setOverlayFromPrediction", true);
                Mod.call(Mod.cfg(ZP_CFG), "save");
                return new String[]{defaults, loaded};
            });
            scenario.log("zpew defaults " + got[0] + "; loaded " + got[1]);
            check(got[0].equals("false 1000 true true"), "defaults wrong: " + got[0]);
            check(got[1].equals("true 750 false false"), "settings did not come back from disk: " + got[1]);
        });
    }

    private static final String[] CHEAT_SOURCES = {"LEFT_CLICK", "AUTO_ROUTES", "INTERACTIVE_MAP", "AUTO_SECRET",
            "AUTO_CLEAR", "AUTO_PUZZLES", "AP3", "PATHFINDING"};
    private static final String[] CHEAT_LABELS = {"Left Click Etherwarp", "Auto Routes", "Interactive Map",
            "Auto Secret / Autopilot", "Auto Clear", "Auto Puzzles", "AP3", "Pathfinding Hopper"};

    /** The Zero Ping Etherwarp tab's rows (labels, formatting stripped) with ZPEW on. */
    @SuppressWarnings("unchecked")
    private static List<String> zpTabLabels(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            try {
                Object tab = Mod.cls("gui.tab.ZeroPingEtherwarpTab").getConstructor().newInstance();
                List<net.minecraft.client.gui.components.AbstractWidget> ws =
                        (List<net.minecraft.client.gui.components.AbstractWidget>) Mod.call(tab, "buildWidgets", 0, 0, 300,
                                (Runnable) () -> {
                                });
                List<String> out = new java.util.ArrayList<>();
                for (var w : ws) {
                    String s = net.minecraft.ChatFormatting.stripFormatting(w.getMessage().getString());
                    int colon = s.indexOf(':');
                    out.add((colon > 0 ? s.substring(0, colon) : s).trim());
                }
                return out;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
    }

    /** One hand etherwarp (sneak held, use pressed) onto (bx, SURFACE_Y-1, bz); waits for the landing. */
    private static void handWarp(ClientGameTestContext ctx, int bx, int bz) {
        Vec3 start = ctx.computeOnClient(mc -> mc.player.position());
        float[] aim = aimAt(start, bx, bz);
        useAt(ctx, aim[0], aim[1]);
        for (int i = 0; i < 40; i++) {
            ctx.waitTicks(1);
            double d = ctx.computeOnClient(mc -> Math.hypot(mc.player.getX() - (bx + 0.5), mc.player.getZ() - (bz + 0.5)));
            if (d < 0.6) {
                break;
            }
        }
        ctx.waitTicks(10);
    }

    /**
     * 1027-zpew-worksfor (killer560, 2026-10-27: "make an option like things that zpew works for with a list of
     * everything"): the Works For switches are saved and loaded; the tab lists only Manual Etherwarps in a legit jar and
     * all nine (the eight cheat ones under the red header) in a cheat jar; a cheat source never predicts in a legit jar;
     * and a hand etherwarp is predicted with Manual on, not with Manual off, and nothing at all with ZPEW off.
     */
    private static void zpWorksFor(ClientGameTestContext ctx) {
        int x = -5600;
        int z = -3000;
        Scenario.run(ctx, "1027-zpew-worksfor", (server, scenario) -> arena(server, x, z, 40), (server, scenario) -> {
            boolean cheat = Mod.isCheat();
            // ---- saved and loaded ----
            String loaded = ctx.computeOnClient(mc -> {
                Mod.call(Mod.cfg(ZP_CFG), "setWorksForByName", "MANUAL", false);
                Mod.call(Mod.cfg(ZP_CFG), "setWorksForByName", "AP3", false);
                Mod.call(Mod.cfg(ZP_CFG), "save");
                Mod.call(Mod.cfg(ZP_CFG), "setWorksForByName", "MANUAL", true);
                Mod.call(Mod.cfg(ZP_CFG), "setWorksForByName", "AP3", true);
                Mod.staticCall(ZP_CFG, "load");
                Object l = Mod.cfg(ZP_CFG);
                String out = Mod.call(l, "getWorksForByName", "MANUAL") + " " + Mod.call(l, "getWorksForByName", "AP3")
                        + " " + Mod.call(l, "getWorksForByName", "AUTO_ROUTES");
                Mod.call(l, "setWorksForByName", "MANUAL", true);
                Mod.call(l, "setWorksForByName", "AP3", true);
                Mod.call(l, "save");
                return out;
            });
            scenario.log("works for, loaded after saving MANUAL and AP3 off: " + loaded);
            check(loaded.equals("false false true"), "Works For did not come back from disk: " + loaded);

            // ---- the tab, by build ----
            ctx.runOnClient(mc -> Mod.set(ZP_CFG, "setEnabled", true));
            List<String> labels = zpTabLabels(ctx);
            scenario.log((cheat ? "cheat" : "legit") + " tab rows: " + labels);
            check(labels.contains("Works For") && labels.contains("Manual Etherwarps"),
                    "the tab has no Works For section with Manual Etherwarps: " + labels);
            for (String l : CHEAT_LABELS) {
                check(labels.contains(l) == cheat, (cheat ? "the cheat tab is missing " : "the legit tab shows ") + l);
            }
            check(labels.contains("Cheat Build - Works For") == cheat, "cheat header shown=" + !cheat + " in a "
                    + (cheat ? "cheat" : "legit") + " jar");
            if (cheat) {
                int manualAt = labels.indexOf("Manual Etherwarps");
                int headerAt = labels.indexOf("Cheat Build - Works For");
                check(headerAt > manualAt && labels.indexOf("AP3") > headerAt, "the cheat sources are not under the red "
                        + "header at the bottom: " + labels);
            }
            String legitGate = ctx.computeOnClient(mc -> {
                Object c = Mod.cfg(ZP_CFG);
                StringBuilder sb = new StringBuilder();
                for (String s : CHEAT_SOURCES) {
                    sb.append(s).append('=').append(Mod.call(c, "worksFor", Mod.enumValue("zpew.ZpewSource", s))).append(' ');
                }
                return sb.toString().trim();
            });
            scenario.log("worksFor() per cheat source: " + legitGate);
            check(cheat == !legitGate.contains("=false"), "cheat sources' worksFor() in a " + (cheat ? "cheat" : "legit")
                    + " jar: " + legitGate);

            // ---- live: Manual on, Manual off, ZPEW off ----
            Hx hx = null;
            try {
                hx = zpSetUp(ctx);
                ctx.getInput().holdKey(o -> o.keyShift);
                ctx.waitTicks(10);
                int[] go = {x + 16, x};
                String[] mode = {"manual on", "manual off", "zpew off"};
                long[][] got = new long[3][];
                for (int i = 0; i < 3; i++) {
                    final int k = i;
                    ctx.runOnClient(mc -> {
                        Mod.call(Mod.cfg(ZP_CFG), "setWorksForByName", "MANUAL", k != 1);
                        Mod.set(ZP_CFG, "setEnabled", k != 2);
                    });
                    ctx.waitTicks(2);
                    JsonObject before = hx.call("dungeon.stats").getAsJsonObject();
                    long p0 = ctx.computeOnClient(mc -> ((Number) Mod.staticCall(ZP, "predictedBy", "MANUAL")).longValue());
                    long h0 = ctx.computeOnClient(mc -> ((Number) Mod.staticCall(ZP, "hitsBy", "MANUAL")).longValue());
                    long s0 = ctx.computeOnClient(mc -> ((Number) Mod.staticCall(ZP, "skippedBy", "MANUAL")).longValue());
                    long all0 = num(ctx, ZP, "predictions");
                    long seen0 = num(ctx, ZP, "usesSeen");
                    handWarp(ctx, go[k % 2], z);
                    JsonObject after = hx.call("dungeon.stats").getAsJsonObject();
                    long warps = after.get("etherwarps").getAsInt() - before.get("etherwarps").getAsInt();
                    long p = ctx.computeOnClient(mc -> ((Number) Mod.staticCall(ZP, "predictedBy", "MANUAL")).longValue()) - p0;
                    long h = ctx.computeOnClient(mc -> ((Number) Mod.staticCall(ZP, "hitsBy", "MANUAL")).longValue()) - h0;
                    long s = ctx.computeOnClient(mc -> ((Number) Mod.staticCall(ZP, "skippedBy", "MANUAL")).longValue()) - s0;
                    long all = num(ctx, ZP, "predictions") - all0;
                    long seen = num(ctx, ZP, "usesSeen") - seen0;
                    got[k] = new long[]{warps, p, h, s, all, seen};
                    scenario.log(String.format(Locale.ROOT, "%s: %d server warp(s), manual predicted %d hit %d skipped %d; "
                            + "all predictions %d, uses seen %d", mode[k], warps, p, h, s, all, seen));
                }
                check(got[0][0] == 1 && got[0][1] == 1 && got[0][2] == 1, "Manual on: the hand warp was not predicted and "
                        + "hit (warps/predicted/hit " + got[0][0] + "/" + got[0][1] + "/" + got[0][2] + ")");
                check(got[1][0] == 1 && got[1][1] == 0 && got[1][3] == 1 && got[1][4] == 0, "Manual off: warps/predicted/"
                        + "skipped/all " + got[1][0] + "/" + got[1][1] + "/" + got[1][3] + "/" + got[1][4]);
                check(got[2][0] == 1 && got[2][4] == 0 && got[2][5] == 0, "ZPEW off: warps/all/seen " + got[2][0] + "/"
                        + got[2][4] + "/" + got[2][5]);
            } finally {
                ctx.runOnClient(mc -> Mod.call(Mod.cfg(ZP_CFG), "setWorksForByName", "MANUAL", true));
                zpTearDown(ctx, hx);
                ctx.runOnClient(mc -> Mod.call(Mod.cfg(ZP_CFG), "save"));
            }
        });
    }

    private static Vec3 camera(Minecraft mc) {
        for (String name : new String[]{"getMainCamera", "mainCamera"}) {
            try {
                Object cam = mc.gameRenderer.getClass().getMethod(name).invoke(mc.gameRenderer);
                return (Vec3) cam.getClass().getMethod("position").invoke(cam);
            } catch (NoSuchMethodException ignored) {
                // the other version's name
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("no camera accessor");
    }

    /** The yaw/pitch from the sneaking eye at {@code feet} to the top center of block (bx, SURFACE_Y-1, bz). */
    private static float[] aimAt(Vec3 feet, int bx, int bz) {
        double ex = feet.x;
        double ey = feet.y + 1.27;
        double ez = feet.z;
        double tx = bx + 0.5;
        double ty = SURFACE_Y;
        double tz = bz + 0.5;
        double dx = tx - ex;
        double dy = ty - ey;
        double dz = tz - ez;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        return new float[]{yaw, pitch};
    }

    private static Hx zpSetUp(ClientGameTestContext ctx) {
        Hx hx = Hx.connect();
        hx.call("dungeon.abilities", "enabled", true);
        hx.give(0, AOTV);
        ctx.runOnClient(mc -> {
            mc.player.getInventory().setSelectedSlot(0);
            Mod.set(ZP_CFG, "setEnabled", true);
            Mod.set(ZP_CFG, "setChain", true);
            Mod.set(ZP_CFG, "setTimeoutMs", 1500);
        });
        ctx.waitTicks(10);
        return hx;
    }

    private static void zpTearDown(ClientGameTestContext ctx, Hx hx) {
        ctx.getInput().releaseKey(o -> o.keyShift);
        ctx.runOnClient(mc -> {
            Latency.remove();
            Mod.set(ZP_CFG, "setEnabled", false);
        });
        if (hx != null) {
            hx.call("dungeon.abilities", "enabled", false);
            hx.close();
        }
        ctx.waitTicks(20);
    }

    /**
     * Sends one etherwarp now at the given look (a client-side turn, reported by this tick's movement packet, and a right
     * click), and records the camera and the player each tick until it lands. Returns the frames seen while pending.
     */
    private static void useAt(ClientGameTestContext ctx, float yaw, float pitch) {
        ctx.runOnClient(mc -> {
            mc.player.setYRot(mc.player.getYRot() + Mth.wrapDegrees(yaw - mc.player.getYRot()));
            mc.player.setXRot(Mth.clamp(pitch, -90f, 90f));
        });
        ctx.getInput().pressKey(o -> o.keyUse);
    }

    /** 1025-zpew-single: one etherwarp under 150 ms latency - the camera is at the landing before he is. */
    private static void zpSingle(ClientGameTestContext ctx) {
        int x = -5400;
        int z = -3000;
        Scenario.run(ctx, "1025-zpew-single", (server, scenario) -> arena(server, x, z, 40), (server, scenario) -> {
            Hx hx = null;
            try {
                hx = zpSetUp(ctx);
                JsonObject before = hx.call("dungeon.stats").getAsJsonObject();
                long hits0 = num(ctx, ZP, "hits");
                long preds0 = num(ctx, ZP, "predictions");
                ctx.runOnClient(mc -> Latency.install(150));
                ctx.waitTicks(20);
                ctx.getInput().holdKey(o -> o.keyShift);
                ctx.waitTicks(10);
                Vec3 start = ctx.computeOnClient(mc -> mc.player.position());
                float[] aim = aimAt(start, x + 16, z);
                useAt(ctx, aim[0], aim[1]);
                // While pending: the camera is at the prediction, the player is not.
                double camOff = -1;
                double playerAway = -1;
                for (int i = 0; i < 20; i++) {
                    ctx.waitTicks(1);
                    double[] snap = ctx.computeOnClient(mc -> {
                        int pending = ((Number) Mod.staticCall(ZP, "pending")).intValue();
                        Vec3 pred = (Vec3) Mod.staticCall(ZP, "predictedFeet");
                        Vec3 cam = camera(mc);
                        if (pending == 0 || pred == null) {
                            return new double[]{-1, -1};
                        }
                        Vec3 eye = new Vec3(pred.x, pred.y + mc.player.getEyeHeight(), pred.z);
                        return new double[]{cam.distanceTo(eye), mc.player.position().distanceTo(pred)};
                    });
                    if (snap[0] >= 0) {
                        camOff = snap[0];
                        playerAway = snap[1];
                        break;
                    }
                }
                ctx.waitTicks(30);
                long hits = num(ctx, ZP, "hits") - hits0;
                long preds = num(ctx, ZP, "predictions") - preds0;
                double err = ctx.computeOnClient(mc -> ((Number) Mod.staticCall(ZP, "lastErrorBlocks")).doubleValue());
                long latency = num(ctx, ZP, "lastLatencyMs");
                JsonObject after = hx.call("dungeon.stats").getAsJsonObject();
                int warps = after.get("etherwarps").getAsInt() - before.get("etherwarps").getAsInt();
                Vec3 end = ctx.computeOnClient(mc -> mc.player.position());
                scenario.log(String.format(Locale.ROOT, "single: %d prediction(s), %d hit(s), %d server warp(s); while pending the "
                                + "camera was %.3f from the predicted eye and the player %.2f from the landing; error %.3f, round "
                                + "trip %d ms; from %s to %s", preds, hits, warps, camOff, playerAway, err, latency, fmt(start),
                        fmt(end)));
                check(warps == 1, "the server did not etherwarp exactly once: " + warps);
                check(preds == 1 && hits == 1, "the warp was not predicted and matched (" + preds + " / " + hits + ")");
                check(camOff >= 0 && camOff < 0.3, "the camera was not at the predicted landing while pending: " + camOff);
                check(playerAway > 5, "the player was already at the landing when the camera was checked: " + playerAway);
                check(err < 0.2, "the prediction was " + err + " blocks off the server's landing");
            } finally {
                zpTearDown(ctx, hx);
            }
        });
    }

    /**
     * 1025-zpew-chain: three etherwarps one tick apart under 150 ms latency - the second and third are aimed from the
     * predicted landing of the one before, which the server has already moved him to when they arrive.
     */
    private static void zpChain(ClientGameTestContext ctx) {
        int x = -5500;
        int z = -3000;
        Scenario.run(ctx, "1025-zpew-chain", (server, scenario) -> arena(server, x, z, 45), (server, scenario) -> {
            Hx hx = null;
            try {
                hx = zpSetUp(ctx);
                JsonObject before = hx.call("dungeon.stats").getAsJsonObject();
                long hits0 = num(ctx, ZP, "hits");
                long misses0 = num(ctx, ZP, "misses");
                ctx.runOnClient(mc -> Latency.install(150));
                ctx.waitTicks(20);
                ctx.getInput().holdKey(o -> o.keyShift);
                ctx.waitTicks(10);
                int[][] targets = {{x + 14, z}, {x + 28, z}, {x + 28, z + 14}};
                Vec3 from = ctx.computeOnClient(mc -> mc.player.position());
                for (int[] t : targets) {
                    float[] aim = aimAt(from, t[0], t[1]);
                    useAt(ctx, aim[0], aim[1]);
                    ctx.waitTicks(1);
                    from = new Vec3(t[0] + 0.5, SURFACE_Y + 0.05, t[1] + 0.5);
                }
                int maxChain = (int) num(ctx, ZP, "maxChained");
                ctx.waitTicks(40);
                long hits = num(ctx, ZP, "hits") - hits0;
                long misses = num(ctx, ZP, "misses") - misses0;
                JsonObject after = hx.call("dungeon.stats").getAsJsonObject();
                int warps = after.get("etherwarps").getAsInt() - before.get("etherwarps").getAsInt();
                Vec3 end = ctx.computeOnClient(mc -> mc.player.position());
                Vec3 want = new Vec3(x + 28.5, SURFACE_Y + 0.05, z + 14.5);
                scenario.log(String.format(Locale.ROOT, "chain: %d server warp(s), %d hit(s), %d miss(es), up to %d pending at "
                        + "once; ended at %s, wanted %s (%.2f off)", warps, hits, misses, maxChain, fmt(end), fmt(want),
                        end.distanceTo(want)));
                check(warps == 3, "the server did not etherwarp three times: " + warps);
                check(hits == 3 && misses == 0, "not every warp was predicted and matched (" + hits + " hit, " + misses + " miss)");
                check(maxChain >= 2, "no warp was ever sent while another was still pending: nothing chained");
                check(end.distanceTo(want) < 0.5, "he did not end on the third landing");
            } finally {
                zpTearDown(ctx, hx);
            }
        });
    }
}

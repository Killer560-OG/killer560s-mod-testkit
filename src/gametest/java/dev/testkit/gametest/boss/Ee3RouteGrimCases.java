package dev.testkit.gametest.boss;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 1109-boss-ee3route-grim (ee3route-1109): the HEE2 -> EE3 route file {@code fastest.json} on GrimAC. The stretch's map
 * (corridor, S2 pit, low-lever platform, Breaker tunnel, walkway to EE3) is rebuilt from his P3 capture as /fill commands
 * ({@code killer560s-mod-logs/ee3route-1109/fills-env.txt}, written by fills.py there; full cubes as stone). The route's
 * Breaker picks are cleared before the run (his rule: "assume that breaker is working as intended"; Breaker Aura's own
 * Grim tests cover its digs). Lever Aura flicks both S2 levers (with their "Not Activated" stands) and the BOOM node sends
 * its Superboom tap; GrimAC must stay silent (Session.test) on the default and experimental configs.
 */
final class Ee3RouteGrimCases {

    private static final Path DIR = Path.of(System.getProperty("user.home"), "killer560s-mod-logs", "ee3route-1109");
    private static final String ROUTE = System.getProperty("testkit.ee3.route", "fastest");
    private static final BlockPos LOW = new BlockPos(27, 124, 127);
    private static final BlockPos HIGH = new BlockPos(23, 132, 138);
    private static final String CU = "cheatutils.CheatUtilsConfig";
    private static final String LA = "leveraura.LeverAuraConfig";

    private Ee3RouteGrimCases() {
    }

    static void register(Session s) {
        if (!Mod.isCheat()) {
            return;
        }
        s.test("1109-boss-ee3route-grim", Ee3RouteGrimCases::run);
    }

    private static boolean powered(net.minecraft.client.Minecraft mc, BlockPos p) {
        BlockState st = mc.level.getBlockState(p);
        return st.getBlock() instanceof LeverBlock && st.getValue(LeverBlock.POWERED);
    }

    static void run(Session c) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        Path route = DIR.resolve("candidates").resolve(ROUTE + ".json");
        Path fills = DIR.resolve("fills-env.txt");
        c.check(Files.isRegularFile(route) && Files.isRegularFile(fills), "missing " + route + " or " + fills);
        try {
            c.server().command("forceload add -2 100 63 146");
            c.server().sync(30);
            for (String l : Files.readAllLines(fills)) {
                if (!l.isBlank()) {
                    c.server().command(l);
                }
            }
            c.server().sync(120);
            for (String l : Files.readAllLines(DIR.resolve("candidates").resolve(ROUTE + ".picks"))) {
                l = l.strip();
                if (!l.isEmpty() && !l.startsWith("#")) {
                    c.server().command("setblock " + l.replace(',', ' ') + " minecraft:air");
                }
            }
            for (BlockPos p : new BlockPos[]{LOW, HIGH}) {
                c.server().command(String.format(Locale.ROOT, "summon minecraft:armor_stand %.1f %d %.1f {NoGravity:1b,Invisible:1b,"
                        + "CustomNameVisible:1b,CustomName:\"Not Activated\",Tags:[\"ee3route\"]}", p.getX() + 0.5, p.getY() + 1, p.getZ() + 0.5));
            }
            c.server().command("item replace entity @p hotbar.6 with minecraft:tnt[minecraft:custom_data={id:\"SUPERBOOM_TNT\"}] 64");
            c.server().command("attribute @p minecraft:movement_speed base set 0.6");
            c.server().sync(30);
            TerminalAuraCases.tp(c, 60.5, 132, 139, 72f);
            c.ctx().waitTicks(10);
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
            LightsDeviceCases.newRun(c);
            LightsDeviceCases.openS2(c);
            undo.add(c.onClient(mc -> Mod.with(CU, "AuraBossLevers", true)));
            undo.add(c.onClient(mc -> Mod.with(LA, "SectionLevers", true)));
            undo.add(c.onClient(mc -> Mod.with(LA, "Range", 4.5)));
            undo.add(c.onClient(mc -> Mod.with(LA, "Cps", 20)));
            undo.add(c.onClient(mc -> Mod.with("ap3.Ap3Config", "Enabled", true)));
            c.onClient(mc -> {
                try {
                    Mod.staticCall("ap3.Ap3Executor", "stop", "test lane");
                    Mod.staticCall("ap3.Ap3Feature", "setForceDungeon", true);
                    Path dir = (Path) Mod.staticCall("ap3.Ap3Store", "directory");
                    Files.createDirectories(dir);
                    Files.copy(route, dir.resolve("ee3-grim.json"), StandardCopyOption.REPLACE_EXISTING);
                    Mod.staticCall("ap3.Ap3Store", "select", "ee3-grim.json");
                    Mod.staticCall("ap3.Ap3Store", "reload");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return null;
            });
            c.ctx().waitTicks(5);
            c.check(c.onClient(mc -> !powered(mc, LOW) && !powered(mc, HIGH)), "an S2 lever was already on before the run");
            long mark = LogTap.mark();
            // Leave and re-enter HEE2's box: a tp lands him in it, as a leap would.
            TerminalAuraCases.tp(c, 62.5, 132, 139, 72f);
            c.ctx().waitTicks(5);
            TerminalAuraCases.tp(c, 60.5, 132, 139, 72f);
            int t0 = -1;
            int arrive = -1;
            double minY = 999;
            for (int t = 0; t < 400 && arrive < 0; t++) {
                c.ctx().waitTick();
                double[] s = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                        mc.player.onGround() ? 1 : 0});
                minY = Math.min(minY, s[1]);
                if (t0 < 0 && Math.hypot(s[0] - 60.5, s[2] - 139) > 0.2) {
                    t0 = t;
                }
                if (Math.abs(s[0] - 2.5) <= 1 && Math.abs(s[2] - 105.5) <= 1 && Math.abs(s[1] - 109) <= 1.5 && s[3] > 0) {
                    arrive = t;
                }
                if (s[1] < 100) {
                    break;
                }
            }
            c.ctx().waitTicks(20);
            boolean[] lev = c.onClient(mc -> new boolean[]{powered(mc, LOW), powered(mc, HIGH)});
            boolean boomSent = false;
            for (String l : LogTap.since(mark)) {
                if (l.contains("[AP3] boom #") && l.contains("turn done")) {
                    boomSent = true;
                }
            }
            c.note(String.format(Locale.ROOT, "route %s: left HEE2 t%d, at EE3 t%d (%d ticks), levers low/high %s/%s, boom tap %s,"
                    + " lowest y %.2f", ROUTE, t0, arrive, arrive < 0 || t0 < 0 ? -1 : arrive - t0, lev[0], lev[1], boomSent, minY));
            c.check(t0 >= 0 && arrive >= 0, "the route never reached EE3 (lowest y " + minY + ")");
            c.check(lev[0] && lev[1], "levers low/high powered " + lev[0] + "/" + lev[1]);
            c.check(boomSent, "the BOOM node never sent its tap");
        } finally {
            c.onClient(mc -> {
                Mod.staticCall("ap3.Ap3Executor", "stop", "test over");
                return null;
            });
            for (int i = undo.size() - 1; i >= 0; i--) {
                undo.get(i).close();
            }
            c.server().command("kill @e[tag=ee3route]");
            c.server().command("attribute @p minecraft:movement_speed base set 0.1");
            c.server().command("forceload remove -2 100 63 146");
            c.hx().sidebarClear();
        }
    }
}

package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 1109-boss-ee3route (ee3route-1109, killer560 2026-11-09: "the fastest route from HEE2 to EE3"): runs AP3 route files
 * from HEE2 (60.5 132 139) to EE3 (2.5 109 105.5) in the sim boss (his P3 capture, S2 in progress, speed 600) and times
 * them in ticks. Lever Aura flicks the two S2 levers; the route's BOOM node Superbooms gate 2.
 *
 * <p>Dungeon Breaker is MODELED, not run (his rule for this job: "assume that breaker is working as intended" and "assume
 * the multi break"): every pick of the route's pick list whose box comes within 4.5 of the eye breaks on that tick, all
 * together, one charge each, out of 20 that come back one per 10 ticks (roomsim/SimBreakerState's numbers). A pick in
 * reach with no charge left is logged as SHORT and stays until a charge comes back.
 *
 * <p>The routes are {@code <name>.json} (an AP3 chains file) and {@code <name>.picks} (lines "x,y,z", or a Breaker Aura
 * config with "picked") in {@code killer560s-mod-logs/ee3route-1109/candidates}; {@code run.txt} there lists
 * "name [repeats]" per line. Default: the shipped pair "his" and "fastest". A name starting with "his" is his own route,
 * timed and reported but not held to the checks.
 */
final class SimBossEe3Route1109 {
    static final String NAME = "1109-boss-ee3route";
    private static final Path DIR = Path.of(System.getProperty("user.home"), "killer560s-mod-logs", "ee3route-1109",
            System.getenv().getOrDefault("KM_EE3_DIR", "candidates")); // leverap3-1109: a run of its own
    private static final BlockPos LOW = new BlockPos(27, 124, 127);
    private static final BlockPos HIGH = new BlockPos(23, 132, 138);
    private static final double REACH = 4.5;
    private static final int MAX_CHARGES = 20;
    private static final int RECHARGE_TICKS = 10;
    private static final AABB ARCH = new AABB(16, 132, 128, 19, 136, 137);

    private SimBossEe3Route1109() {
    }

    static void run(ClientGameTestContext ctx, List<String> f) throws Exception {
        List<String[]> plan = new ArrayList<>();
        Path runFile = DIR.resolve("run.txt");
        if (Files.isRegularFile(runFile)) {
            for (String l : Files.readAllLines(runFile)) {
                l = l.strip();
                if (!l.isEmpty() && !l.startsWith("#")) {
                    plan.add(l.split("\\s+"));
                }
            }
        } else {
            plan.add(new String[]{"his"});
            plan.add(new String[]{"fastest"});
        }
        // killer560's own AP3 movement setting (26.3 killer560smod-ap3-settings.json): 45 Degree Strafe ON; restored after.
        boolean strafeWas = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.call(ModUnderTest.config(Ap3RuntimeTests.CONFIG),
                "isStrafe45", new Class<?>[]{}, new Object[]{}));
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(Ap3RuntimeTests.CONFIG), "setStrafe45", true));
        try {
        SimBossTests.withCapture(f, () -> {
            for (String[] p : plan) {
                int reps = p.length > 1 ? Integer.parseInt(p[1]) : 1;
                for (int i = 0; i < reps; i++) {
                    try {
                        // "name reps x y z yaw": start there instead of HEE2 (one segment of a route, while designing it).
                        double[] at = p.length >= 6 ? new double[]{Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                                Double.parseDouble(p[4]), Double.parseDouble(p[5])} : new double[]{60.5, 132, 139, 72};
                        one(ctx, f, p[0], i, at);
                    } catch (Exception e) {
                        f.add(p[0] + " #" + i + " threw: " + e);
                        e.printStackTrace(System.out);
                    } finally {
                        SimBossTests.cleanup(ctx);
                    }
                }
            }
        });
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(Ap3RuntimeTests.CONFIG), "setStrafe45", strafeWas));
        }
    }

    private static List<BlockPos> picks(Path file) throws Exception {
        List<BlockPos> out = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        String text = Files.readString(file);
        List<String> items = new ArrayList<>();
        if (text.stripLeading().startsWith("{")) {
            for (var e : com.google.gson.JsonParser.parseString(text).getAsJsonObject().getAsJsonArray("picked")) {
                items.add(e.getAsString());
            }
        } else {
            for (String l : text.split("\\R")) {
                l = l.strip();
                if (!l.isEmpty() && !l.startsWith("#")) {
                    items.add(l);
                }
            }
        }
        for (String s : items) {
            String[] c = s.split(",");
            out.add(new BlockPos(Integer.parseInt(c[0].strip()), Integer.parseInt(c[1].strip()), Integer.parseInt(c[2].strip())));
        }
        return out;
    }

    private static double boxDistSq(Vec3 e, BlockPos p) {
        double cx = Math.max(p.getX(), Math.min(e.x, p.getX() + 1));
        double cy = Math.max(p.getY(), Math.min(e.y, p.getY() + 1));
        double cz = Math.max(p.getZ(), Math.min(e.z, p.getZ() + 1));
        return (e.x - cx) * (e.x - cx) + (e.y - cy) * (e.y - cy) + (e.z - cz) * (e.z - cz);
    }

    private static boolean powered(Minecraft mc, BlockPos p) {
        BlockState st = mc.level.getBlockState(p);
        return st.getBlock() instanceof LeverBlock && st.getValue(LeverBlock.POWERED);
    }

    private static void say(String line) {
        System.out.println("[" + NAME + "] " + line);
    }

    private static void one(ClientGameTestContext ctx, List<String> f, String name, int rep, double[] at) throws Exception {
        boolean seg = at[0] != 60.5 || at[2] != 139;
        String tag = name + (seg ? "@" + (int) at[0] + "," + (int) at[2] : "") + " #" + rep;
        boolean his = name.startsWith("his");
        Path route = DIR.resolve(name + ".json");
        if (!Files.isRegularFile(route)) {
            f.add(tag + ": no route file " + route);
            return;
        }
        String routeText = Files.readString(route);
        boolean hasBoom = routeText.contains("\"BOOM\"");
        List<BlockPos> pickList = picks(DIR.resolve(name + ".picks"));

        SimBossTests.start(ctx, "S2", true);
        ctx.waitTicks(40);
        String file = "ee3-" + name + ".json";
        ctx.runOnClient(mc -> {
            try {
                Object cfg = ModUnderTest.config(Ap3RuntimeTests.CONFIG);
                ModUnderTest.set(cfg, "setEnabled", true);
                ModUnderTest.staticCall(Ap3RuntimeTests.FEATURE, "setForceDungeon", new Class<?>[]{boolean.class}, new Object[]{false});
                Object cu = ModUnderTest.staticCall("com.killer560.hub.cheatutils.CheatUtilsConfig", "getInstance");
                ModUnderTest.call(cu, "setAuraBossLevers", new Class<?>[]{boolean.class}, new Object[]{true});
                Object la = ModUnderTest.staticCall("com.killer560.hub.leveraura.LeverAuraConfig", "getInstance");
                ModUnderTest.call(la, "setSectionLevers", new Class<?>[]{boolean.class}, new Object[]{true});
                // The sim's S2 start leaves the section tracker at 1 (no terminal chat): the early switch reads the same S2 levers.
                ModUnderTest.call(la, "setSectionLeversEarly", new Class<?>[]{boolean.class}, new Object[]{true});
                ModUnderTest.call(la, "setRange", new Class<?>[]{double.class}, new Object[]{REACH});
                ModUnderTest.call(la, "setCooldownMs", new Class<?>[]{int.class}, new Object[]{Integer.parseInt(System.getenv().getOrDefault("KM_LEVER_CD", "50"))}); // his instance: default 250
                Path dir = (Path) ModUnderTest.staticCall(Ap3RuntimeTests.STORE, "directory");
                Files.createDirectories(dir);
                Files.copy(route, dir.resolve(file), StandardCopyOption.REPLACE_EXISTING);
                ModUnderTest.staticCall(Ap3RuntimeTests.STORE, "select", new Class<?>[]{String.class}, new Object[]{file});
                ModUnderTest.staticCall(Ap3RuntimeTests.STORE, "reload");
                java.lang.reflect.Method set = Class.forName("com.killer560.hub.roomsim.SimSpeed")
                        .getDeclaredMethod("set", Minecraft.class, int.class);
                set.setAccessible(true);
                set.invoke(null, mc, 600);
                ModUnderTest.staticCall("com.killer560.hub.roomsim.SimItems", "give",
                        new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, "SUPERBOOM_TNT"});
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        ctx.waitTicks(10);
        String chosen = ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.call(
                ModUnderTest.config(Ap3RuntimeTests.CONFIG), "getChainsFile", new Class<?>[]{}, new Object[]{})));
        int boomSlot = ctx.computeOnClient(mc -> {
            for (int i = 0; i < 9; i++) {
                if (mc.player.getInventory().getItem(i).is(net.minecraft.world.item.Items.TNT)) {
                    return i;
                }
            }
            return -1;
        });
        boolean[] lev0 = ctx.computeOnClient(mc -> new boolean[]{powered(mc, LOW), powered(mc, HIGH)});
        // Picks that are really there (not air) at the start.
        List<BlockPos> live = ctx.computeOnClient(mc -> {
            List<BlockPos> l = new ArrayList<>();
            for (BlockPos p : pickList) {
                if (!mc.level.getBlockState(p).isAir()) {
                    l.add(p);
                }
            }
            return l;
        });
        say(tag + ": file " + chosen + ", " + pickList.size() + " picks (" + live.size() + " solid), boom "
                + hasBoom + ", Superboom in slot " + boomSlot + ", levers low/high powered at start " + lev0[0] + "/" + lev0[1]);

        long mark = LogTap.mark();
        SimBossTests.tp(ctx, at[0], at[1], at[2], (float) at[3], 0f);
        int charges = MAX_CHARGES;
        int rc = 0;
        int minCharges = MAX_CHARGES;
        Set<BlockPos> broken = new LinkedHashSet<>();
        List<String> shorts = new ArrayList<>();
        int t0 = -1;
        int arrive = -1;
        int aligned = -1;
        int lowAt = -1;
        int highAt = -1;
        int boomAt = -1;
        int archBreach = -1;
        double maxY = -1;
        for (int t = 0; t < 400; t++) {
            ctx.waitTick();
            final int tick = t;
            double[] s = ctx.computeOnClient(mc -> {
                Vec3 p = mc.player.position();
                Vec3 v = mc.player.getDeltaMovement();
                return new double[]{p.x, p.y, p.z, Math.hypot(v.x, v.z), v.y, mc.player.onGround() ? 1 : 0,
                        powered(mc, LOW) ? 1 : 0, powered(mc, HIGH) ? 1 : 0,
                        (Boolean) ModUnderTest.staticCall(Ap3RuntimeTests.EXEC, "isRunning") ? 1 : 0, mc.player.getYRot()};
            });
            Vec3 pos = new Vec3(s[0], s[1], s[2]);
            Vec3 eye = pos.add(0, 1.62, 0);
            if (t0 < 0 && Math.hypot(s[0] - at[0], s[2] - at[2]) > 0.2 && t > 2) {
                t0 = t;
            }
            if (lowAt < 0 && s[6] > 0) {
                lowAt = t;
            }
            if (highAt < 0 && s[7] > 0) {
                highAt = t;
            }
            if (boomAt < 0 && hasBoom) {
                for (String l : LogTap.since(mark)) {
                    if (l.contains("[CHAT]") && l.contains("The gate has been destroyed!")) {
                        boomAt = t;
                        break;
                    }
                }
            }
            AABB body = new AABB(s[0] - 0.3, s[1], s[2] - 0.3, s[0] + 0.3, s[1] + 1.8, s[2] + 0.3);
            if (archBreach < 0 && body.intersects(ARCH) && (boomAt < 0 || t < boomAt + 2)) {
                archBreach = t;
            }
            // The Breaker model: recharge, then every pick in reach breaks this tick.
            if (charges < MAX_CHARGES && ++rc >= RECHARGE_TICKS) {
                rc = 0;
                charges++;
            }
            List<BlockPos> now = new ArrayList<>();
            for (BlockPos p : live) {
                if (!broken.contains(p) && boxDistSq(eye, p) <= REACH * REACH) {
                    if (charges > 0) {
                        charges--;
                        broken.add(p);
                        now.add(p);
                    } else {
                        shorts.add("t" + t + " " + p.toShortString());
                    }
                }
            }
            minCharges = Math.min(minCharges, charges);
            if (!now.isEmpty()) {
                ctx.runOnClient(mc -> {
                    for (BlockPos p : now) {
                        mc.level.setBlock(p, Blocks.AIR.defaultBlockState(), 11);
                    }
                    var srv = mc.getSingleplayerServer();
                    srv.execute(() -> {
                        var lvl = srv.getPlayerList().getPlayers().get(0).level();
                        for (BlockPos p : now) {
                            lvl.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                        }
                    });
                });
                StringBuilder b = new StringBuilder();
                for (BlockPos p : now) {
                    b.append(' ').append(p.toShortString().replace(" ", ""));
                }
                say(String.format(Locale.ROOT, "%s BREAK t%d (rel %d) %d block(s), %d charge(s) left:%s", tag, t,
                        t0 < 0 ? -1 : t - t0, now.size(), charges, b));
            }
            say(String.format(Locale.ROOT, "%s t%d rel %d pos %.2f %.2f %.2f h %.3f vy %.3f %s yaw %.1f run %s lev %d%d",
                    tag, t, t0 < 0 ? -1 : t - tick + (tick - t0), s[0], s[1], s[2], s[3], s[4], s[5] > 0 ? "G" : "A", s[9],
                    s[8] > 0, (int) s[6], (int) s[7]));
            boolean inEe3 = Math.abs(s[0] - 2.5) <= 1.0 && Math.abs(s[2] - 105.5) <= 1.0 && Math.abs(s[1] - 109) <= 1.5;
            if (arrive < 0 && inEe3 && s[5] > 0) {
                arrive = t;
            }
            if (arrive >= 0 && aligned < 0 && Math.hypot(s[0] - 2.5, s[2] - 105.5) < 0.1 && s[3] < 0.01) {
                aligned = t;
            }
            if (aligned >= 0 && t > aligned + 5 || arrive >= 0 && t > arrive + 40) {
                break;
            }
            if (s[1] < 107 && !his) {
                break; // in the lava: off the line
            }
        }
        ctx.waitTicks(5);
        boolean[] lev = ctx.computeOnClient(mc -> new boolean[]{powered(mc, LOW), powered(mc, HIGH)});
        int arch = hasBoom ? SimBossTests.gateCells(ctx, 2)[0] : -1;
        say(String.format(Locale.ROOT, "RESULT %s: HEE2->EE3 arrive %d ticks, aligned %d ticks (start t%d); levers low t%d high t%d"
                        + " (now %s/%s); boom t%d, arch cells left %d, arch entered early t%d; picks broken %d of %d live, min charges %d,"
                        + " short %d%s",
                tag, arrive < 0 || t0 < 0 ? -1 : arrive - t0, aligned < 0 || t0 < 0 ? -1 : aligned - t0, t0,
                lowAt < 0 ? -1 : lowAt - t0, highAt < 0 ? -1 : highAt - t0, lev[0], lev[1], boomAt < 0 ? -1 : boomAt - t0, arch,
                archBreach, broken.size(), live.size(), minCharges, shorts.size(),
                shorts.isEmpty() ? "" : " first " + String.join(", ", shorts.subList(0, Math.min(6, shorts.size())))));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(Ap3RuntimeTests.EXEC, "stop", new Class<?>[]{String.class},
                new Object[]{"test over"}));
        if (his || seg) {
            return;
        }
        if (t0 < 0) {
            f.add(tag + ": AP3 never moved him off HEE2");
        }
        if (arrive < 0) {
            f.add(tag + ": never arrived at EE3");
        }
        if (!lev[0] || !lev[1]) {
            f.add(tag + ": levers low/high powered " + lev[0] + "/" + lev[1]);
        }
        if (hasBoom && (boomAt < 0 || arch != 0)) {
            f.add(tag + ": gate 2 not blown (chat t" + boomAt + ", arch cells " + arch + ")");
        }
        if (archBreach >= 0) {
            f.add(tag + ": his body entered the arch at t" + archBreach + " before the server removed it");
        }
        // A pick in reach with no charge is the charge wait, reported in the RESULT line, not a fault.
        if (live.size() > 0 && broken.isEmpty()) {
            f.add(tag + ": no pick was broken (the Breaker model never acted)");
        }
    }
}

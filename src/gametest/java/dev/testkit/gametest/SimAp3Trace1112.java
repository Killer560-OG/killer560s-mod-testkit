package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
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
 * 1112-boss-ap3trace (ap3finder-1112): the ground truth the offline AP3 route finder's model is checked against. Reads
 * {@code killer560s-mod-logs/ap3finder-1112/<KM_TRACE_DIR, default tr>/jobs.txt}; each line is one job, run from a fresh
 * S2 boss start at speed 600:
 * <ul>
 *   <li>{@code dump NAME [dig] [box x1,y1,z1,x2,y2,z2] [sec S1]}: every non-air block's collision boxes in the region (default EE3's) to NAME.world (after the dig).</li>
 *   <li>{@code route NAME [dig] [strafe 0|1]}: runs NAME.json with AP3 (Lever Aura on at 4.5, Breaker modeled from
 *   NAME.picks as in 1110-boss-ap3brute) and records every tick to NAME.trace.</li>
 *   <li>{@code inputs NAME [dig]}: AP3 off, replays NAME.in (line 1 "start x y z yaw pitch", then one "keys yaw pitch" a tick,
 *   keys from wasdjcr: j jump, c sneak, r sprint, - none) and records every tick to NAME.trace.</li>
 * </ul>
 * dig names a file of x,y,z lines set to air right after the start (blocks he dug by hand). A trace line is
 * "T t x y z vx vy vz G|A yRotO xRotO xxa zza keys sprinting hcol low high" with the state AFTER tick t and the inputs the
 * tick moved with; "S" is the state before tick 0; "BREAK t x,y,z..." and "BOOM t" are events.
 */
final class SimAp3Trace1112 {
    static final String NAME = "1112-boss-ap3trace";
    private static final Path DIR = Path.of(System.getProperty("user.home"), "killer560s-mod-logs", "ap3finder-1112",
            System.getenv().getOrDefault("KM_TRACE_DIR", "tr"));
    private static final BlockPos LOW = new BlockPos(27, 124, 127);
    private static final BlockPos HIGH = new BlockPos(23, 132, 138);
    private static final double REACH = 4.5;

    private SimAp3Trace1112() {
    }

    static void run(ClientGameTestContext ctx, List<String> f) throws Exception {
        Path jobs = DIR.resolve("jobs.txt");
        if (!Files.isRegularFile(jobs)) {
            say("SKIPPED: no " + jobs);
            return;
        }
        List<String[]> plan = new ArrayList<>();
        for (String l : Files.readAllLines(jobs)) {
            l = l.strip();
            if (!l.isEmpty() && !l.startsWith("#")) {
                plan.add(l.split("\\s+"));
            }
        }
        boolean strafeWas = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.call(ModUnderTest.config(Ap3RuntimeTests.CONFIG),
                "isStrafe45", new Class<?>[]{}, new Object[]{}));
        try {
            SimBossTests.withCapture(f, () -> {
                // consecutive "inputs" jobs with the same dig share one boss start (each start costs ~15 s)
                String shared = null;
                for (int i = 0; i < plan.size(); i++) {
                    String[] p = plan.get(i);
                    boolean keep = false;
                    try {
                        switch (p[0]) {
                            case "dump" -> dump(ctx, p);
                            case "route" -> {
                                route(ctx, f, p);
                                // hold N: leave the player where the route ended for N seconds (someone is watching)
                                int hold = Integer.parseInt(opt(p, "hold", "0"));
                                if (hold > 0) {
                                    say("holding at the route end for " + hold + " s");
                                    ctx.waitTicks(hold * 20);
                                }
                            }
                            case "inputs" -> {
                                String dig = opt(p, "dig", "-");
                                inputs(ctx, f, p, !dig.equals(shared));
                                shared = dig;
                                keep = i + 1 < plan.size() && plan.get(i + 1)[0].equals("inputs")
                                        && dig.equals(opt(plan.get(i + 1), "dig", "-"));
                            }
                            default -> f.add("unknown job " + String.join(" ", p));
                        }
                    } catch (Exception e) {
                        f.add(String.join(" ", p) + " threw: " + e);
                        e.printStackTrace(System.out);
                    } finally {
                        if (!keep) {
                            shared = null;
                            SimBossTests.cleanup(ctx);
                        }
                    }
                }
            });
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(Ap3RuntimeTests.CONFIG), "setStrafe45", strafeWas));
        }
    }

    private static String opt(String[] p, String key, String def) {
        for (int i = 2; i + 1 < p.length; i++) {
            if (p[i].equals(key)) {
                return p[i + 1];
            }
        }
        return def;
    }

    private static List<BlockPos> blocks(Path file) throws Exception {
        List<BlockPos> out = new ArrayList<>();
        if (file == null || !Files.isRegularFile(file)) {
            return out;
        }
        for (String l : Files.readAllLines(file)) {
            l = l.strip();
            if (!l.isEmpty() && !l.startsWith("#")) {
                String[] c = l.split("[,\\s]+");
                out.add(new BlockPos(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2])));
            }
        }
        return out;
    }

    /** Fresh S2 start at speed 600, with the dig file's blocks set to air on both sides. */
    private static void begin(ClientGameTestContext ctx, String[] p) throws Exception {
        String dig = opt(p, "dig", null);
        List<BlockPos> air = blocks(dig == null ? null : DIR.resolve(dig));
        SimBossTests.start(ctx, opt(p, "sec", "S2"), true);
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> {
            try {
                java.lang.reflect.Method set = Class.forName("com.killer560.hub.roomsim.SimSpeed")
                        .getDeclaredMethod("set", Minecraft.class, int.class);
                set.setAccessible(true);
                set.invoke(null, mc, 600);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        if (!air.isEmpty()) {
            setAir(ctx, air);
        }
        ctx.waitTicks(5);
    }

    private static void setAir(ClientGameTestContext ctx, List<BlockPos> now) {
        ctx.runOnClient(mc -> {
            for (BlockPos q : now) {
                mc.level.setBlock(q, Blocks.AIR.defaultBlockState(), 11);
            }
            var srv = mc.getSingleplayerServer();
            srv.execute(() -> {
                var lvl = srv.getPlayerList().getPlayers().get(0).level();
                for (BlockPos q : now) {
                    lvl.setBlock(q, Blocks.AIR.defaultBlockState(), 3);
                }
            });
        });
    }

    private static void dump(ClientGameTestContext ctx, String[] p) throws Exception {
        begin(ctx, p);
        int[] bx = {-6, 100, 94, 67, 146, 152};
        String box = opt(p, "box", null);
        if (box != null) {
            String[] c = box.split(",");
            for (int i = 0; i < 6; i++) {
                bx[i] = Integer.parseInt(c[i]);
            }
        }
        int x1 = bx[0], y1 = bx[1], z1 = bx[2], x2 = bx[3], y2 = bx[4], z2 = bx[5];
        List<String> out = ctx.computeOnClient(mc -> {
            List<String> l = new ArrayList<>();
            var lvl = mc.level;
            BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
            for (int x = x1; x <= x2; x++) {
                for (int y = y1; y <= y2; y++) {
                    for (int z = z1; z <= z2; z++) {
                        q.set(x, y, z);
                        BlockState st = lvl.getBlockState(q);
                        boolean lava = lvl.getFluidState(q).is(FluidTags.LAVA);
                        if (st.isAir() && !lava) {
                            continue;
                        }
                        StringBuilder b = new StringBuilder();
                        b.append(x).append(' ').append(y).append(' ').append(z).append(' ')
                                .append(lava ? 'L' : '-').append(st.getDestroySpeed(lvl, q) < 0 ? 'U' : 'B').append(' ')
                                .append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath());
                        for (AABB a : st.getCollisionShape(lvl, q).toAabbs()) {
                            b.append(String.format(Locale.ROOT, " %s,%s,%s,%s,%s,%s", a.minX, a.minY, a.minZ, a.maxX, a.maxY, a.maxZ));
                        }
                        // friction and speed factor of the block (what the model needs to stand on it)
                        b.append(String.format(Locale.ROOT, " f=%s sf=%s jf=%s", st.getBlock().getFriction(),
                                st.getBlock().getSpeedFactor(), st.getBlock().getJumpFactor()));
                        if (st.getCollisionShape(lvl, q).isEmpty() && !st.getShape(lvl, q).isEmpty()) {
                            for (AABB a : st.getShape(lvl, q).toAabbs()) {
                                b.append(String.format(Locale.ROOT, " o=%s,%s,%s,%s,%s,%s", a.minX, a.minY, a.minZ, a.maxX, a.maxY, a.maxZ));
                            }
                        }
                        l.add(b.toString());
                    }
                }
            }
            return l;
        });
        Path o = DIR.resolve(p[1] + ".world");
        Files.write(o, out);
        say("dumped " + out.size() + " cells to " + o);
    }

    private static boolean powered(Minecraft mc, BlockPos p) {
        BlockState st = mc.level.getBlockState(p);
        return st.getBlock() instanceof LeverBlock && st.getValue(LeverBlock.POWERED);
    }

    private static double boxDistSq(Vec3 e, BlockPos p) {
        double cx = Math.max(p.getX(), Math.min(e.x, p.getX() + 1));
        double cy = Math.max(p.getY(), Math.min(e.y, p.getY() + 1));
        double cz = Math.max(p.getZ(), Math.min(e.z, p.getZ() + 1));
        return (e.x - cx) * (e.x - cx) + (e.y - cy) * (e.y - cy) + (e.z - cz) * (e.z - cz);
    }

    /** LocalPlayer.sprintTriggerTime (private): above 0 a forward press that follows a tick with no forward impulse starts a sprint with no key. */
    private static int triggerTime(LocalPlayer pl) {
        try {
            java.lang.reflect.Field fl = LocalPlayer.class.getDeclaredField("sprintTriggerTime");
            fl.setAccessible(true);
            return fl.getInt(pl);
        } catch (ReflectiveOperationException e) {
            return -99;
        }
    }

    private static String keys(Input in) {
        StringBuilder b = new StringBuilder();
        if (in.forward()) b.append('w');
        if (in.left()) b.append('a');
        if (in.backward()) b.append('s');
        if (in.right()) b.append('d');
        if (in.jump()) b.append('j');
        if (in.shift()) b.append('c');
        if (in.sprint()) b.append('r');
        return b.isEmpty() ? "-" : b.toString();
    }

    /** One trace row: the state after the tick and the inputs it moved with (yRotO = the yaw the tick moved along). */
    private static String row(Minecraft mc, String head) {
        LocalPlayer pl = mc.player;
        Vec3 p = pl.position();
        Vec3 v = pl.getDeltaMovement();
        return String.format(Locale.ROOT, "%s %.17g %.17g %.17g %.17g %.17g %.17g %s %.9g %.9g %.9g %.9g %s %s %s %d%d spd=%.9g yr=%.9g hfi=%s stt=%d mh=%s",
                head, p.x, p.y, p.z, v.x, v.y, v.z, pl.onGround() ? "G" : "A", pl.yRotO, pl.xRotO, pl.xxa, pl.zza,
                keys(pl.input.keyPresses), pl.isSprinting() ? "S" : "-", pl.horizontalCollision ? "H" : "-",
                powered(mc, LOW) ? 1 : 0, powered(mc, HIGH) ? 1 : 0, pl.getAttributeValue(Attributes.MOVEMENT_SPEED), pl.getYRot(),
                pl.input.hasForwardImpulse() ? "1" : "0", triggerTime(pl), pl.minorHorizontalCollision ? "M" : "-");
    }

    private static void route(ClientGameTestContext ctx, List<String> f, String[] p) throws Exception {
        String name = p[1];
        Path route = DIR.resolve(name + ".json");
        boolean strafe = !"0".equals(opt(p, "strafe", "1"));
        double[] at = {60.5, 132, 139, 117, 46.3};
        String st = opt(p, "start", null);
        if (st != null) {
            String[] c = st.split(",");
            for (int i = 0; i < c.length; i++) {
                at[i] = Double.parseDouble(c[i]);
            }
        }
        boolean hasBoom = Files.readString(route).contains("\"BOOM\"");
        List<BlockPos> pickList = blocks(DIR.resolve(name + ".picks"));
        begin(ctx, p);
        String file = "trace-" + name + ".json";
        ctx.runOnClient(mc -> {
            try {
                Object cfg = ModUnderTest.config(Ap3RuntimeTests.CONFIG);
                ModUnderTest.set(cfg, "setStrafe45", strafe);
                ModUnderTest.set(cfg, "setEnabled", false); // on only after the tp: the boss start sits on the start node
                ModUnderTest.staticCall(Ap3RuntimeTests.FEATURE, "setForceDungeon", new Class<?>[]{boolean.class}, new Object[]{false});
                Object cu = ModUnderTest.staticCall("com.killer560.hub.cheatutils.CheatUtilsConfig", "getInstance");
                ModUnderTest.call(cu, "setAuraBossLevers", new Class<?>[]{boolean.class}, new Object[]{true});
                Object la = ModUnderTest.staticCall("com.killer560.hub.leveraura.LeverAuraConfig", "getInstance");
                ModUnderTest.call(la, "setSectionLevers", new Class<?>[]{boolean.class}, new Object[]{true});
                ModUnderTest.call(la, "setSectionLeversEarly", new Class<?>[]{boolean.class}, new Object[]{true});
                ModUnderTest.call(la, "setRange", new Class<?>[]{double.class}, new Object[]{REACH});
                ModUnderTest.call(la, "setCooldownMs", new Class<?>[]{int.class}, new Object[]{250});
                Path dir = (Path) ModUnderTest.staticCall(Ap3RuntimeTests.STORE, "directory");
                Files.createDirectories(dir);
                Files.copy(route, dir.resolve(file), StandardCopyOption.REPLACE_EXISTING);
                ModUnderTest.staticCall(Ap3RuntimeTests.STORE, "select", new Class<?>[]{String.class}, new Object[]{file});
                ModUnderTest.staticCall(Ap3RuntimeTests.STORE, "reload");
                ModUnderTest.staticCall("com.killer560.hub.roomsim.SimItems", "give",
                        new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, "SUPERBOOM_TNT"});
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        ctx.waitTicks(10);
        List<BlockPos> live = ctx.computeOnClient(mc -> {
            List<BlockPos> l = new ArrayList<>();
            for (BlockPos q : pickList) {
                if (!mc.level.getBlockState(q).isAir()) {
                    l.add(q);
                }
            }
            return l;
        });
        List<String> out = new ArrayList<>();
        out.add("# route " + name + " strafe " + strafe + " picks " + pickList.size() + " solid " + live.size());
        long mark = LogTap.mark();
        SimBossTests.command(ctx, String.format(Locale.ROOT, "tp @p %s %s %s %s %s", at[0], at[1], at[2], at[3], at[4]));
        ctx.waitTicks(3);
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(Ap3RuntimeTests.CONFIG), "setEnabled", true));
        out.add(ctx.computeOnClient(mc -> row(mc, "S -1")));
        int charges = 20, rc = 0;
        Set<BlockPos> broken = new LinkedHashSet<>();
        boolean boomed = false;
        int still = 0;
        for (int t = 0; t < 300; t++) {
            ctx.waitTick();
            final int tt = t;
            Object[] s = ctx.computeOnClient(mc -> new Object[]{row(mc, "T " + tt), mc.player.position(),
                    (Boolean) ModUnderTest.staticCall(Ap3RuntimeTests.EXEC, "isRunning")});
            out.add((String) s[0]);
            Vec3 pos = (Vec3) s[1];
            Vec3 eye = pos.add(0, 1.62, 0);
            if (!boomed && hasBoom) {
                for (String l : LogTap.since(mark)) {
                    if (l.contains("[CHAT]") && l.contains("The gate has been destroyed!")) {
                        boomed = true;
                        out.add("BOOM " + t);
                        break;
                    }
                }
            }
            if (charges < 20 && ++rc >= 10) {
                rc = 0;
                charges++;
            }
            List<BlockPos> now = new ArrayList<>();
            live.sort(java.util.Comparator.comparingDouble(q -> boxDistSq(eye, q)));
            for (BlockPos q : live) {
                if (!broken.contains(q) && boxDistSq(eye, q) <= REACH * REACH && charges > 0) {
                    charges--;
                    broken.add(q);
                    now.add(q);
                }
            }
            if (!now.isEmpty()) {
                setAir(ctx, now);
                StringBuilder b = new StringBuilder("BREAK " + t);
                for (BlockPos q : now) {
                    b.append(' ').append(q.getX()).append(',').append(q.getY()).append(',').append(q.getZ());
                }
                out.add(b.toString());
            }
            if (pos.y < 107 || !(Boolean) s[2] && ++still > 30) {
                break;
            }
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(Ap3RuntimeTests.EXEC, "stop", new Class<?>[]{String.class},
                new Object[]{"test over"}));
        ctx.waitTicks(5);
        int arch = hasBoom ? SimBossTests.gateCells(ctx, 2)[0] : -1;
        out.add("END arch " + arch + " " + ctx.computeOnClient(mc -> row(mc, "F")));
        Files.write(DIR.resolve(name + ".trace"), out);
        say("route " + name + ": " + out.size() + " lines");
    }

    private static void inputs(ClientGameTestContext ctx, List<String> f, String[] p, boolean fresh) throws Exception {
        String name = p[1];
        List<String> in = Files.readAllLines(DIR.resolve(name + ".in"));
        String[] s0 = in.get(0).strip().split("\\s+");
        if (fresh) {
            begin(ctx, p);
        }
        ctx.runOnClient(mc -> {
            ModUnderTest.set(ModUnderTest.config(Ap3RuntimeTests.CONFIG), "setEnabled", false);
            Object cu = ModUnderTest.staticCall("com.killer560.hub.cheatutils.CheatUtilsConfig", "getInstance");
            ModUnderTest.call(cu, "setAuraBossLevers", new Class<?>[]{boolean.class}, new Object[]{false});
        });
        SimBossTests.command(ctx, String.format(Locale.ROOT, "tp @p %s %s %s %s %s", s0[1], s0[2], s0[3], s0[4], s0[5]));
        ctx.waitTicks(10);
        List<String> out = new ArrayList<>();
        out.add("# inputs " + name);
        out.add(ctx.computeOnClient(mc -> row(mc, "S -1")));
        try {
            for (int t = 0; t + 1 < in.size(); t++) {
                String[] w = in.get(t + 1).strip().split("\\s+");
                String k = w[0];
                float yaw = Float.parseFloat(w[1]);
                float pitch = Float.parseFloat(w[2]);
                ctx.runOnClient(mc -> {
                    var o = mc.options;
                    o.keyUp.setDown(k.indexOf('w') >= 0);
                    o.keyLeft.setDown(k.indexOf('a') >= 0);
                    o.keyDown.setDown(k.indexOf('s') >= 0);
                    o.keyRight.setDown(k.indexOf('d') >= 0);
                    o.keyJump.setDown(k.indexOf('j') >= 0);
                    o.keyShift.setDown(k.indexOf('c') >= 0);
                    o.keySprint.setDown(k.indexOf('r') >= 0);
                    mc.player.setYRot(yaw);
                    mc.player.setXRot(pitch);
                });
                ctx.waitTick();
                final int tt = t;
                String r = ctx.computeOnClient(mc -> row(mc, "T " + tt));
                out.add(r);
                if (ctx.computeOnClient(mc -> mc.player.getY() < 107.5)) {
                    break;
                }
            }
        } finally {
            ctx.runOnClient(mc -> {
                var o = mc.options;
                for (var km : new net.minecraft.client.KeyMapping[]{o.keyUp, o.keyLeft, o.keyDown, o.keyRight, o.keyJump, o.keyShift, o.keySprint}) {
                    km.setDown(false);
                }
            });
        }
        Files.write(DIR.resolve(name + ".trace"), out);
        say("inputs " + name + ": " + out.size() + " lines");
    }

    private static void say(String line) {
        System.out.println("[" + NAME + "] " + line);
    }
}

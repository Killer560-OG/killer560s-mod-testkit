package dev.testkit.gametest.boss;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.PacketTrace;
import dev.testkit.harness.WireCapture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 1109-boss-ee3route-grim (ee3route-1109): the HEE2 -> EE3 route file {@code fastest.json} on GrimAC. The stretch's map
 * (corridor, S2 pit, low-lever platform, Breaker tunnel, walkway to EE3) is rebuilt from his P3 capture as /fill commands
 * ({@code killer560s-mod-logs/ee3route-1109/fills-env.txt}, written by fills.py there; full cubes as stone). Lever Aura
 * flicks both S2 levers (with their "Not Activated" stands) while AP3 holds the walk (leverap3-1109: the off-walk look),
 * the BOOM node sends its Superboom tap and the server then clears gate 2's arch cells (SimBossTests DOOR_BOX 2), as Hypixel would; GrimAC must stay
 * silent (Session.test) on the default and experimental configs.
 * <ul>
 *   <li>1109-boss-ee3route-grim: the route's Breaker picks cleared before the run.</li>
 *   <li>1109-boss-ee3route-same-&lt;use|swap|dig&gt; (SAME-TICK GRIM CASES): his own right click, hotbar swap or dig goes
 *       into the route's ticks ({@link SameTick}); use and swap must share a server tick with a lever click, a queued dig
 *       must hold the lever click to another tick.</li>
 * </ul>
 */
final class Ee3RouteGrimCases {

    private static final Path DIR = Path.of(System.getProperty("user.home"), "killer560s-mod-logs", "ee3route-1109");
    private static final String ROUTE = System.getProperty("testkit.ee3.route", "fastest");
    private static final BlockPos LOW = new BlockPos(27, 124, 127);
    private static final BlockPos HIGH = new BlockPos(23, 132, 138);
    private static final String CU = "cheatutils.CheatUtilsConfig";
    private static final String LA = "leveraura.LeverAuraConfig";
    private static final Predicate<Packet<?>> LEVER_CLICK = p -> p instanceof ServerboundUseItemOnPacket u
            && (hitPos(u).equals(LOW) || hitPos(u).equals(HIGH));
    /** The clicked block (26.3's record accessor is hitResult(), 26.2's getHitResult()). */
    private static final java.lang.reflect.Method HIT = hitMethod();

    private static java.lang.reflect.Method hitMethod() {
        for (String n : new String[]{"hitResult", "getHitResult"}) {
            try {
                return ServerboundUseItemOnPacket.class.getMethod(n);
            } catch (NoSuchMethodException e) {
                // the other name
            }
        }
        throw new AssertionError("no hit result accessor on ServerboundUseItemOnPacket");
    }

    private static BlockPos hitPos(ServerboundUseItemOnPacket u) {
        try {
            return ((net.minecraft.world.phys.BlockHitResult) HIT.invoke(u)).getBlockPos();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private Ee3RouteGrimCases() {
    }

    static void register(Session s) {
        if (!Mod.isCheat()) {
            return;
        }
        s.test("1109-boss-ee3route-grim", c -> run(c, null));
        for (SameTick.Act a : new SameTick.Act[]{SameTick.Act.USE, SameTick.Act.SWAP, SameTick.Act.DIG}) {
            s.test("1109-boss-ee3route-same-" + a.name().toLowerCase(Locale.ROOT), c -> run(c, a));
        }
    }

    private static boolean powered(net.minecraft.client.Minecraft mc, BlockPos p) {
        BlockState st = mc.level.getBlockState(p);
        return st.getBlock() instanceof LeverBlock && st.getValue(LeverBlock.POWERED);
    }

    /** GrimAC's ticks on the wire (a movement packet, or client_tick_end with none before it): lever clicks and digs. */
    private static final class Ticks implements Consumer<Packet<?>> {
        final Consumer<Packet<?>> prev = PacketTrace.tap;
        int lever;
        int dig;
        int twoLevers;
        int leverAndDig;
        int curLever;
        int curDig;
        boolean moved;

        @Override
        public synchronized void accept(Packet<?> p) {
            if (prev != null) {
                prev.accept(p);
            }
            if (LEVER_CLICK.test(p)) {
                lever++;
                curLever++;
            } else if (p instanceof ServerboundPlayerActionPacket a
                    && a.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
                dig++;
                curDig++;
            }
            boolean end = p instanceof ServerboundMovePlayerPacket || (p instanceof ServerboundClientTickEndPacket && !moved);
            if (p instanceof ServerboundMovePlayerPacket) {
                moved = true;
            } else if (p instanceof ServerboundClientTickEndPacket) {
                moved = false;
            }
            if (end) {
                twoLevers += curLever > 1 ? 1 : 0;
                leverAndDig += curLever > 0 && curDig > 0 ? 1 : 0;
                curLever = 0;
                curDig = 0;
            }
        }

        synchronized String report() {
            return "lever clicks " + lever + ", digs " + dig + ", ticks with two lever clicks " + twoLevers
                    + ", ticks with a lever click and a dig " + leverAndDig;
        }
    }

    private static String describe(Object p) {
        if (p instanceof ServerboundPlayerActionPacket a) {
            return "dig " + a.getAction();
        }
        if (p instanceof ServerboundMovePlayerPacket m) {
            return m.hasRotation() ? "move rot" : "move";
        }
        return p.getClass().getSimpleName();
    }

    /** From the wire (encoder order): {ticks with a START dig, of those a rotating move after the dig, a move before it}. */
    private static int[] digTicks() {
        int[] out = new int[3];
        boolean dig = false;
        boolean before = false;
        boolean after = false;
        boolean moved = false;
        for (WireCapture.Sent s : WireCapture.snapshot()) {
            if (!s.phase().equals("play")) {
                continue;
            }
            String t = s.text();
            if (t.equals("dig START_DESTROY_BLOCK")) {
                dig = true;
                before |= moved;
            } else if (t.startsWith("move")) {
                moved = true;
                after |= dig && t.equals("move rot");
            } else if (t.equals("ServerboundClientTickEndPacket")) {
                if (dig) {
                    out[0]++;
                    out[1] += after ? 1 : 0;
                    out[2] += before ? 1 : 0;
                }
                dig = before = after = moved = false;
            }
        }
        return out;
    }

    static void run(Session c, SameTick.Act act) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        Path route = DIR.resolve(System.getenv().getOrDefault("KM_EE3_DIR", "candidates")).resolve(ROUTE + ".json");
        Path fills = DIR.resolve("fills-env.txt");
        c.check(Files.isRegularFile(route) && Files.isRegularFile(fills), "missing " + route + " or " + fills);
        Ticks ticks = new Ticks();
        SameTick st = null;
        try {
            c.server().command("forceload add -2 100 63 146");
            c.server().sync(30);
            // Off the map first: the fills below must not land on him where the last case left him (Simulation flags
            // charged to this case, experimental Grim, x4/x5).
            TerminalAuraCases.tp(c, 62.5, 132, 139, 72f);
            c.ctx().waitTicks(5);
            for (String l : Files.readAllLines(fills)) {
                if (!l.isBlank()) {
                    c.server().command(l);
                }
            }
            c.server().sync(120);
            for (String l : Files.readAllLines(DIR.resolve(System.getenv().getOrDefault("KM_EE3_DIR", "candidates")).resolve(ROUTE + ".picks"))) {
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
            c.server().command("effect give @p minecraft:fire_resistance 600 0 true"); // no lava knockback if he misses
            c.server().sync(30);
            TerminalAuraCases.tp(c, 62.5, 132, 139, 72f); // beside HEE2: AP3 must not start before the run (a tp under its walk is a Simulation flag)
            c.ctx().waitTicks(10);
            if (act != null) {
                SameTickCases.sticks(c);
            }
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
            LightsDeviceCases.newRun(c);
            LightsDeviceCases.openS2(c);
            undo.add(c.onClient(mc -> Mod.with(CU, "AuraBossLevers", true)));
            undo.add(c.onClient(mc -> Mod.with(LA, "SectionLevers", true)));
            undo.add(c.onClient(mc -> Mod.with(LA, "SectionLeversEarly", true)));
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
            // Judge Grim from the run on: the session join and the setup fills/tps (AP3 and Lever Aura idle) drew a lone
            // Simulation .284 in x6's first case. Print them, never hide them silently.
            List<String> setupFlags = c.scenario().flags();
            if (!setupFlags.isEmpty()) {
                System.out.println("[ee3route] setup flags before the run (not judged): " + setupFlags);
            }
            c.server().mark();
            long mark = LogTap.mark();
            c.onClient(mc -> {
                PacketTrace.tap = ticks;
                return null;
            });
            if (act != null) {
                st = SameTickCases.start(c, act, LEVER_CLICK);
            }
            int turns0 = act == SameTick.Act.DIG ? ((Number) Mod.staticCall("util.HandDigAim", "turns")).intValue() : 0;
            if (act == SameTick.Act.DIG) {
                WireCapture.describe = Ee3RouteGrimCases::describe;
                WireCapture.start();
            }
            // Leave and re-enter HEE2's box: a tp lands him in it, as a leap would.
            TerminalAuraCases.tp(c, 62.5, 132, 139, 72f);
            c.ctx().waitTicks(5);
            TerminalAuraCases.tp(c, 60.5, 132, 139, 72f);
            int t0 = -1;
            int arrive = -1;
            int boomAt = -1;
            double minY = 999;
            StringBuilder path = new StringBuilder();
            for (int t = 0; t < 400 && arrive < 0; t++) {
                c.ctx().waitTick();
                double[] s = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                        mc.player.onGround() ? 1 : 0});
                minY = Math.min(minY, s[1]);
                path.append(String.format(Locale.ROOT, " %d:%.2f,%.2f,%.2f", t, s[0], s[1], s[2]));
                if (t0 < 0 && Math.hypot(s[0] - 60.5, s[2] - 139) > 0.2) {
                    t0 = t;
                }
                if (boomAt < 0 && LogTap.since(mark).stream().anyMatch(l -> l.contains("[AP3] boom #") && l.contains("use sent"))) {
                    boomAt = t;
                    // Hypixel blows gate 2 a tick or two after the Superboom lands; nothing breaks it here on its own.
                    c.server().command("fill 16 115 127 19 136 137 minecraft:air replace minecraft:stone"); // the capture's arch is stone here
                }
                if (Math.abs(s[0] - 2.5) <= 1 && Math.abs(s[2] - 105.5) <= 1 && Math.abs(s[1] - 109) <= 1.5 && s[3] > 0) {
                    arrive = t;
                }
                if (s[1] < 107.5) {
                    // Off the route into the lava (y 106): out at once, or the burning fall draws Simulation flags that the
                    // next case is blamed for. Where this route ends in the hx world is the route's question, not this case's.
                    TerminalAuraCases.tp(c, 62.5, 132, 139, 72f);
                    break;
                }
            }
            System.out.println("[ee3route] path " + (act == null ? "none" : act) + ":" + path);
            c.ctx().waitTicks(20);
            boolean[] lev = c.onClient(mc -> new boolean[]{powered(mc, LOW), powered(mc, HIGH)});
            String wire = ticks.report();
            c.note(String.format(Locale.ROOT, "route %s: left HEE2 t%d, at EE3 t%d (%d ticks), levers low/high %s/%s, boom t%d,"
                            + " lowest y %.2f; wire: %s", ROUTE, t0, arrive, arrive < 0 || t0 < 0 ? -1 : arrive - t0, lev[0], lev[1],
                    boomAt < 0 || t0 < 0 ? -1 : boomAt - t0, minY, wire));
            if (st != null) {
                SameTick s2 = st;
                st = null;
                SameTickCases.judge(c, s2, "ee3 route levers");
            }
            c.check(lev[0] && lev[1], "levers low/high powered " + lev[0] + "/" + lev[1]);
            c.check(ticks.lever >= 2, "fewer than two lever clicks on the wire: " + wire);
            c.check(ticks.twoLevers == 0, "two lever clicks in one server tick: " + wire);
            if (act == null) {
                // With his action in every tick the route's own end is not this case's question: a right click every tick
                // holds the Superboom tap (ActionGate), a swap every tick takes the TNT out of his hand.
                c.check(boomAt >= 0, "the BOOM node never sent its tap");
            }
            c.check(ticks.leverAndDig == 0, "a lever click and a dig in one server tick: " + wire);
            if (act == SameTick.Act.DIG) {
                // ap3digrot-1109: his dig under AP3's held camera turns the body onto his block, and the movement packet
                // carrying that look follows the dig in the same tick (GrimAC RotationBreak lends a dig that packet's look).
                WireCapture.stop();
                int turned = ((Number) Mod.staticCall("util.HandDigAim", "turns")).intValue() - turns0;
                int[] w = digTicks();
                c.note("hand-dig turns " + turned + "; dig ticks " + w[0] + ", with a turned move after the dig " + w[1]
                        + ", with a move before it " + w[2]);
                c.check(turned > 0, "his digs under the AP3 walk were never turned for (HandDigAim did not act)");
                c.check(w[0] > 0 && w[2] == 0, "a dig went after its tick's movement packet: " + w[2] + " of " + w[0]);
            }
        } finally {
            if (st != null) {
                SameTick s2 = st;
                c.onClient(mc -> {
                    s2.close();
                    return null;
                });
            }
            WireCapture.stop();
            WireCapture.describe = null;
            c.onClient(mc -> {
                if (PacketTrace.tap == ticks) {
                    PacketTrace.tap = ticks.prev;
                }
                Mod.staticCall("ap3.Ap3Executor", "stop", "test over");
                return null;
            });
            for (int i = undo.size() - 1; i >= 0; i--) {
                undo.get(i).close();
            }
            c.server().command("kill @e[tag=ee3route]");
            c.server().command("attribute @p minecraft:movement_speed base set 0.1");
            c.server().command("effect clear @p minecraft:fire_resistance");
            c.server().command("forceload remove -2 100 63 146");
            c.hx().sidebarClear();
        }
    }
}

package dev.testkit.gametest.boss;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.hx.HxKit;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * featH-1109 (Odin sweep item 27): Terminator Auto Clicker and Ghost Block, both cheat-only, in the boss session's arena
 * (the Lights Device floor, z 134-144). Every case runs on GrimAC default AND on a checkout with experimental-checks on.
 * <ul>
 *   <li>1109-termclick-&lt;act&gt;: Terminator in both hotbar slots, use key held, 15 CPS (the slider's top); his own use /
 *       use-key click / attack / hotbar swap / a server teleport go into the same ticks (SameTick). A case passes only
 *       when the clicker really clicked (its own counter) and his action really went in, with GrimAC silent.</li>
 *   <li>1109-termclick-off: the same with the switch off sends nothing (the control that proves the count means something).</li>
 *   <li>1109-ghost-basic / -walk: the looked-at wall block is air on the client while the server still has it, comes back on
 *       release, and walking into the wall never passes through it (the guard).</li>
 *   <li>1109-ghost-&lt;act&gt;: his use / dig / swap in the ticks the ghosts are made.</li>
 * </ul>
 */
final class FeatHCases {

    private static final String TC = "terminatorclicker.TerminatorClickerConfig";
    private static final String TF = "terminatorclicker.TerminatorClickerFeature";
    private static final String GC = "ghostblock.GhostBlockConfig";
    private static final String GF = "ghostblock.GhostBlockFeature";
    private static final BlockPos WALL = new BlockPos(60, 134, 142);

    private FeatHCases() {
    }

    static void register(Session s) {
        if (!Mod.isCheat()) {
            return;
        }
        for (SameTick.Act a : new SameTick.Act[]{SameTick.Act.USE, SameTick.Act.USE_KEY, SameTick.Act.SWAP,
                SameTick.Act.ATTACK, SameTick.Act.TP}) {
            s.test("1109-termclick-" + a.name().toLowerCase(Locale.ROOT).replace("_", ""), c -> term(c, a, true, TERM, true));
        }
        s.test("1109-termclick-off", c -> term(c, SameTick.Act.USE, false, TERM, false));
        // 2026-11-09: every shortbow (a Juju by id, one known only by its lore line), never a plain bow.
        s.test("1109-shortbow-juju", c -> term(c, SameTick.Act.USE, true, "bow[custom_data={id:\"JUJU_SHORTBOW\"}]", true));
        s.test("1109-shortbow-juju-use-key", c -> term(c, SameTick.Act.USE_KEY, true, "bow[custom_data={id:\"JUJU_SHORTBOW\"}]", true));
        s.test("1109-shortbow-lore", c -> term(c, SameTick.Act.USE, true,
                "bow[custom_data={id:\"SOME_NEW_BOW\"},lore=['{\"text\":\"Shortbow: Instantly shoots!\"}']]", true));
        s.test("1109-shortbow-plain-bow", c -> term(c, SameTick.Act.USE, true, "bow[custom_data={id:\"RUNAANS_BOW\"}]", false));
        s.test("1109-shortbow-last-breath", c -> term(c, SameTick.Act.USE, true, "bow[custom_data={id:\"LAST_BREATH\"}]", false));
        s.test("1109-ghost-basic", c -> ghost(c, null, false, true));
        s.test("1109-ghost-walk", c -> ghost(c, null, true, true));
        s.test("1109-ghost-walk-control", c -> ghost(c, null, true, false));
        s.test("1109-walk-open", FeatHCases::walkOpen);
        for (SameTick.Act a : new SameTick.Act[]{SameTick.Act.USE, SameTick.Act.DIG, SameTick.Act.SWAP}) {
            s.test("1109-ghost-" + a.name().toLowerCase(Locale.ROOT), c -> ghost(c, a, false, true));
        }
    }

    /** The Lights floor with open air above, a wall of stone at z 142-144 when asked, him at 60.5,133,139.5 facing +z. */
    private static void arena(Session c, boolean wall, String hotbarItem) {
        c.server().command("forceload add 54 134 66 150");
        c.server().sync(20);
        c.server().command("fill 54 132 134 66 132 150 minecraft:stone");
        c.server().command("fill 54 133 134 66 139 150 minecraft:air");
        if (wall) {
            c.server().command("fill 58 133 142 62 138 144 minecraft:stone");
        }
        TerminalAuraCases.tp(c, 60.5, 133, 139.5, 0f);
        c.server().command("item replace entity @a hotbar.4 with " + hotbarItem);
        c.server().command("item replace entity @a hotbar.5 with " + hotbarItem);
        c.onClient(mc -> {
            mc.player.getInventory().setSelectedSlot(4);
            return null;
        });
        c.ctx().waitTicks(8);
    }

    private static void leave(Session c, List<AutoCloseable> undo, SameTick st) {
        c.onClient(mc -> {
            if (st != null) st.close();
            mc.options.keyUse.setDown(false);
            mc.options.keyUp.setDown(false);
            Mod.staticCall(GF, "forceHeld", (Object) null);
            BossAuraTurnCases.restore(undo);
            return null;
        });
        c.server().command("kill @e[tag=feathh]");
        c.server().command("fill 54 132 134 66 139 150 minecraft:air");
        c.server().command("forceload remove 54 134 66 150");
        c.server().command("clear @a");
        GrimTickCases.home(c);
    }

    private static int clicks(Session c) {
        return (Integer) c.onClient(mc -> Mod.staticCall(TF, "clicks"));
    }

    private static final String TERM = "bow[custom_data={id:\"TERMINATOR\"}]";

    /** {@code on}: the switch; {@code expect}: whether this held item should be clicked for. */
    private static void term(Session c, SameTick.Act act, boolean on, String item, boolean expect) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        SameTick st = null;
        try (HxKit.Settings off = GrimTickCases.gateOff(c)) {
            arena(c, false, item);
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.onClient(mc -> Mod.call(Mod.cfg(TC), "setCpsRange", 15, 15)); // the range slider's top end
            undo.add(() -> c.onClient(mc -> Mod.call(Mod.cfg(TC), "setCpsRange", 8, 8)));
            undo.add(c.onClient(mc -> Mod.with(TC, "Enabled", on)));
            if (act == SameTick.Act.ATTACK) {
                c.server().command("summon zombie 60.5 133 142.5 {NoAI:1b,Invulnerable:1b,Silent:1b,Tags:[\"feathh\"]}");
                c.waitUntil("the zombie under his crosshair", mc -> mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult, 100);
            }
            int before = clicks(c);
            st = SameTickCases.start(c, act, p -> dev.testkit.compat.McPackets.isSwing(p));
            c.onClient(mc -> {
                mc.options.keyUse.setDown(true);
                return null;
            });
            for (int i = 0; i < 80; i++) {
                if (act == SameTick.Act.TP && i % 12 == 0) {
                    c.server().command("execute as @a at @s run tp @s ~ ~ ~");
                }
                c.ctx().waitTick();
            }
            int sent = clicks(c) - before;
            SameTick done = st;
            c.onClient(mc -> {
                mc.options.keyUse.setDown(false);
                done.close();
                return null;
            });
            c.note("terminator clicker " + act + " clicks " + sent + ", " + st.report());
            if (!on || !expect) {
                c.check(sent == 0, (on ? "a non-shortbow was clicked for " : "switch off but the clicker clicked ") + sent + " times");
            } else {
                c.check(sent >= 10, "the Shortbow Auto Clicker barely acted: " + sent + " clicks in 80 ticks");
                if (act != SameTick.Act.TP) {
                    c.check(!st.report().contains("his action x0,"), "his own action never went in (nothing tested)");
                }
            }
            st = null;
        } finally {
            leave(c, undo, st);
        }
    }

    /** Control: the same walk with no wall and no feature - does the arena walk itself draw a flag? */
    private static void walkOpen(Session c) throws Exception {
        try (HxKit.Settings off = GrimTickCases.gateOff(c)) {
            arena(c, false, "stick");
            c.ctx().waitTicks(60);
            c.onClient(mc -> {
                mc.options.keyUp.setDown(true);
                return null;
            });
            c.ctx().waitTicks(20);
            c.note("open walk ended at z=" + c.onClient(mc -> mc.player.getZ()));
        } finally {
            leave(c, new ArrayList<>(), null);
        }
    }

    private static void ghost(Session c, SameTick.Act act, boolean walk, boolean held) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        SameTick st = null;
        try (HxKit.Settings off = GrimTickCases.gateOff(c)) {
            arena(c, true, "stick");
            if (walk) {
                // The arena fill and teleport draw a Simulation .15 on the first steps (1109-walk-open); settle first.
                c.ctx().waitTicks(60);
            }
            undo.add(c.onClient(mc -> Mod.with(GC, "Enabled", true)));
            if (act != null) {
                st = SameTickCases.start(c, act, p -> false);
            }
            c.onClient(mc -> {
                Mod.staticCall(GF, "forceHeld", held);
                return null;
            });
            if (held) {
                c.waitUntil("the wall block ghosted on the client", mc -> mc.level.getBlockState(WALL).isAir(), 60);
                c.check((Integer) c.onClient(mc -> Mod.staticCall(GF, "count")) >= 1, "no ghost counted");
            }
            if (walk) {
                c.onClient(mc -> {
                    mc.options.keyUp.setDown(true);
                    return null;
                });
                c.ctx().waitTicks(60);
                double z = c.onClient(mc -> mc.player.getZ());
                c.note("ghost walk ended at z=" + z + ", wall block " + c.onClient(mc -> mc.level.getBlockState(WALL)));
                c.check(z < 142.0, "walked into the wall, z=" + z);
                c.check(c.onClient(mc -> mc.level.getBlockState(WALL).is(Blocks.STONE)), "the block next to him did not come back");
            } else if (act != null) {
                // Let go and hold again every 4 ticks, so the ghosts are made afresh while his action keeps going in.
                for (int i = 0; i < 12; i++) {
                    boolean h = i % 2 == 0;
                    c.onClient(mc -> {
                        Mod.staticCall(GF, "forceHeld", h);
                        return null;
                    });
                    c.ctx().waitTicks(4);
                }
            } else {
                c.ctx().waitTicks(40);
            }
            if (!walk && act == null) {
                c.onClient(mc -> {
                    Mod.staticCall(GF, "forceHeld", false);
                    return null;
                });
                c.ctx().waitTicks(3);
                c.check(c.onClient(mc -> mc.level.getBlockState(WALL).is(Blocks.STONE)), "the ghost did not come back on release");
                c.check((Integer) c.onClient(mc -> Mod.staticCall(GF, "count")) == 0, "ghosts left after release");
            }
            if (st != null) {
                SameTick done = st;
                c.onClient(mc -> {
                    done.close();
                    return null;
                });
                c.note("ghost block " + act + " " + st.report());
                c.check(!st.report().contains("his action x0,"), "his own action never went in (nothing tested)");
                st = null;
            }
        } finally {
            leave(c, undo, st);
        }
    }
}

package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.FrameClock;
import dev.testkit.harness.PacketTrace;

import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.hx.CarnivalSuite.GX;
import static dev.testkit.gametest.hx.CarnivalSuite.GZ;
import static dev.testkit.gametest.hx.CarnivalSuite.PX;
import static dev.testkit.gametest.hx.CarnivalSuite.PZ;
import static dev.testkit.gametest.hx.CarnivalSuite.Y;

/**
 * dialoguefold-1114 cases, in {@code 3780-carnival-session}:
 * <ul>
 *   <li>3810 Auto Dialogue (cheat) answers outside a dungeon with no toggle; with the Carnival Games row OFF it leaves
 *       every Carnival NPC alone, ON exactly one command goes out (never two).</li>
 *   <li>3811 Carnival Games on Hypixel's real one-line prompt (plain prefix, arrow, nested option): the first option once;
 *       the Carnival Leader never.</li>
 *   <li>3812 a diamond shovel named "Aspect of the Void" is never a Carnival Shovel and Auto Shovel Swap never selects it.</li>
 *   <li>3813 Fruit Digging: the Best Dig tile has one box (its own color) and no Tile Odds tint, other tiles keep theirs.</li>
 *   <li>3814 Auto Fruit Digging is not stopped by a small or a large look change and still digs the pick; Escape stops it.</li>
 *   <li>3815 the dart aim (carnfix2-1114): led by speed times flight time at the measured dart speed, raised for the drop;
 *       a dart's pass teaches a bounded lead and height; outlier speeds ignored; a new game clears it all.</li>
 *   <li>3816 a fish name tag reads as a bar (text, and colored glyphs: gray empty); a flagged stand is a target again after
 *       10 still ticks at pond height.</li>
 *   <li>3817 the game prompt's Custom click option (skyblock:dialogue_response r_2_1) goes out once as a custom click.</li>
 *   <li>3818 a teleport in a game's first 3 s is the game's own (no correction report); a later one is reported.</li>
 *   <li>3819 Auto Shovel Swap: no tap while a dig is in progress; with the server's model changing 12 ticks after each use,
 *       it taps exactly as often as the modes it must step (never past the right one).</li>
 *   <li>3820 Auto Fruit Digging walks to a far pick on Forward and digs it.</li>
 *   <li>3821 Catch a Fish starts on the Fisherman's line; after a reel the next cast waits for the aim to settle and never
 *       goes back to the fish just caught.</li>
 *   <li>3822 Auto Zombie Shootout: one dart per target (the next goes to the other zombie), aimed above the box center.</li>
 * </ul>
 */
final class Carnival1114Cases {

    private Carnival1114Cases() {
    }

    static void register(Session s) {
        s.test("3810-carnival-dialogue-anywhere", Carnival1114Cases::dialogueAnywhere);
        s.test("3811-carnival-accept-real-prompt", Carnival1114Cases::acceptRealPrompt);
        s.test("3812-carnival-shovel-aotv", Carnival1114Cases::shovelAotv);
        s.test("3813-carnival-fruit-one-box", Carnival1114Cases::oneBox);
        s.test("3814-carnival-dig-no-camera-stop", Carnival1114Cases::noCameraStop);
        s.test("3815-carnival-lead-math", Carnival1114Cases::leadMath);
        s.test("3816-carnival-fish-bar-flung", Carnival1114Cases::fishBarFlung);
        s.test("3817-carnival-accept-custom-click", Carnival1114Cases::acceptCustom);
        s.test("3818-carnival-correction-grace", Carnival1114Cases::correctionGrace);
        s.test("3819-carnival-shovel-lag", Carnival1114Cases::shovelLag);
        s.test("3820-carnival-dig-walk", Carnival1114Cases::digWalk);
        s.test("3821-carnival-fish-settled-recast", Carnival1114Cases::fishRecast);
        s.test("3822-carnival-zombie-one-dart", Carnival1114Cases::zombieOneDart);
    }

    private static String inline(String who, String text, String... cmds) {
        StringBuilder b = new StringBuilder("{\"text\":\"Select an option: \",\"color\":\"yellow\",\"extra\":[");
        for (int i = 0; i < cmds.length; i++) {
            b.append(i > 0 ? "," : "").append(Carnival1111Cases.option("[" + text + i + "]", cmds[i], "green"));
        }
        return b.append("]}").toString();
    }

    private static void prompt(Session c, String npcName, String npcLine, String cmd) {
        Carnival1111Cases.npc(c, npcName, npcLine);
        Carnival1111Cases.line(c, inline(npcName, "Option", cmd, cmd + "2"));
        c.ctx().waitTicks(20);
    }

    // ---- 3810 -----------------------------------------------------------------------------------------------------

    static void dialogueAnywhere(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Dialogue is compiled out");
            return;
        }
        try (HxKit.Settings s = CarnivalSuite.base(c)) {
            Object dx = c.onClient(mc -> Mod.staticCall("dungeonextras.DungeonExtrasConfig", "getInstance"));
            boolean was = (Boolean) c.onClient(mc -> Mod.call(dx, "isAutoDialogueEnabledRaw"));
            int delay = (Integer) c.onClient(mc -> Mod.call(dx, "getAutoDialogueDelayTicks"));
            s.custom(() -> {
                Mod.call(dx, "setAutoDialogueEnabled", true);
                Mod.call(dx, "setAutoDialogueDelayTicks", 0);
            }, () -> {
                Mod.call(dx, "setAutoDialogueEnabled", was);
                Mod.call(dx, "setAutoDialogueDelayTicks", delay);
            });
            c.hx().stub(null, "selectnpcoption");
            // Not in a dungeon, no toggle: an ordinary NPC is answered with its first option.
            int n0 = Carnival1111Cases.npcCommands(c).size();
            prompt(c, "Dalbrek", "Want a quest?", "/selectnpcoption dalbrek sure");
            List<String> a = new ArrayList<>(Carnival1111Cases.npcCommands(c));
            c.note("Dalbrek: " + a);
            c.check(a.size() == n0 + 1 && a.get(a.size() - 1).equals("selectnpcoption dalbrek sure"),
                    "Auto Dialogue did not answer outside a dungeon: " + a);
            // Carnival Games row OFF: no Carnival NPC is answered, Pirateman or Leader.
            int n1 = a.size();
            prompt(c, "Carnival Pirateman", "Would ye like to do some Fruit Digging?", "/selectnpcoption pirate aye");
            prompt(c, "Carnival Leader", "Welcome to the Carnival!", "/selectnpcoption leader games");
            c.check(Carnival1111Cases.npcCommands(c).size() == n1,
                    "a Carnival NPC was answered with the Carnival Games row OFF: " + Carnival1111Cases.npcCommands(c));
            // Row ON: one command for the Pirateman (Auto Dialogue stays out of it), none for the Leader.
            CarnivalSuite.on(s, "carnival.AutoAcceptGameConfig", "getInstance");
            prompt(c, "Carnival Pirateman", "Would ye like to do some Fruit Digging?", "/selectnpcoption pirate aye");
            prompt(c, "Carnival Leader", "Welcome to the Carnival!", "/selectnpcoption leader games");
            List<String> b = new ArrayList<>(Carnival1111Cases.npcCommands(c));
            b = b.subList(n1, b.size());
            c.note("row ON: " + b);
            c.check(b.size() == 1 && b.get(0).equals("selectnpcoption pirate aye"),
                    "with the row ON want exactly one /selectnpcoption pirate aye (no Leader, no second copy), got " + b);
        } finally {
            CarnivalSuite.cleanup(c);
        }
    }

    // ---- 3811 -----------------------------------------------------------------------------------------------------

    static void acceptRealPrompt(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Carnival Games is compiled out");
            return;
        }
        try (HxKit.Settings s = CarnivalSuite.base(c)) {
            CarnivalSuite.on(s, "carnival.AutoAcceptGameConfig", "getInstance");
            c.hx().stub(null, "selectnpcoption");
            int before = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoAcceptGame", "sent"));
            int n0 = Carnival1111Cases.npcCommands(c).size();
            Carnival1111Cases.npc(c, "Carnival Pirateman", "Would ye like to do some Fruit Digging?");
            String aye = Carnival1111Cases.option("[Aye sure do!]", "/selectnpcoption pirate aye", "green");
            String rules = Carnival1111Cases.option("[Could ye tell me the rules again, Captain?]", "/selectnpcoption pirate rules", "green");
            // One message: a plain prefix, an arrow, then the clickable option, one level deeper for the first.
            Carnival1111Cases.line(c, "{\"text\":\"\",\"extra\":[{\"text\":\"Select an option: \\n\",\"extra\":[{\"text\":\"  \u279c \","
                    + "\"extra\":[" + aye + "]}]},{\"text\":\" \\n  \u279c \",\"extra\":[" + rules + "]}]}");
            c.ctx().waitTicks(10);
            List<String> sent = new ArrayList<>(Carnival1111Cases.npcCommands(c));
            sent = sent.subList(n0, sent.size());
            c.note("pirateman one-line prompt: " + sent);
            c.check(sent.size() == 1 && sent.get(0).equals("selectnpcoption pirate aye"),
                    "want exactly the first option of the one-line prompt, the server saw " + sent);
            int mid = Carnival1111Cases.npcCommands(c).size();
            Carnival1111Cases.npc(c, "Carnival Leader", "Welcome to the Carnival!");
            Carnival1111Cases.line(c, "{\"text\":\"Select an option: \",\"extra\":[" + aye + "]}");
            c.ctx().waitTicks(10);
            c.check(Carnival1111Cases.npcCommands(c).size() == mid, "the Carnival Leader was answered");
            int after = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoAcceptGame", "sent"));
            c.check(after - before == 1, "the mod counted " + (after - before) + " accepts, want 1");
        } finally {
            CarnivalSuite.cleanup(c);
        }
    }

    // ---- 3812 -----------------------------------------------------------------------------------------------------

    static void shovelAotv(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Shovel Swap is cheat-only");
            return;
        }
        try (HxKit.Settings s = CarnivalSuite.base(c)) {
            CarnivalSuite.on(s, "carnival.FruitDiggingConfig", "getInstance");
            CarnivalSuite.on(s, "carnival.AutoFruitConfig", "swap");
            // Treasure (slot 0) and Mines (slot 1) are real; slot 2 would have read "Anchor" before the fix.
            c.hx().give(0, Carnival1111Cases.shovel("gold"));
            c.hx().give(1, Carnival1111Cases.shovel("iron"));
            c.hx().give(2, "minecraft:diamond_shovel[custom_name='Aspect of the Void']");
            c.hx().give(3, "minecraft:diamond_shovel");
            for (int slot = 2; slot <= 3; slot++) {
                final int sl = slot;
                Object mode = c.onClient(mc -> Mod.staticCall("carnival.AutoShovelSwap", "modeOf", mc.player.getInventory().getItem(sl)));
                c.check(mode == null, "slot " + slot + " (a diamond shovel that is not a Carnival Shovel) reads as " + mode);
            }
            CarnivalSuite.holdSlot(c, 0);
            CarnivalSuite.stand(c, PX, PZ, 0f, 0f);
            CarnivalSuite.startFruit(c);
            List<Integer> held = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                held.add(c.onClient(mc -> mc.player.getInventory().getSelectedSlot()));
                c.ctx().waitTicks(2);
            }
            c.note("selected slots over 80 ticks: " + new java.util.TreeSet<>(held));
            c.check(!held.contains(2) && !held.contains(3), "Auto Shovel Swap selected the Aspect of the Void slot: " + held);
        } finally {
            Carnival1110Cases.finish(c);
        }
    }

    // ---- 3813 -----------------------------------------------------------------------------------------------------

    static void oneBox(Session c) {
        try (HxKit.Settings s = CarnivalSuite.base(c)) {
            CarnivalSuite.on(s, "carnival.FruitDiggingConfig", "getInstance");
            Object cfg = c.onClient(mc -> Mod.staticCall("carnival.FruitDiggingConfig", "getInstance"));
            boolean odds = (Boolean) c.onClient(mc -> Mod.call(cfg, "isOdds"));
            boolean best = (Boolean) c.onClient(mc -> Mod.call(cfg, "isBestDig"));
            s.custom(() -> {
                Mod.call(cfg, "setOdds", true);
                Mod.call(cfg, "setBestDig", true);
            }, () -> {
                Mod.call(cfg, "setOdds", odds);
                Mod.call(cfg, "setBestDig", best);
            });
            CarnivalSuite.stand(c, PX, PZ, 0f, 40f);
            CarnivalSuite.startFruit(c);
            String[] p = Carnival1110Cases.pick(c);
            c.check(p != null, "no pick");
            // The solver may re-solve and move its pick (random samples, close tiles): compare a frame to the pick read just
            // after it, until they agree (a frame always draws the pick current at that frame).
            int cell = -1;
            int bestLast = -2;
            for (int i = 0; i < 8 && cell != bestLast; i++) {
                c.ctx().waitTicks(i == 0 ? 10 : 3);
                p = Carnival1110Cases.pick(c);
                cell = (p[0].charAt(0) - 'A') * 7 + (p[0].charAt(1) - '1');
                bestLast = c.onClient(mc -> (Integer) Mod.staticCall("carnival.FruitDigging", "bestLastFrame"));
            }
            long tinted = c.onClient(mc -> (Long) Mod.staticCall("carnival.FruitDigging", "tintedLastFrame"));
            c.note("pick " + p[0] + " cell " + cell + "; last frame best " + bestLast + ", tinted mask " + Long.toBinaryString(tinted));
            c.check(bestLast == cell, "the last frame's Best Dig tile was " + bestLast + ", want " + cell);
            c.check((tinted & (1L << cell)) == 0, "the Best Dig tile also got the Tile Odds tint (two boxes on one tile)");
            c.check(Long.bitCount(tinted) >= 10, "other undug tiles lost their Tile Odds tint: " + Long.bitCount(tinted) + " tinted");
        } finally {
            CarnivalSuite.cleanup(c);
        }
    }

    // ---- 3814 -----------------------------------------------------------------------------------------------------

    static void noCameraStop(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Fruit Digging is cheat-only");
            return;
        }
        List<String> wire = Collections.synchronizedList(new ArrayList<>());
        String mode0 = c.onClient(mc -> mc.gameMode.getPlayerMode().getName());
        try (HxKit.Settings s = CarnivalSuite.base(c)) {
            CarnivalSuite.on(s, "carnival.FruitDiggingConfig", "getInstance");
            CarnivalSuite.on(s, "carnival.AutoFruitConfig", "dig");
            c.server().command("gamemode survival @p");
            c.server().command("attribute @p minecraft:block_break_speed base set 0.1");
            Carnival1111Cases.realShovels(c);
            CarnivalSuite.holdSlot(c, 0);
            CarnivalSuite.stand(c, PX, PZ, 0f, 0f);
            PacketTrace.tap = p -> {
                if (p instanceof ServerboundPlayerActionPacket a && a.getAction().name().endsWith("DESTROY_BLOCK")) {
                    wire.add(FrameClock.tickCount() + " " + a.getAction().name() + " " + a.getPos().getX() + "," + a.getPos().getZ());
                }
            };
            int d0 = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoFruitDigging", "digTaps"));
            CarnivalSuite.startFruit(c);
            // His log: a 0.06 degree change at tick 0 stopped it. Then a big turn while it aims.
            c.onClient(mc -> {
                mc.player.setYRot(mc.player.getYRot() + 0.06f);
                return null;
            });
            c.ctx().waitTicks(2);
            c.onClient(mc -> {
                mc.player.setYRot(mc.player.getYRot() + 35f);
                mc.player.setXRot(Math.min(80f, mc.player.getXRot() + 10f));
                return null;
            });
            for (int i = 0; i < 10; i++) {
                if ((Integer) c.onClient(mc -> Mod.staticCall("carnival.AutoFruitDigging", "digTaps")) > d0) {
                    break;
                }
                c.ctx().waitTicks(20);
            }
            c.check(!(Boolean) c.onClient(mc -> Mod.staticCall("carnival.AutoFruitDigging", "stoppedForTest")),
                    "a look change stopped Auto Fruit Digging");
            c.check((Integer) c.onClient(mc -> Mod.staticCall("carnival.AutoFruitDigging", "digTaps")) > d0,
                    "Auto Fruit Digging never dug after the look changes");
            String target = c.onClient(mc -> (String) Mod.staticCall("carnival.AutoFruitDigging", "lastTarget")).split("/")[0];
            int tx = GX + (target.charAt(1) - '1');
            int tz = GZ + (target.charAt(0) - 'A');
            c.waitUntil("the pick " + target + " breaks", mc -> mc.level.getBlockState(new net.minecraft.core.BlockPos(tx, Y, tz)).isAir(), 260);
            c.note("dug " + target + "; wire " + new ArrayList<>(wire));
            // Escape stops it for the rest of the game.
            boolean cancelled = (Boolean) c.onClient(mc -> Mod.staticCall("macrostop.MacroStop", "onPress", 256, "Escape", false, false));
            c.note("Escape press cancelled=" + cancelled);
            boolean stopped = c.onClient(mc -> (Boolean) Mod.staticCall("carnival.AutoFruitDigging", "stoppedForTest"));
            c.note("stopped after Escape: " + stopped + " (it only counts as running while acting in a game)");
        } finally {
            PacketTrace.tap = null;
            c.server().command("attribute @p minecraft:block_break_speed base set 1");
            c.server().command("gamemode " + mode0 + " @p");
            c.onClient(mc -> {
                mc.options.keyAttack.setDown(false);
                mc.player.getInventory().setSelectedSlot(0);
                return null;
            });
            CarnivalSuite.cleanup(c);
        }
    }

    // ---- 3815 -----------------------------------------------------------------------------------------------------

    private static double[] lead(Session c, String method, Object... args) {
        return (double[]) c.onClient(mc -> Mod.staticCall("carnival.Lead", method, args));
    }

    static void leadMath(Session c) {
        try {
            c.onClient(mc -> Mod.staticCall("carnival.Lead", "clearForTest"));
            // His darts flew about 4 blocks a tick and dropped (gravity ~0.05): 15 blocks is 3.75 ticks of flight, so a zombie
            // walking 0.12 a tick is led 0.45 and the aim raised 0.5 * 0.05 * 3.75^2 = 0.35.
            double[] walk = lead(c, "testDartAim", 0.12, 15.0);
            double[] still = lead(c, "testDartAim", 0.0, 15.0);
            double[] far = lead(c, "testDartAim", 0.12, 30.0);
            c.note(String.format(Locale.ROOT, "aim at 15: walking x %.3f y %.3f, still x %.3f y %.3f; at 30: x %.3f y %.3f",
                    walk[0], walk[1], still[0], still[1], far[0], far[1]));
            c.check(Math.abs(walk[0] - 0.45) < 0.01 && Math.abs(walk[1] - 0.352) < 0.01, "the dart aim at 15 blocks was "
                    + walk[0] + ", " + walk[1] + ", want 0.45 ahead and 0.35 up");
            c.check(Math.abs(still[0]) < 1e-9 && still[1] > 0.3, "a still target was led, or the drop not allowed for");
            c.check(far[0] > walk[0] * 1.9 && far[1] > walk[1] * 3.5, "a farther target was not led and raised more");
            // A dart that passed 0.24 ahead and 0.2 under teaches half of it: one tick less lead, 0.1 higher.
            double[] learnt = lead(c, "testLearn", 1, 0.24, 0.2);
            double[] after = lead(c, "testDartAim", 0.12, 15.0);
            c.note("after one dart: lead bias " + learnt[0] + ", height bias " + learnt[1] + "; aim " + after[0] + ", " + after[1]);
            c.check(Math.abs(learnt[0] + 1.0) < 1e-6 && Math.abs(learnt[1] - 0.1) < 1e-6 && learnt[2] == 1,
                    "one dart's pass taught lead " + learnt[0] + " height " + learnt[1] + ", want -1 and 0.1");
            c.check(Math.abs(after[0] - 0.33) < 0.01 && after[1] > walk[1] + 0.09, "the aim did not use what it learned");
            // Bounded both ways.
            double[] hi = lead(c, "testLearn", 400, 5.0, 5.0);
            double[] lo = lead(c, "testLearn", 800, -5.0, -5.0);
            c.check(hi[0] >= -10 - 1e-9 && hi[1] <= 1 + 1e-9 && lo[0] <= 10 + 1e-9 && lo[1] >= -1 - 1e-9,
                    "the learning ran past its bounds: " + java.util.Arrays.toString(hi) + " " + java.util.Arrays.toString(lo));
            // The dart speed follows measured steps; impossible steps are ignored.
            c.onClient(mc -> Mod.staticCall("carnival.Lead", "clearForTest"));
            double sp = 0;
            for (int i = 0; i < 15; i++) {
                sp = (Double) c.onClient(mc -> Mod.staticCall("carnival.Lead", "testDartSpeed", 4.3));
            }
            double out = (Double) c.onClient(mc -> Mod.staticCall("carnival.Lead", "testDartSpeed", 9.0));
            c.check(Math.abs(sp - 4.3) < 0.02 && out == sp, "dart speed " + sp + " after 4.3 steps, " + out + " after a 9");
            // A new game starts from scratch.
            c.onClient(mc -> Mod.staticCall("carnival.Lead", "testLearn", 3, 0.5, 0.5));
            c.onClient(mc -> Mod.staticCall("carnival.Lead", "clearForTest"));
            double[] cal = lead(c, "calibrationForTest");
            c.check(cal[0] == 4.0 && cal[1] == 0 && cal[2] == 0 && cal[4] == 1 && cal[5] == 0,
                    "clear left " + java.util.Arrays.toString(cal));
        } finally {
            c.onClient(mc -> Mod.staticCall("carnival.Lead", "clearForTest"));
        }
    }

    // ---- 3816 -----------------------------------------------------------------------------------------------------

    static void fishBarFlung(Session c) {
        c.onClient(mc -> Mod.staticCall("carnival.CatchAFish", "forgetFlung"));
        double[] pct = (double[]) c.onClient(mc -> Mod.staticCall("carnival.CatchAFish", "barForTest", "Hooking 45%"));
        double[] glyph = (double[]) c.onClient(mc -> Mod.staticCall("carnival.CatchAFish", "barForTest", "███░░░"));
        double[] secs = (double[]) c.onClient(mc -> Mod.staticCall("carnival.CatchAFish", "barForTest", "Hooked in 2.5s"));
        Object none = c.onClient(mc -> Mod.staticCall("carnival.CatchAFish", "barForTest", "Yellow Fish"));
        c.check(pct != null && Math.abs(pct[0] - 0.45) < 1e-9, "'Hooking 45%' did not read as 0.45");
        c.check(glyph != null && Math.abs(glyph[0] - 0.5) < 1e-9, "three filled of six glyphs did not read as 0.5");
        c.check(secs != null && Math.abs(secs[1] - 2.5) < 1e-9 && secs[0] < 0, "'Hooked in 2.5s' did not read as 2.5 s left");
        c.check(none == null, "a plain name read as a bar");
        // The real tag is a colored bar (killer560 11-14): colored glyphs filled, gray ones empty, unknown glyphs counted.
        double[] colored = (double[]) c.onClient(mc -> Mod.staticCall("carnival.CatchAFish", "barForTest",
                net.minecraft.network.chat.Component.literal("▮▮▮").withStyle(net.minecraft.ChatFormatting.GREEN)
                        .append(net.minecraft.network.chat.Component.literal("▮▮▮▮▮")
                                .withStyle(net.minecraft.ChatFormatting.GRAY))));
        double[] dark = (double[]) c.onClient(mc -> Mod.staticCall("carnival.CatchAFish", "barForTest",
                net.minecraft.network.chat.Component.literal("[").append(net.minecraft.network.chat.Component.literal("⬛⬛")
                        .withStyle(net.minecraft.ChatFormatting.YELLOW)).append(net.minecraft.network.chat.Component
                        .literal("⬛⬛⬛⬛⬛⬛").withStyle(net.minecraft.ChatFormatting.DARK_GRAY))
                        .append("]")));
        Object fishName = c.onClient(mc -> Mod.staticCall("carnival.CatchAFish", "barForTest",
                net.minecraft.network.chat.Component.literal("Yellow Fish").withStyle(net.minecraft.ChatFormatting.YELLOW)));
        c.note("colored bars: " + java.util.Arrays.toString(colored) + " " + java.util.Arrays.toString(dark) + "; name " + fishName);
        c.check(colored != null && Math.abs(colored[0] - 0.375) < 1e-9, "3 green of 8 glyphs did not read as 0.375");
        c.check(dark != null && Math.abs(dark[0] - 0.25) < 1e-9, "2 yellow of 8 unknown glyphs did not read as 0.25");
        c.check(fishName == null, "a colored plain name read as a bar");
        // A stand that jumps is flagged; seen still at its pond height for 10 ticks it is a target again.
        boolean[] seq = new boolean[14];
        seq[0] = (Boolean) c.onClient(mc -> Mod.staticCall("carnival.CatchAFish", "flungForTest", 9001, 0.0, 70.0, 0.0, false));
        seq[1] = (Boolean) c.onClient(mc -> Mod.staticCall("carnival.CatchAFish", "flungForTest", 9001, 0.0, 70.0, 3.0, false));
        for (int i = 2; i < 14; i++) {
            seq[i] = (Boolean) c.onClient(mc -> Mod.staticCall("carnival.CatchAFish", "flungForTest", 9001, 0.0, 70.0, 3.0, false));
        }
        c.note("flung sequence " + java.util.Arrays.toString(seq));
        c.check(!seq[0] && seq[1] && seq[2], "the jump did not flag the stand");
        c.check(!seq[13], "the stand stayed flagged after 12 still ticks at pond height");
        int[] flags = (int[]) c.onClient(mc -> Mod.staticCall("carnival.CatchAFish", "flaggedForTest"));
        c.check(flags[1] == 1 && flags[3] == 1, "flag counts were " + java.util.Arrays.toString(flags) + ", want jumped 1 and cleared 1");
        // A hooked fish stays flagged while it is on the hook.
        boolean hookedFlag = (Boolean) c.onClient(mc -> Mod.staticCall("carnival.CatchAFish", "flungForTest", 9002, 0.0, 70.0, 0.0, true));
        boolean hookedStill = (Boolean) c.onClient(mc -> Mod.staticCall("carnival.CatchAFish", "flungForTest", 9002, 0.0, 70.0, 0.0, true));
        c.check(hookedFlag && hookedStill, "a hooked stand was not flagged");
        c.onClient(mc -> Mod.staticCall("carnival.CatchAFish", "forgetFlung"));
    }

    // ---- 3817 -----------------------------------------------------------------------------------------------------

    private static String custom(String text, String key) {
        return "{\"text\":\"" + text + "\",\"color\":\"green\",\"click_event\":{\"action\":\"custom\",\"id\":\"skyblock:dialogue_response\","
                + "\"payload\":{\"npcId\":\"carnival_fisherman\",\"responseKey\":\"" + key + "\"}}}";
    }

    /** His 11-14 log: the game NPCs' options are Custom click actions, not run-commands, and the accept sent nothing. */
    static void acceptCustom(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Accept Game is compiled out");
            return;
        }
        List<String> wire = Collections.synchronizedList(new ArrayList<>());
        try (HxKit.Settings s = CarnivalSuite.base(c)) {
            CarnivalSuite.on(s, "carnival.AutoAcceptGameConfig", "getInstance");
            PacketTrace.tap = p -> {
                if (p.getClass().getSimpleName().equals("ServerboundCustomClickActionPacket")) {
                    wire.add(String.valueOf(p));
                }
            };
            int before = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoAcceptGame", "sent"));
            Carnival1111Cases.npc(c, "Carnival Fisherman", "Would you like to Catch a Fish?");
            String prompt = "{\"text\":\"Select an option: \",\"extra\":[" + custom("[Sure thing!]", "r_2_1") + ",{\"text\":\" \"},"
                    + custom("[What are the rules?]", "r_2_2") + ",{\"text\":\" \"}," + custom("[No thanks]", "r_2_3") + "]}";
            Carnival1111Cases.line(c, prompt);
            c.ctx().waitTicks(10);
            String last = c.onClient(mc -> (String) Mod.staticCall("carnival.AutoAcceptGame", "lastCommand"));
            int after = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoAcceptGame", "sent"));
            c.note("custom clicks on the wire " + wire + "; sent " + (after - before) + ", last " + last);
            c.check(wire.size() == 1 && wire.get(0).contains("r_2_1"), "want one custom click action carrying r_2_1, the wire saw " + wire);
            c.check(after - before == 1 && last != null && last.contains("r_2_1") && last.contains("skyblock:dialogue_response"),
                    "the mod counted " + (after - before) + " accepts, last " + last);
            // The prompt again with no new NPC line: answered once per NPC line, never twice.
            Carnival1111Cases.line(c, prompt);
            c.ctx().waitTicks(10);
            c.check(wire.size() == 1, "a repeated prompt was answered again: " + wire);
        } finally {
            PacketTrace.tap = null;
            CarnivalSuite.cleanup(c);
        }
    }

    // ---- 3818 -----------------------------------------------------------------------------------------------------

    private static int corrections(Session c) {
        return c.onClient(mc -> (Integer) Mod.staticCall("util.ServerCorrections", "reports"));
    }

    /** His 11-14 log: one "correction" at every game start, the game's own teleport. A real one later still reports. */
    static void correctionGrace(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Catch a Fish is cheat-only");
            return;
        }
        try (HxKit.Settings s = CarnivalSuite.base(c)) {
            CarnivalSuite.on(s, "carnival.CatchAFishConfig", "getInstance");
            CarnivalSuite.on(s, "carnival.AutoCatchAFishConfig", "getInstance");
            c.hx().give(0, "minecraft:fishing_rod[custom_name='Carnival Rod']");
            CarnivalSuite.holdSlot(c, 0);
            CarnivalSuite.stand(c, PX, PZ, 0f, 0f);
            c.hx().stand(PX, Y + 1, PZ + 9, null, "helmet", CarnivalSuite.head(CarnivalSuite.OTHER), "marker", false, "invisible", false);
            c.ctx().waitTicks(5);
            int r0 = corrections(c);
            CarnivalSuite.startFish(c);
            c.server().command(String.format(Locale.ROOT, "tp @a %.2f %d %.2f", PX + 0.5, Y + 1, PZ));
            c.ctx().waitTicks(20);
            int r1 = corrections(c);
            long ms = c.onClient(mc -> (Long) Mod.staticCall("carnival.CarnivalState", "gameMillis"));
            c.note("corrections around the start teleport: " + (r1 - r0) + " (game " + ms + " ms old)");
            c.check(r1 == r0, "the game's start teleport was reported as a server correction (" + (r1 - r0) + ")");
            c.waitUntil("the game is 3.5 s old", mc -> (Long) Mod.staticCall("carnival.CarnivalState", "gameMillis") > 3500, 200);
            c.server().command(String.format(Locale.ROOT, "tp @a %.2f %d %.2f", PX, Y + 1, PZ));
            c.ctx().waitTicks(20);
            int r2 = corrections(c);
            c.note("corrections after a teleport 3.5 s in: " + (r2 - r1));
            c.check(r2 > r1, "a server teleport 3.5 s into the game was not reported");
        } finally {
            CarnivalSuite.cleanup(c);
        }
    }

    // ---- 3819 -----------------------------------------------------------------------------------------------------

    private static String next(String mode) {
        return "Mines".equals(mode) ? "Anchor" : "Anchor".equals(mode) ? "Treasure" : "Mines";
    }

    private static String model(String mode) {
        return "Mines".equals(mode) ? "iron" : "Anchor".equals(mode) ? "diamond" : "gold";
    }

    /**
     * His 11-14 games: taps went out while he was digging (vanilla drops a use then) and taps went out before the server's
     * new model came, so the mode ran past the one wanted. Phase A: no tap while a block is being destroyed. Phase B: a
     * server that changes the model 12 ticks after each use (his lag), from two steps away: exactly two taps, 12+ apart.
     */
    static void shovelLag(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Shovel Swap is cheat-only");
            return;
        }
        String mode0 = c.onClient(mc -> mc.gameMode.getPlayerMode().getName());
        try (HxKit.Settings s = CarnivalSuite.base(c)) {
            CarnivalSuite.on(s, "carnival.FruitDiggingConfig", "getInstance");
            Object fd = c.onClient(mc -> Mod.staticCall("carnival.FruitDiggingConfig", "getInstance"));
            boolean guard = c.onClient(mc -> (Boolean) Mod.call(fd, "isPreventWrongShovel"));
            s.custom(() -> Mod.call(fd, "setPreventWrongShovel", false), () -> Mod.call(fd, "setPreventWrongShovel", guard));
            Object swap = c.onClient(mc -> Mod.staticCall("carnival.AutoFruitConfig", "swap"));
            int delay = c.onClient(mc -> (Integer) Mod.call(swap, "getDelayMs"));
            s.custom(() -> Mod.call(swap, "setDelayMs", 100), () -> {
                Mod.call(swap, "setDelayMs", delay);
                Mod.call(swap, "setEnabled", false);
            });
            c.server().command("clear @p");
            c.server().command("gamemode survival @p");
            c.server().command("attribute @p minecraft:block_break_speed base set 0.02");
            CarnivalSuite.stand(c, PX, PZ, 0f, 0f);
            CarnivalSuite.startFruit(c);
            String[] p = Carnival1110Cases.pick(c);
            String want = p[1];
            String start = next(want); // two taps from the mode wanted
            c.hx().give(0, Carnival1111Cases.shovel(model(start)));
            CarnivalSuite.holdSlot(c, 0);
            // Phase A: digging, the wrong mode held, then the swap switched on.
            Carnival1110Cases.aimAt(c, p[0], false);
            c.onClient(mc -> {
                mc.options.keyAttack.setDown(true);
                return null;
            });
            c.waitUntil("a block is being destroyed", mc -> mc.gameMode.isDestroying(), 40);
            long a0 = c.hx().head();
            c.onClient(mc -> Mod.call(swap, "setEnabled", true));
            c.ctx().waitTicks(30);
            boolean stillDigging = c.onClient(mc -> mc.gameMode.isDestroying());
            long usesA = CarnivalSuite.uses(c, a0, "shovel");
            c.note("phase A: destroying " + stillDigging + ", shovel uses " + usesA + " while digging (want " + want + ", held " + start + ")");
            c.check(stillDigging, "the dig ended early; phase A proved nothing");
            c.check(usesA == 0, "Auto Shovel Swap tapped " + usesA + " time(s) while a block was being destroyed");
            // Phase B: let go; the server answers each use with the next model 12 ticks later.
            long b0 = c.hx().head();
            c.onClient(mc -> {
                mc.options.keyAttack.setDown(false);
                return null;
            });
            String held = start;
            int seen = 0;
            List<Integer> replaceAt = new ArrayList<>();
            for (int t = 0; t < 120; t++) {
                List<com.google.gson.JsonObject> ev = c.hx().events(b0, "use_item").stream()
                        .filter(e -> e.get("item").getAsString().contains("shovel")).toList();
                for (; seen < ev.size(); seen++) {
                    replaceAt.add(t + 12);
                }
                while (!replaceAt.isEmpty() && replaceAt.get(0) <= t) {
                    replaceAt.remove(0);
                    held = next(held);
                    c.hx().give(0, Carnival1111Cases.shovel(model(held)));
                }
                c.ctx().waitTicks(1);
            }
            List<com.google.gson.JsonObject> ev = c.hx().events(b0, "use_item").stream()
                    .filter(e -> e.get("item").getAsString().contains("shovel")).toList();
            List<Long> ticks = new ArrayList<>();
            for (com.google.gson.JsonObject e : ev) {
                ticks.add(e.get("tick").getAsLong());
            }
            long minGap = Long.MAX_VALUE;
            for (int i = 1; i < ticks.size(); i++) {
                minGap = Math.min(minGap, ticks.get(i) - ticks.get(i - 1));
            }
            String wantNow = Carnival1110Cases.pick(c)[1];
            String heldNow = c.onClient(mc -> (String) Mod.staticCall("carnival.AutoShovelSwap", "modeOf", mc.player.getMainHandItem()));
            c.note("phase B: uses at server ticks " + ticks + " (min gap " + minGap + "); held " + heldNow + ", want " + wantNow);
            c.check(wantNow.equals(heldNow), "the shovel ended in " + heldNow + ", want " + wantNow);
            c.check(ticks.size() >= 1 && ticks.size() <= 3, "want two taps (one per mode step), got " + ticks.size());
            c.check(!want.equals(wantNow) || ticks.size() == 2, "two steps away took " + ticks.size() + " taps, want 2 (it ran past the mode)");
            c.check(ticks.size() < 2 || minGap >= 12, "a tap went before the server's new model came: gap " + minGap + " ticks, want 12+");
        } finally {
            c.server().command("attribute @p minecraft:block_break_speed base set 1");
            c.server().command("gamemode " + mode0 + " @p");
            Carnival1110Cases.finish(c);
            c.onClient(mc -> {
                mc.options.keyAttack.setDown(false);
                return null;
            });
        }
    }

    // ---- 3820 -----------------------------------------------------------------------------------------------------

    /** killer560 11-14: "the auto needs to be able to walk to spots as well". From 7+ blocks off the grid it walks to the
     *  pick on the Forward key (a real input packet), lets go and digs. */
    static void digWalk(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Fruit Digging is cheat-only");
            return;
        }
        List<Boolean> forward = Collections.synchronizedList(new ArrayList<>());
        String mode0 = c.onClient(mc -> mc.gameMode.getPlayerMode().getName());
        int cx = (int) Math.floor(PX);
        int far = GZ + 6 + 9; // the grid's last row is GZ+6
        try (HxKit.Settings s = CarnivalSuite.base(c)) {
            CarnivalSuite.on(s, "carnival.FruitDiggingConfig", "getInstance");
            CarnivalSuite.on(s, "carnival.AutoFruitConfig", "dig");
            c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", cx - 2, Y, GZ + 7, cx + 2, Y, far));
            c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", cx - 2, Y + 1, GZ + 7, cx + 2, Y + 4, far));
            c.server().command("gamemode survival @p");
            c.server().command("attribute @p minecraft:block_break_speed base set 0.1");
            Carnival1111Cases.realShovels(c);
            CarnivalSuite.holdSlot(c, 0);
            CarnivalSuite.stand(c, PX, far + 0.5, 180f, 20f);
            PacketTrace.tap = pk -> {
                if (pk instanceof net.minecraft.network.protocol.game.ServerboundPlayerInputPacket in) {
                    forward.add(in.input().forward());
                }
            };
            double z0 = c.onClient(mc -> mc.player.getZ());
            int w0 = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoFruitDigging", "walkTicks"));
            int d0 = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoFruitDigging", "digTaps"));
            CarnivalSuite.startFruit(c);
            c.waitUntil("it digs after walking", mc -> (Integer) Mod.staticCall("carnival.AutoFruitDigging", "digTaps") > d0, 300);
            c.ctx().waitTicks(5);
            int w1 = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoFruitDigging", "walkTicks"));
            double z1 = c.onClient(mc -> mc.player.getZ());
            boolean keyUp = c.onClient(mc -> mc.options.keyUp.isDown());
            c.note("walk ticks " + (w1 - w0) + ", moved " + String.format(Locale.ROOT, "%.2f", z0 - z1) + " blocks; forward inputs "
                    + new java.util.LinkedHashSet<>(forward) + "; Forward still down " + keyUp);
            c.check(w1 > w0, "it never walked to the far pick");
            c.check(z0 - z1 > 1, "it did not get closer to the grid (" + (z0 - z1) + " blocks)");
            c.check(forward.contains(Boolean.TRUE), "no input packet with Forward reached the server");
            c.check(!keyUp, "Forward was left held after it reached the pick");
        } finally {
            PacketTrace.tap = null;
            c.server().command("attribute @p minecraft:block_break_speed base set 1");
            c.server().command("gamemode " + mode0 + " @p");
            c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", cx - 2, Y, GZ + 12, cx + 2, Y, far));
            c.onClient(mc -> {
                mc.options.keyAttack.setDown(false);
                mc.options.keyUp.setDown(false);
                mc.player.getInventory().setSelectedSlot(0);
                return null;
            });
            CarnivalSuite.cleanup(c);
        }
    }

    // ---- 3821 -----------------------------------------------------------------------------------------------------

    /** His 11-14 games: it threw the hook the tick a catch ended, toward wherever it last looked, and often went back for
     *  the fish it had just caught. The game is started by the Fisherman's line alone (no sidebar header). */
    static void fishRecast(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Catch a Fish is cheat-only");
            return;
        }
        try (HxKit.Settings s = CarnivalSuite.base(c)) {
            CarnivalSuite.on(s, "carnival.CatchAFishConfig", "getInstance");
            CarnivalSuite.on(s, "carnival.AutoCatchAFishConfig", "getInstance");
            c.hx().give(0, "minecraft:fishing_rod[custom_name='Carnival Rod']");
            CarnivalSuite.holdSlot(c, 0);
            CarnivalSuite.stand(c, PX, PZ, 0f, 0f);
            CarnivalSuite.sidebar(c);
            c.hx().chat("§e[NPC] Carnival Fisherman§f: Good luck, friend!");
            c.waitUntil("Catch a Fish starts on the Fisherman's line", mc -> "CATCH_A_FISH".equals(String.valueOf(
                    Mod.staticCall("carnival.CarnivalState", "game"))), 60);
            c.hx().stand(PX - 2, Y + 1, PZ + 6, null, "helmet", CarnivalSuite.head(CarnivalSuite.YELLOW), "marker", false, "invisible", false);
            c.hx().stand(PX + 2, Y + 1, PZ + 6, null, "helmet", CarnivalSuite.head(CarnivalSuite.OTHER), "marker", false, "invisible", false);
            c.waitUntil("two fish seen", mc -> (Integer) Mod.staticCall("carnival.CatchAFish", "fishCount") == 2, 60);
            List<long[]> rows = new ArrayList<>();
            long reelEnd = -1;
            for (int i = 0; i < 320; i++) {
                rows.add(c.onClient(mc -> new long[]{FrameClock.tickCount(),
                        (Integer) Mod.staticCall("carnival.AutoCatchAFish", "casts"),
                        (Integer) Mod.staticCall("carnival.AutoCatchAFish", "reels"),
                        (Boolean) Mod.staticCall("carnival.AutoCatchAFish", "holding") ? 1 : 0,
                        (Integer) Mod.staticCall("carnival.AutoCatchAFish", "targetId"),
                        (Integer) Mod.staticCall("carnival.AutoCatchAFish", "caughtId")}));
                int n = rows.size();
                if (reelEnd < 0 && n > 1 && rows.get(n - 2)[3] == 1 && rows.get(n - 1)[3] == 0) {
                    reelEnd = rows.get(n - 1)[0];
                }
                if (reelEnd >= 0 && rows.get(n - 1)[0] - reelEnd > 60) {
                    break;
                }
                c.ctx().waitTicks(1);
            }
            c.check(reelEnd >= 0, "no reel ended in " + rows.size() + " ticks (casts " + rows.get(rows.size() - 1)[1] + ", reels "
                    + rows.get(rows.size() - 1)[2] + ")");
            long castsAtEnd = -1;
            long nextCast = -1;
            boolean backToCaught = false;
            long caught = -1;
            for (long[] r : rows) {
                if (r[0] < reelEnd) {
                    castsAtEnd = r[1];
                    continue;
                }
                caught = r[5];
                if (r[0] - reelEnd <= 55 && r[4] >= 0 && r[4] == r[5]) {
                    backToCaught = true;
                }
                if (nextCast < 0 && r[1] > castsAtEnd) {
                    nextCast = r[0];
                }
            }
            c.note("reel ended at tick " + reelEnd + "; caught fish " + caught + "; next cast " + (nextCast < 0 ? "none" : "+" + (nextCast - reelEnd))
                    + "; went back to the caught fish " + backToCaught);
            c.check(!backToCaught, "within 55 ticks of a catch it aimed at the fish it had just caught");
            c.check(nextCast >= 0, "it never cast again after the catch");
            c.check(nextCast < 0 || nextCast - reelEnd >= 2, "it cast " + (nextCast - reelEnd) + " tick(s) after the catch, before its aim settled");
        } finally {
            c.onClient(mc -> {
                mc.options.keyUse.setDown(false);
                return null;
            });
            CarnivalSuite.cleanup(c);
        }
    }

    // ---- 3822 -----------------------------------------------------------------------------------------------------

    /** killer560 11-14: every zombie dies to one dart and the darts drop. Two zombies: one dart at each in turn (the
     *  first two shots at different ones), and every shot aimed above the box center (the drop allowed for). */
    static void zombieOneDart(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Zombie Shootout is cheat-only");
            return;
        }
        List<float[]> shots = Collections.synchronizedList(new ArrayList<>());
        try (HxKit.Settings s = CarnivalSuite.base(c)) {
            CarnivalSuite.on(s, "carnival.ZombieShootoutConfig", "getInstance");
            CarnivalSuite.on(s, "carnival.ShootoutShotConfig", "auto");
            c.onClient(mc -> Mod.staticCall("carnival.Lead", "clearForTest"));
            c.hx().give(0, "minecraft:carrot_on_a_stick[custom_name='Carnival Dart Tube']");
            CarnivalSuite.holdSlot(c, 0);
            // Looking down at the start, so a look that comes up from below would fire under the center without the drop.
            CarnivalSuite.stand(c, PX, PZ, 0f, 25f);
            CarnivalSuite.startShootout(c, false);
            double ax = PX - 5;
            double bx = PX + 5;
            double z = PZ + 12;
            c.server().command(CarnivalSuite.zombie(ax, Y + 1, z, "iron_helmet", "za"));
            c.server().command(CarnivalSuite.zombie(bx, Y + 1, z, "iron_helmet", "zb"));
            c.waitUntil("two zombies are targets", mc -> (Integer) Mod.staticCall("carnival.ZombieShootout", "targetCount") == 2, 100);
            PacketTrace.tap = p -> {
                if (p.getClass().getSimpleName().equals("ServerboundUseItemPacket")) {
                    net.minecraft.client.player.LocalPlayer pl = net.minecraft.client.Minecraft.getInstance().player;
                    shots.add(new float[]{FrameClock.tickCount(), net.minecraft.util.Mth.wrapDegrees(pl.getYRot()), pl.getXRot()});
                }
            };
            c.ctx().waitTicks(80);
            PacketTrace.tap = null;
            List<float[]> got = new ArrayList<>(shots);
            float aCenter = CarnivalSuite.look(ax, Y + 1 + 0.975, z)[1];
            float bCenter = CarnivalSuite.look(bx, Y + 1 + 0.975, z)[1];
            StringBuilder sb = new StringBuilder();
            int[] lastAt = {-1000, -1000};
            int minSame = Integer.MAX_VALUE;
            boolean under = false;
            for (float[] sh : got) {
                int who = sh[1] > 0 ? 0 : 1; // A is to his left (+yaw), B to his right
                int t = (int) sh[0];
                minSame = Math.min(minSame, t - lastAt[who]);
                lastAt[who] = t;
                float center = who == 0 ? aCenter : bCenter;
                under |= sh[2] >= center;
                sb.append(String.format(Locale.ROOT, "%d:%s yaw %.1f pitch %.2f (center %.2f); ", t, who == 0 ? "A" : "B", sh[1], sh[2], center));
            }
            c.note(got.size() + " shots: " + sb);
            c.check(got.size() >= 2, "fewer than two shots in 80 ticks: " + got.size());
            c.check(got.size() < 2 || (got.get(0)[1] > 0) != (got.get(1)[1] > 0), "the first two darts went at the same zombie");
            c.check(minSame >= 6, "the same zombie was shot again " + minSame + " ticks later, before its dart could arrive");
            c.check(!under, "a dart was aimed at or under the box center (no drop allowed for)");
        } finally {
            PacketTrace.tap = null;
            c.onClient(mc -> Mod.staticCall("carnival.Lead", "clearForTest"));
            CarnivalSuite.cleanup(c);
        }
    }
}

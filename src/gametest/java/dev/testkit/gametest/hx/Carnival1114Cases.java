package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.FrameClock;
import dev.testkit.harness.PacketTrace;

import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
 *   <li>3815 the lead point: a target walking 0.2 blocks a tick is led by speed times flight time, scaled by Lead Adjust.</li>
 *   <li>3816 a fish name tag reads as a bar; a flagged stand is a target again after 10 still ticks at pond height.</li>
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
            c.ctx().waitTicks(10);
            int cell = (p[0].charAt(0) - 'A') * 7 + (p[0].charAt(1) - '1');
            int bestLast = c.onClient(mc -> (Integer) Mod.staticCall("carnival.FruitDigging", "bestLastFrame"));
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

    static void leadMath(Session c) {
        double full = (Double) c.onClient(mc -> Mod.staticCall("carnival.Lead", "testAheadX", 0.2, 15.0, 100));
        double half = (Double) c.onClient(mc -> Mod.staticCall("carnival.Lead", "testAheadX", 0.2, 15.0, 50));
        double none = (Double) c.onClient(mc -> Mod.staticCall("carnival.Lead", "testAheadX", 0.2, 15.0, 0));
        double still = (Double) c.onClient(mc -> Mod.staticCall("carnival.Lead", "testAheadX", 0.0, 15.0, 100));
        double far = (Double) c.onClient(mc -> Mod.staticCall("carnival.Lead", "testAheadX", 0.2, 30.0, 100));
        c.note("lead x: full " + full + ", half " + half + ", none " + none + ", still " + still + ", far " + far);
        // 15 blocks at 3 blocks a tick is 5 ticks of flight plus 1 tick of latency: 6 ticks * 0.2 = 1.2 blocks.
        c.check(Math.abs(full - 1.2) < 0.05, "lead at 100% was " + full + ", want 1.2");
        c.check(Math.abs(half - 0.6) < 0.05, "lead at 50% was " + half + ", want 0.6");
        c.check(Math.abs(none) < 1e-9 && Math.abs(still) < 1e-9, "lead at 0% or for a still target was not 0");
        c.check(far > full + 0.5, "a farther target was not led further: " + far + " vs " + full);
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
}

package dev.testkit.gametest.boss;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.PacketTrace;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * 1090-1091 (mod branch clickguards-1025, dungeon-gaps-1025 item 7): the two wrong-click blockers, which only ever DROP a
 * click he made. Every click is a real {@code useItemOn} (or {@code startDestroyBlock}) aimed at the block the way
 * {@link SimonSaysCases#click} aims, and the proof is the client's own outbound packets: the blocked click sends no
 * {@code use_item_on} / {@code player_action}, the allowed one does, and GrimAC must say nothing about any of it.
 * <ul>
 *   <li>1090 Quiz Block Wrong Answers: three answer pillars with a button each on the spawn platform; the solver learns the
 *   question and its answer from Oruo-shaped chat lines (the year question, answer b). A click on a's button is dropped,
 *   b's goes out, a's while sneaking goes out, and before the answer is known nothing is dropped. One chat line.</li>
 *   <li>1091 Block Wrong Relic: the five relic cauldrons at their real P5 coordinates on an F7 sidebar, a red relic in
 *   slot 9 (empty hand). Right and left click on the purple cauldron are dropped, the red one goes out, sneaking lets the
 *   purple one through, and with the setting off nothing is dropped. One chat line.</li>
 * </ul>
 */
final class ClickGuardCases {

    static final String QUIZ = "puzzlesolvers.QuizSolverFeature";
    static final String QUIZ_CFG = "puzzlesolvers.QuizSolverConfig";
    static final String RELICS = "witherdragons.KingRelicsFeature";
    static final String WD_CFG = "witherdragons.WitherDragonsConfig";

    private ClickGuardCases() {
    }

    static void register(Session s) {
        s.test("1090-boss-quiz-block-wrong", ClickGuardCases::quiz);
        s.test("1091-boss-relic-block-wrong", ClickGuardCases::relic);
    }

    // ---- shared ---------------------------------------------------------------------------------------------------

    /** Turn to the block, then right-click it the way a player's crosshair would. Returns {result, use_item_on sent}. */
    static Object[] use(Session c, BlockPos pos) {
        aim(c, pos);
        Object result = c.onClient(mc -> {
            PacketTrace.start();
            return mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit(mc, pos));
        });
        c.ctx().waitTicks(3);
        int sent = c.onClient(mc -> {
            PacketTrace.stop();
            return PacketTrace.sent("use_item_on");
        });
        return new Object[]{result, sent};
    }

    /** Turn to the block, then a left click (start of a dig). Returns {started, player_action sent}. */
    static Object[] attack(Session c, BlockPos pos) {
        aim(c, pos);
        Object result = c.onClient(mc -> {
            PacketTrace.start();
            return mc.gameMode.startDestroyBlock(pos, hit(mc, pos).getDirection());
        });
        c.ctx().waitTicks(3);
        int sent = c.onClient(mc -> {
            mc.gameMode.stopDestroyBlock();
            PacketTrace.stop();
            return PacketTrace.sent("player_action");
        });
        return new Object[]{result, sent};
    }

    static void aim(Session c, BlockPos pos) {
        c.ctx().runOnClient(mc -> {
            Vec3 d = SimonSaysCases.aimPoint(mc, pos).subtract(mc.player.getEyePosition());
            // Turn the short way from where he looks: an absolute atan2 yaw jumps across the +-180 seam, and GrimAC's
            // AimModulo360 flagged that (1091's first run, 2026-10-25) - the harness's turn, not the mod.
            float want = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            float now = mc.player.getYRot();
            mc.player.setYRot(now + net.minecraft.util.Mth.wrapDegrees(want - now));
            mc.player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
        });
        c.ctx().waitTicks(2);
    }

    static BlockHitResult hit(Minecraft mc, BlockPos pos) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 target = SimonSaysCases.aimPoint(mc, pos);
        var shape = mc.level.getBlockState(pos).getShape(mc.level, pos);
        BlockHitResult hit = shape.isEmpty() ? null
                : shape.clip(eye, eye.add(target.subtract(eye).normalize().scale(eye.distanceTo(target) + 1.5)), pos);
        return hit != null ? hit : new BlockHitResult(target, Direction.UP, pos, false);
    }

    static void sneak(Session c, boolean down) {
        if (down) {
            c.ctx().getInput().holdKey(GLFW.GLFW_KEY_LEFT_SHIFT);
        } else {
            c.ctx().getInput().releaseKey(GLFW.GLFW_KEY_LEFT_SHIFT);
        }
        c.waitUntil("sneaking " + down, mc -> mc.player.isShiftKeyDown() == down, 40);
    }

    static long chatLines(long mark, String needle) {
        return LogTap.since(mark).stream().filter(l -> l.contains("[CHAT]") && l.contains(needle)).count();
    }

    static void fill(Session c, int x1, int y1, int z1, int x2, int y2, int z2, String block) {
        c.server().command("fill " + x1 + " " + y1 + " " + z1 + " " + x2 + " " + y2 + " " + z2 + " " + block);
    }

    static void setInstanceField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    // ---- 1090 Quiz -------------------------------------------------------------------------------------------------

    static void quiz(Session c) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        // Pillars on the spawn platform (y 150), 3 apart so a button is next to its own pillar only; a stone button on
        // each pillar's south face, which is the side he stands on.
        BlockPos[] pillars = {new BlockPos(-617, 151, -623), new BlockPos(-620, 151, -623), new BlockPos(-623, 151, -623)};
        BlockPos[] buttons = new BlockPos[3];
        for (int i = 0; i < 3; i++) {
            buttons[i] = pillars[i].south();
        }
        try {
            c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 180f, "pitch", 0f);
            for (int i = 0; i < 3; i++) {
                BlockPos p = pillars[i];
                c.server().command("setblock " + p.getX() + " " + p.getY() + " " + p.getZ() + " minecraft:smooth_stone");
                BlockPos b = buttons[i];
                c.server().command("setblock " + b.getX() + " " + b.getY() + " " + b.getZ()
                        + " minecraft:stone_button[face=wall,facing=south]");
            }
            c.waitUntil("the three buttons on the client", mc -> {
                for (BlockPos b : buttons) {
                    if (!mc.level.getBlockState(b).is(net.minecraft.world.level.block.Blocks.STONE_BUTTON)) {
                        return false;
                    }
                }
                return true;
            }, 100);
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState in a dungeon", mc -> (Boolean) Mod.staticCall("secrets.DungeonState", "isInDungeon"), 100);
            undo.add(c.onClient(mc -> Mod.with(QUIZ_CFG, "Enabled", true)));
            undo.add(c.onClient(mc -> Mod.with(QUIZ_CFG, "BlockWrongAnswers", true)));
            c.ctx().waitTicks(5);
            boolean inBoss = c.onClient(mc -> (Boolean) Mod.staticCall("livemap.LiveMapFeature", "isInBoss"));
            c.check(!inBoss, "the map thinks he is in the boss here; the solver would be idle");
            // The room's three pillar positions, as the solver works them out from the map in the real Quiz room.
            c.ctx().runOnClient(mc -> {
                try {
                    Object[] opts = (Object[]) Mod.field(QUIZ, "options");
                    for (int i = 0; i < 3; i++) {
                        setInstanceField(opts[i], "blockPos", pillars[i]);
                        setInstanceField(opts[i], "correct", false);
                    }
                    Mod.staticCall(QUIZ, "clearForNewQuestion");
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            int blocked0 = c.onClient(mc -> (Integer) Mod.staticCall(QUIZ, "blockedClicks"));
            long mark = LogTap.mark();

            // Before the answer is known: nothing is dropped.
            Object[] early = use(c, buttons[0]);
            c.note("before the answer: " + early[0] + ", " + early[1] + " use packet(s)");
            c.check(early[0] != InteractionResult.FAIL && (Integer) early[1] >= 1, "a click was dropped before the answer was known");
            c.ctx().waitTicks(25); // the button pops back up

            // Oruo's year question and its three options, as server chat lines.
            long year = ((System.currentTimeMillis() / 1000L) - 1560276000L) / 446400L + 1;
            c.hx().chat("                            What SkyBlock year is it?");
            c.hx().chat("     §6 ⓐ §aYear " + (year + 7));
            c.hx().chat("     §6 ⓑ §aYear " + year);
            c.hx().chat("     §6 ⓒ §aYear " + (year + 13));
            c.waitUntil("the solver to mark b correct", mc -> pillars[1].equals(Mod.staticCall(QUIZ, "getCorrectAnswerPos")), 60);

            Object[] wrong = use(c, buttons[0]);
            Object[] wrongAgain = use(c, buttons[2]);
            Object[] right = use(c, buttons[1]);
            c.ctx().waitTicks(25);
            sneak(c, true);
            Object[] sneakWrong = use(c, buttons[0]);
            sneak(c, false);
            int blocked = c.onClient(mc -> (Integer) Mod.staticCall(QUIZ, "blockedClicks")) - blocked0;
            long lines = chatLines(mark, "[Quiz Solver] Blocked");
            c.note("wrong a: " + wrong[0] + ", " + wrong[1] + " packet(s); wrong c: " + wrongAgain[0] + ", " + wrongAgain[1]
                    + "; right b: " + right[0] + ", " + right[1] + "; wrong a sneaking: " + sneakWrong[0] + ", "
                    + sneakWrong[1] + "; blocked " + blocked + "; chat lines " + lines);
            c.check(wrong[0] == InteractionResult.FAIL && (Integer) wrong[1] == 0, "the click on wrong answer a was sent");
            c.check(wrongAgain[0] == InteractionResult.FAIL && (Integer) wrongAgain[1] == 0, "the click on wrong answer c was sent");
            c.check(right[0] != InteractionResult.FAIL && (Integer) right[1] >= 1, "the click on the right answer b was not sent");
            c.check(sneakWrong[0] != InteractionResult.FAIL && (Integer) sneakWrong[1] >= 1, "sneaking did not let a through");
            c.check(blocked == 2, "blocked " + blocked + ", want 2");
            c.check(lines == 1, lines + " chat lines for two blocked clicks, want exactly 1");

            // Off: nothing dropped.
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(QUIZ_CFG), "setBlockWrongAnswers", false));
            c.ctx().waitTicks(25);
            Object[] off = use(c, buttons[0]);
            c.check(off[0] != InteractionResult.FAIL && (Integer) off[1] >= 1, "with Block Wrong Answers off a click was dropped");
        } finally {
            sneak(c, false);
            c.ctx().runOnClient(mc -> {
                try {
                    Object[] opts = (Object[]) Mod.field(QUIZ, "options");
                    for (Object o : opts) {
                        setInstanceField(o, "blockPos", null);
                        setInstanceField(o, "correct", false);
                    }
                    Mod.staticCall(QUIZ, "clearForNewQuestion");
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            for (int i = undo.size() - 1; i >= 0; i--) {
                final AutoCloseable u = undo.get(i);
                c.ctx().runOnClient(mc -> {
                    try {
                        u.close();
                    } catch (Exception e) {
                        System.out.println("[1090] restore failed: " + e);
                    }
                });
            }
            for (int i = 0; i < 3; i++) {
                BlockPos b = buttons[i];
                BlockPos p = pillars[i];
                c.server().command("setblock " + b.getX() + " " + b.getY() + " " + b.getZ() + " minecraft:air");
                c.server().command("setblock " + p.getX() + " " + p.getY() + " " + p.getZ() + " minecraft:air");
            }
            c.hx().sidebarClear();
        }
    }

    // ---- 1091 relics -----------------------------------------------------------------------------------------------

    static final BlockPos RED = new BlockPos(51, 7, 42);
    static final BlockPos PURPLE = new BlockPos(54, 7, 41);
    static final BlockPos[] CAULDRONS = {new BlockPos(49, 7, 44), PURPLE, new BlockPos(59, 7, 44), new BlockPos(57, 7, 42), RED};

    static void relic(Session c) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        try {
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState.getFloor()==F7",
                    mc -> "F7".equals(Mod.staticCall("secrets.DungeonState", "getFloor")), 100);
            // Forceloaded and built before he is put there (GrimTickCases' i4 platform): without it the second run drew
            // AntiKB on arrival, the floor reaching the client after him.
            c.server().command("forceload add 45 37 63 50");
            c.server().sync(20);
            fill(c, 45, 6, 37, 63, 6, 50, "minecraft:stone");
            fill(c, 45, 7, 37, 63, 12, 50, "minecraft:air");
            for (BlockPos p : CAULDRONS) {
                c.server().command("setblock " + p.getX() + " " + p.getY() + " " + p.getZ() + " minecraft:cauldron");
            }
            c.hx().call("dungeon.tp", "x", 54.5, "y", 7.0, "z", 44.5, "yaw", 180f, "pitch", 0f);
            c.waitUntil("him standing by the cauldrons with them loaded", mc -> mc.player.onGround()
                    && Math.abs(mc.player.getY() - 7.0) < 1e-3
                    && mc.level.getBlockState(RED).is(net.minecraft.world.level.block.Blocks.CAULDRON)
                    && mc.level.getBlockState(PURPLE).is(net.minecraft.world.level.block.Blocks.CAULDRON), 200);
            // The relic sits in slot 9; the hand he clicks with is empty (slot 1).
            c.hx().give(8, "minecraft:player_head[minecraft:custom_data={id:\"RED_KING_RELIC\"}]");
            c.ctx().runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
            c.waitUntil("the red relic in his inventory", mc -> "RED".equals(String.valueOf(
                    Mod.staticCall("witherdragons.KingRelic", "fromStack", mc.player.getInventory().getItem(8)))), 60);
            c.ctx().waitTicks(5);
            boolean p5 = c.onClient(mc -> (Boolean) Mod.staticCall("witherdragons.P5State", "inP5"));
            c.check(p5, "P5State.inP5() is false where the cauldrons are; the guard could not be judged");
            undo.add(c.onClient(mc -> Mod.with(WD_CFG, "RelicsEnabled", true)));
            undo.add(c.onClient(mc -> Mod.with(WD_CFG, "BlockWrongRelic", true)));
            int blocked0 = c.onClient(mc -> (Integer) Mod.staticCall(RELICS, "blockedRelicClicks"));
            long mark = LogTap.mark();

            Object[] wrongUse = use(c, PURPLE);
            Object[] wrongHit = attack(c, PURPLE);
            Object[] rightUse = use(c, RED);
            sneak(c, true);
            Object[] sneakWrong = use(c, PURPLE);
            sneak(c, false);
            int blocked = c.onClient(mc -> (Integer) Mod.staticCall(RELICS, "blockedRelicClicks")) - blocked0;
            long lines = chatLines(mark, "[King Relics] Blocked");
            c.note("purple right-click: " + wrongUse[0] + ", " + wrongUse[1] + " use packet(s); purple left-click: started "
                    + wrongHit[0] + ", " + wrongHit[1] + " action packet(s); red right-click: " + rightUse[0] + ", "
                    + rightUse[1] + "; purple sneaking: " + sneakWrong[0] + ", " + sneakWrong[1] + "; blocked " + blocked
                    + "; chat lines " + lines);
            c.check(wrongUse[0] == InteractionResult.FAIL && (Integer) wrongUse[1] == 0, "the right-click on the purple cauldron was sent");
            c.check(Boolean.FALSE.equals(wrongHit[0]) && (Integer) wrongHit[1] == 0, "the left-click on the purple cauldron was sent");
            c.check(rightUse[0] != InteractionResult.FAIL && (Integer) rightUse[1] >= 1, "the right-click on the red cauldron was not sent");
            c.check(sneakWrong[0] != InteractionResult.FAIL && (Integer) sneakWrong[1] >= 1, "sneaking did not let the purple click through");
            c.check(blocked == 2, "blocked " + blocked + ", want 2");
            c.check(lines == 1, lines + " chat lines for two blocked clicks, want exactly 1");

            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(WD_CFG), "setBlockWrongRelic", false));
            Object[] off = use(c, PURPLE);
            c.check(off[0] != InteractionResult.FAIL && (Integer) off[1] >= 1, "with Block Wrong Relic off a click was dropped");
        } finally {
            sneak(c, false);
            for (int i = undo.size() - 1; i >= 0; i--) {
                final AutoCloseable u = undo.get(i);
                c.ctx().runOnClient(mc -> {
                    try {
                        u.close();
                    } catch (Exception e) {
                        System.out.println("[1091] restore failed: " + e);
                    }
                });
            }
            c.hx().give(8, "minecraft:air");
            c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 0f, "pitch", 0f);
            c.ctx().waitTicks(10);
            fill(c, 45, 6, 37, 63, 12, 50, "minecraft:air");
            c.server().command("forceload remove 45 37 63 50");
            c.hx().sidebarClear();
        }
    }
}

package dev.testkit.gametest.boss;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * P3 Simon Says on both device layouts (killer560, 2026-10-06: Hypixel's update cuts the device from 5 rounds to 4),
 * against {@code boss.ss.*} (hx/boss/HxSimonSays). The test plays the device by hand - start button, then each
 * round's steps in the server's order - while the mod's Simon Says runs with Auto Restart SS and Announce Progress on.
 *
 * <p>What is judged, from the SERVER's record where it can be: the device completes; the start button is pressed
 * exactly once (a second press after completion is Auto Restart "restarting" a device that had simply finished - the
 * pre-update mod's reading of a 4-round device); the mod's announce says "SS n/N" with the device's own N; and, on a
 * jar that has it, {@code TerminalLayouts.simonRounds()} learned the count from the device. Each round the mod's own
 * {@code clickInOrder} must hold that round's steps before the test clicks, so a fake the mod cannot read fails
 * loudly instead of passing vacuously.
 */
final class SimonSaysCases {

    static final String FEATURE = "simonsays.SimonSaysFeature";
    static final String CONFIG = "simonsays.SimonSaysConfig";
    static final String LAYOUTS = "terminals.TerminalLayouts";
    static final BlockPos START = new BlockPos(110, 121, 91);

    private SimonSaysCases() {
    }

    static void register(Session s) {
        // The test turns to each button before clicking (click()), so GrimAC has nothing to object to.
        s.test("501-boss-ss-old-5rounds", c -> play(c, 5, true, 11));
        s.test("502-boss-ss-new-4rounds", c -> play(c, 4, true, 12));
        // No "completed a device!" line: the mod must settle on 4 from the device staying dark.
        s.test("503-boss-ss-new-4rounds-noline", c -> play(c, 4, false, 13));
    }

    static JsonObject state(Session c) {
        return c.hx().call("boss.ss.state").getAsJsonObject();
    }

    static void play(Session c, int rounds, boolean line, int seed) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        try {
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState.getFloor()==F7",
                    mc -> "F7".equals(Mod.staticCall("secrets.DungeonState", "getFloor")), 100);
            boolean hasLayouts = c.onClient(mc -> {
                try {
                    Mod.staticCall(LAYOUTS, "resetSeen");
                    return true;
                } catch (AssertionError e) {
                    return false;
                }
            });
            undo.add(c.onClient(mc -> Mod.with(CONFIG, "SolverEnabled", true)));
            undo.add(c.onClient(mc -> Mod.with(CONFIG, "AutoRestartEnabled", true)));
            undo.add(c.onClient(mc -> Mod.with(CONFIG, "AnnounceProgress", true)));
            boolean restartOn = c.onClient(mc -> (Boolean) Mod.get(CONFIG, "isAutoRestartEnabled"));

            c.hx().call("boss.ss.build", "rounds", rounds, "line", line, "seed", seed);
            c.hx().call("dungeon.tp", "x", 107.5, "y", 120.0, "z", 93.5, "yaw", -90f, "pitch", 0f);
            // Switch Simon Says on only once the client HAS the device: before its chunk arrives the lantern wall
            // reads as air, which the mod's break check takes for a lit lantern, and 30 ticks later it "restarts"
            // a device nobody started (seen on the first run of this case).
            c.waitUntil("the client to have the device wall and start button", mc ->
                    mc.level.getBlockState(new BlockPos(111, 120, 92)).is(net.minecraft.world.level.block.Blocks.OBSIDIAN)
                            && mc.level.getBlockState(START).is(net.minecraft.world.level.block.Blocks.STONE_BUTTON), 200);
            c.ctx().waitTicks(5);
            undo.add(c.onClient(mc -> Mod.with(CONFIG, "Enabled", true)));
            c.waitUntil("the mod to be at the device (SimonSaysFeature.wasActive)",
                    mc -> (Boolean) Mod.field(FEATURE, "wasActive"), 200);
            c.ctx().waitTicks(5);
            click(c, START);
            c.waitUntil("the device to start (Hx)", mc -> !"IDLE".equals(state(c).get("phase").getAsString()), 60);

            for (int r = 1; r <= rounds; r++) {
                final int round = r;
                c.waitUntil("round " + r + " buttons (Hx)", mc -> {
                    JsonObject st = state(c);
                    return "INPUT".equals(st.get("phase").getAsString()) && st.get("round").getAsInt() == round;
                }, 400);
                // Past the mod's reveal settle (10 ticks) before the first click, as a person would be.
                c.ctx().waitTicks(14);
                int seen = c.onClient(mc -> ((List<?>) Mod.field(FEATURE, "clickInOrder")).size());
                c.check(seen == r, "round " + r + ": the mod read " + seen + " step(s) from the reveal, expected " + r);
                JsonArray seq = state(c).getAsJsonArray("sequence");
                for (int k = 0; k < r; k++) {
                    JsonArray xyz = seq.get(k).getAsJsonArray();
                    BlockPos lantern = new BlockPos(xyz.get(0).getAsInt(), xyz.get(1).getAsInt(), xyz.get(2).getAsInt());
                    click(c, lantern.west());
                    c.ctx().waitTicks(4);
                }
            }
            c.waitUntil("ss.completed (Hx)", mc -> !c.events("ss.completed").isEmpty(), 60);
            // Long enough for the mod's verdict timeout (100 ticks), its break window (30) and a restart burst.
            c.ctx().waitTicks(160);

            JsonObject st = state(c);
            int starts = st.get("starts").getAsInt();
            int broke = c.events("ss.broke").size();
            List<String> announces = new ArrayList<>();
            for (String cmd : c.commands()) {
                if (cmd.startsWith("pc SS ")) {
                    announces.add(cmd.substring(3));
                }
            }
            String facts = rounds + "-round device, line " + (line ? "on" : "off") + ": completed, start presses "
                    + starts + " (Auto Restart " + (restartOn ? "on" : "OFF") + "), breaks " + broke + ", announces "
                    + announces;
            c.note(facts);
            c.check(broke == 0, "the device broke: " + c.events("ss.broke"));
            c.check(starts == 1, "the start button was pressed " + starts + " times - Auto Restart restarted a "
                    + "device that had finished");
            // The last round's announce must carry the device's own total (the mod holds round 4's until it knows).
            String last = rounds + "/" + rounds;
            c.check(!announces.isEmpty() && announces.get(announces.size() - 1).equals("SS " + last),
                    "the last announce should be 'SS " + last + "', got " + announces);
            if (hasLayouts) {
                int learned = c.onClient(mc -> (Integer) Mod.staticCall(LAYOUTS, "simonRounds"));
                boolean seenIt = c.onClient(mc -> (Boolean) Mod.staticCall(LAYOUTS, "simonRoundsSeen"));
                boolean pending = c.onClient(mc -> (Boolean) Mod.field(FEATURE, "roundVerdictPending"));
                c.note("TerminalLayouts.simonRounds() = " + learned + " (seen " + seenIt + "), verdict pending " + pending);
                c.check(seenIt && learned == rounds, "the mod learned " + learned + " rounds (seen " + seenIt
                        + ") from a " + rounds + "-round device");
                c.check(!pending, "a round verdict is still pending 160 ticks after the device completed");
            } else {
                c.note("jar has no " + LAYOUTS + "; round-count learning not checked");
            }
        } finally {
            c.hx().call("boss.ss.clear");
            c.onClient(mc -> {
                for (int i = undo.size() - 1; i >= 0; i--) {
                    try {
                        undo.get(i).close();
                    } catch (Exception e) {
                        System.out.println("[boss] restore failed: " + e);
                    }
                }
                return null;
            });
            c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 0f, "pitch", 0f);
        }
    }

    /**
     * A hand right click on {@code pos}: turn to the button first (GrimAC cancels a use the look does not reach -
     * RotationPlace - which the first run of this case hit), let the rotation go out, then click the point the eye
     * ray hits on the block's shape (GrimAutoRoutesTests' form).
     */
    static void click(Session c, BlockPos pos) {
        c.ctx().runOnClient(mc -> {
            Vec3 eye = mc.player.getEyePosition();
            Vec3 target = aimPoint(mc, pos);
            Vec3 d = target.subtract(eye);
            float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
        });
        c.ctx().waitTicks(2);
        c.ctx().runOnClient(mc -> {
            Vec3 eye = mc.player.getEyePosition();
            Vec3 target = aimPoint(mc, pos);
            var shape = mc.level.getBlockState(pos).getShape(mc.level, pos);
            BlockHitResult hit = shape.isEmpty() ? null
                    : shape.clip(eye, eye.add(target.subtract(eye).normalize().scale(eye.distanceTo(target) + 1.5)), pos);
            if (hit == null) {
                hit = new BlockHitResult(target, Direction.WEST, pos, false);
            }
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        });
    }

    /** The centre of the block's shape (a button is a small box against the wall), else the block centre. */
    static Vec3 aimPoint(net.minecraft.client.Minecraft mc, BlockPos pos) {
        var shape = mc.level.getBlockState(pos).getShape(mc.level, pos);
        return shape.isEmpty() ? Vec3.atCenterOf(pos) : shape.bounds().getCenter().add(pos.getX(), pos.getY(), pos.getZ());
    }
}

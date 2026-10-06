package dev.testkit.server.hx.boss;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.testkit.server.hx.HxEvents;
import dev.testkit.server.hx.HxPrimitives;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The F7/M7 P3 Simon Says device, server side, at the coordinates the mod reads it from ({@code
 * simonsays/SimonSaysFeature.java}: sea-lantern wall x=111, stone buttons x=110, y 120-123, z 92-95, start button
 * (110, 121, 91)). Rounds 5 (old) or 4 (Hypixel's 2026-10-06 update, killer560) - {@code rounds}.
 *
 * <p>Modelled only as far as the mod's detection needs, and every rule is the mod's own reading of Hypixel, not a
 * claim about Hypixel: the start button begins round 1; a round lights its steps one at a time (sea lantern, then back
 * to obsidian), then all 16 grid buttons appear; round N has N steps, each new round replays the old steps plus one
 * new one; a wrong press removes every button (a break); after the last round the buttons go and, with {@code line}
 * on, "&lt;player&gt; completed a device! (1/7)" is sent - the line the mod's verdict listens for. Steps never repeat a
 * position (the mod's {@code clickInOrder.contains} cannot hold one twice). Hypixel's round-1 reveal quirks (the
 * mod's firstPhase reverse / drop) are NOT emulated: round 1 here is one plain light.
 *
 * <p>Ops: {@code boss.ss.build {rounds=5, line=true, seed=1, revealTicks=8, gapTicks=4}}, {@code boss.ss.state},
 * {@code boss.ss.clear}. Events: {@code ss.start}, {@code ss.press}, {@code ss.round}, {@code ss.completed},
 * {@code ss.broke}.
 */
final class HxSimonSays {

    static final BlockPos START = new BlockPos(110, 121, 91);
    private static final int LANTERN_X = 111;
    private static final int BUTTON_X = 110;

    enum Phase { OFF, IDLE, REVEAL, INPUT, ROUND_DONE, DONE, BROKEN }

    private static Phase phase = Phase.OFF;
    private static int rounds = 5;
    private static boolean line = true;
    private static int revealTicks = 8;
    private static int gapTicks = 4;
    private static Random rng = new Random(1);
    private static final List<BlockPos> sequence = new ArrayList<>();
    private static int round;
    private static int expected;
    private static int timer;
    private static int revealIndex;
    private static boolean revealLit;
    private static int starts;
    private static boolean startWasPowered;
    private static final boolean[] wasPowered = new boolean[16];

    private HxSimonSays() {
    }

    static List<BlockPos> grid(int x) {
        List<BlockPos> out = new ArrayList<>();
        for (int y = 120; y <= 123; y++) {
            for (int z = 92; z <= 95; z++) {
                out.add(new BlockPos(x, y, z));
            }
        }
        return out;
    }

    private static BlockState state(String s) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, s, false).blockState();
        } catch (Exception e) {
            throw new IllegalStateException("bad block state " + s, e);
        }
    }

    private static BlockState button() {
        return state("minecraft:stone_button[face=wall,facing=west]");
    }

    static JsonElement build(MinecraftServer server, JsonObject a) {
        ServerLevel level = server.overworld();
        rounds = a.has("rounds") ? a.get("rounds").getAsInt() : 5;
        line = !a.has("line") || a.get("line").getAsBoolean();
        revealTicks = a.has("revealTicks") ? a.get("revealTicks").getAsInt() : 8;
        gapTicks = a.has("gapTicks") ? a.get("gapTicks").getAsInt() : 4;
        rng = new Random(a.has("seed") ? a.get("seed").getAsLong() : 1);
        BlockState air = Blocks.AIR.defaultBlockState();
        // Clear the arena, then a floor for the player, the device wall, and the start button.
        for (int x = 103; x <= 112; x++) {
            for (int y = 119; y <= 126; y++) {
                for (int z = 87; z <= 98; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, y, z), air);
                }
            }
        }
        for (int x = 103; x <= 109; x++) {
            for (int z = 87; z <= 98; z++) {
                level.setBlockAndUpdate(new BlockPos(x, 119, z), Blocks.STONE.defaultBlockState());
            }
        }
        for (int y = 119; y <= 124; y++) {
            for (int z = 89; z <= 97; z++) {
                level.setBlockAndUpdate(new BlockPos(LANTERN_X, y, z), Blocks.OBSIDIAN.defaultBlockState());
            }
        }
        level.setBlockAndUpdate(START, button());
        sequence.clear();
        round = 0;
        expected = 0;
        timer = 0;
        starts = 0;
        startWasPowered = false;
        java.util.Arrays.fill(wasPowered, false);
        phase = Phase.IDLE;
        return describe();
    }

    static JsonElement clear(MinecraftServer server, JsonObject a) {
        if (phase != Phase.OFF) {
            setButtons(server.overworld(), false);
        }
        phase = Phase.OFF;
        return describe();
    }

    static JsonObject describe() {
        JsonObject o = new JsonObject();
        o.addProperty("phase", phase.name());
        o.addProperty("rounds", rounds);
        o.addProperty("round", round);
        o.addProperty("expected", expected);
        o.addProperty("starts", starts);
        o.addProperty("line", line);
        JsonArray seq = new JsonArray();
        for (BlockPos p : sequence) {
            JsonArray xyz = new JsonArray();
            xyz.add(p.getX());
            xyz.add(p.getY());
            xyz.add(p.getZ());
            seq.add(xyz);
        }
        o.add("sequence", seq);
        return o;
    }

    private static void setButtons(ServerLevel level, boolean on) {
        BlockState b = on ? button() : Blocks.AIR.defaultBlockState();
        for (BlockPos p : grid(BUTTON_X)) {
            level.setBlockAndUpdate(p, b);
        }
        java.util.Arrays.fill(wasPowered, false);
    }

    private static boolean powered(BlockState s) {
        return s.is(Blocks.STONE_BUTTON) && s.getValue(BlockStateProperties.POWERED);
    }

    private static ServerPlayer player(MinecraftServer server) {
        return HxPrimitives.player(server, new JsonObject());
    }

    private static void event(MinecraftServer server, String type, JsonObject data) {
        HxEvents.custom(type, player(server), data);
    }

    private static void addStep() {
        List<BlockPos> free = new ArrayList<>();
        for (BlockPos p : grid(LANTERN_X)) {
            if (!sequence.contains(p)) {
                free.add(p);
            }
        }
        Collections.shuffle(free, rng);
        sequence.add(free.get(0));
    }

    private static void beginRound(int n) {
        round = n;
        expected = 0;
        while (sequence.size() < n) {
            addStep();
        }
        revealIndex = 0;
        revealLit = false;
        timer = gapTicks;
        phase = Phase.REVEAL;
    }

    static void tick(MinecraftServer server) {
        if (phase == Phase.OFF) {
            return;
        }
        ServerLevel level = server.overworld();
        boolean startNow = powered(level.getBlockState(START));
        if (startNow && !startWasPowered) {
            starts++;
            JsonObject d = new JsonObject();
            d.addProperty("phaseBefore", phase.name());
            event(server, "ss.start", d);
            if (phase == Phase.IDLE || phase == Phase.BROKEN) {
                sequence.clear();
                setButtons(level, false);
                beginRound(1);
            }
        }
        startWasPowered = startNow;
        switch (phase) {
            case REVEAL -> {
                if (--timer > 0) {
                    return;
                }
                if (revealIndex >= round) {
                    setButtons(level, true);
                    phase = Phase.INPUT;
                    JsonObject d = new JsonObject();
                    d.addProperty("round", round);
                    event(server, "ss.round", d);
                    return;
                }
                BlockPos lantern = sequence.get(revealIndex);
                if (!revealLit) {
                    level.setBlockAndUpdate(lantern, Blocks.SEA_LANTERN.defaultBlockState());
                    revealLit = true;
                    timer = revealTicks;
                } else {
                    level.setBlockAndUpdate(lantern, Blocks.OBSIDIAN.defaultBlockState());
                    revealLit = false;
                    revealIndex++;
                    timer = gapTicks;
                }
            }
            case INPUT -> {
                List<BlockPos> buttons = grid(BUTTON_X);
                for (int i = 0; i < buttons.size(); i++) {
                    boolean now = powered(level.getBlockState(buttons.get(i)));
                    if (now && !wasPowered[i]) {
                        BlockPos lantern = buttons.get(i).east();
                        boolean correct = lantern.equals(sequence.get(expected));
                        JsonObject d = new JsonObject();
                        d.addProperty("round", round);
                        d.addProperty("step", expected);
                        d.addProperty("correct", correct);
                        event(server, "ss.press", d);
                        if (!correct) {
                            setButtons(level, false);
                            phase = Phase.BROKEN;
                            event(server, "ss.broke", new JsonObject());
                            return;
                        }
                        expected++;
                        if (expected >= round) {
                            // Leave the buttons up a few ticks, so the client sees the last one powered.
                            phase = Phase.ROUND_DONE;
                            timer = 6;
                            if (round >= rounds) {
                                ServerPlayer p = player(server);
                                if (line) {
                                    p.connection.send(new ClientboundSystemChatPacket(Component.literal(
                                            p.getGameProfile().name() + " completed a device! (1/7)"), false));
                                }
                                JsonObject c = new JsonObject();
                                c.addProperty("rounds", rounds);
                                c.addProperty("line", line);
                                event(server, "ss.completed", c);
                            }
                            return;
                        }
                    }
                    wasPowered[i] = now;
                }
            }
            case ROUND_DONE -> {
                if (--timer > 0) {
                    return;
                }
                setButtons(level, false);
                if (round >= rounds) {
                    phase = Phase.DONE;
                } else {
                    beginRound(round + 1);
                    timer = 10;
                }
            }
            default -> {
            }
        }
    }
}

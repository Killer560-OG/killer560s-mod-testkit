package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The sim's launch items and its no-knockback rule (mod simitems-1105, killer560 2026-11-05: "add spring boots to the boss
 * sim as well and jerry chine gun" / "and bonzo staff" / "make sure you cannot take knockback in the sim"). All on a stone
 * platform high over the flat sim room, so nothing but the floor is in reach.
 *
 * <ul>
 * <li>{@code 1105-sim-bonzo}: three right clicks in three ticks fire ONE balloon (4-tick gate); it bursts on the floor ahead
 * and the server's push takes him about 1.7 up and several blocks back.</li>
 * <li>{@code 1105-sim-jerry}: five shots in one server tick fire three (Hypixel's cap); straight down, a shot lifts him
 * about 2.4 and no further sideways; the action bar shows Rapid-fire's mana.</li>
 * <li>{@code 1105-sim-springboots}: the boots on, 32 ticks of sneak count 10 notes (one per 3 ticks), and letting go
 * launches him to the Spring Boots table's 22.5 blocks for 10 notes.</li>
 * <li>{@code 1105-sim-noknockback}: a zombie's hit, a flying arrow and an explosion each REALLY hit him (the server records
 * the damage, and the knockback the server sent is counted as kept from him), and his position does not move; a Bonzo
 * balloon in the same run still launches him.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimLaunchItemsTests implements FabricClientGameTest {

    private static final String BONZO = "1105-sim-bonzo";
    private static final String JERRY = "1105-sim-jerry";
    private static final String SPRING = "1105-sim-springboots";
    private static final String NOKB = "1105-sim-noknockback";
    private static final String ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String LAUNCH = "com.killer560.hub.roomsim.SimLaunchItems";
    private static final String FILTER = "com.killer560.hub.roomsim.SimLaunch";
    private static final int PLATFORM_Y = 230;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        List<String> chosen = new ArrayList<>();
        for (String n : new String[]{BONZO, JERRY, SPRING, NOKB}) {
            if (!Scenario.skip(n)) {
                chosen.add(n);
            }
        }
        if (chosen.isEmpty()) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(20);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder",
                "buildFlatTest", new Class<?>[]{Minecraft.class}, new Object[]{mc})));
        ctx.waitFor(mc -> mc.level != null, 2000);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitTicks(40);
        Vec3 home = ctx.computeOnClient(mc -> mc.player.position());
        command(ctx, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", (int) home.x - 25, PLATFORM_Y,
                (int) home.z - 25, (int) home.x + 25, PLATFORM_Y, (int) home.z + 25));
        command(ctx, "gamemode survival @p");
        List<String> failures = new ArrayList<>();
        try {
            for (String name : chosen) {
                List<String> f = new ArrayList<>();
                try {
                    tp(ctx, home.x, home.z, 0f, 0f);
                    switch (name) {
                        case BONZO -> bonzo(ctx, f);
                        case JERRY -> jerry(ctx, f);
                        case SPRING -> spring(ctx, f);
                        default -> noKnockback(ctx, f, home);
                    }
                } catch (Throwable t) {
                    f.add(name + " threw: " + t);
                    t.printStackTrace(System.out);
                } finally {
                    ctx.getInput().releaseKey(o -> o.keyShift);
                }
                for (String s : f) {
                    println(name, "FAIL: " + s);
                    failures.add(name + ": " + s);
                }
                println(name, f.isEmpty() ? "PASS" : "FAIL (" + f.size() + ")");
            }
        } finally {
            leave(ctx);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
    }

    // ============================================================================================== cases

    private static void bonzo(ClientGameTestContext ctx, List<String> f) {
        hold(ctx, "BONZO_STAFF");
        look(ctx, 0f, 70f);
        int fired = num(LAUNCH, "bonzoFired"), launched = num(LAUNCH, "launches");
        Vec3 start = pos(ctx);
        for (int i = 0; i < 3; i++) {
            use(ctx);
            ctx.waitTick();
        }
        double[] fl = fly(ctx, start, 40);
        Vec3 end = pos(ctx);
        int shots = num(LAUNCH, "bonzoFired") - fired, pushes = num(LAUNCH, "launches") - launched;
        println(BONZO, String.format(Locale.ROOT, "%d balloon(s) from 3 clicks, %d launch(es), rose %.2f, flew %.2f, z %.2f -> %.2f",
                shots, pushes, fl[0], fl[1], start.z, end.z));
        expect(f, "balloons from 3 clicks in 3 ticks (one per 4 ticks)", shots, 1);
        expect(f, "launches", pushes, 1);
        if (!(end.z < start.z - 2.0)) {
            f.add("not pushed back, away from the burst (z " + start.z + " -> " + end.z + ")");
        }
        if (!(fl[0] > 1.2 && fl[0] < 2.3)) {
            f.add("rose " + fl[0] + " (Hypixel: up to about 1.7)");
        }
        if (!(fl[1] > 3.0 && fl[1] < 14.0)) {
            f.add("flew " + fl[1] + " flat (Hypixel: about 8 back)");
        }
        ctx.waitTicks(10);
        int again = num(LAUNCH, "bonzoFired");
        use(ctx);
        ctx.waitTicks(3);
        expect(f, "a click after the gate fires again", num(LAUNCH, "bonzoFired") - again, 1);
        ctx.waitTicks(40);
    }

    private static void jerry(ClientGameTestContext ctx, List<String> f) {
        hold(ctx, "JERRY_STAFF");
        look(ctx, 0f, 90f);
        long mark = LogTap.mark();
        int fired = num(LAUNCH, "jerryFired"), launched = num(LAUNCH, "launches");
        Vec3 start = pos(ctx);
        // Five use packets the server handles in ONE tick: Hypixel fires three.
        ctx.runOnClient(mc -> {
            for (int i = 0; i < 5; i++) {
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            }
        });
        double[] fl = fly(ctx, start, 40);
        int shots = num(LAUNCH, "jerryFired") - fired, pushes = num(LAUNCH, "launches") - launched;
        println(JERRY, String.format(Locale.ROOT, "%d bullet(s) from 5 uses in a tick, %d launch(es), rose %.2f, drifted %.2f",
                shots, pushes, fl[0], fl[1]));
        expect(f, "bullets from 5 uses in one tick (3 a tick)", shots, 3);
        if (pushes < 1) {
            f.add("no bullet launched him");
        }
        if (!(fl[0] > 1.8 && fl[0] < 3.2)) {
            f.add("rose " + fl[0] + " (one shot: about 2.4)");
        }
        if (fl[1] > 0.5) {
            f.add("looking straight down he drifted " + fl[1]);
        }
        boolean bar = false;
        for (String l : LogTap.since(mark)) {
            bar |= l.contains("Mana (") && l.contains("Rapid-fire");
        }
        if (!bar) {
            println(JERRY, "note: no Rapid-fire action bar line in the log (the overlay is not logged on every jar)");
        }
    }

    private static void spring(ClientGameTestContext ctx, List<String> f) {
        serverPlayer(ctx, sp -> {
            sp.setItemSlot(EquipmentSlot.FEET, build("SPRING_BOOTS"));
            return null;
        });
        ctx.waitTicks(5);
        int launched = num(LAUNCH, "springLaunches");
        Vec3 start = pos(ctx);
        ctx.getInput().holdKey(o -> o.keyShift);
        ctx.waitTicks(32);
        ctx.getInput().releaseKey(o -> o.keyShift);
        double[] fl = fly(ctx, start, 70);
        float h = ((Number) ModUnderTest.staticCall(LAUNCH, "lastSpringHeight")).floatValue();
        int n = num(LAUNCH, "springLaunches") - launched;
        println(SPRING, String.format(Locale.ROOT, "%d launch(es), table height %.1f, rose %.2f", n, h, fl[0]));
        expect(f, "launches", n, 1);
        expect(f, "table height for 10 notes", h, 22.5f);
        if (Math.abs(fl[0] - h) > 1.5) {
            f.add("rose " + fl[0] + ", the table says " + h);
        }
        serverPlayer(ctx, sp -> {
            sp.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            return null;
        });
        ctx.waitTicks(60);
    }

    private static void noKnockback(ClientGameTestContext ctx, List<String> f, Vec3 home) {
        ctx.waitTicks(20);
        Vec3 at = pos(ctx);
        int dropped = num(FILTER, "dropped");
        // 1) a zombie's hit (vanilla's knockback from the attacker), 2) a real arrow flying into him, 3) an explosion.
        command(ctx, String.format(Locale.ROOT, "summon minecraft:zombie %.2f %.2f %.2f {NoAI:1b,Silent:1b,Tags:[\"tkkb\"]}",
                at.x, at.y, at.z + 1.2));
        ctx.waitTicks(3);
        String mob = serverPlayer(ctx, sp -> {
            ServerLevel level = (ServerLevel) sp.level();
            Entity z = level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                    sp.getBoundingBox().inflate(4), e -> e.entityTags().contains("tkkb")).stream().findFirst().orElse(null);
            if (z == null) {
                return "no zombie";
            }
            sp.hurtServer(level, level.damageSources().mobAttack((net.minecraft.world.entity.LivingEntity) z), 1f);
            return sp.getLastDamageSource() == null ? "none" : sp.getLastDamageSource().getMsgId();
        });
        ctx.waitTicks(15);
        command(ctx, "effect give @p minecraft:instant_health 1 1 true");
        command(ctx, String.format(Locale.ROOT, "summon minecraft:arrow %.2f %.2f %.2f {Motion:[0.0d,0.0d,-1.6d]}",
                at.x, at.y + 1.0, at.z + 3.5));
        ctx.waitTicks(15);
        String arrow = serverPlayer(ctx, sp -> sp.getLastDamageSource() == null ? "none" : sp.getLastDamageSource().getMsgId());
        command(ctx, "effect give @p minecraft:instant_health 1 1 true");
        serverPlayer(ctx, sp -> {
            sp.level().explode(null, sp.getX() + 2.5, sp.getY() + 0.5, sp.getZ(), 1.5f, Level.ExplosionInteraction.NONE);
            return null;
        });
        ctx.waitTicks(3);
        String boom = serverPlayer(ctx, sp -> sp.getLastDamageSource() == null ? "none" : sp.getLastDamageSource().getMsgId());
        ctx.waitTicks(20);
        Vec3 now = pos(ctx);
        int kept = num(FILTER, "dropped") - dropped;
        double moved = now.distanceTo(at);
        println(NOKB, String.format(Locale.ROOT, "hits: mob %s, arrow %s, explosion %s; knockback kept from him %d; moved %.3f",
                mob, arrow, boom, kept, moved));
        expect(f, "the zombie's hit landed", mob, "mob");
        expect(f, "the arrow landed", arrow, "arrow");
        expect(f, "the explosion landed", boom, "explosion");
        if (kept < 3) {
            f.add("only " + kept + " knockback packet(s) were kept from him for 3 hits (the hits did not knock back?)");
        }
        if (moved > 0.05) {
            f.add("he moved " + moved + " blocks from the hits");
        }
        command(ctx, "kill @e[tag=tkkb]");
        command(ctx, "effect give @p minecraft:instant_health 1 3 true");
        // A deliberate launch still works in the same run.
        hold(ctx, "BONZO_STAFF");
        look(ctx, 0f, 70f);
        int launched = num(LAUNCH, "launches");
        Vec3 s = pos(ctx);
        use(ctx);
        double[] fl = fly(ctx, s, 30);
        expect(f, "a Bonzo launch after the hits", num(LAUNCH, "launches") - launched, 1);
        if (fl[1] < 3.0) {
            f.add("the Bonzo launch moved him only " + fl[1]);
        }
    }

    // ============================================================================================== helpers

    private static ItemStack build(String id) {
        return (ItemStack) ModUnderTest.staticCall(ITEMS, "build", new Class<?>[]{String.class}, new Object[]{id});
    }

    private static void hold(ClientGameTestContext ctx, String id) {
        serverPlayer(ctx, sp -> {
            sp.setItemInHand(InteractionHand.MAIN_HAND, build(id));
            return null;
        });
        ctx.waitTicks(5);
    }

    private static void use(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND));
    }

    private static <T> T serverPlayer(ClientGameTestContext ctx, Function<ServerPlayer, T> body) {
        Object[] out = new Object[1];
        boolean[] done = {false};
        ctx.runOnClient(mc -> {
            MinecraftServer s = mc.getSingleplayerServer();
            s.execute(() -> {
                try {
                    out[0] = body.apply(s.getPlayerList().getPlayers().get(0));
                } finally {
                    done[0] = true;
                }
            });
        });
        for (int i = 0; i < 40 && !done[0]; i++) {
            ctx.waitTick();
        }
        @SuppressWarnings("unchecked") T t = (T) out[0];
        return t;
    }

    private static void command(ClientGameTestContext ctx, String cmd) {
        serverPlayer(ctx, sp -> {
            MinecraftServer s = sp.level().getServer();
            s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), cmd);
            return null;
        });
    }

    private static void tp(ClientGameTestContext ctx, double x, double z, float yaw, float pitch) {
        command(ctx, String.format(Locale.ROOT, "tp @p %.2f %d %.2f %.1f %.1f", x, PLATFORM_Y + 1, z, yaw, pitch));
        ctx.waitTicks(20);
    }

    private static void look(ClientGameTestContext ctx, float yaw, float pitch) {
        ctx.runOnClient(mc -> {
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
        });
        ctx.waitTicks(3);
    }

    private static Vec3 pos(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> mc.player.position());
    }

    /** Waits {@code ticks}: {highest rise over {@code from}, farthest flat distance from it}. */
    private static double[] fly(ClientGameTestContext ctx, Vec3 from, int ticks) {
        double top = 0, far = 0;
        for (int i = 0; i < ticks; i++) {
            ctx.waitTick();
            Vec3 p = pos(ctx);
            top = Math.max(top, p.y - from.y);
            far = Math.max(far, Math.hypot(p.x - from.x, p.z - from.z));
        }
        return new double[]{top, far};
    }

    private static int num(String cls, String m) {
        return ((Number) ModUnderTest.staticCall(cls, m, new Class<?>[]{}, new Object[]{})).intValue();
    }

    private static void expect(List<String> f, String what, Object got, Object want) {
        boolean ok = String.valueOf(want).equals(String.valueOf(got));
        println("launch", what + ": " + got + (ok ? "" : " (want " + want + ")"));
        if (!ok) {
            f.add(what + ": " + got + ", want " + want);
        }
    }

    private static void leave(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.execute(() -> {
            if (mc.level != null) {
                mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                mc.disconnectWithSavingScreen();
            }
        }));
        ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 2000);
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> mc.execute(() ->
                McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen);
    }

    private static void println(String name, String line) {
        System.out.println("[" + name + "] " + line);
    }
}

package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.coop.CoopSync;
import dev.testkit.gametest.coop.GuestPlayer;
import dev.testkit.harness.Disconnects;
import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Co-op boss sim harness (coopsim-1109). Only when named.
 *
 * <ul>
 * <li>{@code 1109-coop-fakeguest}: a fake guest ({@link GuestPlayer}) joins the P1 boss sim's integrated server. The
 * server's PlayerList counts 2 and the host's client sees the guest's entity; its use / use-on-block / attack /
 * interact / break reach the server's handlers (Fabric's server callbacks record each); placed on the far side of
 * Maxor, Maxor flies at the GUEST once he moves (t 84), not at the host; in P2 Storm leaves his park (t 647) for the
 * guest; removed, the PlayerList is back to 1 and the host's client loses the entity.</li>
 * <li>{@code 1109-coop-two}: two REAL clients ({@code run-coop.sh}). The host starts P1, publishes its integrated server on
 * the LAN port with authentication off, the guest (another offline name) joins 127.0.0.1:port; each client sees the
 * other's player entity, a chat line from each reaches the other. Without run-coop.sh (no role) it is a SKIP row. Then the
 * handshake (guest accepted / not refused, isGuest, never canAct, isSimBoss, both tabs' party; the host's ack count and
 * join line), a far teleport held by the host's server until the guest answers, and a real guest left click firing its
 * Salvation.</li>
 * <li>{@code 1109-coop-rules}, {@code 1109-coop-p3}, {@code 1109-coop-portal}: the server rules with a fake guest (crystal
 * carry, vanilla break, Terminator / Salvation / Sceptre as the guest, terminal in use, device credit, the leave rule, the
 * guest taking Blood's portal). {@code 1109-coop-hypixel-refused} (hx session) covers the Hypixel label.</li>
 * </ul>
 * Hooks for later co-op cases: {@link GuestPlayer} (server-side guest), {@link CoopSync} (the two clients' rendezvous),
 * {@link #publish} and {@link #joinHost}.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class CoopSimTests implements FabricClientGameTest {

    static final String FAKE = "1109-coop-fakeguest";
    static final String TWO = "1109-coop-two";
    static final String RULES = "1109-coop-rules";
    static final String P3 = "1109-coop-p3";
    static final String PORTAL = "1109-coop-portal";
    private static final String FIGHT = "com.killer560.hub.bosssim.BossFight";
    private static final String R = "com.killer560.hub.roomsim.";
    private static final String TERM = R + "SimTerminator";
    private static final String COOP = R + "SimCoop";
    private static final String GUEST_NAME = "TkFakeGuest";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank()) {
            return;
        }
        List<String> failed = new ArrayList<>();
        for (String name : new String[]{FAKE, RULES, P3, PORTAL, TWO}) {
            if (Scenario.skip(name)) {
                continue;
            }
            if (!name.equals(TWO) && CoopSync.role().equals("guest")) {
                Scenario.skipped(name, "the guest side of a two-client run (the host runs it)");
                continue;
            }
            if (name.equals(TWO) && !CoopSync.active()) {
                Scenario.skipped(name, "two-client case: run it with run-coop.sh (no testkit.coop.role here)");
                continue;
            }
            ModUnderTest.require("killer560smod");
            LogTap.install();
            if (name.equals(PORTAL) && !SimPuzzleSolveTests.seedRooms(ctx)) {
                Scenario.skipped(name, "needs real room captures and room database");
                continue;
            }
            ctx.waitTicks(20);
            ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
            List<String> problems = new ArrayList<>();
            List<String> facts = new ArrayList<>();
            Report.caseStarted(name);
            try {
                if (name.equals(FAKE)) {
                    fakeGuest(ctx, problems, facts);
                } else if (name.equals(RULES)) {
                    rules(ctx, problems, facts);
                } else if (name.equals(P3)) {
                    p3(ctx, problems, facts);
                } else if (name.equals(PORTAL)) {
                    portal(ctx, problems, facts);
                } else if (CoopSync.role().equals("host")) {
                    twoHost(ctx, problems, facts);
                } else {
                    twoGuest(ctx, problems, facts);
                }
            } catch (Throwable t) {
                problems.add("threw: " + t);
                t.printStackTrace(System.out);
            } finally {
                if (name.equals(TWO) && !problems.isEmpty()) {
                    CoopSync.fail(String.join("; ", problems));
                }
                cleanup(ctx);
            }
            for (String f : facts) {
                println(name, "FACT " + f);
            }
            String verdict = problems.isEmpty() ? "PASS" : "FAIL";
            String detail = String.join("; ", problems.isEmpty() ? facts : problems);
            Report.caseFinished(name, verdict, "n/a (singleplayer)", detail, List.of(), List.of());
            SuiteVerdict.finished(name);
            for (String p : problems) {
                println(name, "PROBLEM " + p);
            }
            println(name, verdict);
            if (!problems.isEmpty()) {
                failed.add(name + ": " + String.join("; ", problems));
            }
        }
        if (!failed.isEmpty()) {
            throw new AssertionError(String.join(" | ", failed));
        }
    }

    // ===================================================================================== 1109-coop-fakeguest

    private static void fakeGuest(ClientGameTestContext ctx, List<String> f, List<String> facts) {
        SimBossTests.start(ctx, "P1", true);
        MinecraftServer s = ctx.computeOnClient(Minecraft::getSingleplayerServer);
        UUID host = ctx.computeOnClient(mc -> mc.player.getUUID());
        Vec3 hostAt = ctx.computeOnClient(mc -> mc.player.position());
        // Maxor appears with his first line (a few ticks in) and holds still at (73, 226, 53) until t 84.
        until(ctx, mc -> bossPos("Maxor") != null && phaseTick() >= 60, 300);
        Vec3 maxor = bossPos("Maxor");
        if (maxor == null) {
            f.add("no Maxor by P1 t 60");
            return;
        }
        // On the far side of Maxor from the host, 8 blocks out: closer to him than the host is.
        Vec3 away = new Vec3(maxor.x - hostAt.x, 0, maxor.z - hostAt.z).normalize();
        Vec3 guestAt = maxor.add(away.scale(8)).add(0, -1, 0);
        facts.add(String.format(Locale.ROOT, "host %s is %.1f from Maxor %s, guest placed at %s (%.1f)", fmt(hostAt),
                hostAt.distanceTo(maxor), fmt(maxor), fmt(guestAt), guestAt.distanceTo(maxor)));
        GuestPlayer g = GuestPlayer.joinBesideHost(ctx, s, host, GUEST_NAME, guestAt);

        int players = GuestPlayer.onServer(ctx, s, () -> s.getPlayerList().getPlayers().size());
        expect(f, facts, "PlayerList count with the guest", players, 2);
        expect(f, facts, "guest listed by the PlayerList", g.listed(), true);
        expect(f, facts, "host's client sees the guest's player entity", until(ctx, mc -> seesPlayer(mc, GUEST_NAME), 100), true);

        actions(ctx, s, g, f, facts);

        // Maxor holds until t 84, then flies at the CLOSEST player.
        if (!untilTick(ctx, 84, 300)) {
            f.add("P1 never reached t 84");
            return;
        }
        Vec3 m0 = bossPos("Maxor");
        double host0 = m0.distanceTo(hostNow(ctx));
        double guest0 = m0.distanceTo(g.position());
        ctx.waitTicks(30);
        Vec3 m1 = bossPos("Maxor");
        double host1 = m1.distanceTo(hostNow(ctx));
        double guest1 = m1.distanceTo(g.position());
        facts.add(String.format(Locale.ROOT, "Maxor t84 %s -> t%d %s: to guest %.1f -> %.1f, to host %.1f -> %.1f",
                fmt(m0), phaseTick(), fmt(m1), guest0, guest1, host0, host1));
        expect(f, facts, "Maxor flew at the closer guest (within 4 of it)", guest1 < guest0 - 2 && guest1 < 4.5, true);
        expect(f, facts, "Maxor did not fly at the host", host1 > host0, true);

        g.remove();
        ctx.waitTicks(5);
        expect(f, facts, "guest gone from the PlayerList after remove", !g.listed()
                && GuestPlayer.onServer(ctx, s, () -> s.getPlayerList().getPlayers().size()) == 1, true);
        expect(f, facts, "host's client lost the guest's entity", until(ctx, mc -> !seesPlayer(mc, GUEST_NAME), 100), true);
        SimPuzzleSolveTests.teardown(ctx);

        // ---- P2: Storm leaves his park at t 647 for the closest player ----
        SimBossTests.start(ctx, "P2", true);
        MinecraftServer s2 = ctx.computeOnClient(Minecraft::getSingleplayerServer);
        String hostName = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
        SimBossTests.command(ctx, String.format(Locale.ROOT, "tp %s 50.5 169 85.5 0 0", hostName));
        if (!untilTick(ctx, 620, 900)) {
            f.add("P2 never reached t 620");
            return;
        }
        Vec3 park = bossPos("Storm");
        Vec3 h = hostNow(ctx);
        Vec3 out = new Vec3(park.x - h.x, 0, park.z - h.z).normalize();
        Vec3 g2At = new Vec3(park.x + out.x * 14, 172, park.z + out.z * 14);
        GuestPlayer g2 = GuestPlayer.joinBesideHost(ctx, s2, ctx.computeOnClient(mc -> mc.player.getUUID()), GUEST_NAME, g2At);
        untilTick(ctx, 647, 60);
        Vec3 s0 = bossPos("Storm");
        double sh0 = s0.distanceTo(hostNow(ctx));
        double sg0 = s0.distanceTo(g2.position());
        untilTick(ctx, 700, 80);
        Vec3 s1 = bossPos("Storm");
        double sh1 = s1.distanceTo(hostNow(ctx));
        double sg1 = s1.distanceTo(g2.position());
        facts.add(String.format(Locale.ROOT, "Storm t647 %s -> t%d %s: to guest %.1f -> %.1f, to host %.1f -> %.1f",
                fmt(s0), phaseTick(), fmt(s1), sg0, sg1, sh0, sh1));
        expect(f, facts, "Storm chased the closer guest", sg1 < sg0 - 3, true);
        expect(f, facts, "Storm did not chase the host", sh1 > sh0 - 1, true);
        g2.remove();
    }

    /** Each guest action reaches the server's real handler (Fabric's server callbacks record it). */
    private static void actions(ClientGameTestContext ctx, MinecraftServer s, GuestPlayer g, List<String> f, List<String> facts) {
        Vec3 at = g.position();
        BlockPos below = BlockPos.containing(at.x, at.y - 1, at.z);
        SimBossTests.command(ctx, String.format(Locale.ROOT, "setblock %d %d %d minecraft:glass", below.getX(), below.getY(), below.getZ()));
        SimBossTests.command(ctx, String.format(Locale.ROOT, "summon minecraft:armor_stand %.2f %.2f %.2f {NoGravity:1b}",
                at.x + 1.5, at.y, at.z));
        g.hold(new ItemStack(Items.SNOWBALL, 4));
        g.useItem();
        g.useOn(below, Direction.UP);
        Entity stand = GuestPlayer.onServer(ctx, s, () -> {
            for (Entity e : g.entity().level().getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class,
                    new AABB(at.x + 0.5, at.y - 1, at.z - 1, at.x + 2.5, at.y + 2, at.z + 1))) {
                return e;
            }
            return null;
        });
        if (stand == null) {
            f.add("the armor stand for attack/interact was not summoned");
        } else {
            g.interact(stand);
            g.attack(stand);
        }
        g.run(p -> p.setGameMode(net.minecraft.world.level.GameType.CREATIVE));
        boolean broke = g.breakBlock(below, Direction.UP, false);
        g.run(p -> p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL));
        List<String> ev = g.events();
        facts.add("guest events " + ev + "; creative break left " + (broke ? "air" : "the block"));
        String b = below.getX() + "," + below.getY() + "," + below.getZ();
        expect(f, facts, "guest use reached handleUseItem", ev.contains("use snowball"), true);
        expect(f, facts, "guest use-on-block reached handleUseItemOn", ev.contains("useOn " + b), true);
        if (stand != null) {
            expect(f, facts, "guest interact reached handleInteract", ev.contains("interact armor_stand"), true);
            expect(f, facts, "guest attack reached handleAttack", ev.contains("attack armor_stand"), true);
            GuestPlayer.onServer(ctx, s, () -> {
                stand.discard();
                return null;
            });
        }
        // The dig START must reach the server's handler; whether the block then goes is the sim's own rule (SimItems
        // refuses vanilla breaks there), so it is a fact, not a verdict.
        expect(f, facts, "guest dig reached handleBlockBreakAction", ev.contains("dig " + b), true);
    }

    // ======================================================================================== 1109-coop-rules

    /**
     * The server rules for a guest, in P1: a tower crystal it picks up is ITS carry; a vanilla break is refused in survival
     * and allowed in creative; its Terminator's arrows are its own; Salvation arms and fires per shooter, the beam hurting
     * as the guest; its Spirit Sceptre blast hurts as it and is sent to both players.
     */
    private static void rules(ClientGameTestContext ctx, List<String> f, List<String> facts) {
        SimBossTests.start(ctx, "P1", true);
        MinecraftServer s = ctx.computeOnClient(Minecraft::getSingleplayerServer);
        UUID host = ctx.computeOnClient(mc -> mc.player.getUUID());
        if (!until(ctx, mc -> (Boolean) mod(FIGHT, "crystalOnTower", new Class<?>[]{int.class}, 0), 400)) {
            f.add("the first crystal never sat on its tower");
            return;
        }
        GuestPlayer g = GuestPlayer.joinBesideHost(ctx, s, host, GUEST_NAME, new Vec3(63.5, 238, 49.5));
        UUID guest = g.entity().getUUID();
        ctx.waitTicks(5);
        Entity crystal = GuestPlayer.onServer(ctx, s, () -> g.entity().level().getEntitiesOfClass(
                net.minecraft.world.entity.boss.enderdragon.EndCrystal.class, new AABB(63, 236.9, 49, 66, 239.9, 52))
                .stream().findFirst().orElse(null));
        if (crystal == null) {
            f.add("no tower crystal by (64.5, 238.4, 50.5)");
        } else {
            g.interact(crystal);
            ctx.waitTicks(3);
        }
        expect(f, facts, "crystals the guest carries", carried(guest), 1);
        expect(f, facts, "crystals the host carries", carried(host), 0);
        expect(f, facts, "the guest's slot 8 holds the Energy Crystal",
                g.ask(p -> p.getInventory().getItem(8).getHoverName().getString().contains("Energy Crystal")), true);

        // Vanilla break: an instant-break block above its head.
        BlockPos slime = new BlockPos(63, 240, 49);
        SimBossTests.command(ctx, "setblock 63 240 49 minecraft:slime_block");
        long mark = LogTap.mark();
        boolean gone = g.breakBlock(slime, Direction.DOWN, false);
        ctx.waitTicks(2);
        expect(f, facts, "survival guest's instant break left the block", !gone, true);
        expect(f, facts, "refusal logged for the guest", logSeen(mark, "vanilla break refused for " + GUEST_NAME), true);
        g.run(p -> p.setGameMode(net.minecraft.world.level.GameType.CREATIVE));
        expect(f, facts, "creative guest's break took the block", g.breakBlock(slime, Direction.DOWN, false), true);
        g.run(p -> p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL));

        // Terminator: the arrows are the guest's.
        g.hold(simItem(ctx, "TERMINATOR"));
        g.look(0, 0);
        g.useItem();
        int[] arrows = {0, 0};
        for (int i = 0; i < 20 && arrows[0] == 0; i++) {
            ctx.waitTick();
            arrows = GuestPlayer.onServer(ctx, s, () -> {
                int[] n = {0, 0};
                for (var a : g.entity().level().getEntitiesOfClass(net.minecraft.world.entity.projectile.arrow.AbstractArrow.class,
                        g.entity().getBoundingBox().inflate(64))) {
                    UUID o = a.getOwner() == null ? null : a.getOwner().getUUID();
                    n[0] += guest.equals(o) ? 1 : 0;
                    n[1] += host.equals(o) ? 1 : 0;
                }
                return n;
            });
        }
        expect(f, facts, "guest Terminator arrows owned by the guest > 0", arrows[0] > 0, true);
        expect(f, facts, "arrows owned by the host", arrows[1], 0);

        // Salvation: three distinct mobs hit by the guest's arrows arm the guest only; its click fires from it.
        GuestPlayer.onServer(ctx, s, () -> {
            net.minecraft.server.level.ServerLevel lv = (net.minecraft.server.level.ServerLevel) g.entity().level();
            for (int i = 0; i < 3; i++) {
                var a = new net.minecraft.world.entity.decoration.ArmorStand(lv, 60.5 + i, 238, 45.5);
                a.setNoGravity(true);
                lv.addFreshEntity(a);
                mod(TERM, "recordHit", new Class<?>[]{Entity.class, net.minecraft.world.entity.LivingEntity.class}, g.entity(), a);
            }
            return null;
        });
        expect(f, facts, "Salvation armed for the guest", armed(guest), true);
        expect(f, facts, "Salvation armed for the host", armed(host), false);
        net.minecraft.world.entity.LivingEntity beamTarget = target(ctx, s, "63.5 238 53.5", "tkcoopz1");
        ctx.waitTicks(12);
        int sal0 = salvations();
        GuestPlayer.onServer(ctx, s, () -> mod(TERM, "guestLeftClick", new Class<?>[]{ServerPlayer.class}, g.entity()));
        until(ctx, mc -> salvations() > sal0, 40);
        expect(f, facts, "Salvations fired by the guest's click", salvations() - sal0, 1);
        expect(f, facts, "the guest's Salvation used up its charge", armed(guest), false);
        expect(f, facts, "the beam hurt its target as the guest", hurtBy(ctx, s, beamTarget), guest);

        // Spirit Sceptre straight down: the blast hurts as the guest and is sent to both players.
        net.minecraft.world.entity.LivingEntity blastTarget = target(ctx, s, "65.0 238 49.5", "tkcoopz2");
        g.hold(simItem(ctx, "BAT_WAND"));
        int b0 = ((Number) ModUnderTest.staticCall(R + "SimSpiritSceptre", "blasts")).intValue();
        g.look(0, 90);
        g.useItem();
        boolean blasted = until(ctx, mc -> ((Number) ModUnderTest.staticCall(R + "SimSpiritSceptre", "blasts")).intValue() > b0, 80);
        expect(f, facts, "the guest's sceptre blast went off", blasted, true);
        expect(f, facts, "players the blast particles were sent to",
                ModUnderTest.staticCall(R + "SimSpiritSceptre", "lastBlastViewersForTest"), 2);
        expect(f, facts, "the blast hurt its target as the guest", hurtBy(ctx, s, blastTarget), guest);
        g.remove();
    }

    // =========================================================================================== 1109-coop-p3

    /** P3 S1: one player per terminal; device credit goes to the guest; the fight ends only once both left the room. */
    private static void p3(ClientGameTestContext ctx, List<String> f, List<String> facts) {
        SimBossTests.start(ctx, "S1", true);
        MinecraftServer s = ctx.computeOnClient(Minecraft::getSingleplayerServer);
        UUID host = ctx.computeOnClient(mc -> mc.player.getUUID());
        String hostName = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
        double[] at = (double[]) mod("com.killer560.hub.bosssim.BossStations", "position",
                new Class<?>[]{int.class, String.class}, 1, "T1");
        GuestPlayer g = GuestPlayer.joinBesideHost(ctx, s, host, GUEST_NAME,
                new Vec3(Math.floor(at[0]) - 1.5, at[1], Math.floor(at[2]) + 0.5));
        UUID guest = g.entity().getUUID();
        ctx.waitTicks(5);
        Entity stand = GuestPlayer.onServer(ctx, s, () -> {
            for (var a : g.entity().level().getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class,
                    new AABB(at[0] - 1, at[1] - 1, at[2] - 1, at[0] + 1, at[1] + 1, at[2] + 1))) {
                if (a.getCustomName() != null && a.getCustomName().getString().contains("Inactive Terminal")) {
                    return a;
                }
            }
            return null;
        });
        if (stand == null) {
            f.add("no Inactive Terminal stand at S1 T1");
        } else {
            g.interact(stand);
            ctx.waitTicks(5);
            expect(f, facts, "the guest opened T1", g.ask(p -> p.containerMenu != p.inventoryMenu), true);
            long mark = LogTap.mark();
            expect(f, facts, "the host clicked T1's stand", SimBossTests.clickTerminal(ctx, 1, "T1", "Inactive Terminal"), true);
            ctx.waitTicks(5);
            expect(f, facts, "the host was told T1 is in use", chatSeen(mark, "This Terminal is being used by someone else!"), true);
            expect(f, facts, "the host got no terminal screen", ctx.computeOnClient(mc -> McCompat.screen(mc) == null), true);
            g.run(ServerPlayer::closeContainer);
        }

        g.moveTo(new Vec3(60.5, 131, 140.5), 180, -20);
        g.useOn(new BlockPos(60, 135, 142), Direction.NORTH);
        ctx.waitTicks(3);
        expect(f, facts, "Lights credited to", lastBy(2), guest);
        g.moveTo(new Vec3(108.5, 120, 93.5), -90, 0);
        g.useOn(new BlockPos(110, 121, 93), Direction.WEST);
        ctx.waitTicks(3);
        expect(f, facts, "Simon Says credited to", lastBy(1), guest);

        // Leave rule: the host outside, the guest inside keeps it going; both outside ends it two seconds later.
        SimBossTests.command(ctx, "setblock -20 199 -20 minecraft:barrier");
        SimBossTests.command(ctx, "setblock -22 199 -20 minecraft:barrier");
        SimBossTests.command(ctx, "tp " + hostName + " -19.5 200 -19.5");
        ctx.waitTicks(60);
        expect(f, facts, "fight running 60 ticks after the host left with the guest inside", running(), true);
        SimBossTests.command(ctx, "tp " + GUEST_NAME + " -21.5 200 -19.5");
        int t = 0;
        while (t < 120 && running()) {
            ctx.waitTick();
            t++;
        }
        facts.add("fight stopped " + t + " ticks after the guest left too");
        expect(f, facts, "fight stopped 35-50 ticks after both left", t >= 35 && t <= 50, true);
        g.remove();
    }

    // ======================================================================================= 1109-coop-portal

    /** A generated F7: the GUEST stepping into Blood's portal starts P1 (the host stands beside it). */
    private static void portal(ClientGameTestContext ctx, List<String> f, List<String> facts) {
        String code = null;
        for (int i = 0; i < 6 && code == null; i++) {
            code = SimBossTests.planF7(ctx);
        }
        if (code == null) {
            f.add("no F7 could be planned");
            return;
        }
        int[] p = SimBossTests.floorWithPortal(ctx, f, code);
        if (p == null) {
            return;
        }
        MinecraftServer s = ctx.computeOnClient(Minecraft::getSingleplayerServer);
        UUID host = ctx.computeOnClient(mc -> mc.player.getUUID());
        SimBossTests.tp(ctx, p[0] + 4.5, p[1] + 1, p[2] + 0.5, 0, 0);
        GuestPlayer g = GuestPlayer.joinBesideHost(ctx, s, host, GUEST_NAME, new Vec3(p[0] + 0.5, p[1] + 1, p[2] + 4.5));
        ctx.waitTicks(10);
        expect(f, facts, "fight before anyone stepped in", running(), false);
        long mark = LogTap.mark();
        g.moveTo(new Vec3(p[0] + 0.5, p[1] + 1, p[2] + 0.5), 0, 0);
        boolean in = until(ctx, mc -> logSeen(mark, "into the boss through the Blood room's portal (" + GUEST_NAME + ")"), 100);
        expect(f, facts, "the guest's step into the portal was taken", in, true);
        expect(f, facts, "P1 started", until(ctx, mc -> running(), 600), true);
        g.remove();
    }

    private static Object mod(String cls, String method, Class<?>[] types, Object... args) {
        return ModUnderTest.staticCall(cls, method, types, args);
    }

    private static int carried(UUID u) {
        return ((Number) mod(FIGHT, "carriedForTest", new Class<?>[]{UUID.class}, u)).intValue();
    }

    private static boolean armed(UUID u) {
        return (Boolean) mod(TERM, "salvationArmedForTest", new Class<?>[]{UUID.class}, u);
    }

    private static int salvations() {
        return ((Number) ModUnderTest.staticCall(TERM, "salvationsForTest")).intValue();
    }

    private static boolean running() {
        return (Boolean) ModUnderTest.staticCall(FIGHT, "running");
    }

    private static Object lastBy(int section) {
        return mod("com.killer560.hub.bosssim.BossDevices", "lastByForTest", new Class<?>[]{int.class}, section);
    }

    private static ItemStack simItem(ClientGameTestContext ctx, String id) {
        return ctx.computeOnClient(mc -> (ItemStack) mod(R + "SimItems", "build", new Class<?>[]{String.class}, id));
    }

    /** A still, gravity-free zombie at {@code at} tagged {@code tag}, as the server's entity. */
    private static net.minecraft.world.entity.LivingEntity target(ClientGameTestContext ctx, MinecraftServer s, String at, String tag) {
        SimBossTests.command(ctx, "summon minecraft:zombie " + at + " {NoAI:1b,NoGravity:1b,Tags:[\"" + tag + "\"]}");
        ctx.waitTicks(2);
        return GuestPlayer.onServer(ctx, s, () -> {
            for (var lv : s.getAllLevels()) {
                for (var e : lv.getAllEntities()) {
                    if (e instanceof net.minecraft.world.entity.LivingEntity le && e.entityTags().contains(tag)) {
                        return le;
                    }
                }
            }
            return null;
        });
    }

    private static UUID hurtBy(ClientGameTestContext ctx, MinecraftServer s, net.minecraft.world.entity.LivingEntity e) {
        if (e == null) {
            return null;
        }
        return GuestPlayer.onServer(ctx, s, () -> e.getLastHurtByMob() == null ? null : e.getLastHurtByMob().getUUID());
    }

    private static int[] holds() {
        return (int[]) ModUnderTest.staticCall(COOP, "holdsForTest");
    }

    private static int[] guestCounts() {
        return (int[]) ModUnderTest.staticCall(R + "SimGuest", "guestForTest");
    }

    @SuppressWarnings("unchecked")
    private static List<String> partyNames(Minecraft mc) {
        return (List<String>) mod(COOP, "partyNames", new Class<?>[]{Minecraft.class}, mc);
    }

    private static boolean logSeen(long mark, String text) {
        for (String l : LogTap.since(mark)) {
            if (l.contains(text)) {
                return true;
            }
        }
        return false;
    }

    // ========================================================================================= 1109-coop-two

    private static void twoHost(ClientGameTestContext ctx, List<String> f, List<String> facts) {
        CoopSync.put("host-up", "1");
        CoopSync.await(ctx, "guest-up", 900);
        SimBossTests.start(ctx, "P1", true);
        String hostName = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
        int acks0 = ((Number) ModUnderTest.staticCall(COOP, "acksForTest")).intValue();
        long joinMark = LogTap.mark();
        String published = publish(ctx, CoopSync.port());
        facts.add(published);
        if (!published.startsWith("published")) {
            f.add(published);
            return;
        }
        CoopSync.put("host-ready", CoopSync.port() + "\n" + hostName);
        String guestName = CoopSync.await(ctx, "guest-in", 180).trim();
        boolean onServer = until(ctx, mc -> mc.getSingleplayerServer().getPlayerList().getPlayerByName(guestName) != null, 200);
        expect(f, facts, "guest " + guestName + " in the host server's PlayerList", onServer, true);
        expect(f, facts, "host's client sees the guest's player entity", until(ctx, mc -> seesPlayer(mc, guestName), 200), true);
        // Handshake, host side: the guest's ack arrived and was logged; the tab's party is both, host first.
        until(ctx, mc -> ((Number) ModUnderTest.staticCall(COOP, "acksForTest")).intValue() > acks0, 200);
        expect(f, facts, "acks from the guest", ((Number) ModUnderTest.staticCall(COOP, "acksForTest")).intValue() - acks0, 1);
        expect(f, facts, "host logged the guest's join", logSeen(joinMark, "[SimCoop] " + guestName + " joined with"), true);
        List<String> party = List.of(hostName, guestName);
        until(ctx, mc -> party.equals(partyNames(mc)), 200);
        expect(f, facts, "host's tab party", ctx.computeOnClient(CoopSimTests::partyNames), party);
        expect(f, facts, "host isGuest", ctx.computeOnClient(mc -> ModUnderTest.staticCall(R + "SimState", "isGuest",
                new Class<?>[]{Minecraft.class}, new Object[]{mc})), false);

        String nonce = Long.toString(System.nanoTime() % 1_000_000L, 36);
        long mark = LogTap.mark();
        CoopSync.put("host-said", "coop-host-hello-" + nonce);
        ctx.runOnClient(mc -> mc.player.connection.sendChat("coop-host-hello-" + nonce));
        String guestLine = CoopSync.await(ctx, "guest-said", 120).trim();
        boolean heard = paceUntil(ctx, () -> chatSeen(mark, guestLine), 15_000);
        expect(f, facts, "host's chat shows the guest's line '" + guestLine + "'", heard, true);
        CoopSync.put("host-heard", heard ? "1" : "0");

        // Far teleport: the host's server holds the guest until its client has the chunks and answers.
        MinecraftServer s = ctx.computeOnClient(Minecraft::getSingleplayerServer);
        int[] h0 = holds();
        GuestPlayer.onServer(ctx, s, () -> {
            ServerPlayer gp = s.getPlayerList().getPlayerByName(guestName);
            net.minecraft.server.level.ServerLevel lv = (net.minecraft.server.level.ServerLevel) gp.level();
            lv.setBlockAndUpdate(new BlockPos(600, 100, 600), net.minecraft.world.level.block.Blocks.BARRIER.defaultBlockState());
            gp.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, (ItemStack) mod(R + "SimItems", "build",
                    new Class<?>[]{String.class}, "TERMINATOR"));
            return mod(R + "SimArrival", "teleport", new Class<?>[]{ServerPlayer.class, net.minecraft.server.level.ServerLevel.class,
                    double.class, double.class, double.class, java.util.Set.class, float.class, float.class},
                    gp, lv, 600.5, 101.0, 600.5, java.util.Set.of(), 0f, 0f);
        });
        CoopSync.put("host-tp", "1");
        boolean answered = until(ctx, mc -> holds()[1] > h0[1], 600);
        int[] h1 = holds();
        facts.add("holds {started, answered, put-backs} " + java.util.Arrays.toString(h0) + " -> " + java.util.Arrays.toString(h1));
        expect(f, facts, "holds started", h1[0] - h0[0], 1);
        expect(f, facts, "the guest answered its hold", answered, true);
        String landed = CoopSync.await(ctx, "guest-landed", 120).trim();
        facts.add("guest landed at y " + landed);

        // Guest Salvation: three hits by the guest arm it; the guest's real left click fires it.
        UUID gu = GuestPlayer.onServer(ctx, s, () -> s.getPlayerList().getPlayerByName(guestName).getUUID());
        GuestPlayer.onServer(ctx, s, () -> {
            ServerPlayer gp = s.getPlayerList().getPlayerByName(guestName);
            net.minecraft.server.level.ServerLevel lv = (net.minecraft.server.level.ServerLevel) gp.level();
            for (int i = 0; i < 3; i++) {
                var a = new net.minecraft.world.entity.decoration.ArmorStand(lv, 598.5 + i, 101, 596.5);
                a.setNoGravity(true);
                lv.addFreshEntity(a);
                mod(TERM, "recordHit", new Class<?>[]{Entity.class, net.minecraft.world.entity.LivingEntity.class}, gp, a);
            }
            return null;
        });
        expect(f, facts, "Salvation armed for the guest", armed(gu), true);
        int sal0 = salvations();
        CoopSync.put("host-armed", "1");
        boolean fired = until(ctx, mc -> salvations() > sal0, 600);
        expect(f, facts, "the guest's left click fired its Salvation", fired, true);
        expect(f, facts, "the guest's charge used", armed(gu), false);
        String guestVerdict = CoopSync.await(ctx, "guest-done", 120).trim();
        facts.add("guest verdict: " + guestVerdict);
        expect(f, facts, "guest's own checks", guestVerdict, "PASS");
    }

    private static void twoGuest(ClientGameTestContext ctx, List<String> f, List<String> facts) {
        CoopSync.put("guest-up", "1");
        String[] ready = CoopSync.await(ctx, "host-ready", 900).split("\n");
        int port = Integer.parseInt(ready[0].trim());
        String hostName = ready[1].trim();
        String joined = joinHost(ctx, "127.0.0.1:" + port);
        facts.add(joined);
        if (!joined.startsWith("joined")) {
            f.add(joined);
            CoopSync.put("guest-done", "FAIL: " + joined);
            return;
        }
        String me = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
        CoopSync.put("guest-in", me);
        expect(f, facts, "guest's client sees the host's player entity " + hostName, until(ctx, mc -> seesPlayer(mc, hostName), 200), true);
        // Handshake, guest side: one hello accepted, none refused; a guest that can never act; the boss phase and the
        // tab's party come from the host.
        until(ctx, mc -> ((Number) ModUnderTest.staticCall(R + "SimGuest", "acceptedForTest")).intValue() > 0, 200);
        expect(f, facts, "hellos accepted > 0", ((Number) ModUnderTest.staticCall(R + "SimGuest", "acceptedForTest")).intValue() > 0, true);
        expect(f, facts, "hellos refused", ModUnderTest.staticCall(R + "SimGuest", "refusedForTest"), 0);
        expect(f, facts, "guest isGuest", ctx.computeOnClient(mc -> ModUnderTest.staticCall(R + "SimState", "isGuest",
                new Class<?>[]{Minecraft.class}, new Object[]{mc})), true);
        expect(f, facts, "guest canAct", ctx.computeOnClient(mc -> ModUnderTest.staticCall(R + "SimState", "canAct",
                new Class<?>[]{Minecraft.class}, new Object[]{mc})), false);
        until(ctx, mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.secrets.DungeonState", "isSimBoss"), 200);
        expect(f, facts, "guest DungeonState.isSimBoss", ModUnderTest.staticCall("com.killer560.hub.secrets.DungeonState", "isSimBoss"), true);
        List<String> party = List.of(hostName, me);
        until(ctx, mc -> party.equals(partyNames(mc)), 200);
        expect(f, facts, "guest's tab party", ctx.computeOnClient(CoopSimTests::partyNames), party);
        int[] g0 = guestCounts();
        String hostLine = CoopSync.await(ctx, "host-said", 120).trim();
        long since = 0;
        boolean heard = paceUntil(ctx, () -> chatSeen(since, hostLine), 15_000);
        expect(f, facts, "guest's chat shows the host's line '" + hostLine + "'", heard, true);
        String mine = "coop-guest-hello-" + Long.toString(System.nanoTime() % 1_000_000L, 36);
        ctx.runOnClient(mc -> mc.player.connection.sendChat(mine));
        CoopSync.put("guest-said", mine);
        String hostHeard = CoopSync.await(ctx, "host-heard", 60).trim();
        facts.add("host heard the guest's line: " + hostHeard);

        // Far teleport: the host announces the landing, this client holds its loading screen (never moving itself) until
        // the chunks are in, then answers; it ends standing on the barrier at y 101.
        CoopSync.await(ctx, "host-tp", 120);
        expect(f, facts, "landings announced", until(ctx, mc -> guestCounts()[0] > g0[0], 400), true);
        boolean released = until(ctx, mc -> !(Boolean) ModUnderTest.staticCall(R + "SimArrival", "holding")
                && McCompat.screen(mc) == null && mc.player.position().distanceTo(new Vec3(600.5, 101, 600.5)) < 2, 600);
        expect(f, facts, "released at the landing", released, true);
        ctx.waitTicks(20);
        double y = ctx.computeOnClient(mc -> mc.player.getY());
        CoopSync.put("guest-landed", String.format(Locale.ROOT, "%.2f", y));
        expect(f, facts, "guest never fell below the landing", y >= 100.8, true);

        // Salvation: a real attack-key press with the Terminator goes up as sim_click.
        CoopSync.await(ctx, "host-armed", 120);
        until(ctx, mc -> "TERMINATOR".equals(ModUnderTest.staticCall("com.killer560.hub.cheatutils.CheatUtils", "skyblockId",
                new Class<?>[]{ItemStack.class}, new Object[]{mc.player.getMainHandItem()})), 100);
        ctx.runOnClient(mc -> mc.options.keyAttack.setDown(true));
        ctx.waitTicks(3);
        ctx.runOnClient(mc -> mc.options.keyAttack.setDown(false));
        ctx.waitTicks(2);
        expect(f, facts, "Terminator clicks sent", guestCounts()[1] - g0[1], 1);
        CoopSync.put("guest-done", f.isEmpty() ? "PASS" : "FAIL: " + String.join("; ", f));
        CoopSync.pace(ctx, 1000);
    }

    /**
     * Opens the host's integrated server to the LAN on {@code port} with authentication off (an offline guest's name
     * cannot be verified by Mojang's session server). Returns "published ..." with what it found, or why it could not.
     */
    static String publish(ClientGameTestContext ctx, int port) {
        return ctx.computeOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            if (server == null) {
                return "no integrated server to publish";
            }
            boolean auth = server.usesAuthentication();
            server.setUsesAuthentication(false);
            boolean ok = server.publishServer(MinecraftServer.MultiplayerScope.LAN, port);
            return ok ? "published on " + port + " (online mode was " + auth + ", set false; enforceSecureProfile "
                    + server.enforceSecureProfile() + ")" : "publishServer(LAN, " + port + ") returned false (port taken?)";
        });
    }

    /** Joins {@code address} from the title screen; returns "joined ..." or the disconnect reason. */
    static String joinHost(ClientGameTestContext ctx, String address) {
        Disconnects.clear();
        ctx.runOnClient(mc -> ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(address),
                new ServerData("coop-host", address, ServerData.Type.OTHER), false, null));
        boolean in = paceUntil(ctx, () -> ctx.computeOnClient(mc -> mc.player != null && mc.level != null)
                || Disconnects.last() != null || ctx.computeOnClient(CoopSimTests::connectFailed), 60_000);
        if (in && ctx.computeOnClient(mc -> mc.player != null && mc.level != null)) {
            return "joined " + address + " as " + ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
        }
        String screen = ctx.computeOnClient(mc -> {
            var sc = dev.testkit.compat.McCompat.screen(mc);
            return sc == null ? "no screen" : sc.getClass().getSimpleName() + " \"" + sc.getTitle().getString() + "\"";
        });
        return "could not join " + address + ": " + (Disconnects.last() != null ? Disconnects.last() : "") + " (" + screen + ")";
    }

    private static boolean connectFailed(Minecraft mc) {
        return mc.getConnection() == null && dev.testkit.compat.McCompat.screen(mc)
                instanceof net.minecraft.client.gui.screens.DisconnectedScreen;
    }

    // ========================================================================================= helpers

    private static void cleanup(ClientGameTestContext ctx) {
        try {
            MinecraftServer s = ctx.computeOnClient(Minecraft::getSingleplayerServer);
            if (s != null) {
                GuestPlayer.removeAll(ctx, s);
            }
        } catch (Throwable ignored) {
            // never let cleanup replace the verdict
        }
        if (ctx.computeOnClient(mc -> mc.level != null)) {
            if (ctx.computeOnClient(mc -> mc.getSingleplayerServer() == null)) {
                ctx.runOnClient(mc -> mc.disconnectWithProgressScreen());
                ctx.waitFor(mc -> mc.level == null, 600);
                ctx.waitTicks(20);
                ctx.setScreen(TitleScreen::new);
            } else {
                SimPuzzleSolveTests.teardown(ctx);
            }
        }
        // A guest whose host stopped first sits on a DisconnectedScreen, and a gametest class must end on the title.
        if (ctx.computeOnClient(mc -> mc.level == null && !(McCompat.screen(mc) instanceof TitleScreen))) {
            ctx.setScreen(TitleScreen::new);
        }
    }

    private static boolean seesPlayer(Minecraft mc, String name) {
        if (mc.level == null) {
            return false;
        }
        for (Player p : mc.level.players()) {
            if (p.getGameProfile().name().equals(name) && p != mc.player) {
                return true;
            }
        }
        return false;
    }

    private static Vec3 hostNow(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            ServerPlayer sp = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
            return sp == null ? mc.player.position() : sp.position();
        });
    }

    private static Vec3 bossPos(String boss) {
        double[] p = (double[]) ModUnderTest.staticCall(FIGHT, "bossPos", new Class<?>[]{String.class}, new Object[]{boss});
        return p == null ? null : new Vec3(p[0], p[1], p[2]);
    }

    private static int phaseTick() {
        return ((Number) ModUnderTest.staticCall(FIGHT, "phaseTick", new Class<?>[]{}, new Object[]{})).intValue();
    }

    private static boolean untilTick(ClientGameTestContext ctx, int tick, int max) {
        return until(ctx, mc -> phaseTick() >= tick, max);
    }

    private static boolean until(ClientGameTestContext ctx, java.util.function.Predicate<Minecraft> p, int ticks) {
        for (int i = 0; i < ticks; i++) {
            if (ctx.computeOnClient(p::test)) {
                return true;
            }
            ctx.waitTick();
        }
        return false;
    }

    /** Polls {@code p} while ticking at about 20 a second (real time matters once a second client is connected). */
    private static boolean paceUntil(ClientGameTestContext ctx, java.util.function.BooleanSupplier p, int ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (p.getAsBoolean()) {
                return true;
            }
            CoopSync.pace(ctx, 50);
        }
        return p.getAsBoolean();
    }

    private static boolean chatSeen(long mark, String text) {
        for (String l : LogTap.since(mark)) {
            if (l.contains("[CHAT]") && l.contains(text)) {
                return true;
            }
        }
        return false;
    }

    private static void expect(List<String> f, List<String> facts, String what, Object got, Object want) {
        boolean ok = String.valueOf(want).equals(String.valueOf(got));
        facts.add(what + ": " + got + (ok ? "" : " (want " + want + ")"));
        if (!ok) {
            f.add(what + ": " + got + ", want " + want);
        }
    }

    private static String fmt(Vec3 v) {
        return v == null ? "null" : String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", v.x, v.y, v.z);
    }

    private static void println(String name, String line) {
        System.out.println("[" + name + "] " + line);
    }
}

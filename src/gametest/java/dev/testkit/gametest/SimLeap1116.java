package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

import static dev.testkit.gametest.SimBossTests.call;
import static dev.testkit.gametest.SimBossTests.expect;
import static dev.testkit.gametest.SimBossTests.println;
import static dev.testkit.gametest.SimBossTests.start;

/**
 * {@code 1116-sim-infileap} (siminfleap-1116, killer560 2026-11-16: "for sim can you add the infini leap?"). In the sim
 * boss: two Sim Teammates appear and are in the party, the InfiniLeap opens the "Spirit Leap" menu with their heads, a
 * click teleports to the one clicked with Hypixel's line and the target's look, the item stays, a second use inside 2 s is
 * refused with the cooldown line, Fast Leap's engine (what Auto Leap calls) leaps by name through the hidden menu, a Spirit
 * Leap is used up per leap with its 5 s cooldown, and the setting survives a reload. Every check reads what the sim did.
 */
final class SimLeap1116 {

    static final String NAME = "1116-sim-infileap";
    private static final String ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String MATES = "com.killer560.hub.roomsim.SimTeammates";
    private static final String LEAP = "com.killer560.hub.roomsim.SimLeap";
    private static final String PREFS = "com.killer560.hub.roomsim.SimMenuPrefs";

    private SimLeap1116() {
    }

    static void setTeammates(ClientGameTestContext ctx, int n) {
        ctx.runOnClient(mc -> {
            try {
                Object p = ModUnderTest.staticCall(PREFS, "load");
                p.getClass().getField("teammates").setInt(p, n);
                ModUnderTest.call(p, "save", new Class<?>[]{}, new Object[]{});
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
    }

    @SuppressWarnings("unchecked")
    static List<String> mates(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> new ArrayList<>((List<String>) call(MATES, "names")));
    }

    private static ServerPlayer server(Minecraft mc) {
        return mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
    }

    /** Puts a sim item in hotbar slot 0 on the server and holds slot 0. */
    static void give(ClientGameTestContext ctx, String id, int count) {
        ctx.runOnClient(mc -> {
            ItemStack s = (ItemStack) ModUnderTest.staticCall(ITEMS, "build", new Class<?>[]{String.class}, new Object[]{id});
            s.setCount(count);
            mc.getSingleplayerServer().execute(() -> server(mc).getInventory().setItem(0, s));
            mc.player.getInventory().setSelectedSlot(0);
        });
        ctx.waitTicks(4);
    }

    static void move(ClientGameTestContext ctx, String name, double x, double y, double z, float yaw) {
        ctx.runOnClient(mc -> mc.getSingleplayerServer().execute(() -> {
            ModUnderTest.staticCall(MATES, "moveForTest", new Class<?>[]{String.class, double.class, double.class, double.class},
                    new Object[]{name, x, y, z});
            ModUnderTest.staticCall(MATES, "lookForTest", new Class<?>[]{String.class, float.class, float.class},
                    new Object[]{name, yaw, 10f});
        }));
        ctx.waitTicks(4);
    }

    static void use(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND));
        ctx.waitTicks(6);
    }

    private static String title(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> McCompat.screen(mc) == null ? "none" : McCompat.screen(mc).getTitle().getString());
    }

    /** Head slot -> name in the open menu. */
    static List<String> heads(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < 36; i++) {
                ItemStack s = mc.player.containerMenu.getSlot(i).getItem();
                if (s.is(Items.PLAYER_HEAD)) out.add(i + ":" + s.getHoverName().getString());
            }
            return out;
        });
    }

    private static int slotOf(List<String> heads, String name) {
        for (String h : heads) {
            if (h.endsWith(":" + name)) return Integer.parseInt(h.substring(0, h.indexOf(':')));
        }
        return -1;
    }

    static double distTo(ClientGameTestContext ctx, double x, double z) {
        return ctx.computeOnClient(mc -> Math.hypot(mc.player.getX() - x, mc.player.getZ() - z));
    }

    private static int heldCount(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> mc.player.getInventory().getItem(0).getCount());
    }

    static boolean chatSince(long mark, String text) {
        for (String l : LogTap.since(mark)) if (l.contains("[CHAT]") && l.contains(text)) return true;
        return false;
    }

    static void run(ClientGameTestContext ctx, List<String> f) throws Exception {
        SimLeapSpots1117.pick(ctx, "MAGE");
        start(ctx, "S1", true);
        try {
            setTeammates(ctx, 2);
            SimBossTests.until(ctx, "two teammates", mc -> ((List<?>) call(MATES, "names")).size() == 2, 200);
            expect(f, "two Sim Teammates placed", mates(ctx), "[Healer, Tank]");
            ctx.waitTicks(40);
            expect(f, "Fast Leap knows Healer from the tab", ctx.computeOnClient(mc -> ModUnderTest.staticCall(
                    "com.killer560.hub.fastleap.Teammates", "byName", new Class<?>[]{String.class}, new Object[]{"Healer"}) != null), true);

            // In the boss a leap goes to the class's spot since simleapspots-1117, wherever the teammate stands: as Mage,
            // Healer is the Healer (S1: Simon Says) and Tank the Tank.
            double[] me = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
            move(ctx, "Healer", me[0] + 12, me[1], me[2] + 3, 90f);
            move(ctx, "Tank", me[0] - 9, me[1], me[2] - 6, -45f);
            double ax = 107.878, az = 93.917, bx = 108.032, bz = 73.075;

            // InfiniLeap: the menu, a click, the line, the target's look, the item stays.
            give(ctx, "INFINITE_SPIRIT_LEAP", 1);
            expect(f, "InfiniLeap in hand", ctx.computeOnClient(mc -> mc.player.getMainHandItem().getHoverName().getString()), "InfiniLeap™");
            use(ctx);
            expect(f, "the leap menu opens", title(ctx), "Spirit Leap");
            List<String> heads = heads(ctx);
            println(NAME, "heads " + heads);
            expect(f, "a head for each class he does not play (boss, alone: named by class, placed or not)", heads.size(), 4);
            for (String n : new String[]{"Healer", "Tank", "Archer", "Berserk"}) {
                expect(f, "a head named " + n + " (its class), no TeammateN", slotOf(heads, n) >= 0, true);
                String want = n.equals("Berserk") ? "BERSERKER" : n.toUpperCase();
                expect(f, "the leap overlay colors " + n + " in its class color (placed or not)", String.valueOf((Object) ctx.computeOnClient(mc ->
                        ModUnderTest.staticCall("com.killer560.hub.leapmenu.PartyTracker", "classOf",
                                new Class<?>[]{String.class}, new Object[]{n}))), want);
            }
            expect(f, "a real player name stays unknown to the class lookup", String.valueOf((Object) ctx.computeOnClient(mc ->
                    ModUnderTest.staticCall("com.killer560.hub.leapmenu.PartyTracker", "classOf",
                            new Class<?>[]{String.class}, new Object[]{"SomeRealPlayer"}))), "null");
            long mark = LogTap.mark();
            int slot = slotOf(heads, "Healer");
            ctx.runOnClient(mc -> mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, slot, 0,
                    ContainerInput.PICKUP, mc.player));
            ctx.waitTicks(10);
            expect(f, "the menu closed", title(ctx), "none");
            expect(f, "landed on Healer", distTo(ctx, ax, az) < 0.6, true);
            expect(f, "Hypixel's leap line", chatSince(mark, "You have teleported to Healer!"), true);
            expect(f, "took the Healer spot's look", ctx.computeOnClient(mc -> Math.round(net.minecraft.util.Mth.wrapDegrees(mc.player.getYRot()))), -90);
            expect(f, "the InfiniLeap is not used up", heldCount(ctx), 1);

            // On cooldown: no menu, the cooldown line.
            mark = LogTap.mark();
            use(ctx);
            expect(f, "no menu inside the 2 s cooldown", title(ctx), "none");
            expect(f, "the cooldown line", chatSince(mark, "This ability is on cooldown for"), true);

            // Again after the cooldown: leaps again (unlimited).
            ctx.waitTicks(45);
            int leaps = (Integer) ctx.computeOnClient(mc -> call(LEAP, "leapsForTest"));
            use(ctx);
            int slot2 = slotOf(heads(ctx), "Tank");
            ctx.runOnClient(mc -> mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, slot2, 0,
                    ContainerInput.PICKUP, mc.player));
            ctx.waitTicks(10);
            expect(f, "second InfiniLeap to Tank", distTo(ctx, bx, bz) < 0.6, true);
            expect(f, "leap count went up", (Integer) ctx.computeOnClient(mc -> call(LEAP, "leapsForTest")) - leaps, 1);
            expect(f, "still one InfiniLeap", heldCount(ctx), 1);

            // Fast Leap's engine (Auto Leap's) by name, hidden menu.
            ctx.waitTicks(45);
            ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.fastleap.LeapManager", "setCooldownTicksForTest",
                    new Class<?>[]{double.class}, new Object[]{0.0}));
            mark = LogTap.mark();
            ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.fastleap.LeapManager", "leap",
                    new Class<?>[]{String.class, boolean.class, boolean.class, boolean.class},
                    new Object[]{"Healer", false, true, false}));
            long m2 = mark;
            boolean got = SimBossTests.until(ctx, "Fast Leap lands on Healer", mc -> Math.hypot(mc.player.getX() - ax, mc.player.getZ() - az) < 0.6, 100);
            for (String l : LogTap.since(m2)) if (l.contains("FastLeap") || l.contains("SimLeap")) println(NAME, l);
            expect(f, "Fast Leap (Auto Leap's engine) leapt to Healer", got, true);

            // Spirit Leap: used up per leap, 5 s cooldown.
            ctx.waitTicks(45);
            give(ctx, "SPIRIT_LEAP", 3);
            use(ctx);
            expect(f, "Spirit Leap opens the same menu", title(ctx), "Spirit Leap");
            int slot3 = slotOf(heads(ctx), "Tank");
            ctx.runOnClient(mc -> mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, slot3, 0,
                    ContainerInput.PICKUP, mc.player));
            ctx.waitTicks(10);
            expect(f, "Spirit Leap to Tank", distTo(ctx, bx, bz) < 0.6, true);
            expect(f, "one Spirit Leap used up", heldCount(ctx), 2);
            ctx.waitTicks(50);
            mark = LogTap.mark();
            use(ctx);
            expect(f, "Spirit Leap still on its 5 s cooldown after 2.5 s", chatSince(mark, "This ability is on cooldown for"), true);

            // Saved: a reload (restart) keeps 2; 0 removes them.
            ctx.runOnClient(mc -> call(PREFS, "reload"));
            expect(f, "the setting survives a reload", ctx.computeOnClient(mc -> {
                try {
                    Object p = call(PREFS, "cached");
                    return p.getClass().getField("teammates").getInt(p);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            }), 2);
            setTeammates(ctx, 0);
            ctx.waitTicks(4);
            expect(f, "None removes them", mates(ctx).size(), 0);
        } finally {
            ctx.runOnClient(mc -> mc.player.closeContainer());
            setTeammates(ctx, 0);
        }
    }
}

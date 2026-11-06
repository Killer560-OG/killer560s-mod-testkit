package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.PacketTrace;

import java.util.List;
import java.util.Locale;

/**
 * Mod chestbot-1104 (killer560, 2026-11-04: "add an auto swap to drill and drill triggerbot for clicking on crystal hollows
 * powder chests. make sure the triggerbot is limited to not click the same chest 1000 times a second", then "auto swap
 * needs to go to whatever has the highest gemstone powder"). Cheat jar only.
 * <ul>
 *   <li>1104-chestbot-click: a claimed treasure chest under the crosshair, a stick held, Ruby Drill (+5% Gemstone Powder) in
 *       slot 3, Jasper Drill (+20%) in slot 5 and a Divan's Drill (no powder line) in slot 7. The bot swaps to slot 5 first
 *       (one set_carried_item before any use_item_on), then clicks: at Min = Max = 20 CPS (the sliders' top, the GrimAC
 *       check) one click a tick and never two use_item_on in one tick (killer560, 2026-11-04: "no more than once per tick
 *       on a chest"), at CPS 5 one every 4 ticks. A stone block on the chest keeps the vanilla server from opening it.
 *       Then a second chest whose lock particles (crit) sit on its TOP face: a crosshair on its side is never clicked, one on
 *       its top is. GrimAC must stay silent (default and experimental checks).</li>
 * </ul>
 */
final class HxChestBot1104Cases {

    private static final String CFG = "powdermining.PowderMiningConfig";
    private static final String TC = "powdermining.TreasureChests";
    private static final String BOT = "powdermining.PowderChestTriggerbot";
    private static final String STATE = "miningqol.MiningState";
    private static final String UNCOVERED = "§6You uncovered a treasure chest!";

    private HxChestBot1104Cases() {
    }

    static void register(Session s) {
        s.test("1104-chestbot-click", HxChestBot1104Cases::click);
        s.test("1104-chestbot-swapback", HxChestBot1104Cases::swapBack);
        s.test("1104-chestbot-nomineswap", HxChestBot1104Cases::noSwapWhileMining);
        // grimgapC-1105: his right click, his left click on the chest and his scroll in the bot's server ticks.
        s.test("1105-same-chestbot-use", c -> same(c, dev.testkit.gametest.SameTick.Act.USE));
        s.test("1105-same-chestbot-dig", c -> same(c, dev.testkit.gametest.SameTick.Act.DIG));
        s.test("1105-same-chestbot-swap", c -> same(c, dev.testkit.gametest.SameTick.Act.SWAP));
        s.test("1105-chestbot-retry", HxChestBot1104Cases::retry);
    }

    /**
     * 1105-chestbot-retry (chestretry-1105, killer560 2026-11-05: "it just tried clicking this chest but it didnt collect
     * it. so noww it doenst have a waypoint and the triggerbot doesnt get it"). The bot's clicks on chest A reach the server
     * and are ignored (a stone keeps it shut: no lid, no loot). He clicked chest B too, and B's loot block comes: it must
     * not be pinned on A (the jar before took the chest clicked last), so A keeps its highlight and the bot keeps clicking
     * it at its CPS; B goes when its block goes. A's own loot (only A clicked for 1.5 s) then ends A and the clicks stop.
     * Last, "This chest has already been looted." after clicks on C alone ends C (the give-up).
     */
    private static void retry(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Powder Chest Triggerbot is cheat-only");
            return;
        }
        c.waitUntil("standing still", mc -> mc.player.onGround()
                && mc.player.getDeltaMovement().horizontalDistanceSqr() < 1e-4, 200);
        int[] f = feet(c);
        int[] a = {f[0], f[1], f[2] + 2};
        int[] b = {f[0] - 2, f[1], f[2] + 2};
        int[] d = {f[0] + 2, f[1], f[2] + 2};
        Object cfg = c.onClient(mc -> Mod.cfg(CFG));
        boolean old = c.onClient(mc -> (Boolean) Mod.call(cfg, "isChestTriggerbotRaw"));
        try (HxKit.Settings set = new HxKit.Settings(c)
                .custom(() -> Mod.call(cfg, "setChestTriggerbot", true), () -> Mod.call(cfg, "setChestTriggerbot", old))
                .with(CFG, "ChestTriggerbotSwap", false).with(CFG, "ChestTriggerbotMaxCps", 10).with(CFG, "ChestTriggerbotMinCps", 10)
                .with(CFG, "ChestAlertSound", false).with(CFG, "ChestAlertTitle", false)) {
            hollows(c);
            for (int[] p : new int[][]{a, b, d}) {
                setblock(c, p[0], p[1], p[2], "minecraft:air"); // an earlier case's chest here would take the line
                setblock(c, p[0], p[1] + 1, p[2], "minecraft:stone"); // a vanilla chest under a solid block never opens
            }
            c.ctx().waitTicks(65); // and any chest seen in the last 3 s (TreasureChests.MATCH_TICKS) is out of the window
            claim(c, a);
            claim(c, b);
            c.ctx().runOnClient(mc -> Mod.staticCall(BOT, "resetForTest"));
            long since = c.hx().head();
            aimUntilClicked(c, a, "A");
            c.ctx().runOnClient(mc -> Mod.staticCall(TC, "useForTest", b[0], b[1], b[2])); // he clicked B as well
            c.ctx().waitTicks(3); // the bot clicks A again (the chest clicked last)
            lootBlock(c); // B's loot
            c.ctx().waitTicks(10);
            c.check(trackedAt(c, a), "A lost its highlight to another chest's loot (the server never answered A's click)");
            c.check(trackedAt(c, b), "B went on a loot block two chests could own (its block is still there)");
            setblock(c, b[0], b[1], b[2], "minecraft:air");
            c.waitUntil("B gone with its block", mc -> !(Boolean) Mod.staticCall(TC, "tracked", b[0], b[1], b[2]), 40);
            int n0 = clicks(c);
            c.ctx().waitTicks(40);
            int more = clicks(c) - n0;
            int served = c.hx().events(since, "use_block").size();
            c.note("A clicked " + more + " more time(s) in 40 ticks at 10 CPS, server use_block " + served);
            c.check(more >= 15 && more <= 21, "the bot clicked A " + more + " time(s) in 40 ticks after the stray loot (want ~20)");
            c.check(served >= 15, "the server saw " + served + " use_block (the clicks did not reach it)");
            c.check(trackedAt(c, a), "A lost its highlight while uncollected");
            lootBlock(c); // A's loot: only A clicked in the last 1.5 s
            c.waitUntil("A collected by its loot", mc -> !(Boolean) Mod.staticCall(TC, "tracked", a[0], a[1], a[2]), 20);
            int n1 = clicks(c);
            c.ctx().waitTicks(10);
            c.check(clicks(c) == n1, "the bot kept clicking the collected chest: " + (clicks(c) - n1));
            // Hypixel takes a looted chest away. A test chest stays, and a late block resend made it a fresh unclaimed chest
            // that took C's line (new-exp2, one run in two).
            setblock(c, a[0], a[1], a[2], "minecraft:air");
            c.waitUntil("A's block gone", mc -> mc.level.getBlockState(new net.minecraft.core.BlockPos(a[0], a[1], a[2])).isAir(), 40);
            c.ctx().waitTicks(65); // out of the 3 s match window too

            claim(c, d);
            aimUntilClicked(c, d, "C");
            c.hx().chat("§cThis chest has already been looted.");
            c.waitUntil("C ended by \"already looted\"", mc -> !(Boolean) Mod.staticCall(TC, "tracked", d[0], d[1], d[2]), 20);
        } finally {
            for (int[] p : new int[][]{a, b, d}) {
                setblock(c, p[0], p[1] + 1, p[2], "minecraft:air");
                setblock(c, p[0], p[1], p[2], "minecraft:air");
            }
            c.ctx().runOnClient(mc -> Mod.staticCall(CFG, "load"));
            HxKit.hub(c);
        }
    }

    private static void aimUntilClicked(Session c, int[] p, String name) {
        int n = clicks(c);
        for (int i = 0; i < 60 && clicks(c) == n; i++) {
            look(c, p[0] + 0.5, p[1] + 0.4, p[2] + 0.05); // its north face
            c.ctx().waitTicks(1);
        }
        c.check(clicks(c) > n, "chest " + name + " never clicked");
    }

    private static boolean trackedAt(Session c, int[] p) {
        return c.onClient(mc -> (Boolean) Mod.staticCall(TC, "tracked", p[0], p[1], p[2]));
    }

    /** Hypixel's loot block (as 1103-powdergrind-loot sends it). */
    private static void lootBlock(Session c) {
        String sep = "§e§l" + "▬".repeat(64);
        c.hx().chat(sep);
        c.hx().chat("§6§l  CHEST LOCKPICKED ");
        c.hx().chat("");
        c.hx().chat("§a§l  REWARDS");
        c.hx().chat("§d    Gemstone Powder §8x120");
        c.hx().chat(sep);
    }

    /**
     * SAME-TICK GRIM CASES (grimgapC-1105): a claimed chest under the crosshair at Triggerbot CPS 20 while HIS action goes
     * into the bot's ticks ({@link dev.testkit.gametest.SameTick}). use: his right click every tick, after the bot's
     * use_item_on in the same tick (Auto Swap on: the swap to the Jasper Drill in slot 2 goes first). dig: his left click
     * on the chest every other tick; ActionGate holds the bot's click for a tick his attack click is queued in (a dig after
     * a use_item_on in one tick is PacketOrderI), so the two are apart. swap: his scroll 4/5 every other tick with Auto
     * Swap OFF (on, the bot takes the slot back before his ever leaves): his slot goes out inside the bot's useItemOn,
     * ahead of the use. The server must record the bot's use_block, and GrimAC stay silent.
     */
    private static void same(Session c, dev.testkit.gametest.SameTick.Act act) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Powder Chest Triggerbot is cheat-only");
            return;
        }
        c.waitUntil("standing still", mc -> mc.player.onGround()
                && mc.player.getDeltaMovement().horizontalDistanceSqr() < 1e-4, 200);
        int[] f = feet(c);
        int[] a = {f[0], f[1], f[2] + 2};
        Object cfg = c.onClient(mc -> Mod.cfg(CFG));
        boolean old = c.onClient(mc -> (Boolean) Mod.call(cfg, "isChestTriggerbotRaw"));
        dev.testkit.gametest.SameTick st = null;
        try (HxKit.Settings set = new HxKit.Settings(c)
                .custom(() -> Mod.call(cfg, "setChestTriggerbot", true), () -> Mod.call(cfg, "setChestTriggerbot", old))
                .with(CFG, "ChestTriggerbotSwap", act != dev.testkit.gametest.SameTick.Act.SWAP).with(CFG, "ChestTriggerbotNoSwapMining", false).with(CFG, "ChestTriggerbotMaxCps", 20).with(CFG, "ChestTriggerbotMinCps", 20)
                .with(CFG, "ChestAlertSound", false).with(CFG, "ChestAlertTitle", false)) {
            c.hx().give(2, drill("GEMSTONE_DRILL_4", "Jasper Drill X", 20));
            if (act == dev.testkit.gametest.SameTick.Act.DIG) {
                // The session plays in creative, where his left click breaks the chest at once and the bot has nothing left
                // to click (0 or 1 clicks, about one run in two): survival takes 3.75 s to break it by hand.
                c.server().command("gamemode survival @p");
            }
            // SWAP (Auto Swap off): his scroll moves between two drills.
            String held = act == dev.testkit.gametest.SameTick.Act.SWAP ? drill("GEMSTONE_DRILL_4", "Jasper Drill X", 20) : "minecraft:stick";
            c.hx().give(4, held);
            c.hx().give(5, held);
            c.ctx().runOnClient(mc -> mc.player.getInventory().setSelectedSlot(4));
            hollows(c);
            // The stone goes on BEFORE the claim: a bot click on a chest that can still open lifts its lid, and an opened
            // chest is no longer claimed (the crosshair is often on the spot already from the case before).
            setblock(c, a[0], a[1] + 1, a[2], "minecraft:stone");
            claim(c, a);
            c.ctx().runOnClient(mc -> Mod.staticCall(BOT, "resetForTest"));
            for (int i = 0; i < 40; i++) {
                look(c, a[0] + 0.5, a[1] + 0.4, a[2] + 0.05);
                c.ctx().waitTicks(1);
                if (c.onClient(mc -> mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult h
                        && h.getBlockPos().equals(new net.minecraft.core.BlockPos(a[0], a[1], a[2])))) {
                    break;
                }
            }
            long since = c.hx().head();
            int every = act == dev.testkit.gametest.SameTick.Act.USE ? 1 : 2;
            st = c.onClient(mc -> dev.testkit.gametest.SameTick.start(act, every,
                    p -> p instanceof net.minecraft.network.protocol.game.ServerboundUseItemOnPacket, 4, 5));
            c.ctx().waitTicks(60);
            int clicks = clicks(c);
            int served = c.hx().events(since, "use_block").size();
            c.note("bot clicks " + clicks + ", server use_block " + served + ", swaps " + intCall(c, "swaps"));
            c.check(clicks >= 15 && served >= 15, "the bot clicked " + clicks + " time(s), the server saw " + served);
            if (act != dev.testkit.gametest.SameTick.Act.SWAP) {
                c.check(intCall(c, "lastSwapSlot") == 2, "the bot never swapped to the Jasper Drill (slot 2)");
            }
            dev.testkit.gametest.SameTick.judge(c, st, "chest triggerbot", act == dev.testkit.gametest.SameTick.Act.DIG
                    ? dev.testkit.gametest.SameTick.Expect.APART : dev.testkit.gametest.SameTick.Expect.SHARE);
            if (act == dev.testkit.gametest.SameTick.Act.SWAP) {
                c.check(st.playerAfterFeature() == 0, "his slot went out after the bot's click in a server tick: " + st.report());
            }
            st = null;
        } finally {
            if (st != null) {
                dev.testkit.gametest.SameTick s2 = st;
                c.onClient(mc -> {
                    s2.close();
                    return null;
                });
            }
            c.ctx().runOnClient(mc -> {
                if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
                    mc.player.closeContainer();
                }
            });
            setblock(c, a[0], a[1] + 1, a[2], "minecraft:air");
            setblock(c, a[0], a[1], a[2], "minecraft:air");
            if (act == dev.testkit.gametest.SameTick.Act.DIG) c.server().command("gamemode creative @p");
            for (int s : new int[]{2, 4, 5}) {
                c.hx().give(s, "minecraft:air");
            }
            c.ctx().runOnClient(mc -> Mod.staticCall(CFG, "load"));
            HxKit.hub(c);
        }
    }

    private static void click(Session c) {
        if (!Mod.isCheat()) {
            c.check(c.onClient(mc -> !(Boolean) Mod.call(Mod.cfg(CFG), "isChestTriggerbot")),
                    "the triggerbot reads ON in a legit jar");
            c.note("legit jar: Powder Chest Triggerbot is cheat-only");
            return;
        }
        // As a session's first case the player can still be falling or taking teleports from the join: chest A must be
        // placed and aimed from where he stands.
        c.waitUntil("standing still", mc -> mc.player.onGround()
                && mc.player.getDeltaMovement().horizontalDistanceSqr() < 1e-4, 200);
        int[] f = feet(c);
        int[] a = {f[0], f[1], f[2] + 2};
        int[] b = {f[0] + 2, f[1], f[2] + 2};
        Object cfg = c.onClient(mc -> Mod.cfg(CFG));
        boolean old = c.onClient(mc -> (Boolean) Mod.call(cfg, "isChestTriggerbotRaw"));
        try (HxKit.Settings set = new HxKit.Settings(c)
                .custom(() -> Mod.call(cfg, "setChestTriggerbot", true), () -> Mod.call(cfg, "setChestTriggerbot", old))
                .with(CFG, "ChestTriggerbotSwap", true).with(CFG, "ChestTriggerbotMaxCps", 20).with(CFG, "ChestTriggerbotMinCps", 20).with(CFG, "ChestAlertSound", false)
                .with(CFG, "ChestAlertTitle", false)) {
            c.hx().give(0, "minecraft:stick");
            c.hx().give(2, drill("GEMSTONE_DRILL_1", "Ruby Drill TX-15", 5));
            c.hx().give(4, drill("GEMSTONE_DRILL_4", "Jasper Drill X", 20));
            c.hx().give(6, "minecraft:prismarine_shard[custom_data={id:\"DIVAN_DRILL\"},item_name=\"Divan's Drill\"]");
            c.ctx().runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
            hollows(c);
            claim(c, a);
            setblock(c, a[0], a[1] + 1, a[2], "minecraft:stone"); // a vanilla chest under a solid block never opens
            c.ctx().runOnClient(mc -> Mod.staticCall(BOT, "resetForTest"));
            c.ctx().waitTicks(3);

            PacketTrace.start();
            for (int i = 0; i < 60 && clicks(c) == 0; i++) {
                look(c, a[0] + 0.5, a[1] + 0.4, a[2] + 0.05); // its north face, re-aimed while he settles
                c.ctx().waitTicks(1);
            }
            c.check(clicks(c) >= 1, "chest A never clicked: " + c.<String>onClient(mc -> "hit " + mc.hitResult.getType() + " "
                    + (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult h ? h.getBlockPos() + " " + h.getDirection()
                    : "") + ", at " + mc.player.blockPosition() + ", tracked " + Mod.staticCall(TC, "tracked", a[0], a[1], a[2])
                    + ", chests " + Mod.field(TC, "CHESTS")));
            int slot = c.onClient(mc -> mc.player.getInventory().getSelectedSlot());
            c.check(slot == 4, "held slot is " + slot + ", not the Jasper Drill's 4 (highest Gemstone Powder)");
            c.check(intCall(c, "swaps") == 1 && intCall(c, "lastSwapSlot") == 4, "swaps " + intCall(c, "swaps")
                    + " last " + intCall(c, "lastSwapSlot"));
            List<String> lines = PacketTrace.lines();
            int swapAt = firstLine(lines, "set_carried_item");
            int useAt = firstLine(lines, "use_item_on");
            c.check(swapAt >= 0 && useAt > swapAt, "the swap (tick " + swapAt + ") did not go a tick before the click (tick "
                    + useAt + ")");
            int at20 = clicks(c);
            c.ctx().waitTicks(40);
            int fast = clicks(c) - at20;
            c.check(fast >= 36 && fast <= 41, fast + " clicks in 40 ticks at 20 CPS (want one a tick)");
            c.check(PacketTrace.maxPerTick("use_item_on") == 1, PacketTrace.maxPerTick("use_item_on")
                    + " use_item_on in one tick (the limit is one a tick on a chest)");
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "setChestTriggerbotMinCps", 5);
                Mod.call(cfg, "setChestTriggerbotMaxCps", 5);
            });
            c.ctx().waitTicks(4);
            int at5 = clicks(c);
            c.ctx().waitTicks(60);
            int slow = clicks(c) - at5;
            c.check(slow >= 14 && slow <= 16, slow + " clicks in 60 ticks at 5 CPS (want 15)");
            // Min 4 / Max 8: every click rolls a rate in [4, 8], so 100 ticks hold 20-40 clicks; the setters keep Min <= Max.
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "setChestTriggerbotMaxCps", 8);
                Mod.call(cfg, "setChestTriggerbotMinCps", 4);
            });
            c.ctx().waitTicks(4);
            int atRange = clicks(c);
            c.ctx().waitTicks(100);
            int ranged = clicks(c) - atRange;
            c.check(ranged >= 18 && ranged <= 42, ranged + " clicks in 100 ticks at 4-8 CPS (want 20-40)");
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "setChestTriggerbotMinCps", 15);
                Mod.call(cfg, "setChestTriggerbotMaxCps", 2);
            });
            int[] mm = c.onClient(mc -> new int[]{((Number) Mod.call(cfg, "getChestTriggerbotMinCps")).intValue(),
                    ((Number) Mod.call(cfg, "getChestTriggerbotMaxCps")).intValue()});
            c.check(mm[0] == 8 && mm[1] == 8, "Min above Max / Max below Min not clamped: min " + mm[0] + " max " + mm[1]);
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "setChestTriggerbotMinCps", 5);
                Mod.call(cfg, "setChestTriggerbotMaxCps", 5);
            });
            int total = clicks(c);
            int sentUse = PacketTrace.sent("use_item_on");
            c.check(sentUse == total, sentUse + " use_item_on sent for " + total + " clicks");
            c.check(PacketTrace.sentOutsideTick().isEmpty(), "sent outside a tick: " + PacketTrace.sentOutsideTick());

            // Chest B: its lock is on the top face. A side hit is never clicked; a top hit is. Look away from A first.
            c.ctx().runOnClient(mc -> mc.player.setXRot(-90f));
            c.ctx().waitTicks(2);
            claim(c, b);
            int base = clicks(c);
            for (int i = 0; i < 6; i++) {
                crit(c, b);
                c.ctx().waitTicks(3);
            }
            c.waitUntil("lock face read", mc -> "UP".equals(Mod.staticCall("powdermining.LockpickHelper", "faceNow")), 40);
            look(c, b[0] + 0.05, b[1] + 0.4, b[2] + 0.5); // its west face
            for (int i = 0; i < 6; i++) {
                crit(c, b);
                c.ctx().waitTicks(3);
            }
            c.check(clicks(c) == base, "clicked chest B on its side while its lock is on top");
            look(c, b[0] + 0.5, b[1] + 0.875, b[2] + 0.5);
            for (int i = 0; i < 10 && clicks(c) == base; i++) {
                crit(c, b);
                c.ctx().waitTicks(3);
            }
            c.check(clicks(c) > base, "chest B's top (its lock face) was not clicked");
        } finally {
            PacketTrace.stop();
            c.ctx().runOnClient(mc -> {
                if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
                    mc.player.closeContainer(); // chest B opens on the vanilla server once clicked
                }
            });
            for (int[] p : new int[][]{a, b}) {
                setblock(c, p[0], p[1] + 1, p[2], "minecraft:air");
                setblock(c, p[0], p[1], p[2], "minecraft:air");
            }
            c.ctx().runOnClient(mc -> Mod.staticCall(CFG, "load"));
            HxKit.hub(c);
        }
    }

    /**
     * Swap Back When Mining: chest claimed, the bot swaps from the stick (slot 0) to the Jasper Drill (slot 4); the first dig
     * start on a stone puts the stick back (a set_carried_item to 0 ahead of the dig, GrimAC silent). A second visit with
     * the stick replaced by an apple before the dig: no swap back (the slot no longer holds what he had).
     */
    private static void swapBack(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Powder Chest Triggerbot is cheat-only");
            return;
        }
        c.waitUntil("standing still", mc -> mc.player.onGround()
                && mc.player.getDeltaMovement().horizontalDistanceSqr() < 1e-4, 200);
        int[] f = feet(c);
        int[] a = {f[0], f[1], f[2] + 2};
        int[] st = {f[0] - 2, f[1], f[2] + 2};
        Object cfg = c.onClient(mc -> Mod.cfg(CFG));
        boolean old = c.onClient(mc -> (Boolean) Mod.call(cfg, "isChestTriggerbotRaw"));
        try (HxKit.Settings set = new HxKit.Settings(c)
                .custom(() -> Mod.call(cfg, "setChestTriggerbot", true), () -> Mod.call(cfg, "setChestTriggerbot", old))
                .with(CFG, "ChestTriggerbotSwap", true).with(CFG, "ChestTriggerbotSwapBack", true)
                .with(CFG, "ChestTriggerbotNoSwapMining", false).with(CFG, "ChestTriggerbotMaxCps", 20)
                .with(CFG, "ChestTriggerbotMinCps", 20).with(CFG, "ChestAlertSound", false).with(CFG, "ChestAlertTitle", false)) {
            c.hx().give(0, "minecraft:stick");
            c.hx().give(4, drill("GEMSTONE_DRILL_4", "Jasper Drill X", 20));
            hollows(c);
            claim(c, a);
            setblock(c, a[0], a[1] + 1, a[2], "minecraft:stone");
            setblock(c, st[0], st[1], st[2], "minecraft:stone");
            for (int round = 0; round < 2; round++) {
                boolean moved = round == 1;
                c.ctx().runOnClient(mc -> {
                    mc.player.getInventory().setSelectedSlot(0);
                    Mod.staticCall(BOT, "resetForTest");
                });
                if (moved) {
                    c.hx().give(0, "minecraft:stick"); // restored below by the swap-back round's end
                }
                PacketTrace.start();
                for (int i = 0; i < 60 && intCall(c, "swaps") == 0; i++) {
                    look(c, a[0] + 0.5, a[1] + 0.4, a[2] + 0.05);
                    c.ctx().waitTicks(1);
                }
                c.check(intCall(c, "swaps") == 1, "round " + round + ": no swap to the drill");
                c.check(c.onClient(mc -> mc.player.getInventory().getSelectedSlot()) == 4, "round " + round + ": not on the drill");
                look(c, st[0] + 0.5, st[1] + 0.5, st[2] + 0.5);
                if (moved) {
                    c.hx().give(0, "minecraft:apple"); // slot 0 no longer holds the stick
                }
                c.ctx().waitTicks(2);
                // He mines the stone with the real attack key (vanilla's startAttack: dig, then the swing GrimAC wants).
                for (int i = 0; i < 6 && (moved ? i < 4 : intCall(c, "swapBacks") == 0); i++) {
                    c.ctx().runOnClient(mc -> mc.options.keyAttack.setDown(true));
                    c.ctx().waitTicks(2);
                    c.ctx().runOnClient(mc -> mc.options.keyAttack.setDown(false));
                    c.ctx().waitTicks(2);
                    setblock(c, st[0], st[1], st[2], "minecraft:stone");
                    c.ctx().waitTicks(2);
                }
                int slot = c.onClient(mc -> mc.player.getInventory().getSelectedSlot());
                if (!moved) {
                    c.check(intCall(c, "swapBacks") == 1 && slot == 0, "swap back: swapBacks " + intCall(c, "swapBacks")
                            + ", held slot " + slot + " (want 0)");
                    List<String> lines = PacketTrace.lines();
                    int last = -1;
                    for (int i = 0; i < lines.size(); i++) {
                        if ((" " + lines.get(i).split("<")[0] + " ").contains(" set_carried_item ")) last = i;
                    }
                    c.check(last >= 0, "no set_carried_item sent for the swap back");
                } else {
                    c.check(intCall(c, "swapBacks") == 0 && slot == 4, "slot 0 changed but it swapped back: swapBacks "
                            + intCall(c, "swapBacks") + ", slot " + slot);
                }
                PacketTrace.stop();
            }
        } finally {
            c.ctx().runOnClient(mc -> mc.options.keyAttack.setDown(false));
            PacketTrace.stop();
            setblock(c, a[0], a[1] + 1, a[2], "minecraft:air");
            setblock(c, a[0], a[1], a[2], "minecraft:air");
            setblock(c, st[0], st[1], st[2], "minecraft:air");
            c.ctx().runOnClient(mc -> Mod.staticCall(CFG, "load"));
            HxKit.hub(c);
        }
    }

    /**
     * Don't Swap While Mining: with the attack key held and the crosshair on the claimed chest the bot never swaps (20 ticks,
     * zero swaps, still on the stick); released, it swaps to the drill. The switch off: it swaps with the key held.
     */
    private static void noSwapWhileMining(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Powder Chest Triggerbot is cheat-only");
            return;
        }
        c.waitUntil("standing still", mc -> mc.player.onGround()
                && mc.player.getDeltaMovement().horizontalDistanceSqr() < 1e-4, 200);
        int[] f = feet(c);
        int[] a = {f[0], f[1], f[2] + 2};
        Object cfg = c.onClient(mc -> Mod.cfg(CFG));
        boolean old = c.onClient(mc -> (Boolean) Mod.call(cfg, "isChestTriggerbotRaw"));
        try (HxKit.Settings set = new HxKit.Settings(c)
                .custom(() -> Mod.call(cfg, "setChestTriggerbot", true), () -> Mod.call(cfg, "setChestTriggerbot", old))
                .with(CFG, "ChestTriggerbotSwap", true).with(CFG, "ChestTriggerbotNoSwapMining", true)
                .with(CFG, "ChestTriggerbotMaxCps", 20).with(CFG, "ChestTriggerbotMinCps", 20)
                .with(CFG, "ChestAlertSound", false).with(CFG, "ChestAlertTitle", false)) {
            c.server().command("gamemode adventure @p"); // a held attack key must not break the chest
            c.hx().give(0, "minecraft:stick");
            c.hx().give(4, drill("GEMSTONE_DRILL_4", "Jasper Drill X", 20));
            c.ctx().runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
            hollows(c);
            claim(c, a);
            setblock(c, a[0], a[1] + 1, a[2], "minecraft:stone");
            c.ctx().runOnClient(mc -> Mod.staticCall(BOT, "resetForTest"));
            c.ctx().runOnClient(mc -> mc.options.keyAttack.setDown(true));
            for (int i = 0; i < 20; i++) {
                look(c, a[0] + 0.5, a[1] + 0.4, a[2] + 0.05);
                c.ctx().waitTicks(1);
            }
            boolean onChest = c.onClient(mc -> mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult h
                    && h.getBlockPos().equals(new net.minecraft.core.BlockPos(a[0], a[1], a[2])));
            c.check(onChest, "the crosshair is not on the chest, the case proves nothing");
            c.check(intCall(c, "swaps") == 0 && c.onClient(mc -> mc.player.getInventory().getSelectedSlot()) == 0,
                    "swapped while the attack key was held: swaps " + intCall(c, "swaps"));
            c.ctx().runOnClient(mc -> mc.options.keyAttack.setDown(false));
            for (int i = 0; i < 20 && intCall(c, "swaps") == 0; i++) {
                look(c, a[0] + 0.5, a[1] + 0.4, a[2] + 0.05);
                c.ctx().waitTicks(1);
            }
            c.check(intCall(c, "swaps") == 1, "no swap once the attack key was released");
            // Switch off: the held key no longer stops it.
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "setChestTriggerbotNoSwapMining", false);
                mc.player.getInventory().setSelectedSlot(0);
                Mod.staticCall(BOT, "resetForTest");
                mc.options.keyAttack.setDown(true);
            });
            for (int i = 0; i < 40 && intCall(c, "swaps") == 0; i++) {
                look(c, a[0] + 0.5, a[1] + 0.4, a[2] + 0.05);
                c.ctx().waitTicks(1);
            }
            c.check(intCall(c, "swaps") == 1, "the switch is off but the held attack key still stopped the swap");
        } finally {
            c.ctx().runOnClient(mc -> mc.options.keyAttack.setDown(false));
            c.server().command("gamemode creative @p");
            setblock(c, a[0], a[1] + 1, a[2], "minecraft:air");
            setblock(c, a[0], a[1], a[2], "minecraft:air");
            c.ctx().runOnClient(mc -> Mod.staticCall(CFG, "load"));
            HxKit.hub(c);
        }
    }

    private static String drill(String id, String name, int bonus) {
        return "minecraft:prismarine_shard[custom_data={id:\"" + id + "\"},item_name=\"" + name + "\",lore=[{text:\"Grants +"
                + bonus + "% Gemstone Powder, and\",italic:false}]]";
    }

    private static void crit(Session c, int[] p) {
        c.server().command(String.format(Locale.ROOT, "particle minecraft:crit %.2f %.2f %.2f 0.05 0 0.05 0 4 force",
                p[0] + 0.5, p[1] + 0.95, p[2] + 0.5));
    }

    private static int firstLine(List<String> lines, String id) {
        for (int i = 0; i < lines.size(); i++) {
            if ((" " + lines.get(i).split("<")[0] + " ").contains(" " + id + " ")) return i;
        }
        return -1;
    }

    private static void look(Session c, double tx, double ty, double tz) {
        c.ctx().runOnClient(mc -> {
            double dx = tx - mc.player.getX(), dy = ty - mc.player.getEyeY(), dz = tz - mc.player.getZ();
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
            mc.player.setYRot(mc.player.getYRot() + net.minecraft.util.Mth.wrapDegrees(yaw - mc.player.getYRot()));
            mc.player.setXRot(net.minecraft.util.Mth.clamp(pitch, -90f, 90f));
        });
    }

    private static int clicks(Session c) {
        return intCall(c, "clicks");
    }

    private static int intCall(Session c, String m) {
        return c.onClient(mc -> ((Number) Mod.staticCall(BOT, m)).intValue());
    }

    private static void hollows(Session c) {
        c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Jungle", "Purse: 0");
        c.hx().tab(List.of("Area: Crystal Hollows", "Profile: Test", "", "Powders:", " Gemstone: 1,000", ""), null, null);
        c.waitUntil("Crystal Hollows", mc -> "CRYSTAL_HOLLOWS".equals(Mod.staticCall(STATE, "island")), 100);
    }

    private static void setblock(Session c, int x, int y, int z, String block) {
        c.server().command(String.format(Locale.ROOT, "setblock %d %d %d %s", x, y, z, block));
    }

    private static int[] feet(Session c) {
        double[] at = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        return new int[]{(int) Math.floor(at[0]), (int) Math.floor(at[1]), (int) Math.floor(at[2])};
    }

    /** The line then the chest block, waited for; a miss fails with every tick's state (who dropped it). */
    private static void claim(Session c, int[] p) {
        StringBuilder trace = new StringBuilder();
        snap(c, p, "before", trace);
        c.hx().chat(UNCOVERED);
        c.ctx().waitTicks(2);
        snap(c, p, "line", trace);
        setblock(c, p[0], p[1], p[2], "minecraft:chest");
        for (int i = 0; i < 40; i++) {
            if (c.onClient(mc -> (Boolean) Mod.staticCall(TC, "tracked", p[0], p[1], p[2]))) {
                return;
            }
            snap(c, p, "t" + i, trace);
            c.ctx().waitTicks(1);
        }
        c.check(false, "chest " + p[0] + " " + p[1] + " " + p[2] + " never claimed:" + trace);
    }

    private static void snap(Session c, int[] p, String tag, StringBuilder trace) {
        trace.append(" | ").append(tag).append(' ').append(c.<String>onClient(mc -> "isl=" + Mod.staticCall(STATE, "island")
                + " trk=" + Mod.staticCall(TC, "tracking") + " pend=" + Mod.field(TC, "pendingLines") + "@"
                + Mod.field(TC, "pendingAt") + " now=" + Mod.staticCall("util.PerTickServerClock", "now") + " unc="
                + Mod.field(TC, "UNCLAIMED") + " chests=" + Mod.field(TC, "CHESTS") + " blk="
                + mc.level.getBlockState(new net.minecraft.core.BlockPos(p[0], p[1], p[2])).getBlock().getDescriptionId()
                .replace("block.minecraft.", "") + " d2=" + mc.player.blockPosition()
                .distSqr(new net.minecraft.core.BlockPos(p[0], p[1], p[2]))));
    }
}

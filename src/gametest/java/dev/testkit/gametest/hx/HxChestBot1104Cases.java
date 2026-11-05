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
 *       (one set_carried_item before any use_item_on), then clicks; no second click inside 0.75 s, at most one per 0.75 s
 *       after it, and never more than 8 on the chest. A stone block on the chest keeps the vanilla server from opening it.
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
    }

    private static void click(Session c) {
        if (!Mod.isCheat()) {
            c.check(c.onClient(mc -> !(Boolean) Mod.call(Mod.cfg(CFG), "isChestTriggerbot")),
                    "the triggerbot reads ON in a legit jar");
            c.note("legit jar: Powder Chest Triggerbot is cheat-only");
            return;
        }
        int[] f = feet(c);
        int[] a = {f[0], f[1], f[2] + 2};
        int[] b = {f[0] + 2, f[1], f[2] + 2};
        Object cfg = c.onClient(mc -> Mod.cfg(CFG));
        boolean old = c.onClient(mc -> (Boolean) Mod.call(cfg, "isChestTriggerbotRaw"));
        try (HxKit.Settings set = new HxKit.Settings(c)
                .custom(() -> Mod.call(cfg, "setChestTriggerbot", true), () -> Mod.call(cfg, "setChestTriggerbot", old))
                .with(CFG, "ChestTriggerbotSwap", true).with(CFG, "ChestAlertSound", false)
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
            look(c, a[0] + 0.5, a[1] + 0.4, a[2] + 0.05); // its north face
            c.waitUntil("the bot clicked chest A", mc -> clicks(c) >= 1, 60);
            long firstSeen = System.currentTimeMillis();
            int slot = c.onClient(mc -> mc.player.getInventory().getSelectedSlot());
            c.check(slot == 4, "held slot is " + slot + ", not the Jasper Drill's 4 (highest Gemstone Powder)");
            c.check(intCall(c, "swaps") == 1 && intCall(c, "lastSwapSlot") == 4, "swaps " + intCall(c, "swaps")
                    + " last " + intCall(c, "lastSwapSlot"));
            List<String> lines = PacketTrace.lines();
            int swapAt = firstLine(lines, "set_carried_item");
            int useAt = firstLine(lines, "use_item_on");
            c.check(swapAt >= 0 && useAt > swapAt, "the swap (tick " + swapAt + ") did not go a tick before the click (tick "
                    + useAt + ")");
            c.ctx().waitTicks(10); // 0.5 s: inside the 0.75 s cooldown
            c.check(clicks(c) == 1, clicks(c) + " clicks within 0.5 s of the first - the cooldown did not hold");
            c.ctx().waitTicks(30); // ~2 s after the first click
            int two = clicks(c);
            long most = 2 + (System.currentTimeMillis() - firstSeen) / 750;
            c.check(two >= 2 && two <= most, two + " clicks in ~2 s (want 2.." + most + " at one per 0.75 s)");
            c.ctx().waitTicks(140); // ~9 s: past the 8-click cap
            int capped = clicks(c);
            c.check(capped == 8, capped + " clicks after 9 s on one chest (cap 8)");
            int sentUse = PacketTrace.sent("use_item_on");
            c.check(sentUse == capped, sentUse + " use_item_on sent for " + capped + " clicks");
            c.check(PacketTrace.sentOutsideTick().isEmpty(), "sent outside a tick: " + PacketTrace.sentOutsideTick());

            // Chest B: its lock is on the top face. A side hit is never clicked; a top hit is.
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
            c.check(clicks(c) == base + 1, "chest B's top (its lock face) was not clicked: " + (clicks(c) - base));
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

    /**
     * The line then the chest block, waited for. Retried (block cleared, island re-sent) because as the session's first
     * case the claim was missed about one run in three (26.2 and 26.3 experimental, chestbot-1104: the line and the block
     * both arrived, nothing was tracked) - the session's own start-up traffic, not the triggerbot under test.
     */
    private static void claim(Session c, int[] p) {
        for (int attempt = 1; ; attempt++) {
            c.hx().chat(UNCOVERED);
            c.ctx().waitTicks(2);
            setblock(c, p[0], p[1], p[2], "minecraft:chest");
            for (int i = 0; i < 40; i++) {
                if (c.onClient(mc -> (Boolean) Mod.staticCall(TC, "tracked", p[0], p[1], p[2]))) {
                    return;
                }
                c.ctx().waitTicks(1);
            }
            c.check(attempt < 3, "chest " + p[0] + " " + p[1] + " " + p[2] + " never claimed in 3 tries");
            if (attempt >= 3) return;
            c.note("claim of " + p[0] + " " + p[1] + " " + p[2] + " missed on try " + attempt + " (island "
                    + c.onClient(mc -> String.valueOf(Mod.staticCall(STATE, "island"))) + ", server tick "
                    + c.onClient(mc -> String.valueOf(Mod.staticCall("util.PerTickServerClock", "now"))) + "); retrying");
            setblock(c, p[0], p[1], p[2], "minecraft:air");
            c.ctx().waitTicks(10);
            hollows(c);
        }
    }
}

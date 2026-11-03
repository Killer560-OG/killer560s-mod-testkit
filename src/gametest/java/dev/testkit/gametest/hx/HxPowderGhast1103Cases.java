package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import net.minecraft.world.entity.Entity;

import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod powderghast-1103 (killer560, 2026-11-03: "also add powder ghast alerts tracers and esp and such"). Draw-only apart
 * from the opt-in party line; the session's GrimAC check covers the traffic.
 * <ul>
 *   <li>1103-powderghast-alert: Hypixel's spawn line ("The sound of pickaxes clashing ... POWDER GHAST!") and place line
 *       ("Find the Powder Ghast near the X!") start the alert, the despawn timer (about 60 s) and the HUD line, and nothing
 *       is sent; the same words from "[VIP] Bob" do nothing; outside the Dwarven Mines nothing fires; with Party Callout on,
 *       exactly one "pc Powder Ghast near the Forge Basin!" goes out; the switch OFF silences the spawn line.</li>
 *   <li>1103-powderghast-draw: an unnamed ghast is never picked with no event running; a NoAI ghast named "Powder Ghast"
 *       12 blocks out is picked, its box and tracer are drawn (frame counters move) and it is in sight; behind a stone wall
 *       the legit box stops (the tracer keeps drawing) and Through Walls brings it back only on a cheat jar; a hit
 *       (/damage) resets the minute; the switch OFF stops every draw; a killed ghast is dropped.</li>
 * </ul>
 */
final class HxPowderGhast1103Cases {

    private static final String CFG = "miningqol.MiningQolConfig";
    private static final String STATE = "miningqol.MiningState";
    private static final String PG = "miningqol.PowderGhast";
    private static final String SPAWN = "§6The sound of pickaxes clashing against the rock has attracted the attention of the "
            + "§r§c§lPOWDER GHAST§r§6!";
    private static final String TAIL = ",NoAI:1b,Silent:1b,PersistenceRequired:1b}";

    private HxPowderGhast1103Cases() {
    }

    static void register(Session s) {
        s.test("1103-powderghast-alert", HxPowderGhast1103Cases::alert);
        s.test("1103-powderghast-draw", HxPowderGhast1103Cases::draw);
    }

    private static void mines(Session c) {
        c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Royal Mines", "Purse: 0");
        c.hx().tab(List.of("Area: Dwarven Mines", "Profile: Test", "", "Commissions:", " Goblin Slayer: 10%", ""), null, null);
        c.waitUntil("Dwarven Mines", mc -> "DWARVEN_MINES".equals(Mod.staticCall(STATE, "island")), 100);
    }

    private static int intCall(Session c, String m) {
        return c.onClient(mc -> ((Number) Mod.staticCall(PG, m)).intValue());
    }

    private static long left(Session c) {
        return c.onClient(mc -> ((Number) Mod.staticCall(PG, "secondsLeft")).longValue());
    }

    /** The switch ON for the case (read raw: the gated getter reads false outside Skyblock), restored on close. */
    private static Settings on(Session c) {
        Object cfg = c.onClient(mc -> Mod.cfg(CFG));
        boolean old = c.onClient(mc -> (Boolean) Mod.call(cfg, "isPowderGhastRaw"));
        return new Settings(c).custom(() -> Mod.call(cfg, "setPowderGhast", true),
                () -> Mod.call(cfg, "setPowderGhast", old));
    }

    private static void setOn(Session c, boolean v) {
        c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setPowderGhast", v));
    }

    private static long pcCount(Session c) {
        return c.commands().stream().filter(s -> s.startsWith("pc ") && s.contains("Powder Ghast")).count();
    }

    // ---- 1103-powderghast-alert -------------------------------------------------------------------------------------

    private static void alert(Session c) {
        try (Settings set = on(c).with(CFG, "PowderGhastTitle", true).with(CFG, "PowderGhastSound", false)
                .with(CFG, "PowderGhastParty", false).with(CFG, "PowderGhastTimer", true)) {
            // Outside the Dwarven Mines: nothing.
            hub(c);
            int a0 = intCall(c, "alerts");
            c.hx().chat(SPAWN);
            c.ctx().waitTicks(5);
            c.check(intCall(c, "alerts") == a0, "the spawn line fired outside the Dwarven Mines");

            mines(c);
            c.hx().chat("§7[VIP] Bob§f: The sound of pickaxes clashing against the rock has attracted the attention of the POWDER GHAST!");
            c.hx().chat("§7Bob§7: Find the Powder Ghast near the Forge Basin!");
            c.ctx().waitTicks(5);
            c.check(intCall(c, "alerts") == a0, "a player's chat line fired the alert");
            c.check(left(c) == -1, "a player's chat line started the timer");

            long pc0 = pcCount(c);
            c.hx().chat(SPAWN);
            c.hx().chat("§eFind the Powder Ghast near the §r§bCliffside Veins§r§e!");
            c.waitUntil("the alert", mc -> ((Number) Mod.staticCall(PG, "alerts")).intValue() == a0 + 1, 20);
            c.waitUntil("the place", mc -> "Cliffside Veins".equals(Mod.staticCall(PG, "placeNow")), 20);
            long l = left(c);
            c.check(l >= 57 && l <= 60, "timer reads " + l + " s after the spawn");
            String hud = c.onClient(mc -> (String) Mod.staticCall(PG, "hudLineNow"));
            c.check(hud != null && hud.contains("Cliffside Veins") && hud.matches(".*§e(5[7-9]|60)s.*"), "HUD line " + hud);
            c.waitUntil("the timer HUD is drawn",
                    mc -> (Boolean) Mod.staticCall("hud.HudSeen", "drawnRecently", PowderGhastIds.TIMER), 40);
            c.ctx().waitTicks(50); // past the 2 s party fallback
            c.check(pcCount(c) == pc0, "a party line went out with Party Callout off");
            c.note("alert, place and timer: " + hud);

            // The minute up: the event ends.
            c.ctx().runOnClient(mc -> Mod.staticCall(PG, "expireNow"));
            c.waitUntil("the event ends at the minute", mc -> ((Number) Mod.staticCall(PG, "secondsLeft")).longValue() == -1, 20);

            // Party Callout on: exactly one line per spawn.
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setPowderGhastParty", true));
            c.hx().chat(SPAWN);
            c.hx().chat("§eFind the Powder Ghast near the §r§bForge Basin§r§e!");
            c.waitUntil("the party callout", mc -> c.commands().stream()
                    .anyMatch(s -> s.equals("pc Powder Ghast near the Forge Basin!")), 60);
            c.ctx().waitTicks(50);
            c.check(pcCount(c) == pc0 + 1, "party lines sent: " + (pcCount(c) - pc0));
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setPowderGhastParty", false));
            c.ctx().runOnClient(mc -> Mod.staticCall(PG, "expireNow"));

            // The switch OFF: the spawn line does nothing.
            setOn(c, false);
            int a1 = intCall(c, "alerts");
            c.hx().chat(SPAWN);
            c.ctx().waitTicks(10);
            c.check(intCall(c, "alerts") == a1, "the spawn line fired with the switch OFF");
            c.check(left(c) == -1, "the timer runs with the switch OFF");
            c.check(c.events("container.click").isEmpty(), "Powder Ghast clicked something");
        } finally {
            c.ctx().runOnClient(mc -> Mod.staticCall(CFG, "load"));
            hub(c);
        }
    }

    // ---- 1103-powderghast-draw --------------------------------------------------------------------------------------

    private static int ghastNamed(net.minecraft.client.Minecraft mc, boolean named) {
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof net.minecraft.world.entity.monster.Ghast && e.isAlive()
                    && (named == (e.hasCustomName() && e.getCustomName().getString().contains("Powder Ghast")))) {
                return e.getId();
            }
        }
        return -1;
    }

    private static void draw(Session c) {
        double[] at = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        int bx = (int) Math.floor(at[0]), by = (int) Math.floor(at[1]), bz = (int) Math.floor(at[2]);
        try (Settings set = on(c).with(CFG, "PowderGhastBox", true).with(CFG, "PowderGhastTracer", true)
                .with(CFG, "PowderGhastSound", false).with(CFG, "PowderGhastTitle", false)
                .with(CFG, "PowderGhastParty", false).with(CFG, "PowderGhastEsp", false)) {
            mines(c);
            c.ctx().runOnClient(mc -> {
                mc.player.setYRot(180f);
                mc.player.setXRot(0f);
            });
            // An unnamed ghast with no event running: never picked.
            c.server().command(String.format(java.util.Locale.ROOT, "summon ghast %.2f %.2f %.2f", at[0] + 10, at[1] + 2, at[2] - 14)
                    + " {Tags:[\"k1103pg\",\"k1103pgu\"]" + TAIL);
            c.waitUntil("the unnamed ghast on the client", mc -> ghastNamed(mc, false) >= 0, 80);
            c.ctx().waitTicks(20);
            c.check(intCall(c, "targetId") == -1, "an unnamed ghast was picked with no event running");
            c.server().command("kill @e[tag=k1103pgu]");
            c.waitUntil("the unnamed ghast gone", mc -> ghastNamed(mc, false) < 0, 60);

            // The named one, 12 blocks north in the open.
            c.server().command(String.format(java.util.Locale.ROOT, "summon ghast %.2f %.2f %.2f", at[0], at[1], at[2] - 12)
                    + " {Tags:[\"k1103pg\"],CustomName:\"Powder Ghast\",CustomNameVisible:1b" + TAIL);
            c.waitUntil("the Powder Ghast on the client", mc -> ghastNamed(mc, true) >= 0, 80);
            int ghast = c.onClient(mc -> ghastNamed(mc, true));
            c.waitUntil("the Powder Ghast picked", mc -> ((Number) Mod.staticCall(PG, "targetId")).intValue() == ghast, 40);
            int box0 = intCall(c, "boxFrames"), tr0 = intCall(c, "tracerFrames");
            c.ctx().waitTicks(20);
            int box1 = intCall(c, "boxFrames"), tr1 = intCall(c, "tracerFrames");
            c.note("in the open: box frames +" + (box1 - box0) + ", tracer frames +" + (tr1 - tr0));
            c.check(c.onClient(mc -> (Boolean) Mod.staticCall(PG, "visibleNow")), "the ghast in the open is not in sight");
            c.check(box1 > box0, "no box drawn on the ghast in the open");
            c.check(tr1 > tr0, "no tracer drawn to the ghast");
            c.check(!c.onClient(mc -> (Boolean) Mod.staticCall(PG, "throughWallsNow")), "through walls with the setting OFF");
            String alive = c.onClient(mc -> (String) Mod.staticCall(PG, "hudLineNow"));
            c.check(alive != null && alive.contains("alive"), "HUD with no spawn line: " + alive);

            // Behind a wall: the legit box stops, the tracer keeps drawing.
            String wall = String.format(java.util.Locale.ROOT, "%d %d %d %d %d %d", bx - 4, by, bz - 6, bx + 4, by + 6, bz - 6);
            c.server().command("fill " + wall + " minecraft:stone");
            c.waitUntil("the ghast out of sight", mc -> !(Boolean) Mod.staticCall(PG, "visibleNow"), 60);
            c.ctx().waitTicks(4);
            box0 = intCall(c, "boxFrames");
            tr0 = intCall(c, "tracerFrames");
            c.ctx().waitTicks(20);
            c.check(intCall(c, "boxFrames") == box0, "the legit box drew through a wall");
            c.check(intCall(c, "tracerFrames") > tr0, "the tracer stopped behind a wall");
            // Through Walls: only a cheat jar draws it.
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setPowderGhastEsp", true));
            c.ctx().waitTicks(10);
            boolean walls = c.onClient(mc -> (Boolean) Mod.staticCall(PG, "throughWallsNow"));
            c.check(walls == Mod.isCheat(), "Through Walls is " + walls + " on a " + (Mod.isCheat() ? "cheat" : "legit") + " jar");
            box0 = intCall(c, "boxFrames");
            c.ctx().waitTicks(20);
            int drawn = intCall(c, "boxFrames") - box0;
            c.check(Mod.isCheat() ? drawn > 0 : drawn == 0, "box frames behind the wall with Through Walls: " + drawn);
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(CFG), "setPowderGhastEsp", false));
            c.server().command("fill " + wall + " minecraft:air");

            // A hit resets the minute.
            c.hx().chat(SPAWN);
            c.waitUntil("the timer runs", mc -> ((Number) Mod.staticCall(PG, "secondsLeft")).longValue() >= 58, 20);
            c.ctx().waitTicks(70);
            long before = left(c);
            c.server().command("damage @e[tag=k1103pg,limit=1] 1 minecraft:generic");
            c.waitUntil("the hit resets the minute", mc -> ((Number) Mod.staticCall(PG, "secondsLeft")).longValue() >= 59, 40);
            c.note("timer " + before + " s before the hit, " + left(c) + " s after");

            // The switch OFF: nothing drawn.
            setOn(c, false);
            c.ctx().waitTicks(4);
            box0 = intCall(c, "boxFrames");
            tr0 = intCall(c, "tracerFrames");
            c.ctx().waitTicks(20);
            c.check(intCall(c, "targetId") == -1, "a target with the switch OFF");
            c.check(intCall(c, "boxFrames") == box0 && intCall(c, "tracerFrames") == tr0, "drew with the switch OFF");
            setOn(c, true);
            c.waitUntil("picked again", mc -> ((Number) Mod.staticCall(PG, "targetId")).intValue() == ghast, 40);

            // Killed: dropped.
            c.server().command("kill @e[tag=k1103pg]");
            c.waitUntil("a killed ghast is dropped", mc -> ((Number) Mod.staticCall(PG, "targetId")).intValue() == -1, 60);
            c.check(c.events("container.click").isEmpty(), "Powder Ghast clicked something");
        } finally {
            c.server().command("kill @e[tag=k1103pg]");
            c.server().command(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air",
                    bx - 4, by, bz - 6, bx + 4, by + 6, bz - 6));
            c.ctx().runOnClient(mc -> Mod.staticCall(CFG, "load"));
            hub(c);
        }
    }

    /** HUD ids as the mod names them. */
    private static final class PowderGhastIds {
        static final String TIMER = "mining_powder_ghast";
    }
}

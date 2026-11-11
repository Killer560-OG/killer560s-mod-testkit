package dev.testkit.gametest.hx;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.FrameClock;
import dev.testkit.harness.PacketTrace;

import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.hx.CarnivalSuite.GX;
import static dev.testkit.gametest.hx.CarnivalSuite.GZ;
import static dev.testkit.gametest.hx.CarnivalSuite.PX;
import static dev.testkit.gametest.hx.CarnivalSuite.PZ;
import static dev.testkit.gametest.hx.CarnivalSuite.Y;

/**
 * carnivalfold-1111 cases, in {@code 3780-carnival-session}:
 * <ul>
 *   <li>3793 Auto Accept Game (cheat): after a game NPC's "Select an option:" exactly ONE command goes out, the FIRST
 *       option's; no other option, no Carnival Leader.</li>
 *   <li>3794 the shovel's mode from its real item data (iron_shovel with an item_model per mode) and the guard's
 *       "held: X, need: Y" text.</li>
 *   <li>3795 Fruit Digging readability: the shovel line names the mode and how to get there; one label by default.</li>
 *   <li>3796 Strategy: each setting solves and the pick follows the mode with the best expected value.</li>
 *   <li>3797 Zombie Shootout: one timer per zombie (Hypixel's own tag hides ours).</li>
 *   <li>3798 Auto Zombie Shootout (cheat): a lamp under the player and a lit lamp past its time are never shot at;
 *       it carries on to the zombie.</li>
 *   <li>3799 Catch a Fish (cheat): a flung fish is dropped and never aimed at.</li>
 *   <li>3800 Auto Catch a Fish (cheat): walks to the pond's edge on Sneak + Forward key presses, never freezing.</li>
 *   <li>3801 Auto Fruit Digging (cheat): holds the dig until the block breaks, its own swap comes first.</li>
 * </ul>
 */
final class Carnival1111Cases {

    private static final String MODEL = "hypixel_skyblock:item/uncategorized/carnival_shovel_";

    private Carnival1111Cases() {
    }

    static void register(Session s) {
        s.test("3793-carnival-accept-game", Carnival1111Cases::acceptGame);
        s.test("3794-carnival-shovel-model", Carnival1111Cases::shovelModel);
        s.test("3795-carnival-fruit-readable", Carnival1111Cases::readable);
        s.test("3796-carnival-fruit-strategy", Carnival1111Cases::strategy);
        s.test("3797-carnival-zombie-one-timer", Carnival1111Cases::oneTimer);
        s.test("3798-carnival-auto-shootout-skips", Carnival1111Cases::shootoutSkips);
        s.test("3799-carnival-fish-flung", Carnival1111Cases::fishFlung);
        s.test("3800-carnival-auto-fish-walk", Carnival1111Cases::fishWalk);
        s.test("3801-carnival-autodig-hold", Carnival1111Cases::autoDigHold);
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** The real Carnival Shovel: always an iron_shovel, the mode in the item model. */
    static String shovel(String mode) {
        return "minecraft:iron_shovel[custom_name='Carnival Shovel',item_model=\"" + MODEL + mode + "\"]";
    }

    /** Slots 0/1/2: Treasure (gold model), Mines (iron), Anchor (diamond). */
    static void realShovels(Session c) {
        c.hx().give(0, shovel("gold"));
        c.hx().give(1, shovel("iron"));
        c.hx().give(2, shovel("diamond"));
    }

    private static void line(Session c, String json) {
        c.hx().call("chat", "textJson", com.google.gson.JsonParser.parseString(json), "overlay", false);
        c.ctx().waitTicks(2);
    }

    private static String option(String text, String cmd, String color) {
        return "{\"text\":\"" + text + "\",\"color\":\"" + color + "\",\"click_event\":{\"action\":\"run_command\","
                + "\"command\":\"" + cmd + "\"}}";
    }

    private static List<String> npcCommands(Session c) {
        return HxKit.commandsStarting(c, "selectnpcoption");
    }

    private static void npc(Session c, String who, String text) {
        c.hx().chat("§e[NPC] " + who + "§f: " + text);
        c.ctx().waitTicks(2);
    }

    // ---- 3793 -----------------------------------------------------------------------------------------------------

    static void acceptGame(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Accept Game is compiled out");
            return;
        }
        try (HxKit.Settings s = CarnivalSuite.base(c)) {
            CarnivalSuite.on(s, "carnival.AutoAcceptGameConfig", "getInstance");
            c.hx().stub(null, "selectnpcoption");
            int before = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoAcceptGame", "sent"));
            // 1) Pirateman, the options on their own lines (how Hypixel shows them).
            npc(c, "Carnival Pirateman", "Would ye like to do some Fruit Digging?");
            c.hx().chat("§eSelect an option:");
            c.ctx().waitTicks(2);
            line(c, option("[Aye sure do!]", "/selectnpcoption pirate aye", "green"));
            line(c, option("[Could ye tell me the rules again, Captain?]", "/selectnpcoption pirate rules", "green"));
            line(c, option("[I'll set me sights elsewhere.]", "/selectnpcoption pirate leave", "red"));
            c.ctx().waitTicks(8);
            List<String> sent1 = new ArrayList<>(npcCommands(c));
            c.note("pirateman: " + sent1);
            c.check(sent1.size() == 1 && sent1.get(0).equals("selectnpcoption pirate aye"),
                    "Pirateman: want exactly /selectnpcoption pirate aye, the server saw " + sent1);
            // 2) Cowboy, the options inline after the prompt.
            line(c, "{\"text\":\"[NPC] Carnival Cowboy: Fancy a Zombie Shootout?\"}");
            line(c, "{\"text\":\"Select an option: \",\"color\":\"yellow\",\"extra\":[" + option("[Yee-haw!]",
                    "/selectnpcoption cowboy yes", "green") + "," + option("[Rules?]", "/selectnpcoption cowboy rules",
                    "green") + "]}");
            c.ctx().waitTicks(8);
            List<String> sent2 = new ArrayList<>(npcCommands(c));
            sent2.removeAll(sent1);
            c.note("cowboy: " + sent2);
            c.check(sent2.size() == 1 && sent2.get(0).equals("selectnpcoption cowboy yes"),
                    "Cowboy (inline): want exactly the first option, the server saw " + sent2);
            int mid = npcCommands(c).size();
            // 3) The Carnival Leader and any other NPC: nothing.
            npc(c, "Carnival Leader", "Welcome to the Carnival!");
            c.hx().chat("§eSelect an option:");
            line(c, option("[Show me the games]", "/selectnpcoption leader games", "green"));
            npc(c, "Dalbrek", "Want a quest?");
            c.hx().chat("§eSelect an option:");
            line(c, option("[Sure]", "/selectnpcoption dalbrek sure", "green"));
            c.ctx().waitTicks(8);
            c.check(npcCommands(c).size() == mid, "a command went out for the Carnival Leader or another NPC: " + npcCommands(c));
            // 4) The mod's own count agrees: two accepts, the last one the Cowboy's.
            int after = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoAcceptGame", "sent"));
            String last = c.onClient(mc -> (String) Mod.staticCall("carnival.AutoAcceptGame", "lastCommand"));
            c.check(after - before == 2 && "selectnpcoption cowboy yes".equals(last),
                    "the mod counted " + (after - before) + " accept(s), last " + last);
        } finally {
            CarnivalSuite.cleanup(c);
        }
    }

    // ---- 3794 -----------------------------------------------------------------------------------------------------

    static void shovelModel(Session c) {
        try (HxKit.Settings s = CarnivalSuite.base(c)) {
            CarnivalSuite.on(s, "carnival.FruitDiggingConfig", "getInstance");
            realShovels(c);
            String[] want = {"Treasure", "Mines", "Anchor"};
            for (int slot = 0; slot < 3; slot++) {
                CarnivalSuite.holdSlot(c, slot);
                final int sl = slot;
                Object mode = c.onClient(mc -> Mod.staticCall("carnival.AutoShovelSwap", "modeOf",
                        mc.player.getInventory().getItem(sl)));
                String base = c.onClient(mc -> net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(mc.player.getInventory().getItem(sl).getItem()).getPath());
                c.note("slot " + slot + ": " + base + " reads as " + mode);
                c.check("iron_shovel".equals(base), "the test item is not an iron_shovel: " + base);
                c.check(want[slot].equals(mode), "an iron_shovel with the " + want[slot] + " model reads as " + mode);
            }
            // The guard: holding Mines when the pick wants another mode reports "held: Mines, need: X".
            CarnivalSuite.stand(c, PX, PZ, 0f, 0f);
            CarnivalSuite.startFruit(c);
            c.ctx().waitTicks(10);
            String[] p = Carnival1110Cases.pick(c);
            c.check(p != null, "no pick");
            int right = Carnival1110Cases.slotFor(p[1]);
            int wrong = (right + 1) % 3;
            c.onClient(mc -> Mod.call(Mod.staticCall("carnival.FruitDiggingConfig", "getInstance"), "setPreventWrongShovel", true));
            CarnivalSuite.holdSlot(c, wrong);
            Carnival1110Cases.aimAt(c, p[0], false);
            long mark = LogTap.mark();
            int r0 = c.onClient(mc -> (Integer) Mod.staticCall("carnival.FruitDigging", "refusals"));
            Carnival1110Cases.click(c);
            int r1 = c.onClient(mc -> (Integer) Mod.staticCall("carnival.FruitDigging", "refusals"));
            String need = p[1];
            String held = want[wrong];
            List<String> msg = HxKit.logLines(mark, "held: " + held + ", need: " + need);
            c.note("refusals +" + (r1 - r0) + "; guard line " + msg);
            c.check(r1 > r0, "the wrong model was not refused");
            c.check(!msg.isEmpty(), "no 'held: " + held + ", need: " + need + "' message");
            // The right model digs.
            CarnivalSuite.holdSlot(c, right);
            long t0 = c.hx().head();
            Carnival1110Cases.click(c);
            c.check(!Carnival1110Cases.digsAt(c, t0, p[0]).isEmpty(), "the right model did not dig the pick");
        } finally {
            Carnival1110Cases.finish(c);
        }
    }

    // ---- 3795 -----------------------------------------------------------------------------------------------------

    static void readable(Session c) {
        try (HxKit.Settings s = CarnivalSuite.base(c)) {
            CarnivalSuite.on(s, "carnival.FruitDiggingConfig", "getInstance");
            realShovels(c);
            CarnivalSuite.holdSlot(c, 0);
            CarnivalSuite.stand(c, PX, PZ, 0f, 0f);
            CarnivalSuite.startFruit(c);
            c.ctx().waitTicks(10);
            String[] p = Carnival1110Cases.pick(c);
            c.check(p != null, "no pick");
            List<String> hud = CarnivalSuite.hud(c, "carnival.FruitDigging");
            c.note("HUD " + hud);
            // Holding Treasure: the line says which mode is wanted (unless it is Treasure) and how to get there.
            boolean wantsOther = !"Treasure".equals(p[1]);
            if (wantsOther) {
                c.check(CarnivalSuite.has(hud, "Switch shovel mode to " + p[1] + ": right click (now Treasure)"),
                        "the shovel line does not name the mode and how to switch: " + hud);
            } else {
                c.check(CarnivalSuite.has(hud, "Treasure shovel: held"), "the shovel line is wrong: " + hud);
            }
            CarnivalSuite.holdSlot(c, Carnival1110Cases.slotFor(p[1]));
            c.ctx().waitTicks(5);
            hud = CarnivalSuite.hud(c, "carnival.FruitDigging");
            c.check(CarnivalSuite.has(hud, p[1] + " shovel: held"), "no '" + p[1] + " shovel: held' line: " + hud);
            c.check((Boolean) c.onClient(mc -> Mod.call(Mod.staticCall("carnival.FruitDiggingConfig", "getInstance"),
                    "isAllPercents")) == Boolean.FALSE, "percentages on every square default to ON");
            // One label by default: the recommended tile's, in every frame.
            int max = 0;
            int min = 99;
            for (int i = 0; i < 20; i++) {
                int n = c.onClient(mc -> (Integer) Mod.staticCall("carnival.FruitDigging", "labelsLastFrame"));
                max = Math.max(max, n);
                min = Math.min(min, n);
                c.ctx().waitTicks(1);
            }
            c.note("labels per frame: " + min + ".." + max);
            c.check(max == 1 && min == 1, "by default exactly one label draws a frame (the recommended tile's), got " + min + ".." + max);
            // With the setting on there are labels on the other tiles too.
            c.onClient(mc -> Mod.call(Mod.staticCall("carnival.FruitDiggingConfig", "getInstance"), "setAllPercents", true));
            c.ctx().waitTicks(10);
            int many = c.onClient(mc -> (Integer) Mod.staticCall("carnival.FruitDigging", "labelsLastFrame"));
            c.note("with every square: " + many);
            c.check(many > 5, "Percent On Every Square drew only " + many + " label(s)");
        } finally {
            c.onClient(mc -> Mod.call(Mod.staticCall("carnival.FruitDiggingConfig", "getInstance"), "setAllPercents", false));
            Carnival1110Cases.finish(c);
        }
    }

    // ---- 3796 -----------------------------------------------------------------------------------------------------

    static void strategy(Session c) {
        try (HxKit.Settings s = CarnivalSuite.base(c)) {
            CarnivalSuite.on(s, "carnival.FruitDiggingConfig", "getInstance");
            realShovels(c);
            CarnivalSuite.stand(c, PX, PZ, 0f, 0f);
            CarnivalSuite.startFruit(c);
            Object cfg = c.onClient(mc -> Mod.staticCall("carnival.FruitDiggingConfig", "getInstance"));
            int was = (Integer) c.onClient(mc -> Mod.call(cfg, "getStrategy"));
            c.check(was == 0, "the Strategy default is " + was + ", want 0 (Consistent)");
            String[] names = {"Consistent", "Leaderboard", "Daily Quest"};
            try {
                for (int st = 0; st < 3; st++) {
                    final int v = st;
                    int solves0 = c.onClient(mc -> (Integer) Mod.staticCall("carnival.FruitDigging", "solveCount"));
                    c.onClient(mc -> Mod.call(cfg, "setStrategy", v));
                    c.waitUntil("a new solve for " + names[st], mc -> (Integer) Mod.staticCall("carnival.FruitDigging",
                            "solveCount") > solves0, 80);
                    double[] ev = c.onClient(mc -> {
                        Object rec = Mod.field(Mod.ROOT + "carnival.FruitDigging", "recommendation");
                        return rec == null ? null : (double[]) Mod.call(rec, "modeEv");
                    });
                    String shovel = c.onClient(mc -> {
                        Object rec = Mod.field(Mod.ROOT + "carnival.FruitDigging", "recommendation");
                        return rec == null ? null : (String) Mod.call(rec, "shovel");
                    });
                    c.note(names[st] + ": shovel " + shovel + ", mode EV " + java.util.Arrays.toString(ev));
                    c.check(shovel != null && ev != null && ev.length == 3, names[st] + " produced no recommendation");
                    if (ev != null && shovel != null && st == 0) {
                        int best = 0;
                        for (int i = 1; i < 3; i++) {
                            best = ev[i] > ev[best] ? i : best;
                        }
                        c.check(new String[]{"Mines", "Anchor", "Treasure"}[best].equals(shovel),
                                "Consistent recommends " + shovel + " but the best expected value is mode " + best + " " + java.util.Arrays.toString(ev));
                    }
                }
            } finally {
                c.onClient(mc -> Mod.call(cfg, "setStrategy", was));
            }
        } finally {
            Carnival1110Cases.finish(c);
        }
    }

    // ---- 3797 -----------------------------------------------------------------------------------------------------

    static void oneTimer(Session c) {
        try (HxKit.Settings s = CarnivalSuite.base(c)) {
            CarnivalSuite.on(s, "carnival.ZombieShootoutConfig", "getInstance");
            CarnivalSuite.stand(c, PX, PZ, 0f, 0f);
            CarnivalSuite.startShootout(c, false);
            double ax = PX - 4;
            double bx = PX + 4;
            double z = PZ + 12;
            c.server().command(CarnivalSuite.zombie(ax, Y + 1, z, "iron_helmet", "za"));
            c.server().command(CarnivalSuite.zombie(bx, Y + 1, z, "gold_helmet", "zb"));
            c.hx().stand(ax, Y + 3.4, z, "§a4.2s", "marker", true, "invisible", true, "nameVisible", true);
            c.waitUntil("two zombies are targets", mc -> (Integer) Mod.staticCall("carnival.ZombieShootout", "targetCount") == 2, 100);
            c.ctx().waitTicks(5);
            int[] t = c.onClient(mc -> (int[]) Mod.staticCall("carnival.ZombieShootout", "timerSources"));
            c.note("timers: Hypixel's tag " + t[0] + ", ours " + t[1]);
            c.check(t[0] == 1 && t[1] == 1, "two zombies, one with Hypixel's own timer: want theirs 1 + ours 1 (one timer each), got "
                    + t[0] + " + " + t[1]);
            c.hx().removeStands();
            c.ctx().waitTicks(8);
            t = c.onClient(mc -> (int[]) Mod.staticCall("carnival.ZombieShootout", "timerSources"));
            c.check(t[0] == 0 && t[1] == 2, "with no Hypixel tag both zombies get ours: " + t[0] + " + " + t[1]);
        } finally {
            CarnivalSuite.cleanup(c);
        }
    }

    // ---- 3798 -----------------------------------------------------------------------------------------------------

    static void shootoutSkips(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Zombie Shootout is cheat-only");
            return;
        }
        try (HxKit.Settings s = CarnivalSuite.base(c)) {
            CarnivalSuite.on(s, "carnival.ZombieShootoutConfig", "getInstance");
            CarnivalSuite.on(s, "carnival.ShootoutShotConfig", "auto");
            c.hx().give(0, "minecraft:carrot_on_a_stick[custom_name='Carnival Dart Tube']");
            CarnivalSuite.holdSlot(c, 0);
            // A lit lamp right under his feet (a redstone block below keeps it lit), before the game starts so it is scanned.
            int fx = (int) Math.floor(PX);
            int fz = (int) Math.floor(PZ);
            c.server().command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:redstone_block", fx, Y - 1, fz));
            c.server().command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:redstone_lamp[lit=true]", fx, Y, fz));
            c.ctx().waitTicks(3);
            CarnivalSuite.stand(c, PX, PZ, 0f, 0f);
            CarnivalSuite.startShootout(c, true);
            c.server().command(CarnivalSuite.zombie(PX + 9, Y + 1, PZ + 11, "iron_helmet", "zi"));
            c.waitUntil("the zombie and lamps are targets",
                    mc -> (Integer) Mod.staticCall("carnival.ZombieShootout", "targetCount") >= 2, 100);
            int shots0 = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoZombieShootout", "shots"));
            float steepest = 0f;
            // Over the first six seconds the lamps are fresh targets: the far lamp may be shot, the one under him never.
            for (int i = 0; i < 24; i++) {
                float pitch = c.onClient(mc -> mc.player.getXRot());
                steepest = Math.max(steepest, pitch);
                c.ctx().waitTicks(5);
            }
            c.note("steepest pitch in the first 6 s: " + steepest);
            c.check(steepest < 70f, "the auto looked down at the lamp under his feet (pitch " + steepest + ")");
            // Past their 6 s the lit lamps read 0.0s: they are dropped and the auto carries on with the zombie.
            c.waitUntil("the stale lamps are dropped", mc -> (Integer) Mod.staticCall("carnival.ZombieShootout",
                    "targetCount") == 1 && !"Lamp".equals(Mod.staticCall("carnival.ZombieShootout", "bestLabel")), 120);
            long t0 = c.hx().head();
            c.ctx().waitTicks(70);
            long shots = CarnivalSuite.uses(c, t0, "carrot_on_a_stick");
            int shots1 = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoZombieShootout", "shots"));
            c.note("after the lamps went stale: " + shots + " shot(s) at the server; mod shots " + (shots1 - shots0));
            c.check(shots >= 1, "the auto froze on the stale lamp: no shot at the zombie after it was dropped");
        } finally {
            c.server().command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:sand", (int) Math.floor(PX), Y - 1,
                    (int) Math.floor(PZ)));
            c.server().command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:sand", (int) Math.floor(PX), Y,
                    (int) Math.floor(PZ)));
            CarnivalSuite.cleanup(c);
        }
    }

    // ---- 3799 -----------------------------------------------------------------------------------------------------

    static void fishFlung(Session c) {
        try (HxKit.Settings s = CarnivalSuite.base(c)) {
            CarnivalSuite.on(s, "carnival.CatchAFishConfig", "getInstance");
            boolean cheat = Mod.isCheat();
            if (cheat) {
                CarnivalSuite.on(s, "carnival.AutoCatchAFishConfig", "getInstance");
            }
            c.hx().give(0, "minecraft:fishing_rod[custom_name='Carnival Rod']");
            CarnivalSuite.holdSlot(c, 0);
            CarnivalSuite.stand(c, PX, PZ, 90f, 0f);
            CarnivalSuite.startFish(c);
            c.onClient(mc -> {
                Mod.staticCall("carnival.CatchAFish", "forgetFlung");
                return null;
            });
            String yellow = c.hx().stand(PX + 2, Y + 1, PZ + 6, "fishy", "helmet", CarnivalSuite.head(CarnivalSuite.YELLOW),
                    "marker", false, "invisible", false);
            c.waitUntil("the fish is seen", mc -> (Integer) Mod.staticCall("carnival.CatchAFish", "fishCount") == 1, 60);
            // The server flings it two blocks up (a hooked or thrown fish leaves the water).
            c.server().command("tp @e[type=armor_stand,limit=1] " + (PX + 2) + " " + (Y + 3.2) + " " + (PZ + 6));
            c.waitUntil("the flung fish is dropped", mc -> (Integer) Mod.staticCall("carnival.CatchAFish", "fishCount") == 0, 40);
            c.check(c.onClient(mc -> Mod.staticCall("carnival.CatchAFish", "bestYellow")) == null,
                    "a flung fish is still the best target");
            // Back in the water it stays dropped.
            c.server().command("tp @e[type=armor_stand,limit=1] " + (PX + 2) + " " + (Y + 1) + " " + (PZ + 6));
            c.ctx().waitTicks(15);
            c.check((Integer) c.onClient(mc -> Mod.staticCall("carnival.CatchAFish", "fishCount")) == 0,
                    "a fish seen flung came back as a target");
            if (cheat) {
                long t0 = c.hx().head();
                int casts0 = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoCatchAFish", "casts"));
                c.ctx().waitTicks(60);
                int casts1 = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoCatchAFish", "casts"));
                long uses = CarnivalSuite.uses(c, t0, "fishing_rod");
                c.note("auto casts " + (casts1 - casts0) + ", rod uses " + uses + " after the fling");
                c.check(casts1 == casts0 && uses == 0, "the auto aimed or cast at a flung fish (casts +" + (casts1 - casts0)
                        + ", rod uses " + uses + ")");
            }
        } finally {
            c.onClient(mc -> {
                mc.options.keyUse.setDown(false);
                return null;
            });
            CarnivalSuite.cleanup(c);
        }
    }

    // ---- 3800 -----------------------------------------------------------------------------------------------------

    static void fishWalk(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Catch a Fish is cheat-only");
            return;
        }
        List<String> inputs = Collections.synchronizedList(new ArrayList<>());
        try (HxKit.Settings s = CarnivalSuite.base(c)) {
            CarnivalSuite.on(s, "carnival.CatchAFishConfig", "getInstance");
            CarnivalSuite.on(s, "carnival.AutoCatchAFishConfig", "getInstance");
            c.hx().give(0, "minecraft:fishing_rod[custom_name='Carnival Rod']");
            CarnivalSuite.holdSlot(c, 0);
            CarnivalSuite.stand(c, PX, PZ, 0f, 0f);
            // A wall that hides the pond: not all fish are in view, so it walks forward.
            int wz = (int) Math.floor(PZ) + 5;
            c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", (int) PX - 6, Y + 1, wz,
                    (int) PX + 6, Y + 4, wz));
            c.ctx().waitTicks(4);
            CarnivalSuite.startFish(c);
            c.hx().stand(PX, Y + 1, PZ + 12, "fishy", "helmet", CarnivalSuite.head(CarnivalSuite.OTHER), "marker", false,
                    "invisible", false);
            c.waitUntil("the fish is seen", mc -> (Integer) Mod.staticCall("carnival.CatchAFish", "fishCount") == 1, 80);
            double z0 = c.onClient(mc -> mc.player.getZ());
            PacketTrace.tap = p -> {
                if (p instanceof ServerboundPlayerInputPacket in) {
                    inputs.add(in.input().forward() + "/" + in.input().shift());
                }
            };
            c.waitUntil("it starts walking", mc -> (Integer) Mod.staticCall("carnival.AutoCatchAFish", "walks") >= 1, 120);
            c.ctx().waitTicks(20);
            double z1 = c.onClient(mc -> mc.player.getZ());
            boolean keys = inputs.contains("true/true");
            c.note("walked " + (z1 - z0) + " blocks; input packets " + new java.util.LinkedHashSet<>(inputs));
            c.check(keys, "no input packet with Forward and Sneak both held: " + new java.util.LinkedHashSet<>(inputs));
            c.check(z1 - z0 > 0.3, "it did not move forward (" + (z1 - z0) + ")");
            // It never freezes: the walk ends (the wall stops him) within its tick cap, the keys are handed back.
            c.waitUntil("the walk ends", mc -> !(Boolean) Mod.staticCall("carnival.AutoCatchAFish", "walking"), 200);
            boolean fwd = c.onClient(mc -> mc.options.keyUp.isDown());
            c.check(!fwd, "Forward is still held after the walk ended");
        } finally {
            PacketTrace.tap = null;
            c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", (int) PX - 6, Y + 1,
                    (int) Math.floor(PZ) + 5, (int) PX + 6, Y + 4, (int) Math.floor(PZ) + 5));
            c.onClient(mc -> {
                mc.options.keyUp.setDown(false);
                mc.options.keyShift.setDown(false);
                return null;
            });
            CarnivalSuite.cleanup(c);
        }
    }

    // ---- 3801 -----------------------------------------------------------------------------------------------------

    static void autoDigHold(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Fruit Digging is cheat-only");
            return;
        }
        List<String> wire = Collections.synchronizedList(new ArrayList<>());
        String mode0 = c.onClient(mc -> mc.gameMode.getPlayerMode().getName());
        try (HxKit.Settings s = CarnivalSuite.base(c)) {
            CarnivalSuite.on(s, "carnival.FruitDiggingConfig", "getInstance");
            // The swap is part of Auto Fruit Digging: only its own switch is on, not Auto Shovel Swap.
            CarnivalSuite.on(s, "carnival.AutoFruitConfig", "dig");
            c.server().command("gamemode survival @p");
            c.server().command("attribute @p minecraft:block_break_speed base set 0.1");
            realShovels(c);
            CarnivalSuite.holdSlot(c, 0);
            CarnivalSuite.stand(c, PX, PZ, 0f, 0f);
            PacketTrace.tap = p -> {
                long t = FrameClock.tickCount();
                if (p instanceof ServerboundSetCarriedItemPacket sw) {
                    wire.add(t + " swap " + sw.getSlot());
                } else if (p instanceof ServerboundPlayerActionPacket a && a.getAction().name().endsWith("DESTROY_BLOCK")) {
                    wire.add(t + " " + a.getAction().name().replace("_DESTROY_BLOCK", "").toLowerCase(Locale.ROOT) + " "
                            + a.getPos().getX() + "," + a.getPos().getZ());
                }
            };
            CarnivalSuite.startFruit(c);
            c.waitUntil("a dig tap", mc -> (Integer) Mod.staticCall("carnival.AutoFruitDigging", "digTaps") >= 1, 200);
            String target = c.onClient(mc -> (String) Mod.staticCall("carnival.AutoFruitDigging", "lastTarget"));
            String label = target.split("/")[0];
            String mode = target.split("/")[1];
            int tx = GX + (label.charAt(1) - '1');
            int tz = GZ + (label.charAt(0) - 'A');
            c.waitUntil("the block breaks under the held dig", mc -> mc.level.getBlockState(new net.minecraft.core.BlockPos(tx, Y, tz))
                    .isAir(), 260);
            c.ctx().waitTicks(5);
            List<String> w = new ArrayList<>(wire);
            c.note("dug " + target + "; wire " + w);
            String tile = tx + "," + tz;
            int firstStart = -1;
            int firstSwap = -1;
            int stop = -1;
            int abortBefore = 0;
            for (int i = 0; i < w.size(); i++) {
                String e = w.get(i);
                if (firstSwap < 0 && e.contains(" swap ")) {
                    firstSwap = i;
                }
                if (e.endsWith(" start " + tile) && firstStart < 0) {
                    firstStart = i;
                }
                if (firstStart >= 0 && stop < 0 && e.endsWith(" stop " + tile)) {
                    stop = i;
                }
                if (firstStart >= 0 && stop < 0 && e.endsWith(" abort " + tile)) {
                    abortBefore++;
                }
            }
            c.check(firstStart >= 0, "no dig START on " + tile);
            c.check(stop > firstStart, "the dig never finished (no STOP on " + tile + "): it tapped instead of holding");
            c.check(abortBefore == 0, "the dig was aborted " + abortBefore + " time(s) before it broke the block");
            // The swap: the held slot at the start is the pick's mode; when it was not Treasure (slot 0) a swap came first,
            // in a tick of its own.
            int wantSlot = "Mines".equals(mode) ? 1 : "Anchor".equals(mode) ? 2 : 0;
            if (wantSlot != 0) {
                c.check(firstSwap >= 0 && firstSwap < firstStart, "no swap to " + mode + " before the dig: " + w);
                if (firstSwap >= 0 && firstStart >= 0) {
                    String swapTick = w.get(firstSwap).split(" ")[0];
                    String startTick = w.get(firstStart).split(" ")[0];
                    c.check(!swapTick.equals(startTick), "the swap and the dig went in the same tick " + swapTick);
                }
            } else {
                c.note("the pick wanted Treasure (already held): no swap needed");
            }
            int held = c.onClient(mc -> mc.player.getInventory().getSelectedSlot());
            c.check(held == wantSlot, "holding slot " + held + " for " + mode + " (want " + wantSlot + ")");
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
}

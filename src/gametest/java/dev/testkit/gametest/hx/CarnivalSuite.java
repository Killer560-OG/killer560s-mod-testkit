package dev.testkit.gametest.hx;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.TestMap;
import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Carnival features (mod branch staging-carnival-3, 2026-10-25; kept for after 2.0) on the real dedicated server with
 * GrimAC: one session, {@code 3780-carnival-session}, its own platform at (-620, 150, -620) with a 7x7 sand Fruit
 * Digging grid set into it (stone under it so it cannot fall), and the Carnival made the way Hypixel shows it - the
 * sidebar's area and game lines, the NPC and clue chat lines, the reveal tags and fish as armor stands, the zombies as
 * real zombies in armor, a lit Redstone Lamp. Every case proves the feature acted (its state, its HUD lines, the packets
 * the server received) and every case must be anticheat-clean.
 * <ul>
 *   <li>3781 Carnival Tracker: area, game, tokens and stats from the sidebar; a forged token line ignored; the game's end
 *       counted with its best score; Chantelle's goals menu read into Goals Left.</li>
 *   <li>3782 Fruit Digging Solver: the grid found, a pick made, a dig (sandstone + reveal tag + Mines clue) read onto the
 *       right tile, a forged clue ignored, the pick moving on.</li>
 *   <li>3783 Zombie Shootout Helper: zombies classed by armor, the lit lamp picked first, then the diamond zombie.</li>
 *   <li>3784 Catch a Fish Helper: the Yellow Fish told from another fish by its head and picked.</li>
 *   <li>3785 Zombie Shootout Triggerbot (cheat): no shot while the crosshair is off the zombie, shots once it is on.</li>
 *   <li>3786 Auto Zombie Shootout (cheat): turns to an off-axis zombie and shoots it.</li>
 *   <li>3787 Auto Shovel Swap + Auto Fruit Digging (cheat): selects the shovel in the solver's mode, then aims at and
 *       digs the solver's tile; after the dig lands, the next dig goes to another tile.</li>
 *   <li>3788 Auto Catch a Fish (cheat): turns to the fish, casts, and holds Use while the fish is on the hook.</li>
 * </ul>
 * On a legit jar the cheat cases check nothing was sent.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class CarnivalSuite implements FabricClientGameTest {

    public static final String SESSION = "3780-carnival-session";
    static final int CX = -620;
    static final int Y = 150;
    static final int CZ = -620;
    /** The grid's corner (row A, column 1): the 7x7 patch centered on the player's block. */
    static final int GX = CX - 3;
    static final int GZ = CZ - 3;
    static final double PX = CX + 0.5;
    static final double PZ = CZ + 0.5;
    static final String YELLOW = "ewogICJ0aW1lc3RhbXAiIDogMTcyMDA1MjU4MjAwMSwKICAicHJvZmlsZUlkIiA6ICIzM2Y4ZGExMTU1MzQ0YWQ5OWQ0Y2Q2ZjNhYjFjMjNhYSIsCiAgInByb2ZpbGVOYW1lIiA6ICJCXzFfUl9CIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzM2MGY0Zjk5Yzc4YWRkZGVhYjI3NmViZGY2YWI5YTBmY2ZmYWJkNDlmMGI1NDNlMTk4MWFjN2JkM2Q1NWIxOGEiLAogICAgICAibWV0YWRhdGEiIDogewogICAgICAgICJtb2RlbCIgOiAic2xpbSIKICAgICAgfQogICAgfQogIH0KfQ==";
    static final String OTHER = Base64.getEncoder().encodeToString(("{\"textures\":{\"SKIN\":{\"url\":\"http://textures."
            + "minecraft.net/texture/1111111111111111111111111111111111111111111111111111111111111111\"}}}")
            .getBytes(StandardCharsets.UTF_8));

    @Override
    public void runTest(ClientGameTestContext ctx) {
        Session.run(ctx, SESSION,
                (server, s) -> TestMap.on(server)
                        .platform(CX, Y, CZ, 8)
                        .catchFloor(Y - 10)
                        .fill(GX, Y - 1, GZ, GX + 6, Y - 1, GZ + 6, "stone")
                        .survival()
                        .clearInventory()
                        .spawn(PX, PZ, 0f)
                        .build(),
                s -> {
                    ModUnderTest.require("killer560smod");
                    s.test("3781-carnival-tracker", CarnivalSuite::tracker);
                    s.test("3782-carnival-fruit-solver", CarnivalSuite::fruitSolver);
                    s.test("3783-carnival-zombie-helper", CarnivalSuite::zombieHelper);
                    s.test("3784-carnival-fish-helper", CarnivalSuite::fishHelper);
                    s.test("3785-carnival-triggerbot", CarnivalSuite::triggerbot);
                    s.test("3786-carnival-auto-shootout", CarnivalSuite::autoShootout);
                    s.test("3787-carnival-auto-dig", CarnivalSuite::autoDig);
                    s.test("3788-carnival-auto-catch", CarnivalSuite::autoCatch);
                    Carnival1110Cases.register(s);
                });
    }

    // ---- shared ---------------------------------------------------------------------------------------------------

    /** Skyblock Only off for the case, every Carnival switch put back after. */
    static HxKit.Settings base(Session c) {
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        return new HxKit.Settings(c)
                .custom(() -> Mod.staticCall("util.SkyblockGate", "setEnabled", false),
                        () -> Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas))
                .custom(() -> { }, () -> Mod.staticCall("carnival.CarnivalState", "forceGameForTest", (Object) null));
    }

    /** A switch on {@code cfg} (reached by its static {@code accessor}) for the case. */
    static void on(HxKit.Settings s, String cfg, String accessor) {
        s.custom(() -> Mod.call(Mod.staticCall(cfg, accessor), "setEnabled", true),
                () -> Mod.call(Mod.staticCall(cfg, accessor), "setEnabled", false));
    }

    static void sidebar(Session c, String... gameLines) {
        String[] lines = new String[gameLines.length + 4];
        lines[0] = "§707/25/26 §8m12AB";
        lines[1] = "§7⏣ §aCarnival";
        lines[2] = "§fCarnival Tokens: §e1,031";
        lines[3] = " ";
        System.arraycopy(gameLines, 0, lines, 4, gameLines.length);
        c.hx().sidebar("§e§lSKYBLOCK", lines);
    }

    static void stand(Session c, double x, double z, float yaw, float pitch) {
        c.server().command(String.format(Locale.ROOT, "tp @a %.2f %d %.2f %.1f %.1f", x, Y + 1, z, yaw, pitch));
        c.ctx().waitTicks(5);
        c.onClient(mc -> {
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
            return null;
        });
        c.ctx().waitTicks(3);
    }

    static void resetGrid(Session c) {
        c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:sand", GX, Y, GZ, GX + 6, Y, GZ + 6));
    }

    static List<String> hud(Session c, String cls) {
        @SuppressWarnings("unchecked")
        List<String> l = c.onClient(mc -> (List<String>) Mod.staticCall(cls, "hudLines"));
        return l;
    }

    static boolean has(List<String> lines, String text) {
        for (String l : lines) {
            if (l.replaceAll("§.", "").contains(text)) {
                return true;
            }
        }
        return false;
    }

    static int[] pos(JsonObject e) {
        JsonArray a = e.getAsJsonArray("pos");
        return new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()};
    }

    static void cleanup(Session c) {
        c.hx().removeStands();
        c.hx().sidebarClear();
        c.server().command("kill @e[tag=carn]");
        c.server().command("clear @a");
        resetGrid(c);
    }

    static String zombie(double x, double y, double z, String armor, String name) {
        return String.format(Locale.ROOT, "summon zombie %.2f %.2f %.2f {Tags:[\"carn\"],NoAI:1b,NoGravity:1b,Silent:1b,"
                + "Invulnerable:1b,PersistenceRequired:1b,IsBaby:0b,CustomName:\"%s\",equipment:{head:{id:\"minecraft:%s\","
                + "count:1}}}", x, y, z, name, armor);
    }

    /** Yaw and pitch from the player's eye at (PX, Y+1, PZ) to a point. */
    static float[] look(double x, double y, double z) {
        double ey = Y + 1 + 1.62;
        double dx = x - PX;
        double dy = y - ey;
        double dz = z - PZ;
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new float[]{yaw, pitch};
    }

    // ---- 3781 -----------------------------------------------------------------------------------------------------

    static void tracker(Session c) {
        try (HxKit.Settings s = base(c)) {
            on(s, "carnival.CarnivalTrackerConfig", "getInstance");
            stand(c, PX, PZ, 0f, 0f);
            int games0 = c.onClient(mc -> (Integer) Mod.staticCall("carnival.CarnivalTracker", "sessionGames"));
            long earned0 = c.onClient(mc -> (Long) Mod.staticCall("carnival.CarnivalTracker", "sessionEarned"));
            sidebar(c, "§c§lZombie Shootout", "§fScore: §e240", "§fKills: §a5", "§fAccuracy: §a80%", "§fTime Left: §a1:20");
            c.waitUntil("CarnivalState reads Zombie Shootout from the sidebar",
                    mc -> "ZOMBIE_SHOOTOUT".equals(String.valueOf(Mod.staticCall("carnival.CarnivalState", "game"))), 100);
            long tokens = c.onClient(mc -> (Long) Mod.staticCall("carnival.CarnivalState", "tokens"));
            boolean in = c.onClient(mc -> (Boolean) Mod.staticCall("carnival.CarnivalState", "inCarnival"));
            c.check(in && tokens == 1031, "in the Carnival " + in + ", tokens " + tokens + " (want true, 1031)");
            c.ctx().waitTicks(4);
            List<String> lines = hud(c, "carnival.CarnivalTracker");
            c.note("tracker HUD: " + lines);
            c.check(has(lines, "Zombie Shootout Score: 240") && has(lines, "Kills: 5") && has(lines, "Tokens: 1,031"),
                    "the tracker HUD does not show the game, kills and tokens: " + lines);
            c.hx().chat("§e[VIP] Bob§f: You earned 999 Carnival Tokens!");
            c.hx().chat("                    §eYou earned §617 Carnival Tokens§e!");
            c.ctx().waitTicks(5);
            long earned = c.onClient(mc -> (Long) Mod.staticCall("carnival.CarnivalTracker", "sessionEarned")) - earned0;
            c.check(earned == 17, "tokens earned this session read " + earned + " (want 17: the forged 999 must not count)");
            c.ctx().waitTicks(110); // a game counts once it ran 5 s
            c.hx().chat("                             §c§lZombie Shootout");
            sidebar(c);
            c.waitUntil("the game ends", mc -> "NONE".equals(String.valueOf(Mod.staticCall("carnival.CarnivalState", "game"))),
                    60);
            int games = c.onClient(mc -> (Integer) Mod.staticCall("carnival.CarnivalTracker", "sessionGames")) - games0;
            c.check(games == 1, "games this session went up by " + games + ", want 1");
            c.ctx().waitTicks(4);
            lines = hud(c, "carnival.CarnivalTracker");
            c.check(has(lines, "Best Zombie Shootout: ") , "no best Zombie Shootout score on the HUD: " + lines);

            Map<Integer, String> slots = new LinkedHashMap<>();
            slots.put(10, "minecraft:sand[lore=[\"Play 3 games of Fruit Digging.\",\"\",\"COMPLETE\"]]");
            slots.put(12, "minecraft:arrow[lore=[\"Shoot 60 Zombies in a single game of\",\"Zombie Shootout.\",\"\","
                    + "\"Progress: 12/60\"]]");
            c.hx().menu("Carnival Goals", 3, slots, null);
            c.waitUntil("the goals menu is read",
                    mc -> (Integer) Mod.staticCall("carnival.CarnivalTracker", "goalsReads") > 0, 60);
            c.onClient(mc -> {
                mc.player.closeContainer();
                return null;
            });
            c.ctx().waitTicks(4);
            lines = hud(c, "carnival.CarnivalTracker");
            c.note("tracker HUD after the goals menu: " + lines);
            c.check(has(lines, "Shoot 60 Zombies") && !has(lines, "Play 3 games"),
                    "Goals Left does not hold the unfinished goal only: " + lines);
        } finally {
            cleanup(c);
        }
    }

    // ---- 3782 -----------------------------------------------------------------------------------------------------

    static void startFruit(Session c) {
        resetGrid(c);
        sidebar(c, "§6§lFruit Digging", "§fFruits: §a0§7/§a34", "§fScore: §e0");
        c.hx().chat("§e[NPC] Carnival Pirateman§f: Good luck, matey!");
        c.waitUntil("the grid is found", mc -> Mod.staticCall("carnival.FruitDigging", "gridOrigin") != null, 100);
        c.waitUntil("a first pick", mc -> Mod.staticCall("carnival.FruitDigging", "bestLabel") != null, 100);
    }

    /** Digs {@code label}'s tile as the server would: sandstone, a reveal tag over it, a Mines clue. */
    static void serverDig(Session c, String label, String fruit, int mines) {
        int col = label.charAt(1) - '1';
        int row = label.charAt(0) - 'A';
        int x = GX + col;
        int z = GZ + row;
        c.server().command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:sandstone", x, Y, z));
        c.ctx().waitTicks(2);
        c.hx().stand(x + 0.5, Y + 1.2, z + 0.5, "§6" + fruit + " §e(+300)");
        c.hx().chat("§cMINES! §7There " + (mines == 1 ? "is 1 bomb" : "are " + mines + " bombs") + " hidden nearby.");
    }

    static void fruitSolver(Session c) {
        try (HxKit.Settings s = base(c)) {
            on(s, "carnival.FruitDiggingConfig", "getInstance");
            stand(c, PX, PZ, 0f, 0f);
            startFruit(c);
            Object originObj = c.onClient(mc -> Mod.staticCall("carnival.FruitDigging", "gridOrigin"));
            String origin = String.valueOf(originObj);
            String first = c.onClient(mc -> (String) Mod.staticCall("carnival.FruitDigging", "bestLabel"));
            int solves0 = c.onClient(mc -> (Integer) Mod.staticCall("carnival.FruitDigging", "solveCount"));
            c.note("grid at " + origin + ", first pick " + first);
            c.check(origin.contains(String.valueOf(GX)) && origin.contains(String.valueOf(GZ)),
                    "the grid was found at " + origin + ", want its corner at " + GX + ", " + Y + ", " + GZ);
            serverDig(c, first, "Mango", 0);
            c.hx().chat("§e[VIP] Bob§f: MINES! There are 3 bombs hidden nearby.");
            c.waitUntil("the dig is counted and its tag read", mc -> (Integer) Mod.staticCall("carnival.FruitDigging", "digs") == 1
                    && "Mango".equals(Mod.staticCall("carnival.FruitDigging", "contentAt", first)), 60);
            c.ctx().waitTicks(5);
            int mines = c.onClient(mc -> (Integer) Mod.staticCall("carnival.FruitDigging", "minesAt", first));
            c.check(mines == 0, "the Mines clue on " + first + " reads " + mines + " (want 0; the forged 3 must not land)");
            c.waitUntil("a new solve", mc -> (Integer) Mod.staticCall("carnival.FruitDigging", "solveCount") > solves0, 60);
            c.ctx().waitTicks(10);
            String next = c.onClient(mc -> (String) Mod.staticCall("carnival.FruitDigging", "bestLabel"));
            List<String> lines = hud(c, "carnival.FruitDigging");
            c.note("next pick " + next + "; HUD " + lines);
            c.check(next != null && !next.equals(first), "the pick did not move off the dug tile: " + next);
            c.check(has(lines, "Digs: 1/15") && has(lines, "Dig: " + next), "the solver HUD is wrong: " + lines);
        } finally {
            cleanup(c);
        }
    }

    // ---- 3783 -----------------------------------------------------------------------------------------------------

    static final int[] LAMP = {CX + 6, Y + 3, CZ + 16};

    static void startShootout(Session c, boolean lamp) {
        c.server().command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:redstone_lamp", LAMP[0], LAMP[1], LAMP[2]));
        // Lit by a redstone block on top (a lamp set lit with no power goes out on its next update).
        c.server().command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:%s", LAMP[0], LAMP[1] + 1, LAMP[2],
                lamp ? "redstone_block" : "air"));
        c.ctx().waitTicks(3);
        sidebar(c, "§c§lZombie Shootout", "§fScore: §e0", "§fKills: §a0", "§fAccuracy: §a0%");
        c.hx().chat("§e[NPC] Carnival Cowboy§f: Good luck, pal!");
        c.waitUntil("Zombie Shootout is on", mc -> "ZOMBIE_SHOOTOUT".equals(String.valueOf(
                Mod.staticCall("carnival.CarnivalState", "game"))), 60);
    }

    static void zombieHelper(Session c) {
        try (HxKit.Settings s = base(c)) {
            on(s, "carnival.ZombieShootoutConfig", "getInstance");
            stand(c, PX, PZ, 0f, 0f);
            startShootout(c, true);
            c.server().command(zombie(PX - 4, Y + 1, PZ + 14, "leather_helmet", "zl"));
            c.server().command(zombie(PX + 4, Y + 1, PZ + 14, "diamond_helmet", "zd"));
            c.waitUntil("two zombies and the lamp are targets",
                    mc -> (Integer) Mod.staticCall("carnival.ZombieShootout", "targetCount") == 3, 100);
            int lamps = c.onClient(mc -> (Integer) Mod.staticCall("carnival.ZombieShootout", "lampCount"));
            String best = c.onClient(mc -> (String) Mod.staticCall("carnival.ZombieShootout", "bestLabel"));
            c.note(lamps + " lamp(s) watched; best " + best);
            c.check(lamps >= 1 && "Lamp".equals(best), "with a lamp lit the best target is " + best + " (want Lamp)");
            c.server().command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:air", LAMP[0], LAMP[1] + 1, LAMP[2]));
            c.waitUntil("the lamp goes out", mc -> (Integer) Mod.staticCall("carnival.ZombieShootout", "targetCount") == 2, 60);
            c.ctx().waitTicks(2);
            best = c.onClient(mc -> (String) Mod.staticCall("carnival.ZombieShootout", "bestLabel"));
            List<String> lines = hud(c, "carnival.ZombieShootout");
            c.note("best after the lamp: " + best + "; HUD " + lines);
            c.check("Diamond".equals(best), "the best zombie is " + best + " (want Diamond over Leather)");
            c.check(has(lines, "Shoot: Diamond (120") && has(lines, "Zombies up: 2"), "the Shootout HUD is wrong: " + lines);
        } finally {
            cleanup(c);
        }
    }

    // ---- 3784 -----------------------------------------------------------------------------------------------------

    static String head(String texture) {
        return "minecraft:player_head[profile={properties:[{name:\"textures\",value:\"" + texture + "\"}]}]";
    }

    static void startFish(Session c) {
        sidebar(c, "§3§lCatch a Fish", "§fScore: §e0", "§fCatch Streak: §a0");
        c.waitUntil("Catch a Fish is on", mc -> "CATCH_A_FISH".equals(String.valueOf(
                Mod.staticCall("carnival.CarnivalState", "game"))), 60);
    }

    static void fishHelper(Session c) {
        try (HxKit.Settings s = base(c)) {
            on(s, "carnival.CatchAFishConfig", "getInstance");
            stand(c, PX, PZ, 0f, 0f);
            startFish(c);
            c.hx().stand(PX - 3, Y + 1, PZ + 6, null, "helmet", head(OTHER));
            c.hx().stand(PX + 3, Y + 1, PZ + 9, null, "helmet", head(YELLOW));
            c.waitUntil("two fish seen", mc -> (Integer) Mod.staticCall("carnival.CatchAFish", "fishCount") == 2, 60);
            int yellow = c.onClient(mc -> (Integer) Mod.staticCall("carnival.CatchAFish", "yellowCount"));
            Boolean best = c.onClient(mc -> (Boolean) Mod.staticCall("carnival.CatchAFish", "bestYellow"));
            List<String> lines = hud(c, "carnival.CatchAFish");
            c.note("yellow " + yellow + ", best is yellow " + best + "; HUD " + lines);
            c.check(yellow == 1, "the Yellow Fish was not told by its head: " + yellow + " yellow");
            c.check(Boolean.TRUE.equals(best), "the nearer plain fish was picked over the Yellow Fish");
            c.check(has(lines, "Fish up: 2 (yellow 1)") && has(lines, "Catch Streak: 0"), "the fish HUD is wrong: " + lines);
        } finally {
            cleanup(c);
        }
    }

    // ---- 3785 -----------------------------------------------------------------------------------------------------

    static long uses(Session c, long since, String item) {
        return c.hx().events(since, "use_item").stream().filter(e -> e.get("item").getAsString().contains(item)).count();
    }

    static void holdSlot(Session c, int slot) {
        c.onClient(mc -> {
            mc.player.getInventory().setSelectedSlot(slot);
            return null;
        });
        c.ctx().waitTicks(3);
    }

    static void triggerbot(Session c) {
        boolean cheat = Mod.isCheat();
        try (HxKit.Settings s = base(c)) {
            // The helper reads the game and its targets; on a legit jar nothing else would (the cheat ones do not exist).
            on(s, "carnival.ZombieShootoutConfig", "getInstance");
            if (cheat) {
                on(s, "carnival.ShootoutShotConfig", "triggerbot");
            }
            c.hx().give(0, "minecraft:carrot_on_a_stick[custom_name='Carnival Dart Tube']");
            holdSlot(c, 0);
            // 170, not 180: the server sends a 180 look back as -180, and the client then set to 180 jumps a full turn
            // (GrimAC AimModulo360 on the probe's own write, 2026-10-25 first run).
            stand(c, PX, PZ, 170f, 0f);
            startShootout(c, false);
            c.server().command(zombie(PX, Y + 1, PZ + 10, "golden_helmet", "zg"));
            c.waitUntil("the zombie is a target", mc -> (Integer) Mod.staticCall("carnival.ZombieShootout", "targetCount") == 1,
                    100);
            long t0 = c.hx().head();
            c.ctx().waitTicks(30);
            long away = uses(c, t0, "carrot_on_a_stick");
            c.note("shots while looking away: " + away);
            c.check(away == 0, "the triggerbot shot while the crosshair was off every target (" + away + ")");
            float[] r = look(PX, Y + 2.0, PZ + 10);
            stand(c, PX, PZ, r[0], r[1]);
            long t1 = c.hx().head();
            c.ctx().waitTicks(30);
            long on = uses(c, t1, "carrot_on_a_stick");
            Object shots = c.onClient(mc -> Mod.staticCall("carnival.ZombieTriggerbot", "shots"));
            c.note("shots with the crosshair on the zombie: " + on + " (mod counted " + shots + ")");
            if (cheat) {
                c.check(on >= 2, "the triggerbot fired " + on + " shot(s) at the zombie under the crosshair in 1.5 s");
                c.check(on <= 8, "the triggerbot fired " + on + " shots in 1.5 s, more than its 250 ms Shot Delay allows");
            } else {
                c.check(on == 0, "a legit jar shot " + on + " time(s)");
            }
        } finally {
            cleanup(c);
        }
    }

    // ---- 3786 -----------------------------------------------------------------------------------------------------

    static void autoShootout(Session c) {
        boolean cheat = Mod.isCheat();
        try (HxKit.Settings s = base(c)) {
            // The helper reads the game and its targets; on a legit jar nothing else would (the cheat ones do not exist).
            on(s, "carnival.ZombieShootoutConfig", "getInstance");
            if (cheat) {
                on(s, "carnival.ShootoutShotConfig", "auto");
            }
            c.hx().give(0, "minecraft:carrot_on_a_stick[custom_name='Carnival Dart Tube']");
            holdSlot(c, 0);
            stand(c, PX, PZ, 0f, 0f);
            startShootout(c, false);
            double zx = PX + 9;
            double zz = PZ + 11;
            c.server().command(zombie(zx, Y + 1, zz, "iron_helmet", "zi"));
            float[] want = look(zx, Y + 2.0, zz);
            c.waitUntil("the zombie is a target", mc -> (Integer) Mod.staticCall("carnival.ZombieShootout", "targetCount") == 1,
                    100);
            long t0 = c.hx().head();
            c.ctx().waitTicks(60);
            long shots = uses(c, t0, "carrot_on_a_stick");
            float[] got = c.onClient(mc -> new float[]{mc.player.getYRot(), mc.player.getXRot()});
            double yawErr = Math.abs(((got[0] - want[0]) % 360 + 540) % 360 - 180);
            c.note(String.format(Locale.ROOT, "shots %d; look yaw %.1f pitch %.1f, zombie at yaw %.1f pitch %.1f", shots,
                    got[0], got[1], want[0], want[1]));
            if (cheat) {
                c.check(yawErr < 6 && Math.abs(got[1] - want[1]) < 6, "Auto Zombie Shootout did not turn to the zombie");
                c.check(shots >= 1, "Auto Zombie Shootout turned but never shot");
            } else {
                c.check(shots == 0 && yawErr > 20, "a legit jar turned or shot");
            }
        } finally {
            cleanup(c);
        }
    }

    // ---- 3787 -----------------------------------------------------------------------------------------------------

    static void autoDig(Session c) {
        boolean cheat = Mod.isCheat();
        try (HxKit.Settings s = base(c)) {
            on(s, "carnival.FruitDiggingConfig", "getInstance");
            if (cheat) {
                on(s, "carnival.AutoFruitConfig", "swap");
                on(s, "carnival.AutoFruitConfig", "dig");
            }
            c.hx().give(0, "minecraft:golden_shovel[custom_name='Carnival Shovel']");
            c.hx().give(1, "minecraft:iron_shovel[custom_name='Carnival Shovel']");
            c.hx().give(2, "minecraft:diamond_shovel[custom_name='Carnival Shovel']");
            holdSlot(c, 0);
            stand(c, PX, PZ, 0f, 0f);
            long t0 = c.hx().head();
            startFruit(c);
            String pick = c.onClient(mc -> {
                Object r = Mod.staticCall("carnival.FruitDigging", "bestLabel");
                return r == null ? null : r.toString();
            });
            String mode = c.onClient(mc -> {
                Object rec = Mod.field(Mod.ROOT + "carnival.FruitDigging", "recommendation");
                return rec == null ? null : (String) Mod.call(rec, "shovel");
            });
            c.ctx().waitTicks(80);
            String target = c.onClient(mc -> (String) Mod.staticCall("carnival.AutoFruitDigging", "lastTarget"));
            List<JsonObject> digs = c.hx().events(t0, "attack_block");
            List<JsonObject> held = c.hx().events(t0, "held");
            int selected = c.onClient(mc -> mc.player.getInventory().getSelectedSlot());
            c.note("first pick " + pick + " with " + mode + "; dug " + target + "; held slot " + selected + "; held events "
                    + held.size() + "; attack_block events " + digs.size());
            // The pick may change between the first solve and the dig (the board found, a second solve); the dig must
            // go to the pick it was made for, and with the shovel in that pick's mode.
            pick = target.split("/")[0];
            mode = target.split("/")[1];
            if (!cheat) {
                c.check(digs.isEmpty() && selected == 0, "a legit jar dug or swapped");
                return;
            }
            int wantSlot = "Mines".equals(mode) ? 1 : 2;
            c.check(selected == wantSlot, "Auto Shovel Swap holds slot " + selected + " for " + mode + " (want " + wantSlot + ")");
            c.check(!digs.isEmpty(), "Auto Fruit Digging never dug");
            int[] p = pos(digs.get(0));
            int col = p[0] - GX;
            int row = p[2] - GZ;
            String dug = "" + (char) ('A' + row) + (col + 1);
            c.check(p[1] == Y && dug.equals(pick), "the dig went to " + p[0] + " " + p[1] + " " + p[2] + " (" + dug
                    + "), want the pick " + pick);
            c.check(digs.size() <= 3, "it dug " + digs.size() + " times before the dig landed (want at most its 3 tries)");
            // The dig lands: sandstone, a tag, a clue. The next dig must go to another tile.
            long t1 = c.hx().head();
            serverDig(c, dug, "Mango", 1);
            c.ctx().waitTicks(80);
            List<JsonObject> more = c.hx().events(t1, "attack_block");
            c.note("after the first dig landed: " + more.size() + " attack_block event(s)");
            c.check(!more.isEmpty(), "no second dig after the first landed");
            int[] q = pos(more.get(0));
            c.check(!(q[0] == p[0] && q[2] == p[2]), "the second dig went to the tile already dug");
        } finally {
            c.onClient(mc -> {
                mc.player.getInventory().setSelectedSlot(0);
                return null;
            });
            cleanup(c);
        }
    }

    // ---- 3788 -----------------------------------------------------------------------------------------------------

    static void autoCatch(Session c) {
        boolean cheat = Mod.isCheat();
        try (HxKit.Settings s = base(c)) {
            on(s, "carnival.CatchAFishConfig", "getInstance");
            if (cheat) {
                on(s, "carnival.AutoCatchAFishConfig", "getInstance");
            }
            c.hx().give(0, "minecraft:fishing_rod[custom_name='Carnival Rod']");
            holdSlot(c, 0);
            stand(c, PX, PZ, 90f, 0f);
            startFish(c);
            double fx = PX + 2;
            double fz = PZ + 6;
            c.hx().stand(fx, Y + 1, fz, null, "helmet", head(YELLOW), "marker", false, "invisible", false);
            c.waitUntil("the fish is seen", mc -> (Integer) Mod.staticCall("carnival.CatchAFish", "fishCount") == 1, 60);
            long t0 = c.hx().head();
            c.ctx().waitTicks(120);
            long rodUses = uses(c, t0, "fishing_rod");
            Object casts = c.onClient(mc -> Mod.staticCall("carnival.AutoCatchAFish", "casts"));
            Object reels = c.onClient(mc -> Mod.staticCall("carnival.AutoCatchAFish", "reels"));
            float yaw = c.onClient(mc -> mc.player.getYRot());
            c.note("rod uses " + rodUses + "; casts " + casts + ", reels " + reels + "; yaw " + yaw);
            if (cheat) {
                c.check(((Integer) casts) >= 1 && rodUses >= 1, "Auto Catch a Fish never cast");
                c.check(((Integer) reels) >= 1, "the fish was never on the hook, or it never reeled (casts " + casts + ")");
                c.check(rodUses >= 2, "the reel sent no use after the cast (" + rodUses + " rod uses)");
            } else {
                c.check(rodUses == 0, "a legit jar cast");
            }
        } finally {
            c.onClient(mc -> {
                mc.options.keyUse.setDown(false);
                return null;
            });
            cleanup(c);
        }
    }
}

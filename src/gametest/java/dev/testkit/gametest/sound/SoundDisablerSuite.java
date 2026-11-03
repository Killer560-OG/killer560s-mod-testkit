package dev.testkit.gametest.sound;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.TestMap;
import dev.testkit.gametest.hx.Session;
import dev.testkit.harness.SoundProbe;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import java.util.Locale;

/**
 * Sound Disabler (mod sounddisable-1102; killer560, 2026-11-02: "add a sound disabler to hide stuff like bonzo staff sound
 * effect"). Every case plays real sounds two ways - a server {@code playsound} (the sound packet path Hypixel uses) and a
 * client {@code ClientLevel.playLocalSound} (both {@code SoundManager.play} and, 15 blocks away, {@code playDelayed}) - and
 * judges them by {@link SoundProbe}, which counts what reached {@code SoundEngine.play}. A muted sound must never reach it
 * while an unmuted control sent AFTER it (so it is known to have arrived) does, and the mod's own mute counter must have
 * moved for exactly that row.
 * <ul>
 *   <li>1102-sound-bonzo: holding a Bonzo's Staff (by Skyblock id, the Starred id, and by name alone), the ghast shot and the
 *       firework blast after it are muted from the server and locally; a ghast sound with no staff held, a ghast sound 10
 *       blocks away, and a firework blast with no shot before it all still play.</li>
 *   <li>1102-sound-rows: Explosions (server and delayed local), the Custom Sound IDs list (exact and {@code *}) and Show Sound
 *       IDs; Bow Shots left off still plays.</li>
 *   <li>1102-sound-off: the master switch off, or the Bonzo's Staff row off, mutes nothing.</li>
 *   <li>1102-sound-persist: every setting survives save and load.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SoundDisablerSuite implements FabricClientGameTest {

    public static final String SESSION = "1102-sound-session";

    private static final String CFG = "com.killer560.hub.sounddisabler.SoundDisablerConfig";
    private static final String SD = "com.killer560.hub.sounddisabler.SoundDisabler";
    private static final String ROW = "com.killer560.hub.sounddisabler.MutedSound";
    private static final String CONTROL = "entity.experience_orb.pickup";
    private static final String[] ROWS = {"BONZO_STAFF", "EXPLOSIONS", "BOW_SHOTS", "ENDERMEN", "BLAZES", "WOLVES",
            "SPIDERS", "PHANTOMS", "WITHERS", "RAIN_AND_THUNDER"};

    @Override
    public void runTest(ClientGameTestContext ctx) {
        Session.run(ctx, SESSION,
                (server, s) -> TestMap.on(server)
                        .platform(-760, 150, -760, 6)
                        .catchFloor(140)
                        .survival()
                        .clearInventory()
                        .spawn(-759.5, -759.5, 0f)
                        .build(),
                s -> {
                    ModUnderTest.require("killer560smod");
                    s.test("1102-sound-bonzo", SoundDisablerSuite::bonzo);
                    s.test("1102-sound-rows", SoundDisablerSuite::rows);
                    s.test("1102-sound-off", SoundDisablerSuite::off);
                    s.test("1102-sound-persist", SoundDisablerSuite::persist);
                });
    }

    // ------------------------------------------------------------------ cases

    private static void bonzo(Session c) {
        try {
            configure(c, true, "BONZO_STAFF");
            hold(c, "minecraft:blaze_rod[custom_data={id:\"BONZO_STAFF\"}]");
            mutedFromServer(c, "entity.ghast.shoot", "~ ~ ~", "bonzo_staff", "the staff's shot from the server");
            mutedFromServer(c, "entity.firework_rocket.blast", "~3 ~2 ~", "bonzo_staff", "the balloon's blast after the shot");
            mutedLocally(c, "entity.ghast.shoot", 0, false, "bonzo_staff", "the staff's shot played locally");
            playsFromServer(c, "entity.ghast.shoot", "~10 ~ ~", "a ghast sound 10 blocks from the staff holder");
            hold(c, "minecraft:blaze_rod[custom_data={id:\"STARRED_BONZO_STAFF\"}]");
            mutedFromServer(c, "entity.ghast.shoot", "~ ~ ~", "bonzo_staff", "the Starred staff's shot");
            hold(c, "minecraft:blaze_rod[custom_name=\"Bonzo's Staff\"]");
            mutedFromServer(c, "entity.ghast.shoot", "~ ~ ~", "bonzo_staff", "a staff known by its name alone");
            hold(c, "minecraft:air");
            c.onClient(mc -> ModUnderTest.staticCall(SD, "forgetShot"));
            int passed0 = c.onClient(mc -> (Integer) ModUnderTest.staticCall(SD, "bonzoCandidatesPassed"));
            playsFromServer(c, "entity.ghast.shoot", "~ ~ ~", "a ghast sound with no staff held");
            playsFromServer(c, "entity.firework_rocket.blast", "~ ~ ~", "a firework blast with no staff shot before it");
            int passed = c.onClient(mc -> (Integer) ModUnderTest.staticCall(SD, "bonzoCandidatesPassed")) - passed0;
            c.note("Bonzo's Staff row let " + passed + " candidate(s) through");
            c.check(passed == 2, "the Bonzo's Staff row should have judged and passed 2 candidates, it passed " + passed);
        } finally {
            hold(c, "minecraft:air");
            reset(c);
        }
    }

    private static void rows(Session c) {
        try {
            configure(c, true, "EXPLOSIONS");
            setCustom(c, "entity.villager.*, minecraft:entity.cat.ambient");
            mutedFromServer(c, "entity.generic.explode", "~ ~ ~", "explosions", "an explosion from the server");
            mutedLocally(c, "entity.generic.explode", 15, true, "explosions", "a delayed local explosion 15 blocks away");
            mutedFromServer(c, "entity.villager.ambient", "~ ~ ~", "custom", "a custom id by prefix (entity.villager.*)");
            mutedFromServer(c, "entity.cat.ambient", "~ ~ ~", "custom", "a custom id by exact id");
            playsFromServer(c, "entity.cat.purr", "~ ~ ~", "an id not on the custom list");
            playsFromServer(c, "entity.arrow.shoot", "~ ~ ~", "a bow shot with Bow Shots off");
            playsLocally(c, "entity.arrow.shoot", 15, true, "a delayed local bow shot with Bow Shots off");
            c.onClient(mc -> ModUnderTest.call(cfg(), "setShowIds", new Class<?>[]{boolean.class}, new Object[]{true}));
            long mark = LogTap.mark();
            mutedFromServer(c, "entity.villager.trade", "~ ~ ~", "custom", "a custom id while Show Sound IDs is on");
            boolean shown = false;
            for (String l : LogTap.since(mark)) {
                if (l.contains("minecraft:entity.villager.trade") && l.contains("(muted)")) {
                    shown = true;
                    c.note("  " + l);
                }
            }
            c.check(shown, "Show Sound IDs did not name minecraft:entity.villager.trade (muted) in chat");
        } finally {
            reset(c);
        }
    }

    private static void off(Session c) {
        try {
            configure(c, false, ROWS);
            setCustom(c, "entity.cat.ambient");
            hold(c, "minecraft:blaze_rod[custom_data={id:\"BONZO_STAFF\"}]");
            int muted0 = c.onClient(mc -> (Integer) ModUnderTest.staticCall(SD, "muted"));
            playsFromServer(c, "entity.ghast.shoot", "~ ~ ~", "the staff's shot with the switch off");
            playsFromServer(c, "entity.generic.explode", "~ ~ ~", "an explosion with the switch off");
            playsFromServer(c, "entity.cat.ambient", "~ ~ ~", "a custom id with the switch off");
            playsLocally(c, "entity.ghast.shoot", 0, false, "the staff's shot played locally with the switch off");
            configure(c, true, "EXPLOSIONS");
            playsFromServer(c, "entity.ghast.shoot", "~ ~ ~", "the staff's shot with its row off");
            int muted = c.onClient(mc -> (Integer) ModUnderTest.staticCall(SD, "muted")) - muted0;
            c.check(muted == 0, "the mod muted " + muted + " sound(s) while nothing it covers was on");
        } finally {
            hold(c, "minecraft:air");
            reset(c);
        }
    }

    private static void persist(Session c) {
        try {
            c.onClient(mc -> {
                Object cfg = cfg();
                ModUnderTest.call(cfg, "setEnabled", new Class<?>[]{boolean.class}, new Object[]{true});
                for (int i = 0; i < ROWS.length; i++) {
                    setRow(cfg, ROWS[i], i % 2 == 1);
                }
                ModUnderTest.call(cfg, "setCustom", new Class<?>[]{String.class}, new Object[]{"entity.bee.*, block.bell.use"});
                ModUnderTest.call(cfg, "setShowIds", new Class<?>[]{boolean.class}, new Object[]{true});
                ModUnderTest.call(cfg, "save", new Class<?>[0], new Object[0]);
                ModUnderTest.staticCall(CFG, "load");
                return null;
            });
            StringBuilder got = new StringBuilder();
            boolean ok = c.onClient(mc -> {
                Object cfg = cfg();
                boolean all = ModUnderTest.getBoolean(cfg, "isEnabled") && ModUnderTest.getBoolean(cfg, "isShowIds")
                        && "entity.bee.*, block.bell.use".equals(ModUnderTest.call(cfg, "getCustom", new Class<?>[0], new Object[0]))
                        && ModUnderTest.getInt(cfg, "customCount") == 2;
                for (int i = 0; i < ROWS.length; i++) {
                    boolean on = isRow(cfg, ROWS[i]);
                    got.append(ROWS[i]).append('=').append(on).append(' ');
                    all &= on == (i % 2 == 1);
                }
                return all;
            });
            c.note("after save + load: " + got);
            c.check(ok, "a Sound Disabler setting did not survive save and load: " + got);
            java.nio.file.Path file = ModUnderTest.modConfig("killer560smod-sound-disabler.json");
            c.note("config file " + file + " exists " + java.nio.file.Files.exists(file));
            c.check(java.nio.file.Files.exists(file), "no config file at " + file);
        } finally {
            reset(c);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Object cfg() {
        return ModUnderTest.config(CFG);
    }

    private static void setRow(Object cfg, String row, boolean on) {
        ModUnderTest.call(cfg, "setOn", new Class<?>[]{rowClass(), boolean.class},
                new Object[]{ModUnderTest.enumValue(ROW, row), on});
    }

    private static boolean isRow(Object cfg, String row) {
        return (Boolean) ModUnderTest.call(cfg, "isOn", new Class<?>[]{rowClass()},
                new Object[]{ModUnderTest.enumValue(ROW, row)});
    }

    private static Class<?> rowClass() {
        try {
            return Class.forName(ROW);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("no " + ROW, e);
        }
    }

    /** The master switch, and exactly the rows {@code on} on (every other row off); the custom list cleared. */
    private static void configure(Session c, boolean enabled, String... on) {
        c.onClient(mc -> {
            Object cfg = cfg();
            ModUnderTest.call(cfg, "setEnabled", new Class<?>[]{boolean.class}, new Object[]{enabled});
            for (String r : ROWS) {
                setRow(cfg, r, java.util.Arrays.asList(on).contains(r));
            }
            ModUnderTest.call(cfg, "setCustom", new Class<?>[]{String.class}, new Object[]{""});
            ModUnderTest.call(cfg, "setShowIds", new Class<?>[]{boolean.class}, new Object[]{false});
            ModUnderTest.staticCall(SD, "forgetShot");
            return null;
        });
    }

    private static void setCustom(Session c, String list) {
        c.onClient(mc -> ModUnderTest.call(cfg(), "setCustom", new Class<?>[]{String.class}, new Object[]{list}));
    }

    /** Back to a fresh config (switch off, Bonzo's Staff on, the rest off), saved. */
    private static void reset(Session c) {
        c.onClient(mc -> {
            Object cfg = cfg();
            ModUnderTest.call(cfg, "setEnabled", new Class<?>[]{boolean.class}, new Object[]{false});
            for (String r : ROWS) {
                setRow(cfg, r, r.equals("BONZO_STAFF"));
            }
            ModUnderTest.call(cfg, "setCustom", new Class<?>[]{String.class}, new Object[]{""});
            ModUnderTest.call(cfg, "setShowIds", new Class<?>[]{boolean.class}, new Object[]{false});
            ModUnderTest.call(cfg, "save", new Class<?>[0], new Object[0]);
            return null;
        });
    }

    /** Puts {@code item} in his hand (air empties it) and waits until the client has it. */
    private static void hold(Session c, String item) {
        c.server().command("item replace entity @p weapon.mainhand with " + item);
        boolean empty = item.equals("minecraft:air");
        c.waitUntil("the client to see " + item + " in his hand",
                mc -> mc.player != null && mc.player.getMainHandItem().isEmpty() == empty, 100);
        c.server().sync(5);
    }

    private static int reached(Session c, String id) {
        return c.onClient(mc -> SoundProbe.count("minecraft:" + id));
    }

    private static int mutedBy(Session c, String row) {
        return c.onClient(mc -> (Integer) ModUnderTest.staticCall(SD, "muted", new Class<?>[]{String.class},
                new Object[]{row}));
    }

    private static void serverSound(Session c, String id, String at) {
        c.server().command("execute at @p run playsound minecraft:" + id + " master @p " + at + " 1 1");
    }

    /** Sends {@code id} and then the control sound; waits for the control, so {@code id} has arrived too. */
    private static int[] sendWithControl(Session c, String id, String at) {
        int ctl0 = reached(c, CONTROL);
        serverSound(c, id, at);
        serverSound(c, CONTROL, "~ ~ ~");
        c.waitUntil("the control sound to reach the engine", mc -> SoundProbe.count("minecraft:" + CONTROL) > ctl0, 100);
        return new int[]{reached(c, CONTROL) - ctl0};
    }

    private static void mutedFromServer(Session c, String id, String at, String row, String what) {
        int r0 = reached(c, id);
        int m0 = mutedBy(c, row);
        sendWithControl(c, id, at);
        int r = reached(c, id) - r0;
        int m = mutedBy(c, row) - m0;
        c.note(String.format(Locale.ROOT, "%s (%s at %s): reached the engine %d, muted by %s %d", what, id, at, r, row, m));
        c.check(r == 0, what + ": " + id + " reached the sound engine " + r + " time(s)");
        c.check(m == 1, what + ": the " + row + " row counted " + m + " mute(s), wanted 1");
    }

    private static void playsFromServer(Session c, String id, String at, String what) {
        int r0 = reached(c, id);
        sendWithControl(c, id, at);
        int r = reached(c, id) - r0;
        c.note(String.format(Locale.ROOT, "%s (%s at %s): reached the engine %d", what, id, at, r));
        c.check(r == 1, what + ": " + id + " reached the sound engine " + r + " time(s), wanted 1");
    }

    /** Plays {@code id} on the client, {@code dist} blocks east of him; {@code delayed} uses the distance delay. */
    private static void local(Minecraft mc, String id, double dist, boolean delayed) {
        mc.level.playLocalSound(mc.player.getX() + dist, mc.player.getY(), mc.player.getZ(),
                SoundEvent.createVariableRangeEvent(Identifier.withDefaultNamespace(id)), SoundSource.HOSTILE, 1f, 1f, delayed);
    }

    private static void mutedLocally(Session c, String id, double dist, boolean delayed, String row, String what) {
        int r0 = reached(c, id);
        int m0 = mutedBy(c, row);
        int ctl0 = reached(c, CONTROL);
        c.onClient(mc -> {
            local(mc, id, dist, delayed);
            local(mc, CONTROL, dist, delayed);
            return null;
        });
        c.waitUntil("the local control sound to reach the engine", mc -> SoundProbe.count("minecraft:" + CONTROL) > ctl0, 100);
        int r = reached(c, id) - r0;
        int m = mutedBy(c, row) - m0;
        c.note(String.format(Locale.ROOT, "%s (%s, %.0f blocks, delayed %s): reached the engine %d, muted by %s %d", what,
                id, dist, delayed, r, row, m));
        c.check(r == 0, what + ": " + id + " reached the sound engine " + r + " time(s)");
        c.check(m == 1, what + ": the " + row + " row counted " + m + " mute(s), wanted 1");
    }

    private static void playsLocally(Session c, String id, double dist, boolean delayed, String what) {
        int r0 = reached(c, id);
        c.onClient(mc -> {
            local(mc, id, dist, delayed);
            return null;
        });
        c.waitUntil(what + " to reach the engine", mc -> SoundProbe.count("minecraft:" + id) > r0, 100);
        int r = reached(c, id) - r0;
        c.note(String.format(Locale.ROOT, "%s (%s, %.0f blocks, delayed %s): reached the engine %d", what, id, dist, delayed, r));
        c.check(r == 1, what + ": " + id + " reached the sound engine " + r + " time(s), wanted 1");
    }
}

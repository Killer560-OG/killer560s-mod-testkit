package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code 1112-boss-persist} (simpersist-1112, killer560 2026-11-12: Spawn Withers OFF must still be OFF after a restart).
 * Spawn Withers OFF and Floor 7 mode are saved, then the boss screen's state is reloaded from disk (what a restart or a
 * profile load does) and must hold; with both files gone it must read the defaults (ON, Master Mode), which shows the
 * reload really read the disk instead of a cache.
 */
final class SimBossPersist1112 {

    static final String NAME = "1112-boss-persist";
    private static final String SCREEN = "com.killer560.hub.bosssim.BossStartScreen";

    private SimBossPersist1112() {
    }

    private static Class<?> cls() throws ReflectiveOperationException {
        return Class.forName(SCREEN);
    }

    private static boolean master() throws ReflectiveOperationException {
        Field f = cls().getDeclaredField("master");
        f.setAccessible(true);
        return f.getBoolean(null);
    }

    private static void setMaster(boolean on) throws ReflectiveOperationException {
        Field f = cls().getDeclaredField("master");
        f.setAccessible(true);
        f.setBoolean(null, on);
        Method save = cls().getDeclaredMethod("saveMode");
        save.setAccessible(true);
        save.invoke(null);
    }

    private static Path file(String field) throws ReflectiveOperationException {
        Field f = cls().getDeclaredField(field);
        f.setAccessible(true);
        return (Path) f.get(null);
    }

    private static void reload() {
        ModUnderTest.staticCall(SCREEN, "reload", new Class<?>[]{}, new Object[]{});
    }

    private static boolean withers() {
        return (Boolean) ModUnderTest.staticCall(SCREEN, "spawnWithers", new Class<?>[]{}, new Object[]{});
    }

    static void run(ClientGameTestContext ctx, List<String> f) throws Exception {
        ModUnderTest.staticCall(SCREEN, "setSpawnWithers", new Class<?>[]{boolean.class}, new Object[]{false});
        setMaster(false);
        Path wf = file("WITHERS_FILE");
        Path mf = file("MODE_FILE");
        System.out.println("[" + NAME + "] saved: withers file '" + Files.readString(wf) + "', mode file '" + Files.readString(mf) + "'");
        reload();
        boolean w = withers();
        boolean m = master();
        System.out.println("[" + NAME + "] after reload from disk: withers " + w + ", master " + m);
        if (w) {
            f.add("Spawn Withers OFF did not survive a reload from the file");
        }
        if (m) {
            f.add("Floor 7 mode did not survive a reload from the file");
        }
        Files.deleteIfExists(wf);
        Files.deleteIfExists(mf);
        reload();
        w = withers();
        m = master();
        System.out.println("[" + NAME + "] files gone, reloaded: withers " + w + ", master " + m);
        if (!w || !m) {
            f.add("with the files gone the reload kept a stale value (withers " + w + ", master " + m + ")");
        }
        ModUnderTest.staticCall(SCREEN, "setSpawnWithers", new Class<?>[]{boolean.class}, new Object[]{true});
        setMaster(true);
        profileLeavesSimAlone(ctx, f);
    }

    private static Path cfg(String name) {
        return (Path) ModUnderTest.staticCall("com.killer560.hub.util.ModPaths", "config", new Class<?>[]{String.class}, new Object[]{name});
    }

    private static Object pm(String method, String arg) {
        return ModUnderTest.staticCall("com.killer560.hub.profiles.ProfileManager", method, new Class<?>[]{String.class}, new Object[]{arg});
    }

    /** simpersist2-1112: a profile neither saves the sim files nor overwrites them on apply (a profile holding old ones included). */
    private static void profileLeavesSimAlone(ClientGameTestContext ctx, List<String> f) throws Exception {
        String prof = "tkp1112";
        Path coop = cfg("killer560smod-sim-coop.json");
        Path speed = cfg("killer560smod-sim-speed.txt");
        Path wf = file("WITHERS_FILE");
        Files.createDirectories(coop.getParent());
        ModUnderTest.staticCall(SCREEN, "setSpawnWithers", new Class<?>[]{boolean.class}, new Object[]{false});
        Files.writeString(coop, "{\"marker\":1}");
        Files.writeString(speed, "5");
        ctx.runOnClient(mc -> pm("saveCurrentAsProfile", prof));
        Field pd = Class.forName("com.killer560.hub.profiles.ProfileManager").getDeclaredField("PROFILES_DIR");
        pd.setAccessible(true);
        Path dir = ((Path) pd.get(null)).resolve(prof);
        boolean saved = Files.isDirectory(dir);
        long simInProfile;
        try (var st = Files.list(dir)) {
            simInProfile = st.filter(p -> p.getFileName().toString().startsWith("killer560smod-sim-")).count();
        }
        int total;
        try (var st = Files.list(dir)) {
            total = (int) st.count();
        }
        System.out.println("[" + NAME + "] profile saved " + saved + ", files in it " + total + ", sim files in it " + simInProfile);
        if (!saved || total < 2) {
            f.add("the profile was not saved with settings in it, so the exclusion proves nothing");
        }
        if (simInProfile != 0) {
            f.add(simInProfile + " sim file(s) were saved into the profile");
        }
        Files.writeString(dir.resolve("killer560smod-sim-coop.json"), "{\"marker\":2}");
        Files.writeString(dir.resolve("killer560smod-sim-speed.txt"), "99");
        Files.writeString(dir.resolve("killer560smod-sim-boss-withers.txt"), "on");
        ctx.runOnClient(mc -> pm("applyProfile", prof));
        String c = Files.readString(coop);
        String sp = Files.readString(speed);
        String wt = Files.readString(wf);
        boolean sw = withers();
        System.out.println("[" + NAME + "] after apply: coop " + c + ", speed " + sp + ", withers file " + wt + ", withers " + sw);
        if (!c.contains("1")) {
            f.add("apply overwrote sim-coop.json: " + c);
        }
        if (!sp.equals("5")) {
            f.add("apply overwrote sim-speed.txt: " + sp);
        }
        if (!wt.equals("off") || sw) {
            f.add("apply changed Spawn Withers (file '" + wt + "', value " + sw + ")");
        }
        pm("deleteProfile", prof);
        Files.deleteIfExists(coop);
        Files.deleteIfExists(speed);
        ModUnderTest.staticCall(SCREEN, "setSpawnWithers", new Class<?>[]{boolean.class}, new Object[]{true});
    }
}

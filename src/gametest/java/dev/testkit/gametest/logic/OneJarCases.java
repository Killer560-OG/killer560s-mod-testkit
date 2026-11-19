package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 1117-logic-onejar: one jar for Prism and the Modrinth App (mod onejar-1117, killer560 2026-11-17: Account Swapper, its ban
 * status and the Proxy Client must behave on Modrinth exactly as on Prism). Temp folders are built to the Modrinth App's real
 * layout (checked on disk: {@code <app>/app.db}, {@code <app>/profiles/<name>}) and Prism's ({@code <instance>/minecraft}
 * beside instance.cfg); nothing real is touched.
 */
final class OneJarCases {

    private static final String LA = "accounts.core.LauncherAccounts";

    private OneJarCases() {
    }


    private static Object source(String name) {
        return Mod.enumValue("accounts.core.PrismAccount$Source", name);
    }

    private static String detect(String override, String cmd, Path dir) throws Exception {
        return String.valueOf(Mod.staticCall(LA, "detect", override, cmd, dir));
    }

    static void run(LogicCase c) throws Exception {
        Path root = Files.createTempDirectory("testkit-onejar");
        Path app = Files.createDirectories(root.resolve("ModrinthApp"));
        Files.write(app.resolve("app.db"), new byte[]{1});
        Path profile = Files.createDirectories(app.resolve("profiles").resolve("My Profile"));
        Path prism = Files.createDirectories(root.resolve("PrismLauncher"));
        Path inst = Files.createDirectories(prism.resolve("instances").resolve("26.3"));
        Files.writeString(inst.resolve("instance.cfg"), "name=26.3\n");
        Path prismGame = Files.createDirectories(inst.resolve("minecraft"));
        Path other = Files.createDirectories(root.resolve("elsewhere").resolve("game"));

        c.check("a profile under <app>/profiles beside app.db is Modrinth, got " + detect(null, null, profile),
                detect(null, null, profile).equals("MODRINTH"));
        c.check("the Modrinth app's launch class alone is Modrinth",
                detect(null, "com.modrinth.theseus.MinecraftLaunch --x", other).equals("MODRINTH"));
        c.check("a Prism instance's minecraft folder is Prism, got " + detect(null, null, prismGame),
                detect(null, null, prismGame).equals("PRISM"));
        c.check("an unknown folder keeps today's behavior (Prism), got " + detect(null, "net.fabricmc.loader.impl.launch.knot.KnotClient", other),
                detect(null, "net.fabricmc.loader.impl.launch.knot.KnotClient", other).equals("PRISM"));
        c.check("the override wins", detect("modrinth", null, prismGame).equals("MODRINTH") && detect("prism", null, profile).equals("PRISM"));

        Path appDb = app.resolve("app.db");
        Path prismDir = prism.resolve("prismaccountswitcher");
        c.check("Prism keeps its shared folder: " + Mod.staticCall(LA, "sharedDir", prism, source("PRISM"), appDb),
                prismDir.equals(Mod.staticCall(LA, "sharedDir", prism, source("PRISM"), appDb)));
        c.check("Modrinth with a Prism data folder uses the same shared folder as Prism (ban status and Universal proxy are common)",
                prismDir.equals(Mod.staticCall(LA, "sharedDir", prism, source("MODRINTH"), appDb)));
        Path noPrism = root.resolve("NoPrism");
        Object mr = Mod.staticCall(LA, "sharedDir", noPrism, source("MODRINTH"), appDb);
        c.check("Modrinth with no Prism folder keeps them beside app.db, got " + mr, app.resolve("prismaccountswitcher").equals(mr));
        c.check("Prism with no Prism folder is unchanged (no Modrinth fallback)",
                noPrism.resolve("prismaccountswitcher").equals(Mod.staticCall(LA, "sharedDir", noPrism, source("PRISM"), appDb)));
        c.check("the folder was only computed, never created", !Files.exists(noPrism) && !Files.exists(app.resolve("prismaccountswitcher")));
        c.note("detection and shared-folder resolution asserted under Prism, Modrinth and unknown layouts");
    }
}

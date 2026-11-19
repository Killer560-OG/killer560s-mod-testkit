package dev.testkit.gametest.boss;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import java.nio.file.Files;
import java.nio.file.Path;

/** sliderunits-1118 (killer560 2026-11-18: no more CPS): every old clicks-per-second setting file loads as the nearest delay in ms. */
final class SliderUnits1118Cases {
    private SliderUnits1118Cases() {
    }

    static void register(Session s) {
        if (!Mod.isCheat()) {
            return;
        }
        s.test("1118-boss-cps-migrates", SliderUnits1118Cases::migrates);
    }

    /** One old file: the config class, the file's text, then each getter with the value it must read. */
    private static void one(Session c, String cls, String json, Object... getterAndWant) throws Exception {
        Path file = c.onClient(mc -> (Path) Mod.field(cls, "CONFIG_PATH"));
        byte[] old = Files.exists(file) ? Files.readAllBytes(file) : null;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, json);
            c.onClient(mc -> Mod.staticCall(cls, "load"));
            for (int i = 0; i < getterAndWant.length; i += 2) {
                String getter = (String) getterAndWant[i];
                int want = (Integer) getterAndWant[i + 1];
                int got = ((Number) c.onClient(mc -> Mod.call(Mod.cfg(cls), getter))).intValue();
                c.note(cls + " " + json + " -> " + getter + " " + got + " (want " + want + ")");
                c.check(got == want, cls + " " + getter + " read " + got + " from " + json + ", expected " + want);
            }
        } finally {
            if (old != null) {
                Files.write(file, old);
            } else {
                Files.deleteIfExists(file);
            }
            c.onClient(mc -> Mod.staticCall(cls, "load"));
        }
    }

    static void migrates(Session c) throws Exception {
        one(c, "arrowalign.ArrowAlignConfig", "{\"triggerBotCpsMin\": 15, \"triggerBotCpsMax\": 20, \"auraCps\": 10}",
                "getTriggerBotDelayMin", 50, "getTriggerBotDelayMax", 67, "getAuraCooldownMs", 100);
        one(c, "arrowalign.ArrowAlignConfig", "{\"triggerBotDelayMin\": 80, \"triggerBotDelayMax\": 90, \"auraCooldownMs\": 120}",
                "getTriggerBotDelayMin", 80, "getTriggerBotDelayMax", 90, "getAuraCooldownMs", 120);
        one(c, "goldor.GoldorTriggerbotConfig", "{\"cpsMin\": 4, \"cpsMax\": 10}", "getDelayMin", 100, "getDelayMax", 250);
        one(c, "i4sensors.I4SensorsConfig", "{\"cpsMin\": 2, \"cpsMax\": 10}", "getDelayMin", 100, "getDelayMax", 500);
        // the old top of 15 CPS is the new floor of 67 ms, never faster
        one(c, "i4sensors.I4SensorsConfig", "{\"cpsMin\": 15, \"cpsMax\": 15}", "getDelayMin", 67, "getDelayMax", 67);
        one(c, "terminatorclicker.TerminatorClickerConfig", "{\"cpsMin\": 5, \"cpsMax\": 10}", "getDelayMin", 100, "getDelayMax", 200);
        one(c, "terminatorclicker.TerminatorClickerConfig", "{\"cps\": 8}", "getDelayMin", 125, "getDelayMax", 125);
        one(c, "powdermining.PowderMiningConfig", "{\"chestTriggerbotMinCps\": 5, \"chestTriggerbotMaxCps\": 10}",
                "getChestTriggerbotMinDelayMs", 100, "getChestTriggerbotMaxDelayMs", 200);
        one(c, "powdermining.PowderMiningConfig", "{\"chestTriggerbotCps\": 10}",
                "getChestTriggerbotMinDelayMs", 100, "getChestTriggerbotMaxDelayMs", 100);
        one(c, "lightsdevice.LightsDeviceConfig", "{\"cps\": 4}", "getCooldownMs", 250);
        one(c, "lightsdevice.LightsDeviceConfig", "{\"cooldownMs\": 300, \"cps\": 4}", "getCooldownMs", 300);
    }
}

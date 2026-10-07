package dev.testkit.gametest.perf;

import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.TestMap;
import dev.testkit.gametest.hx.Hx;
import dev.testkit.gametest.hx.HxKit;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.FrameClock;
import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 403-perf-hub: what 95-fps-bench does not exercise - a Hypixel-shaped HUB rather than a dungeon. On the Hx test
 * server (so the mod sees mc.hypixel.net, a SkyBlock sidebar and an 80-entry tab list), with ~120 named armour stands
 * and 60 mobs around the player, five sub-phases are timed with the mod's features ON vs OFF, alternated:
 * <ul>
 *   <li><b>lobby</b> - entities, sidebar and tab list, no screen;</li>
 *   <li><b>tab</b> - the same with the player list held open;</li>
 *   <li><b>chat</b> - one Hypixel-shaped chat line every tick (public, party and private lines);</li>
 *   <li><b>menu</b> - a 54-slot chest of SkyBlock items open;</li>
 *   <li><b>storage</b> - the same items on an Ender Chest page, which Storage Overlay (on by default) takes over.</li>
 * </ul>
 * ON is 95's switch set plus features off by default that are commonly turned on in a hub ({@link #HUB_EXTRA});
 * OFF is every config with isEnabled/setEnabled off, as in 95. One ON and one OFF JFR per sub-phase land beside the
 * report ({@code hub-<phase>-on.jfr}) for {@code tools/fps-jfr.py}. Only when named ({@code -Pscenario=403}); it measures,
 * it does not test, except that every phase must have recorded frames and the scene must exist on the client.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class HubPerfBenchTest implements FabricClientGameTest {

    static final String NAME = "403-perf-hub";

    /** Off by default, commonly switched on away from dungeons. Missing ones are reported, not fatal. */
    static final String[][] HUB_EXTRA = {
            {"chattidy.ChatTidyConfig", "setStackDuplicates"}, {"chattidy.ChatTidyConfig", "setHideAbilityDamage"},
            {"partyfinder.PartyFinderOverlayConfig", "setEnabled"},
            {"smoothtp.SmoothTeleportConfig", "setEnabled"}, {"inventorytheme.InventoryThemeConfig", "setEnabled"},
            {"inventorysearch.InventorySearchConfig", "setEnabled"}, {"itemprotect.ItemProtectConfig", "setEnabled"},
            {"slotbinds.SlotBindsConfig", "setEnabled"}, {"tooltipscroll.TooltipScrollConfig", "setEnabled"},
            {"crosshair.CustomCrosshairConfig", "setEnabled"}, {"emotes.ChatEmoteConfig", "setEnabled"},
            {"copychat.CopyChatConfig", "setEnabled"}, {"motionblur.MotionBlurConfig", "setEnabled"},
            {"blessings.BlessingsConfig", "setHud", "isHudEnabled"},
            {"rngmeter.RngMeterConfig", "setEnabled"}, {"storageoverlay.StorageOverlayConfig", "setEnabled"},
    };

    private static final int X = -1300;
    private static final int Z = -1300;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank()) {
            return; // only when named
        }
        Session.run(ctx, NAME,
                (server, s) -> TestMap.on(server)
                        .platform(X, 150, Z, 24)
                        .catchFloor(140)
                        .survival()
                        .clearInventory()
                        .spawn(X + 0.5, Z + 0.5, 0f)
                        .build(),
                s -> {
                    ModUnderTest.require("killer560smod");
                    s.skipDetectorProof();
                    s.test(NAME + "-bench", HubPerfBenchTest::bench);
                });
    }

    private static void bench(Session c) throws Exception {
        ClientGameTestContext ctx = c.ctx();
        int frames = Integer.getInteger("testkit.bench.frames", 600);
        int rounds = Integer.getInteger("testkit.bench.rounds", 2);
        Path out = Path.of(System.getProperty("testkit.reportDir", "build/testkit-report"));
        List<String> report = new ArrayList<>();
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));

        // ---- the scene ----
        HxKit.sidebar(c, "template", "hub");
        List<String> extra = new ArrayList<>();
        String[] ranks = {"§7", "§a[VIP] ", "§a[VIP§6+§a] ", "§b[MVP] ", "§b[MVP§c+§b] ", "§6[MVP§c++§6] "};
        for (int i = 0; i < 79; i++) {
            extra.add("§r§7[§8" + (100 + i * 3) + "§7] §r" + ranks[i % ranks.length] + "HubPlayer" + i + " §r§6"
                    + (i % 4 == 0 ? "⚔" : "♲"));
        }
        HxKit.tab(c, "template", "area", "area", "Hub", "extra", extra);
        c.waitUntil("the client knows it is on SkyBlock",
                mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isOnSkyblock"), 200);
        int stands = 0;
        for (int i = 0; i < 120; i++) {
            double a = i * 0.37;
            double r = 4 + (i % 16);
            c.hx().stand(X + 0.5 + Math.cos(a) * r, 151, Z + 0.5 + Math.sin(a) * r,
                    (i % 3 == 0 ? "§c" : "§a") + "[Lv" + (i * 7 % 300) + "] §fHub Mob §a" + (i * 1234) + "§f/§a50000§c❤");
            stands++;
        }
        for (int i = 0; i < 60; i++) {
            double a = i * 0.61;
            double r = 5 + (i % 14);
            c.server().command(String.format(Locale.ROOT,
                    "summon minecraft:zombie %.2f 151 %.2f {NoAI:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b}",
                    X + 0.5 + Math.cos(a) * r, Z + 0.5 + Math.sin(a) * r));
        }
        ctx.waitTicks(60);
        int seen = c.onClient(mc -> {
            int n = 0;
            for (var e : mc.level.entitiesForRendering()) {
                n++;
            }
            return n;
        });
        report.add("scene: " + stands + " named stand(s) + 60 zombie(s) asked for, client renders " + seen
                + " entit(ies); tab entries " + c.onClient(mc -> mc.getConnection().getListedOnlinePlayers().size()));
        c.check(seen >= 150, "the client sees only " + seen + " entities - the scene did not arrive");

        ctx.runOnClient(mc -> {
            mc.options.enableVsync().set(false);
            mc.options.framerateLimit().set(260);
        });

        // ---- ON / OFF sets ----
        Map<String, Boolean> onBefore = new LinkedHashMap<>();
        Map<Object[], Boolean> offBefore = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<String[]> on = new ArrayList<>(List.of(FpsBenchTest.ON));
        on.addAll(List.of(HUB_EXTRA));
        ctx.runOnClient(mc -> {
            for (String[] s : on) {
                String key = s[0] + "#" + s[1];
                try {
                    String getter = s.length > 2 ? s[2] : s[1].replaceFirst("^set", "is");
                    onBefore.put(key, (Boolean) Mod.call(Mod.cfg(s[0]), getter));
                } catch (Throwable t) {
                    missing.add(key);
                }
            }
        });
        report.add("ON set: " + (on.size() - missing.size()) + " switch(es); missing " + missing);
        List<Object[]> offTargets = ctx.computeOnClient(mc -> FpsBenchTest.offTargets());
        report.add("OFF set: " + offTargets.size() + " config(s) with isEnabled/setEnabled");
        for (Object[] t : offTargets) {
            offBefore.put(t, ctx.computeOnClient(mc -> FpsBenchTest.invokeIs(t)));
        }

        Map<Integer, String> slots = new LinkedHashMap<>();
        String[][] items = {
                {"diamond_sword", "HYPERION", "Hyperion", "gold", "LEGENDARY DUNGEON SWORD"},
                {"bow", "TERMINATOR", "Terminator", "light_purple", "MYTHIC DUNGEON BOW"},
                {"player_head", "WITHER_GOGGLES", "Wither Goggles", "dark_purple", "EPIC DUNGEON HELMET"},
                {"ender_pearl", "SPIRIT_LEAP", "Spirit Leap", "blue", "RARE"},
                {"golden_axe", "RAGNAROCK_AXE", "Ragnarock Axe", "gold", "LEGENDARY DUNGEON SWORD"},
                {"stick", "ASPECT_OF_THE_VOID", "Aspect of the Void", "dark_purple", "EPIC SWORD"},
        };
        for (int i = 0; i < 54; i++) {
            if (i >= 45 && i != 49) {
                slots.put(i, "minecraft:black_stained_glass_pane[custom_name={text:\" \"}]");
                continue;
            }
            String[] it = items[i % items.length];
            slots.put(i, "minecraft:" + it[0] + "[custom_data={id:\"" + it[1] + "\",uuid:\"u" + i + "\"},custom_name={text:\""
                    + it[2] + "\",color:\"" + it[3] + "\",italic:false},lore=[{text:\"Gear Score: " + (500 + i)
                    + "\",color:\"gray\",italic:false},{text:\"Damage: +" + (200 + i) + "\",color:\"gray\",italic:false},"
                    + "{text:\"\"},{text:\"Ability: Something RIGHT CLICK\",color:\"gold\",italic:false},"
                    + "{text:\"" + it[4] + "\",color:\"" + it[3] + "\",bold:true,italic:false}]]");
        }

        String[] phases = {"lobby", "tab", "chat", "menu", "storage"};
        Map<String, long[][][]> results = new LinkedHashMap<>();
        try {
            FpsBenchTest.applyOn(ctx, onBefore, offBefore, true);
            ctx.waitTicks(200);
            FpsBenchTest.applyOn(ctx, onBefore, offBefore, false);
            ctx.waitTicks(200);
            for (String phase : phases) {
                long[][] onF = new long[rounds][], onT = new long[rounds][], offF = new long[rounds][], offT = new long[rounds][];
                enter(c, phase, slots);
                for (int r = 0; r < rounds; r++) {
                    FpsBenchTest.applyOn(ctx, onBefore, offBefore, true);
                    ctx.waitTicks(40);
                    long[][] a = measure(c, phase, frames);
                    onF[r] = a[0];
                    onT[r] = a[1];
                    FpsBenchTest.applyOn(ctx, onBefore, offBefore, false);
                    ctx.waitTicks(40);
                    long[][] b = measure(c, phase, frames);
                    offF[r] = b[0];
                    offT[r] = b[1];
                }
                if (!Boolean.getBoolean("testkit.bench.noJfr")) {
                    FpsBenchTest.applyOn(ctx, onBefore, offBefore, true);
                    ctx.waitTicks(40);
                    jfr(c, phase, frames, out.resolve("hub-" + phase + "-on.jfr"));
                    FpsBenchTest.applyOn(ctx, onBefore, offBefore, false);
                    ctx.waitTicks(40);
                    jfr(c, phase, frames, out.resolve("hub-" + phase + "-off.jfr"));
                }
                leave(c, phase);
                results.put(phase, new long[][][]{onF, onT, offF, offT});
                FrameClock.Stats sOnF = stats(onF), sOffF = stats(offF), sOnT = stats(onT), sOffT = stats(offT);
                report.add(String.format(Locale.ROOT, "%-5s ON  frame %s | tick %s", phase, sOnF.line(), sOnT.line()));
                report.add(String.format(Locale.ROOT, "%-5s OFF frame %s | tick %s", phase, sOffF.line(), sOffT.line()));
                report.add(String.format(Locale.ROOT, "%-5s DELTA frame mean %+.3f ms p95 %+.3f p99 %+.3f | tick mean %+.3f ms"
                                + " p95 %+.3f p99 %+.3f", phase, sOnF.mean() - sOffF.mean(), sOnF.p95() - sOffF.p95(),
                        sOnF.p99() - sOffF.p99(), sOnT.mean() - sOffT.mean(), sOnT.p95() - sOffT.p95(),
                        sOnT.p99() - sOffT.p99()));
                System.out.println("[" + NAME + "] " + report.get(report.size() - 1));
                c.check(sOnF.n() >= frames * rounds / 2 && sOnT.n() >= frames * rounds / 2,
                        "phase " + phase + ": the frame clock recorded " + sOnF.n() + " frames / " + sOnT.n() + " ticks");
            }
        } finally {
            try {
                ctx.runOnClient(mc -> {
                    onBefore.forEach((k, v) -> {
                        String[] p = k.split("#");
                        Mod.call(Mod.cfg(p[0]), p[1], v);
                    });
                    offBefore.forEach(FpsBenchTest::invokeSet);
                    mc.options.keyPlayerList.setDown(false);
                    McCompat.setScreen(mc, null);
                });
            } catch (Throwable ignored) {
                // restoring must not mask the real failure
            }
            try {
                c.hx().removeStands();
                c.server().command("kill @e[type=minecraft:zombie]");
            } catch (Throwable ignored) {
                // the session tears the server down anyway
            }
            for (String line : report) {
                System.out.println("[" + NAME + "] " + line);
            }
            try {
                Files.createDirectories(out);
                Files.writeString(out.resolve("hub-bench.txt"), String.join(System.lineSeparator(), report)
                        + System.lineSeparator());
            } catch (Exception e) {
                System.out.println("[" + NAME + "] could not write hub-bench.txt: " + e);
            }
        }
    }

    private static FrameClock.Stats stats(long[][] parts) {
        long[] all = FpsBenchTest.concat(parts);
        return FrameClock.stats(all, all.length);
    }

    private static void enter(Session c, String phase, Map<Integer, String> slots) {
        switch (phase) {
            case "tab" -> c.ctx().runOnClient(mc -> mc.options.keyPlayerList.setDown(true));
            case "storage" -> {
                // An Ender Chest page: Storage Overlay (on by default) takes the screen over. ON vs OFF here is the
                // overlay against a vanilla chest, so read ON before/after a change rather than the delta.
                c.hx().menu("Ender Chest (1/9)", 6, slots, null);
                c.waitUntil("the ender chest page is open", mc -> McCompat.screen(mc)
                        instanceof net.minecraft.client.gui.screens.inventory.ContainerScreen, 100);
            }
            case "menu" -> {
                // Not an Ender Chest/Backpack title: Storage Overlay (on by default) replaces those screens outright, which
                // made ON cheaper than OFF and measured the overlay instead of an ordinary container (first run).
                c.hx().menu("Auctions Browser", 6, slots, null);
                c.waitUntil("the chest menu is open", mc -> McCompat.screen(mc)
                        instanceof net.minecraft.client.gui.screens.inventory.ContainerScreen, 100);
            }
            default -> {
            }
        }
    }

    private static void leave(Session c, String phase) {
        switch (phase) {
            case "tab" -> c.ctx().runOnClient(mc -> mc.options.keyPlayerList.setDown(false));
            case "menu", "storage" -> c.ctx().runOnClient(mc -> {
                if (mc.player != null) {
                    mc.player.closeContainer();
                }
                McCompat.setScreen(mc, null);
            });
            default -> {
            }
        }
    }

    private static int chatSeq;

    /** One Hypixel-shaped line: public chat, party chat or a private message, rotating. */
    private static void chatLine(Hx hx) {
        int i = chatSeq++;
        String line = switch (i % 4) {
            case 0 -> "§7[§8" + (100 + i % 300) + "§7] §b[MVP§c+§b] HubPlayer" + (i % 79) + "§f: selling hyperion cheap visit me " + i;
            case 1 -> "§9Party §8> §a[VIP] HubPlayer" + (i % 79) + "§f: ready? " + i;
            case 2 -> "§dFrom §6[MVP§c++§6] HubPlayer" + (i % 79) + "§7: are you coming to f7 " + i;
            default -> "§7[§8" + (100 + i % 300) + "§7] §7HubPlayer" + (i % 79) + "§7: lf carry m7 " + i;
        };
        hx.chat(line);
    }

    private static long[][] measure(Session c, String phase, int frames) {
        ClientGameTestContext ctx = c.ctx();
        // On a dedicated server the client renders freely between ticks (~13 frames a tick at the 260 cap), not the
        // ~1.5 of 95's integrated-server sim, so the frame buffer is sized for that; a full buffer is reported.
        ctx.runOnClient(mc -> FrameClock.start(frames * 20));
        for (int i = 0; i < frames; i++) {
            if (phase.equals("chat")) {
                chatLine(c.hx());
            }
            ctx.waitTicks(1);
        }
        return ctx.computeOnClient(mc -> {
            FrameClock.stop();
            if (FrameClock.dropped() > 0) {
                System.out.println("[" + NAME + "] WARNING: " + FrameClock.dropped() + " frame(s) past capacity dropped");
            }
            return new long[][]{FrameClock.frameSamples(), FrameClock.tickSamples()};
        });
    }

    private static void jfr(Session c, String phase, int frames, Path file) {
        if (!phase.equals("chat")) {
            FpsBenchTest.jfrPhase(c.ctx(), frames, file);
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            jdk.jfr.Recording rec = new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"));
            rec.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(1));
            rec.start();
            for (int i = 0; i < frames; i++) {
                chatLine(c.hx());
                c.ctx().waitTicks(1);
            }
            rec.stop();
            rec.dump(file);
            rec.close();
        } catch (Exception e) {
            System.out.println("[" + NAME + "] JFR failed: " + e);
        }
    }
}

package dev.testkit.gametest;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.mod.Quiet;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * WP1's acceptance demo for shared-server mode, and the POSITIVE CONTROLS every later Hx scenario leans on: each
 * case proves one Hx primitive reaches the mod by reading the mod's own state back, so a WP that finds "the mod
 * ignores X" can rule out the primitive first.
 *
 * <ul>
 *   <li>bridge: ops answer; a stubbed command and an unknown one both arrive as {@code cmd} events.</li>
 *   <li>sidebar.set -&gt; {@code DungeonState.getFloor()} is "F7" (and null again after sidebar.clear).</li>
 *   <li>chat {overlay:true} -&gt; {@code PlayerStatsFeature.health} reads the line; also records whether the separate
 *       set-action-bar PACKET reaches the same parser (fact 6 in the coverage plan).</li>
 *   <li>tab.set -&gt; {@code PartyTracker.teammates()} lists the fake teammates, with classes.</li>
 *   <li>give -&gt; {@code CheatUtils.skyblockId} of the client's stack is TERMINATOR.</li>
 *   <li>quiet: {@code Quiet.apply} resolved every switch; the Prism accounts store points at the empty fixture;
 *       offline/no-external-open flags are set.</li>
 * </ul>
 *
 * <p>Fixtures replayed here are in {@code testkit-fixtures/core/demo.json}; each case also checks them against the
 * mod's own Pattern constants with {@link Fixtures#checkPatterns}.
 */
public class SessionDemoTest implements FabricClientGameTest {

    static final String SESSION = "099-session-demo";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        failPath(ctx);
        Session.run(ctx, SESSION,
                (server, s) -> TestMap.on(server)
                        .platform(-300, 150, -300, 6)
                        .catchFloor(140)
                        .survival()
                        .clearInventory()
                        .spawn(-299.5, -299.5, 0f)
                        .build(),
                s -> {
                    ModUnderTest.require("killer560smod");

                    s.test(SESSION + "-bridge", c -> {
                        JsonObject ops = c.hx().call("ops").getAsJsonObject();
                        int count = ops.get("count").getAsInt();
                        c.check(count >= 20, "expected the core ops to be registered, got " + count);
                        for (String stub : new String[]{"surface.status", "menu.status", "dungeon.status", "boss.status"}) {
                            c.check(ops.getAsJsonArray("ops").toString().contains("\"" + stub + "\""),
                                    "WP stub op missing: " + stub);
                        }
                        c.hx().stub("Hx stub answered", "hxprobe");
                        c.ctx().waitTicks(10);
                        c.ctx().runOnClient(mc -> mc.player.connection.sendCommand("hxprobe hello world"));
                        c.ctx().runOnClient(mc -> mc.player.connection.sendCommand("hxunknowncmd x"));
                        c.waitUntil("both commands in the Hx event stream", mc -> c.commands().size() >= 2, 100);
                        List<String> cmds = c.commands();
                        c.check(cmds.contains("hxprobe hello world"), "stubbed command not recorded: " + cmds);
                        c.check(cmds.contains("hxunknowncmd x"), "unknown command not recorded: " + cmds);
                        c.note(count + " ops; commands seen by the server: " + cmds);
                    });

                    s.test(SESSION + "-sidebar-floor", c -> {
                        Fixtures.Fixture f = Fixtures.byId("core.sidebar-catacombs-f7");
                        List<String> agree = Fixtures.checkPatterns(f);
                        c.check(agree.isEmpty(), "fixture disagrees with the mod: " + agree);
                        c.ctx().runOnClient(mc -> c.check(Mod.staticCall("secrets.DungeonState", "getFloor") == null,
                                "premise: floor should be null before the sidebar, was "
                                        + Mod.staticCall("secrets.DungeonState", "getFloor")));
                        sendSidebar(c, f);
                        c.waitUntil("DungeonState.getFloor() == F7",
                                mc -> "F7".equals(Mod.staticCall("secrets.DungeonState", "getFloor")), 100);
                        c.hx().sidebarClear();
                        c.waitUntil("DungeonState.getFloor() == null after sidebar.clear",
                                mc -> Mod.staticCall("secrets.DungeonState", "getFloor") == null, 100);
                        c.note("sidebar.set -> getFloor() F7; sidebar.clear -> null");
                    });

                    s.test(SESSION + "-overlay-playerstats", c -> {
                        Fixtures.Fixture f = Fixtures.byId("core.overlay-stats");
                        List<String> agree = Fixtures.checkPatterns(f);
                        c.check(agree.isEmpty(), "fixture disagrees with the mod: " + agree);
                        try (AutoCloseable on = c.onClient(mc -> Mod.with("playerstats.PlayerStatsConfig", "Enabled", true))) {
                            // Premise: a value that is not the one we will send.
                            c.hx().overlay("§c1/1❤");
                            c.waitUntil("health == 1/1 (reset)",
                                    mc -> "1/1".equals(Mod.field("playerstats.PlayerStatsFeature", "health")), 60);
                            c.hx().call("chat", "text", f.payload().getAsString(), "overlay", true);
                            c.waitUntil("PlayerStatsFeature.health == 1234/2345 from overlay chat",
                                    mc -> "1234/2345".equals(Mod.field("playerstats.PlayerStatsFeature", "health")), 60);
                            Object mana = c.onClient(mc -> Mod.field("playerstats.PlayerStatsFeature", "mana"));
                            Object def = c.onClient(mc -> Mod.field("playerstats.PlayerStatsFeature", "defense"));
                            c.check("890/900".equals(mana) && "567".equals(def), "mana/defense " + mana + "/" + def);
                            // Informational: does the separate SetActionBarText PACKET reach the same parser?
                            c.hx().actionBarPacket("§c4321/4321❤");
                            c.ctx().waitTicks(20);
                            Object after = c.onClient(mc -> Mod.field("playerstats.PlayerStatsFeature", "health"));
                            c.note("overlay chat -> health 1234/2345, mana " + mana + ", defense " + def
                                    + "; set-action-bar PACKET -> health " + after
                                    + ("4321/4321".equals(after) ? " (the packet ALSO reaches it)"
                                    : " (the packet does NOT reach it)"));
                        }
                    });

                    s.test(SESSION + "-tab-partytracker", c -> {
                        Fixtures.Fixture f = Fixtures.byId("core.tab-teammates");
                        List<String> agree = Fixtures.checkPatterns(f);
                        c.check(agree.isEmpty(), "fixture disagrees with the mod: " + agree);
                        // PartyTracker only reads the tab inside a dungeon, so the floor goes up first.
                        sendSidebar(c, Fixtures.byId("core.sidebar-catacombs-f7"));
                        c.waitUntil("in a dungeon (F7)",
                                mc -> "F7".equals(Mod.staticCall("secrets.DungeonState", "getFloor")), 100);
                        List<String> entries = new ArrayList<>();
                        f.payload().getAsJsonObject().getAsJsonArray("entries").forEach(e -> entries.add(e.getAsString()));
                        c.hx().tab(entries, "§bYou are playing on §e§lMC.HYPIXEL.NET", "§aRanks, Boosters & MORE!");
                        c.waitUntil("PartyTracker.teammates() lists HxMateA and HxMateB", mc -> {
                            @SuppressWarnings("unchecked")
                            List<String> mates = (List<String>) Mod.staticCall("leapmenu.PartyTracker", "teammates");
                            return mates.contains("HxMateA") && mates.contains("HxMateB");
                        }, 100);
                        Object classA = c.onClient(mc -> Mod.staticCall("leapmenu.PartyTracker", "classOf", "HxMateA"));
                        Object classB = c.onClient(mc -> Mod.staticCall("leapmenu.PartyTracker", "classOf", "HxMateB"));
                        c.check(classA != null && classA.toString().contains("MAGE"), "HxMateA class " + classA);
                        c.check(classB != null && classB.toString().contains("BERSERK"), "HxMateB class " + classB);
                        int listed = c.onClient(mc -> mc.getConnection().getListedOnlinePlayers().size());
                        c.note("tab.set -> teammates HxMateA (" + classA + "), HxMateB (" + classB + "); " + listed
                                + " listed tab entries on the client");
                        c.hx().tabClear();
                        c.hx().sidebarClear();
                    });

                    s.test(SESSION + "-give-skyblockid", c -> {
                        c.hx().give(0, "minecraft:bow[custom_data={id:\"TERMINATOR\"}]");
                        c.waitUntil("a bow in hotbar slot 0",
                                mc -> !mc.player.getInventory().getItem(0).isEmpty(), 60);
                        String id = c.onClient(mc -> {
                            ItemStack stack = mc.player.getInventory().getItem(0);
                            return (String) Mod.staticCall("cheatutils.CheatUtils", "skyblockId", stack);
                        });
                        c.check("TERMINATOR".equals(id), "CheatUtils.skyblockId = " + id);
                        c.note("give -> CheatUtils.skyblockId(slot 0) = " + id);
                        c.hx().call("give", "slot", 0, "stack", "minecraft:air");
                    });

                    s.test(SESSION + "-primitives", c -> {
                        // title -> the client's Gui holds it
                        c.hx().title("§cHx Title", "§7hx subtitle");
                        c.waitUntil("the client's Gui title to read 'Hx Title'", mc -> {
                            Object t = Mod.field(mc.gui, "title");
                            return t instanceof Component comp && comp.getString().contains("Hx Title");
                        }, 60);
                        // sound and particle: the ops must succeed (nothing in the mod reads them here)
                        c.hx().sound("minecraft:entity.experience_orb.pickup", 0.05, 1.0);
                        c.hx().call("particle", "type", "minecraft:flame", "count", 3);
                        // stand -> the client sees a named armour stand; entity.remove -> it goes
                        double[] at = c.scenario().playerPosition();
                        c.hx().stand(at[0] + 2, at[1], at[2], "§cHx Stand", "small", true, "marker", true);
                        c.waitUntil("the client to see an armour stand named 'Hx Stand'",
                                mc -> standNamed(mc, "Hx Stand"), 100);
                        c.hx().removeStands();
                        c.waitUntil("the stand to be gone on the client", mc -> !standNamed(mc, "Hx Stand"), 100);
                        // menu.open -> a container screen; a click is recorded and moves nothing; close is recorded
                        int id = c.hx().menu("Hx Menu", 3, Map.of(13, "minecraft:diamond"), null);
                        c.waitUntil("a container screen 'Hx Menu' with a diamond in slot 13", mc ->
                                mc.screen instanceof AbstractContainerScreen<?> scr
                                        && scr.getTitle().getString().equals("Hx Menu")
                                        && scr.getMenu().containerId == id
                                        && scr.getMenu().getSlot(13).getItem().is(Items.DIAMOND), 100);
                        c.ctx().runOnClient(mc -> mc.gameMode.handleContainerInput(id, 13, 0, ContainerInput.PICKUP, mc.player));
                        c.waitUntil("container.click and menu.click events for slot 13", mc ->
                                c.events("container.click").stream().anyMatch(e -> e.get("slot").getAsInt() == 13)
                                        && c.events("menu.click").stream().anyMatch(e -> e.get("slot").getAsInt() == 13), 60);
                        c.ctx().waitTicks(5);
                        boolean kept = c.onClient(mc -> mc.player.containerMenu.getCarried().isEmpty()
                                && mc.player.containerMenu.getSlot(13).getItem().is(Items.DIAMOND));
                        c.check(kept, "the click moved the item (carried or slot 13 changed)");
                        c.ctx().runOnClient(mc -> mc.player.closeContainer());
                        c.waitUntil("a container.close event", mc -> !c.events("container.close").isEmpty(), 60);
                        // use_item: right-click with a stick sends ServerboundUseItemPacket
                        c.hx().give(0, "minecraft:stick");
                        c.waitUntil("a stick in slot 0", mc -> mc.player.getInventory().getItem(0).is(Items.STICK), 60);
                        c.ctx().runOnClient(mc -> {
                            mc.player.getInventory().setSelectedSlot(0);
                            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                        });
                        c.waitUntil("a use_item event for the stick", mc -> c.events("use_item").stream()
                                .anyMatch(e -> e.get("item").getAsString().equals("minecraft:stick")), 60);
                        c.hx().call("give", "slot", 0, "stack", "minecraft:air");
                        c.note("title, sound, particle, stand (seen and removed on the client), menu.open + click "
                                + "(recorded, item not moved) + close, use_item - all observed; events: "
                                + c.events().size());
                    });

                    s.test(SESSION + "-quiet-and-accounts", c -> {
                        c.check(Quiet.enabled(), "this run has -PnoQuiet; the QUIET control needs the default");
                        List<String> applied = c.onClient(mc -> Quiet.apply());
                        c.check(applied.size() == Quiet.size(), "Quiet resolved " + applied.size() + " of " + Quiet.size());
                        String expected = System.getProperty("prismaccountswitcher.accountsFile");
                        c.check(expected != null && !expected.isBlank(), "prismaccountswitcher.accountsFile is not set");
                        Path used = c.onClient(mc -> (Path) Mod.staticCall("accounts.core.PrismAccountStore",
                                "defaultAccountsFile"));
                        c.check(used.toAbsolutePath().normalize().equals(Path.of(expected).toAbsolutePath().normalize()),
                                "PrismAccountStore reads " + used + ", not the fixture " + expected);
                        List<?> accounts = c.onClient(mc -> (List<?>) Mod.staticCall("accounts.core.PrismAccountStore", "load"));
                        c.check(accounts.isEmpty(), "the accounts fixture should hold no accounts, got " + accounts.size());
                        // Quiet must take the relay down, not just rely on the offline flag refusing it.
                        c.waitUntil("RelayClient.state() == OFF after Quiet",
                                mc -> "OFF".equals(String.valueOf(Mod.staticCall("relay.RelayClient", "state"))), 100);
                        c.check("true".equals(System.getProperty("killer560.net.offline")), "killer560.net.offline not set");
                        c.check("true".equals(System.getProperty("killer560.test.noExternalOpen")),
                                "killer560.test.noExternalOpen not set");
                        c.note("Quiet.apply set " + applied.size() + "/" + Quiet.size() + " switches; accounts file "
                                + used + " (0 accounts); net.offline and noExternalOpen on; cheat="
                                + Mod.isCheat() + ", devTools=" + Mod.isDevTools());
                    });
                });
    }

    /**
     * Self-test of the session machinery's FAILURE path, run only when the filter names it explicitly
     * ({@code -Psuite=failpath} or {@code -Pscenario=098-session-failpath}) because it fails on purpose. Expected
     * result, every time: exactly ONE failure, {@code 098-session-failpath-fails}, with a screenshot and a FAIL row;
     * the case after it passes (a failure does not end the session); a case that gets the client kicked passes and the
     * next one finds it re-connected. Anything else means Session/Report/SuiteVerdict broke.
     */
    static void failPath(ClientGameTestContext ctx) {
        if (!System.getProperty("testkit.scenario", "").contains("098-session-failpath")) {
            return;
        }
        Session.run(ctx, "098-session-failpath",
                (server, s) -> TestMap.on(server).platform(-340, 150, -340, 4).catchFloor(140).survival()
                        .spawn(-339.5, -339.5, 0f).build(),
                s -> {
                    s.test("098-session-failpath-fails", c -> c.check(false, "deliberate failure (session self-test)"));
                    s.test("098-session-failpath-continues", c -> {
                        c.check("pong".equals(c.hx().call("ping").getAsString()), "bridge did not answer");
                        c.note("the case after a failure ran");
                    });
                    s.test("098-session-failpath-kicked", c -> {
                        c.hx().kick("session self-test kick");
                        c.waitUntil("the client to be disconnected", mc -> mc.level == null, 200);
                        c.note("kicked; the next case must find the client re-connected");
                    });
                    s.test("098-session-failpath-after-kick", c -> {
                        c.check(c.scenario().connected(), "not re-connected after the kick");
                        int players = c.hx().call("players").getAsJsonArray().size();
                        c.check(players == 1, "server sees " + players + " player(s)");
                        c.note("re-connected; server sees 1 player");
                    });
                });
    }

    private static boolean standNamed(Minecraft mc, String name) {
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof ArmorStand && e.getCustomName() != null && e.getCustomName().getString().contains(name)) {
                return true;
            }
        }
        return false;
    }

    private static void sendSidebar(Session c, Fixtures.Fixture f) {
        JsonObject payload = f.payload().getAsJsonObject();
        c.hx().call("sidebar.set", payload.deepCopy());
    }
}

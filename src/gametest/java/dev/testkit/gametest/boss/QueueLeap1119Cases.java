package dev.testkit.gametest.boss;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.testkit.gametest.Fixtures;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Latency;
import dev.testkit.gametest.LogTap;
import dev.testkit.harness.WireCapture;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Queue Leap (mod queueleap-1119, fastleap/QueueLeap; killer560 2026-11-18): his right click with a Spirit Leap opens the
 * mod's own leap menu at once; his pick is clicked on Hypixel's real menu (hidden) the tick its content is in, and he
 * stands still from the use until the leap's teleport. Hx serves the leap menu {@code delay} server ticks after the use;
 * a head click teleports him to B and closes the menu, as Hypixel's does. Every case reads the wire (one use_item, one
 * container click on the real window id) and LeapManager's step log (menu / pick / click / teleport ticks); the session's
 * GrimAC check judges every case (default config; experimental in a checkout whose config.yml turns them on).
 */
final class QueueLeap1119Cases {

    private static final String CFG = "spiritleap.SpiritLeapOverlayConfig";
    private static final String LM = "fastleap.LeapManager";
    private static final String MATE = "SicoKalebQa";
    private static final String MATE2 = "SicoKalebQb";
    private static final int AX = 30;
    private static final int AZ = 60;
    private static final int Y = 180;
    private static final int BX = 52;
    private static final int BZ = 60;

    private enum Pick { EARLY, LATE, ESCAPE, NONE }

    private enum Act { NONE, USE, ATTACK, SWAP, LEAP2, ACCEPT }

    private record Opt(int delay, int lagMs, Pick pick, boolean mateInMenu, boolean menuComes, Act act, boolean fastLeap,
                       boolean on) {
        static Opt early(int delay, int lag) {
            return new Opt(delay, lag, Pick.EARLY, true, true, Act.NONE, false, true);
        }

        Opt with(Act a) {
            return new Opt(delay, lagMs, pick, mateInMenu, menuComes, a, fastLeap, on);
        }
    }

    private QueueLeap1119Cases() {
    }

    static void register(Session s) {
        s.test("1119-queueleap-early-d1", c -> run(c, Opt.early(1, 0)));
        s.test("1119-queueleap-early-d3", c -> run(c, Opt.early(3, 0)));
        s.test("1119-queueleap-early-d10", c -> run(c, Opt.early(10, 0)));
        s.test("1119-queueleap-early-lag150", c -> run(c, Opt.early(1, 150)));
        s.test("1119-queueleap-early-lag300", c -> run(c, Opt.early(1, 300)));
        s.test("1119-queueleap-late", c -> run(c, new Opt(3, 0, Pick.LATE, true, true, Act.NONE, false, true)));
        s.test("1119-queueleap-missing", c -> run(c, new Opt(2, 0, Pick.EARLY, false, true, Act.NONE, false, true)));
        s.test("1119-queueleap-nomenu", c -> run(c, new Opt(0, 0, Pick.NONE, true, false, Act.NONE, false, true)));
        s.test("1119-queueleap-nomenu-picked", c -> run(c, new Opt(0, 0, Pick.EARLY, true, false, Act.NONE, false, true)));
        s.test("1119-queueleap-escape", c -> run(c, new Opt(10, 0, Pick.ESCAPE, true, true, Act.NONE, false, true)));
        s.test("1119-queueleap-fastleap", c -> run(c, new Opt(3, 0, Pick.EARLY, true, true, Act.NONE, true, true)));
        for (Act a : new Act[] {Act.USE, Act.ATTACK, Act.SWAP, Act.LEAP2, Act.ACCEPT}) {
            String id = a.name().toLowerCase(Locale.ROOT);
            s.test("1119-queueleap-same-" + id, c -> run(c, new Opt(3, 0, Pick.LATE, true, true, a, false, true)));
            s.test("1119-queueleap-same-" + id + "-early", c -> run(c, new Opt(3, 0, Pick.EARLY, true, true, a, false, true)));
        }
        // OFF (and every legit jar): a right click opens nothing of the mod's; Hypixel's own menu shows as always
        s.test("1119-queueleap-off-legit-absent", c -> run(c, new Opt(2, 0, Pick.NONE, true, true, Act.NONE, false, false)));
    }

    private static String describe(Object p) {
        if (p instanceof ServerboundUseItemPacket) {
            return "use";
        }
        if (p instanceof ServerboundContainerClickPacket k) {
            return "click " + k.containerId() + " " + k.slotNum();
        }
        if (p instanceof ServerboundContainerClosePacket k) {
            return "close " + k.getContainerId();
        }
        if (p instanceof ServerboundPlayerInputPacket i) {
            return "input " + (i.input().forward() ? "W" : "-");
        }
        return p.getClass().getSimpleName();
    }

    /** Play packets per client tick (client_tick_end closes a tick), keep-alives and pongs left out. */
    private static List<List<String>> ticks() {
        List<List<String>> out = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        for (WireCapture.Sent s : WireCapture.snapshot()) {
            if (!s.phase().equals("play")) {
                continue;
            }
            if (s.text().equals("ServerboundClientTickEndPacket")) {
                out.add(cur);
                cur = new ArrayList<>();
            } else if (!s.text().equals("ServerboundKeepAlivePacket") && !s.text().equals("ServerboundPongPacket")) {
                cur.add(s.text());
            }
        }
        if (!cur.isEmpty()) {
            out.add(cur);
        }
        return out;
    }

    private static int firstTick(List<List<String>> ticks, String prefix) {
        for (int i = 0; i < ticks.size(); i++) {
            for (String p : ticks.get(i)) {
                if (p.startsWith(prefix)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static long count(List<List<String>> ticks, String prefix) {
        return ticks.stream().flatMap(List::stream).filter(p -> p.startsWith(prefix)).count();
    }

    /** The LeapManager step log's tick for the first step starting with {@code step}, or -1. */
    @SuppressWarnings("unchecked")
    private static long stepTick(Session c, String step) {
        List<String> log = c.onClient(mc -> (List<String>) Mod.staticCall(LM, "stepLog"));
        for (String l : log) {
            int sp = l.indexOf(' ');
            if (l.substring(sp + 1).startsWith(step)) {
                return Long.parseLong(l.substring(0, sp));
            }
        }
        return -1;
    }

    private static boolean queueScreen(net.minecraft.client.Minecraft mc) {
        var s = dev.testkit.compat.McCompat.screen(mc);
        return s != null && s.getClass().getName().endsWith("fastleap.QueueLeapScreen");
    }

    private static void run(Session c, Opt o) throws Exception {
        boolean cheat = Mod.isCheat();
        boolean acts = cheat && o.on;
        ClickGuardCases.fill(c, AX - 10, Y - 1, AZ - 10, AX + 10, Y - 1, AZ + 25, "minecraft:stone");
        DungeonFixBossCases.floor(c, BX, Y, BZ);
        TerminalAuraCases.tp(c, AX + 0.5, Y, AZ + 0.5, 0f);
        String self = c.onClient(mc -> mc.player.getGameProfile().name());
        c.hx().call("sidebar.set", Fixtures.byId("core.sidebar-catacombs-f7").payload().getAsJsonObject().deepCopy());
        c.hx().tab(List.of("[120] " + self + " (Archer L)", "[110] " + MATE + " (Mage XL)", "[100] " + MATE2 + " (Tank XX)"),
                null, null);
        c.hx().give(0, Fixtures.byId("items.spirit-leap").payload().getAsJsonObject().get("stack").getAsString());
        JsonObject spec = Fixtures.byId("menus.spirit-leap").payload().getAsJsonObject().deepCopy();
        spec.getAsJsonObject("slots").getAsJsonObject("11").addProperty("name", "§a" + (o.mateInMenu ? MATE : "SicoKalebQz"));
        spec.getAsJsonObject("slots").getAsJsonObject("13").addProperty("name", "§a" + MATE2);
        JsonObject tpTo = new JsonObject();
        tpTo.addProperty("x", BX + 0.5);
        tpTo.addProperty("y", (double) Y);
        tpTo.addProperty("z", BZ + 0.5);
        for (String slot : new String[] {"11", "13"}) {
            spec.getAsJsonObject("on").getAsJsonObject(slot).add("tp", tpTo.deepCopy());
        }
        JsonObject use = new JsonObject();
        use.addProperty("id", "SPIRIT_LEAP");
        if (o.menuComes) {
            use.add("menu", spec);
        } else {
            use.add("menu", com.google.gson.JsonNull.INSTANCE);
        }
        use.addProperty("delay", o.delay);
        if (o.act == Act.ACCEPT) {
            JsonObject here = new JsonObject();
            here.addProperty("x", AX + 0.5);
            here.addProperty("y", (double) Y);
            here.addProperty("z", AZ + 0.5);
            use.add("withTp", here);
        }
        c.hx().call("menu.onUse", use);
        long mark = LogTap.mark();
        WireCapture.describe = QueueLeap1119Cases::describe;
        try (AutoCloseable cfg = c.onClient(mc -> Mod.with(CFG, "QueueLeap", o.on))) {
            c.waitUntil("F7, the leap in slot 0 and two teammates", mc -> "F7".equals(Mod.staticCall("secrets.DungeonState", "getFloor"))
                    && "SPIRIT_LEAP".equals(Mod.staticCall("cheatutils.CheatUtils", "skyblockId", mc.player.getInventory().getItem(0)))
                    && ((List<?>) Mod.staticCall("fastleap.Teammates", "noSelf")).size() >= 2, 200);
            c.onClient(mc -> {
                mc.player.getInventory().setSelectedSlot(0);
                Mod.staticCall(LM, "setCooldownTicksForTest", 0.0);
                return null;
            });
            c.waitUntil("he stands at A", mc -> mc.player.onGround(), 100);
            c.ctx().waitTicks(20);
            if (o.lagMs > 0) {
                c.ctx().runOnClient(mc -> Latency.install(o.lagMs));
                c.ctx().waitTicks(20);
            }
            long t0 = c.onClient(mc -> ((Number) Mod.staticCall("util.ActionGate", "currentTick")).longValue());
            WireCapture.start();
            // his right click, W held from it on (the test presses W again every tick, as a held key would read)
            c.ctx().runOnClient(mc -> {
                KeyMapping.click((InputConstants.Key) Mod.field(mc.options.keyUse, "key"));
                KeyMapping.set((InputConstants.Key) Mod.field(mc.options.keyUp, "key"), true);
            });
            long screenTick = -1;
            long pickTick = -1;
            long tpSeenTick = -1;
            boolean picked = false;
            boolean realShown = false;
            Vec3 atPick = null;
            List<String> trace = new ArrayList<>();
            for (int i = 0; i < 140; i++) {
                c.ctx().waitTicks(1);
                final boolean doPick = !picked;
                Object[] st = c.onClient(mc -> {
                    KeyMapping.set((InputConstants.Key) Mod.field(mc.options.keyUp, "key"), true);
                    long tick = ((Number) Mod.staticCall("util.ActionGate", "currentTick")).longValue();
                    var screen = dev.testkit.compat.McCompat.screen(mc);
                    boolean real = screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>;
                    return new Object[] {tick, queueScreen(mc), real, mc.player.position()};
                });
                long tick = (Long) st[0];
                boolean qs = (Boolean) st[1];
                realShown |= (Boolean) st[2];
                Vec3 pos = (Vec3) st[3];
                trace.add(String.format(Locale.ROOT, "%d%s%s %.2f,%.2f", tick - t0, qs ? " Q" : "", (Boolean) st[2] ? " R" : "",
                        pos.x, pos.z));
                if (qs && screenTick < 0) {
                    screenTick = tick;
                }
                boolean menuIn = stepTick(c, "menu ") >= 0;
                boolean pickNow = doPick && qs && (o.pick == Pick.EARLY || o.pick == Pick.ESCAPE || (o.pick == Pick.LATE && menuIn));
                if (pickNow) {
                    picked = true;
                    pickTick = tick;
                    atPick = pos;
                    c.ctx().runOnClient(mc -> {
                        Object screen = dev.testkit.compat.McCompat.screen(mc);
                        if (o.pick == Pick.ESCAPE) {
                            Mod.call(screen, "onClose");
                            return;
                        }
                        String[] spots = (String[]) Mod.call(screen, "spots");
                        for (int s = 0; s < 4; s++) {
                            if (MATE.equals(spots[s])) {
                                Mod.call(screen, "pick", s);
                            }
                        }
                        switch (o.act) { // his own action, read by this tick's handleKeybinds: the click's tick
                            case USE, LEAP2 -> KeyMapping.click((InputConstants.Key) Mod.field(mc.options.keyUse, "key"));
                            case ATTACK -> KeyMapping.click((InputConstants.Key) Mod.field(mc.options.keyAttack, "key"));
                            case SWAP -> KeyMapping.click((InputConstants.Key) Mod.field(mc.options.keyHotbarSlots[1], "key"));
                            default -> {
                            }
                        }
                        if (o.fastLeap) { // what Fast Leap asks of LeapManager on a left click: waits behind, one click only
                            Mod.staticCall(LM, "leap", MATE, true, false, false);
                        }
                    });
                }
                if (tpSeenTick < 0 && pos.distanceTo(new Vec3(BX + 0.5, Y, BZ + 0.5)) < 0.5) {
                    tpSeenTick = tick;
                }
                if (tpSeenTick >= 0 && tick - tpSeenTick >= 8) {
                    break;
                }
                if (!acts && i > 40) {
                    break;
                }
                if ((!o.menuComes || o.pick == Pick.ESCAPE || !o.mateInMenu) && i > 60) {
                    break;
                }
            }
            WireCapture.stop();
            List<List<String>> ticks = ticks();
            StringBuilder wire = new StringBuilder();
            for (int i = 0; i < ticks.size(); i++) {
                wire.append("\n  tick ").append(i).append(": ").append(String.join(", ", ticks.get(i)));
            }
            long menuTick = stepTick(c, "menu ");
            long clickTick = stepTick(c, "click ");
            long teleportTick = stepTick(c, "teleport");
            c.note(String.format(Locale.ROOT, "delay %d lag %d pick %s act %s: screen +%d, menu +%d, pick +%d, click +%d, "
                            + "teleport packet +%d, at B +%d (ActionGate ticks from the right click)", o.delay, o.lagMs, o.pick,
                    o.act, screenTick - t0, menuTick < 0 ? -1 : menuTick - t0, pickTick < 0 ? -1 : pickTick - t0,
                    clickTick < 0 ? -1 : clickTick - t0, teleportTick < 0 ? -1 : teleportTick - t0,
                    tpSeenTick < 0 ? -1 : tpSeenTick - t0));
            c.note("steps " + c.onClient(mc -> Mod.staticCall(LM, "stepLog")) + "\ntrace " + String.join(" | ", trace)
                    + "\nwire by client tick:" + wire);
            long uses = count(ticks, "use");
            long clicks = count(ticks, "click ");
            if (!acts) {
                c.check(screenTick < 0, "Queue Leap's screen opened with the feature off / on a legit jar");
                c.check(uses == 1, "uses on the wire: " + uses);
                c.check(realShown || !o.menuComes, "Hypixel's menu was not shown with Queue Leap off");
                return;
            }
            c.check(screenTick >= 0 && screenTick - t0 <= 1, "the Queue Leap screen opened at +" + (screenTick - t0));
            c.check(uses == 1, "want exactly one use_item, got " + uses);
            int useTickW = firstTick(ticks, "use");
            if (!o.menuComes) {
                c.check(clicks == 0, "a click went with no menu: " + clicks);
                c.check(ClickGuardCases.chatLines(mark, "the leap menu did not open") == 1, "no single 'did not open' line");
                c.check(c.onClient(mc -> !queueScreen(mc)), "the Queue Leap screen is still up");
                c.check(!c.onClient(mc -> (Boolean) Mod.staticCall(LM, "isBusy")), "LeapManager is still busy");
                c.check(firstTick(ticks.subList(useTickW + 1, ticks.size()), "input W") >= 0, "W never came back after the give-up");
                return;
            }
            if (o.pick == Pick.ESCAPE) {
                long closes = count(ticks, "close ");
                c.check(clicks == 0, "a click went after Escape: " + clicks);
                c.check(closes == 1, "want one close of the arriving menu, got " + closes);
                c.check(!realShown, "Hypixel's menu was shown after Escape");
                c.check(c.hx().call("menu.current").getAsJsonObject().get("containerId").getAsInt() == 0,
                        "the server still has a menu open");
                return;
            }
            if (!o.mateInMenu) {
                c.check(clicks == 0, "a click went for a missing target: " + clicks);
                c.check(ClickGuardCases.chatLines(mark, MATE + " is not in the leap menu") == 1, "no 'not in the leap menu' line");
                c.check(c.onClient(mc -> dev.testkit.compat.McCompat.screen(mc)
                        instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>), "the real menu is not shown");
                return;
            }
            c.check(clicks == 1, "want exactly one container click, got " + clicks);
            String click = ticks.stream().flatMap(List::stream).filter(p -> p.startsWith("click ")).findFirst().orElse("");
            c.check(click.equals("click " + c.onClient(mc -> 0) + " 11") || click.matches("click [1-9]\\d* 11"),
                    "the click is not on slot 11 of a real window: " + click);
            c.check(menuTick >= 0 && clickTick >= 0, "menu or click missing from the step log");
            long want = o.pick == Pick.LATE ? pickTick + 1 : menuTick + 1;
            c.check(clickTick == want, "the click went at +" + (clickTick - t0) + ", want +" + (want - t0)
                    + " (the first tick after the " + (o.pick == Pick.LATE ? "pick" : "menu") + ")");
            c.check(tpSeenTick >= 0, "he never reached B");
            // stood still: no W input from the tick after the use until the teleport; W again within 3 ticks of it
            int clickTickW = firstTick(ticks, "click ");
            int tpW = -1;
            for (int i = Math.max(0, clickTickW); i < ticks.size() && tpW < 0; i++) {
                if (ticks.get(i).contains("ServerboundAcceptTeleportationPacket") && i >= clickTickW) {
                    tpW = i;
                }
            }
            if (o.act != Act.ACCEPT) {
                c.check(tpW >= 0, "no teleport accept after the click");
                for (int i = useTickW + 1; i < Math.max(useTickW + 1, tpW); i++) {
                    c.check(!ticks.get(i).contains("input W"), "W went out in tick " + i + " while the leap was pending");
                }
                c.check(firstTick(ticks.subList(Math.max(0, tpW), Math.min(ticks.size(), tpW + 4)), "input W") >= 0,
                        "walking did not resume within 3 ticks of the teleport");
            }
            if (atPick != null) {
                Vec3 p = atPick;
                c.check(Math.abs(p.z - (AZ + 0.5)) < 0.6, String.format(Locale.ROOT, "he walked %.2f before the pick", p.z - AZ - 0.5));
            }
            switch (o.act) {
                case USE, LEAP2 -> c.check(uses == 1, "his second right click went out during the leap (use suppressed)");
                case ATTACK -> c.check(count(ticks, "ServerboundSwingPacket") + count(ticks, "ServerboundAttackPacket") >= 1,
                        "his attack never went (the same-tick action did not act)");
                case SWAP -> c.check(firstTick(ticks, "ServerboundSetCarriedItemPacket") >= 0, "his hotbar swap never went");
                case ACCEPT -> c.check(count(ticks, "ServerboundAcceptTeleportationPacket") >= 2, "the same-tick teleport was not accepted");
                default -> {
                }
            }
            if (o.act == Act.ATTACK || o.act == Act.SWAP) {
                String marker = o.act == Act.SWAP ? "ServerboundSetCarriedItemPacket" : "ServerboundSwingPacket";
                int actW = firstTick(ticks, marker);
                c.note(o.act + " went in client tick " + actW + ", the click in " + clickTickW);
                if (o.pick == Pick.LATE) {
                    c.check(actW == clickTickW, o.act + " did not land in the click's tick (" + actW + " vs " + clickTickW + ")");
                }
            }
        } finally {
            WireCapture.stop();
            WireCapture.describe = null;
            c.ctx().runOnClient(mc -> {
                KeyMapping.set((InputConstants.Key) Mod.field(mc.options.keyUp, "key"), false);
                Latency.remove();
                if (dev.testkit.compat.McCompat.screen(mc) != null) {
                    dev.testkit.compat.McCompat.setScreen(mc, null);
                }
                if (mc.player.containerMenu != mc.player.inventoryMenu) {
                    mc.player.closeContainer();
                }
                mc.player.getInventory().setSelectedSlot(0);
                Mod.staticCall(LM, "setCooldownTicksForTest", 0.0);
            });
            c.hx().call("menu.onUse", "id", "SPIRIT_LEAP", "menu", null);
            c.hx().call("give", "slot", 0, "stack", "minecraft:air");
            c.hx().sidebarClear();
            c.hx().tabClear();
            c.ctx().waitTicks(40);
        }
    }
}

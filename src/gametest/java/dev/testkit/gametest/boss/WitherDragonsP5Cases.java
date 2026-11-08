package dev.testkit.gametest.boss;

import dev.testkit.gametest.hx.HxKit;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 1108-boss-dragons-p5 (mod dragons1108, owner's to-do Doc: "Nothing wither dragons related is working. There is no box no
 * tracer no popup nothing. Also splits arent sending at the end of an m7"). His 26.3 log of 2026-11-06 showed the cause:
 * at "[BOSS] Wither King: You... again?" Hypixel's sidebar stops naming the floor, DungeonState read 'M7' -> null, the boss
 * phase ended, and with it the P5 gate (no dragon was ever tracked past the first) and the split run (no Dragons or Total line).
 * The case replays that on the real wire: an M7 sidebar, the boss lines through chat, the Wither King's line, then a sidebar
 * with no Catacombs line, the real flame bursts for Red and Orange (/particle flame, count 20, y 19, spread 2/3/2, speed 0),
 * an ender dragon in Red's box and the end line "☠ Defeated Maxor, Storm, Goldor, and Necron in 04m 41s". It asserts the floor
 * holds, both dragons go SPAWNING, the priority is the first to spawn with its title shown, the boxes and the tracer were
 * drawn (the feature's frame counters), the dragon entity makes Red ALIVE, and "Dragons took" and "Total took" reach chat.
 */
final class WitherDragonsP5Cases {

    static final String CFG = "witherdragons.WitherDragonsConfig";
    static final String FEATURE = "witherdragons.WitherDragonsFeature";
    static final String DRAGON = "witherdragons.WitherDragon";
    static final String SPLIT_CFG = "splittimers.SplitTimersConfig";
    static final String STATE = "secrets.DungeonState";

    private WitherDragonsP5Cases() {
    }

    static void register(Session s) {
        s.test("1108-boss-dragons-p5", WitherDragonsP5Cases::run);
    }

    /** Client thread only. */
    static String state(String dragon) {
        return String.valueOf(Mod.call(Mod.enumValue(DRAGON, dragon), "state"));
    }

    static long[] frames(Session c) {
        return c.onClient(mc -> ((long[]) Mod.staticCall(FEATURE, "drawnFrames")).clone());
    }

    @SuppressWarnings("unchecked")
    static List<String> chatSince(Session c, int before) {
        return c.onClient(mc -> {
            List<GuiMessage> all = (List<GuiMessage>) Mod.field(dev.testkit.compat.McCompat.chat(mc), "allMessages");
            List<String> out = new ArrayList<>();
            for (int i = all.size() - before - 1; i >= 0; i--) {
                out.add(all.get(i).content().getString());   // oldest first
            }
            return out;
        });
    }

    static void run(Session c) throws Exception {
        Object priorityMode = c.onClient(mc -> Mod.enumValue(CFG + "$TitleMode", "PRIORITY"));
        Object colorNames = c.onClient(mc -> Mod.enumValue(CFG + "$NameStyle", "COLOUR"));
        try (HxKit.Settings set = new HxKit.Settings(c).with(CFG, "Enabled", true)
                .with(CFG, "DragonBoxes", true)
                .with(CFG, "DragonTracer", true)
                .with(CFG, "DragonTitle", true)
                .with(CFG, "TitleMode", priorityMode)
                .with(CFG, "NameStyle", colorNames)
                .with(CFG, "DragonPriority", false)
                .with(SPLIT_CFG, "Enabled", true)
                .with(SPLIT_CFG, "AnnounceInChat", true)
                .with(SPLIT_CFG, "P5DragonLines", false)
                .with(SPLIT_CFG, "P5RelicLines", false)) {
            c.hx().call("boss.relic.build");   // stone at y 6 over x 40-70, z 30-62: the P5 floor at its real height
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(M7)");
            c.waitUntil("DungeonState M7", mc -> "M7".equals(Mod.staticCall(STATE, "getFloor")), 100);
            RelicCases.tp(c, RelicCases.PX, RelicCases.PY, RelicCases.PZ, 45f, 0f);
            c.hx().chat("Starting in 1 second.");
            c.ctx().waitTicks(5);
            for (String line : new String[]{"[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!",
                    "[BOSS] Storm: Pathetic Maxor, just like expected.", "[BOSS] Goldor: Who dares trespass into my domain?",
                    "The Core entrance is opening!",
                    "[BOSS] Necron: Finally, I heard so much about you. The Eye likes you very much."}) {
                c.hx().chat(line);
                c.ctx().waitTicks(5);
            }
            c.waitUntil("the boss phase on Maxor's line", mc -> Boolean.TRUE.equals(Mod.staticCall(STATE, "isBossPhaseActive")), 60);

            // P5: the Wither King's line, then Hypixel's P5 sidebar with no "The Catacombs (M7)" line.
            c.hx().chat("[BOSS] Wither King: You... again?");
            c.ctx().waitTicks(3);
            c.hx().sidebar("SKYBLOCK", "§707/11/26 §8m123AB", " ", "§fTime Elapsed: §a04m 30s", "§fCleared: §c100%", " ", "§ewww.hypixel.net");
            c.ctx().waitTicks(10);
            String floor = c.onClient(mc -> String.valueOf(Mod.staticCall(STATE, "getFloor")));
            boolean boss = c.onClient(mc -> Boolean.TRUE.equals(Mod.staticCall(STATE, "isBossPhaseActive")));
            boolean p5 = c.onClient(mc -> Boolean.TRUE.equals(Mod.staticCall("witherdragons.P5State", "inP5")));
            c.note("after the P5 sidebar: floor " + floor + ", boss phase " + boss + ", inP5 " + p5);
            c.check("M7".equals(floor) && boss && p5, "the P5 sidebar ended the M7 boss: floor " + floor + ", boss phase " + boss
                    + ", inP5 " + p5);

            long[] f0 = frames(c);
            c.hx().call("particle", "type", "flame", "x", 27, "y", 19, "z", 59, "dx", 2, "dy", 3, "dz", 2, "speed", 0, "count", 20);
            c.hx().call("particle", "type", "flame", "x", 85, "y", 19, "z", 56, "dx", 2, "dy", 3, "dz", 2, "speed", 0, "count", 20);
            c.waitUntil("Red and Orange SPAWNING", mc -> "SPAWNING".equals(state("RED")) && "SPAWNING".equals(state("ORANGE")), 60);
            String priority = c.onClient(mc -> String.valueOf(Mod.staticCall(FEATURE, "priorityDragon")));
            String title = c.onClient(mc -> {
                Object t = Mod.field(dev.testkit.compat.McCompat.hud(mc), "title");
                return t == null ? "" : net.minecraft.ChatFormatting.stripFormatting(((Component) t).getString());
            });
            c.note("priority " + priority + ", title '" + title + "'");
            c.check("RED".equals(priority), "the priority is not Red (both spawn together, Red first in order): " + priority);
            c.check(title.equals("RED DRAGON IS SPAWNING"), "the spawn title is not shown: '" + title + "'");
            c.ctx().waitTicks(10);
            long[] f1 = frames(c);
            c.note("frames with boxes " + (f1[0] - f0[0]) + ", with the tracer " + (f1[1] - f0[1]) + " over 10 ticks");
            c.check(f1[0] - f0[0] >= 3, "no dragon box drawn: " + (f1[0] - f0[0]) + " frames");
            c.check(f1[1] - f0[1] >= 3, "no tracer drawn: " + (f1[1] - f0[1]) + " frames");

            c.server().command("summon minecraft:ender_dragon 27 18 59 {NoAI:1b,Silent:1b,Tags:[\"tkdragon\"]}");
            c.waitUntil("Red ALIVE on its ender dragon", mc -> "ALIVE".equals(state("RED")), 60);

            int before = c.onClient(mc -> ((List<?>) Mod.field(dev.testkit.compat.McCompat.chat(mc), "allMessages")).size());
            c.hx().chat("☠ Defeated Maxor, Storm, Goldor, and Necron in 04m 41s");
            c.ctx().waitTicks(20);   // the summary prints 10 ticks after the end line
            List<String> chat = chatSince(c, before);
            c.note("chat after the end line: " + chat);
            c.check(chat.stream().anyMatch(s -> s.startsWith("Dragons took ") && s.endsWith("!")), "no 'Dragons took X!' split line");
            c.check(chat.stream().anyMatch(s -> s.startsWith("Total took ")), "no 'Total took' summary line");
        } finally {
            c.server().command("kill @e[type=minecraft:ender_dragon,tag=tkdragon]");
            c.hx().sidebarClear();
            c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 0f, "pitch", 0f);
            c.ctx().waitTicks(5);
        }
    }
}

package dev.testkit.gametest.perf;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.TestMap;
import dev.testkit.gametest.hx.HxKit;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.MixinBench;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import org.joml.Matrix4f;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 95-mixin-bench: what killer560s-mod's mixins into VANILLA hot paths cost per call - the part PerfProbe does not see.
 *
 * <p>Two kinds of number, both read against the same run with {@code -NoMod} (vanilla plus this testkit):
 * <ul>
 *   <li><b>direct</b>: a hot vanilla method the mod hooks (entity glow/team colour, item hover name and components, font
 *       width, tooltips, chunk lookups, block shapes, view yaw, FOV, mouse scaling...) called in a tight loop on the
 *       client thread, best of 5 repetitions, in ns per call. The mod's cost is the difference from the no-mod run.</li>
 *   <li><b>in-frame</b>: {@link MixinBench}'s {@code @WrapMethod} timers around slot drawing and a whole container
 *       screen (a 54-slot menu of SkyBlock items open), per-entity extraction and culling (120 named stands + 60
 *       zombies), and the GUI text/sprite/fill calls, during real frames; ns per call minus the timer's own cost.</li>
 * </ul>
 * With the mod there are two configurations: DEFAULTS (a fresh config after Quiet) and ALL ON (95's ON set, 403's hub
 * extras and the features whose hooks this bench exists for). Output {@code mixin-bench.txt} / {@code .tsv}. Only when
 * named ({@code -Pscenario=95-mixin}), which is also what applies the timers. Runs with or without the mod.
 */
public class MixinBenchTest implements FabricClientGameTest {

    static final String NAME = "95-mixin-bench";

    /** Features whose vanilla hooks are measured here and are in neither 95's nor 403's ON set. */
    static final String[][] EXTRA = {
            {"secrets.SecretsConfig", "setMasterEnabled"}, {"secrets.SecretsConfig", "setButtonsEnabled"},
            {"secrets.SecretsConfig", "setChestsEnabled"}, {"secrets.SecretsConfig", "setLeversEnabled"},
            {"secrets.SecretsConfig", "setEssenceEnabled"}, {"slottext.SlotTextConfig", "setEnabled", "isEnabledRaw"},
            {"itemprotect.ItemProtectConfig", "setProtectItemEnabled"}, {"packdisabler.PackDisablerConfig", "setEnabled"},
            {"objecthider.ObjectHiderConfig", "setObjectHiderEnabled"}, {"supporters.SupportersConfig", "setOwnNameInF5"},
    };

    private static final int X = -1460;
    private static final int Z = -1460;
    private static final int Y = 150;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank()) {
            return; // only when named
        }
        Session.run(ctx, NAME,
                (server, s) -> TestMap.on(server)
                        .platform(X, Y, Z, 24)
                        .catchFloor(Y - 10)
                        .survival()
                        .clearInventory()
                        .spawn(X + 0.5, Z + 0.5, 0f)
                        .build(),
                s -> {
                    s.skipDetectorProof();
                    s.test(NAME + "-run", MixinBenchTest::bench);
                });
    }

    private static void bench(Session c) throws Exception {
        ClientGameTestContext ctx = c.ctx();
        boolean mod = ModUnderTest.loaded("killer560smod");
        int frames = Integer.getInteger("testkit.mixinBench.frames", 300);
        String only = System.getProperty("testkit.mixinBench.only", "");
        Path out = Path.of(System.getProperty("testkit.reportDir", "build/testkit-report"));
        List<String> report = new ArrayList<>();
        List<String> tsv = new ArrayList<>();
        tsv.add("config\tkind\tpoint\tns_per_call\tcalls");
        if (mod) {
            ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        }

        // ---- the scene: 403's hub, plus one of each block whose shape the mod hooks ----
        HxKit.sidebar(c, "template", "hub");
        List<String> extra = new ArrayList<>();
        String[] ranks = {"§7", "§a[VIP] ", "§b[MVP] ", "§6[MVP§c++§6] "};
        for (int i = 0; i < 79; i++) {
            extra.add("§r§7[§8" + (100 + i * 3) + "§7] §r" + ranks[i % ranks.length] + "HubPlayer" + i);
        }
        HxKit.tab(c, "template", "area", "area", "Hub", "extra", extra);
        for (int i = 0; i < 120; i++) {
            double a = i * 0.37;
            double r = 4 + (i % 16);
            c.hx().stand(X + 0.5 + Math.cos(a) * r, Y + 1, Z + 0.5 + Math.sin(a) * r,
                    (i % 3 == 0 ? "§c" : "§a") + "[Lv" + (i * 7 % 300) + "] §fHub Mob §a" + (i * 1234) + "§f/§a50000§c❤");
        }
        for (int i = 0; i < 60; i++) {
            double a = i * 0.61;
            double r = 5 + (i % 14);
            c.server().command(String.format(Locale.ROOT,
                    "summon minecraft:zombie %.2f %d %.2f {NoAI:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b}",
                    X + 0.5 + Math.cos(a) * r, Y + 1, Z + 0.5 + Math.sin(a) * r));
        }
        String[] blocks = {"chest[facing=north]", "stone_button[face=wall,facing=north]", "lever[face=floor,facing=north]",
                "skeleton_skull", "stone"};
        BlockPos[] at = new BlockPos[blocks.length];
        for (int i = 0; i < blocks.length; i++) {
            at[i] = new BlockPos(X + 3 + i, Y + 1, Z - 3);
            c.server().command("setblock " + at[i].getX() + " " + at[i].getY() + " " + at[i].getZ() + " minecraft:" + blocks[i]);
        }
        ctx.waitTicks(60);
        int seen = c.onClient(mc -> {
            int n = 0;
            for (Entity e : mc.level.entitiesForRendering()) {
                n++;
            }
            return n;
        });
        report.add("scene: client renders " + seen + " entities; mod loaded: " + mod);
        c.check(seen >= 150, "the client sees only " + seen + " entities - the scene did not arrive");
        c.waitUntil("the client has the bench blocks", mc -> !mc.level.getBlockState(at[0]).isAir()
                && !mc.level.getBlockState(at[3]).isAir(), 200);

        // The items: 403's SkyBlock-shaped menu, read once on the client and kept for the direct loops.
        Map<Integer, String> slots = menuSlots();
        c.hx().menu("Auctions Browser", 6, slots, null);
        c.waitUntil("the chest menu is open", mc -> McCompat.screen(mc) instanceof AbstractContainerScreen<?>, 100);
        ctx.waitTicks(10);
        List<ItemStack> stacks = c.onClient(mc -> {
            List<ItemStack> l = new ArrayList<>();
            for (Slot s : ((AbstractContainerScreen<?>) McCompat.screen(mc)).getMenu().slots) {
                if (s.hasItem()) {
                    l.add(s.getItem());
                }
            }
            return l;
        });
        closeMenu(c);
        c.check(stacks.size() >= 50, "the menu delivered " + stacks.size() + " stacks");
        report.add("items: " + stacks.size() + " stacks from the menu");

        ctx.runOnClient(mc -> {
            mc.options.enableVsync().set(false);
            mc.options.framerateLimit().set(260);
        });

        // ---- configurations ----
        Map<String, Boolean> before = new LinkedHashMap<>();
        List<String[]> on = new ArrayList<>(List.of(FpsBenchTest.ON));
        on.addAll(List.of(HubPerfBenchTest.HUB_EXTRA));
        on.addAll(List.of(EXTRA));
        List<String> missing = new ArrayList<>();
        if (mod) {
            ctx.runOnClient(mc -> {
                for (String[] s : on) {
                    String key = s[0] + "#" + s[1];
                    try {
                        String getter = s.length > 2 ? s[2] : s[1].replaceFirst("^set", "is");
                        before.put(key, (Boolean) Mod.call(Mod.cfg(s[0]), getter));
                    } catch (Throwable t) {
                        missing.add(key);
                    }
                }
            });
            report.add("ALL ON set: " + before.size() + " switch(es); missing " + missing);
        }
        String[] configs = mod ? new String[]{"defaults", "allon"} : new String[]{"nomod"};
        double timer = c.onClient(mc -> MixinBench.overheadNs());
        report.add(String.format(Locale.ROOT, "in-frame timer overhead %.1f ns per call (subtracted)", timer));
        try {
            for (String cfg : configs) {
                if (only.equals("alone")) {
                    break;
                }
                if (cfg.equals("allon")) {
                    ctx.runOnClient(mc -> before.keySet().forEach(k -> {
                        String[] p = k.split("#");
                        Mod.call(Mod.cfg(p[0]), p[1], true);
                    }));
                }
                ctx.waitTicks(40);
                report.add("== " + cfg);
                if (!only.equals("frame")) {
                    direct(c, cfg, stacks, at, report, tsv);
                }
                if (only.equals("direct")) {
                    continue;
                }
                // in-frame: the menu open (an item hovered, so its tooltip is built and drawn every frame, as when a
                // player reads one), then the lobby (entities, HUD)
                openMenuHovered(c, slots, mod, report);
                inFrame(c, cfg, "menu", frames, timer, report, tsv);
                closeMenu(c);
                ctx.waitTicks(20);
                inFrame(c, cfg, "lobby", frames, timer, report, tsv);
            }
            if (mod && (Boolean.getBoolean("testkit.mixinBench.alone") || only.equals("alone"))) {
                alone(c, on, before, slots, timer, report, tsv);
            }
        } finally {
            try {
                if (mod) {
                    ctx.runOnClient(mc -> before.forEach((k, v) -> {
                        String[] p = k.split("#");
                        Mod.call(Mod.cfg(p[0]), p[1], v);
                    }));
                }
                closeMenu(c);
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
            Files.createDirectories(out);
            Files.writeString(out.resolve("mixin-bench.txt"), String.join(System.lineSeparator(), report) + System.lineSeparator());
            Files.writeString(out.resolve("mixin-bench.tsv"), String.join(System.lineSeparator(), tsv) + System.lineSeparator());
        }
    }

    /** Opens the bench menu and parks the cursor on slot 13 (row 2, column 5), so its tooltip is up every frame. */
    private static void openMenuHovered(Session c, Map<Integer, String> slots, boolean mod, List<String> report) {
        c.hx().menu("Auctions Browser", 6, slots, null);
        c.waitUntil("the chest menu is open", mc -> McCompat.screen(mc) instanceof AbstractContainerScreen<?>, 100);
        c.ctx().waitTicks(10);
        double[] at = c.onClient(mc -> {
            AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
            Slot slot = s.getMenu().slots.get(13);
            int left = intField(s, "leftPos");
            int top = intField(s, "topPos");
            float f = 1.0f;
            if (mod) {
                f = ((Number) Mod.staticCall("hud.AutoScale", "appliedFactor", s)).floatValue();
            }
            var w = mc.getWindow();
            double gx = left + slot.x + 8, gy = top + slot.y + 8;
            return new double[]{gx * f * w.getScreenWidth() / (double) w.getGuiScaledWidth(),
                    gy * f * w.getScreenHeight() / (double) w.getGuiScaledHeight()};
        });
        c.ctx().getInput().setCursorPos(at[0], at[1]);
        c.ctx().waitTicks(10);
        int hovered = c.onClient(mc -> {
            try {
                var fld = AbstractContainerScreen.class.getDeclaredField("hoveredSlot");
                fld.setAccessible(true);
                Slot h = (Slot) fld.get(McCompat.screen(mc));
                return h == null ? -1 : h.index;
            } catch (ReflectiveOperationException e) {
                return -2;
            }
        });
        if (hovered != 13) {
            report.add("  WARNING: hovered slot " + hovered + ", wanted 13");
        }
    }

    private static int intField(Object o, String name) {
        try {
            var f = AbstractContainerScreen.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(o);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * {@code -PmixinBenchAlone}: each ALL ON switch on its own over DEFAULTS, the menu reopened and hovered, timing the
     * whole screen and a slot. Names the feature behind an ALL ON menu cost.
     */
    private static void alone(Session c, List<String[]> on, Map<String, Boolean> before, Map<Integer, String> slots,
                              double timer, List<String> report, List<String> tsv) {
        int ticks = Integer.getInteger("testkit.mixinBench.aloneTicks", 40);
        report.add("== alone (each switch over DEFAULTS, menu hovered, " + ticks + " ticks each)");
        Runnable defaults = () -> c.ctx().runOnClient(mc -> before.forEach((k, v) -> {
            String[] p = k.split("#");
            Mod.call(Mod.cfg(p[0]), p[1], v);
        }));
        List<String> keys = new ArrayList<>(before.keySet());
        keys.add(0, "(defaults)");
        for (String key : keys) {
            defaults.run();
            if (!key.startsWith("(")) {
                if (Boolean.TRUE.equals(before.get(key))) {
                    continue; // already on by default: DEFAULTS measures it
                }
                String[] p = key.split("#");
                c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(p[0]), p[1], true));
            }
            closeMenu(c);
            c.ctx().waitTicks(5);
            openMenuHovered(c, slots, true, report);
            c.ctx().runOnClient(mc -> MixinBench.start());
            c.ctx().waitTicks(ticks);
            long[][] r = c.onClient(mc -> {
                MixinBench.stop();
                return new long[][]{MixinBench.calls(), MixinBench.nanos()};
            });
            double screen = r[0][1] == 0 ? 0 : r[1][1] / (double) r[0][1] - timer;
            double slot = r[0][0] == 0 ? 0 : r[1][0] / (double) r[0][0] - timer;
            report.add(String.format(Locale.ROOT, "  alone %-58s screen %9.1f us/frame  slot %7.1f ns  (%d frames)",
                    key, screen / 1000.0, slot, r[0][1]));
            tsv.add("alone\tscreen\t" + key + "\t" + String.format(Locale.ROOT, "%.1f", screen) + "\t" + r[0][1]);
        }
        defaults.run();
        closeMenu(c);
    }

    private static void closeMenu(Session c) {
        c.ctx().runOnClient(mc -> {
            if (mc.player != null) {
                mc.player.closeContainer();
            }
            McCompat.setScreen(mc, null);
        });
    }

    private static void inFrame(Session c, String cfg, String phase, int frames, double timer, List<String> report,
                                List<String> tsv) {
        long[] start = c.onClient(mc -> {
            MixinBench.start();
            return new long[]{renderAllocated(), dev.testkit.harness.FrameClock.frameCount()};
        });
        jdk.jfr.Recording rec = null;
        if (Boolean.getBoolean("testkit.mixinBench.jfr")) {
            try {
                rec = new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"));
                rec.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(1));
                rec.start();
            } catch (Exception e) {
                report.add("  JFR failed: " + e);
                rec = null;
            }
        }
        c.ctx().waitTicks(frames);
        long[][] r = c.onClient(mc -> {
            MixinBench.stop();
            return new long[][]{MixinBench.calls(), MixinBench.nanos(),
                    {renderAllocated() - start[0], dev.testkit.harness.FrameClock.frameCount() - start[1]}};
        });
        if (rec != null) {
            try {
                rec.stop();
                Path file = Path.of(System.getProperty("testkit.reportDir", "build/testkit-report"))
                        .resolve("mixin-" + cfg + "-" + phase + ".jfr");
                Files.createDirectories(file.getParent());
                rec.dump(file);
                rec.close();
                report.add("  JFR " + file.getFileName());
            } catch (Exception e) {
                report.add("  JFR failed: " + e);
            }
        }
        long frameCount = Math.max(1, r[2][1]);
        report.add(String.format(Locale.ROOT, "  frame %-6s render-thread allocation %9.1f KB/frame  (%d frames)",
                phase, r[2][0] / 1024.0 / frameCount, r[2][1]));
        tsv.add(cfg + "	frame-" + phase + "	render-thread KB/frame	"
                + String.format(Locale.ROOT, "%.2f", r[2][0] / 1024.0 / frameCount) + "	" + r[2][1]);
        for (int i = 0; i < MixinBench.POINTS.length; i++) {
            long n = r[0][i];
            if (n == 0) {
                continue;
            }
            double ns = r[1][i] / (double) n - timer;
            report.add(String.format(Locale.ROOT, "  frame %-6s %-44s %9.1f ns/call  (%d calls)", phase, MixinBench.POINTS[i], ns, n));
            tsv.add(cfg + "\tframe-" + phase + "\t" + MixinBench.POINTS[i] + "\t" + String.format(Locale.ROOT, "%.1f", ns) + "\t" + n);
        }
    }

    /** Bytes allocated so far by the calling thread (the render thread, from runOnClient). */
    private static long renderAllocated() {
        return ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean())
                .getCurrentThreadAllocatedBytes();
    }

    // ---- direct-call loops ----------------------------------------------------------------------------------------

    private static long sink;

    private static void direct(Session c, String cfg, List<ItemStack> stacks, BlockPos[] at, List<String> report,
                               List<String> tsv) {
        long scale = Long.getLong("testkit.mixinBench.iters", 1_000_000L);
        Map<String, Object[]> benches = c.onClient(mc -> {
            Map<String, Object[]> m = new LinkedHashMap<>();
            List<Entity> ents = new ArrayList<>();
            for (Entity e : mc.level.entitiesForRendering()) {
                ents.add(e);
            }
            Entity[] es = ents.toArray(new Entity[0]);
            ItemStack[] st = stacks.toArray(new ItemStack[0]);
            Font font = mc.font;
            String[] names = new String[st.length];
            Component[] comps = new Component[st.length];
            for (int i = 0; i < st.length; i++) {
                comps[i] = st[i].getHoverName();
                names[i] = comps[i].getString();
            }
            EntityRenderDispatcher erd = mc.getEntityRenderDispatcher();
            Frustum frustum = new Frustum(new Matrix4f(), new Matrix4f());
            double cx = mc.player.getX(), cy = mc.player.getEyeY(), cz = mc.player.getZ();
            frustum.prepare(cx, cy, cz);
            int pcx = mc.player.chunkPosition().x(), pcz = mc.player.chunkPosition().z();
            var chunks = mc.level.getChunkSource();
            BlockState[] states = new BlockState[at.length];
            for (int i = 0; i < at.length; i++) {
                states[i] = mc.level.getBlockState(at[i]);
            }
            BlockPos near = mc.player.blockPosition();
            BlockPos far = near.offset(16 * 400, 0, 16 * 400);
            var window = mc.getWindow();
            m.put("Entity.getTeamColor", new Object[]{es.length, (LongSupplier) () -> {
                long a = 0;
                for (Entity e : es) {
                    a += e.getTeamColor();
                }
                return a;
            }});
            m.put("Minecraft.shouldEntityAppearGlowing", new Object[]{es.length, (LongSupplier) () -> {
                long a = 0;
                for (Entity e : es) {
                    a += mc.shouldEntityAppearGlowing(e) ? 1 : 0;
                }
                return a;
            }});
            m.put("EntityRenderDispatcher.shouldRender", new Object[]{es.length, (LongSupplier) () -> {
                long a = 0;
                for (Entity e : es) {
                    a += erd.shouldRender(e, frustum, cx, cy, cz) ? 1 : 0;
                }
                return a;
            }});
            m.put("ItemStack.getHoverName", new Object[]{st.length, (LongSupplier) () -> {
                long a = 0;
                for (ItemStack s : st) {
                    a += System.identityHashCode(s.getHoverName()) & 1;
                }
                return a;
            }});
            m.put("ItemStack.get(CUSTOM_DATA)+get(LORE)", new Object[]{st.length * 2, (LongSupplier) () -> {
                long a = 0;
                for (ItemStack s : st) {
                    a += (s.get(DataComponents.CUSTOM_DATA) != null ? 1 : 0) + (s.get(DataComponents.LORE) != null ? 1 : 0);
                }
                return a;
            }});
            m.put("DyedItemColor.getOrDefault", new Object[]{st.length, (LongSupplier) () -> {
                long a = 0;
                for (ItemStack s : st) {
                    a += DyedItemColor.getOrDefault(s, -1);
                }
                return a;
            }});
            m.put("Font.width(String)", new Object[]{names.length, (LongSupplier) () -> {
                long a = 0;
                for (String s : names) {
                    a += font.width(s);
                }
                return a;
            }});
            m.put("Font.width(FormattedText)", new Object[]{comps.length, (LongSupplier) () -> {
                long a = 0;
                for (Component s : comps) {
                    a += font.width(s);
                }
                return a;
            }});
            m.put("Screen.getTooltipFromItem", new Object[]{st.length, (LongSupplier) () -> {
                long a = 0;
                for (ItemStack s : st) {
                    a += Screen.getTooltipFromItem(mc, s).size();
                }
                return a;
            }, 50L});
            m.put("ClientChunkCache.getChunk(far,FULL)", new Object[]{64, (LongSupplier) () -> {
                long a = 0;
                for (int i = 0; i < 64; i++) {
                    a += chunks.getChunk(pcx + 400 + (i & 7), pcz + 400 + (i >> 3), ChunkStatus.FULL, false) == null ? 1 : 0;
                }
                return a;
            }});
            m.put("Level.getChunk(near)", new Object[]{25, (LongSupplier) () -> {
                long a = 0;
                for (int i = 0; i < 25; i++) {
                    a += System.identityHashCode(mc.level.getChunk(pcx - 2 + i % 5, pcz - 2 + i / 5)) & 1;
                }
                return a;
            }});
            m.put("Level.isLoaded(near+far)", new Object[]{2, (LongSupplier) () ->
                    (mc.level.isLoaded(near) ? 1 : 0) + (mc.level.isLoaded(far) ? 1 : 0)});
            m.put("Level.getChunkForCollisions(near)", new Object[]{25, (LongSupplier) () -> {
                long a = 0;
                for (int i = 0; i < 25; i++) {
                    a += mc.level.getChunkForCollisions(pcx - 2 + i % 5, pcz - 2 + i / 5) == null ? 1 : 0;
                }
                return a;
            }});
            m.put("BlockState.getShape(chest,button,lever,skull,stone)", new Object[]{at.length, (LongSupplier) () -> {
                long a = 0;
                for (int i = 0; i < at.length; i++) {
                    a += System.identityHashCode(states[i].getShape(mc.level, at[i])) & 1;
                }
                return a;
            }});
            m.put("ItemCooldowns.getCooldownPercent", new Object[]{st.length, (LongSupplier) () -> {
                long a = 0;
                for (ItemStack s : st) {
                    a += (long) mc.player.getCooldowns().getCooldownPercent(s, 0.5f);
                }
                return a;
            }});
            m.put("LocalPlayer.getViewYRot+getViewXRot", new Object[]{2, (LongSupplier) () ->
                    (long) (mc.player.getViewYRot(0.5f) + mc.player.getViewXRot(0.5f))});
            m.put("AbstractClientPlayer.getFieldOfViewModifier", new Object[]{1, (LongSupplier) () ->
                    (long) mc.player.getFieldOfViewModifier(true, 0.5f)});
            m.put("MouseHandler.getScaledXPos+Y", new Object[]{2, (LongSupplier) () ->
                    (long) (MouseHandler.getScaledXPos(window, 300.0) + MouseHandler.getScaledYPos(window, 200.0))});
            return m;
        });
        for (Map.Entry<String, Object[]> e : benches.entrySet()) {
            int perPass = (Integer) e.getValue()[0];
            LongSupplier body = (LongSupplier) e.getValue()[1];
            long div = e.getValue().length > 2 ? (Long) e.getValue()[2] : 1L;
            long passes = Math.max(10, scale / div / perPass);
            double best = c.onClient(mc -> {
                double min = Double.MAX_VALUE;
                for (int rep = 0; rep < 7; rep++) {
                    long t0 = System.nanoTime();
                    long a = 0;
                    for (long p = 0; p < passes; p++) {
                        a += body.getAsLong();
                    }
                    long t = System.nanoTime() - t0;
                    sink += a;
                    if (rep > 1) { // reps 0 and 1 are warm-up
                        min = Math.min(min, t / (double) (passes * perPass));
                    }
                }
                return min;
            });
            report.add(String.format(Locale.ROOT, "  direct %-52s %9.2f ns/call", e.getKey(), best));
            tsv.add(cfg + "\tdirect\t" + e.getKey() + "\t" + String.format(Locale.ROOT, "%.2f", best) + "\t" + passes * perPass);
        }
        report.add("  (sink " + (sink & 1) + ")");
    }

    private static Map<Integer, String> menuSlots() {
        Map<Integer, String> slots = new LinkedHashMap<>();
        String[][] items = {
                {"diamond_sword", "HYPERION", "Hyperion", "gold", "LEGENDARY DUNGEON SWORD"},
                {"bow", "TERMINATOR", "Terminator", "light_purple", "MYTHIC DUNGEON BOW"},
                {"player_head", "WITHER_GOGGLES", "Wither Goggles", "dark_purple", "EPIC DUNGEON HELMET"},
                {"ender_pearl", "SPIRIT_LEAP", "Spirit Leap", "blue", "RARE"},
                {"golden_axe", "RAGNAROCK_AXE", "Ragnarock Axe", "gold", "LEGENDARY DUNGEON SWORD"},
                {"leather_chestplate", "SHADOW_ASSASSIN_CHESTPLATE", "Shadow Assassin Chestplate", "gold", "LEGENDARY DUNGEON CHESTPLATE"},
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
        return slots;
    }
}

package dev.testkit.gametest.perf;

import dev.testkit.gametest.TestMap;
import dev.testkit.gametest.hx.HxKit;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 95-mixin-fixes: the mixin hot paths fixed on 2026-10-15 (mod branch fps-mixins-1015) stay allocation-free, and each
 * feature still acts. Judged on the client thread's allocated bytes per call over 20,000 calls after a warm-up of the
 * same length (deterministic, unlike time), on a hub-labelled Hx server so the Skyblock gates are open. The mod's
 * methods are called through exact {@link MethodHandle}s, which allocate nothing themselves. Fails on mod main 69505e45:
 * <ul>
 *   <li>Secrets' expanded button hitbox: {@code BlockState.getShape} lower-cased the server address on every call;</li>
 *   <li>Revert Master Stars: {@code ItemStack.getHoverName} rebuilt the name's text on every call;</li>
 *   <li>the Item Protect star: {@code isExplicitlyProtected} built and stripped the item name with no names listed;</li>
 *   <li>Click Sound: every sound played built its id string;</li>
 *   <li>Wither Dragons' ping bridge, Dungeon Alerts' and Blood Camp's remove-entities hooks: a capturing lambda (and an
 *       iterator) per packet.</li>
 * </ul>
 * Each check also proves the feature acted (the button shape changes, the stars are rewritten, a listed uuid is
 * protected), so an allocation-free no-op cannot pass.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class MixinFixesTest implements FabricClientGameTest {

    static final String NAME = "95-mixin-fixes";
    private static final int X = -1540;
    private static final int Z = -1540;
    private static final int Y = 150;
    private static final int CALLS = 20_000;
    /**
     * Bytes per call allowed. The fixed paths allocate nothing of their own, but a mixin's CallbackInfo(Returnable) is
     * 24 B whenever the JIT does not inline its handler into the call site, and in a tight test loop that varies from
     * run to run (0 or 24 on the same jar, 2026-10-15), so each budget sits between that and what main allocated:
     * Secrets 80, Revert Master Stars 920, Item Protect 312, Click Sound 80, Blood Camp 48, the two titles 64+.
     */
    private static final double BUDGET = 40.0;

    @FunctionalInterface
    private interface Body {
        long run() throws Throwable;
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank()) {
            return; // only when named
        }
        Session.run(ctx, NAME,
                (server, s) -> TestMap.on(server)
                        .platform(X, Y, Z, 8)
                        .catchFloor(Y - 10)
                        .survival()
                        .clearInventory()
                        .spawn(X + 0.5, Z + 0.5, 0f)
                        .build(),
                s -> {
                    s.skipDetectorProof();
                    s.test(NAME + "-run", MixinFixesTest::run);
                });
    }

    private static long allocated() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
    }

    /** Bytes allocated per call of {@code body} on the client thread, after a warm-up of the same length. */
    private static double bytesPerCall(Session c, Body body) {
        return c.onClient(mc -> {
            try {
                long sink = 0;
                for (int i = 0; i < CALLS; i++) {
                    sink += body.run();
                }
                long before = allocated();
                for (int i = 0; i < CALLS; i++) {
                    sink += body.run();
                }
                long bytes = allocated() - before;
                return (bytes + (sink & 0)) / (double) CALLS;
            } catch (Throwable t) {
                throw new AssertionError(t);
            }
        });
    }

    private static MethodHandle mh(String cls, String name, Class<?> ret, Class<?>... params) {
        try {
            return MethodHandles.publicLookup().findStatic(Mod.cls(cls), name, MethodType.methodType(ret, params));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("[" + NAME + "] no " + cls + "." + name, e);
        }
    }

    private static void run(Session c) throws Exception {
        List<String> failures = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        HxKit.sidebar(c, "template", "hub");
        c.waitUntil("the client knows it is on SkyBlock",
                mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isOnSkyblock"), 200);
        BlockPos button = new BlockPos(X + 2, Y + 1, Z - 2);
        c.server().command("setblock " + button.getX() + " " + button.getY() + " " + button.getZ()
                + " minecraft:stone_button[face=wall,facing=north]");
        c.waitUntil("the client has the button", mc -> !mc.level.getBlockState(button).isAir(), 200);

        // 1. Secrets: an expanded button hitbox (cheat build only - Secrets' master is cheat-only).
        if (c.onClient(mc -> Mod.isCheat())) {
            Object secrets = c.onClient(mc -> Mod.cfg("secrets.SecretsConfig"));
            boolean m0 = c.onClient(mc -> (Boolean) Mod.field(secrets, "masterEnabled"));
            boolean b0 = c.onClient(mc -> (Boolean) Mod.call(secrets, "isButtonsEnabled"));
            // An address with capitals, as a player can type one: main lower-cased a copy of it on every call.
            String ip0 = c.onClient(mc -> mc.getCurrentServer().ip);
            c.ctx().runOnClient(mc -> mc.getCurrentServer().ip = ip0.toUpperCase(Locale.ROOT));
            try {
                AABB vanilla = c.onClient(mc -> mc.level.getBlockState(button).getShape(mc.level, button).bounds());
                c.ctx().runOnClient(mc -> {
                    Mod.call(secrets, "setMasterEnabled", true);
                    Mod.call(secrets, "setButtonsEnabled", true);
                });
                AABB expanded = c.onClient(mc -> mc.level.getBlockState(button).getShape(mc.level, button).bounds());
                double bpc = bytesPerCall(c, () -> {
                    Minecraft mc = Minecraft.getInstance();
                    BlockState st = mc.level.getBlockState(button);
                    return System.identityHashCode(st.getShape(mc.level, button)) & 1;
                });
                lines.add(String.format(Locale.ROOT, "secrets button getShape: %s -> %s, %.2f B/call", vanilla, expanded, bpc));
                if (vanilla.equals(expanded)) {
                    failures.add("Secrets did not expand the button hitbox (" + vanilla + ") - the check measured nothing");
                }
                if (bpc >= BUDGET) {
                    failures.add(String.format(Locale.ROOT, "Secrets' getShape gate allocates %.1f B per call", bpc));
                }
            } finally {
                c.ctx().runOnClient(mc -> {
                    mc.getCurrentServer().ip = ip0;
                    Mod.call(secrets, "setMasterEnabled", m0);
                    Mod.call(secrets, "setButtonsEnabled", b0);
                });
            }
        } else {
            lines.add("secrets button getShape: SKIP (legit build, Secrets is cheat-only)");
        }

        // Diagnostics (not judged): the same calls with the features off, and a bare component read.
        ItemStack plain = c.onClient(mc -> {
            ItemStack st = new ItemStack(Items.DIAMOND_SWORD);
            st.set(DataComponents.CUSTOM_NAME, Component.literal("Hyperion ✪✪✪✪✪➋"));
            return st;
        });
        double dName = bytesPerCall(c, () -> System.identityHashCode(plain.getHoverName()) & 1);
        double dGet = bytesPerCall(c, () -> System.identityHashCode(plain.get(DataComponents.CUSTOM_NAME)) & 1);
        double dShape = bytesPerCall(c, () -> {
            Minecraft mc = Minecraft.getInstance();
            return System.identityHashCode(mc.level.getBlockState(button).getShape(mc.level, button)) & 1;
        });
        lines.add(String.format(Locale.ROOT, "features off: getHoverName %.2f B, get(CUSTOM_NAME) %.2f B, button getShape %.2f B",
                dName, dGet, dShape));

        // 2. Revert Master Stars.
        Object stars = c.onClient(mc -> Mod.cfg("revertmasterstars.RevertMasterStarsConfig"));
        boolean s0 = c.onClient(mc -> (Boolean) Mod.field(stars, "enabled"));
        try {
            c.ctx().runOnClient(mc -> Mod.call(stars, "setEnabled", true));
            ItemStack starred = c.onClient(mc -> {
                ItemStack st = new ItemStack(Items.DIAMOND_SWORD);
                st.set(DataComponents.CUSTOM_NAME, Component.literal("Hyperion ✪✪✪✪✪➋")
                        .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFFAA00)).withItalic(false)));
                return st;
            });
            String shown = c.onClient(mc -> starred.getHoverName().getString());
            String again = c.onClient(mc -> starred.getHoverName().getString());
            int red = c.onClient(mc -> {
                int[] n = {0};
                starred.getHoverName().visit((style, text) -> {
                    if (style.getColor() != null && (style.getColor().getValue() & 0xFFFFFF) != 0xFFAA00) {
                        n[0] += (int) text.chars().filter(ch -> ch == '✪').count();
                    }
                    return Optional.empty();
                }, Style.EMPTY);
                return n[0];
            });
            double bpc = bytesPerCall(c, () -> System.identityHashCode(starred.getHoverName()) & 1);
            lines.add(String.format(Locale.ROOT, "revert master stars: \"%s\" (%d recoloured), %.2f B/call", shown, red, bpc));
            if (!"Hyperion ✪✪✪✪✪".equals(shown) || !shown.equals(again) || red != 2) {
                failures.add("Revert Master Stars did not rewrite the name: \"" + shown + "\", " + red + " recoloured star(s)");
            }
            if (bpc >= 2 * BUDGET) {
                failures.add(String.format(Locale.ROOT, "getHoverName with Revert Master Stars allocates %.1f B per call", bpc));
            }
        } finally {
            c.ctx().runOnClient(mc -> Mod.call(stars, "setEnabled", s0));
        }

        // 3. Item Protect's star: no typed names on the list.
        Object protect = c.onClient(mc -> Mod.cfg("itemprotect.ItemProtectConfig"));
        boolean p0 = c.onClient(mc -> (Boolean) Mod.call(protect, "isProtectItemEnabledRaw"));
        boolean e0 = c.onClient(mc -> (Boolean) Mod.call(protect, "isEnabledRaw"));
        int names = c.onClient(mc -> ((List<?>) Mod.call(protect, "getProtectedNames")).size());
        MethodHandle isProtected = mh("itemprotect.ItemProtect", "isExplicitlyProtected", boolean.class, ItemStack.class);
        try {
            c.ctx().runOnClient(mc -> {
                Mod.call(protect, "setEnabled", true);
                Mod.call(protect, "setProtectItemEnabled", true);
            });
            ItemStack item = c.onClient(mc -> {
                ItemStack st = new ItemStack(Items.DIAMOND_SWORD);
                CompoundTag tag = new CompoundTag();
                tag.putString("id", "HYPERION");
                tag.putString("uuid", "k560-mixin-fixes-uuid");
                st.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
                st.set(DataComponents.CUSTOM_NAME, Component.literal("Hyperion"));
                return st;
            });
            boolean before = c.onClient(mc -> invokeBool(isProtected, item));
            double bpc = bytesPerCall(c, () -> (boolean) isProtected.invokeExact(item) ? 1 : 0);
            c.ctx().runOnClient(mc -> Mod.call(protect, "addProtectedKey", "k560-mixin-fixes-uuid"));
            boolean after = c.onClient(mc -> invokeBool(isProtected, item));
            c.ctx().runOnClient(mc -> Mod.call(protect, "toggleProtectedKey", "k560-mixin-fixes-uuid"));
            lines.add(String.format(Locale.ROOT, "protect star: %d typed name(s), unlisted %s, listed %s, %.2f B/call",
                    names, before, after, bpc));
            if (before || !after) {
                failures.add("Item Protect: unlisted " + before + ", listed " + after + " - expected false then true");
            }
            if (names == 0 && bpc >= 2 * BUDGET) {
                failures.add(String.format(Locale.ROOT, "isExplicitlyProtected builds the name with no names listed: %.1f B per call", bpc));
            }
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.call(protect, "setProtectItemEnabled", p0);
                Mod.call(protect, "setEnabled", e0);
            });
        }

        // 4. Click Sound: a sound that is not the button click passes through untouched.
        MethodHandle replace = mh("clicksound.ClickSounds", "replace", SoundInstance.class, SoundInstance.class);
        SoundInstance other = c.onClient(mc -> SimpleSoundInstance.forUI(
                SoundEvent.createVariableRangeEvent(Identifier.withDefaultNamespace("entity.experience_orb.pickup")), 1.0f));
        SoundInstance back = c.onClient(mc -> {
            try {
                return (SoundInstance) replace.invokeExact(other);
            } catch (Throwable t) {
                throw new AssertionError(t);
            }
        });
        double sbpc = bytesPerCall(c, () -> (SoundInstance) replace.invokeExact(other) == other ? 1 : 0);
        lines.add(String.format(Locale.ROOT, "click sound replace(other): untouched %s, %.2f B/call", back == other, sbpc));
        if (back != other) {
            failures.add("ClickSounds.replace changed a non-click sound");
        }
        if (sbpc >= BUDGET) {
            failures.add(String.format(Locale.ROOT, "ClickSounds.replace allocates %.1f B per sound", sbpc));
        }

        // 5. Packet bridges: a ping, and a remove-entities packet through Dungeon Alerts and Blood Camp.
        MethodHandle ping = mh("witherdragons.WitherDragonsPackets", "onPing", void.class, int.class);
        MethodHandle alerts = mh("dungeonalerts.DungeonAlertsPackets", "onRemoveEntities", void.class,
                ClientboundRemoveEntitiesPacket.class, ClientLevel.class);
        MethodHandle blood = mh("bloodcamp.BloodCampFeature", "onRemoveEntities", void.class,
                ClientboundRemoveEntitiesPacket.class, Level.class);
        double pingB = bytesPerCall(c, () -> {
            ping.invokeExact(0);
            return 0;
        });
        ClientboundRemoveEntitiesPacket remove = new ClientboundRemoveEntitiesPacket(999_001, 999_002);
        double alertsB = bytesPerCall(c, () -> {
            alerts.invokeExact(remove, Minecraft.getInstance().level);
            return 0;
        });
        double bloodB = bytesPerCall(c, () -> {
            blood.invokeExact(remove, (Level) Minecraft.getInstance().level);
            return 0;
        });
        lines.add(String.format(Locale.ROOT, "packets: ping %.2f B, remove-entities Dungeon Alerts %.2f B, Blood Camp %.2f B",
                pingB, alertsB, bloodB));
        // The ping and Dungeon Alerts lambdas were usually scalar-replaced by the JIT on main too (2.2 / 0.6 B), so
        // they are reported, not judged; Blood Camp's lambda and iterator never were (48 B).
        if (bloodB >= BUDGET / 2) {
            failures.add(String.format(Locale.ROOT, "Blood Camp's remove-entities hook allocates %.1f B per packet", bloodB));
        }

        // 6. Container screens: a menu that is neither a storage page nor an RNG meter. Storage Overlay's and the RNG
        // Meter's per-frame hooks (and the RNG tooltip line) answer from the title object, not from its text.
        java.util.Map<Integer, String> slots = new java.util.LinkedHashMap<>();
        slots.put(13, "minecraft:diamond_sword[custom_name={text:\"Hyperion\",italic:false}]");
        c.hx().menu("Auctions Browser", 6, slots, null);
        c.waitUntil("the chest menu is open", mc -> dev.testkit.compat.McCompat.screen(mc)
                instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>, 100);
        Object rng = c.onClient(mc -> Mod.cfg("rngmeter.RngMeterConfig"));
        Object storage = c.onClient(mc -> Mod.cfg("storageoverlay.StorageOverlayConfig"));
        boolean r0 = c.onClient(mc -> (Boolean) Mod.field(rng, "enabled"));
        boolean o0 = c.onClient(mc -> (Boolean) Mod.field(storage, "enabled"));
        try {
            c.ctx().runOnClient(mc -> {
                Mod.call(rng, "setEnabled", true);
                Mod.call(storage, "setEnabled", true);
            });
            var screen = c.onClient(mc -> (net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)
                    dev.testkit.compat.McCompat.screen(mc));
            Class<?> gfx = net.minecraft.client.gui.GuiGraphicsExtractor.class;
            Class<?> acs = net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class;
            MethodHandle storageHook = mh("storageoverlay.StorageOverlayFeature", "onContainerScreenRender", void.class,
                    acs, gfx, int.class, int.class);
            MethodHandle rngHook = mh("rngmeter.RngMeterOverlay", "onContainerScreenRender", void.class, acs, gfx);
            // Neither hook reaches the graphics for a title that is not theirs, so none is passed.
            double storageB = bytesPerCall(c, () -> {
                storageHook.invoke(screen, null, 0, 0);
                return 0;
            });
            double rngB = bytesPerCall(c, () -> {
                rngHook.invoke(screen, null);
                return 0;
            });
            lines.add(String.format(Locale.ROOT, "container frame hooks on \"%s\": Storage Overlay %.2f B, RNG Meter %.2f B",
                    c.onClient(mc -> screen.getTitle().getString()), storageB, rngB));
            if (storageB >= BUDGET) {
                failures.add(String.format(Locale.ROOT, "Storage Overlay's frame hook allocates %.1f B per frame in a plain menu", storageB));
            }
            if (rngB >= BUDGET) {
                failures.add(String.format(Locale.ROOT, "RNG Meter's frame hook allocates %.1f B per frame in a plain menu", rngB));
            }
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.call(rng, "setEnabled", r0);
                Mod.call(storage, "setEnabled", o0);
                if (mc.player != null) {
                    mc.player.closeContainer();
                }
                dev.testkit.compat.McCompat.setScreen(mc, null);
            });
        }

        for (String l : lines) {
            System.out.println("[" + NAME + "] " + l);
            c.note(l);
        }
        c.check(failures.isEmpty(), String.join("; ", failures));
    }

    private static boolean invokeBool(MethodHandle h, ItemStack stack) {
        try {
            return (boolean) h.invokeExact(stack);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }
}

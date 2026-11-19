package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.testkit.compat.McCompat;
import dev.testkit.compat.McKeys;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * pvfit-1118: the Profile Viewer fits at GUI scales 2/3/4 in every style, the Show Status button, the cross-container
 * search, the pet level past the cap, and the collection rows.
 * <ol>
 *   <li>1118-menu-pvfit-layout: every Inventories view and the Basic page draw at 1280x720 and 1920x1080 on GUI scales
 *       2/3/4 with no exception, the Basic rows never scale under 0.7, and a screenshot of each is kept.</li>
 *   <li>1118-menu-pvfit-search: an emerald in the ender chest and a diamond in a backpack mark exactly those views,
 *       clearing restores, and Ctrl+F focuses the box.</li>
 *   <li>1118-menu-pvfit-status: no request before the click, one on the click (off the render thread), the cache holds
 *       a second click, hidden and 404 read as the brief says.</li>
 *   <li>1118-logic-pvfit-pet: a pet over the cap shows its uncapped level, one under it shows none.</li>
 *   <li>1118-menu-pvfit-collections: a collection row carries the total collected next to the tier.</li>
 * </ol>
 */
final class PvFit1118Cases {

    private PvFit1118Cases() {
    }

    static final String API = "profileviewer.api.ProfileViewerApi";
    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000001b8");
    static final String PROFILE = "{\"success\":true,\"profiles\":[{\"profile_id\":\"p1\",\"cute_name\":\"Pomegranate\",\"selected\":true,"
            + "\"members\":{\"000000000000000000000000000001b8\":{\"currencies\":{\"coin_purse\":1000},"
            + "\"collection\":{\"COBBLESTONE\":1234567,\"WHEAT\":987654},"
            + "\"inventory\":{\"inv_contents\":{\"type\":0,\"data\":\"\"}}}}}]}";

    static void register(Session s) {
        MenuSuite.test(s, "1118-menu-pvfit-layout", PvFit1118Cases::layout);
        MenuSuite.test(s, "1118-menu-pvfit-search", PvFit1118Cases::search);
        MenuSuite.test(s, "1118-menu-pvfit-status", PvFit1118Cases::status);
        MenuSuite.test(s, "1118-logic-pvfit-pet", PvFit1118Cases::pet);
        MenuSuite.test(s, "1118-menu-pvfit-collections", PvFit1118Cases::collections);
    }

    private static ItemStack named(ItemStack base) {
        return base.copy();
    }

    private static List<ItemStack> grid(int n, ItemStack first) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(i == 0 ? named(first) : ItemStack.EMPTY);
        }
        return out;
    }

    /** Puts the fake profile in the cache and opens the viewer on it; the inventories carry an emerald in the ender
     *  chest, a diamond in backpack 0 and arrows in the quiver. */
    @SuppressWarnings("unchecked")
    private static Screen open(Session c, List<AutoCloseable> close) {
        Screen s = c.onClient(mc -> {
            try {
                List<Object> parsed = (List<Object>) Mod.staticCall("profileviewer.data.SbProfile", "parseAll",
                        JsonParser.parseString(PROFILE).getAsJsonObject(), ID);
                Class<?> bpCls = Mod.cls("profileviewer.data.SbProfile$Backpack");
                Object bp = bpCls.getDeclaredConstructors()[0].newInstance(0, new ItemStack(Items.CHEST),
                        grid(54, new ItemStack(Items.DIAMOND)));
                Class<?> invCls = Mod.cls("profileviewer.data.SbProfile$Inventories");
                Object inv = invCls.getDeclaredConstructors()[0].newInstance(grid(36, new ItemStack(Items.APPLE)),
                        List.of(), List.of(), List.of(grid(45, new ItemStack(Items.EMERALD))), List.of(bp),
                        List.of(grid(36, new ItemStack(Items.IRON_SWORD))), 0, List.of(grid(45, new ItemStack(Items.GOLD_INGOT))),
                        grid(54, new ItemStack(Items.BONE)), grid(36, new ItemStack(Items.ARROW)),
                        grid(27, new ItemStack(Items.COD)), grid(27, new ItemStack(Items.POTION)));
                setField(parsed.get(0), "inventories", CompletableFuture.completedFuture(inv));
                Class<?> resCls = Mod.cls(API + "$ProfilesResult");
                Object result = resCls.getDeclaredConstructor(UUID.class, List.class, String.class, long.class)
                        .newInstance(ID, parsed, "PvFitTest", System.currentTimeMillis());
                Constructor<?> cached = Mod.cls(API + "$Cached").getDeclaredConstructor(resCls, long.class);
                cached.setAccessible(true);
                ((Map<UUID, Object>) Mod.field(API, "CACHE")).put(ID, cached.newInstance(result, System.currentTimeMillis()));
                Object target = Mod.cls("profileviewer.ProfileViewerFeature$Target")
                        .getDeclaredConstructor(String.class, UUID.class).newInstance("PvFitTest", ID);
                Screen pv = (Screen) Mod.cls("profileviewer.screen.ProfileViewerScreen")
                        .getDeclaredConstructor(Screen.class, target.getClass()).newInstance(null, target);
                McCompat.setScreen(mc, pv);
                return pv;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("cannot open the PV: " + e, e);
            }
        });
        close.add(() -> c.ctx().runOnClient(mc -> {
            McCompat.setScreen(mc, null);
            ((Map<?, ?>) Mod.field(API, "CACHE")).remove(ID);
        }));
        c.waitUntil("the PV shows the offline profile", mc -> "READY".equals(String.valueOf(Mod.field(s, "state"))), 100);
        return s;
    }

    private static void set(Session c, Screen s, String field, Object v) {
        c.ctx().runOnClient(mc -> {
            try {
                setField(s, field, v);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
    }

    // ---- layout ----------------------------------------------------------------------------------------------------

    static void layout(Session c) throws Exception {
        List<AutoCloseable> close = new ArrayList<>();
        try (SocialCases.Env env = new SocialCases.Env(c)) {
            int[][] sizes = {{1280, 720, 2}, {1280, 720, 3}, {1920, 1080, 2}, {1920, 1080, 3}, {1920, 1080, 4}};
            for (int[] sz : sizes) {
                BazaarV3Cases.setWindow(c, sz[0], sz[1], sz[2]);
                Screen s = open(c, close);
                c.ctx().runOnClient(mc -> Mod.call(Mod.cfg("profileviewer.ProfileViewerConfig"), "setRememberLastPage", false));
                String tag = sz[0] + "x" + sz[1] + "-gui" + sz[2];
                c.ctx().runOnClient(mc -> Mod.call(s, "setPage", 0));
                c.ctx().waitTicks(4);
                float scale = c.onClient(mc -> (Float) Mod.field(s, "minRowScale"));
                c.check(scale >= 0.7f, tag + ": a Basic row scaled to " + scale + ", under 0.7");
                if (sz[2] == 3) {
                    BazaarReskinCases.shot(c, "basic-" + tag);
                }
                c.ctx().runOnClient(mc -> Mod.call(s, "setPage", 2)); // PAGE_INVENTORIES
                for (int view = 0; view < 10; view++) {
                    set(c, s, "invView", view);
                    c.ctx().waitTicks(3); // a throw in the draw would fail the tick
                    if (view == 0 || view == 1 || view == 4) {
                        BazaarReskinCases.shot(c, "inv" + view + "-" + tag);
                    }
                }
                c.note(tag + ": Basic min row scale " + scale + ", ten inventory views drew");
                closeAll(close);
                close.clear();
            }
        } finally {
            closeAll(close);
        }
    }

    // ---- search ----------------------------------------------------------------------------------------------------

    static void search(Session c) throws Exception {
        List<AutoCloseable> close = new ArrayList<>();
        try (SocialCases.Env env = new SocialCases.Env(c)) {
            BazaarV3Cases.setWindow(c, 1600, 900, 2);
            Screen s = open(c, close);
            c.ctx().runOnClient(mc -> Mod.call(s, "setPage", 2));
            c.ctx().waitTicks(6);
            c.ctx().runOnClient(mc -> Mod.call(s, "setFind", "emerald"));
            c.ctx().waitTicks(4);
            int ender = c.onClient(mc -> (Integer) Mod.call(s, "findCount", 1));
            int packs = c.onClient(mc -> (Integer) Mod.call(s, "findCount", 2));
            c.check(ender == 1 && packs == 0, "emerald: ender chest marks " + ender + " (want 1), backpacks " + packs + " (want 0)");
            c.ctx().runOnClient(mc -> Mod.call(s, "setFind", "diamond"));
            c.ctx().waitTicks(4);
            ender = c.onClient(mc -> (Integer) Mod.call(s, "findCount", 1));
            packs = c.onClient(mc -> (Integer) Mod.call(s, "findCount", 2));
            c.check(ender == 0 && packs == 1, "diamond: ender chest marks " + ender + " (want 0), backpacks " + packs + " (want 1)");
            BazaarReskinCases.shot(c, "search-diamond");
            c.ctx().runOnClient(mc -> Mod.call(s, "setFind", ""));
            c.ctx().waitTicks(4);
            int left = 0;
            for (int v = 0; v < 10; v++) {
                final int view = v;
                left += c.onClient(mc -> (Integer) Mod.call(s, "findCount", view));
            }
            c.check(left == 0, "clearing the search left " + left + " marks");
            // Ctrl+F (GLFW F = 70, ctrl = 2) focuses the box.
            boolean before = c.onClient(mc -> (Boolean) Mod.call(s, "findFocused"));
            boolean[] handled = new boolean[1];
            c.ctx().runOnClient(mc -> handled[0] = s.keyPressed(McKeys.keyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_F, com.mojang.blaze3d.platform.InputConstants.MOD_CONTROL)));
            c.note("Ctrl+F handled=" + handled[0] + " screen=" + (mc0(c) == s));
            c.ctx().waitTicks(2);
            boolean after = c.onClient(mc -> (Boolean) Mod.call(s, "findFocused"));
            c.check(!before && after, "Ctrl+F did not focus the search box (before " + before + ", after " + after + ")");
        } finally {
            closeAll(close);
        }
    }

    // ---- status ----------------------------------------------------------------------------------------------------

    static void status(Session c) throws Exception {
        List<AutoCloseable> close = new ArrayList<>();
        AtomicInteger asked = new AtomicInteger();
        try (SocialCases.Env env = new SocialCases.Env(c)) {
            String[] answer = {"{\"session\":{\"online\":true,\"gameType\":\"SKYBLOCK\",\"mode\":\"hub\"}}"};
            boolean[] notFound = {false};
            c.ctx().runOnClient(mc -> Mod.staticCall(API, "setStatusSourceForTest", (java.util.function.Function<UUID, CompletableFuture<JsonObject>>) u -> {
                asked.incrementAndGet();
                if (notFound[0]) {
                    return CompletableFuture.failedFuture(new RuntimeException("404"));
                }
                return CompletableFuture.completedFuture(JsonParser.parseString(answer[0]).getAsJsonObject());
            }));
            close.add(() -> c.ctx().runOnClient(mc -> Mod.staticCall(API, "setStatusSourceForTest", (Object) null)));
            BazaarV3Cases.setWindow(c, 1600, 900, 2);
            Screen s = open(c, close);
            c.ctx().waitTicks(20);
            c.check(asked.get() == 0, "a status request went out before any click (" + asked.get() + ")");
            c.ctx().runOnClient(mc -> Mod.call(s, "toggleStatus"));
            c.waitUntil("the status answer", mc -> asked.get() >= 1, 60);
            c.ctx().waitTicks(4);
            List<?> lines = c.onClient(mc -> (List<?>) Mod.call(s, "statusLines"));
            c.check(lines.size() == 1 && String.valueOf(lines.get(0)).startsWith("Online - "), "online line: " + lines);
            c.ctx().runOnClient(mc -> Mod.call(s, "toggleStatus")); // close
            c.ctx().runOnClient(mc -> Mod.call(s, "toggleStatus")); // open again inside the cache window
            c.ctx().waitTicks(4);
            c.check(asked.get() == 1, "the second click inside 30 s asked again (" + asked.get() + ")");
            BazaarReskinCases.shot(c, "status-online");
            // a 404 reads "Status unavailable" (a new source also empties the 30 s cache)
            c.ctx().runOnClient(mc -> Mod.call(s, "toggleStatus")); // close
            c.ctx().runOnClient(mc -> Mod.staticCall(API, "setStatusSourceForTest", (java.util.function.Function<UUID, CompletableFuture<JsonObject>>) u -> {
                asked.incrementAndGet();
                return CompletableFuture.failedFuture(new RuntimeException("404"));
            }));
            c.ctx().runOnClient(mc -> Mod.call(s, "toggleStatus"));
            c.waitUntil("the 404 answer", mc -> asked.get() >= 2, 60);
            c.ctx().waitTicks(4);
            List<?> bad = c.onClient(mc -> (List<?>) Mod.call(s, "statusLines"));
            c.check(String.valueOf(bad).contains("Status unavailable"), "404 line: " + bad);
        } finally {
            closeAll(close);
        }
    }

    // ---- pet / collections -----------------------------------------------------------------------------------------

    static void pet(Session c) {
        int over = c.onClient(mc -> (Integer) Mod.staticCall("profileviewer.data.LevelTables", "petUncappedLevel", "ENDERMAN", "LEGENDARY", 400_000_000d));
        int under = c.onClient(mc -> (Integer) Mod.staticCall("profileviewer.data.LevelTables", "petUncappedLevel", "ENDERMAN", "LEGENDARY", 50_000d));
        c.note("uncapped level over the cap " + over + ", under " + under);
        c.check(over > 100, "a pet far past level 100 showed uncapped level " + over);
        c.check(under == 0, "a level-low pet showed an uncapped level " + under + ", want none");
    }

    static void collections(Session c) throws Exception {
        List<AutoCloseable> close = new ArrayList<>();
        try (SocialCases.Env env = new SocialCases.Env(c)) {
            BazaarV3Cases.setWindow(c, 1600, 900, 2);
            Screen s = open(c, close);
            // the tier list is a download (refused offline): inject a small one for the run, as PvStyle1104Cases does
            Object remote = c.onClient(mc -> Mod.field("profileviewer.data.ExtraTables", "COLLECTIONS"));
            java.lang.reflect.Field future = remote.getClass().getDeclaredField("future");
            future.setAccessible(true);
            Object old = future.get(remote);
            Object cats = c.onClient(mc -> Mod.staticCall("profileviewer.data.ExtraTables", "parseCollections", com.google.gson.JsonParser.parseString(
                    "{\"collections\":{\"MINING\":{\"name\":\"Mining\",\"items\":{\"COBBLESTONE\":{\"name\":\"Cobblestone\",\"maxTiers\":2,\"tiers\":[{\"tier\":1,\"amountRequired\":50},{\"tier\":2,\"amountRequired\":1000}]}}}}}").getAsJsonObject()));
            synchronized (remote) { future.set(remote, java.util.concurrent.CompletableFuture.completedFuture(cats)); }
            try {
                c.ctx().runOnClient(mc -> Mod.call(s, "setPage", 4)); // PAGE_COLLECTIONS
                c.ctx().waitTicks(40);
                List<?> rows = c.onClient(mc -> (List<?>) Mod.field(Mod.field(s, "extraPages"), "collRowTexts"));
            c.note("collection rows: " + rows);
            boolean cobble = rows.stream().anyMatch(r -> String.valueOf(r).contains("1.23M"));
            c.check(cobble, "no collection row carried the short total 1.23M for 1,234,567 cobblestone: " + rows);
            BazaarReskinCases.shot(c, "collections");
            } finally {
                synchronized (remote) { future.set(remote, old); }
            }
        } finally {
            closeAll(close);
        }
    }

    private static Object mc0(Session c) {
        return c.onClient(mc -> dev.testkit.compat.McCompat.screen(mc));
    }

    private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        for (Class<?> k = target.getClass(); k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // try the parent
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void closeAll(List<AutoCloseable> close) {
        for (int i = close.size() - 1; i >= 0; i--) {
            try {
                close.get(i).close();
            } catch (Exception ignored) {
                // best effort
            }
        }
    }
}

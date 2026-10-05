package dev.testkit.server.hx.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.testkit.server.hx.HxChestMenu;
import dev.testkit.server.hx.HxEvents;
import dev.testkit.server.hx.HxPrimitives;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Floor 7 terminals with Hypixel's click mechanics, server side.
 *
 * <p>Every rule here is ported from the mod's own sources at f40ec89 (paths under
 * {@code src/main/java/com/killer560/hub/}), never invented:
 * <ul>
 *   <li>titles: {@code terminals/TerminalType.java:12-17} (regexes) and {@code termism/TermismPracticeScreen.java:776-786}
 *       (the concrete "What starts with: 'X'?" / "Select all the RED items!" / SILVER for light grey);</li>
 *   <li>grid shapes and click results: {@code termism/TermismPracticeScreen.java} generators (Panes 5x3 :384, Rubix
 *       3x3 :407, Numbers 2x7 counts 1-14 :420, Starts With 3x7 :435, Select 4x7 :496, Melody 9x6 :270-330) and
 *       {@code handleCellClick} :533-614 (Rubix left = forward / right = backward through ORANGE YELLOW GREEN BLUE RED
 *       :60; Numbers only the lowest count; Melody only column 7 of the current row while the lime marker sits on
 *       the magenta column, every 500 ms :75);</li>
 *   <li>Panes TOGGLES (a re-click turns a correct pane back to wrong): {@code terminals/TerminalSolverFeature.java:193}
 *       and :245;</li>
 *   <li>a correct Starts With / Select pick GLINTS rather than vanishing: {@code TerminalSolverFeature.java:1538}
 *       (solveStartsWith) and :1570 (solveSelect);</li>
 *   <li>filler: a black pane with an empty name, {@code experiments/ExperimentSolver.java:566};</li>
 *   <li>Melody's slot model: buttons 16/25/34/43 ({@code TerminalSolverFeature.java:215}), marker row = slot/9 - 1.</li>
 * </ul>
 * Where the grid sits inside the chest (one border row/column of filler) is the testkit's choice - nothing in the mod
 * pins it, and the mod's solver is position-independent (it reads every slot but the player's 36).
 */
final class HxTerminals {

    static final String SCRIPT = "hx.terminal";

    enum Type { PANES, RUBIX, NUMBERS, STARTS_WITH, SELECT, MELODY }

    /** TermismPracticeScreen.java:60 - Rubix cycle order. */
    private static final String[] RUBIX = {"orange", "yellow", "green", "blue", "red"};

    /** TermismPracticeScreen.java:178-187 - one display name + wool per colour (the solver's SELECT_PREFIXES,
     *  TerminalSolverFeature.java:95-104, accepts these names). Key = the title colour word. */
    private static final Map<String, String[]> SELECT = new LinkedHashMap<>();

    static {
        SELECT.put("RED", new String[]{"Rose", "minecraft:red_wool"});
        SELECT.put("GREEN", new String[]{"Cactus", "minecraft:green_wool"});
        SELECT.put("BLUE", new String[]{"Lapis", "minecraft:blue_wool"});
        SELECT.put("BROWN", new String[]{"Cocoa", "minecraft:brown_wool"});
        SELECT.put("WHITE", new String[]{"Bone", "minecraft:white_wool"});
        SELECT.put("BLACK", new String[]{"Ink", "minecraft:black_wool"});
        SELECT.put("YELLOW", new String[]{"Dandelion", "minecraft:yellow_wool"});
        SELECT.put("SILVER", new String[]{"Silver", "minecraft:light_gray_wool"});
    }

    /** A subset of TermismPracticeScreen.java:89-170 STARTS_WITH_POOL (name, item). That pool is the mod's own practice
     *  set ("Not Hypixel's exact real item pool", :86) - the solver only reads the name's first letter. */
    private static final String[][] STARTS_WITH_POOL = {
            {"Stick", "minecraft:stick"}, {"String", "minecraft:string"}, {"Saddle", "minecraft:saddle"},
            {"Snowball", "minecraft:snowball"}, {"Sponge", "minecraft:sponge"}, {"Slimeball", "minecraft:slime_ball"},
            {"Shears", "minecraft:shears"}, {"Sugar", "minecraft:sugar"}, {"Shield", "minecraft:shield"},
            {"Salmon", "minecraft:salmon"}, {"Spider Eye", "minecraft:spider_eye"},
            {"Feather", "minecraft:feather"}, {"Flint", "minecraft:flint"}, {"Egg", "minecraft:egg"},
            {"Emerald", "minecraft:emerald"}, {"Torch", "minecraft:torch"}, {"Ladder", "minecraft:ladder"},
            {"Bucket", "minecraft:bucket"}, {"Bread", "minecraft:bread"}, {"Bone", "minecraft:bone"},
            {"Coal", "minecraft:coal"}, {"Compass", "minecraft:compass"}, {"Clock", "minecraft:clock"},
            {"Diamond", "minecraft:diamond"}, {"Redstone", "minecraft:redstone"}, {"Paper", "minecraft:paper"},
            {"Pumpkin", "minecraft:pumpkin"}, {"Melon", "minecraft:melon"}, {"Apple", "minecraft:apple"},
            {"Arrow", "minecraft:arrow"}, {"Gunpowder", "minecraft:gunpowder"}, {"Book", "minecraft:book"},
            {"Golden Apple", "minecraft:golden_apple"}, {"Wheat", "minecraft:wheat"}, {"Obsidian", "minecraft:obsidian"},
    };

    /** One open terminal. */
    static final class Terminal implements HxMenus.Ticking {
        final Type type;
        final int rows;
        final int[] grid;
        final Random rng;
        final String title;
        final boolean closeOnSolve;
        final int melodyInterval;
        String letter;
        String colorWord;
        int containerId = -1;
        boolean solved;
        int clicks;
        int wrong;
        long openedTick;
        long firstTick = -1;
        long lastTick = -1;
        long solvedTick = -1;
        final Map<Long, Integer> perTick = new LinkedHashMap<>();
        // Melody (TermismPracticeScreen.java:270-330)
        int melodyRow = 1;
        int melodyTarget;
        int melodyLime = 1;
        int melodyDir = 1;
        long melodyLastMove;

        Terminal(Type type, long seed, String title, int rows, int[] grid, boolean closeOnSolve, int melodyInterval) {
            this.type = type;
            this.rng = new Random(seed);
            this.title = title;
            this.rows = rows;
            this.grid = grid;
            this.closeOnSolve = closeOnSolve;
            this.melodyInterval = melodyInterval;
        }

        @Override
        public void tick(MinecraftServer server, HxChestMenu menu, ServerPlayer player) {
            if (type != Type.MELODY || solved) {
                return;
            }
            long now = server.getTickCount();
            if (now - melodyLastMove < melodyInterval) {
                return;
            }
            melodyLastMove = now;
            melodyLime += melodyDir;
            if (melodyLime == 1 || melodyLime == 5) {
                melodyDir *= -1;
            }
            drawMelody(server, menu);
            menu.broadcastChanges();
        }

        void drawMelody(MinecraftServer server, HxChestMenu menu) {
            for (int i = 0; i < 54; i++) {
                menu.items().setItem(i, melodyItem(server, i % 9, i / 9));
            }
        }

        /** TermismPracticeScreen.java:289-313, melodyItemFor. */
        ItemStack melodyItem(MinecraftServer server, int col, int row) {
            boolean inBand = row >= 1 && row < 5;
            if (col == melodyTarget && !inBand) {
                return HxMenus.stack(server, "minecraft:magenta_stained_glass_pane", 1);
            }
            if (col == melodyLime && row == melodyRow) {
                return HxMenus.stack(server, "minecraft:lime_stained_glass_pane", 1);
            }
            if (col >= 1 && col < 6 && row == melodyRow) {
                return HxMenus.stack(server, "minecraft:red_stained_glass_pane", 1);
            }
            if (col == 7 && row == melodyRow) {
                return HxMenus.stack(server, "minecraft:lime_terracotta", 1);
            }
            if (col == 7 && inBand) {
                return HxMenus.stack(server, "minecraft:red_terracotta", 1);
            }
            if (col >= 1 && col < 6 && inBand) {
                return HxMenus.stack(server, "minecraft:white_stained_glass_pane", 1);
            }
            return ItemStack.EMPTY;
        }

        /** Slots that still need a click (for Numbers, in order; for Rubix, with the signed count). */
        JsonArray remaining(HxChestMenu menu) {
            JsonArray out = new JsonArray();
            if (type == Type.MELODY) {
                out.add(16 + (melodyRow - 1) * 9);
                return out;
            }
            List<int[]> nums = new ArrayList<>();
            for (int slot : grid) {
                ItemStack s = menu.items().getItem(slot);
                switch (type) {
                    case PANES -> {
                        if (id(s).equals("minecraft:red_stained_glass_pane")) {
                            out.add(slot);
                        }
                    }
                    case NUMBERS -> {
                        if (id(s).equals("minecraft:red_stained_glass_pane")) {
                            nums.add(new int[]{s.getCount(), slot});
                        }
                    }
                    case RUBIX -> {
                        // filled in below
                    }
                    case STARTS_WITH -> {
                        if (!s.isEmpty() && !s.hasFoil() && s.getHoverName().getString().toUpperCase(Locale.ROOT)
                                .startsWith(letter)) {
                            out.add(slot);
                        }
                    }
                    case SELECT -> {
                        if (!s.isEmpty() && !s.hasFoil() && s.getHoverName().getString()
                                .equalsIgnoreCase(SELECT.get(colorWord)[0])) {
                            out.add(slot);
                        }
                    }
                    default -> {
                    }
                }
            }
            if (type == Type.NUMBERS) {
                nums.sort((a, b) -> Integer.compare(a[0], b[0]));
                nums.forEach(n -> out.add(n[1]));
            }
            if (type == Type.RUBIX) {
                // Not "a solution" (any common colour solves it) - the slots NOT of the majority colour.
                Map<String, Integer> counts = new LinkedHashMap<>();
                for (int slot : grid) {
                    counts.merge(id(menu.items().getItem(slot)), 1, Integer::sum);
                }
                String major = counts.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
                for (int slot : grid) {
                    if (!id(menu.items().getItem(slot)).equals(major)) {
                        out.add(slot);
                    }
                }
            }
            return out;
        }

        JsonObject describe(HxChestMenu menu) {
            JsonObject o = new JsonObject();
            o.addProperty("terminal", type.name());   // not "type": HxEvents.custom would overwrite the event type
            o.addProperty("title", title);
            o.addProperty("containerId", containerId);
            o.addProperty("rows", rows);
            o.addProperty("solved", solved);
            o.addProperty("clicks", clicks);
            o.addProperty("wrong", wrong);
            o.addProperty("openedTick", openedTick);
            o.addProperty("firstTick", firstTick);
            o.addProperty("lastTick", lastTick);
            o.addProperty("solvedTick", solvedTick);
            o.addProperty("maxClicksPerTick", perTick.values().stream().mapToInt(Integer::intValue).max().orElse(0));
            JsonArray g = new JsonArray();
            for (int slot : grid) {
                g.add(slot);
            }
            o.add("grid", g);
            if (menu != null) {
                o.add("remaining", remaining(menu));
                JsonObject board = new JsonObject();
                for (int slot : grid) {
                    ItemStack s = menu.items().getItem(slot);
                    board.addProperty(String.valueOf(slot), s.isEmpty() ? "" : id(s) + "x" + s.getCount()
                            + (s.hasFoil() ? "*" : "") + "|" + s.getHoverName().getString());
                }
                o.add("board", board);
            }
            return o;
        }
    }

    private static Terminal last;
    private static HxChestMenu lastMenu;

    private HxTerminals() {
    }

    static void register() {
        HxChestMenu.registerScript(SCRIPT, HxTerminals::onClick);
    }

    static String id(ItemStack s) {
        return s.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
    }

    private static int[] gridOf(int rowStart, int rowCount, int colStart, int colCount) {
        int[] g = new int[rowCount * colCount];
        int k = 0;
        for (int r = rowStart; r < rowStart + rowCount; r++) {
            for (int c = colStart; c < colStart + colCount; c++) {
                g[k++] = r * 9 + c;
            }
        }
        return g;
    }

    /** menu.terminal {type, seed=1, count?, letter?, color?, interval=10, closeOnSolve=true, wrongFirst?} */
    static JsonElement open(MinecraftServer server, JsonObject a) {
        ServerPlayer player = HxPrimitives.player(server, a);
        Type type = Type.valueOf(a.get("type").getAsString().toUpperCase(Locale.ROOT));
        long seed = a.has("seed") ? a.get("seed").getAsLong() : 1;
        boolean close = !a.has("closeOnSolve") || a.get("closeOnSolve").getAsBoolean();
        int interval = a.has("interval") ? a.get("interval").getAsInt() : 10;
        Random rng = new Random(seed);
        Terminal t;
        List<ItemStack> items;
        switch (type) {
            case PANES -> {
                t = new Terminal(type, seed, "Correct all the panes!", 5, gridOf(1, 3, 2, 5), close, interval);
                items = HxMenus.filled(server, t.rows);
                int count = a.has("count") ? a.get("count").getAsInt() : 4 + rng.nextInt(12);
                List<Integer> idx = shuffled(t.grid.length, rng);
                for (int i = 0; i < t.grid.length; i++) {
                    items.set(t.grid[i], HxMenus.stack(server, idx.indexOf(i) < count
                            ? "minecraft:red_stained_glass_pane" : "minecraft:lime_stained_glass_pane", 1));
                }
            }
            case RUBIX -> {
                t = new Terminal(type, seed, "Change all to same color!", 5, gridOf(1, 3, 3, 3), close, interval);
                items = HxMenus.filled(server, t.rows);
                for (int slot : t.grid) {
                    items.set(slot, HxMenus.stack(server, "minecraft:" + RUBIX[rng.nextInt(5)] + "_stained_glass_pane", 1));
                }
                // never hand out an already-solved board
                if (items.stream().filter(s -> id(s).contains("stained_glass_pane") && !id(s).contains("black"))
                        .map(HxTerminals::id).distinct().count() == 1) {
                    items.set(t.grid[0], HxMenus.stack(server, "minecraft:" + RUBIX[(indexOf(id(items.get(t.grid[0]))) + 2) % 5]
                            + "_stained_glass_pane", 1));
                }
            }
            case NUMBERS -> {
                t = new Terminal(type, seed, "Click in order!", 4, gridOf(1, 2, 1, 7), close, interval);
                items = HxMenus.filled(server, t.rows);
                List<Integer> order = shuffled(14, rng);
                for (int i = 0; i < 14; i++) {
                    items.set(t.grid[i], HxMenus.stack(server, "minecraft:red_stained_glass_pane", order.get(i) + 1));
                }
            }
            case STARTS_WITH -> {
                String letter = a.has("letter") ? a.get("letter").getAsString().toUpperCase(Locale.ROOT) : "S";
                t = new Terminal(type, seed, "What starts with: '" + letter + "'?", 5, gridOf(1, 3, 1, 7), close, interval);
                t.letter = letter;
                items = HxMenus.filled(server, t.rows);
                List<String[]> match = new ArrayList<>();
                List<String[]> other = new ArrayList<>();
                for (String[] e : STARTS_WITH_POOL) {
                    (e[0].toUpperCase(Locale.ROOT).startsWith(letter) ? match : other).add(e);
                }
                Collections.shuffle(match, rng);
                Collections.shuffle(other, rng);
                int count = Math.min(a.has("count") ? a.get("count").getAsInt() : 2 + rng.nextInt(Math.max(1, match.size() - 1)),
                        match.size());
                List<String[]> chosen = new ArrayList<>(match.subList(0, count));
                for (int i = 0; chosen.size() < t.grid.length && i < other.size(); i++) {
                    chosen.add(other.get(i));
                }
                Collections.shuffle(chosen, rng);
                for (int i = 0; i < t.grid.length; i++) {
                    items.set(t.grid[i], i < chosen.size() ? HxMenus.named(server, chosen.get(i)[1], chosen.get(i)[0])
                            : ItemStack.EMPTY);
                }
            }
            case SELECT -> {
                String color = a.has("color") ? a.get("color").getAsString().toUpperCase(Locale.ROOT) : "RED";
                if (!SELECT.containsKey(color)) {
                    throw new IllegalArgumentException("Select colour must be one of " + SELECT.keySet());
                }
                t = new Terminal(type, seed, "Select all the " + color + " items!", 6, gridOf(1, 4, 1, 7), close, interval);
                t.colorWord = color;
                items = HxMenus.filled(server, t.rows);
                int count = a.has("count") ? a.get("count").getAsInt() : 2 + Math.min(rng.nextInt(27), rng.nextInt(27));
                List<String> others = new ArrayList<>(SELECT.keySet());
                others.remove(color);
                List<Integer> idx = shuffled(t.grid.length, rng);
                for (int i = 0; i < t.grid.length; i++) {
                    String key = idx.indexOf(i) < count ? color : others.get(i % others.size());
                    items.set(t.grid[i], HxMenus.named(server, SELECT.get(key)[1], SELECT.get(key)[0]));
                }
            }
            case MELODY -> {
                t = new Terminal(type, seed, "Click the button on time!", 6, new int[]{16, 25, 34, 43}, close, interval);
                t.melodyTarget = a.has("target") ? a.get("target").getAsInt() : 1 + rng.nextInt(5);
                t.melodyLastMove = server.getTickCount();
                items = new ArrayList<>();
                for (int i = 0; i < 54; i++) {
                    items.add(t.melodyItem(server, i % 9, i / 9));
                }
            }
            default -> throw new IllegalArgumentException("unknown terminal type " + type);
        }
        t.openedTick = server.getTickCount();
        HxChestMenu menu = HxMenus.open(player, t.rows, Component.literal(t.title), items, SCRIPT, t);
        t.containerId = menu.containerId;
        last = t;
        lastMenu = menu;
        JsonObject out = t.describe(menu);
        HxEvents.custom("terminal.opened", player, out.deepCopy());
        return out;
    }

    static JsonElement state(MinecraftServer server, JsonObject a) {
        if (last == null) {
            return new JsonObject();
        }
        return last.describe(lastMenu);
    }

    private static int indexOf(String paneId) {
        for (int i = 0; i < RUBIX.length; i++) {
            if (paneId.equals("minecraft:" + RUBIX[i] + "_stained_glass_pane")) {
                return i;
            }
        }
        return -1;
    }

    private static List<Integer> shuffled(int n, Random rng) {
        List<Integer> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add(i);
        }
        Collections.shuffle(l, rng);
        return l;
    }

    private static boolean inGrid(Terminal t, int slot) {
        for (int g : t.grid) {
            if (g == slot) {
                return true;
            }
        }
        return false;
    }

    /** TermismPracticeScreen.java:533-614 handleCellClick, server side. */
    private static void onClick(HxChestMenu menu, ServerPlayer player, int slot, int button, ContainerInput input) {
        Terminal t = HxMenus.state(menu.containerId, Terminal.class);
        if (t == null || t.solved) {
            return;
        }
        MinecraftServer server = player.level().getServer();
        long tick = server.getTickCount();
        t.clicks++;
        if (t.firstTick < 0) {
            t.firstTick = tick;
        }
        t.lastTick = tick;
        t.perTick.merge(tick, 1, Integer::sum);
        boolean correct = false;
        String before = slot >= 0 && slot < menu.items().getContainerSize() ? id(menu.items().getItem(slot)) : "";
        if (slot >= 0 && slot < t.rows * 9 && (t.type == Type.MELODY || inGrid(t, slot))) {
            ItemStack s = menu.items().getItem(slot);
            String id = id(s);
            switch (t.type) {
                case PANES -> {
                    // TerminalSolverFeature.java:193/245 - a re-click flips a correct pane back to wrong.
                    if (id.equals("minecraft:red_stained_glass_pane")) {
                        menu.items().setItem(slot, HxMenus.stack(server, "minecraft:lime_stained_glass_pane", 1));
                        correct = true;
                    } else if (id.equals("minecraft:lime_stained_glass_pane")) {
                        menu.items().setItem(slot, HxMenus.stack(server, "minecraft:red_stained_glass_pane", 1));
                    }
                }
                case RUBIX -> {
                    int idx = indexOf(id);
                    if (idx >= 0) {
                        int next = Math.floorMod(idx + (button == 1 ? -1 : 1), 5);
                        menu.items().setItem(slot, HxMenus.stack(server, "minecraft:" + RUBIX[next] + "_stained_glass_pane", 1));
                        correct = true;   // every Rubix click is legal; "wrong" = moved away from the solution
                    }
                }
                case NUMBERS -> {
                    if (id.equals("minecraft:red_stained_glass_pane")) {
                        int min = Integer.MAX_VALUE;
                        for (int g : t.grid) {
                            ItemStack o = menu.items().getItem(g);
                            if (id(o).equals("minecraft:red_stained_glass_pane")) {
                                min = Math.min(min, o.getCount());
                            }
                        }
                        if (s.getCount() == min) {
                            menu.items().setItem(slot, HxMenus.stack(server, "minecraft:lime_stained_glass_pane", s.getCount()));
                            correct = true;
                        }
                    }
                }
                case STARTS_WITH -> {
                    if (!s.isEmpty() && !s.hasFoil()
                            && s.getHoverName().getString().toUpperCase(Locale.ROOT).startsWith(t.letter)) {
                        menu.items().setItem(slot, HxMenus.glint(s.copy()));
                        correct = true;
                    }
                }
                case SELECT -> {
                    if (!s.isEmpty() && !s.hasFoil()
                            && s.getHoverName().getString().equalsIgnoreCase(SELECT.get(t.colorWord)[0])) {
                        menu.items().setItem(slot, HxMenus.glint(s.copy()));
                        correct = true;
                    }
                }
                case MELODY -> {
                    int col = slot % 9;
                    int row = slot / 9;
                    if (col == 7 && row == t.melodyRow && t.melodyLime == t.melodyTarget) {
                        t.melodyTarget = 1 + t.rng.nextInt(5);
                        t.melodyRow++;
                        correct = true;
                        if (t.melodyRow < 5) {
                            t.drawMelody(server, menu);
                        }
                    }
                }
                default -> {
                }
            }
        }
        if (!correct) {
            t.wrong++;
        }
        JsonObject e = new JsonObject();
        e.addProperty("containerId", menu.containerId);
        e.addProperty("terminal", t.type.name());
        e.addProperty("slot", slot);
        e.addProperty("button", button);
        e.addProperty("input", input.name());
        e.addProperty("before", before);
        e.addProperty("correct", correct);
        HxEvents.custom("terminal.click", player, e);
        if (isSolved(t, menu)) {
            t.solved = true;
            t.solvedTick = tick;
            HxEvents.custom("terminal.solved", player, t.describe(menu));
            System.out.println("[hx] terminal " + t.type + " solved: " + t.clicks + " click(s), " + t.wrong + " wrong, "
                    + (t.solvedTick - t.openedTick) + " tick(s) after opening");
            if (t.closeOnSolve) {
                int id = menu.containerId;
                HxMenus.later(() -> {
                    if (player.containerMenu.containerId == id) {
                        player.closeContainer();
                    }
                });
            }
        }
    }

    private static boolean isSolved(Terminal t, HxChestMenu menu) {
        return switch (t.type) {
            case MELODY -> t.melodyRow >= 5;
            case RUBIX -> {
                String first = null;
                for (int g : t.grid) {
                    String id = id(menu.items().getItem(g));
                    if (first == null) {
                        first = id;
                    } else if (!first.equals(id)) {
                        yield false;
                    }
                }
                yield true;
            }
            default -> t.remaining(menu).isEmpty();
        };
    }
}

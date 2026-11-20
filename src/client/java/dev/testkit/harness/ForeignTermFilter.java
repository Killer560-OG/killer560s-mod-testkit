package dev.testkit.harness;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test-side stand-ins for two other mods' terminal click filters (orderonly-1119: a friend's Auto Terminals never clicked the
 * sim's "Click in order!" board while every other terminal worked; his mod list has Odin 0.4.0 and NoFrills). Ported from
 * their public source (odtheking/Odin main: TerminalSolver, TerminalUtils, TerminalHandler and the six handlers;
 * WhatYouThing/NoFrills main: features/dungeons/TerminalSolvers), only the parts that decide whether a click goes out:
 * <ul>
 * <li>ODIN: the solution is rebuilt only from {@code AbstractContainerMenu.setItem} (slot packets), EVERY slotClicked on an
 * open terminal is canceled, and only a click its handler allows (Numbers: the first of its solution; Melody: the button
 * column; the rest: any slot in the solution) is re-sent as Odin's own middle click (CLONE), after 200 ms First Click Prot,
 * with click prediction and the 600 ms reload.</li>
 * <li>NOFRILLS: per-type switches (all off by default there); for an ON type a click is canceled while it has no solution,
 * for 10 server ticks (pings) after the first slot packet and for 1 tick after a passed click; In Order passes only the
 * lowest unclicked red pane its slot packets showed, and a click it once passed counts as clicked for good.</li>
 * </ul>
 * Never active outside a case that sets {@link #mode}.
 */
public final class ForeignTermFilter {

    public enum Mode { OFF, ODIN, NOFRILLS }

    public static volatile Mode mode = Mode.OFF;
    /** NoFrills type switches that are ON (Panes, InOrder, StartsWith, Select, Colors). */
    public static volatile Set<String> nofrillsOn = Set.of("Panes", "InOrder", "StartsWith", "Select", "Colors");

    public static int canceled;
    public static int forwarded;
    public static int passed;
    public static int setSlots;

    private static boolean registered;
    private static int windowId = -1;
    private static String title = "";
    private static Odin odin;
    private static NoFrills nf;
    private static int tickCounter;

    private ForeignTermFilter() {
    }

    public static synchronized void enable(Mode m) {
        if (!registered) {
            registered = true;
            ClientTickEvents.END_CLIENT_TICK.register(ForeignTermFilter::tickEnd);
        }
        resetCounts();
        odin = null;
        nf = null;
        windowId = -1;
        mode = m;
    }

    public static void resetCounts() {
        canceled = 0;
        forwarded = 0;
        passed = 0;
        setSlots = 0;
    }

    public static String counts() {
        return "canceled=" + canceled + " forwarded=" + forwarded + " passed=" + passed + " slotPackets=" + setSlots;
    }

    // ---- hooks (testkit mixins) ----

    /** ClientPacketListener.handleOpenScreen, after the screen is set (Odin's ScreenEvent.Open). */
    public static void onOpen(int containerId, String screenTitle) {
        if (mode == Mode.OFF) {
            return;
        }
        windowId = containerId;
        title = screenTitle;
        odin = mode == Mode.ODIN ? Odin.of(screenTitle) : null;
        nf = null;
    }

    private static boolean live(Minecraft mc) {
        if (mc.player == null || mc.player.containerMenu.containerId != windowId || windowId <= 0) {
            odin = null;
            nf = null;
            return false;
        }
        return true;
    }

    /** AbstractContainerMenu.setItem TAIL (Odin's SetSlotEvent; NoFrills' SlotUpdateEvent comes from the same packet). */
    public static void onSetItem(AbstractContainerMenu menu, int slot, ItemStack stack) {
        Minecraft mc = Minecraft.getInstance();
        if (mode == Mode.OFF || !live(mc) || menu != mc.player.containerMenu) {
            return;
        }
        setSlots++;
        if (odin != null) {
            odin.updateSlot(menu.slots, slot, stack);
        } else if (mode == Mode.NOFRILLS) {
            nofrillsSlot(menu, slot, stack);
        }
    }

    /** ClientCommonPacketListenerImpl.handlePing: NoFrills counts a server tick per non-zero ping. */
    public static void onPing(int id) {
        if (id != 0) {
            tickCounter++;
        }
    }

    /** AbstractContainerScreen.slotClicked HEAD: true cancels the click. */
    public static boolean onSlotClicked(AbstractContainerMenu menu, Slot slot, int slotId, int button, ContainerInput input) {
        Minecraft mc = Minecraft.getInstance();
        if (mode == Mode.OFF || !live(mc) || menu != mc.player.containerMenu) {
            return false;
        }
        if (mode == Mode.ODIN) {
            if (odin == null) {
                return false;
            }
            if (odin.click(mc, slotId, button)) {
                forwarded++;
            } else {
                canceled++;
            }
            return true;
        }
        boolean cancel = nofrillsClick(slot, slotId, button, input);
        if (cancel) {
            canceled++;
        } else {
            passed++;
        }
        return cancel;
    }

    private static void tickEnd(Minecraft mc) {
        if (mode != Mode.ODIN || odin == null || !live(mc)) {
            return;
        }
        if (!odin.clicked.isEmpty() && System.currentTimeMillis() - odin.lastClick >= 600) {
            odin.clicked.clear();
            odin.updateSlot(mc.player.containerMenu.slots, 0, ItemStack.EMPTY);
        }
    }

    static String id(ItemStack s) {
        return s.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
    }

    // ---- Odin ----

    static final class Odin {
        private static final Pattern STARTS = Pattern.compile("^What starts with: '(\\w)'\\?$");
        private static final Pattern SELECT = Pattern.compile("^Select all the ([\\w ]+) items!$");
        private static final List<String> RUBIX = List.of("orange", "yellow", "green", "blue", "red");

        final String type;
        final int windowSize;
        final long opened = System.currentTimeMillis();
        final List<Integer> solution = new ArrayList<>();
        final List<int[]> clicked = new ArrayList<>();
        long lastClick;
        String letter;
        Set<String> prefixes;
        String lockedColor;
        final Set<Integer> rightSlots = new HashSet<>();
        final Map<Integer, Boolean> overrides = new HashMap<>();

        private Odin(String type, int windowSize) {
            this.type = type;
            this.windowSize = windowSize;
        }

        static Odin of(String t) {
            switch (t) {
                case "Correct all the panes!":
                    return new Odin("PANES", 45);
                case "Change all to same color!":
                    return new Odin("RUBIX", 45);
                case "Click in order!":
                    return new Odin("NUMBERS", 36);
                case "Click the button on time!":
                    return new Odin("MELODY", 54);
                default:
                    break;
            }
            Matcher m = STARTS.matcher(t);
            if (m.matches()) {
                Odin o = new Odin("STARTS_WITH", 45);
                o.letter = m.group(1);
                return o;
            }
            m = SELECT.matcher(t);
            if (m.matches()) {
                Odin o = new Odin("SELECT", 54);
                String c = m.group(1).replace("SILVER", "LIGHT GRAY").toUpperCase(Locale.ROOT).replace(' ', '_');
                o.prefixes = switch (c) {
                    case "BLACK" -> Set.of("black", "ink");
                    case "BLUE" -> Set.of("blue", "lapis");
                    case "BROWN" -> Set.of("brown", "cocoa");
                    case "WHITE" -> Set.of("white", "bone", "wool");
                    case "GREEN" -> Set.of("green", "cactus");
                    case "RED" -> Set.of("red", "rose");
                    case "YELLOW" -> Set.of("yellow", "dandelion");
                    case "LIGHT_GRAY" -> Set.of("silver", "light gray");
                    default -> Set.of(c.toLowerCase(Locale.ROOT).replace('_', ' '));
                };
                return o;
            }
            return null;
        }

        void updateSlot(List<Slot> slots, int slotIndex, ItemStack stack) {
            if (slots.isEmpty() || slotIndex < 0 || slotIndex >= windowSize - 9 || id(stack).equals("black_stained_glass_pane")) {
                return;
            }
            for (int i = 0; i < clicked.size(); i++) {
                if (clicked.get(i)[0] == slotIndex) {
                    clicked.subList(0, i + 1).clear();
                    break;
                }
            }
            solution.clear();
            solution.addAll(solve(slots.subList(0, windowSize - 9), slotIndex));
            for (int[] c : clicked) {
                simulateClick(c[0]);
            }
        }

        List<Integer> solve(List<Slot> slots, int updated) {
            List<Integer> out = new ArrayList<>();
            switch (type) {
                case "PANES" -> {
                    for (int i = 0; i < slots.size(); i++) {
                        if (id(slots.get(i).getItem()).equals("red_stained_glass_pane")) {
                            out.add(i);
                        }
                    }
                }
                case "NUMBERS" -> {
                    for (int i = 0; i < slots.size(); i++) {
                        if (id(slots.get(i).getItem()).equals("red_stained_glass_pane")) {
                            out.add(i);
                        }
                    }
                    out.sort(Comparator.comparingInt(i -> slots.get(i).getItem().getCount()));
                }
                case "RUBIX" -> {
                    Map<Integer, String> panes = new LinkedHashMap<>();
                    for (int i = 0; i < slots.size(); i++) {
                        String id = id(slots.get(i).getItem());
                        if (id.endsWith("_stained_glass_pane") && !id.equals("black_stained_glass_pane")) {
                            panes.put(i, id.substring(0, id.length() - "_stained_glass_pane".length()));
                        }
                    }
                    if (updated == 32 && lockedColor == null) {
                        int best = Integer.MAX_VALUE;
                        for (String goal : RUBIX) {
                            int sum = clicksFor(goal, panes).values().stream().mapToInt(Math::abs).sum();
                            if (sum < best) {
                                best = sum;
                                lockedColor = goal;
                            }
                        }
                    }
                    Map<Integer, Integer> clicks = lockedColor == null ? Map.of() : clicksFor(lockedColor, panes);
                    rightSlots.clear();
                    clicks.forEach((s, n) -> {
                        if (n < 0) {
                            rightSlots.add(s);
                        }
                        for (int k = 0; k < Math.abs(n); k++) {
                            out.add(s);
                        }
                    });
                }
                case "STARTS_WITH" -> {
                    overrides.computeIfPresent(updated, (k, v) -> true);
                    for (int i = 0; i < slots.size(); i++) {
                        ItemStack s = slots.get(i).getItem();
                        if (s.getHoverName().getString().toLowerCase(Locale.ROOT).startsWith(letter.toLowerCase(Locale.ROOT))
                                && overrides.get(i) != Boolean.TRUE && !s.hasFoil()) {
                            out.add(i);
                        }
                    }
                }
                case "SELECT" -> {
                    for (int i = 0; i < slots.size(); i++) {
                        ItemStack s = slots.get(i).getItem();
                        String name = s.getHoverName().getString().toLowerCase(Locale.ROOT);
                        if (!id(s).equals("black_stained_glass_pane") && !s.hasFoil() && prefixes.stream().anyMatch(name::startsWith)) {
                            out.add(i);
                        }
                    }
                }
                default -> {
                    // Melody: Odin's canClick ignores the solution
                }
            }
            return out;
        }

        private Map<Integer, Integer> clicksFor(String goal, Map<Integer, String> panes) {
            int g = RUBIX.indexOf(goal);
            Map<Integer, Integer> out = new LinkedHashMap<>();
            panes.forEach((slot, color) -> {
                int p = RUBIX.indexOf(color);
                int forward = p > g ? (g + RUBIX.size()) - p : g - p;
                int n = forward > 2 ? forward - RUBIX.size() : forward;
                if (n != 0) {
                    out.put(slot, n);
                }
            });
            return out;
        }

        void simulateClick(int slot) {
            if (type.equals("NUMBERS")) {
                if (!solution.isEmpty()) {
                    solution.remove(0);
                }
                return;
            }
            solution.remove(Integer.valueOf(slot));
        }

        boolean canClick(int slot, int button) {
            return switch (type) {
                case "NUMBERS" -> !solution.isEmpty() && solution.get(0) == slot;
                case "MELODY" -> slot == 16 || slot == 25 || slot == 34;
                case "RUBIX" -> solution.contains(slot) && (button == 1) == rightSlots.contains(slot);
                default -> solution.contains(slot);
            };
        }

        /** Odin's TerminalHandler.click; true when Odin sent its own click. */
        boolean click(Minecraft mc, int slot, int button) {
            if (type.equals("STARTS_WITH") && canClick(slot, button) && !overrides.containsKey(slot)) {
                overrides.put(slot, false);
            }
            if (!canClick(slot, button) || System.currentTimeMillis() - opened < 200) {
                return false;
            }
            int mapped = button == 1 && type.equals("RUBIX") ? 1 : 2;
            clicked.add(new int[]{slot, mapped});
            lastClick = System.currentTimeMillis();
            simulateClick(slot);
            mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, slot, mapped,
                    mapped == 2 ? ContainerInput.CLONE : ContainerInput.PICKUP, mc.player);
            return true;
        }
    }

    // ---- NoFrills ----

    static final class NoFrills {
        final String type;
        final Map<Integer, Integer> solutionMap = new HashMap<>();
        final Set<Integer> clickedSet = new HashSet<>();
        int openedAtTick = tickCounter;
        int clickedAtTick;

        NoFrills(String type) {
            this.type = type;
        }

        boolean disabled(int slot) {
            return solutionMap.getOrDefault(slot, -1) == -1;
        }
    }

    static String nfType(String t) {
        if (t.startsWith("Correct all the panes!")) return "Panes";
        if (t.startsWith("Click in order!")) return "InOrder";
        if (t.startsWith("What starts with:") && t.endsWith("?")) return "StartsWith";
        if (t.startsWith("Select all the") && t.endsWith("items!")) return "Select";
        if (t.startsWith("Change all to same color!")) return "Colors";
        if (t.equals("Click the button on time!")) return "Melody";
        return "None";
    }

    private static void nofrillsSlot(AbstractContainerMenu menu, int slotId, ItemStack stack) {
        if (stack.isEmpty() || slotId < 0 || slotId >= menu.slots.size()) {
            return;
        }
        String type = nfType(title);
        if (nf == null && !type.equals("None")) {
            nf = new NoFrills(type);
        }
        if (!nofrillsOn.contains(type)) {
            return;
        }
        Slot slot = menu.slots.get(slotId);
        String id = id(stack);
        String name = stack.getHoverName().getString().trim().toLowerCase(Locale.ROOT);
        switch (type) {
            case "Panes", "InOrder" -> {
                if (id.equals("red_stained_glass_pane")) {
                    nf.solutionMap.put(slot.index, type.equals("InOrder") ? stack.getCount() : 1);
                } else if (id.equals("lime_stained_glass_pane")) {
                    nf.solutionMap.put(slot.index, -1);
                }
            }
            case "StartsWith" -> {
                String c = String.valueOf(title.charAt(title.indexOf('\'') + 1)).toLowerCase(Locale.ROOT);
                if (!name.isEmpty()) {
                    nf.solutionMap.put(slot.index, name.startsWith(c) ? 1 : -1);
                }
            }
            case "Select" -> {
                String color = title.replace("Select all the", "").replace("items!", "").trim();
                String colorName = color.equals("SILVER") ? "light_gray" : color.toLowerCase(Locale.ROOT).replace(" ", "_");
                if (!name.isEmpty()) {
                    boolean ok = id.startsWith(colorName) || switch (colorName) {
                        case "black" -> id.equals("ink_sac");
                        case "blue" -> id.equals("lapis_lazuli");
                        case "brown" -> id.equals("cocoa_beans");
                        case "white" -> id.equals("bone_meal");
                        default -> false;
                    };
                    nf.solutionMap.put(slot.index, ok ? 1 : -1);
                }
            }
            case "Colors" -> {
                int index = List.of("green", "yellow", "orange", "red", "blue").indexOf(
                        id.endsWith("_stained_glass_pane") ? id.substring(0, id.length() - 19) : "");
                if (index != -1) {
                    nf.solutionMap.put(slot.index, index);
                }
            }
            default -> {
            }
        }
    }

    private static boolean nofrillsClick(Slot slot, int slotId, int button, ContainerInput input) {
        if (slot == null || input == ContainerInput.QUICK_MOVE) {
            return false;
        }
        String type = nfType(title);
        if (!nofrillsOn.contains(type) && !type.equals("Melody")) {
            return false;
        }
        if (nf == null || nf.openedAtTick + 10 >= tickCounter || nf.clickedAtTick + 1 >= tickCounter) {
            return true;
        }
        switch (type) {
            case "Panes", "StartsWith", "Select" -> {
                if (nf.clickedSet.contains(slot.index) || nf.disabled(slot.index)) {
                    return true;
                }
            }
            case "InOrder" -> {
                Integer first = nf.solutionMap.entrySet().stream()
                        .sorted(Map.Entry.comparingByValue())
                        .filter(e -> !nf.clickedSet.contains(e.getKey()) && !nf.disabled(e.getKey()))
                        .map(Map.Entry::getKey).findFirst().orElse(null);
                if (first == null || first != slotId) {
                    return true;
                }
            }
            case "Colors" -> {
                // NoFrills cancels a pane already at the most common color (its target 0), then steps its own copy.
                Map<Integer, Long> freq = new HashMap<>();
                nf.solutionMap.values().forEach(v -> freq.merge(v, 1L, Long::sum));
                int most = freq.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(-1);
                if (most != -1 && nf.solutionMap.containsKey(slotId) && nf.solutionMap.get(slotId) == most) {
                    return true;
                }
                Integer index = nf.solutionMap.get(slot.index);
                if (index != null && index != -1) {
                    int next = index - (button == 1 ? -1 : 1);
                    nf.solutionMap.put(slot.index, next < 0 ? 4 : next > 4 ? 0 : next);
                }
                nf.clickedAtTick = tickCounter;
                return false;
            }
            default -> {
                return false; // Melody: only the open / throttle gate above
            }
        }
        nf.clickedSet.add(slot.index);
        nf.clickedAtTick = tickCounter;
        return false;
    }
}

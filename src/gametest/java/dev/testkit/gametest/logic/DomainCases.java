package dev.testkit.gametest.logic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.TreeSet;

/** 356 score + blessings, 357 items + AP3 + routes, 358 MapCode + floor layout, 359 bazaar + party commands. */
final class DomainCases {

    private DomainCases() {
    }

    // ---- 356 ----------------------------------------------------------------------------------------------------

    private static final String SC = "scorecalc.ScoreCalculator";

    static void scoreAndBlessings(LogicCase c) {
        // Tables (ScoreCalculator.java:61-143).
        c.eq("secret fraction F1", 0.3, Mod.staticCall(SC, "requiredSecretFraction", "F1"));
        c.eq("secret fraction F6", 0.85, Mod.staticCall(SC, "requiredSecretFraction", "F6"));
        c.eq("secret fraction M3", 1.0, Mod.staticCall(SC, "requiredSecretFraction", "M3"));
        c.eq("time limit F7", 840, Mod.staticCall(SC, "timeLimitSeconds", "F7"));
        c.eq("time limit M4", 480, Mod.staticCall(SC, "timeLimitSeconds", "M4"));
        c.eq("time limit M6", 600, Mod.staticCall(SC, "timeLimitSeconds", "M6"));
        c.eq("speed F7 within limit", 100, Mod.staticCall(SC, "speedScore", "F7", 840));
        c.eq("speed F7 10% over", 95, Mod.staticCall(SC, "speedScore", "F7", 924));
        c.eq("speed F1 100% over", 73, Mod.staticCall(SC, "speedScore", "F1", 1200));
        c.eq("rank 269", "A", Mod.staticCall(SC, "rank", 269));
        c.eq("rank 270", "S", Mod.staticCall(SC, "rank", 270));
        c.eq("rank 300", "S+", Mod.staticCall(SC, "rank", 300));
        c.eq("rank 99", "D", Mod.staticCall(SC, "rank", 99));

        // Speed never rises with time.
        int rises = 0;
        for (String floor : List.of("E", "F1", "F4", "F7", "M1", "M6", "M7")) {
            int prev = 101;
            for (int s = 0; s <= 4000; s += 7) {
                int v = (Integer) Mod.staticCall(SC, "speedScore", floor, s);
                if (v > prev || v < 0) {
                    rises++;
                }
                prev = v;
            }
        }
        c.eq("speed score is non-increasing and non-negative", 0, rises);

        // Worked runs (hand-derived from the source; see docs/wp/logic.md).
        Object perfect = calc(inputs("F7", 100, 50, 5, 30, 100, 0, 3, 3, 0, 600, true, true, true, true, false,
                false, true));
        c.eq("perfect F7", "308 S+ need 40 left 0", brief(perfect));
        Object oneDeath = calc(inputs("F7", 100, 50, 5, 30, 100, 1, 3, 3, 0, 600, true, true, true, true, false,
                false, true));
        c.eq("perfect F7 with a spirit-pet death", "307 S+ need 42 left 0", brief(oneDeath));
        Object mid = calc(inputs("F7", 50, 20, 2, 11, 0, 0, 3, 1, 0, 300, false, false, false, false, false,
                false, false));
        c.eq("mid-run F7", "171 B need 38 left 18", brief(mid));
        Object mimicF5 = calc(inputs("F5", 100, 30, 0, 25, 100, 0, 2, 2, 0, 300, true, true, true, false, false,
                false, false));
        c.eq("mimic counts only from floor 6", 0, Mod.call(mimicF5, "bonus"));

        // S+ "secrets needed" is sufficient: finding exactly that many (all else perfect) reaches 300.
        int insufficient = 0;
        String firstBad = "";
        Random rng = new Random(356);
        for (int i = 0; i < 400; i++) {
            String floor = List.of("F5", "F6", "F7", "M5", "M7").get(rng.nextInt(5));
            int total = 2 * (10 + rng.nextInt(30)); // even, so 50% is exactly total/2 found
            int deaths = rng.nextInt(3);
            int crypts = rng.nextInt(6);
            int seconds = 200 + rng.nextInt(900);
            boolean mimic = rng.nextBoolean();
            Object now = calc(inputs(floor, 50, total / 2, crypts, 20, 70, deaths, 3, 2, 0, seconds, false, false,
                    mimic, false, false, false, false));
            int need = (Integer) Mod.call(now, "secretsNeeded");
            if (need < 0 || need == Integer.MAX_VALUE || need > total) {
                continue;
            }
            double pct = 100.0 * need / total;
            Object done = calc(inputs(floor, pct, need, crypts, 30, 100, deaths, 3, 3, 0, seconds, true, true, mimic,
                    false, false, false, false));
            if ((Integer) Mod.call(done, "total") < 300) {
                insufficient++;
                if (firstBad.isEmpty()) {
                    firstBad = floor + " total " + total + " need " + need + " -> " + brief(done);
                }
            }
        }
        c.eq("S+ secrets-needed is enough (400 random runs)", "", firstBad + (insufficient > 0 ? " x" + insufficient : ""));

        // Blessing numerals: three parsers in the mod read the same tab footer.
        int disagree = 0;
        String firstDis = "";
        for (int n = 1; n <= 399; n++) {
            String roman = (String) Mod.staticCall("blessings.Blessing", "toRoman", n);
            int b = (Integer) Mod.staticCall("blessings.Blessing", "parseRoman", roman);
            int e = (Integer) Mod.staticCall("experiments.ExperimentsProfitTracker", "romanToInt", roman);
            if (b != n || e != n) {
                disagree++;
                if (firstDis.isEmpty()) {
                    firstDis = n + " " + roman + " -> blessing " + b + ", experiments " + e;
                }
            }
        }
        c.eq("roman round trip 1..399 (Blessing, ExperimentsProfitTracker)", "", firstDis);
        c.eq("parseRoman rejects D/M", 0, Mod.staticCall("blessings.Blessing", "parseRoman", "MD"));
        c.eq("toRoman 1994", "MCMXCIV", Mod.staticCall("blessings.Blessing", "toRoman", 1994));
        c.eq("toRoman 0 falls back", "0", Mod.staticCall("blessings.Blessing", "toRoman", 0));
        // BlessingsFeature (Blessing.POWER pattern) vs P5State.POWER (dragon priority) on the same footer.
        java.util.regex.Pattern blessingPower =
                (java.util.regex.Pattern) Mod.call(Mod.enumValue("blessings.Blessing", "POWER"), "pattern");
        java.util.regex.Pattern p5Power = Mod.pattern("witherdragons.P5State", "POWER");
        List<Integer> divergent = new ArrayList<>();
        for (int n = 1; n <= 60; n++) {
            String footer = "Blessing of Power " + Mod.staticCall("blessings.Blessing", "toRoman", n);
            java.util.regex.Matcher a = blessingPower.matcher(footer);
            java.util.regex.Matcher p = p5Power.matcher(footer);
            int va = a.find() ? (Integer) Mod.staticCall("blessings.Blessing", "parseRoman", a.group(1)) : -1;
            int vp = p.find() ? (Integer) Mod.staticCall("witherdragons.P5State", "romanToInt", p.group(1)) : -1;
            if (va != n || vp != n) {
                divergent.add(n);
            }
        }
        c.check("Power blessing I..XXXIX read the same by BlessingsFeature and P5State",
                divergent.stream().noneMatch(n -> n < 40), "divergent at " + divergent);
        c.note("Power blessing levels where BlessingsFeature and P5State (dragon priority) read different values: "
                + divergent + " (P5State's regex, copied from Odin, stops at XXXIX; whether Hypixel goes past 39 is "
                + "not established here)");
    }

    private static Object inputs(String floor, double pct, int found, int crypts, int rooms, int cleared, int deaths,
                                 int puzzles, int puzzlesDone, int puzzlesFailed, int seconds, boolean blood,
                                 boolean boss, boolean mimic, boolean prince, boolean bat, boolean paul,
                                 boolean spirit) {
        return R.construct(SC + "$Inputs", floor, pct, found, crypts, rooms, cleared, deaths, puzzles, puzzlesDone,
                puzzlesFailed, seconds, blood, boss, mimic, prince, bat, paul, spirit);
    }

    private static Object calc(Object inputs) {
        return Mod.staticCall(SC, "calculate", inputs);
    }

    private static String brief(Object r) {
        return Mod.call(r, "total") + " " + Mod.call(r, "rank") + " need " + Mod.call(r, "secretsNeeded") + " left "
                + Mod.call(r, "secretsRemaining");
    }

    // ---- 357 ----------------------------------------------------------------------------------------------------

    private static final String II = "autoroutes.ItemIdentity";

    static void itemsAp3Routes(LogicCase c) {
        // ItemIdentity.
        c.eq("family hyperion", "WITHER_BLADE", Mod.staticCall(II, "family", "hyperion"));
        c.eq("family starred astraea", "WITHER_BLADE", Mod.staticCall(II, "family", "STARRED_ASTRAEA"));
        c.eq("family aotv", "INSTANT_TRANSMISSION", Mod.staticCall(II, "family", " aspect_of_the_void "));
        c.eq("family unknown is itself", "SPIRIT_SCEPTRE", Mod.staticCall(II, "family", "SPIRIT_SCEPTRE"));
        c.eq("name with reforge and stars", "SPIRIT_SCEPTRE",
                Mod.staticCall(II, "fromName", "§6Heroic Spirit Sceptre ✪✪✪"));
        c.eq("name with reforge", "HYPERION", Mod.staticCall(II, "fromName", "Withered Hyperion"));
        c.eq("name only codes", null, Mod.staticCall(II, "fromName", "§d"));
        ItemStack starred = withId(Items.IRON_SWORD, "STARRED_HYPERION", "§dHeroic Hyperion ✪✪");
        ItemStack astraea = withId(Items.IRON_SWORD, "ASTRAEA", "Astraea");
        ItemStack plain = SolverCases.stack(Items.DIAMOND_SWORD, 1, "Fabled Livid Dagger", false);
        c.eq("of: starred id", "HYPERION", Mod.staticCall(II, "of", starred));
        c.eq("of: name fallback", "LIVID_DAGGER", Mod.staticCall(II, "of", plain));
        c.eq("matches across the wither-blade family", true, Mod.staticCall(II, "matches", astraea, "HYPERION"));
        c.eq("no match across families", false, Mod.staticCall(II, "matches", astraea, "ASPECT_OF_THE_VOID"));
        // The mod reads the Skyblock id in ten places; they must agree.
        String[] readers = {"autoroutes.ItemIdentity", "cheatutils.CheatUtils", "itemprotect.ItemProtect",
            "autopuzzles.AutoPuzzleUtil", "abilitycooldown.AbilityCooldownState", "autodebuff.AutoDebuffFeature",
            "fastleap.LeapManager", "i4sensors.I4SensorsFeature", "livemap.autoclear.ClearExecutor",
            "objecthider.ObjectHiderFeature"};
        ItemStack noData = SolverCases.stack(Items.STICK, 1, "Stick", false);
        ItemStack blankId = withId(Items.STICK, "", "Stick");
        for (ItemStack s : List.of(starred, astraea, noData, blankId, ItemStack.EMPTY)) {
            Map<String, Object> seen = new LinkedHashMap<>();
            for (String r : readers) {
                try {
                    seen.put(r.substring(r.lastIndexOf('.') + 1), Mod.staticCall(r, "skyblockId", s));
                } catch (AssertionError e) {
                    seen.put(r.substring(r.lastIndexOf('.') + 1), "threw " + e.getMessage());
                }
            }
            // "" and null both mean "no id": I4SensorsFeature.skyblockId returns "" by design (its callers call
            // .contains on it, I4SensorsFeature.java:140).
            long distinct = seen.values().stream().map(v -> v == null || "".equals(v) ? "(no id)" : v.toString())
                    .distinct().count();
            c.check("skyblockId readers agree on " + (s.isEmpty() ? "an empty stack" : s.getHoverName().getString()
                    + " id " + ItemIdentityId(s)), distinct == 1, seen.toString());
        }

        // Since the FPS sweep (mod fps-sweep, 2026-10-07) several readers look at the stack's LIVE custom-data tag
        // (util/ItemNbt.view) instead of a deep copy. Same answers, and nothing may write to the stack.
        CompoundTag bookTag = new CompoundTag();
        bookTag.putString("id", "ENCHANTED_BOOK");
        CompoundTag ench = new CompoundTag();
        ench.putInt("ultimate_wise", 5);
        bookTag.put("enchantments", ench);
        ItemStack book = SolverCases.stack(Items.PAPER, 1, "Enchanted Book", false);
        book.set(DataComponents.CUSTOM_DATA, CustomData.of(bookTag));
        CompoundTag petTag = new CompoundTag();
        petTag.putString("id", "PET");
        petTag.putString("petInfo", "{\"type\":\"BLAZE\",\"tier\":\"LEGENDARY\"}");
        ItemStack pet = SolverCases.stack(Items.PAPER, 1, "Blaze", false);
        pet.set(DataComponents.CUSTOM_DATA, CustomData.of(petTag));
        String SIV = "itembrowser.SkyblockItemValue";
        c.eq("item value id: starred", "HYPERION", Mod.staticCall(SIV, "extractId", starred));
        c.eq("item value id: one-enchant book", "ENCHANTMENT_ULTIMATE_WISE_5", Mod.staticCall(SIV, "extractId", book));
        c.eq("item value id: pet", "PET_BLAZE", Mod.staticCall(SIV, "extractId", pet));
        c.eq("item value id: no custom data", null, Mod.staticCall(SIV, "extractId", noData));
        c.eq("item value id: blank id", null, Mod.staticCall(SIV, "extractId", blankId));
        c.eq("etherwarp item: plain sword", false, Mod.staticCall(II, "isEtherwarpItem", starred));
        CompoundTag merged = new CompoundTag();
        merged.putString("id", "ASPECT_OF_THE_VOID");
        merged.putInt("ethermerge", 1);
        ItemStack aotv = SolverCases.stack(Items.DIAMOND_SHOVEL, 1, "Aspect of the Void", false);
        aotv.set(DataComponents.CUSTOM_DATA, CustomData.of(merged));
        c.eq("etherwarp item: ethermerged AOTV", true, Mod.staticCall(II, "isEtherwarpItem", aotv));
        for (ItemStack s : List.of(starred, astraea, book, pet, aotv)) {
            CompoundTag before = s.get(DataComponents.CUSTOM_DATA).copyTag();
            for (String r : readers) {
                try {
                    Mod.staticCall(r, "skyblockId", s);
                } catch (AssertionError ignored) {
                    // a reader without skyblockId(ItemStack) was already reported above
                }
            }
            Mod.staticCall(SIV, "extractId", s);
            Mod.staticCall(II, "isEtherwarpItem", s);
            Mod.staticCall("itemprotect.ItemProtect", "isProtectedItem", s);
            c.check("readers leave " + s.getHoverName().getString() + "'s tag untouched",
                    before.equals(s.get(DataComponents.CUSTOM_DATA).copyTag()), before.toString());
        }

        // AP3 push sizes (docs/AP3.md: "All eighteen real key combinations produce just five sizes").
        Object model = R.construct("ap3.Ap3DiscretePlanner$Model");
        R.set(model, "baseSpeedAttr", 0.1365);
        R.set(model, "blockFriction", 0.6f);
        R.set(model, "onGround", true);
        R.set(model, "sneakMul", 0.3);
        R.set(model, "sprintKeyHeld", true);
        TreeSet<String> sizes = new TreeSet<>();
        int combos = 0;
        for (int fw = -1; fw <= 1; fw++) {
            for (int st = -1; st <= 1; st++) {
                for (boolean sneak : new boolean[]{false, true}) {
                    combos++;
                    Object a = R.construct("ap3.Ap3DiscretePlanner$Action", fw, st, sneak);
                    double eff = (Double) Mod.staticCall("ap3.Ap3DiscretePlanner", "effectiveLength", a, false, 0.3);
                    double speed = (Double) Mod.call(model, "tickSpeed", fw > 0);
                    sizes.add(String.format(Locale.ROOT, "%.5f", speed * eff));
                }
            }
        }
        c.eq("18 key combinations", 18, combos);
        c.eq("five push sizes (docs/AP3.md)", new TreeSet<>(List.of("0.00000", "0.13377", "0.13650", "0.17390",
                "0.17745")), sizes);

        // AP3 store precision (docs/AP3.md: angles were quantised on save; "Store at 5-6 decimals").
        String store = "ap3.Ap3Store";
        Object node = R.construct("ap3.Ap3Node", Mod.enumValue("ap3.Ap3Node$Type", "USE"), 12.345678912, 64.0625,
                -7.123456789, 123.456789f, -45.678901f);
        R.set(node, "useItemId", "HYPERION");
        JsonObject j1 = (JsonObject) Mod.staticCall(store, "writeNode", node);
        Object back = Mod.staticCall(store, "readNode", reparse(j1), 2);
        c.near("ap3 yaw survives a save", 123.456789f, (Float) R.get(back, "yaw"), 1e-4);
        c.near("ap3 pitch survives a save", -45.678901f, (Float) R.get(back, "pitch"), 1e-4);
        c.near("ap3 x survives a save", 12.345678912, (Double) R.get(back, "x"), 1e-6);
        c.eq("ap3 y snaps to a thousandth by design", 64.063, R.get(back, "y"));
        c.eq("ap3 USE node keeps its item", "HYPERION", R.get(back, "useItemId"));
        Object back2 = Mod.staticCall(store, "readNode", reparse((JsonObject) Mod.staticCall(store, "writeNode", back)), 2);
        c.eq("ap3 second save changes nothing", reparse(j1 = (JsonObject) Mod.staticCall(store, "writeNode", back)),
                reparse((JsonObject) Mod.staticCall(store, "writeNode", back2)));
        Object path = R.construct("ap3.Ap3Node", Mod.enumValue("ap3.Ap3Node$Type", "PATH"), 1.0, 70.0, 2.0, 0f, 0f);
        R.set(path, "minSpeed", 0.123456);
        R.set(path, "dirDeg", 33.333333);
        Object pathBack = Mod.staticCall(store, "readNode", reparse((JsonObject) Mod.staticCall(store, "writeNode", path)), 2);
        c.near("ap3 path minSpeed to 4 dp", 0.1235, (Double) R.get(pathBack, "minSpeed"), 1e-9);
        c.near("ap3 path dirDeg to 5 dp", 33.33333, (Double) R.get(pathBack, "dirDeg"), 1e-9);

        // Auto Routes route JSON round trip (RouteStore.writeRoute/readRoute).
        routes(c);
    }

    @SuppressWarnings("unchecked")
    private static void routes(LogicCase c) {
        String rs = "autoroutes.RouteStore";
        Object route = R.construct("autoroutes.Route", "Test Room");
        Object rp = R.construct("autoroutes.RoutePath");
        for (int i = 0; i < 5; i++) {
            Mod.call(rp, "add", R.construct("autoroutes.RoutePath$Sample", 1.0 + i, 64.0, -2.5, 90f, -10f, 65, true));
        }
        Mod.call(route, "setPath", rp);
        List<Object> nodes = (List<Object>) Mod.call(route, "nodes");
        Object ew = R.construct("autoroutes.RouteNode", Mod.enumValue("autoroutes.RouteNode$Type", "ETHERWARP"),
                10.25, 70.0, -4.75, 37.123456f, -12.987654f, 2);
        R.set(ew, "landingX", 20.5);
        R.set(ew, "landingY", 71.0);
        R.set(ew, "landingZ", -30.5);
        R.set(ew, "hasLanding", true);
        nodes.add(ew);
        Object use = R.construct("autoroutes.RouteNode", Mod.enumValue("autoroutes.RouteNode$Type", "USE_ITEM"),
                1.0, 70.0, 1.0, 0f, 0f, 3);
        R.set(use, "item", "HYPERION");
        nodes.add(use);
        Object brk = R.construct("autoroutes.RouteNode", Mod.enumValue("autoroutes.RouteNode$Type",
                "DUNGEON_BREAKER"), 2.0, 70.0, 2.0, 0f, 0f, 4);
        ((List<BlockPos>) R.get(brk, "breakerBlocks")).add(new BlockPos(3, 71, -2));
        nodes.add(brk);
        JsonObject json = (JsonObject) Mod.staticCall(rs, "writeRoute", route);
        Object back = Mod.staticCall(rs, "readRoute", "Test Room", reparse(json));
        List<Object> nb = (List<Object>) Mod.call(back, "nodes");
        c.eq("route node count", 3, nb.size());
        if (nb.size() == 3) {
            Object e = nb.get(0);
            c.eq("route etherwarp type", "ETHERWARP", ((Enum<?>) R.get(e, "type")).name());
            c.near("route x", 10.25, (Double) R.get(e, "x"), 1e-9);
            c.eq("route path index survives", 2, R.get(e, "pathIndex"));
            c.eq("route landing", "20.5/71.0/-30.5", R.get(e, "landingX") + "/" + R.get(e, "landingY") + "/"
                    + R.get(e, "landingZ"));
            c.eq("route use item", "HYPERION", R.get(nb.get(1), "item"));
            c.eq("route breaker block", List.of(new BlockPos(3, 71, -2)), R.get(nb.get(2), "breakerBlocks"));
            // The etherwarp's aim (RouteExecutor.java:1581, AutoRoutesEditScreen.java:444) is the node's yaw/pitch;
            // AP3 was fixed for exactly this quantisation (docs/AP3.md), routes still write 1 decimal.
            c.near("route etherwarp yaw survives a save", 37.123456f, (Float) R.get(e, "yaw"), 1e-3);
            c.near("route etherwarp pitch survives a save", -12.987654f, (Float) R.get(e, "pitch"), 1e-3);
        }
        c.eq("route path samples", 5, Mod.call(Mod.call(back, "path"), "size"));
        Object again = Mod.staticCall(rs, "readRoute", "Test Room",
                reparse((JsonObject) Mod.staticCall(rs, "writeRoute", back)));
        c.eq("route second save is stable", reparse((JsonObject) Mod.staticCall(rs, "writeRoute", back)),
                reparse((JsonObject) Mod.staticCall(rs, "writeRoute", again)));
        // Hostile path text never throws.
        c.noThrow("route path decode on junk", () -> Mod.staticCall("autoroutes.RoutePath", "decode",
                "1 2 3 4 5 99999999999999999999 1;x;;;" + "9".repeat(400), 36000, 512.0));
    }

    private static JsonObject reparse(JsonObject o) {
        return JsonParser.parseString(o.toString()).getAsJsonObject();
    }

    private static ItemStack withId(net.minecraft.world.item.Item item, String id, String name) {
        ItemStack s = SolverCases.stack(item, 1, name, false);
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return s;
    }

    private static String ItemIdentityId(ItemStack s) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d == null ? "(none)" : "\"" + d.copyTag().getStringOr("id", "(none)") + "\"";
    }

    // ---- 358 ----------------------------------------------------------------------------------------------------

    private static final String MC = "roomsim.MapCode";

    static void mapCodeAndLayout(LogicCase c) {
        c.eq("MapCode.selfTest", true, Mod.staticCall(MC, "selfTest"));
        Random rng = new Random(358);
        int bad = 0;
        String first = "";
        for (int round = 0; round < 200; round++) {
            int names = rng.nextInt(30);
            String[] table = new String[names];
            for (int i = 0; i < names; i++) {
                table[i] = "Room " + i + (rng.nextBoolean() ? " é中" : "");
            }
            int[] room = new int[121];
            int[] door = new int[121];
            int[] rot = new int[121];
            for (int i = 0; i < 121; i++) {
                room[i] = names == 0 ? -1 : rng.nextInt(names + 1) - 1;
                door[i] = rng.nextInt(5);
                rot[i] = rng.nextInt(4) * 90;
            }
            Object d = R.construct(MC + "$Decoded", table, room, door, rot);
            String code = (String) Mod.staticCall(MC, "encodeDecoded", d);
            Object back = Mod.staticCall(MC, "decode", code);
            boolean same = back != null && Arrays.equals(table, (String[]) Mod.call(back, "nameTable"))
                    && Arrays.equals(room, (int[]) Mod.call(back, "cellRoom"))
                    && Arrays.equals(door, (int[]) Mod.call(back, "cellDoor"))
                    && Arrays.equals(rot, (int[]) Mod.call(back, "cellRotation"));
            if (!same) {
                bad++;
                first = first.isEmpty() ? "round " + round + " code " + code.substring(0, 20) : first;
            }
        }
        c.eq("MapCode random round trips", "", first + (bad > 0 ? " x" + bad : ""));
        for (String junk : List.of("", "MC2:", "MC2:CwAA", "MC2:DAAA", "MC2:@@", "MC1:AAAA", "mc2:CwAA",
                "MC2:" + "A".repeat(5000), "MC2:////")) {
            Object r = c.noThrow("decode never throws on \"" + abbreviate(junk) + "\"", () -> {
                Object v = Mod.staticCall(MC, "decode", junk);
                return v == null ? "null" : v;
            });
            c.eq("decode rejects \"" + abbreviate(junk) + "\"", "null", r instanceof String ? r : "decoded");
        }
        // Fuzz: mutate valid codes; whatever decode accepts must be something encode would write back.
        Object base = R.construct(MC + "$Decoded", new String[]{"A", "B"}, fill(121, 0), fill(121, 1), fill(121, 90));
        String good = (String) Mod.staticCall(MC, "encodeDecoded", base);
        int accepted = 0;
        int threw = 0;
        String firstThrow = "";
        byte[] raw = java.util.Base64.getUrlDecoder().decode(good.substring(4));
        for (int i = 0; i < 2000; i++) {
            byte[] m = raw.clone();
            m[2 + rng.nextInt(Math.min(6, m.length - 2))] = (byte) rng.nextInt(256);
            String code = "MC2:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(m);
            Object d;
            try {
                d = Mod.staticCall(MC, "decode", code);
            } catch (AssertionError e) {
                threw++;
                firstThrow = firstThrow.isEmpty() ? "decode: " + e.getMessage() : firstThrow;
                continue;
            }
            if (d == null) {
                continue;
            }
            accepted++;
            try {
                Mod.staticCall(MC, "encodeDecoded", d);
            } catch (AssertionError e) {
                threw++;
                firstThrow = firstThrow.isEmpty() ? "re-encode of an accepted code: " + e.getMessage() : firstThrow;
            }
        }
        c.eq("fuzzed codes: decode never throws and everything it accepts re-encodes", "", firstThrow
                + (threw > 0 ? " x" + threw : ""));
        c.note("MapCode fuzz: 2000 mutated codes, " + accepted + " accepted");

        // SimFloorLayout: pure helpers, then determinism of a seeded generation (its comparators must not consume
        // the RNG - SimFloorLayout.java:520) when a room library is available.
        String fl = "roomsim.SimFloorLayout";
        c.eq("isTrap by type", true, Mod.staticCall(fl, "isTrap", "x", "TRAP"));
        c.eq("isTrap by name", true, Mod.staticCall(fl, "isTrap", "New Trap", null));
        c.eq("isTrap normal", false, Mod.staticCall(fl, "isTrap", "Arrow Trap", "NORMAL"));
        c.eq("one-door puzzle", true, Mod.staticCall(fl, "isOneDoor", "puzzle"));
        c.eq("fairy next to blood forbidden", true, Mod.staticCall(fl, "forbiddenPair", "FAIRY", "blood"));
        c.eq("cellKey negative z", 0xFFFFFFFFL, Mod.staticCall(fl, "cellKey", 0, -1));
        layoutDeterminism(c);
    }

    @SuppressWarnings("unchecked")
    private static void layoutDeterminism(LogicCase c) {
        String fl = "roomsim.SimFloorLayout";
        Map<String, Object> usable;
        try {
            usable = (Map<String, Object>) Mod.staticCall("roomsim.SimFloorGen", "usableRooms");
        } catch (AssertionError e) {
            c.skip("floor layout determinism", "usableRooms threw: " + e.getMessage());
            return;
        }
        if (usable == null || usable.size() < 10) {
            c.skip("floor layout determinism", "only " + (usable == null ? 0 : usable.size())
                    + " usable rooms in this client (room library not loaded)");
            return;
        }
        Map<String, Double> recent = (Map<String, Double>) R.get(fl, "RECENT");
        Map<String, Double> saved = new HashMap<>(recent);
        try {
            List<String> runs = new ArrayList<>();
            long t0 = System.nanoTime();
            for (long seed : new long[]{7L, 7L, 8L}) {
                recent.clear();
                recent.putAll(saved);
                Object floor = Mod.staticCall(fl, "generate", usable, 21, 36, 3, 6, new Random(seed));
                runs.add(floor == null ? "null" : String.valueOf(Mod.call(floor, "rooms")));
                if ((System.nanoTime() - t0) / 1e9 > 20) {
                    break;
                }
            }
            c.check("floor layout: same seed, same floor", runs.size() >= 2 && Objects.equals(runs.get(0), runs.get(1))
                    && !"null".equals(runs.get(0)), runs.size() >= 2 ? "differs" : "too slow to compare");
            c.note(String.format(Locale.ROOT, "floor layout: %d generation(s) from %d usable rooms in %.1f s",
                    runs.size(), usable.size(), (System.nanoTime() - t0) / 1e9));
        } finally {
            recent.clear();
            recent.putAll(saved);
        }
    }

    private static int[] fill(int n, int v) {
        int[] a = new int[n];
        Arrays.fill(a, v);
        return a;
    }

    private static String abbreviate(String s) {
        return s.length() > 16 ? s.substring(0, 16) + "...(" + s.length() + ")" : s;
    }

    // ---- 359 ----------------------------------------------------------------------------------------------------

    private static final String BZ = "bazaarflip.BazaarFlipScanner";

    @SuppressWarnings("unchecked")
    static void bazaarAndParty(LogicCase c) {
        // Book direction: the instant-buy book (buy_summary, BazaarApi.java:168) is walked cheapest first and never
        // past the NPC price (BazaarFlipScanner.size, :204).
        Object a = Mod.staticCall(BZ, "size", "P", "P", book(100, 10, 150, 10), 120.0, 1e9, 64);
        c.eq("flip stops at the npc price", "10 1000.0 200.0 false", flip(a));
        Object purse = Mod.staticCall(BZ, "size", "P", "P", book(100, 10, 150, 10), 120.0, 550.0, 64);
        c.eq("flip sized by the purse", "5 500.0 100.0 false", flip(purse));
        Object cap = Mod.staticCall(BZ, "size", "P", "P", book(10, 100), 11.0, 1e9, 1);
        c.eq("flip capped at one inventory", "36 360.0 36.0 true", flip(cap));
        c.eq("no flip at the npc price", null, Mod.staticCall(BZ, "size", "P", "P", book(120, 5), 120.0, 1e9, 64));
        c.eq("no flip when unaffordable", null, Mod.staticCall(BZ, "size", "P", "P", book(100, 5), 120.0, 50.0, 64));
        // Ranking (BazaarFlipScanner.java:176): best first.
        Object small = Mod.staticCall(BZ, "size", "S", "S", book(10, 5), 20.0, 1e9, 64);   // profit 50, 100%
        Object big = Mod.staticCall(BZ, "size", "B", "B", book(100, 50), 110.0, 1e9, 64); // profit 500, 10%
        List<Object> list = new ArrayList<>(List.of(small, big));
        list.sort((Comparator<Object>) Mod.staticCall(BZ, "comparator",
                Mod.enumValue("bazaarflip.BazaarFlipConfig$RankingMode", "TOTAL_PROFIT_PER_RUN")));
        c.eq("rank by profit: biggest profit first", "B", Mod.call(list.get(0), "productId"));
        list.sort((Comparator<Object>) Mod.staticCall(BZ, "comparator",
                Mod.enumValue("bazaarflip.BazaarFlipConfig$RankingMode", "PERCENTAGE_MARGIN")));
        c.eq("rank by margin: best margin first", "S", Mod.call(list.get(0), "productId"));

        // Party-command authorisation (PartyCommandsFeature.handle :170, isTeammate :326).
        String pc = "partycommands.PartyCommandsFeature";
        Object party = Mod.enumValue("chatcommands.ChatCommandsFeature$Channel", "PARTY");
        Object guild = Mod.enumValue("chatcommands.ChatCommandsFeature$Channel", "GUILD");
        Object dm = Mod.enumValue("chatcommands.ChatCommandsFeature$Channel", "PRIVATE");
        c.eq("!warp in party", "WARP", name(Mod.staticCall(pc, "commandFor", "warp", party)));
        c.eq("!warp in guild is no command", null, Mod.staticCall(pc, "commandFor", "warp", guild));
        c.eq("!kick in a DM is no command", null, Mod.staticCall(pc, "commandFor", "kick", dm));
        c.eq("!inv in a DM invites", "INVITE", name(Mod.staticCall(pc, "commandFor", "inv", dm)));
        c.eq("!boop in guild", "BOOP", name(Mod.staticCall(pc, "commandFor", "boop", guild)));
        c.eq("!m7 queues", "QUEUE_INSTANCE", name(Mod.staticCall(pc, "commandFor", "m7", party)));
        c.eq("!t6 is not a floor", null, Mod.staticCall(pc, "commandFor", "t6", party));
        c.eq("f7 instance", "catacombs_floor_seven", Mod.staticCall(pc, "instanceFor", "f7"));
        c.eq("m1 instance", "master_catacombs_floor_one", Mod.staticCall(pc, "instanceFor", "m1"));
        c.noThrow("instanceFor on 20 digits", () -> Mod.staticCall(pc, "instanceFor", "f99999999999999999999"));
        for (String cmd : List.of("WARP", "KICK", "TRANSFER", "PROMOTE", "QUEUE_INSTANCE")) {
            c.eq(cmd + " needs leader", true, Mod.staticCall(pc, "needsLeader",
                    Mod.enumValue("partycommands.PartyCommandsConfig$Command", cmd)));
        }
        c.eq("BOOP needs no leader", false, Mod.staticCall(pc, "needsLeader",
                Mod.enumValue("partycommands.PartyCommandsConfig$Command", "BOOP")));

        Collection<String> members = (Collection<String>) R.get("leapmenu.PartyTracker", "MEMBERS");
        List<String> savedMembers = new ArrayList<>(members);
        Object savedGate = R.get("util.SkyblockGate", "onSkyblock");
        Object cfg = Mod.cfg("partycommands.PartyCommandsConfig");
        boolean savedEnabled = (Boolean) Mod.call(cfg, "isEnabledRaw");
        try {
            members.clear();
            members.add("Killer560");
            c.eq("teammate exact", true, Mod.staticCall(pc, "isTeammate", "Killer560"));
            c.eq("teammate any case", true, Mod.staticCall(pc, "isTeammate", "killer560"));
            c.eq("prefix is not a teammate", false, Mod.staticCall(pc, "isTeammate", "Killer"));
            c.eq("stranger is not a teammate", false, Mod.staticCall(pc, "isTeammate", "Eve"));
            c.eq("17-char name is not a teammate", false, Mod.staticCall(pc, "isTeammate", HostileCases.LONG_NAME));
            c.eq("name with a space is not a teammate", false, Mod.staticCall(pc, "isTeammate", "Killer560 x"));
            // A stranger's party command reaches the teammate gate and is refused there (the log line proves which
            // gate refused it, so "returned false" is not just the feature being off).
            R.set("util.SkyblockGate", "onSkyblock", true);
            Mod.call(cfg, "setEnabled", true);
            long mark = LogTap.mark();
            Object handled = Mod.staticCall(pc, "handle", "Eve", "warp", party);
            boolean refusedAtGate = LogTap.since(mark).stream().anyMatch(l -> l.contains("not a party/dungeon teammate"));
            c.eq("stranger's !warp not handled", false, handled);
            c.check("stranger's !warp refused at the teammate gate", refusedAtGate, "no 'not a party/dungeon teammate' log line");
            Object guildCmd = Mod.staticCall(pc, "handle", "Eve", "warp", guild);
            c.eq("guild !warp not handled", false, guildCmd);
        } finally {
            members.clear();
            members.addAll(savedMembers);
            R.set("util.SkyblockGate", "onSkyblock", savedGate);
            Mod.call(cfg, "setEnabled", savedEnabled);
        }
    }

    private static List<Object> book(double... pa) {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < pa.length; i += 2) {
            out.add(R.construct("auction.BazaarOrderLevel", pa[i], (long) pa[i + 1], 1));
        }
        return out;
    }

    private static String flip(Object f) {
        if (f == null) {
            return null;
        }
        return Mod.call(f, "units") + " " + Mod.call(f, "spend") + " " + Mod.call(f, "profit") + " "
                + Mod.call(f, "capacityLimited");
    }

    private static String name(Object e) {
        return e == null ? null : ((Enum<?>) e).name();
    }
}

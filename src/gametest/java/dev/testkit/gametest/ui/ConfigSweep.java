package dev.testkit.gametest.ui;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Cases 310 and 311: every {@code *Config} singleton in the jar survives a restart (the mod's own rule, CLAUDE.md
 * "Every setting must survive a restart: add the field, load it, save it").
 *
 * <p>310, file level: {@code save()} -> read the JSON -> flip every top-level boolean and nudge every top-level number
 * -> write it -> {@code load()} -> {@code getInstance().save()} -> read again. A flipped boolean that comes back
 * unflipped was saved but not loaded (or loaded but not saved). Numbers that come back different are noted, not
 * failed: a clamp in load() is legitimate.
 *
 * <p>311, setter level: for every {@code isX/getX + setX} pair of boolean, int, float or double on the singleton:
 * set a new value, read it straight back (a setter that refuses the value is a clamp, noted and skipped), save,
 * load, read again. A value the getter returned before the save and not after the load is a setting that does not
 * survive a restart. Configs on the deny list are file-level only (their setters start network, OS or window
 * side effects).
 *
 * <p>Both restore the original file and reload it, so the run leaves every config as it found it. SkyblockGate is
 * switched off first ({@code util/SkyblockGate.setEnabled(false)}) so no setter is refused for not being on Skyblock.
 */
final class ConfigSweep {

    /** Setter-level is not done on these (file level still is): their setters act on the world outside the JVM. */
    static final Set<String> SETTER_DENY = Set.of(
            "discordrpc.DiscordRpcConfig", "voicetotext.VoiceToTextConfig", "shorts.ShortsConfig",
            "window.WindowModeConfig", "windowlayout.WindowLayoutConfig", "proxy.config.ProxyConfig",
            "bridge.BridgeConfig", "modchat.ModChatConfig", "relay.RelayConfig", "translate.TranslateConfig",
            "clicktranslate.ClickTranslateConfig", "auction.AuctionConfig", "updatecheck.UpdateCheckConfig",
            "supporters.SupportersConfig", "autojoinskyblock.AutoJoinSkyblockConfig", "partydata.PartyDataConfig",
            "interop.InteropConfig", "melody.MelodyHudConfig", "motionblur.MotionBlurConfig",
            "fullbright.FullbrightConfig", "copychat.CopyChatConfig", "screenshotcopy.ScreenshotCopyConfig");

    record Singleton(String rel, Class<?> cls) {
    }

    private ConfigSweep() {
    }

    static List<Singleton> singletons(UiCase c) {
        List<Singleton> out = new ArrayList<>();
        for (String fq : JarIndex.classNames(s -> s.endsWith("Config"))) {
            if (fq.contains(".mixin.")) {
                continue;
            }
            Class<?> k = JarIndex.peek(fq);
            if (k == null) {
                continue;
            }
            try {
                Method gi = k.getMethod("getInstance");
                if (Modifier.isStatic(gi.getModifiers())) {
                    out.add(new Singleton(JarIndex.rel(fq), k));
                }
            } catch (NoSuchMethodException ignored) {
                // not a singleton config (a record, a helper)
            }
        }
        return out;
    }

    /** The config file a singleton saves to: its static Path field(s) - CONFIG_PATH by convention. */
    static Path configPath(Class<?> k) {
        try {
            Field f = k.getDeclaredField("CONFIG_PATH");
            f.setAccessible(true);
            return (Path) f.get(null);
        } catch (NoSuchFieldException e) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) && f.getType() == Path.class) {
                    try {
                        f.setAccessible(true);
                        Path p = (Path) f.get(null);
                        if (p != null && p.toString().endsWith(".json")) {
                            return p;
                        }
                    } catch (IllegalAccessException ignored) {
                        // next
                    }
                }
            }
            return null;
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    static Object instance(Class<?> k) throws Throwable {
        return invoke(k.getMethod("getInstance"), null);
    }

    static void save(Object inst) throws Throwable {
        invoke(inst.getClass().getMethod("save"), inst);
    }

    static void load(Class<?> k) throws Throwable {
        invoke(k.getMethod("load"), null);
    }

    static Object invoke(Method m, Object target, Object... args) throws Throwable {
        m.setAccessible(true);
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    static String read(Path p) throws Exception {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    // ---- 310 ------------------------------------------------------------------------------------------------

    static void fileLevel(UiCase c) {
        List<Singleton> all = singletons(c);
        c.note(all.size() + " *Config singletons in the jar");
        c.check(all.size() >= 140, "only " + all.size() + " *Config singletons found - the jar listing is wrong");
        Map<String, String> verdicts = new TreeMap<>();
        int[] n = new int[4]; // pass, fail, no-file, flipped booleans checked
        for (Singleton s : all) {
            String v = c.onClient(mc -> fileRoundTrip(c, s, n));
            verdicts.put(s.rel(), v);
        }
        StringBuilder table = new StringBuilder();
        verdicts.forEach((k, v) -> table.append("\n  ").append(k).append(": ").append(v));
        c.note("per config:" + table);
        c.note(n[0] + " pass, " + n[1] + " fail, " + n[2] + " with no JSON file to flip; " + n[3]
                + " boolean keys flipped and checked");
        c.check(n[3] > 300, "only " + n[3] + " boolean keys were flipped - the sweep did not reach the configs");
    }

    private static String fileRoundTrip(UiCase c, Singleton s, int[] n) {
        Path p = configPath(s.cls());
        String original = null;
        try {
            Object inst = instance(s.cls());
            save(inst);
            if (p == null || !Files.isRegularFile(p)) {
                n[2]++;
                return "NO-FILE (no CONFIG_PATH .json after save()" + (p == null ? "" : ": " + p.getFileName()) + ")";
            }
            original = read(p);
            JsonElement parsed = JsonParser.parseString(original);
            if (!parsed.isJsonObject()) {
                n[2]++;
                return "SKIP (file is not a JSON object)";
            }
            JsonObject base = parsed.getAsJsonObject();
            JsonObject flipped = base.deepCopy();
            List<String> bools = new ArrayList<>();
            List<String> nums = new ArrayList<>();
            for (Map.Entry<String, JsonElement> e : base.entrySet()) {
                if (!e.getValue().isJsonPrimitive()) {
                    continue;
                }
                JsonPrimitive v = e.getValue().getAsJsonPrimitive();
                if (v.isBoolean()) {
                    flipped.addProperty(e.getKey(), !v.getAsBoolean());
                    bools.add(e.getKey());
                } else if (v.isNumber()) {
                    double d = v.getAsDouble();
                    if (d == Math.rint(d) && Math.abs(d) < 1e9) {
                        // ints: a nearby value (towards zero for big ones keeps most clamps happy)
                        long nv = d > 1 ? (long) d - 1 : (long) d + 1;
                        flipped.addProperty(e.getKey(), nv);
                    } else {
                        flipped.addProperty(e.getKey(), d * 0.9);
                    }
                    nums.add(e.getKey());
                }
            }
            Files.writeString(p, new GsonBuilder().setPrettyPrinting().create().toJson(flipped), StandardCharsets.UTF_8);
            load(s.cls());
            save(instance(s.cls()));
            JsonObject back = JsonParser.parseString(read(p)).getAsJsonObject();
            List<String> lostBools = new ArrayList<>();
            List<String> movedNums = new ArrayList<>();
            List<String> dropped = new ArrayList<>();
            for (String k : bools) {
                n[3]++;
                if (!back.has(k)) {
                    dropped.add(k);
                } else if (!back.get(k).equals(flipped.get(k))) {
                    lostBools.add(k);
                }
            }
            for (String k : nums) {
                if (!back.has(k)) {
                    dropped.add(k);
                } else if (back.get(k).getAsDouble() != flipped.get(k).getAsDouble()) {
                    movedNums.add(k + " " + flipped.get(k) + "->" + back.get(k));
                }
            }
            String detail = bools.size() + " bool, " + nums.size() + " num";
            if (!lostBools.isEmpty() || !dropped.isEmpty()) {
                n[1]++;
                String msg = s.rel() + " (" + p.getFileName() + "): "
                        + (lostBools.isEmpty() ? "" : "flipped boolean(s) came back unflipped after load()+save(): "
                        + lostBools + " ")
                        + (dropped.isEmpty() ? "" : "key(s) dropped by load()+save(): " + dropped);
                c.problem(msg.trim());
                return "FAIL " + detail + "; lost " + lostBools + (dropped.isEmpty() ? "" : " dropped " + dropped)
                        + (movedNums.isEmpty() ? "" : "; numbers moved " + movedNums);
            }
            n[0]++;
            return "PASS " + detail + (movedNums.isEmpty() ? "" : "; numbers moved (clamp?) " + movedNums);
        } catch (Throwable t) {
            n[1]++;
            c.problem(s.rel() + ": round trip threw " + UiCase.describe(t));
            return "FAIL threw " + UiCase.describe(t);
        } finally {
            if (original != null && p != null) {
                try {
                    Files.writeString(p, original, StandardCharsets.UTF_8);
                    load(s.cls());
                } catch (Throwable t) {
                    c.problem(s.rel() + ": could not restore its file: " + UiCase.describe(t));
                }
            }
        }
    }

    // ---- 311 ------------------------------------------------------------------------------------------------

    static void setterLevel(UiCase c) {
        List<Singleton> all = singletons(c);
        Map<String, String> verdicts = new TreeMap<>();
        int[] n = new int[5]; // properties checked, survived, lost, refused (clamp), configs denied
        for (Singleton s : all) {
            if (SETTER_DENY.contains(s.rel())) {
                n[4]++;
                verdicts.put(s.rel(), "DENIED (setter side effects; file level only)");
                continue;
            }
            verdicts.put(s.rel(), c.onClient(mc -> setterRoundTrip(c, s, n)));
        }
        StringBuilder table = new StringBuilder();
        verdicts.forEach((k, v) -> table.append("\n  ").append(k).append(": ").append(v));
        c.note("per config:" + table);
        c.note(n[0] + " properties checked: " + n[1] + " survived save+load, " + n[2] + " lost, " + n[3]
                + " refused the new value (clamp/validation, skipped); " + n[4] + " configs deny-listed");
        c.check(n[0] > 300, "only " + n[0] + " properties checked - the sweep did not reach the setters");
    }

    private static String setterRoundTrip(UiCase c, Singleton s, int[] n) {
        Path p = configPath(s.cls());
        String original = null;
        List<String> lost = new ArrayList<>();
        int checked = 0;
        int refused = 0;
        try {
            Object inst0 = instance(s.cls());
            save(inst0);
            if (p != null && Files.isRegularFile(p)) {
                original = read(p);
            }
            for (Method setter : inst0.getClass().getMethods()) {
                if (!setter.getName().startsWith("set") || setter.getParameterCount() != 1
                        || Modifier.isStatic(setter.getModifiers())) {
                    continue;
                }
                Class<?> t = setter.getParameterTypes()[0];
                if (t != boolean.class && t != int.class && t != float.class && t != double.class) {
                    continue;
                }
                String prop = setter.getName().substring(3);
                Method getter = getter(inst0.getClass(), prop, t);
                if (getter == null) {
                    continue;
                }
                Object inst = instance(s.cls());
                Object old = invoke(getter, inst);
                Object want = changed(old, t);
                try {
                    invoke(setter, inst, want);
                } catch (Throwable refusedByThrow) {
                    refused++;
                    continue;
                }
                Object now = invoke(getter, inst);
                if (!now.equals(want)) {
                    refused++;
                    invoke(setter, inst, old);
                    continue;
                }
                checked++;
                save(inst);
                load(s.cls());
                Object reloaded = invoke(getter, instance(s.cls()));
                if (!reloaded.equals(want)) {
                    lost.add(prop + " set " + want + ", after save+load " + reloaded);
                }
                Object inst2 = instance(s.cls());
                invoke(setter, inst2, old);
                save(inst2);
            }
        } catch (Throwable t) {
            c.problem(s.rel() + ": setter round trip threw " + UiCase.describe(t));
            return "FAIL threw " + UiCase.describe(t);
        } finally {
            try {
                if (original != null) {
                    Files.writeString(p, original, StandardCharsets.UTF_8);
                }
                load(s.cls());
            } catch (Throwable t) {
                c.problem(s.rel() + ": could not restore: " + UiCase.describe(t));
            }
        }
        n[0] += checked;
        n[1] += checked - lost.size();
        n[2] += lost.size();
        n[3] += refused;
        if (!lost.isEmpty()) {
            c.problem(s.rel() + ": setting(s) that do not survive save()+load(): " + lost);
            return "FAIL " + checked + " checked; lost " + lost;
        }
        return "PASS " + checked + " checked" + (refused == 0 ? "" : ", " + refused + " refused the new value");
    }

    private static Method getter(Class<?> k, String prop, Class<?> type) {
        for (String name : new String[]{"is" + prop, "get" + prop}) {
            try {
                Method m = k.getMethod(name);
                if (m.getReturnType() == type) {
                    return m;
                }
            } catch (NoSuchMethodException ignored) {
                // next
            }
        }
        return null;
    }

    private static Object changed(Object old, Class<?> t) {
        if (t == boolean.class) {
            return !(Boolean) old;
        }
        if (t == int.class) {
            int v = (Integer) old;
            return v > 1 ? v - 1 : v + 1;
        }
        if (t == float.class) {
            float v = (Float) old;
            return v == 0f ? 0.5f : v * 0.9f;
        }
        double v = (Double) old;
        return v == 0d ? 0.5d : v * 0.9d;
    }

    /** For the report: how many singletons are in the jar, by whether they have a JSON file after save(). */
    static Map<String, Integer> census(List<Singleton> all) {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("singletons", all.size());
        return m;
    }
}

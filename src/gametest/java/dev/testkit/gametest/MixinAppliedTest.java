package dev.testkit.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.service.MixinService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 1104-mixin-applied (mod scan scanmixin-1104): the mod's configs use {@code defaultRequire: 0}, so an injector whose
 * target moved (a method renamed, a call it wraps gone) applies zero times and nothing says so. This lists them.
 *
 * <p>Method: read every {@code *.mixins.json} named by the mod's fabric.mod.json, read each mixin class from the jar
 * with ASM, and for each injector handler ({@code @Inject}, {@code @ModifyArg}, {@code @WrapOperation} ...) and each
 * target class, fetch the POST-mixin bytes of the target from Mixin's own bytecode provider and look for a call to the
 * merged handler (Mixin renames a merged handler to {@code handler$zXX000$<name>}, so the call survives only if the
 * injector found its place). A plain-injector handler must be called from a method its {@code method} selector names; a
 * MixinExtras one (whose call sits in a generated wrapper) from anywhere in the target class. Also: every class in the
 * jar annotated {@code @Mixin} must be listed in a config, and every listed class must exist.
 *
 * <p>Control: testkit.mixinaudit.mixins.json holds one good and four deliberately broken handlers on Minecraft; the case
 * fails unless the good one is seen applied and all four broken ones are reported, so a vacuous audit cannot pass.
 *
 * <p>{@link #EXCEPTIONS}: handlers that legitimately never apply in this run, each with its reason.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class MixinAppliedTest implements FabricClientGameTest {

    private static final String NAME = "1104-mixin-applied";
    private static final String MOD = "killer560smod";

    /** Annotations whose handler call is inside the matched target method itself. */
    private static final Set<String> STRICT = Set.of(
            "Lorg/spongepowered/asm/mixin/injection/Inject;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
            "Lorg/spongepowered/asm/mixin/injection/Redirect;");

    /**
     * "MixinSimpleName#handler" or "MixinSimpleName" -> why it never applies here. Keep each reason specific.
     */
    private static final Map<String, String> EXCEPTIONS = new LinkedHashMap<>();

    static {
        EXCEPTIONS.put("ArrayBackedEventProbeMixin", "perfprobe/PerfProbeMixinPlugin applies the Perf Probe mixins only when PerfProbe.ENABLED (a dev property)");
        EXCEPTIONS.put("HudLayerProbeMixin", "Perf Probe, see ArrayBackedEventProbeMixin");
        EXCEPTIONS.put("ScreenProbeMixin", "Perf Probe, see ArrayBackedEventProbeMixin");
        EXCEPTIONS.put("MenuModMenuListMixin", "Mod Menu 21 has no widget.ModListWidget (pre-existing, the themed Mod Menu list selection never applies; @Pseudo)");
        String only263 = "26.3 only by design (the handler name ends 263; its 26.2 twin applies there): the call it wraps does not exist on 26.2";
        EXCEPTIONS.put("InventoryThemeInventoryBackgroundMixin#killer560smod$themeInventoryBackground263", only263);
        EXCEPTIONS.put("MenuCheckboxMixin#killer560smod$themedCheckbox263", only263);
        EXCEPTIONS.put("MenuEditBoxMixin#killer560smod$themedTextField263", only263);
        EXCEPTIONS.put("MenuLockIconButtonMixin#killer560smod$themedLockButton263", only263);
        EXCEPTIONS.put("MenuSliderMixin#killer560smod$themedSliderTrack263", only263);
        EXCEPTIONS.put("MenuSliderMixin#killer560smod$themedSliderHandle263", only263);
        EXCEPTIONS.put("MobSkinLocalPlayerStateMixin", "26.3 only: LevelExtractor.extractPlayerState does not exist on 26.2 (javap; the class javadoc says so)");
        EXCEPTIONS.put("Ap3ViewYawMixin#killer560smod$ap3FrozenPitch", "26.2 only: 26.3 inherits getViewXRot from Entity, Ap3ViewPitchMixin catches it there");
    }

    private record Injector(String mixin, String handler, String kind, boolean strict, List<String> selectors,
                            List<String> targets) {
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        Scenario.run(ctx, NAME, (server, s) -> TestMap.on(server).platform(8).catchFloor(140).survival().spawn(0.5, 0.5, 0f).build(),
                (server, s) -> body(s));
    }

    private static void body(Scenario s) {
        List<String> problems = new ArrayList<>();
        try {
            // ---- control: the audit must tell good from broken before its verdict on the mod counts ----
            List<String> control = new ArrayList<>();
            List<String> controlStructure = new ArrayList<>();
            audit("testkit", List.of("testkit.mixinaudit.mixins.json"), control, controlStructure, new ArrayList<>(), s, true);
            s.log("[mixinaudit] control structure: " + controlStructure);
            StringBuilder reflected = new StringBuilder();
            for (java.lang.reflect.Method m : net.minecraft.client.Minecraft.class.getDeclaredMethods()) {
                if (m.getName().contains("testkit$audit")) {
                    reflected.append(m.getName()).append(' ');
                }
            }
            s.log("[mixinaudit] reflected control methods in Minecraft: " + reflected);
            Set<String> controlMiss = new LinkedHashSet<>();
            for (String l : control) {
                controlMiss.add(l);
            }
            String joined = String.join("\n", control);
            for (String bad : new String[]{"testkit$auditBadMethod", "testkit$auditBadAt", "testkit$auditBadWrap", "testkit$auditBadCondition"}) {
                if (!joined.contains("#" + bad + " ")) {
                    problems.add("CONTROL: the broken handler " + bad + " was NOT reported (the audit is blind): " + control);
                }
            }
            if (joined.contains("#testkit$auditGood ")) {
                problems.add("CONTROL: the good handler testkit$auditGood was reported as unapplied: " + control);
            }
            s.log("[mixinaudit] control reported " + control.size() + " of 4 broken handlers");

            // ---- the mod ----
            List<String> misses = new ArrayList<>();
            List<String> structure = new ArrayList<>();
            ModContainer mod = FabricLoader.getInstance().getModContainer(MOD).orElseThrow();
            List<String> configs = new ArrayList<>();
            Path fmj = mod.findPath("fabric.mod.json").orElseThrow();
            JsonObject fm = JsonParser.parseString(Files.readString(fmj, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement e : fm.getAsJsonArray("mixins")) {
                configs.add(e.isJsonObject() ? e.getAsJsonObject().get("config").getAsString() : e.getAsString());
            }
            List<String> notes = new ArrayList<>();
            int[] counts = audit(MOD, configs, misses, structure, notes, s, false);
            for (String n : notes) {
                s.log("[mixinaudit] note " + n);
            }
            s.log("[mixinaudit] " + configs.size() + " configs, " + counts[0] + " mixin classes, " + counts[1] + " injector handlers, "
                    + counts[2] + " (handler, target) pairs checked, " + counts[3] + " applied, " + misses.size() + " unapplied");
            if (counts[0] < 250 || counts[2] < 400 || counts[3] < 400) {
                problems.add("the audit looked at too little (" + counts[0] + " mixins, " + counts[2] + " pairs, " + counts[3]
                        + " applied): it did not run");
            }
            List<String> staleExceptions = new ArrayList<>(EXCEPTIONS.keySet());
            for (String m : misses) {
                s.log("[mixinaudit] UNAPPLIED " + m);
                String key = m.substring(0, m.indexOf(' '));
                String simple = key.contains("#") ? key.substring(0, key.indexOf('#')) : key;
                String why = EXCEPTIONS.get(key);
                if (why == null) {
                    why = EXCEPTIONS.get(simple);
                    if (why != null) {
                        staleExceptions.remove(simple);
                    }
                } else {
                    staleExceptions.remove(key);
                }
                if (why == null) {
                    problems.add("unapplied: " + m);
                }
            }
            for (String m : structure) {
                s.log("[mixinaudit] STRUCTURE " + m);
                problems.add("structure: " + m);
            }
            for (String st : staleExceptions) {
                s.log("[mixinaudit] note: exception " + st + " matched nothing (" + EXCEPTIONS.get(st) + ")");
            }
        } catch (Exception e) {
            throw new AssertionError("[mixinaudit] failed: " + e, e);
        }
        if (!problems.isEmpty()) {
            throw new AssertionError("[mixinaudit] " + problems.size() + " problem(s): " + String.join(" | ", problems));
        }
        s.log("[mixinaudit] clean: every injector applied or excepted with a reason");
    }

    /**
     * Audits the named configs of one mod. Returns {mixin classes, handlers, pairs, applied}. Unapplied handlers go to
     * {@code misses} as "Mixin#handler (kind) -> target selector: why"; unlisted/missing classes to {@code structure}.
     */
    private static int[] audit(String modId, List<String> configs, List<String> misses, List<String> structure, List<String> notes, Scenario s,
                               boolean control) throws IOException {
        ModContainer mod = FabricLoader.getInstance().getModContainer(modId).orElseThrow();
        Path root = mod.getRootPaths().get(0);
        Set<String> listed = new LinkedHashSet<>();
        List<String> mixinClasses = new ArrayList<>();
        for (String cfg : configs) {
            Path p = mod.findPath(cfg).orElse(null);
            if (p == null) {
                structure.add("config " + cfg + " is named in fabric.mod.json but is not in the jar");
                continue;
            }
            JsonObject o = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
            String pkg = o.has("package") ? o.get("package").getAsString() : "";
            for (String key : new String[]{"mixins", "client", "server"}) {
                if (o.has(key)) {
                    for (JsonElement e : o.getAsJsonArray(key)) {
                        String cn = pkg + "." + e.getAsString();
                        listed.add(cn);
                        mixinClasses.add(cn);
                    }
                }
            }
        }
        Map<String, ClassNode> classes = new LinkedHashMap<>();
        for (String cn : mixinClasses) {
            try {
                classes.put(cn, MixinService.getService().getBytecodeProvider().getClassNode(cn.replace('.', '/'), false));
            } catch (ClassNotFoundException e) {
                structure.add("mixin class " + cn + " is listed in a config but is not in the jar");
            }
        }
        if (!control) {
            // Every @Mixin class in the jar is listed.
            try (Stream<Path> w = Files.walk(root)) {
                for (Path f : (Iterable<Path>) w.filter(x -> x.toString().endsWith(".class"))::iterator) {
                    String rel = root.relativize(f).toString().replace('\\', '/');
                    String cn = rel.substring(0, rel.length() - 6).replace('/', '.');
                    if (listed.contains(cn) || rel.startsWith("META-INF/")) {
                        continue;
                    }
                    ClassNode n = new ClassNode();
                    new ClassReader(Files.readAllBytes(f)).accept(n, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    if (hasAnnotation(n, "Lorg/spongepowered/asm/mixin/Mixin;")) {
                        structure.add("mixin class " + cn + " is in the jar but in no mixins.json (it never runs)");
                    }
                }
            }
        }

        int handlers = 0;
        int pairs = 0;
        int applied = 0;
        Map<String, ClassNode> postCache = new HashMap<>();
        for (Map.Entry<String, ClassNode> me : classes.entrySet()) {
            ClassNode mx = me.getValue();
            String simple = me.getKey().substring(me.getKey().lastIndexOf('.') + 1);
            List<String> targets = targetsOf(mx);
            if (targets.isEmpty()) {
                structure.add(simple + " has no @Mixin target");
                continue;
            }
            for (Injector inj : injectors(mx, simple, targets)) {
                handlers++;
                for (String target : inj.targets()) {
                    ClassNode post = postCache.computeIfAbsent(target, t -> {
                        try {
                            return read(postMixinBytes(t));
                        } catch (Throwable ex) {
                            s.log("[mixinaudit] cannot load " + t + ": " + ex);
                            return null;
                        }
                    });
                    // A handler that names several methods (one per Minecraft version, or several overloads) applied
                    // when ANY of them took it; the rest are the other version's names.
                    pairs++;
                    List<String> reasons = new ArrayList<>();
                    for (String sel : inj.selectors()) {
                        String why = check(post, inj, sel);
                        if (why != null) {
                            reasons.add(sel + ": " + why);
                        }
                    }
                    if (reasons.size() < inj.selectors().size()) {
                        applied++;
                        for (String r : reasons) {
                            notes.add(simple + "#" + inj.handler() + " -> " + target + " (other selector applied) " + r);
                        }
                    } else {
                        misses.add(simple + "#" + inj.handler() + " (" + inj.kind() + ") -> " + target + " " + String.join(" | ", reasons));
                    }
                }
            }
        }
        if (control) {
            ClassNode m = postCache.get("net.minecraft.client.Minecraft");
            if (m != null) {
                List<String> names = new ArrayList<>();
                for (MethodNode mn : m.methods) {
                    if (mn.name.contains("testkit$audit")) {
                        names.add(mn.name);
                    }
                }
                s.log("[mixinaudit] merged control methods in Minecraft: " + names);
            }
        }
        return new int[]{classes.size(), handlers, pairs, applied};
    }

    /** The target's bytes AFTER Mixin ran, from Knot's own delegate (the bytecode provider returns the original). */
    private static byte[] postMixinBytes(String className) throws Exception {
        ClassLoader cl = net.minecraft.client.Minecraft.class.getClassLoader();
        java.lang.reflect.Field df = null;
        for (Class<?> k = cl.getClass(); k != null && df == null; k = k.getSuperclass()) {
            for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                if (f.getType().getName().endsWith("KnotClassDelegate")) {
                    df = f;
                    break;
                }
            }
        }
        if (df == null) {
            throw new IllegalStateException("no KnotClassDelegate field on " + cl.getClass());
        }
        df.setAccessible(true);
        Object delegate = df.get(cl);
        java.lang.reflect.Method m = delegate.getClass().getDeclaredMethod("getPostMixinClassByteArray", String.class, boolean.class);
        m.setAccessible(true);
        byte[] b = (byte[]) m.invoke(delegate, className, true);
        if (b == null) {
            throw new ClassNotFoundException(className);
        }
        return b;
    }

    /** null when the injector applied for this selector, else the reason. */
    private static String check(ClassNode post, Injector inj, String selector) {
        if (post == null) {
            return "target class cannot be loaded";
        }
        String name = selector;
        String desc = null;
        int semi = name.indexOf(';');
        if (name.startsWith("L") && semi > 0 && name.indexOf('(') > semi) {
            name = name.substring(semi + 1);
        }
        int paren = name.indexOf('(');
        if (paren >= 0) {
            desc = name.substring(paren);
            name = name.substring(0, paren);
        }
        List<MethodNode> matched = new ArrayList<>();
        for (MethodNode mn : post.methods) {
            if (mn.name.equals(name) && (desc == null || mn.desc.equals(desc))) {
                matched.add(mn);
            }
        }
        if (name.equals("*")) {
            matched.addAll(post.methods);
        }
        if (matched.isEmpty()) {
            return "no such method in the target";
        }
        if (inj.strict()) {
            for (MethodNode mn : matched) {
                if (calls(mn, inj.handler())) {
                    return null;
                }
            }
            // A MixinExtras @WrapMethod on the same method moves the original body into "wrapMethod$..$<name>" or "<name>$mixinextras$wrapped$N".
            for (MethodNode mn : post.methods) {
                if ((mn.name.endsWith("$" + name) || mn.name.startsWith(name + "$mixinextras$wrapped")) && calls(mn, inj.handler())) {
                    return null;
                }
            }
            return "the target method exists but never calls the handler (the @At found nothing)";
        }
        for (MethodNode mn : post.methods) {
            if (calls(mn, inj.handler())) {
                return null;
            }
        }
        return "no call to the handler anywhere in the target (the @At found nothing)";
    }

    private static boolean calls(MethodNode mn, String handler) {
        if (mn.instructions == null) {
            return false;
        }
        for (AbstractInsnNode in : mn.instructions) {
            if (in instanceof MethodInsnNode mi && (mi.name.equals(handler) || mi.name.endsWith("$" + handler))) {
                return true;
            }
        }
        return false;
    }

    private static List<Injector> injectors(ClassNode mx, String simple, List<String> targets) {
        List<Injector> out = new ArrayList<>();
        for (MethodNode mn : mx.methods) {
            if (hasAnnotation(mn.visibleAnnotations, "Lorg/spongepowered/asm/mixin/injection/Surrogate;")
                    || hasAnnotation(mn.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/injection/Surrogate;")) {
                continue;
            }
            for (List<AnnotationNode> list : new List[]{mn.visibleAnnotations, mn.invisibleAnnotations}) {
                if (list == null) {
                    continue;
                }
                for (AnnotationNode an : list) {
                    if (!an.desc.startsWith("Lorg/spongepowered/asm/mixin/injection/")
                            && !an.desc.startsWith("Lcom/llamalad7/mixinextras/")) {
                        continue;
                    }
                    List<String> sels = stringArray(an, "method");
                    if (sels.isEmpty()) {
                        continue;
                    }
                    String kind = an.desc.substring(an.desc.lastIndexOf('/') + 1, an.desc.length() - 1);
                    out.add(new Injector(simple, mn.name, kind, STRICT.contains(an.desc), sels, targets));
                }
            }
        }
        return out;
    }

    private static List<String> targetsOf(ClassNode mx) {
        List<String> out = new ArrayList<>();
        List<AnnotationNode> all = new ArrayList<>();
        if (mx.visibleAnnotations != null) {
            all.addAll(mx.visibleAnnotations);
        }
        if (mx.invisibleAnnotations != null) {
            all.addAll(mx.invisibleAnnotations);
        }
        for (AnnotationNode an : all) {
            if (!an.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;") || an.values == null) {
                continue;
            }
            for (int i = 0; i < an.values.size(); i += 2) {
                String key = (String) an.values.get(i);
                Object v = an.values.get(i + 1);
                if (v instanceof List<?> l) {
                    for (Object o : l) {
                        if (o instanceof Type t) {
                            out.add(t.getClassName());
                        } else if (o instanceof String str) {
                            out.add(str.replace('/', '.'));
                        }
                    }
                }
            }
        }
        return out;
    }

    private static List<String> stringArray(AnnotationNode an, String key) {
        List<String> out = new ArrayList<>();
        if (an.values == null) {
            return out;
        }
        for (int i = 0; i < an.values.size(); i += 2) {
            if (key.equals(an.values.get(i)) && an.values.get(i + 1) instanceof List<?> l) {
                for (Object o : l) {
                    out.add((String) o);
                }
            }
        }
        return out;
    }

    private static boolean hasAnnotation(ClassNode n, String desc) {
        return hasAnnotation(n.visibleAnnotations, desc) || hasAnnotation(n.invisibleAnnotations, desc);
    }

    private static boolean hasAnnotation(List<AnnotationNode> list, String desc) {
        if (list != null) {
            for (AnnotationNode a : list) {
                if (a.desc.equals(desc)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode n = new ClassNode();
        new ClassReader(bytes).accept(n, ClassReader.SKIP_FRAMES);
        return n;
    }
}

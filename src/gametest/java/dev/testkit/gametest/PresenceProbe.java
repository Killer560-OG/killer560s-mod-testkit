package dev.testkit.gametest;

import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.KnownPacksManager;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.locale.Language;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The live half of 65-smoke-join-fingerprint for killer560smod (nofingerprint-1115): what a server could probe is read
 * from the RUNNING client, never from a fixed list, and the mod's two guards are made to act on something.
 * <ul>
 *   <li>keys: every key in any language resource of the mod's own pack, and every registered KeyMapping the mod owns
 *       (its {@code ModPresence.ownMappings()}, plus any mapping named or categorized after the mod);</li>
 *   <li>the keybind guard: {@code ModPresence.probeMappingForTest} registers a real mapping from mod code, so a keybind
 *       probe of it would resolve on an unguarded client;</li>
 *   <li>the language guard: the client's language is reloaded through the real {@code ClientLanguage.loadFrom} with two
 *       extra en_us resources, one from the mod's own pack (must be dropped) and one control from vanilla's pack (must
 *       load), then injected for the probe and put back afterwards;</li>
 *   <li>known packs: Fabric's known-packs answer (the client's {@code KnownPacksManager}) must not select a pack named
 *       after the mod, with vanilla's core pack as the control.</li>
 * </ul>
 */
final class PresenceProbe {

    static final String MOD_ID = "killer560smod";
    static final String PROBE_KEY = "killer560smod.testkit.probe";
    static final String PROBE_MAPPING = "key.killer560smod.testkit_probe";
    static final String CONTROL_KEY = "testkit.probe.control";
    static final String CONTROL_TEXT = "TestkitControl";
    private static final String PRESENCE = "com.killer560.hub.privacy.ModPresence";

    /** What prepare found; {@code previous} is the language to put back after the probe (null when none was injected). */
    record Live(Set<String> langKeys, Set<String> mappings, boolean probeMappingRegistered, Language previous,
                List<String> notes) {
    }

    private PresenceProbe() {
    }

    static boolean guardPresent() {
        try {
            Class.forName(PRESENCE);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** Client thread. Only with the mod's guard present (a -NoMod baseline has nothing to probe). */
    @SuppressWarnings("unchecked")
    static Live prepare(Minecraft mc) {
        List<String> notes = new ArrayList<>();
        ModUnderTest.staticCall(PRESENCE, "probeMappingForTest", new Class<?>[]{String.class}, new Object[]{PROBE_MAPPING});
        ResourceManager rm = mc.getResourceManager();
        Set<String> langKeys = new TreeSet<>();
        for (String ns : rm.getNamespaces()) {
            for (Resource r : rm.getResourceStack(Identifier.fromNamespaceAndPath(ns, "lang/en_us.json"))) {
                if (ownPack(r.sourcePackId())) {
                    try (var in = r.open()) {
                        langKeys.addAll(JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                                .getAsJsonObject().keySet());
                    } catch (Exception e) {
                        notes.add("could not read " + ns + ":lang/en_us.json from " + r.sourcePackId() + ": " + e);
                    }
                }
            }
        }
        Set<String> mappings = new TreeSet<>((Set<String>) ModUnderTest.staticCall(PRESENCE, "ownMappings"));
        boolean registered;
        try {
            var f = KeyMapping.class.getDeclaredField("ALL");
            f.setAccessible(true);
            Map<String, KeyMapping> all = (Map<String, KeyMapping>) f.get(null);
            registered = all.containsKey(PROBE_MAPPING);
            for (KeyMapping k : all.values()) {
                if (k.getName().contains("killer560") || k.getCategory().id().getNamespace().equals(MOD_ID)) {
                    mappings.add(k.getName());
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not read KeyMapping.ALL", e);
        }

        PackResources own = rm.listPacks().filter(p -> ownPack(p.packId())).findFirst().orElse(null);
        PackResources vanilla = rm.listPacks().filter(p -> p.packId().equals("vanilla")).findFirst().orElse(null);
        if (own == null || vanilla == null) {
            notes.add("no " + (own == null ? "mod" : "vanilla") + " client pack loaded, so the language guard could not be probed");
            return new Live(langKeys, mappings, registered, null, notes);
        }
        Identifier enUs = Identifier.withDefaultNamespace("lang/en_us.json");
        ResourceManager proxy = (ResourceManager) Proxy.newProxyInstance(ResourceManager.class.getClassLoader(),
                new Class<?>[]{ResourceManager.class}, (p, m, a) -> {
                    Object out = m.invoke(rm, a);
                    if (m.getName().equals("getResourceStack") && enUs.equals(a[0])) {
                        List<Resource> stack = new ArrayList<>((List<Resource>) out);
                        stack.add(resource(own, "{\"" + PROBE_KEY + "\":\"LEAK\"}"));
                        stack.add(resource(vanilla, "{\"" + CONTROL_KEY + "\":\"" + CONTROL_TEXT + "\"}"));
                        return stack;
                    }
                    return out;
                });
        String code = mc.getLanguageManager().getSelected();
        ClientLanguage lang = ClientLanguage.loadFrom(proxy, List.of("en_us", code).stream().distinct().toList(), false);
        notes.add("reloaded language with a mod-pack key (has: " + lang.has(PROBE_KEY) + ") and a control (has: "
                + lang.has(CONTROL_KEY) + ")");
        Language previous = Language.getInstance();
        Language.inject(lang);
        return new Live(langKeys, mappings, registered, previous, notes);
    }

    static void restore(Minecraft mc, Live live) {
        if (live != null && live.previous() != null) {
            Language.inject(live.previous());
        }
    }

    /** Client thread. Problems with the known-packs answer; empty when the mod's pack is never selected. */
    static List<String> knownPacks(List<String> log) {
        List<String> problems = new ArrayList<>();
        List<KnownPack> control = new KnownPacksManager().trySelectingPacks(List.of(KnownPack.vanilla("core")));
        String version = FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("1.1.0");
        List<KnownPack> asked = new ArrayList<>();
        for (String ns : List.of("vanilla", MOD_ID, "fabric")) {
            asked.add(new KnownPack(ns, MOD_ID, version));
        }
        List<KnownPack> got = new KnownPacksManager().trySelectingPacks(asked);
        log.add("known packs: control vanilla:core selected " + control.size() + ", mod-named " + asked + " selected " + got);
        if (control.size() != 1) {
            problems.add("known-packs control: vanilla:core was not selected (" + control + ") - the probe is blind");
        }
        if (!got.isEmpty()) {
            problems.add("known packs: the client selects a pack named after the mod: " + got);
        }
        return problems;
    }

    static boolean ownPack(String packId) {
        return packId != null && (packId.equals(MOD_ID) || packId.startsWith(MOD_ID + "_"));
    }

    private static Resource resource(PackResources pack, String json) {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        return new Resource(pack, () -> new ByteArrayInputStream(b));
    }
}

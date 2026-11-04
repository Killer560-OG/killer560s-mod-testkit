package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

import com.google.gson.JsonPrimitive;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.network.chat.Component;

/**
 * 1104-logic-scanbugs (mod scanbugs-1104, the SpotBugs / Error Prone pass). Three bugs the static scan found:
 * <ul>
 * <li>{@code TranslateFeature.WHISPER_RECIPIENT} and {@code RouteStore.parseHop} were written with {@code "\s+"} in a
 * plain Java string, which is a literal SPACE (Java 15+), not the regex whitespace class. A whisper or a saved path hop
 * split by a tab never matched, and the hop was dropped from the route.</li>
 * <li>{@code RevertMasterStarsFeature.modifyHoverName(null)} with the feature on called {@code rebuild(null)}, a
 * guaranteed NullPointerException; it now returns null.</li>
 * </ul>
 * Each check also runs a positive control so a pass proves the code under test acted.
 */
final class ScanBugsCases {

    static net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext ctx;

    private ScanBugsCases() {
    }

    static void scan(LogicCase c) throws Exception {
        // ---- 1. whisper recipient: a tab between the name and the message is whitespace too.
        Pattern whisper = Mod.pattern("translate.TranslateFeature", "WHISPER_RECIPIENT");
        Matcher space = whisper.matcher("Bob hello there");
        c.check("control: whisper 'Bob hello there' matches", space.matches() && "Bob".equals(space.group(1)),
                "pattern " + whisper.pattern());
        Matcher tab = whisper.matcher("Bob\thello there");
        boolean tabOk = tab.matches();
        c.check("whisper 'Bob<TAB>hello there' matches with recipient Bob and message 'hello there'",
                tabOk && "Bob".equals(tab.group(1)) && "hello there".equals(tab.group(2)),
                "pattern " + whisper.pattern());

        // ---- 2. a saved path hop split by tabs is read, not dropped.
        Method parseHop = Mod.cls("autoroutes.RouteStore").getDeclaredMethod("parseHop",
                com.google.gson.JsonElement.class);
        parseHop.setAccessible(true);
        Object spaced = parseHop.invoke(null, new JsonPrimitive("1.5 70 -2.5 90 10 4 69 -3 0.5 1 0.5"));
        c.check("control: space-separated hop parses", spaced != null, String.valueOf(spaced));
        Object tabbed = parseHop.invoke(null, new JsonPrimitive("1.5\t70\t-2.5\t90\t10\t4\t69\t-3\t0.5\t1\t0.5"));
        c.check("tab-separated hop parses", tabbed != null, String.valueOf(tabbed));
        if (tabbed != null) {
            c.check("tab-separated hop keeps its values (ox 1.5, pitch 10)",
                    ((Number) Mod.call(tabbed, "ox")).doubleValue() == 1.5
                            && ((Number) Mod.call(tabbed, "pitch")).floatValue() == 10f,
                    String.valueOf(tabbed));
        }

        // ---- 3. Revert Master Stars with a null name: no NullPointerException. Runs on the client thread, where
        // the feature itself runs (its cache asks Minecraft.getInstance(), which the gametest thread may not).
        String[] r = ctx.computeOnClient(mc -> {
            Method modify;
            try {
                modify = Mod.cls("revertmasterstars.RevertMasterStarsFeature").getDeclaredMethod("modifyHoverName",
                        Component.class);
            } catch (NoSuchMethodException e) {
                return new String[] {"no method", ""};
            }
            try (AutoCloseable on = Mod.with("revertmasterstars.RevertMasterStarsConfig", "Enabled", true)) {
                Component starred = Component.literal("Hyperion ✪✪✪✪✪➋");
                Object rebuilt = modify.invoke(null, starred);
                String control = rebuilt != null && rebuilt != starred ? "ok" : "not rebuilt: " + rebuilt;
                String nul;
                try {
                    Object out = modify.invoke(null, (Object) null);
                    nul = out == null ? "ok" : "returned " + out;
                } catch (InvocationTargetException e) {
                    nul = "threw " + e.getCause();
                }
                return new String[] {control, nul};
            } catch (Exception e) {
                return new String[] {"error " + e, ""};
            }
        });
        c.check("control: the feature rebuilds a master-starred name", "ok".equals(r[0]), r[0]);
        c.check("modifyHoverName(null) with the feature on returns null without throwing", "ok".equals(r[1]), r[1]);
    }
}

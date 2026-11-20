package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 1119-logic-translit (mod translit-1119): Click Translate never shows the source text back as a translation, and
 * a message typed in Latin letters for a non-Latin language is asked again in that language's script, once.
 * The service is a fake handed to {@code TranslateFeature.translateReadable}; every request it gets is recorded.
 */
final class TranslitCases {
    private static final String TF = "translate.TranslateFeature";
    private static final String ROMAN = "ti trq si purviq vurnat";

    private TranslitCases() {
    }

    /** Runs translateReadable against {@code service}; returns {result text or null, requests joined by |}. */
    private static String[] run(String text, Function<String, String[]> service) throws Exception {
        List<String> seen = new ArrayList<>();
        Class<?> fetch = Mod.cls(TF + "$Fetch");
        Class<?> resultCls = Mod.cls(TF + "$TranslationResult");
        Object fake = Proxy.newProxyInstance(fetch.getClassLoader(), new Class<?>[] {fetch}, (p, m, a) -> {
            String q = (String) a[0];
            seen.add(q);
            String[] r = service.apply(q);
            return resultCls.getDeclaredConstructors()[0].newInstance(r[0], r[1]);
        });
        Object out = Mod.staticCall(TF, "translateReadable", text, "en", fake);
        return new String[] {out == null ? null : String.valueOf(Mod.call(out, "text")), String.join("|", seen)};
    }

    static void translit(LogicCase c) throws Exception {
        // (a) the service hands the Latin text back: retry in Cyrillic, show the retry's translation
        String[] a = run(ROMAN, q -> q.equals(ROMAN) ? new String[] {q, "bg"}
                : new String[] {"you came back first", "bg"});
        c.check("(a) shown text is the retry's translation", "you came back first".equals(a[0]), String.valueOf(a[0]));
        String[] asked = a[1].split("\\|");
        c.check("(a) exactly two requests", asked.length == 2, a[1]);
        c.check("(a) second request is Cyrillic with u as schwa: ти тря си първия върнат",
                asked.length == 2 && "ти тря си първия върнат".equals(asked[1]), a[1]);

        // (b) both unchanged: nothing is shown
        String[] b = run(ROMAN, q -> new String[] {q, "bg"});
        c.check("(b) nothing shown when the retry is unchanged too", b[0] == null, String.valueOf(b[0]));
        c.check("(b) at most one retry", b[1].split("\\|").length == 2, b[1]);

        // (c) a normal foreign line: one request, its translation
        String[] n = run("hola amigo", q -> new String[] {"hello friend", "es"});
        c.check("(c) translation shown", "hello friend".equals(n[0]), String.valueOf(n[0]));
        c.check("(c) one request", "hola amigo".equals(n[1]), n[1]);

        // (d) an English line stays as it is (already in the target language): one request, no retry
        String[] e = run("hello there", q -> new String[] {q, "en"});
        c.check("(d) English line returned as is", "hello there".equals(e[0]), String.valueOf(e[0]));
        c.check("(d) one request", "hello there".equals(e[1]), e[1]);

        // a Latin-script language that returns the source gets no retry and no fake translation
        String[] f = run("hola amigo", q -> new String[] {q, "es"});
        c.check("(e) unchanged Spanish line shows nothing, no retry", f[0] == null && "hola amigo".equals(f[1]), f[0] + " / " + f[1]);
        // case and spacing only do not count as a translation
        String[] g = run(ROMAN, q -> new String[] {"  TI TRQ SI  purviq vurnat ", "bg"});
        c.check("(f) differing only in case and spaces is unchanged", g[0] == null, String.valueOf(g[0]));
    }
}

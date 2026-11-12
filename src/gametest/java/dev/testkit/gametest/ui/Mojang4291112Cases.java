package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;

import java.util.List;
import java.util.UUID;

/**
 * 1112-mojang-budget: the mod never spends Mojang's session join budget in the background (the owner's first Hypixel
 * join failed with "Status: 429", 2026-11-12). Idle: no join and no sessionserver call. Control: an explicit Profile
 * Viewer data request does reach the join (the attempt log sees it), so zero elsewhere is not a blind hook. Then a
 * connect just started and a 429 back-off each hold the same request back.
 */
final class Mojang4291112Cases {

    private Mojang4291112Cases() {
    }

    @SuppressWarnings("unchecked")
    private static List<String> attempts() {
        return (List<String>) Mod.staticCall("util.ModNet", "attemptsForTest");
    }

    private static long joins() {
        return attempts().stream().filter(s -> s.startsWith("session-join://")).count();
    }

    private static void request() throws Exception {
        Mod.staticCall("profileviewer.api.ProfileViewerApi", "fetchProfiles", UUID.randomUUID(), true);
    }

    static void budget(UiCase c) throws Exception {
        Mod.staticCall("util.MojangBudget", "resetForTest");
        Mod.staticCall("util.ModNet", "clearAttemptsForTest");
        c.ticks(200);
        c.check(joins() == 0 && attempts().stream().noneMatch(s -> s.contains("sessionserver")),
                "the mod called Mojang's session server with nobody asking: " + attempts());
        boolean noKey = ((String) Mod.get("profileviewer.ProfileViewerConfig", "getApiKey")).isEmpty();
        c.check(!noKey || !(Boolean) Mod.staticCall("profileviewer.api.ProfileViewerApi", "quietOk"),
                "a background caller is allowed with no key and no token");

        request(); // control: an explicit request does join
        c.ticks(100);
        long control = joins();
        c.check(control >= 1, "control failed: an explicit Profile Viewer request never reached the join, so zero proves nothing: " + attempts());

        Mod.staticCall("util.MojangBudget", "noteConnect");
        request();
        c.ticks(100);
        c.check(joins() == control, "a join was spent right after a server connect started: " + attempts());

        Mod.staticCall("util.MojangBudget", "resetForTest");
        Mod.staticCall("util.MojangBudget", "note429", 0L);
        request();
        c.ticks(100);
        c.check(joins() == control, "a join was spent during the 429 back-off: " + attempts());
        Mod.staticCall("util.MojangBudget", "resetForTest");
        c.note("joins: control " + control + ", none while busy");
    }
}

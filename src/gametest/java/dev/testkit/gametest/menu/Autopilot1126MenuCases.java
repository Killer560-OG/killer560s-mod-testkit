package dev.testkit.gametest.menu;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 1126-menu-autopilot-order (mod autopilot-menu-1126, killer560 2026-11-26): the Dungeons Autopilot tab has no status
 * line; its Order rows read Blood First, Clear Room Before Secreting, Allow Insta Clearing, Do Puzzles, Ice Fill First,
 * Quiz First, Skip Unnecessary Secrets, Face Door on Arrival in that order, with no Clear First, Insta Clear First or Key
 * Range; Secret Room Before Blood, Puzzle Stall Timeout and Route Stall Timeout are renamed; Room Order offers Fastest
 * Time, Largest To Smallest, Smallest To Largest, Edges In; One-Tap Warp shows only with the Spirit Sceptre and keeps its
 * value while hidden; an old file keeps Insta Clear First's value as Allow Insta Clearing, drops Clear First and Key Range
 * (the game's range is used), and a fresh file has Allow Insta Clearing ON (the old default). Legit jar: the tab builds
 * nothing.
 */
final class Autopilot1126MenuCases {

    private static final String AS = "autosecret.AutoSecretConfig";
    private static final String AC = "autoclear.AutoClearConfig";
    private static final String TAB = "com.killer560.hub.gui.tab.AutoClearTab";

    private Autopilot1126MenuCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1126-menu-autopilot-order", Autopilot1126MenuCases::order);
    }

    private static List<String> labels(Session c, boolean scan) {
        return c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            try {
                Object tab = Class.forName(TAB).getConstructor().newInstance();
                List<?> w;
                if (scan) {
                    w = (List<?>) tab.getClass().getMethod("searchScanWidgets").invoke(tab);
                } else {
                    w = (List<?>) tab.getClass().getMethod("buildWidgets", int.class, int.class, int.class,
                            Runnable.class).invoke(tab, 0, 0, 360, (Runnable) () -> { });
                }
                for (Object o : w) {
                    Object msg = o.getClass().getMethod("getMessage").invoke(o);
                    out.add(String.valueOf(msg.getClass().getMethod("getString").invoke(msg)).replaceAll("§.", "").trim());
                }
            } catch (ReflectiveOperationException e) {
                out.add("ERROR " + e);
            }
            return out;
        });
    }

    private static int at(List<String> labels, String prefix) {
        for (int i = 0; i < labels.size(); i++) {
            if (labels.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    static void order(Session c) throws Exception {
        if (!MenuKit.cheat()) {
            List<String> l = labels(c, false);
            c.check(l.isEmpty(), "the legit jar's Dungeons Autopilot tab should build nothing, builds " + l);
            return;
        }
        Path file = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-autosecret.json"));
        byte[] was = Files.exists(file) ? Files.readAllBytes(file) : null;
        Object weaponWas = c.onClient(mc -> Mod.get(AC, "getWeapon"));
        Object oneTapWas = c.onClient(mc -> Mod.get(AC, "getOneTap"));
        try {
            // ---- rows and labels -------------------------------------------------------------------------------------
            c.ctx().runOnClient(mc -> Mod.set(AC, "setWeapon", Mod.enumValue(AC + "$Weapon", "HYPERION")));
            List<String> l = labels(c, false);
            c.note("tab: " + l);
            String[] order = {"Blood First", "Clear Room Before Secreting", "Allow Insta Clearing", "Do Puzzles",
                    "Ice Fill First", "Quiz First", "Skip Unnecessary Secrets", "Face Door on Arrival"};
            int last = -1;
            for (String o : order) {
                int i = at(l, o);
                c.check(i > last, o + " should come after the row before it (at " + i + ", previous " + last + "): " + l);
                last = Math.max(last, i);
            }
            for (String gone : new String[]{"Clear First", "Insta Clear First", "Key Range", "Room Before Blood",
                    "Puzzle Wait", "Route Stall:", "Stopped", "Idle"}) {
                c.check(at(l, gone) < 0, "\"" + gone + "\" should be gone from the tab: " + l);
            }
            String status = c.onClient(mc -> String.valueOf(Mod.staticCall("autosecret.AutoSecretFeature", "statusText")));
            c.check(!l.contains(status), "the status line \"" + status + "\" should be gone from the tab");
            for (String now : new String[]{"Secret Room Before Blood:", "Puzzle Stall Timeout:", "Route Stall Timeout:",
                    "Room Order:"}) {
                c.check(at(l, now) >= 0, "\"" + now + "\" should be on the tab: " + l);
            }
            c.check(at(l, "Blood First") < at(l, "Secret Room Before Blood:"), "the Order switches come before the selectors");

            // ---- Room Order: four options in order, each shown on the row ---------------------------------------------
            Object orderWas = c.onClient(mc -> Mod.get(AS, "getSecretOrder"));
            List<String> shown = new ArrayList<>();
            for (String o : new String[]{"LEAST_ETHERWARPS", "LARGEST_TO_SMALLEST", "SMALLEST_TO_LARGEST", "EDGES_IN"}) {
                c.ctx().runOnClient(mc -> Mod.set(AS, "setSecretOrder", Mod.enumValue(AS + "$SecretOrder", o)));
                List<String> lo = labels(c, false);
                shown.add(lo.get(at(lo, "Room Order:")).substring("Room Order:".length()).trim());
            }
            c.ctx().runOnClient(mc -> Mod.set(AS, "setSecretOrder", orderWas));
            c.check(String.join(",", shown).equals("Fastest Time,Largest To Smallest,Smallest To Largest,Edges In"),
                    "Room Order options should read Fastest Time, Largest To Smallest, Smallest To Largest, Edges In: " + shown);
            int n = c.onClient(mc -> Mod.cls(AS + "$SecretOrder").getEnumConstants().length);
            c.check(n == 4, "Room Order should have exactly four options, has " + n);

            // ---- One-Tap Warp only with the Spirit Sceptre; value kept while hidden ------------------------------------
            c.ctx().runOnClient(mc -> Mod.set(AC, "setOneTap", Mod.enumValue(AC + "$OneTap", "AFTER_IMPACT")));
            c.check(at(l, "One-Tap Warp") < 0, "One-Tap Warp should be hidden with the Hyperion: " + l);
            c.check(at(labels(c, true), "One-Tap Warp") >= 0, "the menu search scan should still find One-Tap Warp");
            c.ctx().runOnClient(mc -> Mod.set(AC, "setWeapon", Mod.enumValue(AC + "$Weapon", "SPIRIT_SCEPTRE")));
            List<String> ls = labels(c, false);
            c.check(at(ls, "One-Tap Warp") >= 0, "One-Tap Warp should show with the Spirit Sceptre: " + ls);
            c.check(ls.get(Math.max(0, at(ls, "One-Tap Warp"))).contains("After Impact"),
                    "One-Tap Warp should keep its value while hidden: " + ls);

            // ---- old file migration / fresh defaults ------------------------------------------------------------------
            Files.createDirectories(file.getParent());
            Files.writeString(file, "{\"instaClear\": false, \"clearFirst\": true, \"keyBaseRange\": 3.0,"
                    + " \"secretOrder\": \"LARGEST_TO_SMALLEST\"}", StandardCharsets.UTF_8);
            c.ctx().runOnClient(mc -> Mod.staticCall(AS, "load"));
            c.check(Boolean.FALSE.equals(c.onClient(mc -> Mod.get(AS, "isInstaClear"))),
                    "an old Insta Clear First OFF should read Allow Insta Clearing OFF");
            double range = c.onClient(mc -> (Double) Mod.get(AS, "keyPickupRange"));
            c.check(Math.abs(range - 6.0) < 1e-9, "a saved Key Range should be ignored (the game's 1 + 5), reads " + range);
            c.check("LARGEST_TO_SMALLEST".equals(String.valueOf((Object) c.onClient(mc -> Mod.get(AS, "getSecretOrder")))),
                    "a saved Room Order should load");
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(AS), "save"));
            String saved = Files.readString(file, StandardCharsets.UTF_8);
            c.check(!saved.contains("clearFirst") && !saved.contains("keyBaseRange") && saved.contains("\"instaClear\": false"),
                    "the saved file should drop clearFirst and keyBaseRange and keep instaClear false: " + saved);
            Files.writeString(file, "{\"secretOrder\": \"NO_SUCH_ORDER\"}", StandardCharsets.UTF_8);
            c.ctx().runOnClient(mc -> Mod.staticCall(AS, "load"));
            c.check("LEAST_ETHERWARPS".equals(String.valueOf((Object) c.onClient(mc -> Mod.get(AS, "getSecretOrder")))),
                    "an unknown Room Order should load as Fastest Time");
            Files.deleteIfExists(file);
            c.ctx().runOnClient(mc -> Mod.staticCall(AS, "load"));
            c.check(Boolean.TRUE.equals(c.onClient(mc -> Mod.get(AS, "isInstaClear"))),
                    "a fresh config keeps the old default: Allow Insta Clearing ON");
            c.check(Boolean.FALSE.equals(c.onClient(mc -> Mod.call(Mod.cfg(AS), "clearsFirst"))),
                    "a fresh config (Solo 300) clears room by room, never every room before any secrets");
        } finally {
            if (was != null) {
                Files.write(file, was);
            } else {
                Files.deleteIfExists(file);
            }
            c.ctx().runOnClient(mc -> {
                Mod.staticCall(AS, "load");
                Mod.set(AC, "setWeapon", weaponWas);
                Mod.set(AC, "setOneTap", oneTapWas);
            });
        }
    }
}

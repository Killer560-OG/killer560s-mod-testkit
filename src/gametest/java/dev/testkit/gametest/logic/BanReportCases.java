package dev.testkit.gametest.logic;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 4101..4103-logic-banreport-*: Auto Report Bans (mod doc-ban-1101, to-do Doc "BAN LOGGER", killer560 2026-11-01). Opt-in,
 * OFF by default: a Hypixel ban disconnect screen builds ONE report (ban message with the Ban ID hidden, version, variant,
 * features and settings, the session's mod log) that is scrubbed of other players' names and UUIDs, IPs and tokens before it
 * goes to the relay. The test drives the REAL path: a {@link DisconnectedScreen} is constructed with the ban text, so the
 * mod's DisconnectedScreenMixin fires; the report body is read from the mod's own record of what it built
 * ({@code BanReportFeature.attemptsForTest}, written before the network - the run is offline, so nothing is sent).
 *
 * <pre>
 * 4101-logic-banreport-fake-ban   the ban screen builds exactly one report, scrubbed; seen again it builds none
 * 4102-logic-banreport-off        with the switch OFF (the default) a ban screen builds nothing and no log is kept
 * 4103-logic-banreport-nothing-else  other kicks, a forged ban line in chat, a ban from another server, a ban screen
 *                                 missing its Reason line: none builds a report
 * </pre>
 */
final class BanReportCases {

    static final String CFG = "banreport.BanReportConfig";
    static final String FEATURE = "banreport.BanReportFeature";
    static final String OTHER = "Bob_Otherguy";
    static final String OTHER_UUID = "0123abcd-4567-89ab-cdef-0123456789ab";
    static final String BAN_ID = "7F3A9C21";
    static final String TEMP_BAN = "You are temporarily banned for 29d 23h 59m 58s from this server!\n\n"
            + "Reason: Cheating through the use of unfair game advantages.\n"
            + "Find out more: https://www.hypixel.net/appeal\n\n"
            + "Ban ID: #" + BAN_ID + "\n"
            + "Sharing your Ban ID may affect the processing of your appeal!";
    static final String PERM_BAN = "You are permanently banned from this server!\n\n"
            + "Reason: Boosting detected on one or multiple SkyBlock profiles.\n"
            + "Find out more: https://www.hypixel.net/appeal\n\n"
            + "Ban ID: #1B2C3D4E\n"
            + "Sharing your Ban ID may affect the processing of your appeal!";

    private BanReportCases() {
    }

    private static void reset(boolean on) {
        Mod.call(Mod.cfg(CFG), "setEnabled", on);
        Mod.staticCall(FEATURE, "clearForTest");
    }

    @SuppressWarnings("unchecked")
    private static List<String> attempts() {
        return (List<String>) Mod.staticCall(FEATURE, "attemptsForTest");
    }

    @SuppressWarnings("unchecked")
    private static List<String> decisions() {
        return (List<String>) Mod.staticCall(FEATURE, "decisionsForTest");
    }

    /** The real disconnect screen, so the mod's mixin on its constructor runs; then back to the title. */
    private static void showDisconnect(Minecraft mc, String reason) {
        McCompat.setScreen(mc, new DisconnectedScreen(new TitleScreen(), Component.literal("Disconnected"),
                Component.literal(reason)));
        McCompat.setScreen(mc, new TitleScreen());
    }

    private static org.slf4j.Logger modLogger(String name) {
        return (org.slf4j.Logger) Mod.staticCall("util.ModLog", "get", name);
    }

    static void fakeBan(LogicCase c) {
        Minecraft mc = Minecraft.getInstance();
        boolean wasOn = (Boolean) Mod.get(CFG, "isEnabled");
        try {
            reset(true);
            String self = mc.getUser().getName();
            String selfUuid = mc.getUser().getProfileId().toString();
            Mod.staticCall("util.SessionLogTap", "clear");
            Mod.staticCall(FEATURE, "noteNameForTest", OTHER);
            modLogger("killer560smod-corrections").info("[Correction] AP3: server moved you 1.20 blocks while walking - carrying on");
            modLogger("killer560smod-leapmenu").info("[Leap] leaping to " + OTHER + " (" + OTHER_UUID + ") for " + self);
            modLogger("killer560smod-net").info("[Net] proxy 203.0.113.9 key=" + OTHER_UUID + " home C:\\Users\\SomeWinUser\\x");
            modLogger("killer560smod-modchat").info("[ModChat] " + OTHER + ": meet me at the secret spot");
            // A setting file the report must carry: Auto Update's, which has a top-level "enabled".
            Mod.call(Mod.cfg("autoupdate.AutoUpdateConfig"), "save");
            Mod.staticCall(FEATURE, "noteConnect", "mc.hypixel.net");

            showDisconnect(mc, TEMP_BAN);
            List<String> a = attempts();
            if (!c.eq("one Hypixel ban screen builds exactly one report", 1, a.size())) {
                c.note("decisions: " + decisions());
                return;
            }
            JsonObject r = JsonParser.parseString(a.get(0)).getAsJsonObject();
            String msg = r.get("message").getAsString();
            c.check("the report carries the ban line", msg.startsWith("You are temporarily banned for 29d 23h 59m 58s from this server!"), msg);
            c.check("and the Reason", msg.contains("Reason: Cheating through the use of unfair game advantages."), msg);
            c.check("the Ban ID is hidden", msg.contains("Ban ID: [hidden]") && !a.get(0).contains(BAN_ID), msg);
            c.check("the ban key is a 64-hex hash", r.get("banKey").getAsString().matches("[0-9a-f]{64}"));
            c.eq("a ban before joining is a login refusal", "login", r.get("kind").getAsString());
            c.eq("the variant is the jar's", Mod.isCheat() ? "cheat" : "legit", r.get("variant").getAsString());
            c.check("the Minecraft version is set", r.get("minecraft").getAsString().matches("26\\.\\d+(\\.\\d+)?"),
                    r.get("minecraft").getAsString());
            c.check("the mod version is set", !r.get("modVersion").getAsString().isEmpty());
            JsonArray features = r.getAsJsonArray("features");
            c.check("the features list is an array of short names", features != null && features.size() <= 300);
            String settings = r.get("settings").getAsString();
            c.check("the settings are included", settings.contains("== killer560smod-autoupdate.json =="), settings.length() + " chars");
            boolean auOn = (Boolean) Mod.get("autoupdate.AutoUpdateConfig", "isEnabled");
            boolean listed = false;
            for (var f : features) {
                listed |= f.getAsString().equals("autoupdate");
            }
            c.eq("a feature switched on is listed (Auto Update's own switch)", auOn, listed);
            c.check("the settings never carry the login or proxy file",
                    !settings.contains("session-login") && !settings.contains("account-proxies"));
            String log = r.get("log").getAsString();
            c.check("the log carries the server correction",
                    log.contains("[Correction] AP3: server moved you 1.20 blocks while walking - carrying on"), tail(log));
            c.check("another player's name is scrubbed", !log.contains(OTHER) && log.contains("<player>"), tail(log));
            c.check("another player's UUID is scrubbed", !a.get(0).contains(OTHER_UUID) && log.contains("<uuid>"), tail(log));
            c.check("the player's own name is kept", log.contains("for " + self), tail(log));
            c.check("an IP address is scrubbed", !a.get(0).contains("203.0.113.9"), tail(log));
            c.check("a home folder is scrubbed", !a.get(0).contains("SomeWinUser"), tail(log));
            c.check("Mod Chat (another player's chat) is never in the log", !log.contains("secret spot") && !log.contains("[ModChat]"),
                    tail(log));
            c.check("the player's own UUID is never replaced by <uuid> in the header fields",
                    !r.get("banKey").getAsString().contains("<"));
            c.check("the whole body is under the relay's 600 KB", a.get(0).getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 600_000,
                    a.get(0).length() + " chars");
            c.note("report: " + a.get(0).length() + " chars, " + features.size() + " feature(s), log " + log.length() + " chars, self "
                    + selfUuid.substring(0, 8));

            // Once per ban: the same screen again (the relay unreachable offline, so not yet marked) is held by the 10-minute
            // limit; once the relay has taken it (marked), it is "already reported" even with the time limits cleared.
            showDisconnect(mc, TEMP_BAN);
            c.eq("the same ban seen again builds no second report", 1, attempts().size());
            Object ban = Mod.staticCall("banreport.BanDetector", "parse", TEMP_BAN);
            String key = (String) Mod.call(ban, "key", selfUuid);
            Mod.call(Mod.cfg(CFG), "clearHistoryForTest");
            Mod.call(Mod.cfg(CFG), "markReported", key);
            showDisconnect(mc, TEMP_BAN);
            c.eq("a ban the relay already took is never sent again", 1, attempts().size());
            List<String> d = decisions();
            // The second sighting is held either by the 10-minute limit or, if the offline failure has not come back yet,
            // by the in-flight guard; both are "not again".
            c.check("the mod says why", d.size() == 3 && (d.get(1).startsWith("rate limited") || d.get(1).equals("already reported"))
                    && d.get(2).equals("already reported"), d.toString());

            // A different ban, kicked while playing: its own report, kind play.
            Mod.call(Mod.cfg(CFG), "clearHistoryForTest");
            Mod.staticCall(FEATURE, "noteJoinedForTest");
            showDisconnect(mc, PERM_BAN);
            List<String> a2 = attempts();
            c.eq("a different ban is its own report", 2, a2.size());
            if (a2.size() == 2) {
                JsonObject r2 = JsonParser.parseString(a2.get(1)).getAsJsonObject();
                c.eq("a kick while playing is kind play", "play", r2.get("kind").getAsString());
                c.check("the permanent ban line is carried", r2.get("message").getAsString()
                        .startsWith("You are permanently banned from this server!"));
            }
        } finally {
            reset(wasOn);
            Mod.call(Mod.cfg(CFG), "save");
        }
    }

    static void off(LogicCase c) {
        Minecraft mc = Minecraft.getInstance();
        boolean wasOn = (Boolean) Mod.get(CFG, "isEnabled");
        try {
            reset(true);
            modLogger("killer560smod-corrections").info("[Correction] line kept while on");
            reset(false);
            c.eq("Auto Report Bans is OFF by default in a fresh config", false, defaultOn());
            @SuppressWarnings("unchecked")
            List<String> kept = (List<String>) Mod.staticCall("util.SessionLogTap", "snapshot");
            c.eq("turning it off drops the session log it kept", 0, kept.size());
            modLogger("killer560smod-corrections").info("[Correction] line while off");
            kept = (List<String>) Mod.staticCall("util.SessionLogTap", "snapshot");
            c.eq("while off no log line is kept", 0, kept.size());
            Mod.staticCall(FEATURE, "noteConnect", "mc.hypixel.net");
            showDisconnect(mc, TEMP_BAN);
            showDisconnect(mc, PERM_BAN);
            c.eq("with the switch off a ban screen builds nothing", 0, attempts().size());
            c.eq("and is not even looked at", 0, decisions().size());
        } finally {
            reset(wasOn);
            Mod.call(Mod.cfg(CFG), "save");
        }
    }

    /** What a config with no file loads as: a JSON object without "enabled". */
    private static boolean defaultOn() {
        // The class's field initializer is the default; a config written by setEnabled(false) is what we just made, so
        // read the field of a fresh instance through its private constructor.
        try {
            Class<?> k = Mod.cls(CFG);
            java.lang.reflect.Constructor<?> ctor = k.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object fresh = ctor.newInstance();
            return (Boolean) Mod.call(fresh, "isEnabled");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    static void nothingElse(LogicCase c) {
        Minecraft mc = Minecraft.getInstance();
        boolean wasOn = (Boolean) Mod.get(CFG, "isEnabled");
        try {
            reset(true);
            Mod.staticCall(FEATURE, "noteConnect", "mc.hypixel.net");
            showDisconnect(mc, "Timed out");
            showDisconnect(mc, "You were kicked while joining that server!");
            showDisconnect(mc, "You were kicked: ban evasion is not allowed on this server.");
            showDisconnect(mc, "Disconnected\n\nYou are temporarily banned for 29d from this server!\nReason: x");
            showDisconnect(mc, "You are temporarily banned for 29d 23h from this server!\n\nFind out more: https://www.hypixel.net/appeal");
            showDisconnect(mc, "[MVP+] " + OTHER + ": You are permanently banned from this server!\nReason: lol");
            // A forged ban in chat, through the client's real system-message path, and as a player line.
            try {
                McCompat.chatListener(mc).handleSystemMessage(Component.literal(TEMP_BAN), false);
                McCompat.chatListener(mc).handleSystemMessage(Component.literal("[MVP+] " + OTHER + ": " + TEMP_BAN), false);
                c.check("the forged chat lines went through the chat listener", true);
            } catch (RuntimeException e) {
                c.skip("forged chat lines", "the chat listener refused at the title screen: " + e);
            }
            c.eq("no other kick and no chat line builds a report", 0, attempts().size());
            c.eq("none of them is even taken for a ban", 0, decisions().size());

            // The real ban text from a server that is not Hypixel.
            Mod.staticCall(FEATURE, "noteConnect", "play.example.net");
            showDisconnect(mc, TEMP_BAN);
            c.eq("a ban screen from another server builds nothing", 0, attempts().size());
            c.check("and says why", decisions().size() == 1 && decisions().get(0).startsWith("not a Hypixel connection"),
                    decisions().toString());
            Mod.staticCall(FEATURE, "noteConnect", "hypixel.net.evil.example");
            showDisconnect(mc, TEMP_BAN);
            c.eq("a host that only contains hypixel.net is not Hypixel", 0, attempts().size());

            // Control: the same client, Hypixel, the real screen - it does act (so the zeros above are not a dead feature).
            Mod.staticCall(FEATURE, "noteConnect", "MC.Hypixel.NET");
            showDisconnect(mc, TEMP_BAN);
            c.eq("control: the real ban screen on Hypixel does build one", 1, attempts().size());
        } finally {
            reset(wasOn);
            Mod.call(Mod.cfg(CFG), "save");
        }
    }

    private static String tail(String log) {
        return log.length() <= 600 ? log : "..." + log.substring(log.length() - 600);
    }
}

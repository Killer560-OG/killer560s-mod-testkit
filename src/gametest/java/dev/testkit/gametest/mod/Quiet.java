package dev.testkit.gametest.mod;

import java.util.ArrayList;
import java.util.List;

/**
 * The QUIET profile: switch off every mod feature that talks to the network or the real machine, so a case against
 * a server labelled {@code mc.hypixel.net} cannot reach a real service, post to a real relay, or open a window.
 *
 * <p>Belt and braces with the JVM flags build.gradle passes by default ({@code killer560.net.offline=true} refuses
 * every mod HTTP/WebSocket call; {@code killer560.test.noExternalOpen=true} turns OS opens into a log line). Those
 * stop the traffic; this stops the features from trying, which keeps their error paths out of a case's log.
 *
 * <p>{@link #apply()} FAILS LOUDLY when any setter or field is missing: a renamed setter that silently did nothing
 * would leave a network feature running under a scenario that believes it is quiet. Opt out of the whole profile
 * with {@code -PnoQuiet} ({@code testkit.quiet=false}); {@link #enabled()} says which is in force.
 *
 * <p>Call on the client thread ({@code ctx.runOnClient(mc -> Quiet.apply())}): the setters save config.
 */
public final class Quiet {

    /** {config class (relative to com.killer560.hub), boolean setter} - each set to false. */
    private static final String[][] SETTERS = {
            {"bridge.BridgeConfig", "setEnabled"},                    // Devonian/NoammAddons/Odin chat bridges
            {"modchat.ModChatConfig", "setEnabled"},                  // mod chat; drives RelayClient.update
            {"partydata.PartyDataConfig", "setShareEnabled"},         // party data sharing over the relay
            {"updatecheck.UpdateCheckConfig", "setNotifyOnStart"},    // GitHub release check
            {"discordrpc.DiscordRpcConfig", "setEnabled"},            // Discord IPC pipe
            {"voicetotext.VoiceToTextConfig", "setEnabled"},          // microphone + Vosk model download
            {"shorts.ShortsConfig", "setEnabled"},                    // browser via DevTools
            {"autojoinskyblock.AutoJoinSkyblockConfig", "setEnabled"},// sends /skyblock on join
            {"translate.TranslateConfig", "setEnabled"},              // Google Translate / MyMemory
            {"clicktranslate.ClickTranslateConfig", "setEnabled"},    // same, on click
            {"windowlayout.WindowLayoutConfig", "setRestoreOnLaunch"},// moves the real window
            {"auction.AuctionConfig", "setAhEnabled"},                // 44-page AH scan (heavy, network)
            {"auction.AuctionConfig", "setBazaarEnabled"},            // Bazaar polling
            {"supporters.SupportersConfig", "setCustomCosmeticsEnabled"}, // cosmetics fetch
            {"supporters.SupportersConfig", "setShareIfSupporter"},
    };

    /** {class, static boolean field} - features whose switch is a plain static. */
    private static final String[][] FIELDS = {
            {"spotify.SpotifyLyricsFeature", "enabled"},              // Spotify desktop + lrclib
    };

    private Quiet() {
    }

    /** Whether the profile is in force for this run ({@code -PnoQuiet} turns it off). */
    public static boolean enabled() {
        return !"false".equals(System.getProperty("testkit.quiet", "true"));
    }

    /** How many switches {@link #apply()} sets; a positive control compares against it. */
    public static int size() {
        return SETTERS.length + FIELDS.length;
    }

    /**
     * Turn every listed feature off. Returns one line per switch ({@code "bridge.BridgeConfig.setEnabled(false)"}),
     * and throws an AssertionError listing EVERY missing setter/field at once if any is missing.
     */
    public static List<String> apply() {
        List<String> done = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String[] s : SETTERS) {
            try {
                Mod.set(s[0], s[1], false);
                done.add(s[0] + "." + s[1] + "(false)");
            } catch (AssertionError e) {
                missing.add(s[0] + "." + s[1] + ": " + e.getMessage());
            }
        }
        for (String[] f : FIELDS) {
            try {
                Mod.setField(f[0], f[1], false);
                done.add(f[0] + "." + f[1] + " = false");
            } catch (AssertionError e) {
                missing.add(f[0] + "." + f[1] + ": " + e.getMessage());
            }
        }
        if (!missing.isEmpty()) {
            throw new AssertionError("[quiet] " + missing.size() + " of " + size()
                    + " switch(es) could not be set - the mod changed under the QUIET profile:\n  "
                    + String.join("\n  ", missing));
        }
        return done;
    }
}

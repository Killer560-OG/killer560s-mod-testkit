package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 87-chat-tidy: the mod's Chat Tidy (mod branch chat-tidy, killer560 2026-10-07) - Stack Duplicate Messages and the
 * Damage Message Hider - judged on what is IN THE CHAT WINDOW ({@code ChatComponent.allMessages}, through the mod's
 * {@code ChatTidy.testChatLines}), never on the log alone. Every line arrives from the dedicated server by
 * {@code tellraw}, so it takes the real system-chat packet path a Hypixel line takes.
 *
 * <p>Stacking: one line three times is ONE line ending "(x3)" in grey; different lines, the same text in a different
 * colour, separators and blank lines do not stack; with the setting off nothing stacks. Hider: one real line of every
 * family collected from his Dungeons log is hidden AND still reached the mod's ChatObserver (hidden at display, not at
 * receive); the same text inside a party message, with extra text after it, or from {@code /say} is shown; with the
 * toggle off nothing of it is hidden.
 *
 * <p>Since mod chat-batch (2026-10-08) these settings sit under Chat Hider's master switch ({@code setEnabled}, turned
 * on here and off at the end), and Hide Damage Messages hides both families with no per-family switch (the old
 * {@code setHideAbilityDamage}/{@code setHideIncomingHits} are checked to be gone). Interleaved stacking is 568.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class ChatTidyTests implements FabricClientGameTest {

    private static final String NAME = "87-chat-tidy";
    private static final String CFG = "com.killer560.hub.chattidy.ChatTidyConfig";
    private static final String TIDY = "com.killer560.hub.chattidy.ChatTidy";

    /** One genuine line per shape, verbatim from his 26.1.2 (Dungeons) logs, 2026-09 to 2026-10-07. */
    private static final List<String> ABILITY = List.of(
            "Your Implosion hit 2 enemies for 14,736,463.2 damage.",
            "Your Implosion hit 1 enemy for 28,946,171.5 damage.",
            "Your Guided Sheep hit 3 enemies for 1,234,567.8 damage.",
            "Your Spirit Sceptre hit 1 enemy for 302,114 damage.",
            "Your Witherborn hit 4 enemies for 9,876,543.2 damage.",
            "Your Explosive Shot hit 6 enemies for 4,500,000.1 damage.",
            "Your Spirit Pet hit 1 enemy for 12,000 damage.",
            "Your Thunderstorm hit 2 enemies for 88,000.5 damage.");
    private static final List<String> INCOMING = List.of(
            "A Crypt Wither Skull exploded, hitting you for 23,760 damage.",
            "A Spirit Sheep exploded, hitting you for 1,500 damage.",
            "A Chicken Mine exploded, hitting you for 2,000 damage.",
            "Maxor's Frenzy hit you for 3,200.5 damage.",
            "Storm's Giga Lightning hit you for 9,684.1 true damage.",
            "The Arrow Trap hit you for 1,892.8 damage!",
            "Maxor's Wither TNT hit you for 7200.0 damage.",
            "Bonzo's Balloon hit you for 4752.0 damage.",
            "The Stormy Crypt Dreadlord struck you for 772.1 damage!",
            "The Spirit Chicken's lightning struck you for 1,000 damage.",
            "Stormy Leech struck you for 400 damage!",
            "The Lost Adventurer used Dragon's Breath on you!",
            "The Frozen Adventurer used Ice Spray on you!",
            "Your bone plating reduced the damage you took by 718.3!");
    /** Decided: Hypixel has never been seen to send it, but if it does it is the same spam - hidden. */
    private static final String ZERO_HIT = "Your Implosion hit 0 enemies for 0 damage.";
    /** Must stay visible: a player's message quoting the line, and the line with other text after it. */
    private static final List<String> KEEP = List.of(
            "Party > [MVP+] Eve: Your Implosion hit 2 enemies for 1,000 damage.",
            "Party > [MVP++] kwyt: Stormy Skull struck you for 900 damage!",
            "Guild > Eve [Member]: A Crypt Wither Skull exploded, hitting you for 23,760 damage.",
            "Your Implosion hit 2 enemies for 14,736,463.2 damage. lol",
            "[BOSS] The Watcher: My Watchful Eyes are keeping their...eyes...on you!",
            "Eve's Wish healed you for 1,000 health and granted you an absorption shield with 500 health!");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        Scenario.run(ctx, NAME,
                (server, scenario) -> TestMap.on(server)
                        .platform(-4200, 100, 4200, 6)
                        .catchFloor(80)
                        .survival()
                        .spawn(-4199.5, 4200.5, 0f)
                        .build(),
                (server, scenario) -> {
                    try {
                        body(ctx, server, scenario);
                    } finally {
                        ModUnderTest.turnOff(CFG, "setStackDuplicates");
                        ModUnderTest.turnOff(CFG, "setHideDamageMessages");
                        ModUnderTest.turnOff(CFG, "setEnabled");
                    }
                });
    }

    private static void body(ClientGameTestContext ctx, TestServer server, Scenario scenario) {
        ctx.waitTicks(40);
        Object cfg = ModUnderTest.config(CFG);
        long failuresBefore = num("testFailures");
        ModUnderTest.set(cfg, "setEnabled", true); // Chat Hider's master switch

        // ---------------- stacking ----------------
        ModUnderTest.set(cfg, "setStackDuplicates", true);
        ModUnderTest.set(cfg, "setHideDamageMessages", false);
        long stackedBefore = num("testStacked");
        String alpha = "Tidy stack probe alpha 1,234";
        for (int i = 0; i < 3; i++) {
            send(ctx, server, alpha, "aqua");
        }
        List<String> lines = chat(ctx, 6);
        scenario.log("chat after 3x alpha (newest first): " + lines);
        expect(lines.get(0).equals(alpha + " (x3)"), "newest line is \"" + alpha + " (x3)\"", lines);
        expect(count(lines, alpha) == 0, "no un-stacked copy of alpha is left", lines);
        String colour = (String) onClient(ctx, () -> ModUnderTest.staticCall(TIDY, "testNewestLastColour"));
        expect("gray".equalsIgnoreCase(colour), "the (x3) suffix is grey (read " + colour + ")", lines);
        expect(num("testStacked") - stackedBefore == 2, "the mod counted 2 stacks", lines);
        scenario.log("PASS stack: 3 identical lines -> one \"" + lines.get(0) + "\", grey suffix");

        send(ctx, server, "Tidy probe beta", "white");
        send(ctx, server, "Tidy probe gamma", "white");
        lines = chat(ctx, 4);
        expect(lines.get(0).equals("Tidy probe gamma") && lines.get(1).equals("Tidy probe beta"),
                "different lines stay two lines", lines);
        scenario.log("PASS different lines do not stack: " + lines.subList(0, 2));

        send(ctx, server, "Tidy probe delta", "red");
        send(ctx, server, "Tidy probe delta", "green");
        lines = chat(ctx, 4);
        expect(lines.get(0).equals("Tidy probe delta") && lines.get(1).equals("Tidy probe delta"),
                "same text in a different colour stays two lines", lines);
        scenario.log("PASS same text, different colour does not stack: " + lines.subList(0, 2));

        send(ctx, server, "-----------------------------", "blue");
        send(ctx, server, "-----------------------------", "blue");
        send(ctx, server, "", "white");
        send(ctx, server, "", "white");
        lines = chat(ctx, 4);
        expect(lines.get(0).isEmpty() && lines.get(1).isEmpty()
                        && lines.get(2).equals("-----------------------------")
                        && lines.get(3).equals("-----------------------------"),
                "separators and blank lines never stack", lines);
        scenario.log("PASS separators and blank lines keep their spacing: " + lines);

        ModUnderTest.set(cfg, "setStackDuplicates", false);
        send(ctx, server, "Tidy probe epsilon", "white");
        send(ctx, server, "Tidy probe epsilon", "white");
        lines = chat(ctx, 3);
        expect(lines.get(0).equals("Tidy probe epsilon") && lines.get(1).equals("Tidy probe epsilon"),
                "with Stack Duplicate Messages off nothing stacks", lines);
        scenario.log("PASS stacking off: two lines");

        // ---------------- hider ----------------
        ModUnderTest.set(cfg, "setHideDamageMessages", true);
        long hiddenBefore = num("testHidden");
        List<String> hide = new ArrayList<>(ABILITY);
        hide.addAll(INCOMING);
        hide.add(ZERO_HIT);
        send(ctx, server, "Tidy marker before hides", "white");
        for (String line : hide) {
            send(ctx, server, line, "gray");
        }
        lines = chat(ctx, 10);
        expect(lines.get(0).equals("Tidy marker before hides"), "every damage line hidden (the marker is still newest)", lines);
        List<String> window = chat(ctx, 80);
        for (String line : hide) {
            expect(!window.contains(line), "not in the chat window: " + line, lines);
        }
        List<String> observed = observed(ctx);
        for (String line : hide) {
            expect(observed.contains(line), "hidden line still reached ChatObserver: " + line, observed);
        }
        expect(num("testHidden") - hiddenBefore == hide.size(),
                "the mod counted " + hide.size() + " hides (counted " + (num("testHidden") - hiddenBefore) + ")", lines);
        scenario.log("PASS hider: " + hide.size() + " damage lines (" + ABILITY.size() + " ability, " + INCOMING.size()
                + " incoming, 1 zero-hit) absent from the chat window, all seen by ChatObserver");

        for (String line : KEEP) {
            send(ctx, server, line, "white");
            lines = chat(ctx, 1);
            expect(lines.get(0).equals(line), "kept: " + line, lines);
        }
        server.command("say Your Implosion hit 2 enemies for 1,000 damage.");
        ctx.waitTicks(10);
        lines = chat(ctx, 1);
        expect(lines.get(0).contains("Your Implosion hit 2 enemies for 1,000 damage."), "a /say of the line is kept", lines);
        scenario.log("PASS player-shaped and extended lines kept (" + (KEEP.size() + 1) + "), newest: " + lines.get(0));

        for (String gone : List.of("setHideAbilityDamage", "setHideIncomingHits")) {
            boolean present = false;
            for (java.lang.reflect.Method m : cfg.getClass().getMethods()) {
                present |= m.getName().equals(gone);
            }
            expect(!present, "the per-family switch " + gone + " is gone (Hide Damage Messages covers both)", gone);
        }
        scenario.log("PASS one switch: no per-family setters left; both families were hidden above by Hide Damage Messages alone");

        ModUnderTest.set(cfg, "setHideDamageMessages", false);
        hiddenBefore = num("testHidden");
        for (String line : List.of(ABILITY.get(0), INCOMING.get(0), INCOMING.get(11))) {
            send(ctx, server, line, "gray");
            lines = chat(ctx, 1);
            expect(lines.get(0).equals(line), "toggle off shows: " + line, lines);
        }
        expect(num("testHidden") == hiddenBefore, "toggle off: nothing counted as hidden", lines);
        scenario.log("PASS hider off: nothing hidden");

        expect(num("testFailures") == failuresBefore,
                "the chat hook never threw (last: " + onClient(ctx, () -> ModUnderTest.staticCall(TIDY, "testLastFailure")) + ")",
                lines);
        scenario.log("RESULT PASS 87-chat-tidy");
    }

    private static void send(ClientGameTestContext ctx, TestServer server, String text, String colour) {
        String escaped = text.replace("\\", "\\\\").replace("\"", "\\\"");
        server.command("tellraw @a {\"text\":\"" + escaped + "\",\"color\":\"" + colour + "\"}");
        ctx.waitTicks(6);
    }

    /** The newest {@code max} chat-window messages, newest first, without the mod's own "[ModChat]" relay notices
     *  (the offline relay retries and announces itself at random moments; it is not under test here). */
    @SuppressWarnings("unchecked")
    private static List<String> chat(ClientGameTestContext ctx, int max) {
        List<String> all = (List<String>) onClient(ctx, () -> ModUnderTest.staticCall(TIDY, "testChatLines",
                new Class<?>[]{int.class}, new Object[]{max + 20}));
        List<String> out = new ArrayList<>();
        for (String l : all) {
            if (!l.startsWith("[ModChat]") && out.size() < max) {
                out.add(l);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> observed(ClientGameTestContext ctx) {
        return (List<String>) onClient(ctx, () -> ModUnderTest.staticCall(TIDY, "testObserved"));
    }

    private static long num(String hook) {
        return (Long) ModUnderTest.staticCall(TIDY, hook);
    }

    private static Object onClient(ClientGameTestContext ctx, java.util.function.Supplier<Object> s) {
        AtomicReference<Object> out = new AtomicReference<>();
        ctx.runOnClient(mc -> out.set(s.get()));
        return out.get();
    }

    private static int count(List<String> lines, String exact) {
        int n = 0;
        for (String l : lines) {
            if (l.equals(exact)) {
                n++;
            }
        }
        return n;
    }

    private static void expect(boolean ok, String what, Object seen) {
        if (!ok) {
            throw new AssertionError("[" + NAME + "] FAIL " + what + " - saw " + seen);
        }
    }
}

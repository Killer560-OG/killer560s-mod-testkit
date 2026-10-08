package dev.testkit.gametest.chat;

import com.google.gson.JsonObject;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.TestMap;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static dev.testkit.gametest.hx.HxKit.Settings;
import static dev.testkit.gametest.hx.HxKit.awaitCommand;
import static dev.testkit.gametest.hx.HxKit.assertNoCommand;

/**
 * killer560's chat batch (mod branch chat-batch, 2026-10-08), one shared server ({@code 560-chat-session}). Every chat
 * line arrives from the dedicated server as a real system-chat packet (Hx {@code chat}), so it takes the path a Hypixel
 * line takes; every judgement is on what is IN the chat window, on the real clipboard, or on the commands the server
 * received.
 *
 * <ul>
 *   <li>561-chat-copy-whole: Shift or Ctrl + LEFT click on any wrapped line of a long message (and past the end of its
 *       text) puts the WHOLE message on the clipboard, plain (colour codes gone). Click positions come from vanilla's own
 *       chat geometry ({@code ChatComponent.extractRenderState}, javap), computed here, not asked of the mod.</li>
 *   <li>562-chat-copy-line: Shift or Ctrl + RIGHT click copies only the clicked wrapped line; every line of the message
 *       in turn, and their concatenation is the message.</li>
 *   <li>563-chat-copy-plain-click: a plain left click on a suggest-command line does vanilla's thing (the command lands in
 *       the input box, clipboard untouched); a plain right click copies nothing; with Copy Chat off a Shift click
 *       copies nothing; a Chat Hider stack is copied without its (xN); the "copied" line appears.</li>
 *   <li>564-chat-voice-openmic: synthetic 16 kHz audio through Open Mic's real path (segmenter -> transcription ->
 *       noise filter -> send): speech over a room noise ABOVE the old fixed threshold (which never ended an utterance)
 *       sends exactly one /pc; noise alone, a blip, an empty or "uh" transcription send nothing; two sentences send two;
 *       the Silence Before Send setting decides whether a pause splits them.</li>
 *   <li>566-chat-hider-rules: Chat Hider's master gates every rule; with it on, each of the eight rules hides its line
 *       and only its line; Hide Damage Messages alone hides ability AND incoming-hit lines.</li>
 *   <li>567-chat-hider-migration: old Chat Tidy and Object Hider files become one Chat Hider config (master on if
 *       either old feature had anything on, every sub-option carried, saved at once); old files with everything off
 *       stay off; a new file is never re-migrated.</li>
 *   <li>568-chat-stack-interleaved: a repeat stacks onto its earlier copy with other messages between (moved to the
 *       bottom with its count), a wrapped one leaves no line behind, the 50-message window holds at its exact edge,
 *       and a copy older than 60 s does not stack.</li>
 * </ul>
 * {@code TESTKIT_CHAT_SHOTS=<dir>} copies the GUI-scale-2 pictures there.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class ChatBatchSuite implements FabricClientGameTest {

    static final String SESSION = "560-chat-session";

    static final String TIDY = "chattidy.ChatTidy";
    static final String HIDER = "chattidy.ChatTidyConfig";
    static final String COPY = "copychat.CopyChatFeature";
    static final String COPY_CFG = "copychat.CopyChatConfig";
    static final String VOICE = "voicetotext.VoiceToTextFeature";
    static final String VOICE_CFG = "voicetotext.VoiceToTextConfig";
    static final String SENTINEL = "chat-batch clipboard sentinel";
    static final int SHIFT = 1;
    static final int CTRL = 2;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        Session.run(ctx, SESSION,
                (server, s) -> TestMap.on(server)
                        .platform(-5610, 100, 5610, 6)
                        .catchFloor(90)
                        .survival()
                        .spawn(-5609.5, 5610.5, 0f)
                        .build(),
                s -> {
                    ModUnderTest.require("killer560smod");
                    s.test("561-chat-copy-whole", ChatBatchSuite::copyWhole);
                    s.test("562-chat-copy-line", ChatBatchSuite::copyLine);
                    s.test("563-chat-copy-plain-click", ChatBatchSuite::copyPlain);
                    s.test("564-chat-voice-openmic", ChatBatchSuite::voiceOpenMic);
                    s.test("566-chat-hider-rules", ChatBatchSuite::hiderRules);
                    s.test("567-chat-hider-migration", ChatBatchSuite::hiderMigration);
                    s.test("568-chat-stack-interleaved", ChatBatchSuite::stackInterleaved);
                });
    }

    // =================================================================================================================
    // shared
    // =================================================================================================================

    /** Every case starts here: SkyblockGate off (the test server is not Skyblock), every chat feature under test off. */
    static Settings base(Session c) {
        return new Settings(c)
                .custom(() -> Mod.staticCall("util.SkyblockGate", "setEnabled", false), () -> { })
                .with(HIDER, "Enabled", false)
                .with(COPY_CFG, "Enabled", false)
                .with("clicktranslate.ClickTranslateConfig", "Enabled", false);
    }

    static Object chat(Minecraft mc) {
        return Mod.staticCall("compat.McCompat", "chat", mc);
    }

    /** The newest {@code max} chat messages, newest first, plain, without the mod's own relay / copy notices. */
    @SuppressWarnings("unchecked")
    static List<String> lines(Session c, int max) {
        List<String> all = c.onClient(mc -> (List<String>) Mod.staticCall(TIDY, "testChatLines", max + 40));
        List<String> out = new ArrayList<>();
        for (String l : all) {
            if (!l.startsWith("[ModChat]") && !l.contains("copied to clipboard") && out.size() < max) {
                out.add(l);
            }
        }
        return out;
    }

    static void say(Session c, String text) {
        c.hx().chat(text);
        c.ctx().waitTicks(4);
    }

    static void sayJson(Session c, JsonObject json) {
        c.hx().call("chat", "textJson", json, "overlay", false);
        c.ctx().waitTicks(4);
    }

    static void shot(Session c, String label) {
        String name = c.name() + "-" + label;
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        Path kept = dev.testkit.harness.Report.screenshot(name, taken);
        c.note("picture " + label + " -> " + (kept != null ? kept : taken));
        String dir = System.getenv("TESTKIT_CHAT_SHOTS");
        if (dir == null || dir.isBlank()) {
            return;
        }
        try {
            String jar = Mod.isCheat() ? "cheat" : "legit";
            String mcv = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                    .getMetadata().getVersion().getFriendlyString();
            Path out = Path.of(dir).resolve(name + "-" + mcv + "-" + jar + ".png");
            Files.createDirectories(out.getParent());
            Files.copy(taken, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception | Error e) {
            c.note("could not copy " + name + ": " + e);
        }
    }

    static int guiScale(Session c, int scale) {
        return c.onClient(mc -> {
            int old = mc.options.guiScale().get();
            mc.options.guiScale().set(scale);
            mc.resizeGui();
            return old;
        });
    }

    // =================================================================================================================
    // Copy Chat (561-563)
    // =================================================================================================================

    /** One wrapped chat row on screen: its plain text, its message's plain text, and a point inside the row. */
    record Row(String line, String message, double x, double y, double xPastText) {
    }

    /**
     * The on-screen rows of the newest message whose text contains {@code marker}, top to bottom. Positions come from
     * vanilla's chat layout (javap 26.1.2 and 26.2, {@code ChatComponent.extractRenderState} and {@code forEachLine}):
     * chatBottom = floor((guiHeight - 40) / scale); visible row i (0 = bottom) spans
     * [chatBottom - (i + 1) * entryHeight, chatBottom - i * entryHeight) in chat units, entryHeight = 9 * (spacing + 1);
     * screen = chat units * scale. The chat must be scrolled to the bottom.
     */
    @SuppressWarnings("unchecked")
    static List<Row> rows(Session c, String marker) {
        return c.onClient(mc -> {
            Object chat = chat(mc);
            List<GuiMessage.Line> trimmed = (List<GuiMessage.Line>) Mod.call(chat, "killer560smod$getTrimmedMessages");
            int scroll = (Integer) Mod.call(chat, "killer560smod$getChatScrollbarPos");
            double scale = (Double) Mod.call(chat, "killer560smod$invokeGetScale");
            double spacing = mc.options.chatLineSpacing().get();
            int entryHeight = (int) (9.0 * (spacing + 1.0));
            int chatBottom = Mth.floor((mc.getWindow().getGuiScaledHeight() - 40) / (float) scale);
            int perPage = (Integer) Mod.call(chat, "getLinesPerPage");
            GuiMessage target = null;
            List<Row> out = new ArrayList<>();
            for (int i = 0; i < Math.min(perPage, trimmed.size() - scroll); i++) {
                GuiMessage.Line line = trimmed.get(i + scroll);
                if (target == null && line.parent().content().getString().contains(marker)) {
                    target = line.parent();
                }
                if (target != null && line.parent() == target) {
                    double localY = chatBottom - i * entryHeight - entryHeight / 2.0;
                    int textWidth = mc.font.width(line.content());
                    out.add(0, new Row(plain(line.content()), strip(unstacked(target)), 6 * scale, localY * scale,
                            (textWidth + 6) * scale));
                }
            }
            return out;
        });
    }

    static String unstacked(GuiMessage m) {
        return ((Component) Mod.staticCall(TIDY, "unstackedContent", m)).getString();
    }

    static String plain(net.minecraft.util.FormattedCharSequence seq) {
        StringBuilder sb = new StringBuilder();
        seq.accept((pos, style, cp) -> {
            sb.appendCodePoint(cp);
            return true;
        });
        return strip(sb.toString());
    }

    static String strip(String s) {
        String out = ChatFormatting.stripFormatting(s);
        return out == null ? s : out;
    }

    static void openChat(Session c) {
        c.ctx().runOnClient(mc -> {
            Object chat = chat(mc);
            ((net.minecraft.client.gui.components.ChatComponent) chat).resetChatScroll();
            McCompat.setScreen(mc, new ChatScreen("", false));
        });
        c.ctx().waitTicks(2);
    }

    static boolean click(Session c, double x, double y, int button, int mods) {
        return c.onClient(mc -> {
            ChatScreen screen = (ChatScreen) McCompat.screen(mc);
            return screen.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(button, mods)), false);
        });
    }

    static String clipboard(Session c) {
        return c.onClient(mc -> mc.keyboardHandler.getClipboard());
    }

    static void setClipboard(Session c, String text) {
        c.ctx().runOnClient(mc -> mc.keyboardHandler.setClipboard(text));
    }

    /** A long, colour-coded message that wraps over several chat rows. */
    static String longMessage(String marker) {
        StringBuilder sb = new StringBuilder("§6" + marker + " §ccolour §acodes §rand");
        for (int i = 1; i <= 34; i++) {
            sb.append(" word").append(i);
        }
        return sb.append(" end.").toString();
    }

    static void copyWhole(Session c) {
        String before = clipboard(c);
        try (Settings set = base(c).with(COPY_CFG, "Enabled", true)) {
            String raw = longMessage("CopyWholeProbe");
            String want = strip(raw);
            say(c, raw);
            openChat(c);
            List<Row> rows = rows(c, "CopyWholeProbe");
            c.note("message wraps over " + rows.size() + " rows: " + rows.stream().map(Row::line).toList());
            c.check(rows.size() >= 3, "premise: the long message wraps over at least 3 rows, saw " + rows.size());
            c.check(want.equals(rows.get(0).message()), "premise: the message read back is the one sent");
            long copiesBefore = c.onClient(mc -> (Long) Mod.staticCall(COPY, "testCopies"));
            int n = 0;
            for (int i = 0; i < rows.size(); i++) {
                for (int mod : new int[]{SHIFT, CTRL}) {
                    setClipboard(c, SENTINEL);
                    List<Row> now = rows(c, "CopyWholeProbe"); // each copy adds a notice line below
                    Row r = now.get(i);
                    boolean took = click(c, (mod == SHIFT ? r.x() : r.xPastText()), r.y(), 0, mod);
                    String got = clipboard(c);
                    c.check(took, "row " + i + " " + (mod == SHIFT ? "Shift" : "Ctrl") + "+left click was not consumed");
                    c.check(want.equals(got), "row " + i + " " + (mod == SHIFT ? "Shift" : "Ctrl")
                            + "+left click copied \"" + got + "\" - want the whole message \"" + want + "\"");
                    n++;
                }
            }
            long copies = c.onClient(mc -> (Long) Mod.staticCall(COPY, "testCopies")) - copiesBefore;
            c.check(copies == n, "the mod counted " + copies + " copies for " + n + " clicks");
            c.check(!want.contains("§"), "premise: the expected text has no colour codes");
            c.note("PASS " + n + " clicks (Shift on the text and Ctrl past its end, every row): clipboard = whole message, "
                    + want.length() + " chars, no colour codes");
            c.ctx().waitTicks(2);
            List<String> chatNow = c.onClient(mc -> {
                @SuppressWarnings("unchecked")
                List<String> l = (List<String>) Mod.staticCall(TIDY, "testChatLines", 3);
                return l;
            });
            c.check(chatNow.stream().anyMatch(l -> l.contains("Chat message copied to clipboard!")),
                    "no \"Chat message copied to clipboard!\" line in chat: " + chatNow);
            int old = guiScale(c, 2);
            try {
                openChat(c);
                c.ctx().waitTicks(3);
                shot(c, "chat-open");
            } finally {
                guiScale(c, old);
            }
        } finally {
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
            setClipboard(c, before);
        }
    }

    static void copyLine(Session c) {
        String before = clipboard(c);
        try (Settings set = base(c).with(COPY_CFG, "Enabled", true)) {
            String raw = longMessage("CopyLineProbe");
            String want = strip(raw);
            say(c, raw);
            openChat(c);
            List<Row> rows = rows(c, "CopyLineProbe");
            c.check(rows.size() >= 3, "premise: the long message wraps over at least 3 rows, saw " + rows.size());
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < rows.size(); i++) {
                for (int mod : new int[]{SHIFT, CTRL}) {
                    setClipboard(c, SENTINEL);
                    Row r = rows(c, "CopyLineProbe").get(i);
                    boolean took = click(c, r.x(), r.y(), 1, mod);
                    String got = clipboard(c);
                    c.check(took, "row " + i + " right click was not consumed");
                    c.check(r.line().equals(got), "row " + i + " " + (mod == SHIFT ? "Shift" : "Ctrl")
                            + "+right click copied \"" + got + "\" - want only that row \"" + r.line() + "\"");
                    c.check(!got.equals(want), "row " + i + " copied the whole message, not the line");
                }
                joined.append(rows.get(i).line());
            }
            c.check(joined.toString().replace(" ", "").equals(want.replace(" ", "")),
                    "the rows copied one by one do not add up to the message: " + joined);
            String last = c.onClient(mc -> (String) Mod.staticCall(COPY, "testLastKind"));
            c.check("line".equals(last), "the mod's last copy kind is " + last);
            c.note("PASS " + rows.size() + " rows x Shift/Ctrl right click: each copied exactly its own row; together "
                    + "they are the message");
        } finally {
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
            setClipboard(c, before);
        }
    }

    static void copyPlain(Session c) {
        String before = clipboard(c);
        try (Settings set = base(c).with(COPY_CFG, "Enabled", true)) {
            JsonObject suggest = new JsonObject();
            suggest.addProperty("text", "PlainClickProbe click me to suggest");
            JsonObject click = new JsonObject();
            click.addProperty("action", "suggest_command");
            click.addProperty("command", "/plainclick suggested");
            suggest.add("click_event", click);
            sayJson(c, suggest);
            openChat(c);
            Row r = rows(c, "PlainClickProbe").get(0);

            // plain left click: vanilla's suggest_command fills the input box; the clipboard is untouched
            setClipboard(c, SENTINEL);
            click(c, r.x(), r.y(), 0, 0);
            String input = c.onClient(mc -> ((EditBox) Mod.field(McCompat.screen(mc), "input")).getValue());
            c.check("/plainclick suggested".equals(input), "a plain left click did not do vanilla's suggest_command (input \""
                    + input + "\")");
            c.check(SENTINEL.equals(clipboard(c)), "a plain left click changed the clipboard to \"" + clipboard(c) + "\"");
            c.note("plain left click: input box = \"" + input + "\" (vanilla), clipboard untouched");

            // Shift+left on the same line copies instead, and the input box is left alone
            c.ctx().runOnClient(mc -> ((EditBox) Mod.field(McCompat.screen(mc), "input")).setValue(""));
            click(c, r.x(), r.y(), 0, SHIFT);
            String input2 = c.onClient(mc -> ((EditBox) Mod.field(McCompat.screen(mc), "input")).getValue());
            c.check("PlainClickProbe click me to suggest".equals(clipboard(c)), "Shift+left on a clickable line copied \""
                    + clipboard(c) + "\"");
            c.check(input2.isEmpty(), "Shift+left also ran the line's click event (input \"" + input2 + "\")");

            // plain right click copies nothing
            setClipboard(c, SENTINEL);
            r = rows(c, "PlainClickProbe").get(0);
            click(c, r.x(), r.y(), 1, 0);
            c.check(SENTINEL.equals(clipboard(c)), "a plain right click changed the clipboard");

            // a click on empty space above the chat is not a copy
            boolean took = click(c, r.x(), 5, 0, SHIFT);
            c.check(!took && SENTINEL.equals(clipboard(c)), "a Shift click above the chat copied something");

            // a stack is copied without its (xN)
            try (Settings hider = new Settings(c).with(HIDER, "Enabled", true).with(HIDER, "StackDuplicates", true)) {
                c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
                say(c, "StackCopyProbe repeated");
                say(c, "something between");
                say(c, "StackCopyProbe repeated");
                c.check(lines(c, 1).get(0).equals("StackCopyProbe repeated (x2)"), "premise: stacked, saw " + lines(c, 3));
                openChat(c);
                Row s = rows(c, "StackCopyProbe").get(0);
                click(c, s.x(), s.y(), 0, CTRL);
                c.check("StackCopyProbe repeated".equals(clipboard(c)), "a stacked line copied as \"" + clipboard(c) + "\"");
                c.note("Ctrl+left on \"StackCopyProbe repeated (x2)\" copied \"" + clipboard(c) + "\"");
            }

            // Copy Chat off: Shift+left copies nothing
            c.ctx().runOnClient(mc -> Mod.set(COPY_CFG, "setEnabled", false));
            setClipboard(c, SENTINEL);
            r = rows(c, "PlainClickProbe").get(0);
            click(c, r.x(), r.y(), 0, SHIFT);
            c.check(SENTINEL.equals(clipboard(c)), "with Copy Chat off a Shift click copied \"" + clipboard(c) + "\"");
            c.note("PASS plain clicks are vanilla's, right click copies nothing, the feature off copies nothing, a stack "
                    + "copies without its count");
        } finally {
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
            setClipboard(c, before);
        }
    }

    // =================================================================================================================
    // Voice To Text Open Mic (564)
    // =================================================================================================================

    static final int RATE = 16000;

    /** Builds 16 kHz 16-bit mono PCM: room noise of RMS ~{@code noise}, with tones laid over it. */
    static final class Pcm {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        final Random rnd = new Random(560);
        final double noise;

        Pcm(double noise) {
            this.noise = noise;
        }

        Pcm quiet(int ms) {
            return tone(ms, 0);
        }

        /** {@code ms} of a 220 Hz tone at {@code amp} over the noise (amp 0 = noise alone). */
        Pcm tone(int ms, double amp) {
            int n = RATE * ms / 1000;
            for (int i = 0; i < n; i++) {
                double v = rnd.nextGaussian() * noise + amp * Math.sin(2 * Math.PI * 220 * i / RATE);
                short s = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(v)));
                out.write(s & 0xFF);
                out.write((s >> 8) & 0xFF);
            }
            return this;
        }

        /** Speech-like: syllables of tone with short dips between them. */
        Pcm speech(int syllables) {
            for (int i = 0; i < syllables; i++) {
                tone(220, 7000);
                quiet(90);
            }
            return this;
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }

    static void voiceOpenMic(Session c) {
        List<Integer> heard = new CopyOnWriteArrayList<>();
        String[] reply = {"voice probe one"};
        Function<byte[], String> stub = audio -> {
            heard.add(audio.length);
            return reply[0];
        };
        Object mode = c.onClient(mc -> Mod.enumValue(VOICE_CFG + "$Mode", "OPEN_MIC"));
        Object party = c.onClient(mc -> Mod.enumValue("spotify.ChatDestination", "PARTY"));
        try (Settings set = base(c)
                // Arm-once latch set first, so enabling Open Mic does not open a real microphone or fetch Vosk.
                .custom(() -> Mod.setField(VOICE, "openMicArmed", true), () -> { })
                .with(VOICE_CFG, "Mode", mode)
                .with(VOICE_CFG, "ChatDestination", party)
                .with(VOICE_CFG, "OpenMicSilenceMs", 1000)
                .with(VOICE_CFG, "Enabled", true)
                .custom(() -> Mod.staticCall(VOICE, "testSetTranscriber", stub),
                        () -> ModUnderTest.staticCall("com.killer560.hub." + VOICE, "testSetTranscriber",
                                new Class<?>[]{Function.class}, new Object[]{null}))) {
            c.ctx().waitTicks(3);
            String state = c.onClient(mc -> String.valueOf(Mod.field(VOICE, "state")));
            c.note("Voice To Text state with Open Mic on and the latch set: " + state + " (no microphone opened)");

            // The old fixed threshold called RMS > 500 voice; this room's noise is ~700, so the old code never ended.
            double room = 700;
            byte[] noiseOnly = new Pcm(room).quiet(6000).bytes();
            c.check(oldDetectorNeverEnds(noiseOnly), "premise: the old fixed-500 detector would hear voice in this noise");

            // 1. noise alone: nothing finishes, nothing sent
            long sent0 = num(c, "testSent");
            int done = feed(c, noiseOnly);
            c.check(done == 0, "6 s of room noise finished " + done + " utterance(s)");

            // 2. one sentence then silence: exactly one /pc with the transcription
            Pcm one = new Pcm(room).quiet(1500).speech(6).quiet(2500);
            done = feed(c, one.bytes());
            c.check(done == 1, "speech then 2.5 s quiet finished " + done + " utterance(s), want 1");
            String cmd = awaitCommand(c, "pc ", 80);
            c.check("pc voice probe one".equals(cmd), "sent /" + cmd);
            c.waitUntil("the mod counted the send", mc -> num0("testSent") == sent0 + 1, 40);
            int speechBytes = 6 * (220 + 90) * RATE / 1000 * 2;
            int got = heard.get(heard.size() - 1);
            c.check(got >= speechBytes && got <= speechBytes + (1000 + 300 + 400) * RATE / 1000 * 2,
                    "the utterance handed to the transcriber is " + got + " bytes; speech is " + speechBytes);
            c.note("one sentence over ~" + (int) room + " RMS room noise: 1 utterance (" + got + " bytes, speech "
                    + speechBytes + "), sent /" + cmd);

            // 3. a click / bump: too short to be speech
            done = feed(c, new Pcm(room).quiet(1000).tone(100, 9000).quiet(2500).bytes());
            c.check(done == 0, "a 100 ms blip finished " + done + " utterance(s)");

            // 4. empty and filler transcriptions are not sent
            long ignored0 = num(c, "testIgnored");
            int k = 0;
            for (String filler : new String[]{"", "uh", "the"}) {
                reply[0] = filler;
                c.check(feed(c, one.bytes()) == 1, "premise: the speech finished an utterance");
                long want = ignored0 + (++k);
                c.waitUntil("transcription \"" + filler + "\" ignored", mc -> num0("testIgnored") == want, 60);
            }
            assertNoCommand(c, 10, "pc uh", "pc the", "pc  ");
            reply[0] = "voice probe one";

            // 5. two sentences 2 s apart: two sends, and it kept listening between them
            int cmdsBefore = c.commands().size();
            long sent1 = num(c, "testSent");
            done = feed(c, new Pcm(room).quiet(800).speech(4).quiet(2000).speech(4).quiet(2000).bytes());
            c.check(done == 2, "two sentences 2 s apart finished " + done + " utterance(s), want 2");
            c.waitUntil("two more sends", mc -> num0("testSent") == sent1 + 2, 80);

            // 6. Silence Before Send decides: a 1.5 s pause splits at 1.0 s and does not at 2.5 s
            byte[] pause = new Pcm(room).quiet(800).speech(4).quiet(1500).speech(4).quiet(3000).bytes();
            c.check(feed(c, pause) == 2, "a 1.5 s pause with Silence Before Send 1.0 s is not two utterances");
            c.ctx().runOnClient(mc -> Mod.set(VOICE_CFG, "setOpenMicSilenceMs", 2500));
            c.check(feed(c, pause) == 1, "a 1.5 s pause with Silence Before Send 2.5 s split the sentence");
            c.note("PASS noise only 0, one sentence 1 (/pc sent), blip 0, empty/uh/the ignored (not sent), two sentences 2,"
                    + " a 1.5 s pause splits at 1.0 s and not at 2.5 s; " + (c.commands().size() - cmdsBefore)
                    + " /pc in the last part");
        }
    }

    /** The detector before 2026-10-08: RMS over a fixed 500 is voice, 1.2 s below it ends the utterance. */
    static boolean oldDetectorNeverEnds(byte[] pcm) {
        int voiced = 0;
        int chunks = 0;
        for (int off = 0; off + 4096 <= pcm.length; off += 4096) {
            long sum = 0;
            for (int i = off; i < off + 4096; i += 2) {
                short s = (short) ((pcm[i + 1] << 8) | (pcm[i] & 0xFF));
                sum += (long) s * s;
            }
            chunks++;
            if (Math.sqrt(sum / 2048.0) > 500) {
                voiced++;
            }
        }
        return chunks > 0 && voiced == chunks;
    }

    static int feed(Session c, byte[] pcm) {
        int n = c.onClient(mc -> (Integer) Mod.staticCall(VOICE, "testFeedOpenMic", pcm, 1_000_000L));
        c.ctx().waitTicks(4);
        return n;
    }

    static long num(Session c, String hook) {
        return c.onClient(mc -> num0(hook));
    }

    static long num0(String hook) {
        return ((Number) Mod.staticCall(VOICE, hook)).longValue();
    }

    // =================================================================================================================
    // Chat Hider (566, 567)
    // =================================================================================================================

    /** One sample line per rule, and the setter that turns that rule on. */
    record Rule(String setter, String line) {
    }

    static final List<Rule> RULES = List.of(
            new Rule("HideDamageMessages", "Your Implosion hit 2 enemies for 14,736,463.2 damage."),
            new Rule("HideDamageMessages", "A Crypt Wither Skull exploded, hitting you for 23,760 damage."),
            new Rule("HideUselessMessages", "There are blocks in the way!"),
            new Rule("HideDiscordWarnings", "Beware of people on Discord pretending to be Hypixel Staff!"),
            new Rule("HideMicrosoftWarnings", "Never give out your Microsoft account password to anyone."),
            new Rule("HideNonRankInvites", "HxNoRank has invited you to join their party!"));
    static final String[] ALL_SETTERS = {"HideDamageMessages", "HideUselessMessages", "HideDiscordWarnings",
            "HideMicrosoftWarnings", "HideEmptyChatMessages", "HideActionbar", "HideNonRankInvites"};

    static void hiderRules(Session c) {
        try (Settings set = base(c)) {
            Settings all = new Settings(c);
            for (String s : ALL_SETTERS) {
                all.with(HIDER, s, true);
            }
            try (all) {
                // master OFF: every rule on, nothing hidden
                for (Rule r : RULES) {
                    say(c, r.line());
                    c.check(lines(c, 1).get(0).equals(r.line()), "master off hid \"" + r.line() + "\": " + lines(c, 3));
                }
                c.check(overlayShown(c, "HiderOverlayProbe off"), "master off hid the action bar");
                c.note("master off: all " + RULES.size() + " sample lines and the action bar shown");

                // master ON: each sample hidden, and the lines around them kept
                c.ctx().runOnClient(mc -> Mod.set(HIDER, "setEnabled", true));
                for (Rule r : RULES) {
                    say(c, "HiderMarker before " + r.setter());
                    say(c, r.line());
                    List<String> l = lines(c, 2);
                    c.check(l.get(0).equals("HiderMarker before " + r.setter()), "master on did not hide \"" + r.line()
                            + "\" (" + r.setter() + "): " + l);
                }
                say(c, "HiderMarker before empty");
                say(c, "   ");
                c.check(lines(c, 1).get(0).equals("HiderMarker before empty"), "an empty line was not hidden: " + lines(c, 2));
                c.check(!overlayShown(c, "HiderOverlayProbe on"), "Hide Actionbar did not hide the action bar");
                say(c, "[MVP+] HxRank has invited you to join their party!");
                c.check(lines(c, 1).get(0).equals("[MVP+] HxRank has invited you to join their party!"),
                        "a ranked invite was hidden");
                say(c, "Party > [MVP+] Eve: There are blocks in the way!");
                c.check(lines(c, 1).get(0).startsWith("Party > [MVP+] Eve"), "a party message quoting a rule was hidden");
                c.note("master on: every rule hid its sample (damage: ability AND incoming by Hide Damage Messages alone),"
                        + " the action bar hidden, a ranked invite and a player's quote kept");

                // each rule switched off on its own shows its line again
                for (Rule r : RULES) {
                    c.ctx().runOnClient(mc -> Mod.set(HIDER, "set" + r.setter(), false));
                    say(c, r.line());
                    c.check(lines(c, 1).get(0).equals(r.line()), r.setter() + " off still hid \"" + r.line() + "\"");
                    c.ctx().runOnClient(mc -> Mod.set(HIDER, "set" + r.setter(), true));
                }
                c.note("PASS each rule off on its own shows its line again");
            }
        }
    }

    static boolean overlayShown(Session c, String text) {
        c.ctx().runOnClient(mc -> {
            Object hud = McCompat.hud(mc);
            Mod.call(hud, "setOverlayMessage", Component.literal("reset"), false);
        });
        c.hx().overlay(text);
        c.ctx().waitTicks(5);
        return c.onClient(mc -> {
            Object m = Mod.field(McCompat.hud(mc), "overlayMessageString");
            return m != null && ((Component) m).getString().equals(text);
        });
    }

    static void hiderMigration(Session c) throws Exception {
        Path tidyFile = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-chattidy.json"));
        Path ohFile = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-objecthider.json"));
        String tidyOld = Files.exists(tidyFile) ? Files.readString(tidyFile, StandardCharsets.UTF_8) : null;
        String ohOld = Files.exists(ohFile) ? Files.readString(ohFile, StandardCharsets.UTF_8) : null;
        try {
            Files.createDirectories(tidyFile.getParent());
            Files.createDirectories(ohFile.getParent());
            // a: Chat Tidy had Hide Damage on (with a family switched off), Hide Chat had two hides on
            Files.writeString(tidyFile, "{\"stackDuplicates\": false, \"hideDamageMessages\": true, "
                    + "\"hideAbilityDamage\": false, \"hideIncomingHits\": true}", StandardCharsets.UTF_8);
            Files.writeString(ohFile, "{\"hideHealerFairy\": true, \"hideUselessMessages\": true, \"hideActionbar\": true, "
                    + "\"hideDiscordWarnings\": false}", StandardCharsets.UTF_8);
            String a = load(c);
            c.check(a.equals("enabled=true stack=false damage=true useless=true discord=false microsoft=false empty=false "
                    + "actionbar=true invites=false"), "migration a: " + a);
            String saved = Files.readString(tidyFile, StandardCharsets.UTF_8);
            c.check(saved.contains("\"enabled\": true") && saved.contains("\"hideUselessMessages\": true"),
                    "the migrated config was not saved at once: " + saved);
            c.note("a: old Hide Damage on + Useless/Actionbar on -> " + a + "; saved");

            // b: only Hide Chat had something on, no Chat Tidy file at all
            Files.delete(tidyFile);
            Files.writeString(ohFile, "{\"hideNonRankInvites\": true}", StandardCharsets.UTF_8);
            String b = load(c);
            c.check(b.startsWith("enabled=true") && b.endsWith("invites=true"), "migration b: " + b);

            // c: both old features with everything off stay off
            Files.writeString(tidyFile, "{\"stackDuplicates\": false, \"hideDamageMessages\": false}", StandardCharsets.UTF_8);
            Files.writeString(ohFile, "{\"hideHealerFairy\": true}", StandardCharsets.UTF_8);
            String cc = load(c);
            c.check(cc.equals("enabled=false stack=false damage=false useless=false discord=false microsoft=false "
                    + "empty=false actionbar=false invites=false"), "migration c: " + cc);

            // d: a Chat Hider file is never migrated again, whatever the old file says
            Files.writeString(tidyFile, "{\"enabled\": false, \"stackDuplicates\": true, \"hideUselessMessages\": false}",
                    StandardCharsets.UTF_8);
            Files.writeString(ohFile, "{\"hideUselessMessages\": true}", StandardCharsets.UTF_8);
            String d = load(c);
            c.check(d.startsWith("enabled=false stack=true") && d.contains("useless=false"), "a new file re-migrated: " + d);
            c.note("PASS b " + b + "; c (all off) " + cc + "; d (new file kept) " + d);
        } finally {
            if (tidyOld != null) {
                Files.writeString(tidyFile, tidyOld, StandardCharsets.UTF_8);
            } else {
                Files.deleteIfExists(tidyFile);
            }
            if (ohOld != null) {
                Files.writeString(ohFile, ohOld, StandardCharsets.UTF_8);
            } else {
                Files.deleteIfExists(ohFile);
            }
            c.ctx().runOnClient(mc -> {
                Mod.staticCall(HIDER, "load");
                Mod.staticCall("objecthider.ObjectHiderConfig", "load");
            });
        }
    }

    static String load(Session c) {
        return c.onClient(mc -> {
            Mod.staticCall(HIDER, "load");
            Object cfg = Mod.cfg(HIDER);
            return "enabled=" + Mod.call(cfg, "getEnabledRaw") + " stack=" + Mod.call(cfg, "getStackDuplicatesRaw")
                    + " damage=" + Mod.call(cfg, "getHideDamageMessagesRaw")
                    + " useless=" + Mod.call(cfg, "getHideUselessMessagesRaw")
                    + " discord=" + Mod.call(cfg, "getHideDiscordWarningsRaw")
                    + " microsoft=" + Mod.call(cfg, "getHideMicrosoftWarningsRaw")
                    + " empty=" + Mod.call(cfg, "getHideEmptyChatMessagesRaw")
                    + " actionbar=" + Mod.call(cfg, "getHideActionbarRaw")
                    + " invites=" + Mod.call(cfg, "getHideNonRankInvitesRaw");
        });
    }

    // =================================================================================================================
    // Stacking with messages in between (568)
    // =================================================================================================================

    static void stackInterleaved(Session c) {
        try (Settings set = base(c).with(HIDER, "Enabled", true).with(HIDER, "StackDuplicates", true)) {
            long stacked0 = c.onClient(mc -> (Long) Mod.staticCall(TIDY, "testStacked"));
            // A B A -> "A (x2)" at the bottom, B above it, no plain A left
            say(c, "StackProbe A");
            say(c, "StackProbe B");
            say(c, "StackProbe A");
            List<String> l = lines(c, 5);
            c.check(l.get(0).equals("StackProbe A (x2)") && l.get(1).equals("StackProbe B") && !l.contains("StackProbe A"),
                    "A B A: " + l);
            // three more in between, then A again -> x3, still one A
            say(c, "StackProbe C");
            say(c, "StackProbe D");
            say(c, "StackProbe E");
            say(c, "StackProbe A");
            l = lines(c, 8);
            c.check(l.get(0).equals("StackProbe A (x3)") && count(l, "StackProbe A") == 0 && count(l, "StackProbe A (x2)") == 0,
                    "A ... A ... A: " + l);
            c.note("A B A -> " + lines(c, 2) + "; then C D E A -> " + l.subList(0, 5));

            // a wrapped line stacks across another and leaves no wrapped row behind
            String longLine = longMessage("StackWrapProbe");
            say(c, longLine);
            say(c, "StackProbe between");
            say(c, longLine);
            l = lines(c, 3);
            c.check(l.get(0).equals(strip(longLine) + " (x2)") || l.get(0).equals(longLine + " (x2)"),
                    "wrapped line did not stack: " + l.get(0));
            int[] orphans = c.onClient(mc -> (int[]) Mod.staticCall(TIDY, "testTrimmedOrphans"));
            c.check(orphans[0] == 0, orphans[0] + " wrapped row(s) of a removed copy left in the chat");
            c.note("wrapped line stacked across one message; " + orphans[1] + " rows in chat, 0 orphans");

            // different colour in between and repeated: no stack across a different style
            sayJson(c, colour("StackProbe colour", "red"));
            say(c, "StackProbe spacer");
            sayJson(c, colour("StackProbe colour", "green"));
            l = lines(c, 3);
            c.check(l.get(0).equals("StackProbe colour") && l.get(2).equals("StackProbe colour"),
                    "same text in another colour stacked: " + l);

            // the 50-message window, at its edge: 49 between stacks, 50 between does not
            say(c, "StackEdge49");
            for (int i = 0; i < 49; i++) {
                c.hx().chat("filler49 " + i);
            }
            c.ctx().waitTicks(6);
            premiseBetween(c, "StackEdge49", 49);
            say(c, "StackEdge49");
            c.check(lines(c, 1).get(0).equals("StackEdge49 (x2)"), "49 messages between did not stack: " + lines(c, 2));
            say(c, "StackEdge50");
            for (int i = 0; i < 50; i++) {
                c.hx().chat("filler50 " + i);
            }
            c.ctx().waitTicks(6);
            premiseBetween(c, "StackEdge50", 50);
            say(c, "StackEdge50");
            l = lines(c, 60);
            c.check(l.get(0).equals("StackEdge50") && count(l, "StackEdge50") == 2,
                    "50 messages between stacked (window is 50): " + l.get(0) + ", copies " + count(l, "StackEdge50"));
            c.note("window edge: 49 between -> stacked; 50 between -> two lines");

            int old = guiScale(c, 2);
            try {
                say(c, "StackProbe shot");
                say(c, "StackProbe shot B");
                say(c, "StackProbe shot");
                openChat(c);
                c.ctx().waitTicks(3);
                shot(c, "stacked");
            } finally {
                c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
                guiScale(c, old);
            }

            // older than 60 s: no stack
            say(c, "StackAgeProbe");
            c.note("waiting 61 s for the time window...");
            c.ctx().waitTicks(61 * 20);
            say(c, "StackAgeProbe");
            l = lines(c, 3);
            c.check(l.get(0).equals("StackAgeProbe") && l.get(1).equals("StackAgeProbe"),
                    "a copy older than 60 s stacked: " + l);
            long stacked = c.onClient(mc -> (Long) Mod.staticCall(TIDY, "testStacked")) - stacked0;
            // A x2, A x3, the wrapped line, the 49-edge, the picture's stack: exactly five, nothing else stacked.
            c.check(stacked == 5, "the mod counted " + stacked + " stacks, want exactly 5");
            long fails = c.onClient(mc -> (Long) Mod.staticCall(TIDY, "testFailures"));
            c.check(fails == 0, "the chat hook threw: " + c.onClient(mc -> Mod.staticCall(TIDY, "testLastFailure")));
            c.note("PASS interleaved stacking: " + stacked + " stacks, no orphans, window 50 / 60 s held, hook never threw");
        }
    }

    /** Premise for the window edge: exactly {@code n} messages (of any kind) sit below {@code text} in the chat. */
    @SuppressWarnings("unchecked")
    static void premiseBetween(Session c, String text, int n) {
        List<String> raw = c.onClient(mc -> (List<String>) Mod.staticCall(TIDY, "testChatLines", 80));
        int at = raw.indexOf(text);
        c.check(at == n, "premise: " + n + " messages between, but \"" + text + "\" is at index " + at
                + " (another message arrived?): " + raw.subList(0, Math.min(at + 1, raw.size())));
    }

    static JsonObject colour(String text, String colour) {
        JsonObject o = new JsonObject();
        o.addProperty("text", text);
        o.addProperty("color", colour);
        return o;
    }

    static int count(List<String> l, String exact) {
        int n = 0;
        for (String s : l) {
            if (s.equals(exact)) {
                n++;
            }
        }
        return n;
    }
}

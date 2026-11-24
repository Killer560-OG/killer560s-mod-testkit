package dev.testkit.gametest.hx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod autodialogue-1122 (his bug report, 2026-11-22 8:32 PM: Michael's trivia in the Spider's Den was not answered).
 * Hypixel's prompt is a plain "Select an option: " with the clickable option NESTED under a plain sibling (an arrow,
 * then the option), as his log's line "Select an option: [No, I am very silly] [Yes, I am very smart]" came; Auto
 * Dialogue read only the first top-level sibling's click event and never acted. The case replays his NPC lines and that
 * shape and reads the command off the wire; a purchase line (control) is never answered.
 */
final class HxAutoDialogue1122Cases {

    private static final String CFG = "dungeonextras.DungeonExtrasConfig";

    private HxAutoDialogue1122Cases() {
    }

    static void register(Session s) {
        s.test("1122-autodialogue-nested", c -> run(c, "Quick! Trivia question! Do you know what §cMob Types §fare?", true));
        // Real captures of 11-23 (logger chatclick lines): the Trapper's YES/NO, Vincent's custom-click give button, a look-alike.
        s.test("1123-autodialogue-trapper", c -> real(c, "trapper", true));
        s.test("1123-autodialogue-giveitem", c -> real(c, "giveitem", false));
        s.test("1123-autodialogue-playerfake", c -> real(c, "playerfake", false));
        // Last: its "sell" NPC line stays in the feature's 10 s NPC context and would block the Trapper case that follows.
        s.test("1122-autodialogue-unsafe", c -> run(c, "I can sell you a map for 5k coins, deal?", false));
    }

    private static JsonObject option(String text, String command) {
        JsonObject wrap = new JsonObject();
        wrap.addProperty("text", "");
        JsonArray extra = new JsonArray();
        extra.add("▶ ");
        extra.add(clickable(text, command));
        wrap.add("extra", extra);
        return wrap;
    }

    private static final String UUID = "94fb50d8-e11f-4390-a1b7-d2471b8330f8";

    private static JsonObject custom() {
        JsonObject o = new JsonObject();
        o.addProperty("text", "[GIVE ITEM] ");
        o.addProperty("color", "green");
        o.addProperty("bold", true);
        JsonObject click = new JsonObject();
        click.addProperty("action", "custom");
        click.addProperty("id", "skyblock:dialogue_response");
        click.addProperty("payload", "{npcId:\"vincent\",responseKey:\"pay\"}");
        o.add("click_event", click);
        return o;
    }

    private static JsonArray yesNo(String lead) {
        JsonArray a = new JsonArray();
        a.add(lead + "Accept the trapper's task to hunt the animal?\nClick an option: ");
        a.add(clickable("[YES]", "/chatprompt " + UUID + " YES"));
        a.add(" - ");
        a.add(clickable("[NO]", "/chatprompt " + UUID + " NO"));
        return a;
    }

    private static void real(Session c, String kind, boolean expectSend) {
        c.hx().sidebar("§e§lSKYBLOCK", "§710/31/26 §8m190CG", "", " §7⏣ §eTrapper's Den", "", "§ewww.hypixel.net");
        c.hx().tab(List.of("Area: Mushroom Desert", "Profile: Test"), null, null);
        Object cfg = c.onClient(mc -> Mod.staticCall(CFG, "getInstance"));
        boolean was = c.onClient(mc -> (Boolean) Mod.call(cfg, "isAutoDialogueEnabledRaw"));
        int delay = c.onClient(mc -> (Integer) Mod.call(cfg, "getAutoDialogueDelayTicks"));
        c.ctx().runOnClient(mc -> {
            Mod.call(cfg, "setAutoDialogueEnabled", true);
            Mod.call(cfg, "setAutoDialogueDelayTicks", 1);
        });
        List<String> sent = new CopyOnWriteArrayList<>();
        java.util.function.Consumer<net.minecraft.network.protocol.Packet<?>> before = dev.testkit.harness.PacketTrace.tap;
        dev.testkit.harness.PacketTrace.tap = p -> {
            if (p instanceof ServerboundChatCommandPacket cmd) {
                sent.add(cmd.command());
            }
        };
        try {
            c.waitUntil("Skyblock gate", mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "allows"), 100);
            if (!c.onClient(mc -> (Boolean) Mod.call(cfg, "isAutoDialogueEnabled"))) {
                c.note("legit build: Auto Dialogue does not exist there, nothing to test");
                return;
            }
            JsonElement msg;
            switch (kind) {
                case "giveitem" -> {
                    c.hx().chat("[NPC] Vincent: ✆ §fIf you can bring me one, I'll give you my contact.");
                    JsonArray a = new JsonArray();
                    a.add(custom());
                    msg = a;
                }
                case "playerfake" -> {
                    c.hx().chat("[NPC] Trevor: Any longer than that and the animal will run away!");
                    msg = yesNo("§b[MVP§c+§b] Evil§f: ");
                }
                default -> {
                    c.hx().chat("[NPC] Trevor: You will have 10 minutes to find the mob from when you accept the task.");
                    c.hx().chat("[NPC] Trevor: Any longer than that and the animal will run away!");
                    msg = yesNo("\n");
                }
            }
            c.hx().call("chat", "textJson", msg, "overlay", false);
            if (expectSend) {
                c.waitUntil("the Trapper's YES was sent", mc -> sent.contains("chatprompt " + UUID + " YES"), 60);
                c.ctx().waitTicks(40);
                c.check(sent.size() == 1, "sent more than the one YES: " + sent);
                c.note("sent: " + sent);
            } else {
                c.ctx().waitTicks(60);
                c.check(sent.isEmpty(), kind + " was clicked: " + sent);
            }
        } finally {
            dev.testkit.harness.PacketTrace.tap = before;
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "setAutoDialogueEnabled", was);
                Mod.call(cfg, "setAutoDialogueDelayTicks", delay);
            });
        }
    }

    private static void run(Session c, String npcLine, boolean expectSend) {
        c.hx().sidebar("§e§lSKYBLOCK", "§710/31/26 §8m190CG", "", " §7⏣ §eMichael's Crib", "", "§ewww.hypixel.net");
        c.hx().tab(List.of("Area: Spider's Den", "Profile: Test"), null, null);
        Object cfg = c.onClient(mc -> Mod.staticCall(CFG, "getInstance"));
        boolean was = c.onClient(mc -> (Boolean) Mod.call(cfg, "isAutoDialogueEnabledRaw"));
        int delay = c.onClient(mc -> (Integer) Mod.call(cfg, "getAutoDialogueDelayTicks"));
        c.ctx().runOnClient(mc -> {
            Mod.call(cfg, "setAutoDialogueEnabled", true);
            Mod.call(cfg, "setAutoDialogueDelayTicks", 1);
        });
        List<String> sent = new CopyOnWriteArrayList<>();
        java.util.function.Consumer<net.minecraft.network.protocol.Packet<?>> before = dev.testkit.harness.PacketTrace.tap;
        dev.testkit.harness.PacketTrace.tap = p -> {
            if (p instanceof ServerboundChatCommandPacket cmd) {
                sent.add(cmd.command());
            }
        };
        try {
            c.waitUntil("Skyblock gate", mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "allows"), 100);
            if (!c.onClient(mc -> (Boolean) Mod.call(cfg, "isAutoDialogueEnabled"))) {
                c.note("legit build: Auto Dialogue does not exist there, nothing to test");
                return;
            }
            c.hx().chat("[NPC] Michael: §fWassup! We're out here on a camping trip!");
            c.hx().chat("[NPC] Michael: §f" + npcLine);
            JsonArray msg = new JsonArray();
            msg.add("Select an option: ");
            msg.add(option("[No, I am very silly]", "/selectnpcoption michael_trivia a"));
            msg.add(" ");
            msg.add(option("[Yes, I am very smart]", "/selectnpcoption michael_trivia b"));
            JsonObject o = new JsonObject();
            o.add("textJson", msg);
            c.hx().call("chat", "textJson", o.get("textJson"), "overlay", false);
            if (expectSend) {
                c.waitUntil("the first option was answered", mc -> sent.contains("selectnpcoption michael_trivia a"), 60);
                c.check(!sent.contains("selectnpcoption michael_trivia b"), "the second option was sent too");
                c.note("sent: " + sent);
            } else {
                c.ctx().waitTicks(40);
                c.check(sent.isEmpty(), "a purchase line was answered: " + sent);
            }
        } finally {
            dev.testkit.harness.PacketTrace.tap = before;
            c.ctx().runOnClient(mc -> {
                Mod.call(cfg, "setAutoDialogueEnabled", was);
                Mod.call(cfg, "setAutoDialogueDelayTicks", delay);
            });
        }
    }
}

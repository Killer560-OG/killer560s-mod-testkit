package dev.testkit.server.hx.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.testkit.server.hx.HxBridge;
import dev.testkit.server.hx.HxChestMenu;
import dev.testkit.server.hx.HxEvents;
import dev.testkit.server.hx.HxPrimitives;

import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * A fake Moonglade Beacon "Tune Frequency" / "Upgrade Signal Strength" menu (foragebeacon-auto-1123), built from the 11-23
 * recordings (killer560s-mod-logs/beaconmenu-1123-recording.md). {@code menu.beacon {mode: "tune"|"upgrade", beacon:
 * [{color, speed, pitch, idx}], mine: [{color, speed, pitch, idx}], onStep=false}}: colors are the mod's 13 names ("light_blue"),
 * pitch 0-2. Row 10-16 is the beacon's beats, row 28-34 yours; each beat steps one slot along its row every
 * {@code 15 + 10 * (5 - speed)} ticks and plays note_block.bass at its pitch; your buttons (Color, Speed, Pitch, Pause) act
 * like the recording: left click forward, right click back, Speed 5 wraps to 1, Pitch High wraps to Low, Color red wraps to
 * white; Pause freezes your beat's step timer and unpause resumes it (recorded 11-24: five unpauses, each next step came the
 * rest of the period later). The tune completes when EVERY one of
 * your beats equals a beacon beat in color, speed, pitch AND slot, unpaused, and steps on the same tick as it (within one),
 * checked at each click (and at each step with {@code onStep}); the beat an unpause click frees needs only the slot (11-25
 * recording: his white pane shared the beacon's slot but stepped 5 ticks after it, and no Pitch click completed it, while
 * his unpause on the slot did): chat "BEACON REWARDS", the menu closes. {@code menu.beacon.state} returns {clicks, done, steps, ...};
 * {@code beacon.click} {slot, kind, tick} and {@code beacon.done} are events.
 */
final class HxBeacon implements HxMenus.Ticking {

    static final String SCRIPT = "hx.beacon";
    static final List<String> COLORS = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "cyan",
            "purple", "blue", "brown", "green", "red");
    private static final String[] PITCH = {"Low", "Normal", "High"};
    private static final float[] PITCH_SOUND = {0.095f, 0.794f, 1.492f};
    private static final int[][] TUNE_BTN = {{46, 48, 50, 52}};
    private static final int[][] UPGRADE_BTN = {{37, 39, 41, 43}, {46, 48, 50, 52}};

    /** One beat: color index, speed 1-5, pitch 0-2, slot index in its row, ticks since its last step, paused. */
    private static final class Beat {
        int color;
        int speed;
        int pitch;
        int idx;
        int since;
        boolean paused;

        int period() {
            return 15 + 10 * (5 - speed);
        }

        boolean same(Beat o) {
            return color == o.color && speed == o.speed && pitch == o.pitch && idx == o.idx;
        }

        boolean inStep(Beat o) {
            return same(o) && Math.abs(since - o.since) <= 1;
        }
    }

    private final List<Beat> beacon = new ArrayList<>();
    private final List<Beat> mine = new ArrayList<>();
    private final int[][] buttons;
    private final boolean onStep;
    private int clicks;
    private int steps;
    private int tick;
    /**
     * {@code answerLag} ticks: a click takes effect that many ticks later and the old buttons are sent back at once (recorded
     * 11-25 4:54 PM: a Speed click answered 4 ticks later, the click's own pickup undone first).
     */
    private int answerLag;
    private final List<int[]> due = new ArrayList<>();
    private boolean done;
    private boolean dirty = true;

    private HxBeacon(boolean upgrade, boolean onStep) {
        this.buttons = upgrade ? UPGRADE_BTN : TUNE_BTN;
        this.onStep = onStep;
    }

    static void register() {
        HxChestMenu.registerScript(SCRIPT, HxBeacon::onClick);
        HxBridge.register("menu.beacon", HxBeacon::open);
        HxBridge.register("menu.beacon.state", (s, a) -> {
            HxChestMenu m = HxMenus.current(HxPrimitives.player(s, a));
            HxBeacon h = m == null ? null : HxMenus.state(m.containerId, HxBeacon.class);
            return h == null ? new JsonObject() : h.state();
        });
    }

    private static void read(JsonArray from, List<Beat> into) {
        for (JsonElement e : from) {
            JsonObject o = e.getAsJsonObject();
            Beat b = new Beat();
            b.color = COLORS.indexOf(o.get("color").getAsString());
            b.speed = o.get("speed").getAsInt();
            b.pitch = o.get("pitch").getAsInt();
            b.idx = o.has("idx") ? o.get("idx").getAsInt() : 0;
            if (o.has("since")) {
                b.since = o.get("since").getAsInt();
            }
            into.add(b);
        }
    }

    private static JsonElement open(MinecraftServer server, JsonObject a) {
        ServerPlayer player = HxPrimitives.player(server, a);
        boolean upgrade = a.has("mode") && a.get("mode").getAsString().equals("upgrade");
        HxBeacon h = new HxBeacon(upgrade, a.has("onStep") && a.get("onStep").getAsBoolean());
        read(a.getAsJsonArray("beacon"), h.beacon);
        read(a.getAsJsonArray("mine"), h.mine);
        h.answerLag = a.has("answerLag") ? a.get("answerLag").getAsInt() : 0;
        HxChestMenu menu = HxMenus.open(player, 6, Component.literal(upgrade ? "Upgrade Signal Strength" : "Tune Frequency"),
                HxMenus.filled(server, 6), SCRIPT, h);
        h.render(server, menu);
        JsonObject out = new JsonObject();
        out.addProperty("containerId", menu.containerId);
        return out;
    }

    private JsonObject state() {
        JsonObject o = new JsonObject();
        o.addProperty("clicks", clicks);
        o.addProperty("done", done);
        o.addProperty("steps", steps);
        o.addProperty("tick", tick);
        JsonArray m = new JsonArray();
        for (Beat b : mine) {
            m.add(COLORS.get(b.color) + "," + b.speed + "," + b.pitch + "," + b.idx + (b.paused ? ",paused" : ""));
        }
        o.add("mine", m);
        JsonArray ph = new JsonArray(); // per beat of yours: ticks its step timer is ahead of the matching beacon beat's, or null
        for (Beat b : mine) {
            Beat t = beacon.stream().filter(b::same).findFirst().orElse(null);
            if (t == null) {
                ph.add(com.google.gson.JsonNull.INSTANCE);
            } else {
                ph.add(b.since - t.since);
            }
        }
        o.add("phase", ph);
        return o;
    }

    private void sound(ServerPlayer p, Beat b) {
        p.connection.send(new ClientboundSoundPacket(Holder.direct(SoundEvent.createVariableRangeEvent(Identifier.parse("minecraft:block.note_block.bass"))),
                SoundSource.RECORDS, p.getX(), p.getY(), p.getZ(), 1f, PITCH_SOUND[b.pitch], p.getRandom().nextLong()));
    }

    private boolean advance(ServerPlayer p, Beat b) {
        if (b.paused || ++b.since < b.period()) {
            return false;
        }
        b.since = 0;
        b.idx = (b.idx + 1) % 7;
        sound(p, b);
        return true;
    }

    @Override
    public void tick(MinecraftServer server, HxChestMenu menu, ServerPlayer player) {
        if (done) {
            return;
        }
        tick++;
        for (int i = 0; i < due.size() && !done; ) {
            int[] c = due.get(i);
            if (c[0] <= tick) {
                due.remove(i);
                apply(menu, player, c[1], c[2], c[3], c[4]);
            } else {
                i++;
            }
        }
        if (done) {
            return;
        }
        for (Beat b : beacon) {
            dirty |= advance(player, b);
        }
        for (Beat b : mine) {
            dirty |= advance(player, b);
        }
        if (dirty) {
            steps++;
            render(server, menu);
            if (onStep && complete(null)) {
                finish(player);
            }
        }
    }

    /** {@code freed}: the beat this click just unpaused, which needs only the slot. */
    private boolean complete(Beat freed) {
        for (Beat m : mine) {
            if (m.paused || beacon.stream().noneMatch(m == freed ? m::same : m::inStep)) { // 11-24: matched while paused completed only at the unpause click
                return false;
            }
        }
        return true;
    }

    private void finish(ServerPlayer player) {
        done = true;
        JsonObject e = state();
        HxEvents.custom("beacon.done", player, e);
        player.sendSystemMessage(Component.literal("BEACON REWARDS"));
        HxMenus.later(player::closeContainer);
    }

    private static ItemStack item(MinecraftServer s, String spec, String name, String... lore) {
        return HxMenus.named(s, spec, name, lore);
    }

    private static String dye(String c) {
        return switch (c) {
            case "white" -> "bone_meal";
            case "blue" -> "lapis_lazuli";
            case "brown" -> "cocoa_beans";
            default -> c + "_dye";
        };
    }

    private static String words(String c) {
        StringBuilder sb = new StringBuilder();
        for (String w : c.split("_")) {
            sb.append(sb.length() > 0 ? " " : "").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    private void render(MinecraftServer server, HxChestMenu menu) {
        dirty = false;
        ItemStack gray = item(server, "minecraft:gray_stained_glass_pane", "");
        for (int row : new int[]{10, 28}) {
            for (int i = 0; i < 7; i++) {
                set(menu, row + i, gray);
            }
        }
        for (Beat b : beacon) {
            set(menu, 10 + b.idx, item(server, "minecraft:" + COLORS.get(b.color) + "_stained_glass_pane", "Beacon Beat"));
        }
        for (int g = 0; g < mine.size(); g++) {
            Beat b = mine.get(g);
            set(menu, 28 + b.idx, item(server, "minecraft:" + COLORS.get(b.color) + "_stained_glass_pane", "Your Beat"));
            int[] btn = buttons[g];
            set(menu, btn[0], item(server, "minecraft:" + dye(COLORS.get(b.color)), "Color", "§7Current color: " + words(COLORS.get(b.color))));
            set(menu, btn[1], item(server, "minecraft:sugar", "Speed", "§7Current speed: " + b.speed));
            set(menu, btn[2], item(server, "minecraft:note_block", "Pitch", "§7Current pitch: " + PITCH[b.pitch], "§7Sound: ENABLED"));
            set(menu, btn[3], item(server, b.paused ? "minecraft:red_terracotta" : "minecraft:green_terracotta", "Pause",
                    "§7Currently: " + (b.paused ? "PAUSED" : "UNPAUSED")));
        }
        menu.broadcastChanges();
    }

    private static void set(HxChestMenu menu, int slot, ItemStack s) {
        if (!ItemStack.matches(menu.items().getItem(slot), s)) {
            menu.items().setItem(slot, s);
        }
    }

    private static void onClick(HxChestMenu menu, ServerPlayer player, int slot, int button, ContainerInput input) {
        HxBeacon h = HxMenus.state(menu.containerId, HxBeacon.class);
        if (h == null || h.done) {
            return;
        }
        for (int g = 0; g < h.mine.size(); g++) {
            int k = java.util.Arrays.binarySearch(h.buttons[g], slot);
            if (k < 0) {
                continue;
            }
            int d = button == 1 ? -1 : 1;
            if (h.answerLag > 0) {
                h.due.add(new int[]{h.tick + h.answerLag, g, k, d, slot});
                menu.sendAllDataToRemote(); // the old buttons back, the answer later
            } else {
                h.apply(menu, player, g, k, d, slot);
            }
            return;
        }
    }

    private void apply(HxChestMenu menu, ServerPlayer player, int g, int k, int d, int slot) {
        HxBeacon h = this;
        {
            Beat b = h.mine.get(g);
            switch (k) {
                case 0 -> b.color = Math.floorMod(b.color + d, 13);
                case 1 -> b.speed = Math.floorMod(b.speed - 1 + d, 5) + 1;
                case 2 -> b.pitch = Math.floorMod(b.pitch + d, 3);
                default -> {
                    b.paused = !b.paused;
                }
            }
            h.clicks++;
            JsonObject e = new JsonObject();
            e.addProperty("slot", slot);
            e.addProperty("kind", k);
            e.addProperty("tick", h.tick);
            HxEvents.custom("beacon.click", player, e);
            h.render(player.level().getServer(), menu);
            if (h.complete(k == 3 && !b.paused ? b : null)) {
                h.finish(player);
            }
            return;
        }
    }
}

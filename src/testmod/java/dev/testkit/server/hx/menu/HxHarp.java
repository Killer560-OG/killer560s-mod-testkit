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
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A fake Melody's Harp ("Harp - Test", 6 rows) whose rows advance like Hypixel's, for Auto Melody's Harp (autoharp-1120).
 * The notes of step j are listed by column (0-6). At logical step n, note j is a colored wool at row n-j (rows 0-3, slot
 * 9*row+1+column) and a Block of Quartz in the click row (slots 37-43) when n-j is 4; at n-j = 5 it is gone. A click on
 * a click-row slot is right when it reaches the board while that note shows quartz, once.
 * <ul>
 *   <li>{@code stepTicks}: server ticks per step. {@code stalls}: [[step, extraTicks], ...] holds the rows that long
 *       after that step (server lag). {@code freezes}: [[step, ticks into it, ticks], ...] freezes the server there
 *       (nothing read, judged, moved or sent), then the owed harp ticks run {@link #BURST} a server tick, as a real server
 *       catches up after a stall.</li>
 *   <li>{@code pingMs}: the round trip being faked: the client is shown the board pingMs/2 late and a click is judged
 *       pingMs/2 after it arrives, which is what a real round trip does. {@code pingSteps}: [[step, pingMs(, downMs)], ...]
 *       changes the round trip from that server step on; with downMs only the click's way changes (his spikes on
 *       Hypixel delayed the clicks, never the board: autoharp-misses-1124). Every judged click is answered with a
 *       pling downMs later, as Hypixel answers every harp click.</li>
 * </ul>
 * Events: {@code harp.click} {slot, col, step, ok, ms (into its step), rx (received, ms clock mod 100000)},
 * {@code harp.render} {step, at, adv}, {@code harp.freeze} {step, ticks, at}, {@code harp.miss} {j, col}, {@code harp.done} {ok, wrong, missed}.
 * {@code menu.harp.state} returns the same counts.
 */
final class HxHarp implements HxMenus.Ticking {

    static final String SCRIPT = "hx.harp";
    private static final String[] WOOL = {"red", "orange", "yellow", "lime", "cyan", "blue", "purple"};

    private final int[][] notes;
    private final int stepTicks;
    /** One-way delays being faked: a click is judged upMs after it arrives, the board is shown downMs late. */
    private long upMs;
    private long downMs;
    private int[][] pingSteps = new int[0][];
    /** When each judged click's pling is due (ms), oldest first. */
    private final Deque<Long> acks = new ArrayDeque<>();
    private final int[][] stalls;
    /** [[step, ticks into it, freeze ticks], ...]: the server freezes there, then catches up BURST harp ticks a tick. */
    private int[][] freezes = new int[0][];
    private static final int BURST = 4;
    /** Server ticks start a few ms either side of their 50 ms grid. The board and the plings both go out on a tick
     *  downMs after theirs; without this room a tick that came 2 ms early held one a whole tick more, a coin flip per
     *  packet that read a 100 ms trip as 150 and moved a run's score (autoharp-misses-1124 fix10: 13 to 17 of 17). */
    private static final long TICK_JITTER_MS = 5;
    private int frozen;
    private int owed;
    private int harpTicks;
    private final boolean[][] played;
    private final List<long[]> hist = new ArrayList<>();
    private final Deque<long[]> pending = new ArrayDeque<>();
    private int n;
    private int shownN = -1;
    private int lastAdvance;
    private int ok;
    private int wrong;
    private int missed;
    private boolean done;

    private HxHarp(int[][] notes, int stepTicks, int pingMs, int[][] stalls) {
        this.notes = notes;
        this.stepTicks = stepTicks;
        this.upMs = pingMs / 2L;
        this.downMs = pingMs / 2L;
        this.stalls = stalls;
        this.played = new boolean[notes.length][7];
    }

    static void register() {
        HxChestMenu.registerScript(SCRIPT, HxHarp::onClick);
        HxBridge.register("menu.harp", HxHarp::open);
        HxBridge.register("menu.harp.state", (s, a) -> {
            HxChestMenu m = HxMenus.current(HxPrimitives.player(s, a));
            HxHarp h = m == null ? null : HxMenus.state(m.containerId, HxHarp.class);
            return h == null ? new JsonObject() : h.counts();
        });
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    private static JsonElement open(MinecraftServer server, JsonObject a) {
        ServerPlayer player = HxPrimitives.player(server, a);
        JsonArray steps = a.getAsJsonArray("steps");
        int[][] notes = new int[steps.size()][];
        for (int j = 0; j < notes.length; j++) {
            JsonArray cols = steps.get(j).getAsJsonArray();
            notes[j] = new int[cols.size()];
            for (int i = 0; i < cols.size(); i++) {
                notes[j][i] = cols.get(i).getAsInt();
            }
        }
        int[][] stalls = new int[0][];
        if (a.has("stalls")) {
            JsonArray st = a.getAsJsonArray("stalls");
            stalls = new int[st.size()][];
            for (int i = 0; i < st.size(); i++) {
                stalls[i] = new int[]{st.get(i).getAsJsonArray().get(0).getAsInt(), st.get(i).getAsJsonArray().get(1).getAsInt()};
            }
        }
        HxHarp h = new HxHarp(notes, a.has("stepTicks") ? a.get("stepTicks").getAsInt() : 5,
                a.has("pingMs") ? a.get("pingMs").getAsInt() : 0, stalls);
        List<ItemStack> items = HxMenus.filled(server, 6);
        if (a.has("freezes")) {
            JsonArray fz = a.getAsJsonArray("freezes");
            h.freezes = new int[fz.size()][3];
            for (int i = 0; i < fz.size(); i++) {
                for (int k = 0; k < 3; k++) {
                    h.freezes[i][k] = fz.get(i).getAsJsonArray().get(k).getAsInt();
                }
            }
        }
        if (a.has("pingSteps")) {
            JsonArray ps = a.getAsJsonArray("pingSteps");
            h.pingSteps = new int[ps.size()][3];
            for (int i = 0; i < ps.size(); i++) {
                h.pingSteps[i][0] = ps.get(i).getAsJsonArray().get(0).getAsInt();
                h.pingSteps[i][1] = ps.get(i).getAsJsonArray().get(1).getAsInt();
                h.pingSteps[i][2] = ps.get(i).getAsJsonArray().size() > 2 ? ps.get(i).getAsJsonArray().get(2).getAsInt() : -1;
            }
        }
        h.hist.add(new long[]{now(), 0});
        HxChestMenu menu = HxMenus.open(player, 6, Component.literal("Harp - Test"), items, SCRIPT, h);
        h.render(server, menu, 0);
        JsonObject out = new JsonObject();
        out.addProperty("containerId", menu.containerId);
        return out;
    }

    private JsonObject counts() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", ok);
        o.addProperty("wrong", wrong);
        o.addProperty("missed", missed);
        o.addProperty("step", n);
        o.addProperty("done", done);
        return o;
    }

    private int extraTicks(int step) {
        for (int[] s : stalls) {
            if (s[0] == step) {
                return s[1];
            }
        }
        return 0;
    }

    private int logicalAt(long ms) {
        int at = 0;
        for (long[] h : hist) {
            if (h[0] <= ms) {
                at = (int) h[1];
            }
        }
        return at;
    }

    /** When the board reached that step (ms), for the click's margin into its step. */
    private long stepStart(int step) {
        for (long[] h : hist) {
            if (h[1] == step) {
                return h[0];
            }
        }
        return hist.get(0)[0];
    }

    @Override
    public void tick(MinecraftServer server, HxChestMenu menu, ServerPlayer player) {
        long now = now();
        int last = notes.length + 5;
        // A server freeze: nothing is read, judged, moved or sent; the ticks it owes then run in a burst, BURST a tick.
        if (frozen > 0) {
            frozen--;
            owed++;
            return;
        }
        int runs = 1 + Math.min(owed, BURST - 1);
        owed -= runs - 1;
        for (int i = 0; i < runs && frozen == 0; i++) {
            harpTick(player, now, last);
        }
        while (!acks.isEmpty() && acks.peekFirst() <= now) {
            acks.pollFirst();
            player.connection.send(new ClientboundSoundPacket(Holder.direct(SoundEvent.createVariableRangeEvent(
                    Identifier.parse("minecraft:block.note_block.pling"))), SoundSource.MASTER, player.getX(), player.getY(),
                    player.getZ(), 1f, 1f, player.getRandom().nextLong()));
        }
        int show = logicalAt(now - downMs + TICK_JITTER_MS);
        if (show > shownN) {
            render(server, menu, show);
            JsonObject r = new JsonObject();
            r.addProperty("step", show);
            r.addProperty("at", now % 100000);
            r.addProperty("adv", stepStart(show) % 100000);
            HxEvents.custom("harp.render", player, r);
        }
        if (!done && n >= last && pending.isEmpty() && shownN == n) {
            done = true;
            HxEvents.custom("harp.done", player, counts());
        }
    }

    /** One tick of the harp's own clock: due clicks are judged BEFORE the rows move on (a note hit in time is not missed). */
    private void harpTick(ServerPlayer player, long now, int last) {
        while (!pending.isEmpty() && pending.peekFirst()[0] <= now) {
            long[] c = pending.pollFirst();
            judge(player, (int) c[1], logicalAt(c[0]), c[0], now);
        }
        harpTicks++;
        if (n < last && harpTicks - lastAdvance >= stepTicks + extraTicks(n)) {
            n++;
            lastAdvance = harpTicks;
            hist.add(new long[]{now, n});
            for (int[] ps : pingSteps) {
                if (ps[0] == n) {
                    downMs = ps[2] < 0 ? ps[1] / 2L : ps[2];
                    upMs = ps[1] - downMs;
                }
            }
            int gone = n - 5;
            for (int col : gone >= 0 && gone < notes.length ? notes[gone] : new int[0]) {
                if (!played[gone][col]) {
                    missed++;
                    JsonObject e = new JsonObject();
                    e.addProperty("j", gone);
                    e.addProperty("col", col);
                    HxEvents.custom("harp.miss", player, e);
                }
            }
        }
        for (int[] f : freezes) {
            if (f[0] == n && harpTicks - lastAdvance == f[1]) {
                frozen = f[2];
                JsonObject e = new JsonObject();
                e.addProperty("step", n);
                e.addProperty("ticks", f[2]);
                e.addProperty("at", now % 100000);
                HxEvents.custom("harp.freeze", player, e);
            }
        }
    }

    private void judge(ServerPlayer player, int slot, int step, long at, long tickNow) {
        int col = slot - 37;
        int j = step - 4;
        boolean right = j >= 0 && j < notes.length && !played[j][col] && contains(notes[j], col);
        if (right) {
            played[j][col] = true;
            ok++;
        } else {
            wrong++;
        }
        JsonObject e = new JsonObject();
        e.addProperty("slot", slot);
        e.addProperty("col", col);
        e.addProperty("step", step);
        e.addProperty("ok", right);
        e.addProperty("ms", at - stepStart(step));
        e.addProperty("rx", (at - upMs) % 100000);
        HxEvents.custom("harp.click", player, e);
        acks.addLast(tickNow + downMs - TICK_JITTER_MS);
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    private void render(MinecraftServer server, HxChestMenu menu, int step) {
        shownN = step;
        for (int row = 0; row < 5; row++) {
            for (int col = 0; col < 7; col++) {
                int j = step - row;
                ItemStack s = HxMenus.filler(server);
                if (j >= 0 && j < notes.length && contains(notes[j], col)) {
                    s = HxMenus.stack(server, row == 4 ? "minecraft:quartz_block" : "minecraft:" + WOOL[col] + "_wool", 1);
                }
                int slot = 9 * row + 1 + col;
                if (!ItemStack.matches(menu.items().getItem(slot), s)) {
                    menu.items().setItem(slot, s);
                }
            }
        }
        menu.broadcastChanges();
    }

    private static void onClick(HxChestMenu menu, ServerPlayer player, int slot, int button, ContainerInput input) {
        HxHarp h = HxMenus.state(menu.containerId, HxHarp.class);
        if (h != null && slot >= 37 && slot <= 43) {
            // The faked trip starts when the click reached the server (netty thread), not when a possibly stalled server
            // tick got to it: a real server's own stall does not lengthen the network's one-way delay.
            long now = now();
            long at = HxEvents.clickArrivalMs;
            // One connection keeps its order: a click sent after a delayed one is handled after it, never before (TCP).
            long due = (at > 0 && at <= now && now - at < 2000 ? at : now) + h.upMs;
            h.pending.addLast(new long[]{h.pending.isEmpty() ? due : Math.max(due, h.pending.peekLast()[0]), slot});
        }
    }
}

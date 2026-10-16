package dev.testkit.gametest;

import dev.testkit.harness.PacketTrace;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The tick gaps an auto puzzle leaves between its own packets (mod branch puzzles-nowait-1015). killer560's standing
 * rule: automation acts the very next tick, no fixed safety waits; match a human's packets instead. Auto Water's
 * branch (water-late-1015) found two kinds of wait in the shared code, and this measures both in any puzzle room:
 * <ul>
 *   <li><b>fixed waits</b>: the gap from a block click to the next item use (a warp), from the sneak going down to the
 *   warp's use, and from a landing to the next action (click, warp or shot) - each must be 1 tick: the tick of the
 *   click, the sneak or the landing prepares (turn, sneak), the next one acts;</li>
 *   <li><b>the camera wait</b>: {@code util/TurnFirst} holding a turn up to 10 ticks because an auto's OWN free camera
 *   reads as "somebody else is turning him" - read straight off the puzzle autos' {@code TurnFirst} (its
 *   {@code foreignWait} counter) every tick, so a waited tick is counted whatever its cause; and the gap from the moment
 *   a click became possible (a landing, a sneak let go, the auto's own "ready" signal) to the click.</li>
 * </ul>
 * Recorded on the client thread: every serverbound packet is stamped with the client tick in progress (END_CLIENT_TICK
 * counts them, the auto acts at START), a landing is a jump of more than 0.9 blocks between two ticks (an etherwarp, or
 * an Ice Fill hop), and an optional per-room probe whose value changing is a "ready" moment (Tic Tac Toe's next move
 * appearing, Ice Path's silverfish stopping). A shot is skipped from the landing gap while the Shoot cooldown since the
 * last shot still ran (his setting, not a wait). The verdict refuses to pass unless the room solved (the base) and the
 * case measured the pairs it names ({@link #need}).
 */
final class PuzzleTimingExtra extends SolveExtra {

    enum Kind { CLICK, WARP, SHOT, MAP_WARP, SHIFT_DOWN, SHIFT_UP, LAND, READY, CAMERA_WAIT }

    record Ev(long tick, Kind kind, String info) {
    }

    /** Which gaps a case must have measured at least once (and which it judges). */
    enum Pair { CLICK_USE, SNEAK_USE, LAND_ACT, READY_CLICK, VOLLEY_NEXT }

    private static final String UTIL = "com.killer560.hub.autopuzzles.AutoPuzzleUtil";

    // ---- recorded on the client thread ----
    private static volatile boolean recording;
    private static boolean hooked;
    private static long ended;
    private static Vec3 lastPos;
    private static Boolean lastShift;
    private static String lastProbe;
    private static int lastWait;
    private static long logMark;
    private static Function<Minecraft, String> probe;
    private static final List<Ev> EVENTS = new ArrayList<>();

    private final List<Pair> need;
    private final Function<Minecraft, String> roomProbe;
    private final SolveExtra inner;
    /** Which actions the landing and ready gaps are judged for (all are printed). */
    private java.util.function.Predicate<Ev> judged = e -> true;
    private final java.util.function.BiConsumer<Object, Object> settings;
    private java.util.function.Consumer<Packet<?>> oldTap;
    private boolean installed;
    private int configures;
    private String summary = "not evaluated";
    private int cooldownTicks = 10;

    /**
     * @param need      the gaps that must have been measured (every measured gap is judged)
     * @param roomProbe client-thread reading whose change is a "ready" moment, or null
     * @param inner     another case's extra run alongside (a reward-chest claim), or null
     * @param settings  extra settings (auto-puzzles config, cheat-utils config), or null
     */
    PuzzleTimingExtra(List<Pair> need, Function<Minecraft, String> roomProbe, SolveExtra inner,
                      java.util.function.BiConsumer<Object, Object> settings) {
        this.need = need;
        this.roomProbe = roomProbe;
        this.inner = inner;
        this.settings = settings;
    }

    /** Judge the landing and ready gaps only for actions {@code p} accepts (Water Board: the reward chest's click). */
    PuzzleTimingExtra judgeOnly(java.util.function.Predicate<Ev> p) {
        this.judged = p;
        return this;
    }

    @Override
    void begin(String name, long mark) {
        super.begin(name, mark);
        if (inner != null) {
            inner.begin(name, mark);
        }
    }

    private static void add(long tick, Kind kind, String info) {
        synchronized (EVENTS) {
            EVENTS.add(new Ev(tick, kind, info));
        }
    }

    private static void onEnd(Minecraft mc) {
        if (!recording || mc.player == null) {
            return;
        }
        long t = ++ended;
        Vec3 pos = mc.player.position();
        if (lastPos != null && pos.distanceToSqr(lastPos) > 0.81) {
            add(t, Kind.LAND, String.format(Locale.US, "%.1f block(s)", Math.sqrt(pos.distanceToSqr(lastPos))));
        }
        lastPos = pos;
        if (probe != null) {
            String v;
            try {
                v = probe.apply(mc);
            } catch (Throwable e) {
                v = "probe failed: " + e;
            }
            if (v != null && !v.equals(lastProbe)) {
                // Read at END of tick t, after the auto's START tick ran: the auto can first act on it at t + 1.
                add(t + 1, Kind.READY, v);
            }
            lastProbe = v;
        }
        // The mod's own "a reward chest is there to click" line (RewardChest picks it the tick it appears).
        long m = LogTap.mark();
        if (m != logMark) {
            for (String l : LogTap.since(logMark)) {
                if (l.contains(": reward chest at ")) {
                    add(t, Kind.READY, "reward chest picked"); // logged by the auto at START of this tick
                }
            }
            logMark = m;
        }
        int w = foreignWait();
        if (w > 0 && w != lastWait) {
            add(t, Kind.CAMERA_WAIT, "TurnFirst foreignWait " + w);
        }
        lastWait = w;
    }

    /** The puzzle autos' TurnFirst wait counter: it counts up while a turn waits on a camera somebody else holds. */
    private static int foreignWait() {
        try {
            Class<?> util = Class.forName(UTIL);
            var f = util.getDeclaredField("TURN");
            f.setAccessible(true);
            Object turn = f.get(null);
            var w = turn.getClass().getDeclaredField("foreignWait");
            w.setAccessible(true);
            return w.getInt(turn);
        } catch (Throwable e) {
            waitUnreadable = String.valueOf(e);
            return 0;
        }
    }

    /** Set when the TurnFirst counter could not be read, so a "0 waited ticks" is never quoted off a broken probe. */
    private static volatile String waitUnreadable;

    private static void onPacket(Packet<?> p) {
        if (!recording) {
            return;
        }
        long t = ended + 1; // the tick in progress: END_CLIENT_TICK has not counted it yet
        if (p instanceof ServerboundUseItemOnPacket u) {
            net.minecraft.core.BlockPos pos = u.getHitResult().getBlockPos();
            Minecraft mc = Minecraft.getInstance();
            boolean chest = mc.level != null
                    && mc.level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.ChestBlock;
            // Who sent it: the block half RightClick.beforeUse sends ahead of a use is part of that use, not a click; a
            // Secret Aura click is another feature's (the auto's next step may wait on it, so it is a ready moment).
            String from = "";
            for (StackTraceElement f : Thread.currentThread().getStackTrace()) {
                String c = f.getClassName();
                if (c.endsWith("util.RightClick") && f.getMethodName().equals("beforeUse")) {
                    from = "paired";
                    break;
                }
                if (c.contains("SecretAura")) {
                    from = "aura";
                    break;
                }
            }
            if (from.equals("paired")) {
                return;
            }
            if (from.equals("aura")) {
                add(t + 1, Kind.READY, "Secret Aura clicked " + pos.toShortString());
                return;
            }
            add(t, Kind.CLICK, pos.toShortString() + (chest ? " chest" : ""));
        } else if (p instanceof ServerboundUseItemPacket) {
            for (StackTraceElement f : Thread.currentThread().getStackTrace()) {
                if (f.getClassName().contains(".livemap.autoclear.")) {
                    // The Interactive Map's own warp (Water Board's start area, Blaze's secret walk): not the auto's.
                    add(t, Kind.MAP_WARP, f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1));
                    return;
                }
            }
            Minecraft mc = Minecraft.getInstance();
            ItemStack held = mc.player == null ? ItemStack.EMPTY : mc.player.getMainHandItem();
            boolean aotv;
            try {
                aotv = (Boolean) ModUnderTest.staticCall(UTIL, "isAotv", new Class<?>[]{ItemStack.class},
                        new Object[]{held});
            } catch (Throwable e) {
                aotv = false;
            }
            add(t, aotv ? Kind.WARP : Kind.SHOT, held.getHoverName().getString());
        } else if (p instanceof ServerboundPlayerInputPacket in) {
            boolean shift = in.input().shift();
            if (lastShift == null || shift != lastShift) {
                add(t, shift ? Kind.SHIFT_DOWN : Kind.SHIFT_UP, "");
            }
            lastShift = shift;
        } else if (p instanceof ServerboundMovePlayerPacket) {
            // nothing: the rotation side is GrimAC's to judge (the 1015-grim-* cases)
        }
    }

    @Override
    void configure(Object auto, Object cheat) {
        if (inner != null) {
            inner.configure(auto, cheat);
        }
        if (settings != null) {
            settings.accept(auto, cheat);
        }
        try {
            cooldownTicks = (int) Math.ceil(((Integer) ModUnderTest.call(auto, "getShootCooldownMs", new Class<?>[0],
                    new Object[0])) / 50.0);
        } catch (Throwable ignored) {
            // keeps 10
        }
        // The base calls configure for the solver alone, again with the auto on, and once more after the verdict. What
        // happened before the auto was on (the room's own spawn landing) is not the auto's: start over on the second.
        configures++;
        if (configures == 2) {
            synchronized (EVENTS) {
                EVENTS.clear();
            }
        }
        if (installed) {
            return;
        }
        installed = true;
        if (!hooked) {
            hooked = true;
            ClientTickEvents.END_CLIENT_TICK.register(PuzzleTimingExtra::onEnd);
        }
        synchronized (EVENTS) {
            EVENTS.clear();
        }
        ended = 0;
        lastPos = null;
        lastShift = null;
        lastProbe = null;
        lastWait = 0;
        waitUnreadable = null;
        logMark = LogTap.mark();
        probe = roomProbe;
        oldTap = PacketTrace.tap;
        java.util.function.Consumer<Packet<?>> chained = oldTap;
        PacketTrace.tap = p -> {
            onPacket(p);
            if (chained != null) {
                chained.accept(p);
            }
        };
        recording = true;
    }

    @Override
    void tick(ClientGameTestContext ctx, int t, int solvedAt, boolean container) {
        if (inner != null) {
            inner.tick(ctx, t, solvedAt, container);
        }
    }

    @Override
    boolean satisfied() {
        return inner == null || inner.satisfied();
    }

    private static boolean act(Kind k) {
        return k == Kind.CLICK || k == Kind.WARP || k == Kind.SHOT;
    }

    @Override
    String verdict(ClientGameTestContext ctx, int solvedAt) {
        recording = false;
        PacketTrace.tap = oldTap;
        probe = null;
        List<Ev> ev;
        synchronized (EVENTS) {
            ev = new ArrayList<>(EVENTS);
        }
        List<String> bad = new ArrayList<>();
        if (inner != null) {
            String v = inner.verdict(ctx, solvedAt);
            if (v != null) {
                bad.add(v);
            }
        }
        // The timeline itself, compact, so a reader can check every number below by eye.
        StringBuilder line = new StringBuilder();
        for (Ev e : ev) {
            if (line.length() > 150) {
                SimPuzzleRoundTests.say(name, "timeline " + line);
                line.setLength(0);
            }
            line.append(e.tick()).append(':').append(e.kind().name().toLowerCase(Locale.ROOT))
                    .append(e.kind() == Kind.CLICK || e.kind() == Kind.READY ? "(" + e.info() + ")" : "").append(' ');
        }
        if (line.length() > 0) {
            SimPuzzleRoundTests.say(name, "timeline " + line);
        }
        Stats clickUse = new Stats("click -> use");
        Stats sneakUse = new Stats("sneak -> warp use");
        Stats landAct = new Stats("landing -> next action");
        Stats readyClick = new Stats("ready -> click");
        Stats volleyNext = new Stats("volley ended -> next action");
        // A volley's result known on the client (the room's probe says "volley ended #n") -> the auto's next shot, warp
        // or click: no margin on top. A shot his Shoot cooldown still held is not counted.
        for (int i = 0; i < ev.size(); i++) {
            Ev r = ev.get(i);
            if (r.kind() != Kind.READY || !r.info().contains("volley ended #")) {
                continue;
            }
            for (int j = i + 1; j < ev.size(); j++) {
                Ev n = ev.get(j);
                if (n.kind() == Kind.READY && n.info().contains("volley flying #")) {
                    break; // another volley left before anything else
                }
                if (n.kind() == Kind.MAP_WARP) {
                    break; // the secret trip, set off when the volley LEFT the bow: the Interactive Map's pace, not this
                }
                if (act(n.kind())) {
                    if (n.kind() == Kind.SHOT && lastShotBefore(ev, j) + cooldownTicks + 1 >= r.tick()) {
                        break;
                    }
                    volleyNext.add(n.tick() - r.tick(), r.info().replaceAll(".*(volley ended #\\d+).*", "$1") + " at "
                            + r.tick() + " -> " + n.kind().name().toLowerCase(Locale.ROOT) + " at " + n.tick());
                    break;
                }
            }
        }
        int waits = 0;
        for (int i = 0; i < ev.size(); i++) {
            Ev e = ev.get(i);
            switch (e.kind()) {
                case CAMERA_WAIT -> waits++;
                case CLICK -> {
                    // the next use before the next click / landing / ready moment
                    for (int j = i + 1; j < ev.size(); j++) {
                        Ev n = ev.get(j);
                        if (n.kind() == Kind.CLICK || n.kind() == Kind.LAND) {
                            break;
                        }
                        if (n.kind() == Kind.WARP || n.kind() == Kind.SHOT) {
                            clickUse.add(n.tick() - e.tick(), "click at " + e.tick() + " on " + e.info()
                                    + " -> " + n.kind().name().toLowerCase(Locale.ROOT) + " at " + n.tick());
                            break;
                        }
                    }
                    // what made the click possible: the latest landing, sneak let go or ready moment before it
                    long ready = Long.MIN_VALUE;
                    String why = null;
                    for (int j = i - 1; j >= 0; j--) {
                        Ev p = ev.get(j);
                        if (act(p.kind())) {
                            break; // the auto's own previous action: the gap after it is click -> use's business
                        }
                        if (p.kind() == Kind.LAND || p.kind() == Kind.SHIFT_UP || p.kind() == Kind.READY) {
                            ready = p.tick();
                            why = p.kind().name().toLowerCase(Locale.ROOT) + (p.kind() == Kind.READY ? " " + p.info() : "");
                            break;
                        }
                    }
                    if (why != null && judged.test(e)) {
                        readyClick.add(e.tick() - ready, why + " at " + ready + " -> click at " + e.tick() + " on " + e.info());
                    }
                }
                case WARP -> {
                    long down = Long.MIN_VALUE;
                    for (int j = i - 1; j >= 0; j--) {
                        Ev p = ev.get(j);
                        if (p.kind() == Kind.WARP || p.kind() == Kind.MAP_WARP || p.kind() == Kind.SHIFT_UP) {
                            break; // sneak held over from the previous warp, or not held at all
                        }
                        if (p.kind() == Kind.SHIFT_DOWN) {
                            down = p.tick();
                            break;
                        }
                    }
                    if (down != Long.MIN_VALUE) {
                        sneakUse.add(e.tick() - down, "sneak at " + down + " -> warp at " + e.tick());
                    }
                }
                case LAND -> {
                    long ready = e.tick();
                    String why = "landing at " + e.tick();
                    for (int j = i + 1; j < ev.size(); j++) {
                        Ev n = ev.get(j);
                        if (n.kind() == Kind.LAND) {
                            break;
                        }
                        if (n.kind() == Kind.READY) {
                            ready = n.tick();
                            why = "landing at " + e.tick() + ", ready (" + n.info() + ") at " + n.tick();
                            continue;
                        }
                        if (act(n.kind())) {
                            if (n.kind() == Kind.SHOT && lastShotBefore(ev, j) + cooldownTicks + 1 >= ready) {
                                break; // his Shoot cooldown was still running: not the auto's wait
                            }
                            if (!judged.test(n)) {
                                break;
                            }
                            landAct.add(n.tick() - ready, why + " -> " + n.kind().name().toLowerCase(Locale.ROOT)
                                    + " at " + n.tick());
                            break;
                        }
                    }
                }
                default -> {
                }
            }
        }
        for (Stats s : List.of(clickUse, sneakUse, landAct, readyClick, volleyNext)) {
            SimPuzzleRoundTests.say(name, s.describe());
        }
        SimPuzzleRoundTests.say(name, "TurnFirst camera-wait ticks: " + waits
                + (waitUnreadable == null ? " (counter read every tick)" : " - COUNTER UNREADABLE: " + waitUnreadable));
        if (waitUnreadable != null) {
            bad.add("the TurnFirst wait counter could not be read (" + waitUnreadable + ")");
        }
        if (waits > 0) {
            bad.add(waits + " tick(s) of TurnFirst waiting on the auto's own free camera");
        }
        judge(bad, Pair.CLICK_USE, clickUse);
        judge(bad, Pair.SNEAK_USE, sneakUse);
        judge(bad, Pair.LAND_ACT, landAct);
        judge(bad, Pair.READY_CLICK, readyClick);
        judge(bad, Pair.VOLLEY_NEXT, volleyNext);
        summary = String.format(Locale.US, "TIMING %s | %s | %s | %s | %s | camera-wait %d", clickUse.brief(), sneakUse.brief(),
                landAct.brief(), readyClick.brief(), volleyNext.brief(), waits);
        SimPuzzleRoundTests.say(name, summary);
        return bad.isEmpty() ? null : String.join("; ", bad);
    }

    private static long lastShotBefore(List<Ev> ev, int j) {
        for (int k = j - 1; k >= 0; k--) {
            if (ev.get(k).kind() == Kind.SHOT) {
                return ev.get(k).tick();
            }
        }
        return Long.MIN_VALUE / 4;
    }

    /** Every measured gap is judged; {@link #need} only says which must have been measured at all. */
    private void judge(List<String> bad, Pair pair, Stats s) {
        if (s.n == 0 && !need.contains(pair)) {
            return;
        }
        if (s.n == 0) {
            bad.add("no " + s.label + " pair measured - nothing to judge");
        } else if (s.max > 1) {
            bad.add(s.label + " worst " + s.max + " tick(s) (" + s.worst + ")");
        }
    }

    @Override
    String summary() {
        String in = inner == null ? "" : inner.summary();
        return in.isEmpty() ? summary : in + "; " + summary;
    }

    private static final class Stats {
        final String label;
        int n;
        long max = Long.MIN_VALUE;
        long sum;
        String worst = "";
        final List<Long> all = new ArrayList<>();

        Stats(String label) {
            this.label = label;
        }

        void add(long gap, String what) {
            n++;
            sum += gap;
            all.add(gap);
            if (gap > max) {
                max = gap;
                worst = what;
            }
        }

        String brief() {
            return n == 0 ? label + " n/a" : String.format(Locale.US, "%s n=%d max %d mean %.1f", label, n, max,
                    sum / (double) n);
        }

        String describe() {
            return brief() + (n == 0 ? "" : " " + all + " (worst: " + worst + ")");
        }
    }
}

package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 1105-logic-hypixel-servertime (mod branch masktimer-1105; killer560, 2026-11-05, real F7 P3: "one proc went to 0 back to 3
 * down to about 2.8 befoer proccing the other procced at .6 or so left", "the timer after the start finishes is off for p3
 * it waits awhile to show", "the storm pad timer also felt off by a small amount of ticks as well. for purple pad").
 *
 * <p>A hypixel.net stream as his 2026-11-05 log shows it: one chain ping a server tick plus 16% extra chain pings (census
 * 1.161 chain pings per 50 ms), a rare random id, the server's time packet every 20 server ticks (its game time is the
 * server's own tick count; before or after that tick's ping at random), network bunching, a 3.5 s stall the server does NOT
 * catch up right before Storm's lightning line (his 14:40:01), a 1.6 s one in Goldor (his 14:41:18) and a 500 ms one it does
 * catch up. Health drops 11 ticks off Goldor's phase (his line+11 / +71) and four mask procs on Goldor's real ticks. Checks:
 * Storm's Lightning reads 0 within 1 at its line and Purple 0 within 1 on the server's 62nd tick after it; the Goldor HUD
 * has a line on every tick from Storm's death to Goldor's (Start, then Tick held at 3.0); Goldor's Tick reads the server's
 * real phase within 1 on every tick (within 3 in the second after a stall) and 0 within 1 at every proc; it never rises
 * without passing 0; no mask cooldown ever rises without a proc of its own, and the Spirit Mask's reads 0 within 1 of the
 * server's 600th tick after its proc.
 */
final class MaskTimer1105Cases {

    private static final String CLOCK = "util.PerTickServerClock";
    private static final String PINGS = "witherdragons.ServerTickClock";
    private static final String TT = "ticktimers.TickTimersFeature";
    private static final String GT = "ticktimers.GoldorTick";
    private static final String SL = "ticktimers.StormLine";
    private static final String MASK = "maskinvincibility.MaskInvincibilityFeature";
    private static final long BASE = 3_000_000L;

    private MaskTimer1105Cases() {
    }

    /** The server: its tick count and its packets, one client tick at a time. */
    private static final class Hx {
        final java.util.Random random = new java.util.Random(1105L);
        int chain = -700;
        long tick;
        int pings;
        final List<Object> held = new ArrayList<>();

        /** One server tick's packets: Integer = a ping id, Long = a time packet's game time. */
        void serverTick(List<Object> out) {
            tick++;
            boolean time = tick % 20 == 0;
            boolean timeFirst = time && random.nextBoolean();
            if (timeFirst) {
                out.add(BASE + tick);
            }
            out.add(chain--);
            pings++;
            if (random.nextDouble() < 0.16) {
                out.add(chain--);
                pings++;
            }
            if (random.nextDouble() < 0.005) {
                out.add(-1 - random.nextInt(32766));
            }
            if (time && !timeFirst) {
                out.add(BASE + tick);
            }
        }

        /** One client tick (50 ms of the test's wall clock) carrying {@code serverTicks} server ticks; {@code chat} runs
         *  after the packets, as a chat line of the last of them. {@code mayBunch}: 8% of the time the packets land with the
         *  next client tick instead. */
        void clientTick(int serverTicks, Runnable chat, boolean mayBunch) {
            List<Object> p = new ArrayList<>(held);
            held.clear();
            for (int i = 0; i < serverTicks; i++) {
                serverTick(p);
            }
            if (mayBunch && chat == null && serverTicks == 1 && p.size() <= 3 && random.nextDouble() < 0.08) {
                held.addAll(p);
                p.clear();
            }
            for (Object o : p) {
                if (o instanceof Integer id) {
                    Mod.staticCall(PINGS, "onPing", id);
                } else {
                    Mod.staticCall(CLOCK, "serverTimeForTest", (Long) o);
                }
            }
            if (chat != null) {
                chat.run();
            }
            Mod.staticCall(CLOCK, "tickForTest");
            Mod.staticCall(GT, "endOfTick");
            Mod.staticCall(SL, "endOfTick");
            Mod.staticCall(MASK, "tick");
        }
    }

    private static void chat(String line) {
        Mod.staticCall(TT, "onChatMessage", Component.literal(line));
    }

    @SuppressWarnings("unchecked")
    private static List<String> hudLines() {
        Object hud = R.construct(TT + "$TickTimersHudElement");
        List<String> out = new ArrayList<>();
        for (String l : (List<String>) Mod.call(hud, "activeLines")) {
            out.add(l.replaceAll("§.", ""));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static int[] cooldowns() {
        Map<?, Integer> m = (Map<?, Integer>) R.get(MASK, "cooldownRemaining");
        int[] out = new int[3];
        int i = 0;
        for (Integer v : m.values()) {
            out[i++] = v;
        }
        return out;
    }

    static void hypixelServerTime(LogicCase c) throws Exception {
        Object simWas = R.get("secrets.DungeonState", "simOverrideActive");
        boolean gateWas = (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled");
        Mod.staticCall("util.SkyblockGate", "setEnabled", false);
        R.set("secrets.DungeonState", "simOverrideActive", true);
        try (AutoCloseable on = Mod.with("ticktimers.TickTimersConfig", "Enabled", true);
             AutoCloseable g = Mod.with("ticktimers.TickTimersConfig", "GoldorTimer", true);
             AutoCloseable st = Mod.with("ticktimers.TickTimersConfig", "GoldorStartTimer", true);
             AutoCloseable tot = Mod.with("ticktimers.TickTimersConfig", "GoldorShowTotal", false);
             AutoCloseable sl = Mod.with("ticktimers.TickTimersConfig", "StormLine", true);
             AutoCloseable dt = Mod.with("ticktimers.TickTimersConfig", "DisplayInTicks", true);
             AutoCloseable sym = Mod.with("ticktimers.TickTimersConfig", "ShowSymbol", true);
             AutoCloseable pre = Mod.with("ticktimers.TickTimersConfig", "ShowPrefix", true);
             AutoCloseable mOn = Mod.with("maskinvincibility.MaskInvincibilityConfig", "Enabled", true);
             AutoCloseable mTitle = Mod.with("maskinvincibility.MaskInvincibilityConfig", "ProcTitle", false);
             AutoCloseable mParty = Mod.with("maskinvincibility.MaskInvincibilityConfig", "AnnounceToParty", false);
             AutoCloseable mSwap = Mod.with("maskinvincibility.MaskInvincibilityConfig", "AutoSwapEnabled", false)) {
            Mod.staticCall(CLOCK, "hypixelForTest", Boolean.TRUE);
            Mod.staticCall(CLOCK, "untrustForTest");
            Hx hx = new Hx();
            for (int i = 0; i < 60; i++) {
                hx.clientTick(1, null, false);
            }
            Mod.staticCall(TT, "resetAll");
            c.check("hypixel.net mode: trusted", (Boolean) Mod.staticCall(CLOCK, "trusted"), "not trusted");

            // ---- Storm: Lightning across a 3.5 s stall the server does not catch up, then Purple. ----
            hx.clientTick(1, () -> chat("[BOSS] Storm: Pathetic Maxor, just like expected."), false);
            long open = hx.tick;
            while (hx.tick < open + 520) {
                hx.clientTick(1, null, true);
            }
            while (!hx.held.isEmpty()) {
                hx.clientTick(1, null, false);
            }
            for (int i = 0; i < 70; i++) {
                hx.clientTick(0, null, false); // 3.5 s: no server tick, no packet
            }
            double banked = 0.0; // the credit is drained in at the next ping, so read it after each tick
            while (hx.tick < open + 554) {
                hx.clientTick(1, null, false);
                banked = Math.min(banked, ((Number) R.get(CLOCK, "wallExcess")).doubleValue());
            }
            double paid = ((Number) R.get(CLOCK, "wallExcess")).doubleValue();
            // The feature acted: the stall banked the wall clock's credit, and the server's time packets took it back (the
            // extra chain pings ate it before 2026-11-05 and ran the count ahead: his Purple read 62 in 59.3 wall ticks).
            c.check("the stall banked credit and the next time packets took it back", banked <= -30.0 && paid >= -3.0,
                    "banked " + banked + ", after " + paid);
            int[] leftAtLine = new int[1];
            hx.clientTick(1, () -> {
                leftAtLine[0] = (Integer) Mod.staticCall(SL, "remaining");
                chat("[BOSS] Storm: ENERGY HEED MY CALL!");
            }, false);
            long light = hx.tick;
            c.check("Lightning read 0 within 1 at the lightning line (555 server ticks after Storm's line, a 3.5 s stall"
                    + " in it that the server did not catch up)", Math.abs(leftAtLine[0]) <= 1, "read " + leftAtLine[0]);
            int purpleWorst = 0;
            String purpleAt = "";
            int purpleAtZero = 99;
            while (hx.tick < light + 66) {
                hx.clientTick(1, null, false);
                int expected = (int) Math.max(0L, 62L - (hx.tick - light));
                int shown = (Integer) Mod.staticCall(SL, "remaining");
                if (Math.abs(shown - expected) > Math.abs(purpleWorst)) {
                    purpleWorst = shown - expected;
                    purpleAt = "server tick +" + (hx.tick - light) + ": shows " + shown + ", real " + expected;
                }
                if (hx.tick == light + 61) {
                    purpleAtZero = shown; // the tick before the 62nd: 1 left
                }
            }
            c.check("Purple read the server's ticks since the lightning line within 1 on every tick", Math.abs(purpleWorst) <= 1,
                    "worst " + purpleWorst + " (" + purpleAt + ")");
            c.check("Purple read 1 on the server's 61st tick after the lightning line (0 on the 62nd), within 1",
                    Math.abs(purpleAtZero - 1) <= 1, "read " + purpleAtZero);

            // ---- Storm's death to Goldor's line: Start, then Tick held at 3.0, never a gap. ----
            hx.clientTick(1, () -> chat("[BOSS] Storm: I should have known that I stood no chance."), false);
            long death = hx.tick;
            int gaps = 0;
            String gapAt = "";
            boolean sawHeld = false;
            while (hx.tick < death + 89) {
                hx.clientTick(1, null, false);
                List<String> lines = hudLines();
                boolean start = lines.stream().anyMatch(l -> l.startsWith("Start:"));
                boolean tick = lines.stream().anyMatch(l -> l.startsWith("Tick:"));
                if (!start && !tick) {
                    gaps++;
                    gapAt = gapAt.isEmpty() ? "storm+" + (hx.tick - death) + " " + lines : gapAt;
                }
                if (hx.tick - death > 66 && tick) {
                    sawHeld |= lines.stream().anyMatch(l -> l.equals("Tick: 60t"));
                }
            }
            c.check("the Goldor HUD had a line on every tick from Storm's death to Goldor's line", gaps == 0,
                    gaps + " ticks with none, first " + gapAt);
            c.check("after Start ran out the Tick stood at 3.0 until Goldor's line", sawHeld, hudLines().toString());

            // ---- Goldor: 22 cycles, the line's phase is the server's. ----
            hx.clientTick(1, () -> chat("[BOSS] Goldor: Who dares trespass into my domain?"), false);
            long goldor0 = hx.tick + 60;
            c.check("Goldor's line: the Tick reads 3.0 (60)", (Integer) Mod.staticCall(GT, "remaining") == 60,
                    "reads " + Mod.staticCall(GT, "remaining"));
            int[] procCycles = {5, 7, 18, 21};
            String[] procLines = {"Second Wind Activated! Your Spirit Mask saved your life!", "Your ⚚ Bonzo's Mask saved your life!",
                    "Second Wind Activated! Your Spirit Mask saved your life!", "Your Phoenix Pet saved you from certain death!"};
            long lostStallAt = goldor0 + 9L * 60 + 20;
            long caughtStallAt = goldor0 + 13L * 60 + 33;
            long end = goldor0 + 22L * 60;
            long settleUntil = Long.MIN_VALUE;
            int worst = 0;
            String worstAt = "";
            int worstNear = 0;
            int checked = 0;
            int rises = 0;
            String riseAt = "";
            int coolRises = 0;
            String coolRiseAt = "";
            List<Integer> procOffsets = new ArrayList<>();
            long spiritProc = Long.MIN_VALUE;
            int spiritAt600 = 99;
            int spiritAt597 = -1;
            int prevShown = 60;
            long prevClock = (Long) Mod.staticCall(CLOCK, "now");
            int[] prevCool = cooldowns();
            boolean lostDone = false;
            boolean caughtDone = false;
            while (hx.tick < end) {
                int serverTicks = 1;
                if (!lostDone && hx.tick == lostStallAt) {
                    lostDone = true;
                    for (int i = 0; i < 32; i++) {
                        hx.clientTick(0, null, false); // 1.6 s, never caught up: the server lost 32 ticks
                    }
                    settleUntil = hx.tick + 21;
                }
                if (!caughtDone && hx.tick == caughtStallAt) {
                    caughtDone = true;
                    for (int i = 0; i < 10; i++) {
                        hx.clientTick(0, null, false);
                    }
                    serverTicks = 11; // caught up in one burst
                    settleUntil = hx.tick + 31;
                }
                long next = hx.tick + serverTicks;
                long phase = Math.floorMod(next - goldor0, 60L);
                long cycle = Math.floorDiv(next - goldor0, 60L);
                Runnable chatNow = null;
                boolean procNow = false;
                if (phase == 0 && serverTicks == 1) {
                    for (int k = 0; k < procCycles.length; k++) {
                        if (cycle == procCycles[k]) {
                            String line = procLines[k];
                            procNow = true;
                            chatNow = () -> {
                                chat(line);
                                Mod.staticCall(MASK, "onChatMessage", Component.literal(line));
                            };
                            if (k == 0) {
                                spiritProc = next;
                            }
                        }
                    }
                }
                hx.clientTick(serverTicks, chatNow, !procNow && phase != 59);
                if (phase == 11 && cycle >= 0 && cycle < 6 || phase == 37 && cycle == 3 || phase == 49 && cycle == 15) {
                    Mod.staticCall(GT, "testHitKindNow", "SELF"); // your health, but not Goldor (his line+11 / +71)
                }
                if (procNow) {
                    @SuppressWarnings("unchecked")
                    List<Integer> offs = (List<Integer>) Mod.staticCall(GT, "testOffsetsAtHits");
                    procOffsets.add(offs.isEmpty() ? 99 : offs.get(offs.size() - 1));
                }
                if (spiritProc != Long.MIN_VALUE && hx.held.isEmpty()) {
                    if (hx.tick == spiritProc + 597) {
                        spiritAt597 = cooldowns()[0];
                    } else if (hx.tick == spiritProc + 600) {
                        spiritAt600 = cooldowns()[0];
                    }
                }
                int[] cool = cooldowns();
                for (int t = 0; t < 3; t++) {
                    if (cool[t] > prevCool[t] && !procNow) {
                        coolRises++;
                        coolRiseAt = coolRiseAt.isEmpty() ? "mask " + t + " " + prevCool[t] + " -> " + cool[t] : coolRiseAt;
                    }
                }
                prevCool = cool;
                int shown = (Integer) Mod.staticCall(GT, "remaining");
                long clock = (Long) Mod.staticCall(CLOCK, "now");
                if (shown > prevShown && clock - prevClock < prevShown) {
                    rises++;
                    riseAt = riseAt.isEmpty() ? "goldor+" + (hx.tick - goldor0) + ": " + prevShown + " -> " + shown
                            + " with the clock +" + (clock - prevClock) : riseAt;
                }
                prevShown = shown;
                prevClock = clock;
                if (!hx.held.isEmpty()) {
                    continue; // the client has not seen this tick yet
                }
                int expected = (int) Math.floorMod(goldor0 - hx.tick, 60L);
                int err = (int) (Math.floorMod(shown - expected + 30L, 60L) - 30L);
                if (hx.tick < settleUntil) {
                    worstNear = Math.max(worstNear, Math.abs(err));
                    continue;
                }
                checked++;
                if (Math.abs(err) > Math.abs(worst)) {
                    worst = err;
                    worstAt = "goldor+" + (hx.tick - goldor0) + ": shows " + shown + ", real " + expected;
                }
            }
            c.check("the stream carried Hypixel's extra chain pings (about 1.16 a server tick)",
                    hx.pings >= hx.tick * 112 / 100, hx.pings + " pings in " + hx.tick + " server ticks");
            c.check("checked Goldor's line on most of 22 cycles", checked >= 20 * 60 * 9 / 10, checked + " ticks");
            c.check("Goldor's Tick read the server's real phase within 1 on every tick (the line's phase, your own health"
                    + " drops 11 off it, a 1.6 s stall the server lost and a 500 ms one it caught up)", Math.abs(worst) <= 1,
                    "worst " + worst + " (" + worstAt + ")");
            c.check("within 3 in the second after each stall (the next time packet settles it)", worstNear <= 3,
                    "worst " + worstNear);
            c.check("every mask proc on Goldor's tick read 0 within 1", procOffsets.size() == 4
                    && procOffsets.stream().allMatch(o -> Math.abs(o) <= 1), "offsets at the procs " + procOffsets);
            c.check("the Tick never rose without passing 0", rises == 0, rises + " rises, first " + riseAt);
            c.check("no mask cooldown rose without a proc of its own", coolRises == 0, coolRises + ", first " + coolRiseAt);
            c.check("the Spirit Mask's 30 s cooldown read 0 within 1 of the server's 600th tick after its proc",
                    spiritAt600 <= 1 && spiritAt597 >= 2, "at +597: " + spiritAt597 + ", at +600: " + spiritAt600);
            c.eq("no Goldor re-lock was needed", 0, Mod.staticCall(GT, "testRelocks"));
        } finally {
            Mod.staticCall(CLOCK, "hypixelForTest", (Object) null);
            Mod.staticCall(CLOCK, "untrustForTest");
            Mod.staticCall(TT, "resetAll");
            R.set("secrets.DungeonState", "simOverrideActive", simWas);
            Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas);
        }
    }
}

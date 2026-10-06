package dev.testkit.gametest;

import dev.testkit.harness.PacketTrace;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Measurements for Auto Routes' timing cases (96-ar-chain / -crypthold, 62-argrim-chain / -crypt), read from
 * {@link PacketTrace}'s buckets after the run.
 *
 * <p><b>Ticks per warp.</b> A route's use goes out at START_CLIENT_TICK and lands in the PREVIOUS bucket (see 96-ar's
 * Sample), so the bucket index of each {@code use_item} is a tick count that moves exactly as the uses do; the gaps
 * between consecutive uses are the ticks per warp.
 *
 * <p><b>Landing before the next warp.</b> Vanilla answers the server's teleport ({@code player_position}) with
 * {@code accept_teleportation} at once, from the packet handler. So in the serverbound stream, an
 * {@code accept_teleportation} between two etherwarp uses proves the second was sent after the client had the server's
 * landing for the first - aimed from where the server put him, not ahead of it.
 */
final class ArChainMeasure {

    private ArChainMeasure() {
    }

    /** One chain, as the trace saw it. */
    record Chain(List<Integer> useTicks, List<Integer> gaps, int usesBeforeLanding, int teleportsReceived,
                 List<Integer> landingToUse) {

        int warps() {
            return useTicks.size();
        }

        double ticksPerWarp() {
            return useTicks.size() < 2 ? Double.NaN
                    : (useTicks.get(useTicks.size() - 1) - useTicks.get(0)) / (double) (useTicks.size() - 1);
        }

        /** Every warp after the first went out on the tick its previous landing was accepted. */
        boolean firesOnLanding() {
            return landingToUse.size() == useTicks.size() - 1 && landingToUse.stream().allMatch(l -> l == 0);
        }

        int maxGap() {
            return gaps.stream().mapToInt(Integer::intValue).max().orElse(-1);
        }

        String describe() {
            return String.format(Locale.ROOT, "%d warp(s), %.2f tick(s) per warp, gaps %s, %d use(s) sent before the "
                    + "previous landing's teleport accept, %d player_position received, ticks from each landing's "
                    + "teleport accept to the next use %s", warps(), ticksPerWarp(), gaps, usesBeforeLanding,
                    teleportsReceived, landingToUse);
        }
    }

    /**
     * The etherwarp uses of the recorded trace from the {@code skip}-th on (skip 1 to leave out a hand warp onto the
     * start node), their gaps, and how many went out with no teleport accept since the use before them.
     */
    static Chain chain(int skip) {
        List<Integer> ticks = new ArrayList<>();
        int before = 0;
        int seenUses = 0;
        boolean acceptSince = false;
        int lastAccept = -1;
        List<Integer> lag = new ArrayList<>();
        int n = PacketTrace.tickCount();
        for (int t = 0; t < n; t++) {
            for (String p : PacketTrace.sentOn(t)) {
                if (p.equals("accept_teleportation")) {
                    acceptSince = true;
                    lastAccept = t;
                } else if (p.equals("use_item")) {
                    seenUses++;
                    if (seenUses > skip) {
                        if (!ticks.isEmpty() && !acceptSince) {
                            before++;
                        }
                        if (!ticks.isEmpty() && acceptSince) {
                            lag.add(t - lastAccept);
                        }
                        ticks.add(t);
                    }
                    acceptSince = false;
                }
            }
        }
        List<Integer> gaps = new ArrayList<>();
        for (int i = 1; i < ticks.size(); i++) {
            gaps.add(ticks.get(i) - ticks.get(i - 1));
        }
        return new Chain(ticks, gaps, before, PacketTrace.received("player_position"), lag);
    }

    /**
     * A held right click as the trace saw it, from bucket {@code from}: each tick that sent a use ({@code use_item_on}
     * and/or {@code use_item}), with what it sent, and the gaps between them.
     */
    record Held(List<Integer> ticks, List<Integer> gaps, List<String> perUse) {

        String describe() {
            return ticks.size() + " use tick(s), gaps " + gaps + ", packets per use " + perUse.stream().distinct().toList();
        }

        /** True when every gap is {@code period} (vanilla: rightClickDelay 4). */
        boolean every(int period) {
            return !gaps.isEmpty() && gaps.stream().allMatch(g -> g == period);
        }
    }

    static Held held(int from) {
        List<Integer> ticks = new ArrayList<>();
        List<String> per = new ArrayList<>();
        int n = PacketTrace.tickCount();
        for (int t = Math.max(0, from); t < n; t++) {
            List<String> uses = PacketTrace.sentOn(t).stream()
                    .filter(p -> p.equals("use_item") || p.equals("use_item_on")).toList();
            if (!uses.isEmpty()) {
                ticks.add(t);
                per.add(String.join("+", uses));
            }
        }
        List<Integer> gaps = new ArrayList<>();
        for (int i = 1; i < ticks.size(); i++) {
            gaps.add(ticks.get(i) - ticks.get(i - 1));
        }
        return new Held(ticks, gaps, per);
    }

    /** The chain round the arena (relative block x,z), each node etherwarping to the next; the last lands on {@link #END}. */
    static final int[][] CHAIN = {{6, 6}, {12, 6}, {18, 6}, {24, 6}, {24, 12}, {24, 18}, {24, 24}, {18, 24}, {12, 24},
            {6, 24}, {6, 18}, {6, 12}};
    /** Where the last warp lands: inside the ring, on no node. */
    static final int[] END = {12, 12};
}

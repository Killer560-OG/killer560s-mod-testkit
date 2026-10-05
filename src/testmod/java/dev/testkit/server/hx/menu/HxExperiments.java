package dev.testkit.server.hx.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.testkit.server.hx.HxChestMenu;
import dev.testkit.server.hx.HxEvents;
import dev.testkit.server.hx.HxPrimitives;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Experimentation Table minigames, server side, built from what the mod's solver reads (sources at f40ec89, paths
 * under {@code src/main/java/com/killer560/hub/experiments/}):
 * <ul>
 *   <li>titles {@code "<Game> (<tier>)"}: ExperimentsProfitTracker.java:80 PUZZLE_TITLE, ExperimentSolver.java:236-244
 *       mode detection; ExperimentNavigator.java:452 quotes "Ultrasequencer (Metaphysical)";</li>
 *   <li>the phase signal is slot 49: glowstone = watch, clock = your turn (ExperimentSolver.java:597-604, :638-645);</li>
 *   <li>Chronomatron: the NEW note is still glinted when the clock appears, notes live in slots 10-43
 *       (ExperimentSolver.java:605-623); the note item's type is not read;</li>
 *   <li>Ultrasequencer: during glowstone the notes are dye-family items whose STACK COUNT is the order, slots 9-44
 *       (ExperimentSolver.java:644-652, isSequenceItem :1186-1190); hidden on the clock;</li>
 *   <li>Superpairs: covered tiles are named "Click any button!" / "Click a second button!" (SUPERPAIRS_HIDDEN_PATTERN,
 *       ExperimentSolver.java:1207-1208), tiles in slots 9-44, "Remaining Clicks: N" in slot 4
 *       (ExperimentsProfitTracker.java:84 REMAINING_CLICKS; ExperimentsFeature.java:1123-1136).</li>
 * </ul>
 * A wrong click ends the game the way the mod expects ({@code experiment.over} event, then the "Experiment Over"
 * reward screen when {@code rewards} is given). Which slots hold the notes is the testkit's choice.
 */
final class HxExperiments {

    static final String SCRIPT = "hx.experiment";

    enum Game { CHRONOMATRON, ULTRASEQUENCER, SUPERPAIRS }

    enum Phase { WATCH, TURN, OVER }

    /** Note positions: inside 10-43 (Chronomatron) and 9-44 (Ultrasequencer), away from the filler border. */
    private static final int[] NOTE_SLOTS = {10, 12, 14, 16, 28, 30, 32, 34, 20, 22, 24, 38, 40, 42};
    /** Chronomatron note colours (terracotta; the solver only reads the glint). */
    private static final String[] NOTE_ITEMS = {"minecraft:red_terracotta", "minecraft:blue_terracotta",
            "minecraft:lime_terracotta", "minecraft:yellow_terracotta", "minecraft:purple_terracotta",
            "minecraft:orange_terracotta", "minecraft:cyan_terracotta", "minecraft:white_terracotta",
            "minecraft:pink_terracotta", "minecraft:brown_terracotta", "minecraft:green_terracotta",
            "minecraft:gray_terracotta", "minecraft:magenta_terracotta", "minecraft:light_blue_terracotta"};
    /** Ultrasequencer numbered notes: dye-family per ExperimentSolver.isSequenceItem (:1186-1190). */
    private static final String[] DYES = {"minecraft:lime_dye", "minecraft:red_dye", "minecraft:light_blue_dye",
            "minecraft:yellow_dye", "minecraft:orange_dye", "minecraft:pink_dye", "minecraft:cyan_dye"};

    static final class Session implements HxMenus.Ticking {
        final Game game;
        final String tier;
        final Random rng;
        final int maxRounds;
        final int watchTicks;
        final int holdTicks;
        final JsonObject rewards;
        final List<Integer> sequence = new ArrayList<>();
        Phase phase = Phase.WATCH;
        int round = 0;
        int input = 0;
        long phaseStart;
        int clicks;
        int wrong;
        int clicksDuringWatch;
        final List<Long> clickTicks = new ArrayList<>();
        // Superpairs
        final List<Integer> tiles = new ArrayList<>();
        final List<String[]> faces = new ArrayList<>();
        final boolean[] matched = new boolean[54];
        int firstOpen = -1;
        int secondOpen = -1;
        long recoverAt = -1;
        int remainingClicks;
        int pairsFound;
        // Superpairs powerups, only on a board given a "layout": {"powerup":true} (or "find") is Instant Find,
        // {"powerup":"clicks","amount":N} is "Gained +N Clicks". As on Hypixel (killer560, 2026-10-05): "if a powerup
        // is the second click of a turn the first click is still up but then the next click is treated with either
        // the insta find from the powerup or if it just more clicks then its basically a free space. If you do a
        // powerup into a powerup it is the same deal, if you get an insta find into an insta find then your next two
        // clicks are insta finds." So turning one over costs a click and never touches the turn; an Instant Find arms
        // the next click (armed counts, they stack): that tile and its partner are claimed - closing the turn if the
        // partner is the turn's open tile. A powerup turned over by an armed click leaves it armed. Covered tiles read
        // "Next button is instantly rewarded!" while armed (the mod's SUPERPAIRS_HIDDEN_PATTERN lists that text).
        final java.util.Map<Integer, Integer> powerups = new java.util.HashMap<>();   // tile index -> +clicks (0 = Instant Find)
        int armed;
        int powerupsRevealed;
        int instantFindsSpent;
        /** Every pair the server counted as claimed, in order: {a, b, name, viaPowerup, tick}. */
        final List<JsonObject> claimed = new ArrayList<>();

        Session(Game game, String tier, long seed, int maxRounds, int watchTicks, int holdTicks, JsonObject rewards) {
            this.game = game;
            this.tier = tier;
            this.rng = new Random(seed);
            this.maxRounds = maxRounds;
            this.watchTicks = watchTicks;
            this.holdTicks = holdTicks;
            this.rewards = rewards;
        }

        String title() {
            return switch (game) {
                case CHRONOMATRON -> "Chronomatron (" + tier + ")";
                case ULTRASEQUENCER -> "Ultrasequencer (" + tier + ")";
                case SUPERPAIRS -> "Superpairs (" + tier + ")";
            };
        }

        @Override
        public void tick(MinecraftServer server, HxChestMenu menu, ServerPlayer player) {
            long now = server.getTickCount();
            if (phase == Phase.OVER) {
                return;
            }
            switch (game) {
                case CHRONOMATRON -> tickChronomatron(server, menu, now);
                case ULTRASEQUENCER -> tickUltrasequencer(server, menu, now);
                case SUPERPAIRS -> {
                    if (recoverAt > 0 && now >= recoverAt) {
                        cover(server, menu, firstOpen);
                        cover(server, menu, secondOpen);
                        firstOpen = -1;
                        secondOpen = -1;
                        recoverAt = -1;
                        for (int t : tiles) {
                            cover(server, menu, t);     // every cover back to "Click any button!"
                        }
                        menu.broadcastChanges();
                    }
                }
                default -> {
                }
            }
        }

        void startRound(MinecraftServer server, HxChestMenu menu) {
            round++;
            input = 0;
            if (game == Game.CHRONOMATRON) {
                sequence.add(NOTE_SLOTS[rng.nextInt(8)]);
            } else {
                // a fresh random order of `round` distinct slots each round, as the real game reshuffles
                List<Integer> pool = new ArrayList<>();
                for (int s : NOTE_SLOTS) {
                    pool.add(s);
                }
                Collections.shuffle(pool, rng);
                sequence.clear();
                sequence.addAll(pool.subList(0, Math.min(round + 1, pool.size())));
            }
            phase = Phase.WATCH;
            phaseStart = server.getTickCount();
            draw(server, menu);
        }

        int length() {
            return sequence.size();
        }

        void tickChronomatron(MinecraftServer server, HxChestMenu menu, long now) {
            if (phase == Phase.WATCH) {
                // light each note in turn for watchTicks, then the clock with the newest note still lit
                int step = (int) ((now - phaseStart) / Math.max(1, watchTicks));
                if (step < sequence.size()) {
                    drawNotes(server, menu, sequence.get(step));
                    setItem(server, menu, 49, "minecraft:glowstone");
                    menu.broadcastChanges();
                } else {
                    phase = Phase.TURN;
                    phaseStart = now;
                    drawNotes(server, menu, sequence.get(sequence.size() - 1));
                    setItem(server, menu, 49, "minecraft:clock");
                    menu.broadcastChanges();
                }
            } else if (phase == Phase.TURN && now - phaseStart == holdTicks) {
                drawNotes(server, menu, -1);
                menu.broadcastChanges();
            }
        }

        void tickUltrasequencer(MinecraftServer server, HxChestMenu menu, long now) {
            if (phase == Phase.WATCH && now - phaseStart >= watchTicks) {
                phase = Phase.TURN;
                phaseStart = now;
                for (int s : sequence) {
                    setItem(server, menu, s, "minecraft:gray_stained_glass_pane");
                }
                setItem(server, menu, 49, "minecraft:clock");
                menu.broadcastChanges();
            }
        }

        void drawNotes(MinecraftServer server, HxChestMenu menu, int lit) {
            for (int i = 0; i < 8; i++) {
                ItemStack s = HxMenus.stack(server, NOTE_ITEMS[i], 1);
                if (NOTE_SLOTS[i] == lit) {
                    HxMenus.glint(s);
                }
                menu.items().setItem(NOTE_SLOTS[i], s);
            }
        }

        void draw(MinecraftServer server, HxChestMenu menu) {
            for (int i = 0; i < 54; i++) {
                menu.items().setItem(i, HxMenus.filler(server));
            }
            if (game == Game.CHRONOMATRON) {
                drawNotes(server, menu, -1);
                setItem(server, menu, 49, "minecraft:glowstone");
            } else if (game == Game.ULTRASEQUENCER) {
                for (int i = 0; i < sequence.size(); i++) {
                    menu.items().setItem(sequence.get(i), HxMenus.stack(server, DYES[i % DYES.length], i + 1));
                }
                setItem(server, menu, 49, "minecraft:glowstone");
            }
            menu.broadcastChanges();
        }

        void setItem(MinecraftServer server, HxChestMenu menu, int slot, String id) {
            menu.items().setItem(slot, HxMenus.stack(server, id, 1));
        }

        // ---- Superpairs ----

        void setupSuperpairs(MinecraftServer server, HxChestMenu menu, int pairs, int clicksAllowed, JsonArray layout) {
            for (int i = 0; i < 54; i++) {
                menu.items().setItem(i, HxMenus.filler(server));
            }
            String[][] kinds = {
                    {"minecraft:enchanted_book", "Power VI"}, {"minecraft:enchanted_book", "Sharpness V"},
                    {"minecraft:enchanted_book", "Protection V"}, {"minecraft:enchanted_book", "Growth V"},
                    {"minecraft:enchanted_book", "Smite VI"}, {"minecraft:enchanted_book", "Looting III"}};
            if (layout != null) {
                // An explicit board, tile by tile from slot 9: {item, name} or {powerup: true}. No shuffle.
                for (JsonElement el : layout) {
                    JsonObject t = el.getAsJsonObject();
                    if (t.has("powerup")) {
                        JsonElement kind = t.get("powerup");
                        boolean clicksKind = kind.isJsonPrimitive() && kind.getAsJsonPrimitive().isString()
                                && kind.getAsString().equals("clicks");
                        if (clicksKind) {
                            int amount = t.has("amount") ? t.get("amount").getAsInt() : 3;
                            powerups.put(faces.size(), amount);
                            faces.add(new String[] {"minecraft:clock", "Gained +" + amount + " Clicks", "Instant powerup!"});
                        } else {
                            powerups.put(faces.size(), 0);
                            faces.add(new String[] {"minecraft:nether_star", "Instant Find", "Powerup for next click!"});
                        }
                    } else {
                        faces.add(new String[] {t.get("item").getAsString(), t.get("name").getAsString()});
                    }
                }
            } else {
                for (int i = 0; i < pairs; i++) {
                    faces.add(kinds[i % kinds.length]);
                    faces.add(kinds[i % kinds.length]);
                }
                Collections.shuffle(faces, rng);
            }
            for (int i = 0; i < faces.size(); i++) {
                tiles.add(9 + i);   // first row of the 9-44 board, snake order starts at 9
            }
            for (int t : tiles) {
                cover(server, menu, t);
            }
            remainingClicks = clicksAllowed;
            menu.items().setItem(4, HxMenus.named(server, "minecraft:book", "Remaining Clicks: " + remainingClicks));
            phase = Phase.TURN;
            menu.broadcastChanges();
        }

        void cover(MinecraftServer server, HxChestMenu menu, int slot) {
            if (slot < 0 || matched[slot] || (slot == firstOpen && recoverAt < 0)) {
                return;
            }
            menu.items().setItem(slot, HxMenus.named(server, "minecraft:light_blue_stained_glass_pane",
                    armed > 0 ? "Next button is instantly rewarded!"
                            : firstOpen >= 0 && slot != firstOpen ? "Click a second button!" : "Click any button!"));
        }

        int regularTiles() {
            return tiles.size() - powerups.size();
        }

        void claim(int a, int b, String name, boolean viaPowerup, long tick) {
            JsonObject c = new JsonObject();
            c.addProperty("a", a);
            c.addProperty("b", b);
            c.addProperty("name", name);
            c.addProperty("viaPowerup", viaPowerup);
            c.addProperty("tick", tick);
            claimed.add(c);
        }
    }

    private static Session last;

    private HxExperiments() {
    }

    static void register() {
        HxChestMenu.registerScript(SCRIPT, HxExperiments::onClick);
    }

    /** menu.experiment {game, tier="High", seed=1, rounds=3, watch=10, hold=6, pairs=3, clicks=20, rewards?} */
    static JsonElement open(MinecraftServer server, JsonObject a) {
        ServerPlayer player = HxPrimitives.player(server, a);
        Session s = start(server, player, a);
        JsonObject out = describe(s);
        return out;
    }

    static Session start(MinecraftServer server, ServerPlayer player, JsonObject a) {
        Game game = Game.valueOf(a.get("game").getAsString().toUpperCase(Locale.ROOT));
        Session s = new Session(game, a.has("tier") ? a.get("tier").getAsString() : "High",
                a.has("seed") ? a.get("seed").getAsLong() : 1, a.has("rounds") ? a.get("rounds").getAsInt() : 3,
                a.has("watch") ? a.get("watch").getAsInt() : 10, a.has("hold") ? a.get("hold").getAsInt() : 6,
                a.has("rewards") ? a.getAsJsonObject("rewards") : null);
        List<ItemStack> items = HxMenus.filled(server, 6);
        HxChestMenu menu = HxMenus.open(player, 6, Component.literal(s.title()), items, SCRIPT, s);
        last = s;
        if (game == Game.SUPERPAIRS) {
            s.setupSuperpairs(server, menu, a.has("pairs") ? a.get("pairs").getAsInt() : 3,
                    a.has("clicks") ? a.get("clicks").getAsInt() : 20,
                    a.has("layout") ? a.getAsJsonArray("layout") : null);
        } else {
            s.startRound(server, menu);
        }
        HxEvents.custom("experiment.opened", player, describe(s));
        return s;
    }

    static JsonElement state(MinecraftServer server, JsonObject a) {
        return last == null ? new JsonObject() : describe(last);
    }

    static JsonObject describe(Session s) {
        JsonObject o = new JsonObject();
        o.addProperty("game", s.game.name());
        o.addProperty("title", s.title());
        o.addProperty("phase", s.phase.name());
        o.addProperty("round", s.round);
        o.addProperty("clicks", s.clicks);
        o.addProperty("wrong", s.wrong);
        o.addProperty("clicksDuringWatch", s.clicksDuringWatch);
        o.addProperty("pairsFound", s.pairsFound);
        o.addProperty("remainingClicks", s.remainingClicks);
        JsonArray seq = new JsonArray();
        s.sequence.forEach(seq::add);
        o.add("sequence", seq);
        JsonArray ticks = new JsonArray();
        s.clickTicks.forEach(ticks::add);
        o.add("clickTicks", ticks);
        o.addProperty("powerupState", s.instantFindsSpent);
        o.addProperty("armed", s.armed);
        o.addProperty("powerupsRevealed", s.powerupsRevealed);
        o.addProperty("firstOpen", s.firstOpen);
        JsonArray claimedArr = new JsonArray();
        s.claimed.forEach(claimedArr::add);
        o.add("claimed", claimedArr);
        JsonObject board = new JsonObject();
        for (int i = 0; i < s.tiles.size(); i++) {
            board.addProperty(String.valueOf(s.tiles.get(i)), s.faces.get(i)[1]);
        }
        o.add("board", board);
        return o;
    }

    private static void over(MinecraftServer server, HxChestMenu menu, ServerPlayer player, Session s, String why) {
        s.phase = Phase.OVER;
        JsonObject e = describe(s);
        e.addProperty("reason", why);
        HxEvents.custom("experiment.over", player, e);
        System.out.println("[hx] experiment " + s.game + " over (" + why + ") after round " + s.round + ", "
                + s.clicks + " click(s)");
        if (s.rewards != null) {
            HxMenus.later(() -> HxMenuSpec.open(server, player, s.rewards));
        } else {
            int id = menu.containerId;
            HxMenus.later(() -> {
                if (player.containerMenu.containerId == id) {
                    player.closeContainer();
                }
            });
        }
    }

    private static void onClick(HxChestMenu menu, ServerPlayer player, int slot, int button, ContainerInput input) {
        Session s = HxMenus.state(menu.containerId, Session.class);
        if (s == null || s.phase == Phase.OVER || slot < 0 || slot >= 54) {
            return;
        }
        MinecraftServer server = player.level().getServer();
        long now = server.getTickCount();
        s.clicks++;
        s.clickTicks.add(now);
        JsonObject e = new JsonObject();
        e.addProperty("game", s.game.name());
        e.addProperty("slot", slot);
        e.addProperty("phase", s.phase.name());
        e.addProperty("round", s.round);
        e.addProperty("input", input.name());
        switch (s.game) {
            case CHRONOMATRON, ULTRASEQUENCER -> {
                if (s.phase == Phase.WATCH) {
                    s.clicksDuringWatch++;
                    e.addProperty("correct", false);
                    HxEvents.custom("experiment.click", player, e);
                    return;     // Hypixel ignores clicks while it plays the pattern
                }
                boolean ok = s.input < s.sequence.size() && s.sequence.get(s.input) == slot;
                e.addProperty("correct", ok);
                e.addProperty("expected", s.input < s.sequence.size() ? s.sequence.get(s.input) : -1);
                HxEvents.custom("experiment.click", player, e);
                if (!ok) {
                    s.wrong++;
                    over(server, menu, player, s, "wrong click on " + slot);
                    return;
                }
                s.input++;
                if (s.input == s.sequence.size()) {
                    HxEvents.custom("experiment.round", player, describe(s));
                    if (s.round >= s.maxRounds) {
                        over(server, menu, player, s, "max rounds");
                    } else {
                        s.startRound(server, menu);
                    }
                }
            }
            case SUPERPAIRS -> {
                int idx = s.tiles.indexOf(slot);
                boolean covered = idx >= 0 && !s.matched[slot] && slot != s.firstOpen && slot != s.secondOpen
                        && s.recoverAt < 0;
                e.addProperty("correct", covered);
                e.addProperty("armed", s.armed);
                e.addProperty("firstOpen", s.firstOpen);
                HxEvents.custom("experiment.click", player, e);
                if (!covered) {
                    return;     // a face-up tile, filler, or a click while a missed pair is still up: nothing happens
                }
                if (s.powerups.containsKey(idx)) {
                    // A powerup: shown, a click spent, the turn untouched (its open tile stays up).
                    String[] pf = s.faces.get(idx);
                    menu.items().setItem(slot, HxMenus.named(server, pf[0], pf[1], pf[2]));
                    s.matched[slot] = true;
                    s.powerupsRevealed++;
                    s.remainingClicks--;
                    int bonus = s.powerups.get(idx);
                    if (bonus == 0) {
                        s.armed++;
                    } else {
                        s.remainingClicks += bonus;
                    }
                    for (int t : s.tiles) {
                        s.cover(server, menu, t);
                    }
                    menu.items().setItem(4, HxMenus.named(server, "minecraft:book", "Remaining Clicks: " + s.remainingClicks));
                    if (s.remainingClicks <= 0) {
                        over(server, menu, player, s, "out of clicks");
                    }
                    return;
                }
                if (s.armed > 0) {
                    // Armed: this tile and its partner are claimed at once (the partner may be the turn's open tile).
                    String[] f1 = s.faces.get(idx);
                    int partner = -1;
                    for (int i = 0; i < s.tiles.size(); i++) {
                        String[] f2 = s.faces.get(i);
                        if (i != idx && !s.matched[s.tiles.get(i)] && f2[0].equals(f1[0]) && f2[1].equals(f1[1])) {
                            partner = s.tiles.get(i);
                            break;
                        }
                    }
                    if (partner >= 0) {
                        menu.items().setItem(slot, HxMenus.named(server, f1[0], f1[1]));
                        menu.items().setItem(partner, HxMenus.named(server, f1[0], f1[1]));
                        s.matched[slot] = true;
                        s.matched[partner] = true;
                        s.pairsFound++;
                        s.armed--;
                        s.instantFindsSpent++;
                        if (partner == s.firstOpen) {
                            s.firstOpen = -1;
                        }
                        s.claim(slot, partner, f1[1], true, now);
                        s.remainingClicks--;
                        menu.items().setItem(4, HxMenus.named(server, "minecraft:book", "Remaining Clicks: " + s.remainingClicks));
                        HxEvents.custom("experiment.pair", player, describe(s));
                        for (int t : s.tiles) {
                            s.cover(server, menu, t);
                        }
                        if (s.pairsFound * 2 == s.regularTiles()) {
                            over(server, menu, player, s, "all pairs");
                        } else if (s.remainingClicks <= 0) {
                            over(server, menu, player, s, "out of clicks");
                        }
                        return;
                    }
                }
                String[] face = s.faces.get(idx);
                menu.items().setItem(slot, HxMenus.named(server, face[0], face[1]));
                s.remainingClicks--;
                menu.items().setItem(4, HxMenus.named(server, "minecraft:book", "Remaining Clicks: " + s.remainingClicks));
                if (s.firstOpen < 0) {
                    s.firstOpen = slot;
                    for (int t : s.tiles) {
                        if (t != slot && !s.matched[t]) {
                            s.cover(server, menu, t);
                        }
                    }
                } else {
                    s.secondOpen = slot;
                    String[] other = s.faces.get(s.tiles.indexOf(s.firstOpen));
                    if (other[0].equals(face[0]) && other[1].equals(face[1])) {
                        s.matched[slot] = true;
                        s.matched[s.firstOpen] = true;
                        s.pairsFound++;
                        s.claim(s.firstOpen, slot, face[1], false, now);
                        s.firstOpen = -1;
                        s.secondOpen = -1;
                        HxEvents.custom("experiment.pair", player, describe(s));
                        for (int t : s.tiles) {
                            s.cover(server, menu, t);
                        }
                    } else {
                        s.recoverAt = now + 10;
                    }
                }
                if (s.pairsFound * 2 == s.regularTiles()) {
                    over(server, menu, player, s, "all pairs");
                } else if (s.remainingClicks <= 0) {
                    over(server, menu, player, s, "out of clicks");
                }
            }
            default -> {
            }
        }
    }
}

package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 1109-ui-soundfx / 1109-ui-soundfx-layout (mod soundfx-1109, killer560 2026-11-09: "add more options of what sounds you
 * can change in sound effects. also put the sound into the same line as the title of the original sound and the toggle").
 * <p>soundfx: every Sound Effects row (22) is left alone while OFF (no replacement counted, the effect's own count
 * unchanged) and replaced while ON on a real SoundManager.play of a vanilla sound it covers (the played id is the chosen
 * preset's, not the game's; the count rises by one, the last effect is the row's). Config is restored.
 * <p>soundfx-layout: in the real drawer every row's name+switch and its sound selector share one line (same y, side by
 * side), no row stacks, and every label and every preset name fits its button.
 */
final class SoundFx1109Cases {

    private SoundFx1109Cases() {
    }

    private static final String CFG = "clicksound.SoundEffectConfig";
    private static final String FX = "clicksound.SoundEffects";
    private static final String CLICKS = "clicksound.ClickSounds";

    /** One vanilla sound id each row covers (the effect's key to the id). */
    private static final Map<String, String> SAMPLE = new LinkedHashMap<>();

    static {
        SAMPLE.put("arrow_hit", "entity.arrow.hit_player");
        SAMPLE.put("attack_hit", "entity.player.attack.strong");
        SAMPLE.put("block_hit", "block.stone.hit");
        SAMPLE.put("mob_death", "entity.zombie.death");
        SAMPLE.put("block_break", "block.stone.break");
        SAMPLE.put("arrow_shoot", "entity.arrow.shoot");
        SAMPLE.put("xp_orb", "entity.experience_orb.pickup");
        SAMPLE.put("item_pickup", "entity.item.pickup");
        SAMPLE.put("level_up", "entity.player.levelup");
        SAMPLE.put("anvil", "block.anvil.use");
        SAMPLE.put("enchant_table", "block.enchantment_table.use");
        SAMPLE.put("chest", "block.chest.open");
        SAMPLE.put("door", "block.wooden_door.open");
        SAMPLE.put("explosion", "entity.generic.explode");
        SAMPLE.put("teleport", "entity.enderman.teleport");
        SAMPLE.put("note_block", "block.note_block.bass");
        SAMPLE.put("villager", "entity.villager.no");
        SAMPLE.put("wither", "entity.wither.shoot");
        SAMPLE.put("eat_drink", "entity.generic.eat");
        SAMPLE.put("splash_potion", "entity.splash_potion.break");
        SAMPLE.put("totem", "item.totem.use");
        SAMPLE.put("fishing", "entity.fishing_bobber.splash");
    }

    private static Object[] effects() {
        return Mod.cls("clicksound.SoundEffect").getEnumConstants();
    }

    private static String key(Object effect) {
        return (String) Mod.field(effect, "key");
    }

    private static void play(UiCase c, String id) {
        c.onClient(mc -> {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(
                    SoundEvent.createVariableRangeEvent(Identifier.withDefaultNamespace(id)), 1.0f));
            return null;
        });
        c.ticks(2);
    }

    @SuppressWarnings("unchecked")
    static void effects(UiCase c) throws Exception {
        Object cfg = c.onClient(mc -> Mod.cfg(CFG));
        Object[] all = effects();
        List<Object> presets = (List<Object>) Mod.staticCall(CLICKS, "presets");
        String pid = (String) Mod.call(presets.get(0), "id");
        boolean[] was = new boolean[all.length];
        String[] choice = new String[all.length];
        for (int i = 0; i < all.length; i++) {
            was[i] = (Boolean) Mod.call(cfg, "isOn", all[i]);
            choice[i] = (String) Mod.call(cfg, "getChoice", all[i]);
        }
        List<String> problems = new ArrayList<>();
        int acted = 0;
        try {
            c.check(all.length >= 20, "only " + all.length + " Sound Effects rows");
            c.check(all.length == SAMPLE.size(), all.length + " effects but " + SAMPLE.size() + " samples: add the new one");
            for (Object e : all) {
                Mod.call(cfg, "setOn", e, false);
                Mod.call(cfg, "setChoice", e, pid);
            }
            for (Object e : all) {
                String k = key(e);
                String id = SAMPLE.get(k);
                if (id == null) {
                    problems.add(k + ": no sample sound");
                    continue;
                }
                int total0 = (Integer) Mod.staticCall(FX, "replaced");
                int own0 = (Integer) Mod.staticCall(FX, "replaced", k);
                play(c, id); // OFF: untouched
                if ((Integer) Mod.staticCall(FX, "replaced", k) != own0 || (Integer) Mod.staticCall(FX, "replaced") != total0) {
                    problems.add(k + ": replaced while OFF (" + id + ")");
                }
                Mod.call(cfg, "setOn", e, true);
                play(c, id); // ON: replaced by the chosen preset
                int own1 = (Integer) Mod.staticCall(FX, "replaced", k);
                String last = (String) Mod.staticCall(FX, "lastPlayedId");
                if (own1 != own0 + 1) {
                    problems.add(k + ": ON did not replace " + id + " (count " + own0 + " -> " + own1 + ")");
                } else if (!k.equals(Mod.staticCall(FX, "lastEffect")) || !last.contains(pid) || last.endsWith(id)) {
                    problems.add(k + ": played " + last + " for " + id + ", not preset " + pid);
                } else {
                    acted++;
                }
                Mod.call(cfg, "setOn", e, false);
                c.ticks(2);
            }
        } finally {
            for (int i = 0; i < all.length; i++) {
                Mod.call(cfg, "setOn", all[i], was[i]);
                Mod.call(cfg, "setChoice", all[i], choice[i]);
            }
        }
        c.note("replaced " + acted + "/" + all.length + " rows with preset " + pid);
        c.check(problems.isEmpty(), String.join("; ", problems));
        c.check(acted == all.length, "only " + acted + " of " + all.length + " rows acted");
    }

    @SuppressWarnings("unchecked")
    static void layout(UiCase c) throws Exception {
        List<String> problems = new ArrayList<>();
        int[] rows = {0};
        c.onClient(mc -> {
            Screen s;
            try {
                s = MenuACases.open(mc);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            Mod.call(s, "openFeature", MenuACases.leaf("SoundEffectsTab"));
            return null;
        });
        c.ticks(5);
        c.onClient(mc -> {
            Screen s = McCompat.screen(mc);
            List<AbstractWidget> pane = MenuACases.paneRows(s);
            c.note("drawer pane: " + pane.size() + " widgets");
            var font = mc.font;
            List<Object> presets = (List<Object>) Mod.staticCall(CLICKS, "presets");
            int longest = 0;
            for (Object p : presets) {
                longest = Math.max(longest, font.width((String) Mod.call(p, "label")));
            }
            for (Object e : effects()) {
                String label = (String) Mod.field(e, "label");
                AbstractWidget sw = null;
                for (AbstractWidget w : pane) {
                    if (w.getClass().getSimpleName().equals("SettingsButtonWidget") && (Boolean) Mod.call(w, "isSwitch")
                            && label.equals(Mod.call(Mod.call(w, "parts"), "label"))) {
                        sw = w;
                    }
                }
                if (sw == null) {
                    problems.add(label + ": no switch row");
                    continue;
                }
                AbstractWidget sel = null;
                for (AbstractWidget w : pane) {
                    if (w != sw && w.getY() == sw.getY() && w.getClass().getSimpleName().equals("SettingsButtonWidget")
                            && (Boolean) Mod.call(w, "isCycling")) {
                        sel = w;
                    }
                }
                if (sel == null) {
                    problems.add(label + ": its sound selector is not on the switch's line (y " + sw.getY() + ")");
                    continue;
                }
                rows[0]++;
                if (sel.getX() < sw.getX() + sw.getWidth()) {
                    problems.add(label + ": selector overlaps the switch");
                }
                int fit = font.width(label) + 36; // the name plus the ON/OFF pill
                if (fit > sw.getWidth()) {
                    problems.add(label + ": name needs " + fit + " px, switch is " + sw.getWidth());
                }
                if (longest + 28 > sel.getWidth()) {
                    problems.add(label + ": longest sound name needs " + (longest + 28) + " px, selector is " + sel.getWidth());
                }
            }
            McCompat.setScreen(mc, null);
            return null;
        });
        c.note(rows[0] + " rows with the name, switch and sound selector on one line");
        c.check(rows[0] == effects().length, "only " + rows[0] + " rows share a line: " + String.join("; ", problems));
        c.check(problems.isEmpty(), String.join("; ", problems));
    }
}

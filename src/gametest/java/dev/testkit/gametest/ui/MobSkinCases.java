package dev.testkit.gametest.ui;

import com.mojang.authlib.GameProfile;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Play as Mob (mod todo-mobskin-1018; killer560's to-do item 2026-10-18: "Make an option to change your character to any
 * ingame mob. Still have scale sliders apply. If you are a supporter it can be shared."). Player Cosmetics gained My Mob
 * (left click forward, right click back) and Show Others' Mobs; the relay's supporter entry gained an optional {@code mob}.
 * <ul>
 *   <li>1018-mob-own: with My Mob = Pig your own render state (what {@code EntityRenderDispatcher.extractEntity} hands the
 *       level renderer) is a pig's, at your position and yaw; real F5 frames swap it (the mod's own counter names YOU); on
 *       screen the front F5 view shows a pig (pink pixels) where the player view shows none; Player puts you back.</li>
 *   <li>1018-mob-scale: My Size and Width reach the mob - the state's scale is My Size, the stretch is carried, and on
 *       screen a 0.5x pig covers about a quarter of a 1x pig's pixels and a 2x-wide pig about twice.</li>
 *   <li>1018-mob-share: the share push carries {@code "mob":"pig"} only when linked with Share if Supporter on, alone with
 *       no display name; the relay's entry (the mod's own parser) gives another player a mob, a boss or junk value none;
 *       Show Others' Mobs OFF and being in a dungeon both draw him as a player; the cache keeps the mob.</li>
 *   <li>1018-mob-settings: defaults, save + load, profile reload, the tab's rows and cycling both ways, tooltips.</li>
 *   <li>1018-mob-all: every mob the tab offers draws in real frames without being marked broken.</li>
 * </ul>
 */
final class MobSkinCases {

    private static final String SC = "supporters.SupportersConfig";
    private static final String SF = "supporters.SupportersFeature";
    private static final String SA = "supporters.SupportersAutoShare";
    private static final String SS = "supporters.SupportersSelfService";
    private static final String NC = "namechanger.NameChangerConfig";
    private static final String MR = "mobskin.MobSkinRender";
    private static final String MS = "mobskin.MobSkins";
    private static final String DS = "secrets.DungeonState";

    private static final UUID MATE = UUID.fromString("5a5b5c5d-1111-4222-8333-444455557777");
    private static final int MATE_ENTITY = 918_001;

    private MobSkinCases() {
    }

    private static boolean ready(UiCase c) {
        if (!Mod.has(MR) || !Mod.has(MS)) {
            c.problem("this jar has no Play as Mob (mod before todo-mobskin-1018)");
            return false;
        }
        return true;
    }

    private static String typeId(EntityRenderState s) {
        return s == null || s.entityType == null ? "null" : EntityType.getKey(s.entityType).getPath();
    }

    /** Your render state as the level renderer would get it this frame. */
    private static EntityRenderState ownState(UiCase c) {
        return c.onClient(mc -> mc.getEntityRenderDispatcher().extractEntity(mc.player, 1f));
    }

    private static void setMob(UiCase c, String id) {
        c.onClient(mc -> {
            Mod.set(SC, "setOwnMob", id);
            return null;
        });
    }

    // ---- the F5 scene -------------------------------------------------------------------------------------------

    private static Object[] saved;

    private static void scene(UiCase c) {
        c.onClient(mc -> {
            if (saved == null) {
                saved = new Object[]{Mod.get(SC, "getOwnMob"), Mod.get(SC, "getOwnScale"), Mod.get(SC, "getModelWidth"),
                        Mod.get(SC, "getModelHeight"), Mod.get(SC, "getModelThickness"), Mod.get(SC, "isShowOthersMobs"),
                        mc.player.getYRot(), mc.player.getXRot()};
            }
            McCompat.setScreen(mc, null);
            McCompat.clearChatAndToasts(mc);
            McCompat.setHudHidden(mc, true);
            mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
            mc.player.setYRot(0f);
            mc.player.setXRot(0f);
            Mod.set(SC, "setOwnScale", 1.0f);
            Mod.set(SC, "setModelWidth", 1.0f);
            Mod.set(SC, "setModelHeight", 1.0f);
            Mod.set(SC, "setModelThickness", 1.0f);
            return null;
        });
        c.ticks(5);
    }

    private static void restore(UiCase c) {
        c.onClient(mc -> {
            if (saved != null) {
                Mod.set(SC, "setOwnMob", saved[0]);
                Mod.set(SC, "setOwnScale", saved[1]);
                Mod.set(SC, "setModelWidth", saved[2]);
                Mod.set(SC, "setModelHeight", saved[3]);
                Mod.set(SC, "setModelThickness", saved[4]);
                Mod.set(SC, "setShowOthersMobs", saved[5]);
                mc.player.setYRot((Float) saved[6]);
                mc.player.setXRot((Float) saved[7]);
                saved = null;
            }
            Mod.call(Mod.cfg(SC), "save");
            if (mc.level != null && mc.level.getEntity(MATE_ENTITY) != null) {
                mc.level.removeEntity(MATE_ENTITY, Entity.RemovalReason.DISCARDED);
            }
            McCompat.setHudHidden(mc, false);
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            return null;
        });
        c.ticks(2);
    }

    private static BufferedImage shot(UiCase c, String label) {
        c.ticks(4);
        String name = c.name() + "-" + label;
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        Path kept = dev.testkit.harness.Report.screenshot(name, taken);
        c.note(label + " -> " + (kept != null ? kept : taken));
        try {
            return javax.imageio.ImageIO.read(taken.toFile());
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read " + taken, e);
        }
    }

    /** Pig-pink pixels as the shaded pig actually draws in the test world (measured on the first run's frames: mostly
     *  (120, 60-80, 80-100) up to (160, 110, 140)): red well above green, blue at least green but not above red. A
     *  skin tone has blue below green, purple hair blue above red, grass and sky fail the red test. */
    private static int pink(BufferedImage img) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int p = img.getRGB(x, y);
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                if (r >= 100 && r - g >= 30 && b >= g && r >= b) {
                    n++;
                }
            }
        }
        return n;
    }

    // ---- 1018-mob-own ---------------------------------------------------------------------------------------------

    static void own(UiCase c) {
        if (!ready(c)) {
            return;
        }
        try {
            scene(c);
            setMob(c, "");
            EntityRenderState asPlayer = ownState(c);
            BufferedImage playerShot = shot(c, "player");
            setMob(c, "pig");
            EntityRenderState asPig = ownState(c);
            double[] where = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
            c.note("player state " + typeId(asPlayer) + " " + asPlayer.getClass().getSimpleName() + "; with My Mob = pig: "
                    + typeId(asPig) + " " + asPig.getClass().getSimpleName() + String.format(Locale.ROOT,
                    " at %.2f %.2f %.2f (you %.2f %.2f %.2f)", asPig.x, asPig.y, asPig.z, where[0], where[1], where[2]));
            c.check("player".equals(typeId(asPlayer)), "with no mob your state is not a player's: " + typeId(asPlayer));
            c.check("pig".equals(typeId(asPig)), "with My Mob = Pig your render state is not a pig's: " + typeId(asPig));
            c.check(Math.abs(asPig.x - where[0]) < 0.01 && Math.abs(asPig.y - where[1]) < 0.01 && Math.abs(asPig.z - where[2]) < 0.01,
                    "the pig is not drawn where you stand");
            if (asPig instanceof LivingEntityRenderState lp && asPlayer instanceof LivingEntityRenderState lq) {
                c.note(String.format(Locale.ROOT, "body yaw pig %.1f player %.1f; scale pig %.2f player %.2f", lp.bodyRot,
                        lq.bodyRot, lp.scale, lq.scale));
                c.check(Math.abs(lp.bodyRot - lq.bodyRot) < 0.01, "the pig does not face the way you do");
            }
            // Real frames: the level renderer's own extraction swaps you (the counter moves and names you).
            long before = c.onClient(mc -> (Long) Mod.staticCall(MR, "swaps"));
            BufferedImage pigShot = shot(c, "pig");
            long swapped = c.onClient(mc -> (Long) Mod.staticCall(MR, "swaps")) - before;
            String last = c.onClient(mc -> String.valueOf(Mod.staticCall(MR, "lastSwap")));
            String me = c.onClient(mc -> mc.player.getUUID().toString());
            c.note("F5 frames swapped " + swapped + " time(s); last " + last);
            c.check(swapped >= 3, "real F5 frames did not swap your state (" + swapped + ")");
            c.check(last.equals(me + " pig"), "the last swap is not you as a pig: " + last);
            int pinkPlayer = pink(playerShot), pinkPig = pink(pigShot);
            c.note("pig-pink pixels: player " + pinkPlayer + ", pig " + pinkPig);
            c.check(pinkPig > 400 && pinkPig > 5 * Math.max(1, pinkPlayer), "no pig on screen in F5 (pink " + pinkPlayer
                    + " -> " + pinkPig + ")");
            // Player again.
            setMob(c, "");
            EntityRenderState back = ownState(c);
            int pinkBack = pink(shot(c, "player-again"));
            c.note("back to Player: state " + typeId(back) + ", pink " + pinkBack);
            c.check("player".equals(typeId(back)) && pinkBack * 5 < pinkPig, "Player did not put you back");
            // The real entity never changed: still a player, same hitbox.
            String real = c.onClient(mc -> EntityType.getKey(mc.player.getType()).getPath() + " "
                    + String.format(Locale.ROOT, "%.2fx%.2f", mc.player.getBbWidth(), mc.player.getBbHeight()));
            c.note("your entity: " + real);
            c.check(real.equals("player 0.60x1.80"), "your real entity or hitbox changed: " + real);
        } finally {
            restore(c);
        }
    }

    // ---- 1018-mob-scale -------------------------------------------------------------------------------------------

    static void scale(UiCase c) {
        if (!ready(c)) {
            return;
        }
        try {
            scene(c);
            setMob(c, "pig");
            int one = pink(shot(c, "pig-1x"));
            c.onClient(mc -> {
                Mod.set(SC, "setOwnScale", 0.5f);
                return null;
            });
            EntityRenderState half = ownState(c);
            int small = pink(shot(c, "pig-0.5x"));
            c.onClient(mc -> {
                Mod.set(SC, "setOwnScale", 1.0f);
                Mod.set(SC, "setModelWidth", 2.0f);
                return null;
            });
            EntityRenderState wide = ownState(c);
            Object shape = c.onClient(mc -> Mod.call(wide, "killer560smod$mobShape"));
            int wideN = pink(shot(c, "pig-2x-wide"));
            float halfScale = ((LivingEntityRenderState) half).scale;
            String shapeText = shape instanceof float[] f ? java.util.Arrays.toString(f) : String.valueOf(shape);
            c.note(String.format(Locale.ROOT, "pink: 1x %d, 0.5x %d (%.2f), 2x wide %d (%.2f); 0.5x state scale %.2f %s; "
                    + "wide stretch %s", one, small, small / (double) one, wideN, wideN / (double) one, halfScale,
                    typeId(half), shapeText));
            c.check(one > 400, "no pig at 1x to measure against (" + one + ")");
            c.check("pig".equals(typeId(half)) && Math.abs(halfScale - 0.5f) < 0.01, "My Size 0.5 did not reach the pig's state");
            c.check(small > one * 0.1 && small < one * 0.5, "a 0.5x pig is not about a quarter of a 1x pig on screen");
            c.check("[2.0, 1.0, 1.0]".equals(shapeText), "Width 2 is not carried on your pig's state: " + shapeText);
            c.check(wideN > one * 1.4 && wideN < one * 2.8, "a 2x-wide pig is not about twice a 1x pig on screen");
        } finally {
            restore(c);
        }
    }

    // ---- 1018-mob-share -------------------------------------------------------------------------------------------

    private static void unthrottle() {
        Mod.setField(SA, "lastPushedKey", null);
        Mod.setField(SA, "lastPushAtMs", -1L);
    }

    private static final Consumer<String> NO_ERROR = e -> {
    };

    private static String push(UiCase c) {
        return c.onClient(mc -> {
            unthrottle();
            int before = (Integer) Mod.staticCall(SS, "attempts");
            Mod.staticCall(SA, "shareCurrent", NO_ERROR);
            int after = (Integer) Mod.staticCall(SS, "attempts");
            return after == before ? "(none)" : String.valueOf(Mod.staticCall(SS, "lastAttempt"));
        });
    }

    private static String entry(UUID id, String name, String mobJson) {
        return "{\"uuid\":\"" + id + "\",\"name\":\"" + name + "\",\"scale\":1" + (mobJson == null ? "" : ",\"mob\":" + mobJson) + "}";
    }

    private static List<?> applyEntries(UiCase c, String... entries) {
        String body = "{\"version\":78,\"supporters\":[" + String.join(",", entries) + "]}";
        return c.onClient(mc -> {
            List<?> list = (List<?>) Mod.staticCall("supporters.SupportersFetcher", "parseSupporters",
                    com.google.gson.JsonParser.parseString(body).getAsJsonObject().get("supporters"));
            Mod.staticCall(SF, "apply", 78, list, false);
            return list;
        });
    }

    private static String mateState(UiCase c) {
        return c.onClient(mc -> {
            Entity e = mc.level.getEntity(MATE_ENTITY);
            return typeId(mc.getEntityRenderDispatcher().extractEntity(e, 1f));
        });
    }

    static void share(UiCase c) {
        if (!ready(c)) {
            return;
        }
        Object[] keep = c.onClient(mc -> new Object[]{Mod.get(NC, "getOwnDisplayName"), Mod.get(NC, "getOwnColor"),
                Mod.get(SC, "isShareIfSupporter"), Mod.get(SC, "isShareTrail"), Mod.field(SF, "byUuid")});
        try {
            scene(c);
            c.onClient(mc -> {
                Mod.set(NC, "setOwnDisplayName", "MobGuy");
                Mod.set(NC, "setOwnColor", 0);
                Mod.set(SC, "setShareIfSupporter", true);
                Mod.set(SC, "setShareTrail", false);
                Mod.set(SC, "setOwnMob", "pig");
                Mod.staticCall(SA, "setLinked", false);
                return null;
            });
            String notLinked = push(c);
            c.note("not a supporter: " + notLinked);
            c.check("(none)".equals(notLinked), "a non-supporter attempted a share: " + notLinked);
            c.onClient(mc -> {
                Mod.staticCall(SA, "setLinked", true);
                return null;
            });
            String on = push(c);
            c.note("supporter, My Mob = Pig: " + on);
            c.check(on.startsWith("POST ") && on.contains("\"name\":\"MobGuy\"") && on.endsWith(",\"mob\":\"pig\"}"),
                    "the push does not carry the mob last: " + on);
            c.onClient(mc -> {
                Mod.set(NC, "setOwnDisplayName", "");
                return null;
            });
            String mobOnly = push(c);
            c.note("no display name, My Mob = Pig: " + mobOnly);
            c.check(mobOnly.startsWith("POST {\"name\":\"\"") && mobOnly.endsWith(",\"mob\":\"pig\"}"),
                    "with no display name the mob does not go up alone: " + mobOnly);
            c.onClient(mc -> {
                Mod.set(SC, "setOwnMob", "");
                return null;
            });
            String nothing = push(c);
            c.note("no name, no trail, no mob: " + nothing);
            c.check("DELETE".equals(nothing), "with nothing to share the entry is not cleared: " + nothing);
            c.onClient(mc -> {
                Mod.set(SC, "setOwnMob", "pig");
                Mod.set(SC, "setShareIfSupporter", false);
                return null;
            });
            String off = push(c);
            c.note("Share if Supporter off: " + off);
            c.check("(none)".equals(off), "Share if Supporter OFF still pushed: " + off);

            // Receiving: the relay's entry, through the mod's own parser, on a fake player standing in front of you.
            c.onClient(mc -> {
                RemotePlayer p = new RemotePlayer(mc.level, new GameProfile(MATE, "MobMate"));
                p.setId(MATE_ENTITY);
                p.setPos(mc.player.getX() + 2, mc.player.getY(), mc.player.getZ() + 3);
                mc.level.addEntity(p);
                Mod.set(SC, "setShowOthersMobs", true);
                return null;
            });
            List<?> parsed = applyEntries(c, entry(MATE, "", "\"zombie\""));
            String parsedMob = c.onClient(mc -> String.valueOf(Mod.call(parsed.get(0), "mob")));
            String shown = mateState(c);
            c.note("his entry: mob " + parsedMob + "; drawn as " + shown);
            c.check("zombie".equals(parsedMob), "the parser did not read the shared mob: " + parsedMob);
            c.check("zombie".equals(shown), "a supporter sharing a zombie is not drawn as one: " + shown);
            c.onClient(mc -> {
                Mod.set(SC, "setShowOthersMobs", false);
                return null;
            });
            String hidden = mateState(c);
            // In a dungeon (the sim's flag, set and cleared inside one client task so no tick runs with it).
            String[] dungeon = c.onClient(mc -> {
                Mod.set(SC, "setShowOthersMobs", true);
                Mod.staticCall(DS, "setRoomSim", true);
                try {
                    boolean flagged = (Boolean) Mod.staticCall(DS, "isInDungeon");
                    Entity e = mc.level.getEntity(MATE_ENTITY);
                    return new String[]{typeId(mc.getEntityRenderDispatcher().extractEntity(e, 1f)),
                            typeId(mc.getEntityRenderDispatcher().extractEntity(mc.player, 1f)), String.valueOf(flagged)};
                } finally {
                    Mod.staticCall(DS, "setRoomSim", false);
                }
            });
            String inDungeon = dungeon[0];
            String ownInDungeon = dungeon[1];
            c.check("true".equals(dungeon[2]), "the dungeon flag did not take");
            String after = mateState(c);
            c.note("Show Others' Mobs OFF: " + hidden + "; in a dungeon: " + inDungeon + " (you: " + ownInDungeon
                    + "); out again: " + after);
            c.check("player".equals(hidden), "Show Others' Mobs OFF still draws his mob: " + hidden);
            c.check("player".equals(inDungeon), "inside a dungeon his mob still shows: " + inDungeon);
            c.check("pig".equals(ownInDungeon), "inside a dungeon your own mob stopped showing: " + ownInDungeon);
            c.check("zombie".equals(after), "his mob did not come back after the dungeon: " + after);

            // Junk and bosses are dropped by the parser; the cache keeps a good mob.
            List<?> bad = applyEntries(c, entry(MATE, "", "\"wither\""), entry(new UUID(1, 2), "A", "\"ender_dragon\""),
                    entry(new UUID(1, 3), "B", "\"minecraft:zombie\""), entry(new UUID(1, 4), "C", "7"),
                    entry(new UUID(1, 5), "D", "\"Zombie\""), entry(new UUID(1, 6), "E", "{\"id\":\"pig\"}"));
            List<String> badMobs = c.onClient(mc -> {
                List<String> out = new ArrayList<>();
                for (Object e : bad) {
                    out.add(String.valueOf(Mod.call(e, "mob")));
                }
                return out;
            });
            String wither = mateState(c);
            c.note("bad mobs parsed as " + badMobs + "; a shared wither draws " + wither);
            c.check(badMobs.size() == 6 && badMobs.stream().allMatch("null"::equals), "a bad mob was not dropped: " + badMobs);
            c.check("player".equals(wither), "a shared wither is drawn: " + wither);
            List<?> good = applyEntries(c, entry(MATE, "&aKept", "\"slime\""));
            String cached = c.onClient(mc -> {
                Object loaded = Mod.staticCall("supporters.SupportersCache", "load");
                List<?> was = (List<?>) Mod.call(loaded, "entries");
                Mod.staticCall("supporters.SupportersCache", "save", 78, good);
                String got = "missing";
                for (Object e : (List<?>) Mod.call(Mod.staticCall("supporters.SupportersCache", "load"), "entries")) {
                    if (MATE.equals(Mod.call(e, "uuid"))) {
                        got = String.valueOf(Mod.call(e, "mob"));
                    }
                }
                Mod.staticCall("supporters.SupportersCache", "save", (Integer) Mod.call(loaded, "version"), was);
                return got;
            });
            c.note("cache round trip: " + cached);
            c.check("slime".equals(cached), "the supporters cache lost the mob: " + cached);
        } finally {
            c.onClient(mc -> {
                Mod.set(NC, "setOwnDisplayName", keep[0]);
                Mod.set(NC, "setOwnColor", keep[1]);
                Mod.set(SC, "setShareIfSupporter", keep[2]);
                Mod.set(SC, "setShareTrail", keep[3]);
                Mod.setField(SF, "byUuid", keep[4]);
                Mod.staticCall(SA, "setLinked", false);
                unthrottle();
                return null;
            });
            restore(c);
        }
    }

    // ---- 1018-mob-settings ----------------------------------------------------------------------------------------

    private static List<String> rows(UiCase c, Object tab) {
        return c.onClient(mc -> {
            @SuppressWarnings("unchecked")
            List<AbstractWidget> ws = (List<AbstractWidget>) Mod.call(tab, "buildWidgets", 0, 0, 320, (Runnable) () -> {
            });
            List<String> out = new ArrayList<>();
            for (AbstractWidget w : ws) {
                out.add(net.minecraft.ChatFormatting.stripFormatting(w.getMessage().getString()));
            }
            return out;
        });
    }

    private static AbstractWidget row(UiCase c, Object tab, String prefix) {
        return c.onClient(mc -> {
            @SuppressWarnings("unchecked")
            List<AbstractWidget> ws = (List<AbstractWidget>) Mod.call(tab, "buildWidgets", 0, 0, 320, (Runnable) () -> {
            });
            for (AbstractWidget w : ws) {
                if (net.minecraft.ChatFormatting.stripFormatting(w.getMessage().getString()).startsWith(prefix)) {
                    return w;
                }
            }
            return null;
        });
    }

    /** Runs a SettingsButtonWidget's left ({@code onPress}) or right ({@code onSecondaryPress}) click handler. */
    private static void press(AbstractWidget w, String which) {
        try {
            Object handler = Mod.field(w, which);
            Class<?> sbw = Mod.cls("gui.SettingsButtonWidget");
            Mod.cls("gui.SettingsButtonWidget$OnPress").getMethod("onPress", sbw).invoke(handler, w);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not press " + which + ": " + e, e);
        }
    }

    static void settings(UiCase c) {
        if (!ready(c)) {
            return;
        }
        Object[] keep = c.onClient(mc -> new Object[]{Mod.get(SC, "getOwnMob"), Mod.get(SC, "isShowOthersMobs")});
        try {
            Object[] round = c.onClient(mc -> {
                Object fresh;
                try {
                    var k = Mod.cls(SC).getDeclaredConstructor();
                    k.setAccessible(true);
                    fresh = k.newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
                Object defMob = Mod.call(fresh, "getOwnMob");
                Object defShow = Mod.call(fresh, "isShowOthersMobs");
                Mod.set(SC, "setOwnMob", "zombified_piglin");
                Mod.set(SC, "setShowOthersMobs", false);
                Mod.call(Mod.cfg(SC), "save");
                Mod.staticCall(SC, "load");
                Object a = Mod.get(SC, "getOwnMob");
                Object b = Mod.get(SC, "isShowOthersMobs");
                Mod.set(SC, "setOwnMob", "cow"); // memory only
                Mod.set(SC, "setShowOthersMobs", true);
                Mod.staticCall("profiles.ProfileManager", "reloadAllConfigs");
                Object pa = Mod.get(SC, "getOwnMob");
                Object pb = Mod.get(SC, "isShowOthersMobs");
                Mod.set(SC, "setOwnMob", "wither");
                Object boss = Mod.get(SC, "getOwnMob");
                return new Object[]{defMob, defShow, a, b, pa, pb, boss};
            });
            c.note("defaults: mob '" + round[0] + "', show " + round[1] + "; after save+load: " + round[2] + ", " + round[3]
                    + "; after profile reload: " + round[4] + ", " + round[5] + "; setting a wither gives '" + round[6] + "'");
            c.check("".equals(round[0]) && Boolean.TRUE.equals(round[1]), "the defaults are not Player and Show Others' Mobs ON");
            c.check("zombified_piglin".equals(round[2]) && Boolean.FALSE.equals(round[3]), "save + load lost the settings");
            c.check("zombified_piglin".equals(round[4]) && Boolean.FALSE.equals(round[5]), "a profile reload did not restore them");
            c.check("".equals(round[6]), "a boss was accepted as your mob: " + round[6]);

            List<?> offered = c.onClient(mc -> (List<?>) Mod.staticCall(MS, "available"));
            c.note(offered.size() + " mobs offered: " + offered);
            c.check(offered.size() >= 70 && offered.contains("pig") && offered.contains("zombie"),
                    "too few mobs offered (" + offered.size() + ")");
            for (String boss : new String[]{"ender_dragon", "wither", "giant", "elder_guardian", "warden"}) {
                c.check(!offered.contains(boss), boss + " is offered");
            }

            c.onClient(mc -> {
                Mod.set(SC, "setOwnMob", "");
                Mod.set(SC, "setShowOthersMobs", true);
                return null;
            });
            Object tab = c.onClient(mc -> {
                try {
                    return Mod.cls("gui.tab.CosmeticsTab").getDeclaredConstructor().newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            c.onClient(mc -> {
                try {
                    var f = Mod.cls("gui.tab.CosmeticsTab").getDeclaredField("playAsMobOpen");
                    f.setAccessible(true);
                    f.set(tab, true);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
                return null;
            });
            List<String> rs = rows(c, tab);
            c.note("rows: " + rs);
            c.check(rs.contains("▼ Play as Mob") || rs.stream().anyMatch(r -> r.contains("Play as Mob")), "no Play as Mob section");
            c.check(rs.contains("My Mob: Player") && rs.contains("Show Others' Mobs: ON"), "the Play as Mob rows are missing: " + rs);
            AbstractWidget my = row(c, tab, "My Mob:");
            c.check(my != null && Boolean.TRUE.equals(c.onClient(mc -> Mod.call(my, "isCycling"))),
                    "My Mob is not a cycling button");
            String first = c.onClient(mc -> {
                press(my, "onPress");
                return String.valueOf(Mod.get(SC, "getOwnMob"));
            });
            // Right click back twice from the first mob: Player, then the last mob (it wraps).
            String[] backs = c.onClient(mc -> {
                press(my, "onSecondaryPress");
                String a = String.valueOf(Mod.get(SC, "getOwnMob"));
                press(my, "onSecondaryPress");
                String b = String.valueOf(Mod.get(SC, "getOwnMob"));
                return new String[]{a, b};
            });
            String label = c.onClient(mc -> net.minecraft.ChatFormatting.stripFormatting(my.getMessage().getString()));
            c.note("left click from Player: '" + first + "'; right click: '" + backs[0] + "', again: '" + backs[1]
                    + "' (label " + label + ")");
            c.check(first.equals(offered.get(0)), "left click from Player is not the first mob: " + first);
            c.check("".equals(backs[0]) && backs[1].equals(offered.get(offered.size() - 1)),
                    "right click does not step back and wrap: " + backs[0] + ", " + backs[1]);
            c.check(label.startsWith("My Mob: ") && !label.equals("My Mob: Player"), "the button does not name the mob: " + label);
            for (String l : new String[]{"Play as Mob", "My Mob: Player", "Show Others' Mobs: ON"}) {
                String tip = c.onClient(mc -> (String) Mod.staticCall("gui.SettingTooltips", "describe", "Player Cosmetics", l));
                c.note("tooltip '" + l + "': " + tip);
                c.check(tip != null && tip.toLowerCase(Locale.ROOT).contains("mob"), "no tooltip for '" + l + "'");
            }
        } finally {
            c.onClient(mc -> {
                Mod.set(SC, "setOwnMob", keep[0]);
                Mod.set(SC, "setShowOthersMobs", keep[1]);
                Mod.call(Mod.cfg(SC), "save");
                return null;
            });
        }
    }

    // ---- 1018-mob-all ---------------------------------------------------------------------------------------------

    static void all(UiCase c) {
        if (!ready(c)) {
            return;
        }
        try {
            scene(c);
            List<?> offered = c.onClient(mc -> (List<?>) Mod.staticCall(MS, "available"));
            List<String> wrong = new ArrayList<>();
            long before = c.onClient(mc -> (Long) Mod.staticCall(MR, "swaps"));
            for (Object id : offered) {
                setMob(c, (String) id);
                String got = typeId(ownState(c));
                if (!got.equals(id)) {
                    wrong.add(id + "->" + got);
                }
                c.ticks(2); // two real F5 frames with this mob
            }
            long swapped = c.onClient(mc -> (Long) Mod.staticCall(MR, "swaps")) - before;
            Object broken = c.onClient(mc -> Mod.staticCall(MR, "broken"));
            c.note(offered.size() + " mobs; real-frame swaps " + swapped + "; wrong " + wrong + "; broken " + broken);
            c.check(wrong.isEmpty(), "some mobs did not draw as themselves: " + wrong);
            c.check(String.valueOf(broken).equals("[]"), "some mobs were marked broken: " + broken);
            c.check(swapped >= offered.size() * 2L, "real frames swapped only " + swapped + " times for " + offered.size() + " mobs");
        } finally {
            restore(c);
        }
    }
}

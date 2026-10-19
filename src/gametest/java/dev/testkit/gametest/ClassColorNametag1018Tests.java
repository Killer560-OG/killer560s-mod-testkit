package dev.testkit.gametest;

import com.mojang.authlib.GameProfile;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * killer560s-mod Class Colors (killer560, 2026-10-18: "for class colors ... they still keep their old nametag and it
 * looks weird. just remove their normal nametag"). Class Colors draws its own class-coloured tag over a teammate, so
 * the vanilla one must not be drawn as well. The vanilla decision is {@code AvatarRenderer.shouldShowName(Avatar,
 * double)}; the case calls the real renderer's method (the mixin sits inside it) for a real v4-UUID RemotePlayer that
 * the tab list has given a class.
 * <ul>
 *   <li>{@code 1018-classcolor-nametag-on} - Class Colors and its Nametags setting on: vanilla tag not shown.</li>
 *   <li>{@code 1018-classcolor-nametag-off} - the same teammate with Nametags off, then with Class Colors off: the
 *       vanilla tag is shown (it is also the control: the instrument really does see a tag when nothing hides it).</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class ClassColorNametag1018Tests implements FabricClientGameTest {

    private static final String MOD_ID = "killer560smod";
    private static final String CFG = "com.killer560.hub.dungeonalerts.DungeonAlertsConfig";
    private static final String CC = "com.killer560.hub.dungeonalerts.ClassColors";
    private static final String DUNGEON_STATE = "com.killer560.hub.secrets.DungeonState";
    private static final String CLASS_ENUM = "com.killer560.hub.dungeonclass.DungeonClass";
    private static final String MATE = "CcNameMate";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        run(ctx, "1018-classcolor-nametag-on", true);
        run(ctx, "1018-classcolor-nametag-off", false);
    }

    private void run(ClientGameTestContext ctx, String name, boolean hiddenCase) {
        if (Scenario.skip(name)) {
            return;
        }
        ModUnderTest.require(MOD_ID);
        Scenario.run(ctx, name,
                (server, scenario) -> TestMap.on(server)
                        .platform(300, 150, 300, 8)
                        .catchFloor(120)
                        .spawn(300.5, 300.5, 0f)
                        .build(),
                (server, scenario) -> {
                    scenario.assertDetectorWorks();
                    Object cfg = ModUnderTest.config(CFG);
                    boolean[] old = {field(cfg, "classColorsEnabled"), field(cfg, "classColorsNametags")};
                    int[] id = {-1};
                    try {
                        ctx.computeOnClient(mc -> {
                            ModUnderTest.staticCall(DUNGEON_STATE, "setRoomSim", new Class<?>[]{boolean.class},
                                    new Object[]{true});
                            classes().put(MATE, ModUnderTest.enumValue(CLASS_ENUM, "MAGE"));
                            RemotePlayer p = new RemotePlayer(mc.level, new GameProfile(
                                    UUID.fromString("1a2b3c4d-1111-4222-9333-444455556666"), MATE));
                            p.setPos(mc.player.getX() + 2, mc.player.getY(), mc.player.getZ());
                            mc.level.addEntity(p);
                            id[0] = p.getId();
                            return null;
                        });
                        ctx.waitTicks(5);
                        // Control: with Class Colors off nothing hides the tag, so the instrument must see it.
                        set(ctx, cfg, false, true);
                        boolean control = shown(ctx, id[0]);
                        scenario.log("Class Colors off: vanilla tag shown = " + control);
                        if (!control) {
                            throw new AssertionError("with Class Colors off the vanilla tag is not shown either -"
                                    + " shouldShowName reads false for this teammate anyway, so nothing is measured");
                        }
                        if (hiddenCase) {
                            set(ctx, cfg, true, true);
                            boolean on = shown(ctx, id[0]);
                            scenario.log("Class Colors + Nametags on: vanilla tag shown = " + on);
                            if (on) {
                                throw new AssertionError("Class Colors draws its own nametag but the vanilla one is"
                                        + " still shown for the teammate");
                            }
                        } else {
                            set(ctx, cfg, true, false);
                            boolean nametagsOff = shown(ctx, id[0]);
                            scenario.log("Class Colors on, Nametags off: vanilla tag shown = " + nametagsOff);
                            if (!nametagsOff) {
                                throw new AssertionError("Nametags setting off but the vanilla tag is still hidden");
                            }
                        }
                    } finally {
                        set(ctx, cfg, old[0], old[1]);
                        ctx.computeOnClient(mc -> {
                            ModUnderTest.staticCall(DUNGEON_STATE, "setRoomSim", new Class<?>[]{boolean.class},
                                    new Object[]{false});
                            classes().remove(MATE);
                            if (id[0] >= 0 && mc.level != null && mc.level.getEntity(id[0]) != null) {
                                mc.level.removeEntity(id[0], Entity.RemovalReason.DISCARDED);
                            }
                            return null;
                        });
                    }
                });
    }

    /** The tab-list class table, as the Hx cases reach it: the teammate "has" a class as if the tab list said so. */
    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> classes() {
        try {
            java.lang.reflect.Field f = Class.forName(CC).getDeclaredField("CLASSES");
            f.setAccessible(true);
            return (java.util.Map<String, Object>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no ClassColors.CLASSES", e);
        }
    }

    private static boolean field(Object cfg, String f) {
        try {
            return cfg.getClass().getField(f).getBoolean(cfg);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no field " + f, e);
        }
    }

    private static void set(ClientGameTestContext ctx, Object cfg, boolean enabled, boolean nametags) {
        ctx.computeOnClient(mc -> {
            try {
                cfg.getClass().getField("classColorsEnabled").setBoolean(cfg, enabled);
                cfg.getClass().getField("classColorsNametags").setBoolean(cfg, nametags);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            return null;
        });
    }

    /** The real renderer's own answer: AvatarRenderer.shouldShowName(Avatar, double) at 4 blocks. */
    private static boolean shown(ClientGameTestContext ctx, int entityId) {
        return ctx.computeOnClient(mc -> {
            Entity e = mc.level.getEntity(entityId);
            if (!(e instanceof Avatar avatar)) {
                throw new AssertionError("the teammate entity is gone (" + e + ")");
            }
            Object renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(avatar);
            for (Class<?> k = renderer.getClass(); k != null; k = k.getSuperclass()) {
                for (Method m : k.getDeclaredMethods()) {
                    if (m.getName().equals("shouldShowName") && m.getParameterCount() == 2
                            && m.getParameterTypes()[0] == Avatar.class) {
                        try {
                            m.setAccessible(true);
                            return (Boolean) m.invoke(renderer, avatar, 16.0);
                        } catch (ReflectiveOperationException ex) {
                            throw new AssertionError("could not call shouldShowName", ex);
                        }
                    }
                }
            }
            throw new AssertionError("no shouldShowName(Avatar, double) on " + renderer.getClass());
        });
    }
}

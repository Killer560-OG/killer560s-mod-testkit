package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static dev.testkit.gametest.SimBossTests.call;
import static dev.testkit.gametest.SimBossTests.command;
import static dev.testkit.gametest.SimBossTests.expect;
import static dev.testkit.gametest.SimBossTests.println;
import static dev.testkit.gametest.SimBossTests.start;
import static dev.testkit.gametest.SimLeap1116.chatSince;

/**
 * {@code 1116-sim-classes} (simclasses-1116, killer560 2026-11-16: "the ability to select classes besides just mage and
 * stuff with the other abilities and ultimates"). In the sim boss with two Sim Teammates, for every class at level 50:
 * picking it says Hypixel's selected line, the tab row reads "(Class L)" and PartyTracker reads the class back, the
 * sidebar lists the teammates with their class letters, Fast Leap finds a teammate by its sim class, Drop Stack uses the
 * class ability (its "Used X!" line, its cooldown, a second press refused with the cooldown line, and its effect on a
 * zombie or on his health), and the ultimate fires through Auto Ult on "Maxor is enraged" (cheat jar; a plain Drop on
 * legit) with its effect. Then level 30 shows XXX, and a reload keeps the class and level. Every check reads what the
 * sim did.
 */
final class SimClasses1116 {

    static final String NAME = "1116-sim-classes";
    private static final String PREFS = "com.killer560.hub.roomsim.SimMenuPrefs";
    private static final String CLASSES = "com.killer560.hub.roomsim.SimClasses";
    private static final String ABIL = "com.killer560.hub.roomsim.SimClassAbilities";
    private static final String DC = "com.killer560.hub.dungeonclass.DungeonClass";
    private static final String CU_CFG = "com.killer560.hub.cheatutils.CheatUtilsConfig";
    private static final String ULT = "com.killer560.hub.cheatutils.AutoUltFeature";
    private static final String[] ORDER = {"HEALER", "TANK", "ARCHER", "BERSERKER", "MAGE"};

    private SimClasses1116() {
    }

    private record Cls(String id, String name, String ability, String ult, int abilityTicks, int ultTicks) {
    }

    /** Level 50, the only one of the class: Explosive Shot 40 - 2*10 = 20 s; Mage 75% off (25% + 25 x 2%). */
    private static final Cls[] ALL = {
            new Cls("HEALER", "Healer", "Healing Circle", "Wish", 40, 2400),
            new Cls("MAGE", "Mage", "Guided Sheep", "Thunderstorm", 150, 2500),
            new Cls("BERSERKER", "Berserk", "Throwing Axe", "Ragnarok", 200, 1200),
            new Cls("ARCHER", "Archer", "Explosive Shot", "Rapid Fire", 400, 2000),
            new Cls("TANK", "Tank", "Seismic Wave", "Castle of Stone", 300, 3000),
    };

    private static void pick(ClientGameTestContext ctx, String cls, int level) {
        ctx.runOnClient(mc -> {
            try {
                Object p = ModUnderTest.staticCall(PREFS, "load");
                p.getClass().getField("simClass").set(p, ModUnderTest.enumValue(DC, cls));
                p.getClass().getField("classLevel").setInt(p, level);
                ModUnderTest.call(p, "save", new Class<?>[]{}, new Object[]{});
                ModUnderTest.staticCall(CLASSES, "selfChanged", new Class<?>[]{boolean.class}, new Object[]{true});
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
    }

    private static Object prefField(ClientGameTestContext ctx, String field) {
        return ctx.computeOnClient(mc -> {
            try {
                Object p = call(PREFS, "cached");
                return String.valueOf(p.getClass().getField(field).get(p));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
    }

    private static ServerPlayer sp(Minecraft mc) {
        return mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
    }

    /** His own tab row's text. */
    private static String ownTabRow(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            String self = mc.player.getGameProfile().name();
            for (PlayerInfo info : mc.getConnection().getListedOnlinePlayers()) {
                Component c = info.getTabListDisplayName();
                String s = c == null ? "" : c.getString();
                if (s.startsWith("[") && s.contains("] " + self + " (")) return s;
            }
            return "none";
        });
    }

    @SuppressWarnings("unchecked")
    private static List<String> sidebar(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            List<String> out = new ArrayList<>();
            Object lines = ModUnderTest.staticCall("com.killer560.hub.scoreboard.ScoreboardData", "sidebarNow",
                    new Class<?>[]{Minecraft.class}, new Object[]{mc});
            for (Object o : (List<Object>) lines) out.add(String.valueOf(o).replaceAll("§.", ""));
            return out;
        });
    }

    private static void send(ClientGameTestContext ctx, ServerboundPlayerActionPacket.Action a) {
        ctx.runOnClient(mc -> mc.getConnection().send(new ServerboundPlayerActionPacket(a, BlockPos.ZERO, Direction.DOWN)));
    }

    private static int[] cooldowns(ClientGameTestContext ctx) {
        return ctx.computeOnClient(SimClasses1116::cd);
    }

    private static int[] cd(Minecraft mc) {
        return ((int[]) ModUnderTest.staticCall(ABIL, "cooldownsForTest",
                new Class<?>[]{net.minecraft.server.MinecraftServer.class, String.class},
                new Object[]{mc.getSingleplayerServer(), mc.player.getGameProfile().name()}));
    }

    private static boolean ultActive(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(ABIL, "ultActiveForTest",
                new Class<?>[]{net.minecraft.server.MinecraftServer.class, String.class},
                new Object[]{mc.getSingleplayerServer(), mc.player.getGameProfile().name()}));
    }

    /** Server values {health, max, absorption, strength, resistance} of the player. */
    private static float[] body(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            ServerPlayer p = sp(mc);
            return new float[]{p.getHealth(), p.getMaxHealth(), p.getAbsorptionAmount(),
                    p.hasEffect(MobEffects.STRENGTH) ? 1 : 0, p.hasEffect(MobEffects.RESISTANCE) ? 1 : 0};
        });
    }

    /** Low health, no natural regeneration (food 17 regenerates nothing and starves nothing), no shield. */
    private static void hurtSelf(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.getSingleplayerServer().execute(() -> {
            ServerPlayer p = sp(mc);
            p.removeAllEffects();
            p.getFoodData().setFoodLevel(17);
            p.setAbsorptionAmount(0);
            p.setHealth(8);
        }));
        ctx.waitTicks(3);
    }

    private static int zombiesAlive(ClientGameTestContext ctx) {
        return ctx.computeOnClient(SimClasses1116::zombies);
    }

    private static int zombies(Minecraft mc) {
        return (sp(mc).level().getEntitiesOfClass(net.minecraft.world.entity.monster.zombie.Zombie.class,
                sp(mc).getBoundingBox().inflate(40), z -> z.isAlive() && z.entityTags().contains("simcls")).size());
    }

    static void run(ClientGameTestContext ctx, List<String> f) throws Exception {
        boolean cheat = dev.testkit.gametest.mod.Mod.isCheat();
        start(ctx, "S1", true);
        String self = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
        try {
            SimLeap1116.setTeammates(ctx, 2);
            SimBossTests.until(ctx, "two teammates", mc -> ((List<?>) call("com.killer560.hub.roomsim.SimTeammates", "names")).size() == 2, 200);
            if (cheat) {
                ctx.runOnClient(mc -> {
                    Object cfg = ModUnderTest.config(CU_CFG);
                    ModUnderTest.set(cfg, "setAutoUltEnabled", true);
                    ModUnderTest.set(cfg, "setUltMaxorEnraged", true);
                    for (String c : ORDER) {
                        ModUnderTest.call(cfg, "setUltClass", new Class<?>[]{((Enum<?>) ModUnderTest.enumValue(DC, c)).getDeclaringClass(), boolean.class},
                                new Object[]{ModUnderTest.enumValue(DC, c), true});
                    }
                });
                // The ult override only matters when set; AUTO reads the class off the tab, which this case checks.
                println(NAME, "Auto Ult override " + ctx.computeOnClient(mc -> ModUnderTest.call(ModUnderTest.config(CU_CFG),
                        "getUltClassOverride", new Class<?>[]{}, new Object[]{})));
            }
            for (Cls c : ALL) {
                String tag = c.name + ": ";
                long mark = LogTap.mark();
                pick(ctx, c.id, 50);
                ctx.waitTicks(50); // the tab and sidebar rebuild; Auto Ult rescans the tab every 40 ticks
                expect(f, tag + "the selected line", chatSince(mark, "You have selected the " + c.name + " Dungeon Class!"), true);
                String row = ownTabRow(ctx);
                println(NAME, tag + "tab row " + row);
                expect(f, tag + "his tab row shows the class", row.endsWith("(" + c.name + " L)"), true);
                Object selfCls = ctx.computeOnClient(mc ->
                        ModUnderTest.staticCall("com.killer560.hub.leapmenu.PartyTracker", "selfClass"));
                expect(f, tag + "PartyTracker reads his class", String.valueOf(selfCls), c.id);

                // Teammates play the classes he does not, in order; the sidebar and Fast Leap know them.
                List<String> mates = new ArrayList<>();
                for (String o : ORDER) if (!o.equals(c.id) && mates.size() < 2) mates.add(o);
                List<String> side = sidebar(ctx);
                println(NAME, tag + "sidebar " + side);
                for (int i = 0; i < 2; i++) {
                    String mate = mates.get(i).equals("BERSERKER") ? "Berserk" : mates.get(i).charAt(0) + mates.get(i).substring(1).toLowerCase();
                    String letter = mates.get(i).substring(0, 1);
                    expect(f, tag + "sidebar lists [" + letter + "] " + mate,
                            side.stream().anyMatch(l -> l.startsWith("[" + letter + "] " + mate + " ")), true);
                    String cls = mates.get(i);
                    Object t = ctx.computeOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.fastleap.Teammates",
                            "firstAliveOfClass", new Class<?>[]{((Enum<?>) ModUnderTest.enumValue(DC, cls)).getDeclaringClass()},
                            new Object[]{ModUnderTest.enumValue(DC, cls)}));
                    expect(f, tag + "a " + cls + " leap goes to " + mate, t == null ? "none" : String.valueOf(t).contains("name=" + mate), true);
                    Object mateCls = ctx.computeOnClient(mc ->
                            ModUnderTest.staticCall("com.killer560.hub.leapmenu.PartyTracker", "classOf",
                                    new Class<?>[]{String.class}, new Object[]{mate}));
                    expect(f, tag + "PartyTracker reads " + mate, String.valueOf(mateCls), cls);
                }

                // The ability, aimed level at a zombie 6 blocks ahead (Healer: looking down, low on health).
                double[] at = ctx.computeOnClient(mc -> new double[]{Math.floor(mc.player.getX()) + 0.5, mc.player.getY(), Math.floor(mc.player.getZ()) + 0.5});
                boolean healer = c.id.equals("HEALER");
                command(ctx, "kill @e[tag=simcls]");
                command(ctx, String.format(java.util.Locale.ROOT, "tp %s %.2f %.2f %.2f 0 %d", self, at[0], at[1], at[2], healer ? 90 : 0));
                if (healer) {
                    hurtSelf(ctx);
                } else {
                    command(ctx, String.format(java.util.Locale.ROOT, "summon zombie %.2f %.2f %.2f {NoAI:1b,Tags:[\"simcls\"]}", at[0], at[1], at[2] + 6));
                    ctx.waitTicks(4);
                    expect(f, tag + "a zombie stands ahead", zombiesAlive(ctx), 1);
                }
                mark = LogTap.mark();
                send(ctx, ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS);
                ctx.waitTicks(3);
                int[] cd = cooldowns(ctx);
                expect(f, tag + "'Used " + c.ability + "!'", chatSince(mark, "Used " + c.ability + "!"), true);
                expect(f, tag + c.ability + " cooldown ~" + c.abilityTicks + " ticks (" + cd[0] + ")",
                        cd[0] > c.abilityTicks - 8 && cd[0] <= c.abilityTicks, true);
                long again = LogTap.mark();
                send(ctx, ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS);
                ctx.waitTicks(3);
                expect(f, tag + "a second press is refused", chatSince(again, "This ability is on cooldown for"), true);
                if (healer) {
                    ctx.waitTicks(45);
                    float[] b = body(ctx);
                    expect(f, tag + "Healing Circle healed him (8 -> " + b[0] + ")", b[0] > 8.5f, true);
                    expect(f, tag + "'Healing Circle is now available!'", chatSince(mark, "Healing Circle is now available!"), true);
                } else {
                    SimBossTests.until(ctx, "the zombie dies", mc -> zombies(mc) == 0, 60);
                    expect(f, tag + c.ability + " killed the zombie", zombiesAlive(ctx), 0);
                    if (c.id.equals("MAGE") || c.id.equals("ARCHER")) {
                        expect(f, tag + "the hit line", chatSince(mark, "Your " + c.ability + " hit 1 enemy for"), true);
                    }
                }

                // The ultimate: Auto Ult on Maxor's enrage line (cheat), a plain Drop on a legit jar.
                hurtSelf(ctx);
                mark = LogTap.mark();
                if (cheat) {
                    ctx.runOnClient(mc -> {
                        try {
                            Class<?> u = Class.forName(ULT);
                            var fired = u.getDeclaredField("firedThisWorld");
                            fired.setAccessible(true);
                            ((Set<?>) fired.get(null)).clear();
                        } catch (ReflectiveOperationException e) {
                            throw new AssertionError(e);
                        }
                    });
                    command(ctx, "tellraw " + self + " {\"text\":\"⚠ Maxor is enraged! ⚠\",\"color\":\"red\"}");
                } else {
                    send(ctx, ServerboundPlayerActionPacket.Action.DROP_ITEM);
                }
                long m2 = mark;
                boolean used = SimBossTests.until(ctx, "the ultimate goes", mc -> cd(mc)[1] > 0, 60);
                for (String l : LogTap.since(m2)) if (l.contains("AutoUlt") || l.contains("[SimClass]")) println(NAME, l);
                expect(f, tag + (cheat ? "Auto Ult used " : "Drop used ") + c.ult, used, true);
                int[] cd2 = cooldowns(ctx);
                expect(f, tag + c.ult + " cooldown ~" + c.ultTicks + " ticks (" + cd2[1] + ")",
                        cd2[1] > c.ultTicks - 60 && cd2[1] <= c.ultTicks, true);
                float[] b = body(ctx);
                if (healer) {
                    expect(f, tag + "Wish healed to full (" + b[0] + "/" + b[1] + ")", b[0] >= b[1] - 0.01f, true);
                    expect(f, tag + "Wish shield (" + b[2] + ")", b[2] > 1f, true);
                    expect(f, tag + "the Wish line", chatSince(mark, "Your Wish healed you for"), true);
                    expect(f, tag + "no 'Used Wish!'", chatSince(mark, "Used Wish!"), false);
                } else {
                    expect(f, tag + "'Used " + c.ult + "!'", chatSince(mark, "Used " + c.ult + "!"), true);
                    expect(f, tag + c.ult + " is running", ultActive(ctx), true);
                    if (c.id.equals("BERSERKER")) expect(f, tag + "Ragnarok gives Strength", b[3] > 0, true);
                    if (c.id.equals("TANK")) expect(f, tag + "Castle of Stone gives Resistance", b[4] > 0, true);
                }
                command(ctx, "kill @e[tag=simcls]");
            }

            // Level 30 reads XXX in the tab; a reload keeps class and level.
            pick(ctx, "ARCHER", 30);
            ctx.waitTicks(30);
            expect(f, "level 30 in the tab", ownTabRow(ctx).endsWith("(Archer XXX)"), true);
            ctx.runOnClient(mc -> call(PREFS, "reload"));
            expect(f, "the class survives a reload", prefField(ctx, "simClass"), "ARCHER");
            expect(f, "the level survives a reload", prefField(ctx, "classLevel"), "30");
        } finally {
            command(ctx, "kill @e[tag=simcls]");
            ctx.runOnClient(mc -> {
                ModUnderTest.turnOff(CU_CFG, "setAutoUltEnabled");
                try {
                    Object p = ModUnderTest.staticCall(PREFS, "load");
                    p.getClass().getField("simClass").set(p, ModUnderTest.enumValue(DC, "MAGE"));
                    p.getClass().getField("classLevel").setInt(p, 50);
                    ModUnderTest.call(p, "save", new Class<?>[]{}, new Object[]{});
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            ctx.runOnClient(mc -> mc.getSingleplayerServer().execute(() -> {
                ServerPlayer p = sp(mc);
                p.removeAllEffects();
                p.setHealth(p.getMaxHealth());
                p.getFoodData().setFoodLevel(20);
            }));
            SimLeap1116.setTeammates(ctx, 0);
        }
    }
}

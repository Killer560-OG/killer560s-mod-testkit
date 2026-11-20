package dev.testkit.gametest.boss;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/**
 * Hide Players (hideplayers-1119): a real player (version 4 UUID) is hidden, a Shadow Assassin style player entity
 * (version 2 UUID, the way Hypixel builds its NPCs and mobs) never is; and "Only In Chosen Phases" hides a real
 * player only while the F7 boss is in a chosen phase.
 */
final class HidePlayers1119Cases {
    private static final String OHC = "objecthider.ObjectHiderConfig";
    private static final String OHF = "objecthider.ObjectHiderFeature";
    private static final int ID_REAL = 9_001_190;
    private static final int ID_SA = 9_001_191;

    private HidePlayers1119Cases() {
    }

    static void register(Session s) {
        s.test("1119-boss-hideplayers-sa", HidePlayers1119Cases::shadowAssassin);
        s.test("1119-boss-hideplayers-phases", HidePlayers1119Cases::phases);
    }

    private static RemotePlayer[] spawn(Session c) {
        return c.onClient(mc -> {
            RemotePlayer real = new RemotePlayer(mc.level,
                    new com.mojang.authlib.GameProfile(UUID.randomUUID(), "TkRealGuy"));
            RemotePlayer sa = new RemotePlayer(mc.level,
                    new com.mojang.authlib.GameProfile(new UUID(0x0000_0000_0000_2000L, 0x8000_0000_0000_0001L), "Shadow Assassin"));
            real.setId(ID_REAL);
            sa.setId(ID_SA);
            real.setPos(mc.player.getX() + 3, mc.player.getY(), mc.player.getZ());
            sa.setPos(mc.player.getX() - 3, mc.player.getY(), mc.player.getZ());
            mc.level.addEntity(real);
            mc.level.addEntity(sa);
            return new RemotePlayer[]{real, sa};
        });
    }

    private static boolean hides(Session c, Entity e) {
        return c.onClient(mc -> (Boolean) Mod.staticCall(OHF, "shouldHideEntity", e));
    }

    private static void cleanup(Session c) {
        c.onClient(mc -> {
            mc.level.removeEntity(ID_REAL, Entity.RemovalReason.DISCARDED);
            mc.level.removeEntity(ID_SA, Entity.RemovalReason.DISCARDED);
            return null;
        });
    }

    static void shadowAssassin(Session c) throws Exception {
        Object cfg = Mod.cfg(OHC);
        boolean wasMaster = (Boolean) Mod.call(cfg, "isObjectHiderEnabled");
        boolean was = (Boolean) Mod.call(cfg, "getHidePlayersRaw");
        boolean gate = (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled");
        try {
            Mod.staticCall("util.SkyblockGate", "setEnabled", false);
            Mod.call(cfg, "setObjectHiderEnabled", true);
            Mod.call(cfg, "setHidePlayersPhases", false);
            Mod.call(cfg, "setHidePlayers", false);
            RemotePlayer[] p = spawn(c);
            boolean realOff = hides(c, p[0]);
            boolean saOff = hides(c, p[1]);
            Mod.call(cfg, "setHidePlayers", true);
            boolean realOn = hides(c, p[0]);
            boolean saOn = hides(c, p[1]);
            c.note("off: real " + realOff + ", SA " + saOff + "; on: real " + realOn + ", SA " + saOn);
            c.check(!realOff && !saOff, "something hidden with Hide Players off");
            c.check(realOn, "premise: the feature did not hide the real player");
            c.check(!saOn, "the Shadow Assassin style player (version 2 UUID) was hidden");
        } finally {
            cleanup(c);
            Mod.call(cfg, "setHidePlayers", was);
            Mod.call(cfg, "setObjectHiderEnabled", wasMaster);
            Mod.staticCall("util.SkyblockGate", "setEnabled", gate);
        }
    }

    static void phases(Session c) throws Exception {
        Object cfg = Mod.cfg(OHC);
        boolean wasMaster = (Boolean) Mod.call(cfg, "isObjectHiderEnabled");
        boolean was = (Boolean) Mod.call(cfg, "getHidePlayersRaw");
        boolean gate = (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled");
        try {
            c.server().command("forceload add 21 123 30 131");
            c.server().sync(20);
            c.server().command("fill 21 123 123 30 123 131 minecraft:stone");
            c.server().command("fill 21 124 123 30 129 131 minecraft:air");
            c.ctx().waitTicks(10);
            TerminalAuraCases.tp(c, 25.5, 124, 127.5, 90f);
            Mod.staticCall("util.SkyblockGate", "setEnabled", false);
            Mod.call(cfg, "setObjectHiderEnabled", true);
            Mod.call(cfg, "setHidePlayers", true);
            for (int i = 1; i <= 5; i++) {
                Mod.call(cfg, "setHidePlayersPhase", i, i == 3);
            }
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
            RemotePlayer[] p = spawn(c);
            c.hx().chat("[BOSS] Storm: Pathetic Maxor, just like expected.");
            c.waitUntil("Floor7Tracker phase P2",
                    mc -> "P2".equals(String.valueOf(Mod.staticCall(TerminalAuraCases.TRACKER, "getPhase"))), 100);
            Mod.call(cfg, "setHidePlayersPhases", false);
            c.ctx().waitTicks(2);
            boolean offP2 = hides(c, p[0]);
            Mod.call(cfg, "setHidePlayersPhases", true);
            c.ctx().waitTicks(2);
            boolean chosenP2 = hides(c, p[0]);
            c.hx().chat(TerminalAuraCases.GOLDOR);
            c.waitUntil("Floor7Tracker phase P3",
                    mc -> "P3".equals(String.valueOf(Mod.staticCall(TerminalAuraCases.TRACKER, "getPhase"))), 100);
            c.ctx().waitTicks(2);
            boolean chosenP3 = hides(c, p[0]);
            boolean saP3 = hides(c, p[1]);
            Mod.call(cfg, "setHidePlayersPhase", 3, false);
            c.ctx().waitTicks(2);
            boolean noneP3 = hides(c, p[0]);
            c.note("switch off in P2: " + offP2 + "; only P3 chosen: P2 " + chosenP2 + ", P3 " + chosenP3
                    + " (SA " + saP3 + "); nothing chosen in P3: " + noneP3);
            c.check(offP2, "premise: switch OFF did not hide in P2 as before");
            c.check(!chosenP2, "hidden in P2 with only P3 chosen");
            c.check(chosenP3, "not hidden in P3 with P3 chosen");
            c.check(!saP3, "the Shadow Assassin style player was hidden in P3");
            c.check(!noneP3, "hidden in P3 with no phase chosen");
        } finally {
            cleanup(c);
            Mod.call(cfg, "setHidePlayersPhases", false);
            Mod.call(cfg, "setHidePlayers", was);
            Mod.call(cfg, "setObjectHiderEnabled", wasMaster);
            Mod.staticCall("util.SkyblockGate", "setEnabled", gate);
        }
    }
}

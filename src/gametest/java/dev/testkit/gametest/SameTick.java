package dev.testkit.gametest;

import dev.testkit.harness.PacketTrace;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * SAME-TICK GRIM CASES (grimsame-1105; killer560 2026-11-05: an idle-after test is not a Grim test). While a feature runs,
 * the player's own action is put into every client tick, where vanilla puts it, and the wire is split into GrimAC's
 * ticks (a movement packet, or {@code client_tick_end} when no movement packet went before it; a teleport's echo is not
 * one), so a case can prove that the feature's packet and his own went out inside ONE of the server's ticks before a
 * clean GrimAC verdict counts.
 * <ul>
 *   <li>{@link Act#USE}: a right click into the air, {@code gameMode.useItem}, at START after every mod's START
 *       handler (where {@code handleKeybinds} would send it: after the START actors, before the movement packet). Its
 *       {@code use_item} carries his body look.</li>
 *   <li>{@link Act#ATTACK}: {@code gameMode.attack} + swing on the entity his crosshair is on (or the case's target),
 *       same place.</li>
 *   <li>{@link Act#DIG}: his hand dig, {@code gameMode.startDestroyBlock} on the block his crosshair is on, same place.</li>
 *   <li>{@link Act#SWAP}: the selected slot moves between two slots at END (a scroll or a hotbar key between ticks);
 *       vanilla's {@code gameMode.tick} (or the next action through the game mode) sends it next tick.</li>
 *   <li>{@link Act#CLICK}: a container click on an empty player slot of the open menu at END (a mouse click between
 *       ticks), so it lands in the next server tick ahead of the START actors.</li>
 * </ul>
 * Packets sent while the player's action runs are his; a teleport accept is always his. Everything else matching the
 * case's predicate is the feature's.
 */
public final class SameTick implements AutoCloseable {

    public enum Act { USE, ATTACK, DIG, SWAP, CLICK }

    private static final Identifier LATE = Identifier.parse("testkit:sametick_late");
    private static boolean hooked;
    private static volatile SameTick active;

    private final Act act;
    private final int every;
    private final Predicate<Packet<?>> feature;
    private final Supplier<Entity> target;
    private final int slotA;
    private final int slotB;
    private final java.util.function.Consumer<Packet<?>> prevTap;

    private boolean injecting;
    private int tick;
    private int done;
    private boolean movedSinceEnd;
    private boolean echoNext;
    /** The current GrimAC tick: packet names, F = feature, P = his. */
    private final List<String> window = new ArrayList<>();
    private boolean winFeature;
    private boolean winPlayer;
    private final List<String> both = new ArrayList<>();
    private int featureWindows;
    private int playerWindows;

    private SameTick(Act act, int every, Predicate<Packet<?>> feature, Supplier<Entity> target, int slotA, int slotB) {
        this.act = act;
        this.every = Math.max(1, every);
        this.feature = feature;
        this.target = target;
        this.slotA = slotA;
        this.slotB = slotB;
        this.prevTap = PacketTrace.tap;
    }

    /**
     * Starts putting {@code act} into every {@code every}-th client tick. Client thread. {@code target} is the entity an
     * ATTACK hits (null for the others); SWAP moves between {@code slotA} and {@code slotB}.
     */
    public static SameTick start(Act act, int every, Predicate<Packet<?>> feature, Supplier<Entity> target, int slotA,
                                 int slotB) {
        if (!hooked) {
            hooked = true;
            ClientTickEvents.START_CLIENT_TICK.addPhaseOrdering(net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE, LATE);
            ClientTickEvents.START_CLIENT_TICK.register(LATE, mc -> {
                SameTick s = active;
                if (s != null && (s.act == Act.USE || s.act == Act.ATTACK || s.act == Act.DIG)) s.inject(mc);
            });
            ClientTickEvents.END_CLIENT_TICK.register(mc -> {
                SameTick s = active;
                if (s != null && (s.act == Act.SWAP || s.act == Act.CLICK)) s.inject(mc);
            });
        }
        SameTick s = new SameTick(act, every, feature, target, slotA, slotB);
        PacketTrace.tap = s::onSent;
        active = s;
        return s;
    }

    private void inject(Minecraft mc) {
        if (mc.player == null || mc.gameMode == null || tick++ % every != 0) return;
        injecting = true;
        try {
            switch (act) {
                case USE -> {
                    // A stick's use passes, so vanilla sends the use_item and no swing.
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    done++;
                }
                case ATTACK -> {
                    // What his crosshair (the body's look, which automation may have turned) is on, unless the case names it.
                    Entity e = target != null ? target.get()
                            : mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult eh ? eh.getEntity() : null;
                    if (e != null && e.isAlive()) {
                        mc.gameMode.attack(mc.player, e);
                        dev.testkit.compat.McCompat.swingArm(mc.player);
                        done++;
                    }
                }
                case DIG -> {
                    if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bh
                            && bh.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                        mc.gameMode.startDestroyBlock(bh.getBlockPos(), bh.getDirection());
                        dev.testkit.compat.McCompat.swingArm(mc.player);
                        done++;
                    }
                }
                case SWAP -> {
                    var inv = mc.player.getInventory();
                    inv.setSelectedSlot(inv.getSelectedSlot() == slotA ? slotB : slotA);
                    done++;
                }
                case CLICK -> {
                    if (dev.testkit.compat.McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> scr) {
                        var menu = scr.getMenu();
                        // The last slot of the menu is the player's hotbar slot 9; the case leaves it empty.
                        int slot = menu.slots.size() - 1;
                        mc.gameMode.handleContainerInput(menu.containerId, slot, 0,
                                net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
                        done++;
                    }
                }
            }
        } finally {
            injecting = false;
        }
    }

    private void onSent(Packet<?> p) {
        java.util.function.Consumer<Packet<?>> prev = prevTap;
        if (prev != null) prev.accept(p);
        synchronized (window) {
            String name = p.type().id().getPath();
            boolean echo = echoNext && p instanceof ServerboundMovePlayerPacket;
            if (!echo) echoNext = false;
            if (p instanceof ServerboundAcceptTeleportationPacket) echoNext = true;
            boolean mine = injecting || p instanceof ServerboundAcceptTeleportationPacket;
            boolean feat = !mine && feature.test(p);
            if (mine) winPlayer = true;
            if (feat) winFeature = true;
            window.add(name + (mine ? "(P)" : feat ? "(F)" : ""));
            boolean tickPacket = (p instanceof ServerboundMovePlayerPacket && !echo)
                    || (p instanceof ServerboundClientTickEndPacket && !movedSinceEnd);
            if (p instanceof ServerboundMovePlayerPacket && !echo) movedSinceEnd = true;
            if (p instanceof ServerboundClientTickEndPacket) movedSinceEnd = false;
            if (tickPacket) closeWindow();
        }
    }

    private void closeWindow() {
        if (winFeature) featureWindows++;
        if (winPlayer) playerWindows++;
        if (winFeature && winPlayer && both.size() < 400) both.add(String.join(" ", window));
        window.clear();
        winFeature = false;
        winPlayer = false;
    }

    /** Server ticks that carried both the feature's packet and his. */
    public int both() {
        synchronized (window) {
            return both.size();
        }
    }

    public int featureWindows() {
        synchronized (window) {
            return featureWindows;
        }
    }

    /** How many times his action was put in. */
    public int done() {
        return done;
    }

    /** One line for the case's note: counts and the first few shared ticks. */
    public String report() {
        synchronized (window) {
            return act + ": his action x" + done + ", feature ticks " + featureWindows + ", his ticks " + playerWindows
                    + ", SHARED " + both.size() + (both.isEmpty() ? "" : " e.g. [" + String.join("] [",
                    both.subList(0, Math.min(3, both.size()))) + "]");
        }
    }

    @Override
    public void close() {
        active = null;
        PacketTrace.tap = prevTap;
    }
}

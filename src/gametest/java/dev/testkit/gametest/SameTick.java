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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

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
 *   <li>{@link Act#ATTACK} / {@link Act#DIG}: a left click queued at END ({@code KeyMapping.click} on the attack key, a
 *       mouse press between ticks) while his crosshair is on an entity / a block, so vanilla's {@code handleKeybinds}
 *       attacks or digs next tick after the START actors - and the mod can see the queued click at START, as it can in
 *       real play. His packets are the {@code attack} / the {@code player_action} digs the feature's predicate does not
 *       claim.</li>
 *   <li>{@link Act#SWAP}: the selected slot moves between two slots at END (a scroll or a hotbar key between ticks);
 *       vanilla's {@code gameMode.tick} (or the next action through the game mode) sends it next tick.</li>
 *   <li>{@link Act#DIG_HELD} (grimgapB-1105): his left click HELD on a block (the attack key down every tick, no new
 *       press), so vanilla's {@code continueAttack} digs block after block from {@code handleKeybinds}; released on
 *       {@link #close}.</li>
 *   <li>{@link Act#CLICK}: a container click on an empty player slot of the open menu at END (a mouse click between
 *       ticks), so it lands in the next server tick ahead of the START actors.</li>
 * </ul>
 * Packets sent while the player's action runs (or, for SWAP / ATTACK / DIG, the packet vanilla sends for it next tick)
 * are his; a teleport accept is marked (T) and is neither. Everything else matching the case's predicate is the feature's.
 */
public final class SameTick implements AutoCloseable {

    public enum Act { USE, ATTACK, DIG, SWAP, CLICK, DIG_HELD, USE_KEY }

    private static final Identifier LATE = Identifier.parse("testkit:sametick_late");
    private static boolean hooked;
    private static volatile SameTick active;

    private final Act act;
    private final int every;
    private final Predicate<Packet<?>> feature;
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
    private boolean winPlayerAfter;
    private final List<String> both = new ArrayList<>();
    private int featureWindows;
    private int playerWindows;
    /** Server ticks in which a packet of his went out AFTER the feature's (a swap after a use is PacketOrderE). */
    private int playerAfterFeature;

    private SameTick(Act act, int every, Predicate<Packet<?>> feature, int slotA, int slotB) {
        this.act = act;
        this.every = Math.max(1, every);
        this.feature = feature;
        this.slotA = slotA;
        this.slotB = slotB;
        this.prevTap = PacketTrace.tap;
    }

    /**
     * Starts putting {@code act} into every {@code every}-th client tick. Client thread. SWAP moves between {@code slotA} and {@code slotB}.
     */
    public static SameTick start(Act act, int every, Predicate<Packet<?>> feature, int slotA, int slotB) {
        if (!hooked) {
            hooked = true;
            ClientTickEvents.START_CLIENT_TICK.addPhaseOrdering(net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE, LATE);
            ClientTickEvents.START_CLIENT_TICK.register(LATE, mc -> {
                SameTick s = active;
                if (s != null && s.act == Act.USE) s.inject(mc);
            });
            ClientTickEvents.END_CLIENT_TICK.register(mc -> {
                SameTick s = active;
                if (s != null && s.act != Act.USE) s.inject(mc);
            });
        }
        SameTick s = new SameTick(act, every, feature, slotA, slotB);
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
                case ATTACK, DIG -> {
                    // What his crosshair is on decides what vanilla's startAttack does with the click.
                    boolean on = act == Act.ATTACK ? mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult
                            : mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bh
                            && bh.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK;
                    if (on) {
                        net.minecraft.client.KeyMapping.click(mc.options.keyAttack.getDefaultKey());
                        done++;
                    }
                }
                case USE_KEY -> {
                    // His right click as he makes it: a press queued for the next tick's handleKeybinds (vanilla
                    // startUseItem: on a block use_item_on then use_item, into the air use_item), so a feature can see it
                    // coming at START_CLIENT_TICK the way it sees his real clicks.
                    net.minecraft.client.KeyMapping.click(mc.options.keyUse.getDefaultKey());
                    done++;
                }
                case DIG_HELD -> {
                    mc.options.keyAttack.setDown(true);
                    if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bh
                            && bh.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
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
            // A SWAP's slot change goes out later, from vanilla's gameMode.tick: it is his by its slot.
            // A teleport accept is marked (T) and never counts as his action: every warp has one, so it would make any
            // route case "share" a tick without his action in it.
            boolean accept = p instanceof ServerboundAcceptTeleportationPacket;
            boolean mine = (injecting && !accept)
                    || (act == Act.SWAP && p instanceof net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket h
                    && (h.getSlot() == slotA || h.getSlot() == slotB))
                    || (act == Act.ATTACK && "attack".equals(name) && !feature.test(p))
                    || (act == Act.USE_KEY && (p instanceof net.minecraft.network.protocol.game.ServerboundUseItemPacket
                    || p instanceof net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
                    || p instanceof net.minecraft.network.protocol.game.ServerboundInteractPacket) && !feature.test(p))
                    || ((act == Act.DIG || act == Act.DIG_HELD) && p instanceof net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
                    && !feature.test(p));
            boolean feat = !mine && !accept && feature.test(p);
            if (mine && winFeature && !winPlayerAfter) {
                winPlayerAfter = true;
                playerAfterFeature++;
            }
            if (mine) winPlayer = true;
            if (feat) winFeature = true;
            window.add(name + (mine ? "(P)" : feat ? "(F)" : accept ? "(T)" : ""));
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
        winPlayerAfter = false;
    }

    /** Server ticks that carried both the feature's packet and his. */
    public int both() {
        synchronized (window) {
            return both.size();
        }
    }

    /** Server ticks in which his packet followed the feature's. */
    public int playerAfterFeature() {
        synchronized (window) {
            return playerAfterFeature;
        }
    }

    /** Server ticks that carried his action. */
    public int playerWindows() {
        synchronized (window) {
            return playerWindows;
        }
    }

    public int featureWindows() {
        synchronized (window) {
            return featureWindows;
        }
    }

    public Act act() {
        return act;
    }

    /** How many times his action was put in. */
    public int done() {
        return done;
    }

    /** One line for the case's note: counts and the first few shared ticks. */
    public String report() {
        synchronized (window) {
            return act + ": his action x" + done + ", feature ticks " + featureWindows + ", his ticks " + playerWindows
                    + ", his after the feature's " + playerAfterFeature + ", SHARED " + both.size() + (both.isEmpty() ? "" : " e.g. [" + String.join("] [",
                    both.subList(0, Math.min(3, both.size()))) + "]");
        }
    }

    @Override
    public void close() {
        active = null;
        if (act == Act.DIG_HELD) Minecraft.getInstance().options.keyAttack.setDown(false);
        PacketTrace.tap = prevTap;
    }
}

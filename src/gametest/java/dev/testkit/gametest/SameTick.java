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
 *   <li>{@link Act#TP}: nothing is put in; the case has the server teleport him, and his teleport accept (with its echo)
 *       is the action (grimgapA-1105: a feature acting in the tick he answers a teleport).</li>
 *   <li>{@link Act#USE_KEY}: a click of the use key at END, so the next tick's {@code handleKeybinds} sends the right
 *       click as vanilla does (on a block use_item_on then use_item), after {@code gameMode.tick} has sent a pending hotbar
 *       change, and a feature sees it queued at START as it sees his real clicks. His packets are the uses and interacts
 *       sent while slotA or slotB is held (grimgapA/B-1105; one act since ship8, was KEYUSE and USE_KEY).</li>
 * </ul>
 * Packets sent while the player's action runs (or, for SWAP / ATTACK / DIG, the packet vanilla sends for it next tick)
 * are his; a teleport accept is marked (T) and is neither. Everything else matching the case's predicate is the feature's.
 */
public final class SameTick implements AutoCloseable {

    public enum Act { USE, ATTACK, DIG, SWAP, CLICK, SCREEN_CLICK, TP, DIG_HELD, USE_KEY, OPEN_CLICK }

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
    /** Injections still allowed; -1 (the default) is no limit. See {@link #allow}. */
    private volatile int left = -1;
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
        if (act == Act.TP || mc.player == null || mc.gameMode == null || tick++ % every != 0) return;
        if (left == 0) return;
        if (left > 0) left--;
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
                case OPEN_CLICK -> {
                    // His inventory opened, one click on its last slot (hotbar 9, left empty) and closed again inside this tick's
                    // end: the click and the close go out right behind whatever the feature sent at the tick's start.
                    dev.testkit.compat.McCompat.setScreen(mc, new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
                    var menu = mc.player.containerMenu;
                    mc.gameMode.handleContainerInput(menu.containerId, menu.slots.size() - 1, 0,
                            net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
                    dev.testkit.compat.McCompat.setScreen(mc, null);
                    done++;
                }
                case SCREEN_CLICK -> {
                    // His mouse on the open menu's last slot (his hotbar slot 9, left empty), through the screen as a real
                    // press and release: a screen that holds his clicks (the sorter's) sends nothing.
                    if (dev.testkit.compat.McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> scr) {
                        var slot = scr.getMenu().slots.get(scr.getMenu().slots.size() - 1);
                        double x = intField(scr, "leftPos") + slot.x + 8;
                        double y = intField(scr, "topPos") + slot.y + 8;
                        var ev = new net.minecraft.client.input.MouseButtonEvent(x, y, new net.minecraft.client.input.MouseButtonInfo(
                                com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0));
                        scr.mouseClicked(ev, false);
                        scr.mouseReleased(ev);
                        done++;
                    }
                }
            }
        } finally {
            injecting = false;
        }
    }

    private static int intField(Object o, String name) {
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
            try {
                java.lang.reflect.Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.getInt(o);
            } catch (NoSuchFieldException e) {
                // the next superclass
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("no field " + name + " on " + o.getClass());
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
            // A held-item change inside his use / click is a slot someone set earlier (vanilla sends it first): his only
            // in SWAP, by its slot.
            boolean carried = p instanceof net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
            if (accept && act == Act.TP) done++;
            boolean mine = (injecting && !accept && !carried) || (accept && act == Act.TP)
                    || (act == Act.SWAP && p instanceof net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket h
                    && (h.getSlot() == slotA || h.getSlot() == slotB))
                    || (act == Act.ATTACK && "attack".equals(name) && !feature.test(p))
                    || (act == Act.USE_KEY && (p instanceof net.minecraft.network.protocol.game.ServerboundUseItemPacket
                    || p instanceof net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
                    || p instanceof net.minecraft.network.protocol.game.ServerboundInteractPacket)
                    && (heldSlot() == slotA || heldSlot() == slotB))
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

    /**
     * Null when the case really tested something, else why not. A queued click (ATTACK / DIG / USE_KEY) holds a world actor for the
     * tick it is queued in (ActionGate), so both must have gone out but never in one server tick; any other act must share one.
     */
    public String verdict(String what) {
        return verdict(what, act == Act.ATTACK || act == Act.DIG || act == Act.USE_KEY);
    }

    /** As {@link #verdict(String)}; {@code apart}: the feature must wait out his action (both out, never one tick). */
    public String verdict(String what, boolean apart) {
        if (done == 0) return what + ": his action never went in (nothing tested)";
        if (apart) {
            if (playerWindows() == 0 || featureWindows() == 0) return what + ": his click or the feature never went out (" + report() + ")";
            return both() == 0 ? null : what + ": his click and the feature shared a server tick (" + report() + ")";
        }
        return both() > 0 ? null : what + ": no server tick carried both the feature's packet and his - nothing tested (" + report() + ")";
    }

    private static int heldSlot() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? -1 : mc.player.getInventory().getSelectedSlot();
    }

    /** Only the next {@code n} injections go in (0: none until the next allow): his action around one moment. */
    public void allow(int n) {
        left = n;
    }

    /** One line for the case's note: counts and the first few shared ticks. */
    public String report() {
        synchronized (window) {
            return act + ": his action x" + done + ", feature ticks " + featureWindows + ", his ticks " + playerWindows
                    + ", his after the feature's " + playerAfterFeature + ", SHARED " + both.size() + (both.isEmpty() ? "" : " e.g. [" + String.join("] [",
                    both.subList(0, Math.min(3, both.size()))) + "]");
        }
    }

    /** What a case wants of the shared ticks. */
    public enum Expect {
        /** Some server tick carried both. */
        SHARE,
        /** Both acted, never in one server tick (the feature waits for his action, or the two cannot meet). */
        APART,
        /** Both went out on the wire (in one server tick or apart); GrimAC's flags decide the rest (the harness fails a flagged case). */
        ACTED,
        /** The feature acted and none of his action reached the wire (the feature holds it). */
        HELD
    }

    /** Stops his action, notes the counts, and fails a case that tested nothing or broke {@code want}. */
    public static void judge(dev.testkit.gametest.hx.Session c, SameTick st, String what, Expect want) {
        c.onClient(mc -> {
            st.close();
            return null;
        });
        String r = st.report();
        c.note(what + " " + r);
        c.check(st.done() > 0, what + ": his action never went in (nothing tested)");
        c.check(st.featureWindows() > 0, what + ": the feature sent nothing (nothing tested; " + r + ")");
        switch (want) {
            case SHARE -> c.check(st.both() > 0, what + ": no server tick carried both the feature's packet and his ("
                    + r + ")");
            case ACTED -> c.check(st.playerWindows() > 0, what + ": his action never reached the wire (" + r + ")");
            case APART -> c.check(st.playerWindows() > 0 && st.both() == 0, what + ": want both apart (" + r + ")");
            case HELD -> c.check(st.playerWindows() == 0, what + ": his action reached the wire (" + r + ")");
        }
    }

    @Override
    public void close() {
        active = null;
        if (act == Act.DIG_HELD) Minecraft.getInstance().options.keyAttack.setDown(false);
        PacketTrace.tap = prevTap;
    }
}

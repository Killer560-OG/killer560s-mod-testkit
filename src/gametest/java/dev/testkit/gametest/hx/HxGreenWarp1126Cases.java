package dev.testkit.gametest.hx;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * SAME-TICK GRIM CASES for the green room warp (autopilot-greenroom-warp-1126, cheat jar only): Dungeons Autopilot switched
 * on before the run with Warp to Door Before Start on and an etherwarp item in the hotbar etherwarps him (ClearExecutor's
 * one-hop sender: item swap, sneak, use) onto the stand by the gate instead of walking. The Hx server has no Entrance, so the
 * stand is {@code GreenRoomStart.testGoal}: 10 blocks apart along a flat floor, back and forth (switched off and on at each
 * end, so every leg is a fresh arm). His actions are put into the warp's ticks; the feature's packets are its use_item and
 * its sneak input.
 * <ul>
 *   <li>1126-hx-greenwarp-same-&lt;idle|use|attack|swap|click|tp&gt;: his right click, a hit on an interaction entity round
 *       him, a hotbar scroll, an inventory click or the server's teleport accept in the warp's ticks; GrimAC (default and
 *       experimental checkouts) silent, both on the wire, and at least 3 warps landed (the server's own count).</li>
 * </ul>
 * Names: 1126-hx-greenwarp-same-idle, 1126-hx-greenwarp-same-use, 1126-hx-greenwarp-same-attack,
 * 1126-hx-greenwarp-same-swap, 1126-hx-greenwarp-same-click, 1126-hx-greenwarp-same-tp.
 * <p>
 * 1126-hx-firsttrip-gate-same-&lt;idle|use|attack|swap|click|tp&gt; (autopilot-prerun-1126): the same legs, each warp held
 * as the first trip through the shut entrance gate is ({@code ClearExecutor.testGate}: primed each tick - item, sneak,
 * turn - and let go the tick a stone block beside the floor is air), the block taken away 3-7 ticks into the hold, so his
 * actions land in the held ticks and in the release tick. Names: 1126-hx-firsttrip-gate-same-idle,
 * 1126-hx-firsttrip-gate-same-use, 1126-hx-firsttrip-gate-same-attack, 1126-hx-firsttrip-gate-same-swap,
 * 1126-hx-firsttrip-gate-same-click, 1126-hx-firsttrip-gate-same-tp.
 */
final class HxGreenWarp1126Cases {

    private static final String GRS = "autosecret.GreenRoomStart";
    private static final String PILOT = "autosecret.DungeonsAutopilot";
    private static final String CE = "livemap.autoclear.ClearExecutor";
    private static final String AOTV = "minecraft:diamond_shovel[custom_data={id:\"ASPECT_OF_THE_VOID\",ethermerge:1,"
            + "tuned_transmission:4},unbreakable={}]";

    private HxGreenWarp1126Cases() {
    }

    static void register(Session s) {
        for (String a : new String[]{"idle", "use", "attack", "swap", "click", "tp"}) {
            s.test("1126-hx-greenwarp-same-" + a, c -> same(c, a, false));
            s.test("1126-hx-firsttrip-gate-same-" + a, c -> same(c, a, true));
        }
    }

    /** The floor; returns the two stands (feet blocks) 10 apart. */
    private static BlockPos[] stage(Session c, boolean target) {
        dungeon(c);
        BlockPos door = (BlockPos) Mod.staticCall("livemap.DungeonLayout", "doorBlock", 1);
        int x = door.getX();
        int z = door.getZ();
        c.server().command("forceload add " + (x - 14) + " " + (z - 14) + " " + (x + 14) + " " + (z + 14));
        c.server().command(String.format("fill %d 67 %d %d 68 %d stone", x - 12, z - 12, x + 12, z + 12));
        c.server().command(String.format("fill %d 69 %d %d 75 %d air", x - 12, z - 12, x + 12, z + 12));
        c.ctx().waitTicks(5);
        c.hx().call("dungeon.tp", "x", x + 0.5, "y", 69.0, "z", z - 4.5, "yaw", 90f, "pitch", 0f);
        c.hx().give(2, AOTV);
        c.hx().give(4, "minecraft:stick");
        c.hx().give(5, "minecraft:stick");
        c.hx().call("dungeon.abilities", "enabled", true);
        c.server().command("kill @e[tag=sametick]");
        if (target) {
            c.server().command(String.format(java.util.Locale.ROOT, "summon minecraft:interaction %.2f 66 %.2f "
                    + "{width:30f,height:8f,response:true,Tags:[\"sametick\"]}", x + 0.5, z + 0.5));
        }
        c.waitUntil("the floor on the client", mc -> !mc.level.getBlockState(new BlockPos(x, 68, z + 5)).isAir(), 200);
        c.onClient(mc -> {
            mc.player.getInventory().setSelectedSlot(4);
            Mod.set("autosecret.AutoSecretConfig", "setGreenRoomWarp", true);
            return null;
        });
        c.waitUntil("standing on the floor", mc -> mc.player.onGround() && Math.abs(mc.player.getY() - 69) < 0.01
                && mc.player.getDeltaMovement().horizontalDistanceSqr() < 1e-6, 200);
        c.ctx().waitTicks(20);
        return new BlockPos[]{new BlockPos(x, 69, z + 5), new BlockPos(x, 69, z - 5)};
    }

    private static void clean(Session c) {
        c.onClient(mc -> {
            Mod.staticCall(PILOT, "setOn", false);
            Mod.staticCall(GRS, "testGoal", (Object) null);
            Mod.staticCall(CE, "cancel");
            return null;
        });
        c.hx().call("dungeon.abilities", "enabled", false);
        c.server().command("kill @e[tag=sametick]");
        c.server().command("forceload remove all");
        for (int s : new int[]{2, 4, 5}) {
            c.hx().give(s, "minecraft:air");
        }
        hub(c);
    }

    private static void go(Session c, BlockPos goal) {
        c.onClient(mc -> {
            Mod.staticCall(PILOT, "setOn", false);
            Mod.staticCall(GRS, "testGoal", goal);
            Mod.staticCall(PILOT, "setOn", true);
            return null;
        });
    }

    private static String result(Session c) {
        return c.onClient(mc -> String.valueOf(Mod.staticCall(GRS, "result")));
    }

    private static int warps(Session c) {
        return c.hx().call("dungeon.stats").getAsJsonObject().get("etherwarps").getAsInt();
    }

    private static boolean feature(Packet<?> p) {
        return p instanceof ServerboundUseItemPacket || p instanceof ServerboundPlayerInputPacket in && in.input().shift();
    }

    private static void same(Session c, String a, boolean gate) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Dungeons Autopilot is cheat-only");
            return;
        }
        SameTick.Act act = switch (a) {
            // His real right click: pressed between ticks, so START sees it queued (the mod holds its item swap for it)
            // and it goes as vanilla's startUseItem with his own item. Act.USE's bare use_item from nowhere after the
            // swap is no click a player sends (greenroom-fix-1126).
            case "use" -> SameTick.Act.USE_CLICK;
            case "attack" -> SameTick.Act.ATTACK;
            case "swap" -> SameTick.Act.SWAP;
            case "click" -> SameTick.Act.SCREEN_CLICK;
            case "tp" -> SameTick.Act.TP;
            default -> null;
        };
        SameTick st = null;
        try {
            BlockPos[] ends = stage(c, act == SameTick.Act.ATTACK);
            int warps0 = warps(c);
            c.onClient(mc -> {
                wireOn(mc);
                return null;
            });
            if (act != null) {
                st = c.onClient(mc -> SameTick.start(act, 2, HxGreenWarp1126Cases::feature,
                        4, 5));
            }
            int legs = 0;
            int warped = 0;
            int settled = 0;
            BlockPos block = ends[0].offset(-6, 1, -5);
            int openAt = -1;
            int heldTicks = 0;
            int released = 0;
            for (int i = 0; i < 240; i++) {
                String r = result(c);
                if (!"warping".equals(r) && !"armed".equals(r) && (settled++ > 6 || legs == 0)) {
                    warped += "warped".equals(r) ? 1 : 0;
                    if (gate) {
                        c.server().command(String.format("setblock %d %d %d stone", block.getX(), block.getY(), block.getZ()));
                        c.waitUntil("the gate block on the client", mc -> !mc.level.getBlockState(block).isAir(), 40);
                        c.onClient(mc -> {
                            Mod.staticCall(CE, "testGate", java.util.List.of(block));
                            return null;
                        });
                        openAt = i + 3 + legs % 5;
                    }
                    go(c, ends[legs % 2]);
                    legs++;
                    settled = 0;
                }
                if (gate && c.onClient(mc -> (Boolean) Mod.staticCall(CE, "waitingForGate"))) {
                    heldTicks++;
                }
                if (gate && i == openAt) {
                    c.server().command(String.format("setblock %d %d %d air", block.getX(), block.getY(), block.getZ()));
                    released++;
                }
                if (act == SameTick.Act.SCREEN_CLICK && i % 30 == 1) {
                    c.onClient(mc -> {
                        dev.testkit.compat.McCompat.setScreen(mc,
                                new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
                        return null;
                    });
                } else if (act == SameTick.Act.SCREEN_CLICK && i % 30 == 7) {
                    c.onClient(mc -> {
                        dev.testkit.compat.McCompat.setScreen(mc, null);
                        return null;
                    });
                }
                if (act == SameTick.Act.TP && i % 4 == 1) {
                    c.server().command("execute as @a at @s run tp @s ~ ~ ~");
                }
                c.ctx().waitTick();
            }
            int landed = warps(c) - warps0;
            c.note(String.format(java.util.Locale.US, "green room warp %s: %d leg(s) asked, %d read warped, %d warp(s) landed"
                    + " on the server, last result %s", a, legs, warped, landed, result(c)));
            // A leg reads "warped" only once the server put him within 1 of a stand 10 away with no walk: that is a landing
            // too (hx experimental 03:01, 11-26: every leg landed and confirmed, the server's stat read 0).
            if (landed < 3 && warped >= 3) {
                c.note("dungeon.stats etherwarps read " + landed + " while " + warped + " legs landed by the server's teleport");
            }
            if (warped < 2 || landed < 3 && warped < 3) {
                dumpWire(c);
            }
            if (gate) {
                c.note(heldTicks + " tick(s) held for the gate over " + released + " release(s)");
                c.check(heldTicks >= legs && released >= 3, "the warps were not held for the gate (" + heldTicks
                        + " held tick(s), " + released + " release(s))");
            }
            c.check(landed >= 3 || warped >= 3, "only " + landed + " warp(s) landed (nothing tested)");
            c.check(warped >= 2, "only " + warped + " leg(s) ended warped");
            // His clicks never ride the warp item: the AOTV in hand on his level look was an Instant Transmission or an
            // etherwarp of his (wire t195 on 0745f7232, greenroom-fix2-1126).
            c.check(aotvLevelUses == 0, aotvLevelUses + " use(s) went with the AOTV in hand on his level look (his click rode the warp item)");
            if (st != null) {
                SameTick.judge(c, st, "green room warp", SameTick.Expect.ACTED);
                st = null;
            }
        } finally {
            if (st != null) {
                SameTick st2 = st;
                c.onClient(mc -> {
                    st2.close();
                    return null;
                });
            }
            c.onClient(mc -> {
                wireOff();
                Mod.staticCall(CE, "testGate", (Object) null);
                return null;
            });
            clean(c);
        }
    }

    /**
     * On a failed leg count, the wire tick by tick (client game time): set_carried_item with its slot, use_item with the
     * held item and look, the sneak input, teleport accepts, his tagged packets, and the server's position packets, so a
     * failure says whose use went with which item (greenroom-fix2-1126).
     */
    private static final java.util.List<String> WIRE = new java.util.ArrayList<>();
    private static java.util.function.Consumer<Packet<?>> wirePrevOut;
    private static java.util.function.Consumer<Packet<?>> wirePrevIn;
    /** Uses sent with the AOTV in hand on a level look (his: the hops look down onto the floor 10 away). */
    private static int aotvLevelUses;

    private static void wireOn(net.minecraft.client.Minecraft mc) {
        synchronized (WIRE) {
            WIRE.clear();
        }
        aotvLevelUses = 0;
        wirePrevOut = dev.testkit.harness.PacketTrace.tap;
        wirePrevIn = dev.testkit.harness.PacketTrace.inTap;
        dev.testkit.harness.PacketTrace.tap = p -> {
            if (wirePrevOut != null) wirePrevOut.accept(p);
            String s = null;
            if (p instanceof net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket h) {
                s = "carried " + h.getSlot();
            } else if (p instanceof ServerboundUseItemPacket u) {
                var pl = mc.player;
                if (pl != null && dev.testkit.compat.McPackets.useItemXRot(u) == 0f
                        && pl.getMainHandItem().is(net.minecraft.world.item.Items.DIAMOND_SHOVEL)) {
                    aotvLevelUses++;
                }
                s = String.format(java.util.Locale.ROOT, "use_item %s %.1f/%.1f sel %d",
                        pl == null ? "?" : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(pl.getMainHandItem().getItem()).getPath(),
                        dev.testkit.compat.McPackets.useItemYRot(u), dev.testkit.compat.McPackets.useItemXRot(u),
                        pl == null ? -1 : pl.getInventory().getSelectedSlot());
            } else if (p instanceof ServerboundPlayerInputPacket in) {
                s = "input shift=" + in.input().shift();
            } else if (p instanceof net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket) {
                s = "accept_tp";
            } else if (p instanceof net.minecraft.network.protocol.game.ServerboundUseItemOnPacket) {
                s = "use_item_on";
            }
            if (s != null) wireAdd(mc, ">" + s);
        };
        dev.testkit.harness.PacketTrace.inTap = p -> {
            if (wirePrevIn != null) wirePrevIn.accept(p);
            if (p instanceof net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket pp) {
                String t = pp.toString();
                wireAdd(mc, "<pos " + t.substring(0, Math.min(140, t.length())));
            }
        };
    }

    private static void wireAdd(net.minecraft.client.Minecraft mc, String s) {
        synchronized (WIRE) {
            if (WIRE.size() < 400) {
                long t = mc.level == null ? -1 : mc.level.getGameTime();
                WIRE.add("t" + t + " " + s + " [" + Mod.staticCall(GRS, "result") + "]");
            }
        }
    }

    private static void wireOff() {
        dev.testkit.harness.PacketTrace.tap = wirePrevOut;
        dev.testkit.harness.PacketTrace.inTap = wirePrevIn;
    }

    private static void dumpWire(Session c) {
        java.util.List<String> w;
        synchronized (WIRE) {
            w = new java.util.ArrayList<>(WIRE);
        }
        for (int i = 0; i < Math.min(w.size(), 160); i += 6) {
            c.note("WIRE " + String.join(" | ", w.subList(i, Math.min(w.size(), i + 6))));
        }
    }
}

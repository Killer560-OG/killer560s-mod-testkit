package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;

import java.net.InetSocketAddress;

/**
 * Mod staging-coopsim: on a server labeled {@code mc.hypixel.net} (the testkit's GrimAC server, a private 127.0.0.1
 * address) the co-op sim's guest side registers no {@code killer560smod:sim_*} channel and accepts nothing. The control:
 * the same connection relabeled with its bare address IS eligible, so the refusal is the label's, not the address's.
 */
final class HxCoop1109Cases {

    private static final String GUEST = "roomsim.SimGuest";

    private HxCoop1109Cases() {
    }

    static void register(Session s) {
        s.test("1109-coop-hypixel-refused", HxCoop1109Cases::refused);
    }

    private static void refused(Session c) {
        String channels = c.onClient(mc -> ClientPlayNetworking.getReceived().stream().map(Object::toString)
                .filter(id -> id.startsWith("killer560smod:sim_")).toList().toString());
        c.check("[]".equals(channels), "sim channels registered on a mc.hypixel.net server: " + channels);
        c.check((Integer) Mod.staticCall(GUEST, "acceptedForTest") == 0, "a sim hello was accepted on Hypixel");
        c.check(c.onClient(mc -> !(Boolean) Mod.staticCall("roomsim.SimState", "isGuest", mc)), "isGuest true on Hypixel");
        String[] r = c.onClient(mc -> {
            ClientPacketListener conn = mc.getConnection();
            ServerData data = conn.getServerData();
            boolean priv = conn.getConnection().getRemoteAddress() instanceof InetSocketAddress isa
                    && (Boolean) Mod.staticCall(GUEST, "privateAddress", isa.getAddress());
            boolean asLabeled = (Boolean) Mod.staticCall(GUEST, "eligible", mc, conn);
            String label = data.ip;
            data.ip = "127.0.0.1";
            boolean bare;
            try {
                bare = (Boolean) Mod.staticCall(GUEST, "eligible", mc, conn);
            } finally {
                data.ip = label;
            }
            return new String[]{label, Boolean.toString(priv), Boolean.toString(asLabeled), Boolean.toString(bare)};
        });
        c.check(r[0].contains("hypixel.net"), "the session's label is not Hypixel's: " + r[0]);
        c.check("true".equals(r[1]), "the test server's address is not private, so the control means nothing");
        c.check("false".equals(r[2]), "eligible() is true for " + r[0]);
        c.check("true".equals(r[3]), "control: eligible() false for the same connection labeled 127.0.0.1");
    }
}

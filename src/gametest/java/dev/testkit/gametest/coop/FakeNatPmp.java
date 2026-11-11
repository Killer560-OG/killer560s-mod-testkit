package dev.testkit.gametest.coop;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A stand-in NAT-PMP (RFC 6886) / PCP (RFC 6887) gateway on loopback for the co-op sim's port mapping (mod
 * roomsim/SimUpnp, coopfree-1111). It answers the public address request and TCP map requests; {@link #pcpOnly} makes it
 * answer NAT-PMP with UNSUPP_VERSION and serve PCP MAP instead. It maps every port to {@link #EXTERNAL_PORT_OFFSET} above
 * itself, so a test sees the mod take the router's external port. Point the mod at it with
 * {@code killer560.simcoop.natpmp=127.0.0.1:}{@link #port}. Never touches a real router.
 */
public final class FakeNatPmp {

    public static final int EXTERNAL_PORT_OFFSET = 7;
    private static final byte[] EXTERNAL = {(byte) 203, 0, 113, 7};
    private final DatagramSocket udp;
    private final List<String> requests = new ArrayList<>();
    public volatile boolean pcpOnly;

    public FakeNatPmp() throws IOException {
        udp = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        Thread t = new Thread(this::answer, "testkit-fake-natpmp");
        t.setDaemon(true);
        t.start();
    }

    public int port() { return udp.getLocalPort(); }

    /** "pmp-addr", "pmp-map PORT LIFETIME", "pcp-map PORT LIFETIME" or "pmp-unsupp", in order. */
    public List<String> requests() {
        synchronized (requests) {
            return new ArrayList<>(requests);
        }
    }

    public void stop() { udp.close(); }

    private void log(String s) {
        synchronized (requests) {
            requests.add(s);
        }
    }

    private void answer() {
        byte[] buf = new byte[1100];
        while (!udp.isClosed()) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                udp.receive(p);
                ByteBuffer in = ByteBuffer.wrap(buf, 0, p.getLength());
                byte[] out;
                if (buf[0] == 0 && pcpOnly) {
                    log("pmp-unsupp");
                    out = ByteBuffer.allocate(8).put((byte) 0).put((byte) (128 + buf[1])).putShort((short) 1).putInt(1).array();
                } else if (buf[0] == 0 && buf[1] == 0) {
                    log("pmp-addr");
                    out = ByteBuffer.allocate(12).put((byte) 0).put((byte) 128).putShort((short) 0).putInt(1).put(EXTERNAL).array();
                } else if (buf[0] == 0 && buf[1] == 2) {
                    int internal = in.getShort(4) & 0xffff, life = in.getInt(8);
                    log("pmp-map " + internal + " " + life);
                    out = ByteBuffer.allocate(16).put((byte) 0).put((byte) 130).putShort((short) 0).putInt(1)
                            .putShort((short) internal).putShort((short) (life == 0 ? 0 : internal + EXTERNAL_PORT_OFFSET))
                            .putInt(life).array();
                } else if (buf[0] == 2 && buf[1] == 1 && p.getLength() >= 60) {
                    int internal = in.getShort(40) & 0xffff, life = in.getInt(4);
                    log("pcp-map " + internal + " " + life);
                    ByteBuffer o = ByteBuffer.allocate(60).put((byte) 2).put((byte) 0x81).put((byte) 0).put((byte) 0)
                            .putInt(life).putInt(1).put(new byte[12]).put(buf, 24, 12).put((byte) 6).put(new byte[3])
                            .putShort((short) internal).putShort((short) (internal + EXTERNAL_PORT_OFFSET));
                    o.put(new byte[10]).put((byte) 0xff).put((byte) 0xff).put(EXTERNAL);
                    out = o.array();
                } else {
                    continue;
                }
                udp.send(new DatagramPacket(out, out.length, p.getSocketAddress()));
            } catch (IOException e) {
                return;
            }
        }
    }
}

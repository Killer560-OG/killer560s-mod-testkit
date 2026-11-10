package dev.testkit.gametest.coop;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A stand-in UPnP internet gateway on loopback for the co-op sim's port mapping (mod roomsim/SimUpnp): it answers the
 * M-SEARCH on a UDP port, serves a device description with a WANIPConnection service, and answers AddPortMapping,
 * GetExternalIPAddress and DeletePortMapping. Point the mod at it with {@code killer560.simcoop.ssdp=127.0.0.1:}{@link #ssdpPort}.
 * {@link #refuse} makes AddPortMapping fail with UPnP error 718; {@link #silent} makes the UDP side never answer.
 * Never touches a real router.
 */
public final class FakeGateway {

    public static final String EXTERNAL_IP = "203.0.113.7";
    /** The address GetExternalIPAddress answers; 127.0.0.1 when a test must really connect to the "public" endpoint. */
    public static volatile String externalIp = EXTERNAL_IP;

    private final DatagramSocket udp;
    private final HttpServer http;
    private final List<String> actions = new ArrayList<>();
    public volatile boolean refuse, silent;

    public FakeGateway() throws IOException {
        udp = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/desc.xml", ex -> reply(ex, 200, "<?xml version=\"1.0\"?><root><device><serviceList><service>"
                + "<serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>"
                + "<controlURL>/ctl</controlURL></service></serviceList></device></root>"));
        http.createContext("/ctl", ex -> {
            String action = String.valueOf(ex.getRequestHeaders().getFirst("SOAPAction"));
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            action = action.substring(action.indexOf('#') + 1).replace("\"", "");
            synchronized (actions) {
                actions.add(action + " " + tag(body, "NewExternalPort"));
            }
            if (action.equals("AddPortMapping") && refuse) {
                reply(ex, 500, "<s:Envelope><s:Body><s:Fault><detail><UPnPError><errorCode>718</errorCode>"
                        + "<errorDescription>ConflictInMappingEntry</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>");
            } else if (action.equals("GetExternalIPAddress")) {
                reply(ex, 200, "<s:Envelope><s:Body><u:GetExternalIPAddressResponse><NewExternalIPAddress>" + externalIp
                        + "</NewExternalIPAddress></u:GetExternalIPAddressResponse></s:Body></s:Envelope>");
            } else {
                reply(ex, 200, "<s:Envelope><s:Body/></s:Envelope>");
            }
        });
        http.start();
        Thread t = new Thread(this::answer, "testkit-fake-gateway");
        t.setDaemon(true);
        t.start();
    }

    public int ssdpPort() { return udp.getLocalPort(); }

    /** "Action externalPort" for every SOAP call, in order. */
    public List<String> actions() {
        synchronized (actions) {
            return new ArrayList<>(actions);
        }
    }

    public void stop() {
        udp.close();
        http.stop(0);
    }

    private void answer() {
        byte[] buf = new byte[2048];
        while (!udp.isClosed()) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                udp.receive(p);
                if (silent) continue;
                byte[] out = ("HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\nLOCATION: http://127.0.0.1:"
                        + http.getAddress().getPort() + "/desc.xml\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
                udp.send(new DatagramPacket(out, out.length, p.getSocketAddress()));
            } catch (IOException e) {
                return;
            }
        }
    }

    private static String tag(String xml, String tag) {
        int a = xml.indexOf("<" + tag + ">");
        int b = xml.indexOf("</" + tag + ">");
        return a < 0 || b < a ? "" : xml.substring(a + tag.length() + 2, b);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/xml");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(b);
        }
    }
}

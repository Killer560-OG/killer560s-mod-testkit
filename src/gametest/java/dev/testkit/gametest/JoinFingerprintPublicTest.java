package dev.testkit.gametest;

/**
 * 65-smoke-join-fingerprint-public: the same join and probe as {@link JoinFingerprintTest}, with the loopback server
 * labeled as a public, non-Hypixel host. The co-op sim registers its payload channels only for a private address, so
 * here (a public name reached over loopback, like a VPN or proxy route) {@code minecraft:register} must still match the
 * no-mod baseline (nofingerprint-1115).
 */
public class JoinFingerprintPublicTest extends JoinFingerprintTest {

    public JoinFingerprintPublicTest() {
        super("65-smoke-join-fingerprint-public", "play.example.net");
    }
}

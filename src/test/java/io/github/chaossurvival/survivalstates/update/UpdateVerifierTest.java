package io.github.chaossurvival.survivalstates.update;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/** 更新验证测试：坏哈希、坏签名、截断、篡改。 */
class UpdateVerifierTest {

    private final UpdateVerifier verifier = new UpdateVerifier();

    private KeyPair keyPair() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("Ed25519");
        return g.generateKeyPair();
    }

    private String pubB64(PublicKey k) {
        return Base64.getEncoder().encodeToString(k.getEncoded());
    }

    private UpdateVerifier.Manifest signedManifest(PrivateKey priv, String sha256, long size) throws Exception {
        UpdateVerifier.Manifest base = new UpdateVerifier.Manifest(
                1, "1.21.1", 2329, "1.0.1", "stable",
                "survival-states-1.0.1.jar", "https://github.com/x/y/releases/a.jar",
                sha256, size, "");
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(priv);
        signer.update(verifier.canonical(base));
        String sig = Base64.getEncoder().encodeToString(signer.sign());
        return new UpdateVerifier.Manifest(
                1, "1.21.1", 2329, "1.0.1", "stable",
                base.artifact(), base.downloadUrl(), sha256, size, sig);
    }

    @Test
    void 哈希验证正确与错误() {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        String correct = verifier.sha256(data);
        assertTrue(verifier.verifySha256(data, correct).success());
        assertFalse(verifier.verifySha256(data, "00".repeat(32)).success());
        assertFalse(verifier.verifySha256(data, "").success());
    }

    @Test
    void 平台校验() throws Exception {
        KeyPair kp = keyPair();
        var good = signedManifest(kp.getPrivate(), "ab".repeat(32), 100);
        assertTrue(verifier.verifyPlatform(good).success());
        var badMc = new UpdateVerifier.Manifest(1, "1.21.4", 2329, "1.0.1", "stable",
                good.artifact(), good.downloadUrl(), good.sha256(), 100, good.signature());
        assertFalse(verifier.verifyPlatform(badMc).success());
        var badBuild = new UpdateVerifier.Manifest(1, "1.21.1", 9999, "1.0.1", "stable",
                good.artifact(), good.downloadUrl(), good.sha256(), 100, good.signature());
        assertFalse(verifier.verifyPlatform(badBuild).success());
    }

    @Test
    void 签名验证正确与坏签名() throws Exception {
        KeyPair kp = keyPair();
        PublicKey pub = verifier.loadPublicKey(pubB64(kp.getPublic()));
        var good = signedManifest(kp.getPrivate(), "ab".repeat(32), 100);
        assertTrue(verifier.verifySignature(good, pub).success());

        // 篡改 artifact 后签名应失败。
        var tampered = new UpdateVerifier.Manifest(1, "1.21.1", 2329, "1.0.1", "stable",
                "evil.jar", good.downloadUrl(), good.sha256(), 100, good.signature());
        assertFalse(verifier.verifySignature(tampered, pub).success());
    }

    @Test
    void 截断签名视为失败() throws Exception {
        KeyPair kp = keyPair();
        PublicKey pub = verifier.loadPublicKey(pubB64(kp.getPublic()));
        var good = signedManifest(kp.getPrivate(), "ab".repeat(32), 100);
        String truncated = good.signature().substring(0, 8);
        var bad = new UpdateVerifier.Manifest(1, "1.21.1", 2329, "1.0.1", "stable",
                good.artifact(), good.downloadUrl(), good.sha256(), 100, truncated);
        assertFalse(verifier.verifySignature(bad, pub).success());
    }

    @Test
    void 大小校验() {
        assertTrue(verifier.verifySize(100, 100).success());
        assertFalse(verifier.verifySize(99, 100).success());
    }
}

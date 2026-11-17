package io.github.chaossurvival.survivalstates.update;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 更新包验证器：SHA-256 防传输损坏，Ed25519 签名建立发布者身份。
 *
 * <p>SHA-256 只防损坏；签名才验证发布者。两者缺一不可。签名覆盖
 * canonical manifest（去除 signature 字段后按固定字段顺序拼接）。
 * 所有 IO/哈希在异步线程完成，本类为纯验证逻辑，便于单测。</p>
 */
public final class UpdateVerifier {

    /** 更新清单模型。 */
    public record Manifest(
            int manifestVersion,
            String minecraftVersion,
            int purpurBuild,
            String pluginVersion,
            String releaseChannel,
            String artifact,
            String downloadUrl,
            String sha256,
            long sizeBytes,
            String signature
    ) {
    }

    /** 验证结果。字段名为 success（避免与静态工厂 ok() 冲突）。 */
    public record VerifyResult(boolean success, String reason) {
        public static VerifyResult ok() {
            return new VerifyResult(true, "OK");
        }

        public static VerifyResult fail(String reason) {
            return new VerifyResult(false, reason);
        }

        /** 供调用方以 ok() 语义查询是否成功。 */
        public boolean isOk() {
            return success;
        }
    }

    /** 计算字节数组的 SHA-256 十六进制串。 */
    public String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("无 SHA-256", e);
        }
    }

    /** 校验文件哈希。 */
    public VerifyResult verifySha256(byte[] bytes, String expected) {
        if (expected == null || expected.isBlank()) {
            return VerifyResult.fail("清单缺少 sha256");
        }
        String actual = sha256(bytes);
        return actual.equalsIgnoreCase(expected.trim())
                ? VerifyResult.ok()
                : VerifyResult.fail("SHA-256 不匹配：期望 " + expected + " 实际 " + actual);
    }

    /** 校验文件大小。 */
    public VerifyResult verifySize(int size, long expected) {
        return size == expected ? VerifyResult.ok()
                : VerifyResult.fail("大小不匹配：期望 " + expected + " 实际 " + size);
    }

    /**
     * 构造 canonical manifest 字节串：固定字段顺序拼接，排除 signature。
     * 发布端必须用完全相同的算法签名。
     */
    public byte[] canonical(Manifest m) {
        String s = String.join("\n",
                "manifestVersion=" + m.manifestVersion(),
                "minecraftVersion=" + m.minecraftVersion(),
                "purpurBuild=" + m.purpurBuild(),
                "pluginVersion=" + m.pluginVersion(),
                "releaseChannel=" + m.releaseChannel(),
                "artifact=" + m.artifact(),
                "downloadUrl=" + m.downloadUrl(),
                "sha256=" + m.sha256(),
                "sizeBytes=" + m.sizeBytes()
        );
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** 从 Base64 编码的 X.509 SubjectPublicKeyInfo 加载 Ed25519 公钥。 */
    public PublicKey loadPublicKey(String base64) throws GeneralSecurityException {
        try {
            byte[] der = Base64.getDecoder().decode(base64.trim());
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        } catch (InvalidKeySpecException | IllegalArgumentException e) {
            throw new GeneralSecurityException("公钥格式非法", e);
        }
    }

    /** 验证 detached Ed25519 签名。 */
    public VerifyResult verifySignature(Manifest m, PublicKey publicKey) {
        if (m.signature() == null || m.signature().isBlank()) {
            return VerifyResult.fail("清单缺少签名");
        }
        if (publicKey == null) {
            return VerifyResult.fail("未配置公钥");
        }
        try {
            byte[] sig = Base64.getDecoder().decode(m.signature().trim());
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(canonical(m));
            return verifier.verify(sig) ? VerifyResult.ok()
                    : VerifyResult.fail("Ed25519 签名验证失败");
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return VerifyResult.fail("签名验证异常: " + e.getMessage());
        }
    }

    /** 平台兼容性检查：只接受 1.21.1 / build 2329。 */
    public VerifyResult verifyPlatform(Manifest m) {
        if (!"1.21.1".equals(m.minecraftVersion())) {
            return VerifyResult.fail("MC 版本不匹配: " + m.minecraftVersion());
        }
        if (m.purpurBuild() != 2329) {
            return VerifyResult.fail("Purpur build 不匹配: " + m.purpurBuild());
        }
        return VerifyResult.ok();
    }
}

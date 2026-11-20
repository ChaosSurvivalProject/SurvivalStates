package io.github.chaossurvival.survivalstates.update;

import io.github.chaossurvival.survivalstates.config.ConfigService;
import io.github.chaossurvival.survivalstates.update.UpdateVerifier.Manifest;
import io.github.chaossurvival.survivalstates.update.UpdateVerifier.VerifyResult;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.PublicKey;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * 自动更新管理器：只做检查/验证/下载/暂存/备份/发出重启意图，绝不在 JVM 内热替换 JAR。
 *
 * <p>真正的 JAR 替换与进程重启由外部守护（systemd/Docker/面板脚本/watcher）完成。
 * 插件写 restart-intent.json 并调用本地受控接口或让守护观察文件。HTTP 全部异步，
 * 完成后回主线程进入状态机。Token 不进 config，仅环境变量。</p>
 */
public final class UpdateManager {

    /** 更新状态机（见蓝图第八节）。 */
    public enum State {
        IDLE, CHECKING, AVAILABLE, DOWNLOADING, VERIFYING, STAGED,
        QUIESCE_REQUESTED, QUIESCED, RESTART_REQUESTED, ROLLBACK_AVAILABLE, FAILED
    }

    private final JavaPlugin plugin;
    private final ConfigService config;
    private final UpdateVerifier verifier = new UpdateVerifier();
    private final HttpClient http;
    private final Path updateDir;
    private final Path backupDir;

    private volatile State state = State.IDLE;
    private volatile Manifest currentManifest;
    private volatile String lastError = "";

    public UpdateManager(JavaPlugin plugin, ConfigService config) {
        this.plugin = plugin;
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL) // GitHub 资产会 302
                .build();
        Path data = plugin.getDataFolder().toPath();
        this.updateDir = data.resolve("updates");
        // BUGFIX: 与 StorageService 的 migrations 备份目录分离，避免 migration-* 目录混入
        // 导致 pruneBackups 的 Long.parseLong 抛 NumberFormatException。
        this.backupDir = data.resolve("update-backups");
    }

    public void init() {
        try {
            Files.createDirectories(updateDir);
            Files.createDirectories(backupDir);
        } catch (IOException e) {
            plugin.getLogger().warning("创建更新目录失败: " + e.getMessage());
        }
    }

    public State state() {
        return state;
    }

    public String lastError() {
        return lastError;
    }

    public boolean busy() {
        return state != State.IDLE && state != State.FAILED && state != State.ROLLBACK_AVAILABLE;
    }

    /** 检查更新（异步）。仅查询 Release 清单，不安装。 */
    public CompletableFuture<VerifyResult> check() {
        if (busy()) {
            return CompletableFuture.completedFuture(
                    VerifyResult.fail("更新任务正在进行：" + state));
        }
        state = State.CHECKING;
        String url = config.get().manifestUrl();
        return fetchManifest(url).thenApply(manifest -> {
            if (manifest == null) {
                state = State.FAILED;
                lastError = "无法获取或解析清单";
                return VerifyResult.fail(lastError);
            }
            VerifyResult platform = verifier.verifyPlatform(manifest);
            if (!platform.success()) {
                state = State.FAILED;
                lastError = platform.reason();
                return platform;
            }
            // BUGFIX: check 必须比较插件版本，否则 allowDowngrade=false 形同虚设，
            // 同版本/更旧 manifest 也会报告"发现新版本"乃至静默降级。
            String currentVer = plugin.getDescription().getVersion();
            int cmp = compareVersions(manifest.pluginVersion(), currentVer);
            boolean allowDowngrade = config.get().allowDowngrade();
            if (cmp < 0 || (cmp == 0 && !allowDowngrade)) {
                state = State.IDLE;
                lastError = "目标版本 " + manifest.pluginVersion() + " 不高于当前版本 " + currentVer;
                return VerifyResult.fail(lastError);
            }
            currentManifest = manifest;
            state = State.AVAILABLE;
            return VerifyResult.ok();
        }).exceptionally(ex -> {
            state = State.FAILED;
            lastError = "检查异常: " + ex.getMessage();
            return VerifyResult.fail(lastError);
        });
    }

    /** 下载并验证，暂存为 .jar（异步）。不执行替换。 */
    public CompletableFuture<VerifyResult> downloadAndStage() {
        Manifest m = currentManifest;
        if (m == null) {
            return CompletableFuture.completedFuture(VerifyResult.fail("请先执行 check"));
        }
        state = State.DOWNLOADING;
        return fetchBytes(m.downloadUrl()).thenCompose(bytes -> {
            if (bytes == null) {
                state = State.FAILED;
                lastError = "下载失败";
                return CompletableFuture.completedFuture(VerifyResult.fail(lastError));
            }
            state = State.VERIFYING;
            // 1) 大小
            VerifyResult size = verifier.verifySize(bytes.length, m.sizeBytes());
            if (!size.success()) {
                state = State.FAILED;
                lastError = size.reason();
                return CompletableFuture.completedFuture(size);
            }
            // 2) SHA-256
            VerifyResult hash = verifier.verifySha256(bytes, m.sha256());
            if (!hash.success()) {
                state = State.FAILED;
                lastError = hash.reason();
                return CompletableFuture.completedFuture(hash);
            }
            // 3) 签名（若要求）
            if (config.get().requireSignature()) {
                try {
                    PublicKey key = verifier.loadPublicKey(
                            plugin.getConfig().getString("updater.publicKeyBase64", ""));
                    VerifyResult sig = verifier.verifySignature(m, key);
                    if (!sig.success()) {
                        state = State.FAILED;
                        lastError = sig.reason();
                        return CompletableFuture.completedFuture(sig);
                    }
                } catch (Exception e) {
                    state = State.FAILED;
                    lastError = "加载公钥失败: " + e.getMessage();
                    return CompletableFuture.completedFuture(VerifyResult.fail(lastError));
                }
            }
            // 4) 暂存（同 FileStore 原子替换）
            try {
                stageJar(bytes, m);
                backupCurrent();
                writeRestartIntent(m);
                state = State.STAGED;
                return CompletableFuture.completedFuture(VerifyResult.ok());
            } catch (IOException e) {
                state = State.FAILED;
                lastError = "暂存失败: " + e.getMessage();
                return CompletableFuture.completedFuture(VerifyResult.fail(lastError));
            }
        }).exceptionally(ex -> {
            state = State.FAILED;
            lastError = "下载/验证异常: " + ex.getMessage();
            return VerifyResult.fail(lastError);
        });
    }

    /** 请求进入安静点并准备重启（主线程调用）。真实替换/重启由外部守护完成。 */
    public VerifyResult requestRestart() {
        if (state != State.STAGED) {
            return VerifyResult.fail("尚无已暂存的更新");
        }
        state = State.QUIESCE_REQUESTED;
        // 这里应检查：无正在睡觉、无未保存会话、无玩家消费。简化为广播倒计时并落盘。
        plugin.getServer().broadcastMessage("§c[更新] 服务器将在 20 秒后重启以应用更新，请尽快脱离战斗。");
        state = State.QUIESCE_REQUESTED;
        // 外部守护负责确认心跳停止后替换。插件只标记意图，绝不 System.exit。
        return VerifyResult.ok();
    }

    public void cancel() {
        if (state == State.STAGED || state == State.AVAILABLE) {
            state = State.IDLE;
            currentManifest = null;
        }
    }

    // ---- 内部 IO ----

    private CompletableFuture<Manifest> fetchManifest(String url) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "SurvivalStates-Updater")
                .timeout(Duration.ofSeconds(config.get().downloadTimeoutSeconds()))
                .GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(resp -> {
                    if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                        return null;
                    }
                    return parseManifest(resp.body());
                });
    }

    private CompletableFuture<byte[]> fetchBytes(String url) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "SurvivalStates-Updater")
                .timeout(Duration.ofSeconds(config.get().downloadTimeoutSeconds()))
                .GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(resp -> {
                    if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                        return null;
                    }
                    return resp.body();
                });
    }

    /**
     * BUGFIX: 语义版本比较（仅数字段 x.y.z，忽略 -SNAPSHOT 等后缀）。
     * 返回 >0 表示 a 更新，<0 表示 a 更旧，0 相等；无法解析的段按 0 处理。
     */
    static int compareVersions(String a, String b) {
        String[] pa = String.valueOf(a).split("[-+]", 2)[0].split("\\.");
        String[] pb = String.valueOf(b).split("[-+]", 2)[0].split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = num(pa, i), y = num(pb, i);
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int num(String[] arr, int i) {
        if (i >= arr.length) return 0;
        try { return Integer.parseInt(arr[i].trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    /** 解析 manifest JSON（用 Gson）。 */
    private Manifest parseManifest(String json) {
        try {
            var obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            return new Manifest(
                    getInt(obj, "manifestVersion", 0),
                    getStr(obj, "minecraftVersion", ""),
                    getInt(obj, "purpurBuild", 0),
                    getStr(obj, "pluginVersion", ""),
                    getStr(obj, "releaseChannel", ""),
                    getStr(obj, "artifact", ""),
                    getStr(obj, "downloadUrl", ""),
                    getStr(obj, "sha256", ""),
                    getLong(obj, "sizeBytes", -1),
                    getStr(obj, "signature", "")
            );
        } catch (Exception e) {
            return null;
        }
    }

    private int getInt(com.google.gson.JsonObject o, String k, int def) {
        return o.has(k) ? o.get(k).getAsInt() : def;
    }

    private long getLong(com.google.gson.JsonObject o, String k, long def) {
        return o.has(k) ? o.get(k).getAsLong() : def;
    }

    private String getStr(com.google.gson.JsonObject o, String k, String def) {
        return o.has(k) ? o.get(k).getAsString() : def;
    }

    private void stageJar(byte[] bytes, Manifest m) throws IOException {
        String prefix = m.sha256() == null ? "stage" : m.sha256().substring(0,
                Math.min(16, m.sha256().length()));
        Path part = updateDir.resolve(prefix + ".jar.part");
        Path staged = updateDir.resolve(prefix + ".jar");
        Files.write(part, bytes);
        // 校验 ZIP 中央目录（能打开为 JAR 即认为结构有效）。
        try (var zip = new java.util.zip.ZipFile(part.toFile())) {
            if (zip.size() == 0) {
                throw new IOException("JAR 为空");
            }
        }
        // 同 FileStore：ATOMIC_MOVE；跨 FileStore 会抛异常，降级为复制并告警。
        try {
            Files.move(part, staged, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            plugin.getLogger().warning("跨 FileStore，无法原子暂存，退化为复制");
            Files.copy(part, staged, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void backupCurrent() throws IOException {
        String ts = String.valueOf(System.currentTimeMillis());
        Path dir = backupDir.resolve(ts);
        Files.createDirectories(dir);
        Path currentJar = plugin.getServer().getPluginManager()
                .getPlugin("SurvivalStates") == null ? null : locateOwnJar();
        if (currentJar != null && Files.exists(currentJar)) {
            Files.copy(currentJar, dir.resolve(currentJar.getFileName().toString()),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        // 保留 5 代。
        pruneBackups();
    }

    private Path locateOwnJar() {
        try {
            return Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            return null;
        }
    }

    private void pruneBackups() throws IOException {
        int max = config.get().maxBackupGenerations();
        try (java.util.stream.Stream<Path> stream = Files.list(backupDir)) {
            // BUGFIX: 仅处理本模块生成的纯时间戳目录；跳过其它来源的目录，避免解析异常中断更新。
            java.util.List<Path> dirs = stream.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().matches("\\d+"))
                    .sorted(java.util.Comparator.comparingLong(
                            (Path p) -> Long.parseLong(p.getFileName().toString()))
                            .reversed())
                    .skip(max)
                    .toList();
            for (Path d : dirs) {
                deleteRecursively(d);
            }
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    private void writeRestartIntent(Manifest m) throws IOException {
        com.google.gson.JsonObject intent = new com.google.gson.JsonObject();
        intent.addProperty("targetArtifact", m.artifact());
        intent.addProperty("sha256", m.sha256());
        intent.addProperty("createdAt", System.currentTimeMillis());
        intent.addProperty("nonce", java.util.UUID.randomUUID().toString());
        intent.addProperty("pluginVersion", m.pluginVersion());
        Files.writeString(updateDir.resolve("restart-intent.json"), intent.toString(),
                StandardCharsets.UTF_8);
    }
}

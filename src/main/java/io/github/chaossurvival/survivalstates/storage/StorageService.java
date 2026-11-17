package io.github.chaossurvival.survivalstates.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.plugin.java.JavaPlugin;
import io.github.chaossurvival.survivalstates.state.MathUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 玩家存档存储：同目录 tmp → fsync → 原子替换。
 *
 * <p>写失败三次进入只读故障态；坏档隔离到 quarantined/；未知高版本只读隔离，
 * 不覆盖原文件。离线不按现实时间累积——存档只保存数值，不补算时间。</p>
 */
public final class StorageService {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final JavaPlugin plugin;
    private final Logger log;
    private final Path playerDataDir;
    private final Path quarantineDir;
    private final Path backupDir;

    private volatile boolean readOnlyFault = false;
    private int consecutiveFailures = 0;

    public StorageService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        Path data = plugin.getDataFolder().toPath();
        this.playerDataDir = data.resolve("playerdata");
        this.quarantineDir = data.resolve("quarantined");
        this.backupDir = data.resolve("backups");
    }

    public void init() throws IOException {
        Files.createDirectories(playerDataDir);
        Files.createDirectories(quarantineDir);
        Files.createDirectories(backupDir);
    }

    public boolean isReadOnlyFault() {
        return readOnlyFault;
    }

    /** 读取并迁移玩家存档；无档或损坏返回 null。 */
    public PlayerData load(UUID uuid) {
        Path file = playerDataDir.resolve(uuid + ".json");
        if (!Files.exists(file)) {
            return null;
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject json = JsonParser.parseString(content).getAsJsonObject();
            int version = json.has("schemaVersion") ? json.get("schemaVersion").getAsInt() : 1;

            if (!MigrationRegistry.isKnown(version)) {
                quarantine(file, uuid, "未知 schemaVersion=" + version);
                return null;
            }
            if (MigrationRegistry.needsMigration(version)) {
                backupOriginal(file, uuid);
                json = MigrationRegistry.migrateToLatest(json);
            }

            PlayerData data = GSON.fromJson(json, PlayerData.class);
            if (data == null || data.uuid == null) {
                quarantine(file, uuid, "解析后数据为空");
                return null;
            }
            // 校验和验证：坏档隔离，回退默认态。
            if (data.checksum != null && !data.checksum.isBlank()) {
                String expected = data.checksum;
                data.checksum = "";
                String canonical = GSON.toJson(data);
                data.checksum = expected;
                if (!expected.equals(Checksums.sha256(canonical))) {
                    log.warning("玩家 " + uuid + " 存档校验和不匹配，隔离并按默认态恢复");
                    quarantine(file, uuid, "checksum-mismatch");
                    return null;
                }
            }
            clampRanges(data);
            return data;
        } catch (Exception e) {
            log.warning("读取玩家 " + uuid + " 存档失败: " + e.getMessage());
            quarantine(file, uuid, "parse-error");
            return null;
        }
    }

    /** 原子保存单个玩家：tmp → fsync → 原子替换。 */
    public void save(PlayerData data) {
        if (readOnlyFault) {
            return;
        }
        Path target = playerDataDir.resolve(data.uuid + ".json");
        Path tmp = playerDataDir.resolve(data.uuid + ".json.tmp");
        try {
            // 计算校验和（排除 checksum 字段自身）。
            String expectedChecksum = data.checksum;
            data.checksum = "";
            String canonical = GSON.toJson(data);
            data.checksum = Checksums.sha256(canonical);
            String payload = GSON.toJson(data);
            data.checksum = expectedChecksum;

            Files.writeString(tmp, payload, StandardCharsets.UTF_8);
            // fsync：通过 FileChannel 强制落盘。
            try (var ch = java.nio.channels.FileChannel.open(tmp,
                    java.nio.file.StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // 同文件系统应支持原子替换；不支持时退化为普通替换并记录。
                log.warning("原子替换不可用，退化为普通替换: " + target);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            consecutiveFailures = 0;
        } catch (IOException e) {
            consecutiveFailures++;
            log.warning("保存玩家 " + data.uuid + " 失败（第 " + consecutiveFailures + " 次）: " + e.getMessage());
            if (consecutiveFailures >= 3) {
                readOnlyFault = true;
                log.severe("存档连续失败 3 次，进入只读故障态；请检查磁盘！");
            }
        }
    }

    private void clampRanges(PlayerData d) {
        d.hydration = MathUtil.clip100(d.hydration);
        d.fatigue = MathUtil.clip100(d.fatigue);
        d.temperature = MathUtil.clip100(d.temperature);
        d.activity = MathUtil.clip100(d.activity);
        d.quality = MathUtil.clip100(d.quality);
    }

    private void quarantine(Path file, UUID uuid, String reason) {
        try {
            Path dest = quarantineDir.resolve(uuid + ".json." + System.currentTimeMillis() + ".bad");
            Files.move(file, dest, StandardCopyOption.REPLACE_EXISTING);
            log.warning("玩家 " + uuid + " 坏档已隔离到 " + dest + "（原因: " + reason + "）");
        } catch (IOException e) {
            log.warning("隔离坏档失败: " + e.getMessage());
        }
    }

    private void backupOriginal(Path file, UUID uuid) {
        try {
            Path bdir = backupDir.resolve("migration-" + System.currentTimeMillis());
            Files.createDirectories(bdir);
            Files.copy(file, bdir.resolve(uuid + ".json"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warning("迁移前备份失败: " + e.getMessage());
        }
    }
}

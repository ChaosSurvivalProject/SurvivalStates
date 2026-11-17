package io.github.chaossurvival.survivalstates.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * 配置服务：载入 config.yml，范围检查，生成 config-resolved.yml。
 *
 * <p>数学模型配置只在启动时读取一次；安全重载只刷新消息与音效。
 * 非法数值回退到内置默认并记录 WARN，不静默纠正。</p>
 */
public final class ConfigService {

    private final JavaPlugin plugin;
    private final Logger log;
    private final File resolvedFile;

    // 解析后的配置快照（record 不可变）。
    private volatile Resolved resolved;
    private volatile Messages messages;

    public ConfigService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.resolvedFile = new File(plugin.getDataFolder(), "config-resolved.yml");
    }

    /** 不可变运行时配置快照。 */
    public record Resolved(
            int stateIntervalTicks,
            int environmentIntervalTicks,
            int persistenceIntervalSeconds,
            double maxIntegrationSeconds,
            double baseHydrationDrain,
            double baseFatigueGain,
            boolean failOnUnexpectedApi,
            int lowHealthEnter,
            int lowHealthExit,
            boolean updaterEnabled,
            String updaterRepository,
            String updaterChannel,
            String manifestUrl,
            String publicKeyFingerprint,
            boolean requireSignature,
            boolean requireSha256,
            int checkHours,
            int downloadTimeoutSeconds,
            int restartGraceSeconds,
            int maxBackupGenerations,
            boolean allowDowngrade
    ) {
    }

    /** 启动时载入并校验。 */
    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();

        Resolved r = new Resolved(
                rangeInt(c, "ticks.stateIntervalTicks", 20, 1, 200),
                rangeInt(c, "ticks.environmentIntervalTicks", 100, 1, 1200),
                rangeInt(c, "ticks.persistenceIntervalSeconds", 30, 5, 600),
                rangeDouble(c, "ticks.maxIntegrationSecondsPerTick", 0.25, 0.01, 1.0),
                Math.abs(rangeDouble(c, "hydration.baseDrainPerSecond", 100.0 / 7200, 1e-6, 1.0)),
                Math.abs(rangeDouble(c, "fatigue.baseGainPerSecond", 100.0 / 14400, 1e-6, 1.0)),
                c.getBoolean("platform.failOnUnexpectedApi", true),
                rangeInt(c, "attributes.lowHealthEnter", 6, 1, 19),
                rangeInt(c, "attributes.lowHealthExit", 8, 1, 20),
                c.getBoolean("updater.enabled", false),
                c.getString("updater.repository", "ChaosSurvivalProject/SurvivalStates"),
                c.getString("updater.channel", "stable"),
                c.getString("updater.manifestUrl", "https://example.invalid/releases/survival-manifest.json"),
                c.getString("updater.publicKeyFingerprint", "SHA256:..."),
                c.getBoolean("updater.requireSignature", true),
                c.getBoolean("updater.requireSha256", true),
                rangeInt(c, "updater.checkHours", 6, 1, 168),
                rangeInt(c, "updater.downloadTimeoutSeconds", 60, 5, 600),
                rangeInt(c, "updater.restartGraceSeconds", 20, 5, 300),
                rangeInt(c, "updater.maxBackupGenerations", 5, 1, 50),
                c.getBoolean("updater.allowDowngrade", false)
        );
        this.resolved = r;
        this.messages = Messages.from(c);
        writeResolved(r);
    }

    /** 安全重载：只刷新消息与音效，数学快照保持不变。 */
    public void reloadSafe() {
        plugin.reloadConfig();
        this.messages = Messages.from(plugin.getConfig());
    }

    public Resolved get() {
        return resolved;
    }

    public Messages getMessages() {
        return messages;
    }

    private void writeResolved(Resolved r) {
        try {
            YamlConfiguration out = new YamlConfiguration();
            out.set("apiVersion", 1);
            out.set("resolved.stateIntervalTicks", r.stateIntervalTicks());
            out.set("resolved.environmentIntervalTicks", r.environmentIntervalTicks());
            out.set("resolved.persistenceIntervalSeconds", r.persistenceIntervalSeconds());
            out.set("resolved.maxIntegrationSeconds", r.maxIntegrationSeconds());
            out.set("resolved.baseHydrationDrain", r.baseHydrationDrain());
            out.set("resolved.baseFatigueGain", r.baseFatigueGain());
            out.set("resolved.lowHealthEnter", r.lowHealthEnter());
            out.set("resolved.lowHealthExit", r.lowHealthExit());
            out.set("note", "本文件由插件自动生成；数学模型改动需重启服务器。");
            out.save(resolvedFile);
        } catch (IOException e) {
            log.warning("无法写入 config-resolved.yml: " + e.getMessage());
        }
    }

    private int rangeInt(FileConfiguration c, String path, int def, int min, int max) {
        int v = c.getInt(path, def);
        if (v < min || v > max) {
            log.warning("配置项 " + path + " = " + v + " 超出范围 [" + min + "," + max + "]，回退默认 " + def);
            return def;
        }
        return v;
    }

    private double rangeDouble(FileConfiguration c, String path, double def, double min, double max) {
        double v = c.getDouble(path, def);
        if (!Double.isFinite(v) || v < min || v > max) {
            log.warning("配置项 " + path + " = " + v + " 超出范围 [" + min + "," + max + "]，回退默认 " + def);
            return def;
        }
        return v;
    }
}

package io.github.chaossurvival.survivalstates;

import io.github.chaossurvival.survivalstates.command.SurvivalCommand;
import io.github.chaossurvival.survivalstates.config.ConfigService;
import io.github.chaossurvival.survivalstates.item.ItemService;
import io.github.chaossurvival.survivalstates.listener.PlayerListener;
import io.github.chaossurvival.survivalstates.service.*;
import io.github.chaossurvival.survivalstates.state.FoodSafety;
import io.github.chaossurvival.survivalstates.storage.StorageService;
import io.github.chaossurvival.survivalstates.ui.BossBarManager;
import io.github.chaossurvival.survivalstates.update.UpdateManager;
import io.github.chaossurvival.survivalstates.util.VersionProbe;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * SurvivalStates 主类：生命周期编排。
 *
 * <p>全程中文注释/消息/文档。启动做版本门禁，关键 API 不匹配时进入诊断模式，
 * 不启用状态积分（宁可少机制，不可卡玩家）。disable 时取消任务、移除 BossBar、
 * 清除属性、flush 保存，写 dirty-shutdown 标记。</p>
 */
public final class SurvivalStates extends JavaPlugin {

    private ConfigService config;
    private StorageService storage;
    private BossBarManager bossBars;
    private AttributeService attributes;
    private SoundService sounds;
    private StateService states;
    private RestService rest;
    private ItemService items;
    private FoodSafety foodSafety;
    private UpdateManager updater;
    private VersionProbe.Report probe;

    private int stateTaskId = -1;
    private int saveTaskId = -1;
    private boolean diagnosticMode = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // 1) 版本门禁。
        probe = VersionProbe.probe(this);
        for (String d : probe.diagnostics()) {
            getLogger().warning("[版本核验] " + d);
        }
        config = new ConfigService(this);
        config.load();

        if (!probe.allOk() && config.get().failOnUnexpectedApi()) {
            diagnosticMode = true;
            getLogger().severe("关键 API/版本不匹配，进入诊断模式：不启用状态积分。"
                    + "请使用 /survival doctor 查看细节。");
        }

        // 2) 存档。
        storage = new StorageService(this);
        try {
            storage.init();
        } catch (Exception e) {
            getLogger().severe("存档目录初始化失败: " + e.getMessage());
        }

        // 3) 各服务。
        bossBars = new BossBarManager(this);
        attributes = new AttributeService(this);
        sounds = new SoundService(this, getConfig().getBoolean("sounds.enabled", true));
        states = new StateService(this, config, bossBars, attributes, sounds);
        rest = new RestService(this, config, states, sounds);
        items = new ItemService(this);
        foodSafety = new FoodSafety(readFoodTuning());
        updater = new UpdateManager(this, config);
        updater.init();

        // 4) 命令与事件。
        var cmd = getCommand("survival");
        SurvivalCommand executor = new SurvivalCommand(
                this, config, states, rest, items, storage, updater, probe);
        if (cmd != null) {
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }
        getServer().getPluginManager().registerEvents(
                new PlayerListener(this, config, states, rest, items, foodSafety, sounds, storage), this);

        // 5) 调度：每个 tick 调一次积分（内部按真实 dt 夹取）；周期保存。
        if (!diagnosticMode) {
            stateTaskId = Bukkit.getScheduler().runTaskTimer(this, () -> {
                states.tick();
                rest.tickRest();
            }, 0L, 1L).getTaskId();
        }
        int saveTicks = Math.max(20, config.get().persistenceIntervalSeconds() * 20);
        saveTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            // 快照：构造不可变数据，交给异步线程保存，不碰 Bukkit 对象。
            var snapshot = new java.util.ArrayList<io.github.chaossurvival.survivalstates.storage.PlayerData>();
            for (var rt : states.all()) {
                snapshot.add(rt.toData());
            }
            for (var data : snapshot) {
                storage.save(data);
            }
        }, saveTicks, saveTicks).getTaskId();

        // 6) dirty shutdown 标记。
        writeDirtyFlag(true);
        getLogger().info("SurvivalStates v" + getDescription().getVersion()
                + " 已启用" + (diagnosticMode ? "（诊断模式）" : "")
                + " | 目标 MC 1.21.1 / PurPur 2329 / Java 21");
    }

    @Override
    public void onDisable() {
        // 取消任务。
        if (stateTaskId >= 0) {
            Bukkit.getScheduler().cancelTask(stateTaskId);
        }
        if (saveTaskId >= 0) {
            Bukkit.getScheduler().cancelTask(saveTaskId);
        }
        // 清除属性与 BossBar。
        if (attributes != null && bossBars != null) {
            for (var rt : states.all()) {
                Player p = Bukkit.getPlayer(rt.uuid());
                if (p != null) {
                    attributes.clear(p);
                    bossBars.detach(p);
                }
                if (storage != null) {
                    storage.save(rt.toData());
                }
            }
            bossBars.clearAll();
        }
        writeDirtyFlag(false);
        getLogger().info("SurvivalStates 已安全停用，状态已 flush。");
    }

    private FoodSafety.Tuning readFoodTuning() {
        return new FoodSafety.Tuning(
                getConfig().getDouble("foodSafety.suspiciousHarmChance", 0.50),
                getConfig().getBoolean("foodSafety.badAlwaysHarmful", true),
                getConfig().getInt("foodSafety.nauseaSeconds", 30),
                getConfig().getInt("foodSafety.hungerSeconds", 30),
                // BUGFIX: 原第 5 参误读 nauseaSeconds，导致坏水中毒时长被反胃配置错误耦合；
                // 无独立配置项时复用 hungerSeconds（与坏水 HUNGER 分支一致）。
                getConfig().getInt("foodSafety.hungerSeconds", 30)
        );
    }

    private void writeDirtyFlag(boolean dirty) {
        try {
            var f = new java.io.File(getDataFolder(), "dirty-shutdown.flag");
            if (!getDataFolder().exists()) {
                getDataFolder().mkdirs();
            }
            if (dirty) {
                java.nio.file.Files.writeString(f.toPath(),
                        "上次关闭非正常：若为插件崩溃导致，请检查 playerdata。");
            } else {
                java.nio.file.Files.deleteIfExists(f.toPath());
            }
        } catch (Exception ignored) {
        }
    }
}

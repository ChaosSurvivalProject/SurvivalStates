package io.github.chaossurvival.survivalstates.ui;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * BossBar 显示：默认最多三条（水分、疲劳、温度/状态摘要），不逐 tick 重建。
 *
 * <p>文案只在跨层或每 5 秒变化；进服、维度切换、重连后重新附加。
 * 异常时关闭 UI 但保留积分逻辑。</p>
 */
public final class BossBarManager {

    private static final int MAX_BARS = 3;

    private final JavaPlugin plugin;

    public BossBarManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** 三条状态条，按玩家保存。 */
    private final java.util.Map<java.util.UUID, Bars> bars = new java.util.HashMap<>();

    private record Bars(BossBar hydration, BossBar fatigue, BossBar summary) {
    }

    /** 进服/重连后附加。 */
    public void attach(Player player) {
        try {
            Bars existing = bars.get(player.getUniqueId());
            if (existing != null) {
                existing.hydration.addPlayer(player);
                existing.fatigue.addPlayer(player);
                existing.summary.addPlayer(player);
                return;
            }
            BossBar h = create("§b水分", 1.0, BarColor.BLUE);
            BossBar f = create("§e疲劳", 0.0, BarColor.YELLOW);
            BossBar s = create("§a状态", 0.5, BarColor.GREEN);
            h.addPlayer(player);
            f.addPlayer(player);
            s.addPlayer(player);
            bars.put(player.getUniqueId(), new Bars(h, f, s));
        } catch (Throwable t) {
            plugin.getLogger().warning("BossBar 附加失败: " + t.getMessage());
        }
    }

    /** 更新进度与文案（调用方控制频率 ≤5Hz）。 */
    public void update(Player player, double hydration, double fatigue,
                       double temperature, String summaryTitle) {
        Bars b = bars.get(player.getUniqueId());
        if (b == null) {
            return;
        }
        try {
            b.hydration.setProgress(clamp(hydration / 100.0));
            b.fatigue.setProgress(clamp(fatigue / 100.0));
            // 温度以 50 为中性映射到 0–1。
            b.summary.setProgress(clamp(temperature / 100.0));
            b.summary.setTitle(summaryTitle);
        } catch (Throwable t) {
            detach(player);
        }
    }

    /** 离服移除玩家（保留条对象以便重连复用）。 */
    public void detach(Player player) {
        Bars b = bars.get(player.getUniqueId());
        if (b != null) {
            b.hydration.removePlayer(player);
            b.fatigue.removePlayer(player);
            b.summary.removePlayer(player);
        }
    }

    /** disable：移除全部条。 */
    public void clearAll() {
        for (Bars b : bars.values()) {
            b.hydration.removeAll();
            b.fatigue.removeAll();
            b.summary.removeAll();
        }
        bars.clear();
    }

    private BossBar create(String title, double progress, BarColor color) {
        BossBar bar = Bukkit.createBossBar(title, color, BarStyle.SEGMENTED_10);
        bar.setProgress(progress);
        bar.setVisible(true);
        return bar;
    }

    private double clamp(double v) {
        if (Double.isNaN(v)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, v));
    }
}

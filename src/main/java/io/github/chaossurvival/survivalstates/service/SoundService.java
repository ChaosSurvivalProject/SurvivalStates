package io.github.chaossurvival.survivalstates.service;

import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 音效服务：只使用 Sound 注册表键，启动时验证；全部走 PLAYERS 分类，可关闭。
 *
 * <p>配置里的音效名在首次使用时校验，非法名记录一次警告后静默跳过，不刷屏。</p>
 */
public final class SoundService {

    private final JavaPlugin plugin;
    private final boolean enabled;
    private final java.util.Set<String> warned = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public SoundService(JavaPlugin plugin, boolean enabled) {
        this.plugin = plugin;
        this.enabled = enabled;
    }

    /** 播放配置中的音效（名字映射见 config.sounds）。 */
    public void play(Player player, String logicalName) {
        if (!enabled) {
            return;
        }
        String soundName = switch (logicalName) {
            case "warning" -> plugin.getConfig().getString("sounds.warning", "BLOCK_NOTE_BLOCK_BASS");
            case "restStart" -> plugin.getConfig().getString("sounds.restStart", "BLOCK_WOOL_PLACE");
            case "drink" -> plugin.getConfig().getString("sounds.drink", "ENTITY_PLAYER_SPLASH");
            case "danger" -> plugin.getConfig().getString("sounds.danger", "ENTITY_PLAYER_HURT");
            default -> null;
        };
        if (soundName == null) {
            return;
        }
        try {
            Sound sound = Sound.valueOf(soundName);
            String categoryName = plugin.getConfig().getString("sounds.category", "PLAYERS");
            SoundCategory category = SoundCategory.valueOf(categoryName);
            player.playSound(player.getLocation(), sound, category, 0.7f, 1.0f);
        } catch (IllegalArgumentException e) {
            if (warned.add(soundName)) {
                plugin.getLogger().warning("无效音效名，已跳过: " + soundName);
            }
        }
    }
}

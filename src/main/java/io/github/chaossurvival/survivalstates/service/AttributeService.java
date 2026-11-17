package io.github.chaossurvival.survivalstates.service;

import io.github.chaossurvival.survivalstates.state.MathUtil;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 属性惩罚服务：只通过固定 NamespacedKey 的小幅乘算修饰符施加“疲劳惩罚”，
 * 绝不 setBaseValue、不叠加药水、不直接削血。
 *
 * <p>每 20 tick 与目标比较，差 &lt;0.005 不更新；先移除同键旧值再加入新值。
 * 1.21.1 常量使用 GENERIC_/PLAYER_ 前缀。生命 &le;6 时停止惩罚（低血保护）。</p>
 */
public final class AttributeService {

    private final JavaPlugin plugin;
    private final NamespacedKey keyAttack;
    private final NamespacedKey keySpeed;
    private final NamespacedKey keyMining;

    public AttributeService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.keyAttack = new NamespacedKey(plugin, "fatigue_attack");
        this.keySpeed = new NamespacedKey(plugin, "fatigue_speed");
        this.keyMining = new NamespacedKey(plugin, "fatigue_mining");
    }

    /** 施加/更新疲劳属性惩罚。lowHealthProtected 或创造/旁观时应清除。 */
    public void apply(Player player, double fatigue, boolean lowHealthProtected) {
        org.bukkit.GameMode mode = player.getGameMode();
        if (lowHealthProtected
                || mode == org.bukkit.GameMode.CREATIVE
                || mode == org.bukkit.GameMode.SPECTATOR) {
            clear(player);
            return;
        }
        double f = MathUtil.smoothstep(fatigue, 55.0, 100.0);
        double attack = -0.22 * f;
        double speed = -0.15 * f;
        double mining = -0.18 * f;

        modify(player, Attribute.GENERIC_ATTACK_DAMAGE, keyAttack, attack);
        modify(player, Attribute.GENERIC_MOVEMENT_SPEED, keySpeed, speed);
        modify(player, Attribute.PLAYER_MINING_EFFICIENCY, keyMining, mining);
    }

    private void modify(Player player, Attribute attribute, NamespacedKey key, double target) {
        var attr = player.getAttribute(attribute);
        if (attr == null) {
            return;
        }
        // 找同键旧值，差 <0.005 不更新，避免逐 tick 改组件。
        double current = 0.0;
        for (AttributeModifier m : attr.getModifiers()) {
            if (key.equals(m.getKey())) {
                current = m.getAmount();
            }
        }
        if (Math.abs(current - target) < 0.005) {
            return;
        }
        attr.removeModifier(key);
        if (Math.abs(target) > 1e-9) {
            attr.addModifier(new AttributeModifier(
                    key, target, AttributeModifier.Operation.ADD_SCALAR));
        }
    }

    /** 清除本插件全部修饰符（退出、disable、创造/旁观时调用）。 */
    public void clear(Player player) {
        safeRemove(player, Attribute.GENERIC_ATTACK_DAMAGE, keyAttack);
        safeRemove(player, Attribute.GENERIC_MOVEMENT_SPEED, keySpeed);
        safeRemove(player, Attribute.PLAYER_MINING_EFFICIENCY, keyMining);
    }

    private void safeRemove(Player player, Attribute attribute, NamespacedKey key) {
        try {
            var attr = player.getAttribute(attribute);
            if (attr != null) {
                attr.removeModifier(key);
            }
        } catch (Throwable ignored) {
            // 属性访问异常时先移除插件键，进入安全模式。
        }
    }
}

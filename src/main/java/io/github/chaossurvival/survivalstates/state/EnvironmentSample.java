package io.github.chaossurvival.survivalstates.state;

/**
 * 环境采样输入：由 Bukkit 层每 5 秒采样一次并转成此纯数据对象。
 *
 * <p>原版没有玩家体温机制；这里所有字段服务于插件自定义的“体感等效温度”，
 * 不是原版机制，也不代表医学体温。</p>
 *
 * @param biomeTemperature 群系基础温度（Biome#getTemperature()）
 * @param y                玩家 Y 坐标
 * @param desertLike       沙漠类群系标签 0/1
 * @param nether           下界 0/1
 * @param coldBiome        寒冷群系标签 0/1
 * @param rain             是否下雨/处于雨中
 * @param directSun        是否阳光直射
 * @param fireNearby       附近是否有火/岩浆
 * @param water            是否接触水
 * @param armorHeat        保暖装备加成 0–1
 * @param crowding         附近实体拥挤度 0–1
 * @param worldEnabled     该世界是否启用温度模型
 * @param flatOverride     世界平坦覆盖温度（如下界 78），NaN 表示不覆盖
 */
public record EnvironmentSample(
        double biomeTemperature,
        double y,
        double desertLike,
        double nether,
        double coldBiome,
        double rain,
        double directSun,
        double fireNearby,
        double water,
        double armorHeat,
        double crowding,
        boolean worldEnabled,
        double flatOverride
) {

    public static EnvironmentSample neutral() {
        return new EnvironmentSample(
                0.7, 64.0, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0, 0.0, 0.0,
                true, Double.NaN
        );
    }
}

package io.github.chaossurvival.survivalstates.state;

/**
 * 环境等效温度模型（纯函数）。
 *
 * <p>重要：原版 Minecraft 没有玩家体温机制。这里的 E 是插件自定义的
 * “体感等效温度”，单位仅为游戏化的摄氏度等效值，不是原版机制，
 * 也不代表任何医学体温。</p>
 *
 * <p>目标温度公式（见开发蓝图第二节）：</p>
 * <pre>
 * E_target = 21
 *          + biomeWeight·(biomeTemperature - 0.7)
 *          + 8·desert + 6·nether - 10·cold
 *          - altitudeFall·max(0, Y - 62)
 *          - 4·rain + 8·directSun + 5·fireNearby
 *          - 3·water + 3·armorHeat + 2·crowding
 * </pre>
 * 下界等世界可用 flatOverride 直接覆盖，并用 hotCap/coldFloor 夹取。
 */
public final class EnvironmentModel {

    /** 温度模型可调参数，对应 config.temperature。 */
    public record Tuning(
            double biomeWeight,      // 默认 14
            double altitudeFall,     // 默认 0.0065
            double hotCap,           // 主世界默认 85
            double coldFloor,        // 默认 0
            double tauSeconds        // E 平滑时间常数，默认 120
    ) {
        public static Tuning defaults() {
            return new Tuning(14.0, 0.0065, 85.0, 0.0, 120.0);
        }
    }

    private final Tuning tuning;

    public EnvironmentModel(Tuning tuning) {
        this.tuning = tuning == null ? Tuning.defaults() : tuning;
    }

    /**
     * 由环境样本计算目标等效温度 E_target。
     * 世界禁用时返回中性 21，使体温平滑回落到中性，而不是冻结或乱跳。
     */
    public double target(EnvironmentSample s) {
        if (s == null || !s.worldEnabled()) {
            return 21.0;
        }
        // 平坦覆盖（如下界 78）：直接采用，不再叠加群系修正。
        if (!Double.isNaN(s.flatOverride())) {
            return MathUtil.clip(s.flatOverride(), tuning.coldFloor(), tuning.hotCap());
        }
        double target = 21.0
                + tuning.biomeWeight() * (s.biomeTemperature() - 0.7)
                + 8.0 * s.desertLike()
                + 6.0 * s.nether()
                - 10.0 * s.coldBiome()
                - tuning.altitudeFall() * Math.max(0.0, s.y() - 62.0)
                - 4.0 * s.rain()
                + 8.0 * s.directSun()
                + 5.0 * s.fireNearby()
                - 3.0 * s.water()
                + 3.0 * s.armorHeat()
                + 2.0 * s.crowding();
        // 下界单独封顶在较热区间，避免把群系温度直接当环境温度。
        double cap = s.nether() > 0.5 ? Math.min(tuning.hotCap(), 85.0) : tuning.hotCap();
        return MathUtil.clip(target, tuning.coldFloor(), cap);
    }

    /**
     * 用指数平滑把当前 E 向目标推进，单步变化夹到 [-2, +2]。
     *
     * @param currentE 当前等效温度
     * @param targetE  目标等效温度
     * @param dt       经过的真实秒（已夹到单帧上限）
     */
    public double advanceE(double currentE, double targetE, double dt) {
        double alpha = MathUtil.expSmoothAlpha(dt, tuning.tauSeconds());
        double delta = MathUtil.clip((targetE - currentE) * alpha, -2.0, 2.0);
        return currentE + delta;
    }

    /** 由等效温度 E 映射到 0–100 的体核热感 T：T = clip100(50 + 2.5·(E-21))。 */
    public double temperatureFromE(double e) {
        return MathUtil.clip100(50.0 + 2.5 * (e - 21.0));
    }
}

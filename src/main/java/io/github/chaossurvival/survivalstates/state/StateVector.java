package io.github.chaossurvival.survivalstates.state;

/**
 * 玩家生存状态的不可变快照。所有 0–100 量表都是连续量，HUD 只做显示取整。
 *
 * @param hydration   水分 H，0–100，100 为满，0 为完全缺水（不直接等于血量）
 * @param fatigue     疲劳 F，0–100，100 为必须休息
 * @param temperature 体核热感 T，0–100，50 中性，0 过冷，100 过热
 * @param activity    长时活动负荷 A，0–100，非原版饥饿 exhaustion
 * @param quality     当前休息质量估计 Q，0–100，仅影响恢复与预警
 * @param shortLoad   最近短时活动强度 L，0–1
 * @param envTemp     体感环境温度 E，摄氏度等效值
 * @param onlineSeconds 累计在线秒数
 */
public record StateVector(
        double hydration,
        double fatigue,
        double temperature,
        double activity,
        double quality,
        double shortLoad,
        double envTemp,
        long onlineSeconds
) {

    public StateVector {
        hydration = MathUtil.clip100(hydration);
        fatigue = MathUtil.clip100(fatigue);
        temperature = MathUtil.clip100(temperature);
        activity = MathUtil.clip100(activity);
        quality = MathUtil.clip100(quality);
        shortLoad = MathUtil.clip01(shortLoad);
        if (Double.isNaN(envTemp) || Double.isInfinite(envTemp)) {
            envTemp = 21.0;
        }
    }

    /** 默认初值：H=100、F=0、T=50、A=0、Q=80、L=0、E=21。 */
    public static StateVector initial() {
        return new StateVector(100.0, 0.0, 50.0, 0.0, 80.0, 0.0, 21.0, 0L);
    }

    /** 从持久化数据恢复，逐字段做范围防御。 */
    public static StateVector of(double h, double f, double t, double a, double q,
                                 double l, double e, long online) {
        return new StateVector(h, f, t, a, q, l, e, online);
    }
}

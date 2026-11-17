package io.github.chaossurvival.survivalstates.state;

/**
 * 状态积分器（纯函数，无 Bukkit 依赖）。
 *
 * <p>把开发蓝图第二节的全部微分方程落地为确定性计算。所有速率方法都是
 * 静态纯函数，单测可独立校准；{@link #integrate} 组合出下一个状态向量。
 * 时间 t 以真实秒计，单帧 dt 由调用方先夹到 {@code maxIntegrationSeconds}（默认 0.25）。</p>
 */
public final class Integrator {

    /** 基准流率与耦合系数，对应 config 校准节。 */
    public record Tuning(
            double hydrationDrainPerSecond,   // 100/7200 ≈ 0.0138889
            double fatigueGainPerSecond,      // 100/14400 ≈ 0.0069444
            double heatThirstMultiplier,      // 1.20
            double coldThirstReduction,       // 0.35
            double activityThirstMultiplier,  // 0.35
            double dehydrationFoodMultiplier, // 0.25
            double hydrationLowMultiplier,    // 0.75
            double activityFatigueMultiplier, // 0.45
            double coldFatigueMultiplier,     // 0.30
            double heatFatigueMultiplier      // 0.20
    ) {
        public static Tuning defaults() {
            return new Tuning(
                    100.0 / 7200.0,
                    100.0 / 14400.0,
                    1.20, 0.35, 0.35, 0.25,
                    0.75, 0.45, 0.30, 0.20
            );
        }
    }

    /** 一次积分所需的全部外部输入。 */
    public record Input(
            double dt,                    // 已夹到单帧上限的真实秒
            double envTargetE,            // 环境目标等效温度
            double horizontalSpeed,       // 水平速度（格/秒）
            double blockBreaksLast5s,     // 近 5 秒破坏方块数
            double attacksLast5s,         // 近 5 秒成功攻击数
            double jumpOrSprintFlag,      // 跳跃或疾跑标志 0/1
            double foodRecoveryCredit,    // 进食饱食恢复带来的疲劳抵扣（0–0.20）
            double mealRecoveryCredit,    // 饮水/进食的疲劳缓冲消耗速率
            boolean flightOrCreative,     // 飞行/创造/旁观：不计活动
            boolean vehicleHalfWeight     // 载具按半权计
    ) {
        public static Input neutral(double dt) {
            return new Input(dt, 21.0, 0, 0, 0, 0, 0, 0, false, false);
        }
    }

    /** 积分结果：新状态 + 供 Bukkit 层使用的派生量。 */
    public record Result(
            StateVector state,
            double foodRateMultiplier, // 原版食物额外消耗倍率
            double heatStress,         // Sh
            double coldStress          // Sc
    ) {
    }

    private final Tuning tuning;
    private final EnvironmentModel envModel;

    public Integrator(Tuning tuning, EnvironmentModel envModel) {
        this.tuning = tuning == null ? Tuning.defaults() : tuning;
        this.envModel = envModel == null
                ? new EnvironmentModel(EnvironmentModel.Tuning.defaults()) : envModel;
    }

    // ------------------------------------------------------------------
    // 可独立校准的纯速率方法
    // ------------------------------------------------------------------

    /** 热压力 Sh = smoothstep(T,62,100)^1.35。 */
    public static double heatStress(double t) {
        return Math.pow(MathUtil.smoothstep(t, 62.0, 100.0), 1.35);
    }

    /** 冷压力 Sc = smoothstep(50-T,26,50)^1.20。 */
    public static double coldStress(double t) {
        return Math.pow(MathUtil.smoothstep(50.0 - t, 26.0, 50.0), 1.20);
    }

    /** 口渴不足平滑门 G40 = smoothstep(40-H,0,20)。 */
    public static double hydrationLowGate(double h) {
        return MathUtil.smoothstep(40.0 - h, 0.0, 20.0);
    }

    /** 脱水平滑门 G20 = smoothstep(20-H,0,20)。 */
    public static double dehydrationGate(double h) {
        return MathUtil.smoothstep(20.0 - h, 0.0, 20.0);
    }

    /** 水分流失速率（点/秒），恒为负。 */
    public double hydrationDrainRate(double t, double a) {
        double sh = heatStress(t);
        double sc = coldStress(t);
        double activityExtra = 1.0 + tuning.activityThirstMultiplier() * Math.max(0.0, a - 50.0) / 50.0;
        return -tuning.hydrationDrainPerSecond()
                * (1.0 + tuning.heatThirstMultiplier() * sh - tuning.coldThirstReduction() * sc)
                * activityExtra;
    }

    /** 原版食物额外消耗倍率。 */
    public double foodRateMultiplier(double t, double a, double h) {
        double sh = heatStress(t);
        double sc = coldStress(t);
        double m = 1.0
                + 0.50 * sc
                + 0.20 * Math.max(0.0, a - 50.0) / 50.0
                - 0.10 * sh;
        m *= 1.0 + tuning.dehydrationFoodMultiplier() * dehydrationGate(h);
        return Math.max(0.0, m);
    }

    /** 疲劳增长速率（点/秒），恒为正（未计恢复抵扣）。 */
    public double fatigueGainRate(double h, double a, double t) {
        double g40 = hydrationLowGate(h);
        double sh = heatStress(t);
        double sc = coldStress(t);
        return tuning.fatigueGainPerSecond() * (
                1.0
                + tuning.hydrationLowMultiplier() * g40
                + tuning.activityFatigueMultiplier() * Math.max(0.0, a - 40.0) / 60.0
                + tuning.coldFatigueMultiplier() * sc
                + tuning.heatFatigueMultiplier() * sh
        );
    }

    /** 短时活动强度 L_raw（未平滑）。 */
    public double shortLoadRaw(Input in) {
        double speed = Math.min(1.0, Math.max(0.0, in.horizontalSpeed()) / 4.3);
        double breaks = Math.max(0.0, in.blockBreaksLast5s());
        double attacks = Math.max(0.0, in.attacksLast5s());
        double flag = MathUtil.clip01(in.jumpOrSprintFlag());
        double raw = 0.45 * speed + 0.25 * breaks + 0.20 * attacks + 0.10 * flag;
        if (in.flightOrCreative()) {
            return 0.0;
        }
        if (in.vehicleHalfWeight()) {
            raw *= 0.5;
        }
        return MathUtil.clip01(raw);
    }

    /** 活动负荷 A 的变化速率（点/秒）。 */
    public double activityRate(double a, double l) {
        double rise = 18.0 * Math.max(0.0, l - 0.15) * (1.0 - a / 100.0) / 85.0;
        double fall = 10.0 * Math.max(0.0, 0.15 - l) * (a / 100.0) / 60.0;
        return rise - fall;
    }

    // ------------------------------------------------------------------
    // 组合积分
    // ------------------------------------------------------------------

    /**
     * 推进一个状态向量一个时间步。创造/旁观/飞行时冻结活动与状态积分由调用方保证：
     * 这里仍计算，但短时活动输入会被 shortLoadRaw 归零，且调用方可选择不调用本方法。
     */
    public Result integrate(StateVector prev, Input in) {
        double dt = MathUtil.clampDt(in.dt(), 0.25);
        if (dt <= 0.0) {
            return new Result(prev, foodRateMultiplier(prev.temperature(), prev.activity(), prev.hydration()),
                    heatStress(prev.temperature()), coldStress(prev.temperature()));
        }

        // 1) 体感温度 E 向环境目标平滑推进，并映射到 T。
        double newE = envModel.advanceE(prev.envTemp(), in.envTargetE(), dt);
        double newT = envModel.temperatureFromE(newE);

        // 2) 短时活动强度 L：指数平滑，tau=8 秒。
        double rawL = shortLoadRaw(in);
        double alphaL = MathUtil.expSmoothAlpha(dt, 8.0);
        double newL = prev.shortLoad() + (rawL - prev.shortLoad()) * alphaL;
        newL = MathUtil.clip01(newL);

        // 3) 五大状态积分（先算斜率再一次性推进，避免顺序偏置）。
        double dH = hydrationDrainRate(newT, prev.activity());
        double dF = fatigueGainRate(prev.hydration(), prev.activity(), newT)
                - Math.max(0.0, in.foodRecoveryCredit())
                - Math.max(0.0, in.mealRecoveryCredit());
        double dA = activityRate(prev.activity(), newL);
        double foodRate = foodRateMultiplier(newT, prev.activity(), prev.hydration());

        double newH = MathUtil.clip100(prev.hydration() + dH * dt);
        double newF = MathUtil.clip100(prev.fatigue() + dF * dt);
        double newA = MathUtil.clip100(prev.activity() + dA * dt);

        // onlineSeconds 由运行时按真实在线时间统计，积分器只负责状态量，不在此累加。
        StateVector next = new StateVector(
                newH, newF, newT, newA, prev.quality(), newL, newE, prev.onlineSeconds()
        );
        return new Result(next, foodRate, heatStress(newT), coldStress(newT));
    }
}

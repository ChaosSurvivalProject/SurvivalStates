package io.github.chaossurvival.survivalstates.service;

/**
 * 休息会话状态机（见蓝图第四节）。床只是入口与场景，插件独立累计 R。
 *
 * <p>阶段：ARMED（3 秒预警）→ ACTIVE（累计有效睡眠）→ PAUSED（离床/受伤，
 * 5 秒窗口）→ DONE / ABORTED。10 分钟是“本次最多需要多久”的随机上限，
 * 不是固定倒计时。强制态不设无敌、不跳原版夜晚。</p>
 */
public final class RestSession {

    public enum Phase {
        ARMED, ACTIVE, PAUSED, DONE, ABORTED
    }

    private Phase phase;
    private final double fatigueAtEntry;
    private final double qualityAtEntry;
    private final double maxRest;         // 本次随机上限秒数 180–600
    private final double required;        // 本次所需有效秒数
    private double accumulatedR;          // 已累计有效秒数
    private double armedRemaining;        // ARMED 剩余秒
    private double pauseRemaining;        // PAUSED 剩余回床窗口秒
    private double qualitySum;            // 质量累计（求均值）
    private int qualitySamples;
    private long lastDamageEpoch;
    private int wakeAttempts;
    private boolean sleepAnchorSet;

    /** 创建新会话。armSeconds 为预警秒数。 */
    public static RestSession create(double fatigueEntry, double quality,
                                     double armSeconds, java.util.random.RandomGenerator rng) {
        double maxRest = 180.0 + rng.nextDouble() * 420.0; // 180–600
        double required = maxRest
                * (0.65 + 0.35 * fatigueEntry / 100.0)
                * (1.10 - 0.35 * quality / 100.0);
        RestSession s = new RestSession(fatigueEntry, quality, maxRest, required);
        s.phase = Phase.ARMED;
        s.armedRemaining = armSeconds;
        return s;
    }

    private RestSession(double fatigueAtEntry, double qualityAtEntry,
                        double maxRest, double required) {
        this.fatigueAtEntry = fatigueAtEntry;
        this.qualityAtEntry = qualityAtEntry;
        this.maxRest = maxRest;
        this.required = required;
        this.accumulatedR = 0;
        this.qualitySamples = 0;
        this.qualitySum = 0;
    }

    /** 反序列化的会话占位（会话离线不累积，重新进入时按当前质量重建）。 */
    public static RestSession deserialize(String s) {
        return null;
    }

    public String serialize() {
        return phase + "|" + Math.round(accumulatedR) + "|" + Math.round(required);
    }

    public Phase phase() {
        return phase;
    }

    public double accumulatedR() {
        return accumulatedR;
    }

    public double required() {
        return required;
    }

    public double progress() {
        return required <= 0 ? 1.0 : Math.min(1.0, accumulatedR / required);
    }

    public double armedRemaining() {
        return armedRemaining;
    }

    public int wakeAttempts() {
        return wakeAttempts;
    }

    /** 推进一个时间步。返回 true 表示会话结束（DONE 或 ABORTED）。 */
    public boolean tick(double dt,
                        boolean bedAvailable,
                        boolean safety,
                        boolean quiet,
                        boolean hungerOkay,
                        double noBedEfficiency) {
        switch (phase) {
            case ARMED -> {
                armedRemaining -= dt;
                if (armedRemaining <= 0) {
                    phase = Phase.ACTIVE;
                }
            }
            case ACTIVE -> {
                double effectiveRate = (bedAvailable ? 1.0 : noBedEfficiency)
                        * (safety ? 1.0 : 0.0)
                        * (quiet ? 1.0 : 0.35)
                        * (0.75 + 0.25 * (hungerOkay ? 1.0 : 0.5));
                accumulatedR += dt * effectiveRate;
                qualitySum += qualityTarget(bedAvailable, safety, quiet) * dt;
                qualitySamples++;
                if (accumulatedR >= required) {
                    phase = Phase.DONE;
                }
            }
            case PAUSED -> {
                pauseRemaining -= dt;
                if (pauseRemaining <= 0) {
                    phase = Phase.ABORTED; // 超时：保存进度并回到清醒态
                }
            }
            default -> {
                return true;
            }
        }
        return phase == Phase.DONE || phase == Phase.ABORTED;
    }

    /** 打断（受伤/离床/传送等）：保存会话进入 PAUSED，5 秒窗口。 */
    public void interrupt(double resumeWindowSeconds) {
        if (phase == Phase.ACTIVE || phase == Phase.ARMED) {
            phase = Phase.PAUSED;
            pauseRemaining = resumeWindowSeconds;
        }
    }

    /** 在回床窗口内恢复。 */
    public boolean tryResume() {
        if (phase == Phase.PAUSED && pauseRemaining > 0) {
            phase = Phase.ACTIVE;
            return true;
        }
        return false;
    }

    public void recordWakeAttempt() {
        wakeAttempts++;
    }

    public boolean isSleepAnchorSet() {
        return sleepAnchorSet;
    }

    public void markSleepAnchorSet() {
        this.sleepAnchorSet = true;
    }

    public void recordDamage(long epochSeconds) {
        lastDamageEpoch = epochSeconds;
    }

    public long lastDamageEpoch() {
        return lastDamageEpoch;
    }

    /** 会话质量目标：床 + 黑暗 + 安静 - 威胁 - 打断。 */
    private double qualityTarget(boolean bedAvailable, boolean safety, boolean quiet) {
        double q = 30.0
                + (bedAvailable ? 55.0 : 0.0)
                + 10.0 // darkEnough：休息默认在较暗环境，简化为常量可配置
                + (quiet ? 5.0 : 0.0)
                - (safety ? 0.0 : 20.0)
                - (wakeAttempts > 0 ? 15.0 : 0.0);
        return io.github.chaossurvival.survivalstates.state.MathUtil.clip100(q);
    }

    /** 结束时的质量均值与疲劳扣除量。 */
    public double finalQuality() {
        return qualitySamples == 0 ? qualityAtEntry : qualitySum / qualitySamples;
    }

    /** 成功休息移除的疲劳点数：min(F, 25+55·Q/100)。 */
    public double fatigueRelief(double currentFatigue) {
        return Math.min(currentFatigue, 25.0 + 55.0 * finalQuality() / 100.0);
    }
}

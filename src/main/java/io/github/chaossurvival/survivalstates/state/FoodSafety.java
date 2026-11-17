package io.github.chaossurvival.survivalstates.state;

import java.util.random.RandomGenerator;

/**
 * 饮食安全结算（纯逻辑，注入随机源以便确定性测试）。
 *
 * <p>三品质：clean 安全；suspect 首次消费用玩家独立种子 50% 有害；
 * bad 100% 有害。同一消费序号只结算一次（幂等由调用层的 consumeSeq 保证）。
 * 副作用采用“刷新而非叠等级”，由 Bukkit 层施加时再检查同类效果。</p>
 */
public final class FoodSafety {

    /** 物品品质。 */
    public enum Quality {
        CLEAN, SUSPECT, BAD
    }

    /** 单次消费的结算结果。 */
    public record Outcome(
            Quality quality,
            boolean harmful,
            double hydrationGain,   // 水分恢复点数
            double fatigueBuffer,   // 疲劳缓冲点数
            int nauseaSeconds,
            int hungerSeconds,
            int poisonSeconds
    ) {
        public boolean hasSideEffect() {
            return nauseaSeconds > 0 || hungerSeconds > 0 || poisonSeconds > 0;
        }
    }

    /** 食物安全配置。 */
    public record Tuning(
            double suspiciousHarmChance, // 0.50
            boolean badAlwaysHarmful,    // true
            int nauseaSeconds,           // 30
            int hungerSeconds,           // 30
            int badPoisonSeconds         // 30：坏水的 POISON/HUNGER 时长
    ) {
        public static Tuning defaults() {
            return new Tuning(0.50, true, 30, 30, 30);
        }
    }

    /** 可疑物品有害时使用的副作用类型，坏水 POISON 或 HUNGER 二选一。 */
    public enum BadEffect {
        POISON, HUNGER
    }

    private final Tuning tuning;

    public FoodSafety(Tuning tuning) {
        this.tuning = tuning == null ? Tuning.defaults() : tuning;
    }

    /**
     * 结算一次消费。
     *
     * @param quality       物品品质
     * @param isWater       是否饮品（决定恢复量）
     * @param rng           玩家独立随机源（可疑品质时消耗一次）
     * @param badWaterEffect 坏水副作用类型；null 时按 HUNGER
     */
    public Outcome resolve(Quality quality, boolean isWater, RandomGenerator rng, BadEffect badWaterEffect) {
        if (quality == null) {
            quality = Quality.CLEAN;
        }
        boolean harmful = switch (quality) {
            case CLEAN -> false;
            case BAD -> true; // 坏物恒有害
            case SUSPECT -> rng != null && rng.nextDouble() < tuning.suspiciousHarmChance();
        };

        double hydration = 0.0;
        double buffer = 0.0;
        int nausea = 0;
        int hunger = 0;
        int poison = 0;

        if (isWater) {
            // 水分恢复区间见蓝图第四节表格。
            switch (quality) {
                case CLEAN -> {
                    hydration = 40.0;
                    buffer = harmful ? 3.0 : 12.0;
                }
                case SUSPECT -> {
                    hydration = 25.0;
                    buffer = harmful ? 3.0 : 6.0;
                    if (harmful) {
                        nausea = tuning.nauseaSeconds();
                        hunger = 1; // 额外饥饿 1 点（作为压力点数，非秒）
                    }
                }
                case BAD -> {
                    hydration = 12.0;
                    buffer = 0.0;
                    if (badWaterEffect == BadEffect.POISON) {
                        poison = tuning.badPoisonSeconds();
                    } else {
                        hunger = tuning.hungerSeconds();
                    }
                }
            }
        } else {
            // 食物：保留原版营养与饱和，这里只给有限疲劳缓冲。
            switch (quality) {
                case CLEAN -> buffer = 8.0;
                case SUSPECT -> {
                    buffer = harmful ? 4.0 : 8.0;
                    if (harmful) {
                        hunger = tuning.hungerSeconds();
                    }
                }
                case BAD -> {
                    buffer = 0.0;
                    nausea = 45; // 坏食物默认 45 秒 NAUSEA，不叠加毒药
                }
            }
        }

        return new Outcome(quality, harmful, hydration, buffer, nausea, hunger, poison);
    }

    /**
     * 每小时从饮水获得疲劳缓冲的上限检查（防刷水，默认 80 点）。
     *
     * @param gainedThisHour 本小时已获得缓冲
     * @param candidate      本次候选缓冲
     * @return 实际允许的缓冲（不超过剩余额度，且不小于 0）
     */
    public double clampWaterBuffer(double gainedThisHour, double candidate) {
        final double hourlyCap = 80.0;
        double remaining = hourlyCap - Math.max(0.0, gainedThisHour);
        return Math.max(0.0, Math.min(Math.max(0.0, candidate), remaining));
    }

    /** 为某玩家构造确定性随机源：seed = 基础种子 XOR UUID 稳定哈希。 */
    public static RandomGenerator playerRng(long baseSeed, java.util.UUID uuid) {
        long seed = baseSeed ^ uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
        // SplittableRandom 实现 RandomGenerator 且支持 long 种子，便于确定性重放。
        return new java.util.SplittableRandom(seed);
    }
}

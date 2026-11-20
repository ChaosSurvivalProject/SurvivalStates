package io.github.chaossurvival.survivalstates.service;

import io.github.chaossurvival.survivalstates.state.*;
import io.github.chaossurvival.survivalstates.storage.PlayerData;

import java.util.random.RandomGenerator;
import java.util.UUID;

/**
 * 单个玩家的在线运行时状态。仅在主线程积分与修改。
 *
 * <p>静态持有 Player 引用是被禁止的；这里只保存 UUID 与纯数据，
 * 需要 Player 时通过 Bukkit.getPlayer(uuid) 在主线程获取。</p>
 */
public final class PlayerRuntime {

    private final UUID uuid;
    // BUGFIX(Bug3): 在线秒数小数进位累加器，避免每 tick 取整导致永不增长。
    private double onlineSecondCarry;
    private volatile StateVector state;
    private final Hysteresis hysteresis = new Hysteresis();
    private EnvironmentSample environment = EnvironmentSample.neutral();
    private final RandomGenerator rng;

    // 活动采集：近 5 秒计数（由监听器主线程的 tick 归零）。
    private volatile int breaksLast5s;
    private volatile int attacksLast5s;
    private volatile boolean jumpOrSprint;
    private double horizontalSpeed;

    // 恢复抵扣：进食饱食恢复（随 600 秒衰减）与饮水缓冲消耗速率。
    private double foodCredit;
    private double mealBufferRate;

    // 饮水缓冲每小时额度。
    private long waterBufferWindowStart;
    private double waterBufferGained;

    // 安全约束。
    private boolean lowHealthProtected;

    // 休息会话（见 RestSession）。
    private volatile RestSession restSession;

    // 供水/进食幂等：上一消费序号。
    private long lastConsumeSeq = -1;

    public PlayerRuntime(UUID uuid, PlayerData data, long baseSeed) {
        this.uuid = uuid;
        this.rng = FoodSafety.playerRng(baseSeed, uuid);
        if (data != null) {
            this.state = StateVector.of(
                    data.hydration, data.fatigue, data.temperature, data.activity,
                    data.quality, data.shortLoad, data.envTemp, data.onlineSeconds
            );
            this.waterBufferWindowStart = data.lastRestEpochSeconds;
        } else {
            this.state = StateVector.initial();
        }
    }

    public UUID uuid() {
        return uuid;
    }

    public StateVector state() {
        return state;
    }

    public void setState(StateVector state) {
        this.state = state;
    }

    public double onlineSecondCarry() {
        return onlineSecondCarry;
    }

    public void setOnlineSecondCarry(double v) {
        this.onlineSecondCarry = v;
    }

    public Hysteresis hysteresis() {
        return hysteresis;
    }

    public RandomGenerator rng() {
        return rng;
    }

    public EnvironmentSample environment() {
        return environment;
    }

    public void setEnvironment(EnvironmentSample environment) {
        this.environment = environment;
    }

    // ---- 活动采集 ----
    public int breaksLast5s() {
        return breaksLast5s;
    }

    public void recordBreak() {
        breaksLast5s++;
    }

    public int attacksLast5s() {
        return attacksLast5s;
    }

    public void recordAttack() {
        attacksLast5s++;
    }

    public void recordJumpOrSprint() {
        jumpOrSprint = true;
    }

    public void setHorizontalSpeed(double horizontalSpeed) {
        this.horizontalSpeed = horizontalSpeed;
    }

    public double horizontalSpeed() {
        return horizontalSpeed;
    }

    /** 每 5 秒由 tick 调用，归零短时计数。 */
    public void resetActivityWindow() {
        breaksLast5s = 0;
        attacksLast5s = 0;
        jumpOrSprint = false;
    }

    public double jumpOrSprintFlag() {
        return jumpOrSprint ? 1.0 : 0.0;
    }

    // ---- 恢复抵扣 ----
    public double foodCredit() {
        return foodCredit;
    }

    public double mealBufferRate() {
        return mealBufferRate;
    }

    public void addFoodCredit(double credit) {
        this.foodCredit = MathUtil.clip(this.foodCredit + credit, 0.0, 0.20);
    }

    public void addMealBuffer(double points) {
        // 30 秒内平滑扣除：每秒速率 = points/30。
        this.mealBufferRate += Math.max(0.0, points) / 30.0;
    }

    /** 每步衰减进食抵扣与缓冲消耗。 */
    public void consumeRecovery(double dt) {
        // 进食抵扣 600 秒线性衰减。
        if (foodCredit > 0) {
            foodCredit = Math.max(0.0, foodCredit - dt / 600.0 * 0.20);
        }
        if (mealBufferRate > 0) {
            // 缓冲总点数通过 rate*30 估算，逐步消耗 rate 本身。
            mealBufferRate = Math.max(0.0, mealBufferRate - mealBufferRate * dt / 30.0);
        }
    }

    // ---- 饮水缓冲额度 ----
    public double waterBufferGained() {
        return waterBufferGained;
    }

    public void addWaterBufferGained(double points) {
        this.waterBufferGained += points;
    }

    public void resetWaterBufferIfHourPassed(long nowSeconds) {
        if (nowSeconds - waterBufferWindowStart >= 3600L) {
            waterBufferWindowStart = nowSeconds;
            waterBufferGained = 0;
        }
    }

    // ---- 安全约束 ----
    public boolean isLowHealthProtected() {
        return lowHealthProtected;
    }

    public void setLowHealthProtected(boolean lowHealthProtected) {
        this.lowHealthProtected = lowHealthProtected;
    }

    // ---- 休息会话 ----
    public RestSession restSession() {
        return restSession;
    }

    public void setRestSession(RestSession restSession) {
        this.restSession = restSession;
    }

    // ---- 幂等 ----
    public long lastConsumeSeq() {
        return lastConsumeSeq;
    }

    public void setLastConsumeSeq(long lastConsumeSeq) {
        this.lastConsumeSeq = lastConsumeSeq;
    }

    /** 导出为存档数据。 */
    public PlayerData toData() {
        PlayerData d = new PlayerData();
        d.schemaVersion = io.github.chaossurvival.survivalstates.storage.MigrationRegistry.LATEST;
        d.uuid = uuid.toString();
        d.hydration = state.hydration();
        d.fatigue = state.fatigue();
        d.temperature = state.temperature();
        d.activity = state.activity();
        d.quality = state.quality();
        d.shortLoad = state.shortLoad();
        d.envTemp = state.envTemp();
        d.onlineSeconds = state.onlineSeconds();
        d.lastRestEpochSeconds = System.currentTimeMillis() / 1000L;
        d.session = restSession == null ? null : restSession.serialize();
        return d;
    }
}

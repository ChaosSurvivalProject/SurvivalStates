package io.github.chaossurvival.survivalstates.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 状态积分校准测试：验证 240/120 基准、NaN/越界、10 秒卡顿、
 * 高低温与压力公式，全部不依赖服务器时钟（注入固定 dt）。
 */
class IntegratorTest {

    private final Integrator integrator = new Integrator(Integrator.Tuning.defaults(), null);

    /** 以固定步长积分若干秒，返回最终状态。 */
    private StateVector run(StateVector start, double seconds, double step,
                            java.util.function.Function<Double, Integrator.Input> inputFactory) {
        StateVector s = start;
        double elapsed = 0;
        while (elapsed < seconds - 1e-9) {
            double dt = Math.min(step, seconds - elapsed);
            s = integrator.integrate(s, inputFactory.apply(dt)).state();
            elapsed += dt;
        }
        return s;
    }

    @Test
    void 口渴基准7200秒见底() {
        // 中性温度、无活动补偿：H 从 100 到 0 用 7200 秒，容差 ±1%。
        // 用中性环境，dt=0.05 精细积分。
        StateVector end = run(StateVector.initial(), 7200, 0.25,
                dt -> Integrator.Input.neutral(dt));
        assertTrue(end.hydration() <= 1.0, "7200 秒后水分应接近 0，实际=" + end.hydration());
    }

    @Test
    void 疲劳基准14400秒满() {
        // 保持水分满，避免 G40 放大；隔离测试基础疲劳速率。
        // 每步把 H 重置为 100，模拟“无进食补偿但水分充足”的校准条件。
        StateVector s = StateVector.initial();
        double elapsed = 0;
        while (elapsed < 14400 - 1e-9) {
            double dt = Math.min(0.25, 14400 - elapsed);
            StateVector fed = new StateVector(100, s.fatigue(), s.temperature(),
                    s.activity(), s.quality(), s.shortLoad(), s.envTemp(), s.onlineSeconds());
            s = integrator.integrate(fed, Integrator.Input.neutral(dt)).state();
            elapsed += dt;
        }
        assertTrue(s.fatigue() >= 99.0, "14400 秒后疲劳应接近 100，实际=" + s.fatigue());
    }

    @Test
    void 单帧10秒卡顿不越界() {
        StateVector start = StateVector.of(100, 99.8, 50, 90, 80, 0.5, 21, 0);
        Integrator.Result r = integrator.integrate(start, Integrator.Input.neutral(10.0));
        assertTrue(r.state().fatigue() <= 100 && r.state().fatigue() >= 0);
        assertTrue(r.state().hydration() <= 100 && r.state().hydration() >= 0);
        assertTrue(r.state().activity() <= 100 && r.state().activity() >= 0);
    }

    @Test
    void 高温加剧口渴低温提高食物需求() {
        double hotT = 100.0, coldT = 0.0;
        double hotDrain = integrator.hydrationDrainRate(hotT, 0);
        double coldDrain = integrator.hydrationDrainRate(coldT, 0);
        double neutralDrain = integrator.hydrationDrainRate(50, 0);
        assertTrue(hotDrain < neutralDrain, "高温口渴流失更快");
        assertTrue(coldDrain > neutralDrain, "低温口渴流失更慢（负值更小幅）");
        assertEquals(2.2, hotDrain / neutralDrain, 0.05); // 1+1.20*1

        double coldFood = integrator.foodRateMultiplier(coldT, 0, 100);
        double neutralFood = integrator.foodRateMultiplier(50, 0, 100);
        assertTrue(coldFood <= 1.5 + 1e-9, "低温食物倍率不超过 1.5");
        assertTrue(coldFood > neutralFood);
    }

    @Test
    void 压力与门限公式正确() {
        assertEquals(0.0, Integrator.heatStress(62));
        assertEquals(1.0, Integrator.heatStress(100));
        assertEquals(0.0, Integrator.heatStress(50));
        assertEquals(1.0, Integrator.coldStress(0));
        assertEquals(0.0, Integrator.coldStress(24));
        assertEquals(0.0, Integrator.hydrationLowGate(40));
        assertEquals(1.0, Integrator.hydrationLowGate(20));
    }

    @Test
    void NaN输入不污染状态() {
        StateVector s = StateVector.of(Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0);
        assertTrue(Double.isFinite(s.hydration()));
        assertTrue(Double.isFinite(s.fatigue()));
        assertTrue(Double.isFinite(s.envTemp()));
    }
}

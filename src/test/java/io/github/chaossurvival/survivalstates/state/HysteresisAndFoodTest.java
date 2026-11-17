package io.github.chaossurvival.survivalstates.state;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;

/** 滞后门与饮食安全测试。 */
class HysteresisAndFoodTest {

    @Test
    void 疲劳阈值抖动只触发一次() {
        Hysteresis h = new Hysteresis();
        // 69→71 越过 70 进入疲惫；再回 69 退出（<65 才退出，所以 69 不退出）。
        List<Hysteresis.Event> e1 = h.update(100, 71, 50);
        assertTrue(e1.stream().anyMatch(e -> e.band() == Hysteresis.Band.FATIGUE_TIRED && e.entered()));
        // 71→70→71 之间反复，不应重复触发。
        h.update(100, 70, 50);
        List<Hysteresis.Event> e2 = h.update(100, 71, 50);
        assertTrue(e2.stream().noneMatch(e -> e.band() == Hysteresis.Band.FATIGUE_TIRED));
        // 降到 64 才退出。
        List<Hysteresis.Event> e3 = h.update(100, 64, 50);
        assertTrue(e3.stream().anyMatch(e -> e.band() == Hysteresis.Band.FATIGUE_TIRED && !e.entered()));
    }

    @Test
    void 口渴与危险阈值滞后() {
        Hysteresis h = new Hysteresis();
        assertTrue(h.update(39, 0, 50).stream()
                .anyMatch(e -> e.band() == Hysteresis.Band.HYDRATION_LOW && e.entered()));
        // 41 不应退出（需 >45）。
        assertTrue(h.update(41, 0, 50).stream()
                .noneMatch(e -> e.band() == Hysteresis.Band.HYDRATION_LOW));
        assertTrue(h.update(46, 0, 50).stream()
                .anyMatch(e -> e.band() == Hysteresis.Band.HYDRATION_LOW && !e.entered()));

        Hysteresis h2 = new Hysteresis();
        assertTrue(h2.update(100, 91, 50).stream()
                .anyMatch(e -> e.band() == Hysteresis.Band.FATIGUE_DANGER && e.entered()));
    }

    @Test
    void 可疑物品50百分比有害() {
        FoodSafety fs = new FoodSafety(FoodSafety.Tuning.defaults());
        int trials = 10000;
        int harmful = 0;
        RandomGenerator rng = new java.util.SplittableRandom(12345L);
        for (int i = 0; i < trials; i++) {
            FoodSafety.Outcome o = fs.resolve(FoodSafety.Quality.SUSPECT, true, rng, null);
            if (o.harmful()) {
                harmful++;
            }
        }
        double rate = harmful / (double) trials;
        // 二项分布 10000 次，p=0.5，标准差 0.005，5σ 区间约 [0.475,0.525]。
        assertTrue(rate > 0.47 && rate < 0.53, "可疑有害比例=" + rate);
    }

    @Test
    void 干净安全坏物恒有害() {
        FoodSafety fs = new FoodSafety(FoodSafety.Tuning.defaults());
        RandomGenerator rng = new java.util.SplittableRandom(1L);
        assertFalse(fs.resolve(FoodSafety.Quality.CLEAN, true, rng, null).harmful());
        assertTrue(fs.resolve(FoodSafety.Quality.BAD, true, rng, null).harmful());
        assertTrue(fs.resolve(FoodSafety.Quality.BAD, false, rng, null).hasSideEffect());
    }

    @Test
    void 每小时饮水缓冲封顶80() {
        FoodSafety fs = new FoodSafety(FoodSafety.Tuning.defaults());
        assertEquals(40.0, fs.clampWaterBuffer(0, 40), 1e-9);
        assertEquals(5.0, fs.clampWaterBuffer(75, 20), 1e-9);
        assertEquals(0.0, fs.clampWaterBuffer(90, 20), 1e-9);
    }
}

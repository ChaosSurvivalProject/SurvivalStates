package io.github.chaossurvival.survivalstates.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 纯数学工具测试。 */
class MathUtilTest {

    @Test
    void clip边界与NaN() {
        assertEquals(0.0, MathUtil.clip01(-3));
        assertEquals(1.0, MathUtil.clip01(9));
        assertEquals(0.5, MathUtil.clip01(0.5));
        assertEquals(0.0, MathUtil.clip01(Double.NaN));
        assertEquals(0.0, MathUtil.clip100(-1));
        assertEquals(100.0, MathUtil.clip100(101));
        assertEquals(5.0, MathUtil.clip(5, 0, 10));
    }

    @Test
    void smoothstep端点与中点() {
        assertEquals(0.0, MathUtil.smoothstep(-1, 0, 10));
        assertEquals(1.0, MathUtil.smoothstep(11, 0, 10));
        assertEquals(0.5, MathUtil.smoothstep(5, 0, 10), 1e-9);
    }

    @Test
    void 指数平滑系数单调有界() {
        assertTrue(MathUtil.expSmoothAlpha(1, 120) > 0);
        assertTrue(MathUtil.expSmoothAlpha(1, 120) < 0.01);
        assertEquals(0.0, MathUtil.expSmoothAlpha(0, 120));
        assertEquals(1.0, MathUtil.expSmoothAlpha(1, 0));
    }

    @Test
    void 单帧夹取() {
        assertEquals(0.25, MathUtil.clampDt(10, 0.25));
        assertEquals(0.0, MathUtil.clampDt(Double.NaN, 0.25));
    }
}

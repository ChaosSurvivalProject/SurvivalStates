package io.github.chaossurvival.survivalstates.state;

/**
 * 纯数学工具：所有函数无副作用、无状态，便于单元测试与确定性重放。
 *
 * <p>约定：</p>
 * <ul>
 *   <li>{@code clip01(x)} 把任意实数夹到 [0,1]；</li>
 *   <li>{@code clip100(x)} 把任意实数夹到 [0,100]；</li>
 *   <li>{@code smoothstep(x,a,b)} 为三次平滑阶跃；</li>
 *   <li>{@code expSmoothAlpha(dt,tau)} 为指数平滑系数 alpha=1-exp(-dt/tau)。</li>
 * </ul>
 *
 * <p>所有函数都对 NaN / 无穷做了防御：NaN 会被夹到区间下限，
 * 避免脏数据污染状态积分（见开发蓝图第九节测试矩阵）。</p>
 */
public final class MathUtil {

    private MathUtil() {
    }

    /** 把 x 夹到 [0,1]；NaN 视为 0。 */
    public static double clip01(double x) {
        if (Double.isNaN(x)) {
            return 0.0;
        }
        if (x <= 0.0) {
            return 0.0;
        }
        if (x >= 1.0) {
            return 1.0;
        }
        return x;
    }

    /** 把 x 夹到 [lo,hi]；NaN 视为 lo。 */
    public static double clip(double x, double lo, double hi) {
        if (Double.isNaN(x)) {
            return lo;
        }
        if (x < lo) {
            return lo;
        }
        if (x > hi) {
            return hi;
        }
        return x;
    }

    /** 把 x 夹到 [0,100]；NaN 视为 0。 */
    public static double clip100(double x) {
        return clip(x, 0.0, 100.0);
    }

    /**
     * 三次平滑阶跃：x<=a 时为 0，x>=b 时为 1，
     * 中间为 3u²-2u³，其中 u=(x-a)/(b-a)。
     */
    public static double smoothstep(double x, double a, double b) {
        if (b <= a) {
            return x >= b ? 1.0 : 0.0;
        }
        double u = clip01((x - a) / (b - a));
        return u * u * (3.0 - 2.0 * u);
    }

    /**
     * 指数平滑系数 alpha = 1 - exp(-dt/tau)。
     * tau<=0 时返回 1（立即跟随），dt<=0 时返回 0（不变化）。
     */
    public static double expSmoothAlpha(double dt, double tau) {
        if (dt <= 0.0) {
            return 0.0;
        }
        if (tau <= 0.0) {
            return 1.0;
        }
        return 1.0 - Math.exp(-dt / tau);
    }

    /** 线性插值。 */
    public static double lerp(double a, double b, double t) {
        return a + (b - a) * clip01(t);
    }

    /** 把单帧 dt 夹到 [0, maxIntegration]，防止长卡顿瞬间灌满状态。 */
    public static double clampDt(double dt, double maxIntegration) {
        if (Double.isNaN(dt) || dt <= 0.0) {
            return 0.0;
        }
        return Math.min(dt, maxIntegration);
    }
}

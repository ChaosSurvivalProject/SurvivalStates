package io.github.chaossurvival.survivalstates.state;

/**
 * 滞后（滞回）门限状态机：避免整数阈值边界逐 tick 反复触发事件。
 *
 * <p>每个区间有进入/退出两条线，只有状态真正跨层时才产生一次事件。
 * 阈值来自开发蓝图第三节：</p>
 * <ul>
 *   <li>水分：正常 &lt;75 / &gt;80，不足 &lt;40 / &gt;45，脱水 &lt;20 / &gt;25；</li>
 *   <li>疲劳：清醒 &lt;70/&gt;75，疲惫 &gt;70/&lt;65，危险 &gt;90/&lt;85，
 *       预警 &gt;97/&lt;93，强制 &ge;100；</li>
 *   <li>温度：热应激 &gt;62/&lt;57，冷应激 &lt;24/&gt;29。</li>
 * </ul>
 */
public final class Hysteresis {

    /** 需要判断的层级。 */
    public enum Band {
        HYDRATION_NORMAL, HYDRATION_LOW, HYDRATION_DEHYDRATED,
        FATIGUE_TIRED, FATIGUE_DANGER, FATIGUE_WARNING, FATIGUE_FORCE,
        TEMP_HOT, TEMP_COLD
    }

    /** 一次跨层的方向：进入或退出。 */
    public record Event(Band band, boolean entered, double value) {
    }

    /** 区间定义：enter 为进入阈值，exit 为退出阈值，rising=true 表示向上穿越进入。 */
    private record Gate(double enter, double exit, boolean rising) {
        boolean shouldEnter(double v) {
            return rising ? v >= enter : v <= enter;
        }

        boolean shouldExit(double v) {
            return rising ? v <= exit : v >= exit;
        }
    }

    // 口渴层级越低越严重，故用下降穿越进入（rising=false）。
    private static final Gate HYDRATION_NORMAL = new Gate(75.0, 80.0, false);
    private static final Gate HYDRATION_LOW = new Gate(40.0, 45.0, false);
    private static final Gate HYDRATION_DEHYDRATED = new Gate(20.0, 25.0, false);
    // 疲劳层级越高越严重，上升穿越进入。
    private static final Gate FATIGUE_TIRED = new Gate(70.0, 65.0, true);
    private static final Gate FATIGUE_DANGER = new Gate(90.0, 85.0, true);
    private static final Gate FATIGUE_WARNING = new Gate(97.0, 93.0, true);
    private static final Gate FATIGUE_FORCE = new Gate(100.0, 99.0, true);
    // 温度：热上升进入，冷下降进入。
    private static final Gate TEMP_HOT = new Gate(62.0, 57.0, true);
    private static final Gate TEMP_COLD = new Gate(24.0, 29.0, false);

    private final java.util.EnumMap<Band, Boolean> active = new java.util.EnumMap<>(Band.class);
    private final java.util.List<Event> pending = new java.util.ArrayList<>();

    public Hysteresis() {
        // 初始全部未激活；首次 update 会把当前所处层级判定为“进入”。
        for (Band b : Band.values()) {
            active.put(b, false);
        }
    }

    /**
     * 用当前状态刷新所有层级，返回本次发生的跨层事件列表（可能为空）。
     * 事件只在跨层瞬间产生一次，抖动于阈值附近不会重复触发。
     */
    public java.util.List<Event> update(double hydration, double fatigue, double temperature) {
        pending.clear();
        evaluate(Band.HYDRATION_NORMAL, HYDRATION_NORMAL, hydration);
        evaluate(Band.HYDRATION_LOW, HYDRATION_LOW, hydration);
        evaluate(Band.HYDRATION_DEHYDRATED, HYDRATION_DEHYDRATED, hydration);
        evaluate(Band.FATIGUE_TIRED, FATIGUE_TIRED, fatigue);
        evaluate(Band.FATIGUE_DANGER, FATIGUE_DANGER, fatigue);
        evaluate(Band.FATIGUE_WARNING, FATIGUE_WARNING, fatigue);
        evaluate(Band.FATIGUE_FORCE, FATIGUE_FORCE, fatigue);
        evaluate(Band.TEMP_HOT, TEMP_HOT, temperature);
        evaluate(Band.TEMP_COLD, TEMP_COLD, temperature);
        return java.util.List.copyOf(pending);
    }

    private void evaluate(Band band, Gate gate, double value) {
        boolean was = active.get(band);
        if (!was && gate.shouldEnter(value)) {
            active.put(band, true);
            pending.add(new Event(band, true, value));
        } else if (was && gate.shouldExit(value)) {
            active.put(band, false);
            pending.add(new Event(band, false, value));
        }
    }

    public boolean isActive(Band band) {
        return Boolean.TRUE.equals(active.get(band));
    }
}

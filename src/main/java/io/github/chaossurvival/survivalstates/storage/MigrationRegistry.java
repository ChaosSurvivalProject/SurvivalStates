package io.github.chaossurvival.survivalstates.storage;

import java.util.Map;
import java.util.function.Function;

/**
 * 存档迁移注册表。
 *
 * <p>v1→v2 示例：旧字段 thirst 改名 hydration，旧布尔 dehydrated 换算成 H&lt;20。
 * 迁移先备份、幂等；未知高版本只读隔离，绝不覆盖原文件。</p>
 */
public final class MigrationRegistry {

    /** 当前支持的最新 schema 版本。 */
    public static final int LATEST = 2;

    /** 迁移函数：把低版本 JsonObject 升级到下一版本。 */
    private static final Map<Integer, Function<com.google.gson.JsonObject, com.google.gson.JsonObject>> MIGRATORS =
            Map.of(
                    // v1 -> v2
                    1, json -> {
                        com.google.gson.JsonObject out = json.deepCopy();
                        if (out.has("thirst") && !out.has("hydration")) {
                            out.add("hydration", out.get("thirst"));
                            out.remove("thirst");
                        }
                        if (out.has("dehydrated")) {
                            boolean dehydrated = out.get("dehydrated").getAsBoolean();
                            double h = out.has("hydration") ? out.get("hydration").getAsDouble() : 100.0;
                            // 旧布尔换算为 H<20：脱水则把水分压到 15，否则至少 25。
                            out.addProperty("hydration", dehydrated ? Math.min(h, 15.0) : Math.max(h, 25.0));
                            out.remove("dehydrated");
                        }
                        out.addProperty("schemaVersion", 2);
                        return out;
                    }
            );

    /** 已知版本集合（含最新）。 */
    private static final java.util.Set<Integer> KNOWN =
            java.util.Set.of(1, 2);

    public static boolean isKnown(int version) {
        return KNOWN.contains(version);
    }

    public static boolean needsMigration(int version) {
        return version < LATEST && MIGRATORS.containsKey(version);
    }

    /**
     * 反复迁移直到最新版本。未知版本抛异常，调用方据此隔离该文件。
     */
    public static com.google.gson.JsonObject migrateToLatest(com.google.gson.JsonObject json) {
        int v = json.has("schemaVersion") ? json.get("schemaVersion").getAsInt() : 1;
        if (!isKnown(v)) {
            throw new IllegalArgumentException("未知的存档 schemaVersion=" + v + "，已隔离不加载");
        }
        while (v < LATEST) {
            Function<com.google.gson.JsonObject, com.google.gson.JsonObject> f = MIGRATORS.get(v);
            if (f == null) {
                throw new IllegalStateException("缺少 v" + v + "→v" + (v + 1) + " 迁移器");
            }
            json = f.apply(json);
            v = json.get("schemaVersion").getAsInt();
        }
        return json;
    }
}

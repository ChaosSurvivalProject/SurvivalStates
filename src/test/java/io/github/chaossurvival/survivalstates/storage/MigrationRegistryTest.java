package io.github.chaossurvival.survivalstates.storage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 存档迁移测试：v1→v2、未知版本隔离、幂等。 */
class MigrationRegistryTest {

    @Test
    void v1迁移到v2() {
        JsonObject v1 = JsonParser.parseString(
                "{\"schemaVersion\":1,\"uuid\":\"x\",\"thirst\":82.0,\"dehydrated\":true}"
        ).getAsJsonObject();
        JsonObject v2 = MigrationRegistry.migrateToLatest(v1);
        assertEquals(2, v2.get("schemaVersion").getAsInt());
        assertTrue(v2.has("hydration"));
        assertFalse(v2.has("thirst"));
        assertFalse(v2.has("dehydrated"));
        assertTrue(v2.get("hydration").getAsDouble() <= 15.0);
    }

    @Test
    void 非脱水v1水分不低于25() {
        JsonObject v1 = JsonParser.parseString(
                "{\"schemaVersion\":1,\"thirst\":30.0,\"dehydrated\":false}"
        ).getAsJsonObject();
        JsonObject v2 = MigrationRegistry.migrateToLatest(v1);
        assertTrue(v2.get("hydration").getAsDouble() >= 25.0);
    }

    @Test
    void 未知版本抛出隔离() {
        JsonObject v99 = JsonParser.parseString(
                "{\"schemaVersion\":99,\"uuid\":\"x\"}"
        ).getAsJsonObject();
        assertThrows(IllegalArgumentException.class,
                () -> MigrationRegistry.migrateToLatest(v99));
    }

    @Test
    void 已是最新版幂等() {
        JsonObject v2 = JsonParser.parseString(
                "{\"schemaVersion\":2,\"hydration\":80.0}"
        ).getAsJsonObject();
        JsonObject out = MigrationRegistry.migrateToLatest(v2);
        assertEquals(80.0, out.get("hydration").getAsDouble());
    }
}

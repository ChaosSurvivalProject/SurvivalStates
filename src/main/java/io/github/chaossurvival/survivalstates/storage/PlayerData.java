package io.github.chaossurvival.survivalstates.storage;

/**
 * 玩家存档数据对象（可序列化 POJO）。对应蓝图第六节的 playerdata/&lt;uuid&gt;.json。
 *
 * <p>session 以简化字符串保存（休息会话在离线时不按现实时间累积），
 * 迁移由 {@link MigrationRegistry} 负责。checksum 覆盖除自身外的全部字段。</p>
 */
public final class PlayerData {

    public int schemaVersion = 1;
    public String uuid;
    public double hydration = 100.0;
    public double fatigue = 0.0;
    public double temperature = 50.0;
    public double activity = 0.0;
    public double quality = 80.0;
    public double shortLoad = 0.0;
    public double envTemp = 21.0;
    public long onlineSeconds = 0L;
    public long lastRestEpochSeconds = 0L;
    public String session = null;
    public int attributeEpoch = 0;
    public String checksum = "";

    /** 生成新档（在线态首次保存）。 */
    public static PlayerData create(java.util.UUID uuid) {
        PlayerData d = new PlayerData();
        d.uuid = uuid.toString();
        return d;
    }
}

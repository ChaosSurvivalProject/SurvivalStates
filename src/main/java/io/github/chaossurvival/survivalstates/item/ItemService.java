package io.github.chaossurvival.survivalstates.item;

import io.github.chaossurvival.survivalstates.state.FoodSafety.Quality;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 物品身份服务：用 ItemMeta 的 PDC + 固定 NamespacedKey 标识水/食物。
 *
 * <p>1.21.1 中不使用 ItemStack#editPersistentDataContainer；显示名与 Lore 只作提示，
 * 不作为判定依据。自定义模型由资源包 custom_model_data 映射，服务端只发模型选择值。
 * PDC 只放 item_id / quality / data_version，不放完整状态。</p>
 */
public final class ItemService {

    public static final int DATA_VERSION = 1;

    private final NamespacedKey keyItemId;
    private final NamespacedKey keyQuality;
    private final NamespacedKey keyDataVersion;
    private final NamespacedKey keyCmd;

    public ItemService(JavaPlugin plugin) {
        this.keyItemId = new NamespacedKey(plugin, "item_id");
        this.keyQuality = new NamespacedKey(plugin, "quality");
        this.keyDataVersion = new NamespacedKey(plugin, "data_version");
        this.keyCmd = new NamespacedKey(plugin, "cmd");
    }

    /** 读取物品品质；非本插件物品返回 null。 */
    public Quality quality(ItemStack item) {
        PdcView view = read(item);
        if (view == null) {
            return null;
        }
        try {
            return Quality.valueOf(view.quality());
        } catch (IllegalArgumentException e) {
            return Quality.CLEAN; // 未知品质安全降级
        }
    }

    /** 是否为饮品。 */
    public boolean isWater(ItemStack item) {
        PdcView v = read(item);
        return v != null && v.itemId() != null && v.itemId().startsWith("water");
    }

    /** 读取自定义模型选择值（供资源包 CMD 映射）。 */
    public Integer customModel(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.get(keyCmd, PersistentDataType.INTEGER);
    }

    /** 发放一个带 PDC 的调试物品。 */
    public ItemStack create(String itemId, Quality quality, Material material, int cmd, String displayName) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(net.kyori.adventure.text.Component.text(displayName));
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(keyItemId, PersistentDataType.STRING, itemId);
        pdc.set(keyQuality, PersistentDataType.STRING, quality.name());
        pdc.set(keyDataVersion, PersistentDataType.INTEGER, DATA_VERSION);
        pdc.set(keyCmd, PersistentDataType.INTEGER, cmd);
        item.setItemMeta(meta);
        return item;
    }

    private PdcView read(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String id = pdc.get(keyItemId, PersistentDataType.STRING);
        String q = pdc.get(keyQuality, PersistentDataType.STRING);
        Integer version = pdc.get(keyDataVersion, PersistentDataType.INTEGER);
        if (id == null || q == null) {
            return null;
        }
        if (version != null && version > DATA_VERSION) {
            // 未知高版本数据：保持原样，按普通物品处理。
            return null;
        }
        return new PdcView(id, q);
    }

    private record PdcView(String itemId, String quality) {
    }
}

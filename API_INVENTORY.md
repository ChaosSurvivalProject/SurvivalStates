# API 核验清单（API_INVENTORY.md）

> 对应开发蓝图「阶段 0：冻结契约」。目标平台固定为 **Minecraft 1.21.1 / PurPur build 2329 / Java 21**，
> 不追求「1.21.x 兼容」。本清单在真实服务端构建/启动时逐项核验；无法运行期验证的项目标注「开发期验证」。

## 0. 平台与构建

| 项 | 期望值 | 核验方式 | 状态 |
|---|---|---|---|
| JDK | 21（`--release 21` / toolchain 21） | `./gradlew build` + `VersionProbe` | 构建期核验 |
| Minecraft | 1.21.1 | `Bukkit.getMinecraftVersion()` | 启动门禁 |
| 服务端 | PurPur build 2329 | 启动横幅人工确认 | 人工核验 |
| 依赖 | `org.purpurmc.purpur:purpur-api:1.21.1-R0.1-SNAPSHOT`（compileOnly） | Gradle 解析 | 构建期核验 |
| 禁止项 | 无 NMS / `net.minecraft.*` / `org.bukkit.craftbukkit.*` / 反射内部类 | 源码 grep + code review | 静态核验 |

> PurPur 1.21.1 下载页标注最终构建 2329（2024-11-01）并提示旧版本不再获支持，
> 因此必须自建版本锁定、测试服与回滚能力，不跟随 latest。

## 1. 呈现层

| API | 用途 | 1.21.1 方法 | 核验要点 |
|---|---|---|---|
| BossBar | 三条状态条 | `Bukkit.createBossBar(String, BarColor, BarStyle)`、`addPlayer`、`setProgress`、`setTitle` | 最多 3 条；进服/维度/重连后重新附加；不逐 tick 重建 |
| Sound | 预警/休息/饮水 | `Sound.valueOf(name)` + `player.playSound(loc, sound, category, vol, pitch)` | 启动验证枚举名；走 `SoundCategory.PLAYERS` |
| Title | 3 秒预警 | `Player.showTitle(...)`（可选） | 未在首版启用，保留扩展点 |

## 2. 属性层（1.21.1 常量前缀核验）

| 语义 | 1.21.1 常量 | 修饰符 | 状态 |
|---|---|---|---|
| 最大生命 | `Attribute.GENERIC_MAX_HEALTH` | 默认关闭 | 启动反射核验 |
| 移动速度 | `Attribute.GENERIC_MOVEMENT_SPEED` | `ADD_SCALAR` -0.15·f | 启动反射核验 |
| 攻击伤害 | `Attribute.GENERIC_ATTACK_DAMAGE` | `ADD_SCALAR` -0.22·f | 启动反射核验 |
| 攻击速度 | `Attribute.GENERIC_ATTACK_SPEED` | 默认关闭 | 启动反射核验 |
| 挖掘效率 | `Attribute.PLAYER_MINING_EFFICIENCY` | `ADD_SCALAR` -0.18·f | 启动反射核验 |

> 高版本教程的 `Attribute.MAX_HEALTH` **不可照抄**。`VersionProbe` 会在启动时
> 对以上 5 个常量做 `valueOf` 存在性检查，缺失即进入诊断模式。
> 修饰符使用固定 `NamespacedKey`，先 `removeModifier(key)` 再 `addModifier`，
> 绝不 `setBaseValue`。

## 3. 物品身份与持久化

| API | 用途 | 1.21.1 正确用法 | 坑 |
|---|---|---|---|
| PDC | 物品 ID/品质/版本 | `ItemMeta#getPersistentDataContainer()` → 写后 `setItemMeta` | 不使用 `ItemStack#editPersistentDataContainer`（非目标 API） |
| NamespacedKey | `survival:item_id` 等 | `new NamespacedKey(plugin, "...")` | 命名空间固定，配置改名不影响旧物品 |
| DataComponent | 自定义模型（可选） | 资源包 `custom_model_data` 映射 | 组件 API 可能随小版本破坏，官方不承诺跨版本兼容；首版只用 PDC + 资源包 CMD |
| 存档 | playerdata JSON | Gson + 同目录 tmp → fsync → `ATOMIC_MOVE` | `ATOMIC_MOVE` 跨 FileStore 会失败，先验 FileStore；`OfflinePlayer` PDC 只读且可能阻塞 |

## 4. 事件与调度

| API | 用途 | 核验要点 |
|---|---|---|
| `PlayerItemConsumeEvent` | 读取最终 `ItemStack` | 检查 `isCancelled()`；下一 tick 验证物品真实消耗；消费序号幂等 |
| `PlayerBedEnterEvent` / `PlayerBedLeaveEvent` | 床入口/离床信号 | 离床事件无法可靠区分原因，只作即时信号，权威在 2 秒周期床校验 |
| `BukkitScheduler#runTaskTimer` | 状态 tick / 保存 | 20 tick = 1 秒只是名义值；积分用真实经过时间 dt 并夹到 0.25，卡顿不瞬间灌满状态 |
| 线程边界 | 主线程 vs 异步 | Player/World/Item/Attribute/BossBar 只在主线程；HTTP/哈希/压缩/磁盘 IO 异步，完成后回主线程 |

## 5. 自动更新

| API | 用途 | 核验要点 |
|---|---|---|
| `java.net.http.HttpClient` | Release 发现/下载 | 处理 200 与 302；校验最终域名、限制响应体积、指数退避 |
| `MessageDigest` SHA-256 | 防传输损坏 | 边写边算；与清单哈希比对 |
| `Signature` Ed25519 | 验证发布者 | canonical manifest 去签名后验签；TOFU 基线指纹展示给管理员确认 |
| `Files.move(ATOMIC_MOVE)` | 原子暂存 | 同 FileStore 才原子；跨 FileStore 降级并告警 |
| `Server#shutdown()` | 停止（非重启） | 插件只写 `restart-intent.json` 并请求外部守护；绝不 `System.exit` 或 `/reload` 充当重启 |

## 6. 无法在运行期自动核验、需开发期验证的项

- 幻翼统计：`Player#setStatistic(Statistic.TIME_SINCE_REST, 0)` 是否在 1.21.1 生效且符合预期 —— 默认关闭 `sleep.countAsVanillaRest`，需在测试服实测后再开。
- 原版食物从 20 到 0 的基准秒数 —— 必须用标准脚本（站立、不跳、不战斗、不回血）实测，不能假设固定 30 分钟。
- `Player#sleep(Location, boolean)` 在各反作弊下的表现 —— 若被拦截，配置 `visualSleepRequired=false`，会话照常继续。

## 结论

首版实现严格使用上述公共 API，不含任何 NMS/CraftBukkit/Purpur 私有实现。
`VersionProbe` 在每次启动时输出实际版本与缺失项；关键不匹配即进入诊断模式，
宁可禁用玩法积分，也不卡死或补刀玩家。

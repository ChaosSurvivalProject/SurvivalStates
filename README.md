# SurvivalStates — PurPur 生存状态插件

<p align="center">
  <img src="assets/images/banner.png" alt="SurvivalStates 宣传图" width="820">
</p>


一个面向 **Minecraft Java 1.21.1 / PurPur build 2329 / Java 21** 的生存状态插件。
提供口渴、疲劳、体感温度、活动量与强制睡眠机制，呈现方式为 **BossBar + 物品模型 + 音效**。

> **这是 Bukkit/Paper/PurPur 插件，不是 Mod。** 不使用 Forge/Fabric/NeoForge，
> 不含任何 NMS、`net.minecraft.*`、`org.bukkit.craftbukkit.*` 或 Purpur 私有实现。零运行依赖。

## 效果预览

<p align="center">
  <img src="assets/images/demo.png" alt="SurvivalStates 演示图" width="720">
</p>


## 核心设计

- **0–100 连续状态量表**：水分 H、疲劳 F、体感温度 T、活动量 A、休息质量 Q；HUD 只做显示取整。
- **240/120 分钟是校准基准，不是玩法保证**：中性条件下疲劳 0→100 用 14400 秒、水分 100→0 用 7200 秒；联机后由耦合项改写，以实测校准。
- **强制睡眠不接管原版昼夜**：插件独立累计休息会话，不改世界时间、不改 `players_sleeping_percentage`、不设无敌。
- **属性只施加小幅惩罚**：固定 `NamespacedKey` 的 `ADD_SCALAR` 修饰符；普通难度原版饥饿已能把生命压到 1，脱水不再造成直接伤害。
- **自动更新可验证、可暂停、可回滚**：SHA-256 + Ed25519 双重验证、异步下载、原子暂存；**不在 JVM 内热替换 JAR**，真正替换由外部守护完成。

## 安装

1. 准备 PurPur **1.21.1 build 2329** 服务端，Java **21**。
2. 将 `build/libs/survival-states-1.0.0.jar` 放入 `plugins/`。
3. 启动服务器，生成 `plugins/SurvivalStates/config.yml`。
4. 必须确认启动日志无「版本核验」严重警告；否则插件进入诊断模式，状态积分不启用。
5. （可选）安装配套资源包以显示自定义水/食物模型；服务端只发模型选择值，不要求客户端装 Mod。

> PurPur 1.21.1 已被上游标记为旧版本、不再官方支持。请在独立测试服验证后再上生产，并准备回滚。

## 命令与权限

| 命令 | 权限 | 功能 |
|---|---|---|
| `/survival status [玩家]` | `survival.status.self`（本人）/ `survival.status.other` | 查看精确状态与会话 |
| `/survival sleep` | `survival.sleep` | 主动尝试休息（不绕过安全条件） |
| `/survival drink <clean\|suspect\|bad>` | `survival.item.give` | 发放调试水 |
| `/survival feed <品质> [数量]` | `survival.item.give` | 发放调试食物 |
| `/survival set <状态> <值>` | `survival.admin.set` | 设置状态边界（写入审计） |
| `/survival reload` | `survival.admin.reload` | 仅安全重载（消息/音效） |
| `/survival update <check\|apply\|cancel\|status>` | `survival.admin.update` | 更新管理 |
| `/survival doctor` | `survival.admin.doctor` | API/世界/任务/更新诊断 |

默认：`survival.status.self`、`survival.sleep` 为 true；`survival.bypass.fatigue` 与全部 admin 权限为 OP。

## 配置

完整 `config.yml` 随插件生成，分层为运行时快照、内容定义与内部状态。
数学模型（校准、水分、疲劳、温度、活动、属性、睡眠、食品安全）改动需**重启**；
消息与音效可通过 `/survival reload` 安全重载。非法数值回退默认并写入 `config-resolved.yml`。

## 持久化与迁移

- 每 30 秒 + 关键事件（退出、死亡、强制睡眠起止、换世界）原子保存 `playerdata/<uuid>.json`。
- 先写同目录 `.tmp` → `fsync` → `ATOMIC_MOVE`；连续失败 3 次进入只读故障态并告警。
- 坏档隔离到 `quarantined/`，按默认态恢复；未知高版本只读隔离不覆盖。
- 迁移注册表支持 v1→v2（`thirst`→`hydration`、`dehydrated`→`H<20`），先备份、幂等。
- **离线不按现实时间累积状态**。

## 自动更新与外部守护

插件不会在进程内热替换 JAR。流程：

1. 异步检查 GitHub Release → 解析 manifest。
2. 下载到 `updates/<hash>.jar.part`，边写边算 SHA-256。
3. 校验大小、哈希、ZIP/JAR 结构、Ed25519 签名。
4. 同 FileStore 原子暂存；备份当前 JAR/配置/存档（保留 5 代）。
5. 广播倒计时、写 `restart-intent.json`、请求外部守护。
6. 外部守护确认进程退出后原子替换并启动；120 秒无健康信号则自动回滚。

Token **不写入配置**，仅通过环境变量提供给守护/发现端。公钥指纹首次信任需管理员确认（TOFU）。

守护脚本示例职责（需按实际部署实现）：

```bash
# 1) 等待服务器进程正常退出（检查日志出现 "SurvivalStates 已安全停用" 或 PID 消失）
# 2) 校验 updates/restart-intent.json 中的 sha256
# 3) 同目录原子替换 plugins/survival-states-1.0.0.jar（mv -f 同文件系统）
# 4) 启动服务端；120 秒内检测健康文件/日志版本，失败则用 backups/<ts>/ 回滚
```

## 故障安全

「宁可少机制，不可卡玩家」：API 缺失进诊断模式；状态任务连续异常不补算大段 dt；
属性异常先移除插件键；BossBar 异常关 UI 保留逻辑；物品解析异常按普通食物处理。
`/reload` 不作为支持流程；disable 时取消任务、移除 BossBar/修饰符、flush 保存。

## 构建与测试

```bash
./gradlew build       # 编译 + 运行全部单元测试
./gradlew test        # 仅测试
```

测试覆盖：240/120 校准、NaN/越界、10 秒卡顿、阈值抖动、高低温、可疑 50% 二项、
饮食幂等、迁移、坏哈希/坏签名/截断/降级。详见 [TESTING.md](TESTING.md)。

## 已知限制与开发期验证项

- 幻翼原版统计是否随插件休息重置：默认关闭，需 1.21.1 实测。
- 原版食物基准秒数：必须用标准脚本实测，不能假设固定 30 分钟。
- 反作弊对视觉 `sleep` 的拦截：`visualSleepRequired=false` 时会话仍继续。
- 群系温度、火源、拥挤度等情境当前为简化采样，可在 `config.yml` 与 `EnvironmentModel` 中扩展。

## 许可

MIT License，见 [LICENSE](LICENSE)。

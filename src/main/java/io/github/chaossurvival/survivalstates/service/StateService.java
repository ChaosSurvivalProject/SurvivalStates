package io.github.chaossurvival.survivalstates.service;

import io.github.chaossurvival.survivalstates.config.ConfigService;
import io.github.chaossurvival.survivalstates.state.*;
import io.github.chaossurvival.survivalstates.ui.BossBarManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 状态服务：整个插件的运行时核心。
 *
 * <p>在 Bukkit 主线程每 stateIntervalTicks 积分一次；环境每 environmentIntervalTicks
 * 采样一次；所有 Player/World 访问只在主线程。HTTP/哈希/磁盘 IO 在别处异步。
 * 线程模型：ConcurrentHashMap 只存数据，不跨线程修改 Bukkit 对象。</p>
 */
public final class StateService {

    private final JavaPlugin plugin;
    private final ConfigService config;
    private final Integrator integrator;
    private final EnvironmentModel envModel;
    private final BossBarManager bossBars;
    private final AttributeService attributes;
    private final SoundService sounds;

    /** 在线玩家运行时状态，仅主线程读写。 */
    private final ConcurrentHashMap<UUID, PlayerRuntime> runtimes = new ConcurrentHashMap<>();

    private int tickCounter = 0;
    private long lastHudUpdate = 0;
    private long lastTickNanos = 0;

    public StateService(JavaPlugin plugin, ConfigService config, BossBarManager bossBars,
                        AttributeService attributes, SoundService sounds) {
        this.plugin = plugin;
        this.config = config;
        this.bossBars = bossBars;
        this.attributes = attributes;
        this.sounds = sounds;
        double baseH = config.get().baseHydrationDrain();
        double baseF = config.get().baseFatigueGain();
        // 用配置里的基准速率构造 Tuning，保持与校准一致。
        Integrator.Tuning tuning = new Integrator.Tuning(
                baseH, baseF, 1.20, 0.35, 0.35, 0.25, 0.75, 0.45, 0.30, 0.20
        );
        this.integrator = new Integrator(tuning, null);
        this.envModel = new EnvironmentModel(EnvironmentModel.Tuning.defaults());
    }

    public PlayerRuntime get(UUID uuid) {
        return runtimes.get(uuid);
    }

    public PlayerRuntime getOrCreate(UUID uuid, io.github.chaossurvival.survivalstates.storage.PlayerData data) {
        return runtimes.computeIfAbsent(uuid,
                u -> new PlayerRuntime(u, data, System.currentTimeMillis()));
    }

    public void remove(UUID uuid) {
        runtimes.remove(uuid);
    }

    public java.util.Collection<PlayerRuntime> all() {
        return runtimes.values();
    }

    /** 环境采样（主线程，每 environmentIntervalTicks 调用一次）。 */
    public void sampleEnvironments() {
        for (PlayerRuntime rt : runtimes.values()) {
            Player p = Bukkit.getPlayer(rt.uuid());
            if (p == null || !p.isOnline()) {
                continue;
            }
            rt.setEnvironment(sampleEnvironment(p));
        }
    }

    private EnvironmentSample sampleEnvironment(Player p) {
        Location loc = p.getLocation();
        World world = loc.getWorld();
        if (world == null) {
            return EnvironmentSample.neutral();
        }
        String env = world.getEnvironment().name();
        boolean nether = env.equals("NETHER");
        boolean over = env.equals("NORMAL");
        boolean end = env.equals("THE_END");

        double biomeTemp = 0.7;
        double desert = 0, cold = 0;
        try {
            // 1.21.1 群系温度通过 World#getTemperature(x,z) 获取，不在 Biome 枚举上。
            biomeTemp = world.getTemperature(loc.getBlockX(), loc.getBlockZ());
            String biome = loc.getBlock().getBiome().name().toUpperCase();
            if (biome.contains("DESERT") || biome.contains("BADLANDS") || biome.contains("NETHER")) {
                desert = 1;
            }
            if (biome.contains("SNOW") || biome.contains("FROZEN") || biome.contains("ICE")
                    || biome.contains("TAIGA")) {
                cold = 1;
            }
        } catch (Throwable ignored) {
            // 群系 API 异常时退化为中性，不让插件崩溃。
        }

        double flatOverride = nether ? 78.0 : Double.NaN;
        boolean worldEnabled = !end; // 末地禁用温度模型

        return new EnvironmentSample(
                biomeTemp, loc.getY(), desert, nether ? 1.0 : 0.0, cold,
                world.hasStorm() ? 1.0 : 0.0,
                loc.getBlock().getLightFromSky() > 12 ? 1.0 : 0.0,
                nearFire(p) ? 1.0 : 0.0,
                p.isInWater() ? 1.0 : 0.0,
                armorHeat(p),
                0.0, // crowding 简化为 0，基础公式已足够
                worldEnabled, flatOverride
        );
    }

    private boolean nearFire(Player p) {
        // 简化：玩家着火或站在岩浆附近视为有热源；不做逐方块昂贵扫描。
        return p.getFireTicks() > 0;
    }

    private double armorHeat(Player p) {
        // 简化：穿皮革/盔甲视为轻微保暖；细节可在配置中扩展。
        var inv = p.getInventory().getArmorContents();
        int pieces = 0;
        for (org.bukkit.inventory.ItemStack item : inv) {
            if (item != null && item.getType() != org.bukkit.Material.AIR) {
                pieces++;
            }
        }
        return pieces / 4.0;
    }

    /** 每 tick 主循环：对所有在线玩家积分与渲染。
     *
     * <p>调度器每个服务器 tick 调用一次；这里用真实经过时间计算 dt（夹到单帧上限），
     * 使积分速率与调度频率解耦，保证 7200/14400 秒校准在不同 tick 率下都成立。
     * 服务器卡顿时单次 dt 被夹到上限，宁可慢一点也不瞬间灌满状态。</p> */
    public void tick() {
        long now = System.nanoTime();
        double realDt = lastTickNanos == 0 ? 0.0 : (now - lastTickNanos) / 1_000_000_000.0;
        lastTickNanos = now;
        // 各 tick 共用同一 dt；夹到单帧上限。
        double dt = MathUtil.clampDt(realDt, config.get().maxIntegrationSeconds());

        tickCounter++;
        boolean sampleEnv = tickCounter % Math.max(1, config.get().environmentIntervalTicks()) == 0;
        if (sampleEnv) {
            sampleEnvironments();
        }

        for (PlayerRuntime rt : runtimes.values()) {
            Player p = Bukkit.getPlayer(rt.uuid());
            if (p == null || !p.isOnline()) {
                continue;
            }
            // 创造/旁观：冻结状态并清除属性。
            org.bukkit.GameMode mode = p.getGameMode();
            if (mode == org.bukkit.GameMode.CREATIVE || mode == org.bukkit.GameMode.SPECTATOR) {
                attributes.clear(p);
                bossBars.detach(p);
                continue;
            }

            // 低血保护：生命 <= enter 进入，> exit 退出，停止额外压力与惩罚。
            double health = p.getHealth();
            boolean lowHp;
            if (rt.isLowHealthProtected()) {
                lowHp = health <= config.get().lowHealthExit();
            } else {
                lowHp = health <= config.get().lowHealthEnter();
            }
            rt.setLowHealthProtected(lowHp);

            // 构造积分输入。
            Integrator.Input input = new Integrator.Input(
                    dt,
                    envModel.target(rt.environment()),
                    rt.horizontalSpeed(),
                    rt.breaksLast5s(),
                    rt.attacksLast5s(),
                    rt.jumpOrSprintFlag(),
                    lowHp ? 0.2 : rt.foodCredit(),  // 低血时抵扣疲劳增长
                    rt.mealBufferRate(),
                    p.isFlying(),
                    p.isInsideVehicle()
            );

            Integrator.Result result = integrator.integrate(rt.state(), input);
            StateVector next = result.state();
            // BUGFIX: 20 TPS 下 realDt≈0.05s，直接取整恒为 0，在线秒数永不增长。
            // 用 double 累计小数部分，满 1 秒再进位。
            double onlineAcc = rt.onlineSecondCarry() + Math.max(0.0, realDt);
            long addedSeconds = (long) Math.floor(onlineAcc);
            rt.setOnlineSecondCarry(onlineAcc - addedSeconds);
            next = new StateVector(next.hydration(), next.fatigue(), next.temperature(),
                    next.activity(), next.quality(), next.shortLoad(), next.envTemp(),
                    rt.state().onlineSeconds() + addedSeconds);
            rt.setState(next);
            rt.consumeRecovery(dt);

            // 每 5 秒归零活动窗口。
            if (tickCounter % 100 == 0) {
                rt.resetActivityWindow();
            }

            // 滞后状态事件。
            List<Hysteresis.Event> events = rt.hysteresis().update(
                    next.hydration(), next.fatigue(), next.temperature());
            handleEvents(p, rt, events);

            // 属性与 HUD（≤5Hz）。
            attributes.apply(p, next.fatigue(), lowHp);
            if (System.currentTimeMillis() - lastHudUpdate >= 200) {
                updateHud(p, rt, next);
            }
        }
        if (System.currentTimeMillis() - lastHudUpdate >= 200) {
            lastHudUpdate = System.currentTimeMillis();
        }
    }

    private void updateHud(Player p, PlayerRuntime rt, StateVector s) {
        bossBars.attach(p);
        String tempTier = s.temperature() > 62 ? "§c炎热"
                : s.temperature() < 24 ? "§b寒冷" : "§a适宜";
        String summary = "§f状态 §7| " + tempTier
                + " §7| 疲劳 " + Math.round(s.fatigue());
        bossBars.update(p, s.hydration(), s.fatigue(), s.temperature(), summary);
    }

    private void handleEvents(Player p, PlayerRuntime rt, List<Hysteresis.Event> events) {
        for (Hysteresis.Event e : events) {
            if (!e.entered()) {
                continue;
            }
            switch (e.band()) {
                case FATIGUE_WARNING -> {
                    p.sendMessage(config.getMessages().simple("warnFatigue"));
                    sounds.play(p, "warning");
                }
                case TEMP_HOT -> p.sendMessage("§c你感到炎热，注意补水。");
                case TEMP_COLD -> p.sendMessage("§b你感到寒冷，食物消耗会加快。");
                case HYDRATION_DEHYDRATED -> p.sendMessage("§c你严重缺水！");
                default -> {
                    // 审计日志可在此扩展；平时不逐 tick 刷屏。
                }
            }
        }
    }
}

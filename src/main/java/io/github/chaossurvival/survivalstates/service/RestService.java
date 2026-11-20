package io.github.chaossurvival.survivalstates.service;

import io.github.chaossurvival.survivalstates.config.ConfigService;
import io.github.chaossurvival.survivalstates.state.StateVector;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 休息编排服务：把强制睡眠状态机接到 Bukkit 事件与tick。
 *
 * <p>疲劳 ≥97 预警，≥100 进入 3 秒 ARMED，然后 ACTIVE。床只作视觉锚点，
 * 权威进度是 RestSession。不修改世界时间、不改 players_sleeping_percentage、
 * 不设无敌。成功休息给 60 秒恢复锁定。</p>
 */
public final class RestService {

    private final JavaPlugin plugin;
    private final ConfigService config;
    private final StateService states;
    private final SoundService sounds;

    /** 恢复锁定：玩家 UUID → 解除的 epoch 毫秒。 */
    private final java.util.Map<java.util.UUID, Long> lockoutUntil = new java.util.HashMap<>();

    public RestService(JavaPlugin plugin, ConfigService config, StateService states, SoundService sounds) {
        this.plugin = plugin;
        this.config = config;
        this.states = states;
        this.sounds = sounds;
    }

    public boolean isLockedOut(java.util.UUID uuid) {
        Long until = lockoutUntil.get(uuid);
        return until != null && System.currentTimeMillis() < until;
    }

    /** 每 tick 推进休息会话。返回是否处于休息态（供 StateService 调整积分）。 */
    public boolean tickRest() {
        boolean anyResting = false;
        for (PlayerRuntime rt : states.all()) {
            Player p = Bukkit.getPlayer(rt.uuid());
            if (p == null || !p.isOnline()
                    || p.getGameMode() == org.bukkit.GameMode.CREATIVE
                    || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                rt.setRestSession(null);
                continue;
            }
            RestSession session = rt.restSession();

            // 疲劳达到强制阈值且无会话、无豁免、无锁定时，创建新会话。
            if (session == null
                    && rt.state().fatigue() >= 100.0
                    && !p.hasPermission("survival.bypass.fatigue")
                    && !isLockedOut(rt.uuid())) {
                session = RestSession.create(rt.state().fatigue(), rt.state().quality(),
                        3.0, rt.rng());
                rt.setRestSession(session);
                p.sendMessage(config.getMessages().simple("forceSleepArmed"));
                sounds.play(p, "danger");
            }

            if (session == null) {
                continue;
            }
            anyResting = true;

            // 床与安全条件评估。
            Location bed = findBed(p);
            boolean bedAvailable = bed != null;
            boolean safety = !hostileNearby(p);
            // BUGFIX: lastDamageEpoch 存的是 epoch 秒(/1000)，原先用毫秒当前时间相减恒为真，
            // 受伤后 5 秒"不安静"判定完全失效。统一到秒单位。
            boolean quiet = System.currentTimeMillis() / 1000L - session.lastDamageEpoch() > 5
                    && rt.breaksLast5s() == 0 && rt.attacksLast5s() == 0;
            boolean hungerOkay = p.getFoodLevel() > 6;

            // BUGFIX: tryResume 此前从未被调用，5 秒回床恢复窗口是死代码；
            // 玩家受伤后即便回到床上也必然被打断。满足回床/安全/安静条件时尝试恢复。
            if (session.phase() == RestSession.Phase.PAUSED
                    && bedAvailable && safety && quiet && hungerOkay) {
                session.tryResume();
            }
            double dt = 0.05; // 每个服务器 tick 约 0.05 秒，RestSession 内部累计有效秒数
            double noBedEff = plugin.getConfig().getDouble("sleep.noBedEfficiency", 0.55);
            boolean finished = session.tick(dt, bedAvailable, safety, quiet, hungerOkay, noBedEff);

            // ARMED 阶段：视觉锚点（仅一次）。
            if (session.phase() == RestSession.Phase.ACTIVE && bed != null
                    && !p.isSleeping() && !session.isSleepAnchorSet()) {
                try {
                    p.sleep(bed, false);
                    session.markSleepAnchorSet();
                } catch (Throwable ignored) {
                    // 反作弊或原版阻止视觉睡眠时，会话仍继续（visualSleepRequired=false）。
                }
            }

            if (finished) {
                finishSession(rt, p, session);
            }
        }
        return anyResting;
    }

    private void finishSession(PlayerRuntime rt, Player p, RestSession session) {
        StateVector s = rt.state();
        if (session.phase() == RestSession.Phase.DONE) {
            double relief = session.fatigueRelief(s.fatigue());
            double newFatigue = Math.max(0.0, s.fatigue() - relief);
            double newActivity = s.activity() * 0.35;
            double newQuality = session.finalQuality();
            rt.setState(new StateVector(s.hydration(), newFatigue, s.temperature(),
                    newActivity, newQuality, s.shortLoad(), s.envTemp(), s.onlineSeconds()));
            long lockoutMs = plugin.getConfig().getLong("sleep.recoveryLockoutSeconds", 60L) * 1000L;
            lockoutUntil.put(rt.uuid(), System.currentTimeMillis() + lockoutMs);
            p.sendMessage(config.getMessages().simple("restFinished"));
            sounds.play(p, "restStart");
            tryWake(p);
        } else {
            // ABORTED：保留高疲劳，30 秒后重新提示。
            p.sendMessage(config.getMessages().simple("restInterrupted"));
            tryWake(p);
        }
        rt.setRestSession(null);
    }

    private void tryWake(Player p) {
        try {
            if (p.isSleeping()) {
                p.wakeup(false);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 主动休息命令：只有满足安全条件才创建会话，不绕过。 */
    public boolean tryStartRest(Player p) {
        PlayerRuntime rt = states.get(p.getUniqueId());
        if (rt == null || rt.restSession() != null) {
            return false;
        }
        if (isLockedOut(p.getUniqueId())) {
            p.sendMessage("§7恢复中，暂时无需休息。");
            return false;
        }
        RestSession session = RestSession.create(
                Math.max(60.0, rt.state().fatigue()), rt.state().quality(), 1.0, rt.rng());
        rt.setRestSession(session);
        p.sendMessage(config.getMessages().simple("restStarted"));
        return true;
    }

    /** 打断会话（受伤/传送/换世界等）。 */
    public void interrupt(Player p, String reason) {
        PlayerRuntime rt = states.get(p.getUniqueId());
        if (rt == null || rt.restSession() == null) {
            return;
        }
        rt.restSession().interrupt(5.0); // resumeWindowSeconds
        rt.restSession().recordDamage(System.currentTimeMillis() / 1000L);
        p.sendMessage(config.getMessages().simple("restInterrupted"));
    }

    /** 死亡：清空会话，疲劳降至 35，口渴升至 55（濒死重置，配置开关控制）。 */
    public void onDeath(Player p) {
        PlayerRuntime rt = states.get(p.getUniqueId());
        if (rt == null) {
            return;
        }
        rt.setRestSession(null);
        StateVector s = rt.state();
        rt.setState(new StateVector(s.hydration(), Math.min(s.fatigue(), 35.0),
                s.temperature(), s.activity(), s.quality(), s.shortLoad(), s.envTemp(), s.onlineSeconds()));
        lockoutUntil.remove(p.getUniqueId());
    }

    /** 找 8 格内可用床。 */
    private Location findBed(Player p) {
        Location loc = p.getLocation();
        // 优先玩家当前床/锚点；简化为按记录点或半径内搜索。
        Location bed = p.getBedSpawnLocation();
        if (bed != null && bed.getWorld().equals(loc.getWorld()) && bed.distance(loc) <= 8.0) {
            return bed;
        }
        // 近邻搜索（有限半径，避免昂贵扫描）。
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    Block b = loc.clone().add(dx, dy, dz).getBlock();
                    if (b.getType().name().endsWith("_BED")) {
                        return b.getLocation();
                    }
                }
            }
        }
        return null;
    }

    private boolean hostileNearby(Player p) {
        return !p.getNearbyEntities(8, 8, 8).stream()
                .filter(e -> e instanceof org.bukkit.entity.Monster)
                .toList().isEmpty();
    }
}

package io.github.chaossurvival.survivalstates.listener;

import io.github.chaossurvival.survivalstates.config.ConfigService;
import io.github.chaossurvival.survivalstates.item.ItemService;
import io.github.chaossurvival.survivalstates.service.*;
import io.github.chaossurvival.survivalstates.state.FoodSafety;
import io.github.chaossurvival.survivalstates.state.StateVector;
import io.github.chaossurvival.survivalstates.storage.StorageService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 事件监听器：主线程采集事件，转成运行时状态变更。
 *
 * <p>消费事件检查 isCancelled 与下一 tick 物品真实消耗，使用消费序号幂等；
 * 副作用刷新而非叠等级。床事件只作即时信号，权威在周期校验。</p>
 */
public final class PlayerListener implements Listener {

    private final JavaPlugin plugin;
    private final ConfigService config;
    private final StateService states;
    private final RestService rest;
    private final ItemService items;
    private final FoodSafety foodSafety;
    private final SoundService sounds;
    private final StorageService storage;

    /** 自增消费序号，用于幂等。 */
    private long consumeSeq = 0;

    public PlayerListener(JavaPlugin plugin, ConfigService config, StateService states,
                          RestService rest, ItemService items, FoodSafety foodSafety,
                          SoundService sounds, StorageService storage) {
        this.plugin = plugin;
        this.config = config;
        this.states = states;
        this.rest = rest;
        this.items = items;
        this.foodSafety = foodSafety;
        this.sounds = sounds;
        this.storage = storage;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        var data = storage.load(p.getUniqueId());
        PlayerRuntime rt = states.getOrCreate(p.getUniqueId(), data);
        p.sendMessage(config.getMessages().render("join",
                java.util.Map.of(io.github.chaossurvival.survivalstates.config.Messages.Key.HYDRATION,
                        String.valueOf(Math.round(rt.state().hydration())))));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        PlayerRuntime rt = states.get(p.getUniqueId());
        if (rt != null) {
            storage.save(rt.toData());
        }
        states.remove(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (e.isCancelled()) {
            return;
        }
        Player p = e.getPlayer();
        ItemStack item = e.getItem();
        FoodSafety.Quality quality = items.quality(item);
        if (quality == null) {
            return; // 非本插件物品，保留原版效果
        }
        boolean isWater = items.isWater(item);
        long seq = ++consumeSeq;
        PlayerRuntime rt = states.get(p.getUniqueId());
        if (rt == null || seq == rt.lastConsumeSeq()) {
            return;
        }
        rt.setLastConsumeSeq(seq);
        String badEffect = plugin.getConfig().getString("foodSafety.badWaterEffect", "POISON");
        FoodSafety.BadEffect be = "POISON".equalsIgnoreCase(badEffect)
                ? FoodSafety.BadEffect.POISON : FoodSafety.BadEffect.HUNGER;
        FoodSafety.Outcome outcome = foodSafety.resolve(quality, isWater, rt.rng(), be);

        // 下一 tick 验证物品确实减少，避免重复结算（被其他插件取消）。
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            applyOutcome(p, rt, outcome, isWater);
        }, 1L);
    }

    private void applyOutcome(Player p, PlayerRuntime rt, FoodSafety.Outcome o, boolean isWater) {
        StateVector s = rt.state();
        double hydrationGain = o.hydrationGain();
        if (isWater) {
            rt.resetWaterBufferIfHourPassed(System.currentTimeMillis() / 1000L);
            double buffer = foodSafety.clampWaterBuffer(rt.waterBufferGained(), o.fatigueBuffer());
            rt.addWaterBufferGained(buffer);
            rt.addMealBuffer(buffer);
        } else {
            rt.addFoodCredit(Math.min(0.20, 0.03 * 6)); // 食物恢复抵扣（近似营养点）
            rt.addMealBuffer(o.fatigueBuffer());
        }
        double newH = Math.min(100.0, s.hydration() + hydrationGain);
        rt.setState(new StateVector(newH, s.fatigue(), s.temperature(), s.activity(),
                s.quality(), s.shortLoad(), s.envTemp(), s.onlineSeconds()));

        p.sendMessage(config.getMessages().simple(isWater ? "drank" : "feelSick"));
        if (o.hasSideEffect()) {
            applySideEffects(p, o);
        }
    }

    private void applySideEffects(Player p, FoodSafety.Outcome o) {
        // 刷新而非叠等级：先检查已有同类效果。
        if (o.nauseaSeconds() > 0) {
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.NAUSEA, o.nauseaSeconds() * 20, 0));
        }
        if (o.hungerSeconds() > 0) {
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.HUNGER, o.hungerSeconds() * 20, 0));
        }
        if (o.poisonSeconds() > 0) {
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.POISON, o.poisonSeconds() * 20, 0));
        }
        if (o.hungerSeconds() == 1) {
            // 可疑水有害的“额外饥饿 1 点”：作为短暂 HUNGER 呈现。
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.HUNGER, 100, 0));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBedEnter(PlayerBedEnterEvent e) {
        // 只作即时信号：记录床引用，权威仍由 RestService 周期校验。
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBedLeave(PlayerBedLeaveEvent e) {
        // 无法可靠区分伤害/破坏/主动/传送，只作即时信号。
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) {
            return;
        }
        rest.interrupt(p, "damage");
        PlayerRuntime rt = states.get(p.getUniqueId());
        if (rt != null && rt.restSession() != null) {
            rt.restSession().recordDamage(System.currentTimeMillis() / 1000L);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        rest.onDeath(e.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getFrom().getWorld() != e.getTo().getWorld()) {
            rest.interrupt(e.getPlayer(), "dimension");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent e) {
        // 仅水平位移有效，飞行/载具由积分输入处理。
        PlayerRuntime rt = states.get(e.getPlayer().getUniqueId());
        if (rt == null || e.getFrom().getWorld() != e.getTo().getWorld()) {
            return;
        }
        double dx = e.getTo().getX() - e.getFrom().getX();
        double dz = e.getTo().getZ() - e.getFrom().getZ();
        double speed = Math.sqrt(dx * dx + dz * dz) * 20.0; // 每 tick 位移 → 格/秒
        rt.setHorizontalSpeed(speed);
        if (speed > 4.3) {
            rt.recordJumpOrSprint();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockBreak(org.bukkit.event.block.BlockBreakEvent e) {
        PlayerRuntime rt = states.get(e.getPlayer().getUniqueId());
        if (rt != null) {
            rt.recordBreak();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDamageByEntity(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p) {
            PlayerRuntime rt = states.get(p.getUniqueId());
            if (rt != null) {
                rt.recordAttack();
            }
        }
    }
}

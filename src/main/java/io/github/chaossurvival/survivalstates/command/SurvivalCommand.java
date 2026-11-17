package io.github.chaossurvival.survivalstates.command;

import io.github.chaossurvival.survivalstates.config.ConfigService;
import io.github.chaossurvival.survivalstates.item.ItemService;
import io.github.chaossurvival.survivalstates.service.RestService;
import io.github.chaossurvival.survivalstates.service.StateService;
import io.github.chaossurvival.survivalstates.state.FoodSafety;
import io.github.chaossurvival.survivalstates.state.StateVector;
import io.github.chaossurvival.survivalstates.storage.StorageService;
import io.github.chaossurvival.survivalstates.update.UpdateManager;
import io.github.chaossurvival.survivalstates.util.VersionProbe;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * /survival 命令处理器与 Tab 补全。
 *
 * <p>危险命令（set/drink/feed/update/doctor）记录 UUID、时间、旧值、新值到审计。
 * 本人 status 默认可用，其余按最小权限原则。</p>
 */
public final class SurvivalCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;
    private final ConfigService config;
    private final StateService states;
    private final RestService rest;
    private final ItemService items;
    private final StorageService storage;
    private final UpdateManager updater;
    private final VersionProbe.Report probe;

    public SurvivalCommand(JavaPlugin plugin, ConfigService config, StateService states,
                           RestService rest, ItemService items, StorageService storage,
                           UpdateManager updater, VersionProbe.Report probe) {
        this.plugin = plugin;
        this.config = config;
        this.states = states;
        this.rest = rest;
        this.items = items;
        this.storage = storage;
        this.updater = updater;
        this.probe = probe;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§6=== 生存状态插件 ===");
            sender.sendMessage("§7/survival status [玩家] | sleep | drink <品质> | feed <品质> [数量]");
            sender.sendMessage("§7/survival set <状态> <值> | reload | update <check|apply|cancel|status> | doctor");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "status" -> status(sender, args);
            case "sleep" -> sleep(sender);
            case "drink" -> giveDrink(sender, args);
            case "feed" -> giveFood(sender, args);
            case "set" -> set(sender, args);
            case "reload" -> reload(sender);
            case "update" -> update(sender, args);
            case "doctor" -> doctor(sender);
            default -> sender.sendMessage("§c未知子命令: " + args[0]);
        }
        return true;
    }

    private void status(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            if (!sender.hasPermission("survival.status.other")) {
                sender.sendMessage("§c你无权查看他人状态。");
                return;
            }
            target = plugin.getServer().getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage("§c玩家不在线: " + args[1]);
                return;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage("§c控制台请指定玩家名。");
            return;
        }
        var rt = states.get(target.getUniqueId());
        if (rt == null) {
            sender.sendMessage("§c该玩家无状态数据。");
            return;
        }
        StateVector s = rt.state();
        sender.sendMessage("§6=== " + target.getName() + " 的生存状态 ===");
        sender.sendMessage("§b水分: " + fmt(s.hydration()) + "  §e疲劳: " + fmt(s.fatigue()));
        sender.sendMessage("§a温度: " + fmt(s.temperature()) + "  §7活动: " + fmt(s.activity()));
        sender.sendMessage("§7质量: " + fmt(s.quality()) + "  在线秒: " + s.onlineSeconds());
        sender.sendMessage("§7休息会话: " + (rt.restSession() == null
                ? "无" : rt.restSession().phase() + " " + fmt(rt.restSession().progress() * 100) + "%"));
    }

    private void sleep(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§c仅玩家可用。");
            return;
        }
        if (!p.hasPermission("survival.sleep")) {
            sender.sendMessage("§c无权限。");
            return;
        }
        if (!rest.tryStartRest(p)) {
            p.sendMessage("§c现在无法开始休息（正在休息/恢复中）。");
        }
    }

    private void giveDrink(CommandSender sender, String[] args) {
        if (!sender.hasPermission("survival.item.give")) {
            sender.sendMessage("§c无权限。");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§7用法: /survival drink <clean|suspect|bad>");
            return;
        }
        FoodSafety.Quality q = parseQuality(args[1]);
        if (q == null) {
            sender.sendMessage("§c品质无效。");
            return;
        }
        if (sender instanceof Player p) {
            p.getInventory().addItem(items.create("water_" + q.name().toLowerCase(), q,
                    Material.POTION, 1000 + q.ordinal(), "§b" + q + " 水"));
            p.sendMessage("§a已发放水。");
        }
        audit(sender, "drink", q.name(), "-");
    }

    private void giveFood(CommandSender sender, String[] args) {
        if (!sender.hasPermission("survival.item.give")) {
            sender.sendMessage("§c无权限。");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§7用法: /survival feed <clean|suspect|bad> [数量]");
            return;
        }
        FoodSafety.Quality q = parseQuality(args[1]);
        if (q == null) {
            sender.sendMessage("§c品质无效。");
            return;
        }
        int count = args.length >= 3 ? safeInt(args[2], 1) : 1;
        if (sender instanceof Player p) {
            p.getInventory().addItem(items.create("food_" + q.name().toLowerCase(), q,
                    Material.BREAD, 2000 + q.ordinal(), "§6" + q + " 食物").asQuantity(count));
            p.sendMessage("§a已发放食物 x" + count);
        }
        audit(sender, "feed", q.name(), String.valueOf(count));
    }

    private void set(CommandSender sender, String[] args) {
        if (!sender.hasPermission("survival.admin.set")) {
            sender.sendMessage("§c无权限。");
            return;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§c仅玩家可用。");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("§7用法: /survival set <hydration|fatigue|temperature|activity|quality> <值>");
            return;
        }
        var rt = states.get(p.getUniqueId());
        if (rt == null) {
            return;
        }
        StateVector s = rt.state();
        double v = safeDouble(args[2], 0);
        StateVector next = switch (args[1].toLowerCase()) {
            case "hydration" -> new StateVector(v, s.fatigue(), s.temperature(), s.activity(),
                    s.quality(), s.shortLoad(), s.envTemp(), s.onlineSeconds());
            case "fatigue" -> new StateVector(s.hydration(), v, s.temperature(), s.activity(),
                    s.quality(), s.shortLoad(), s.envTemp(), s.onlineSeconds());
            case "temperature" -> new StateVector(s.hydration(), s.fatigue(), v, s.activity(),
                    s.quality(), s.shortLoad(), s.envTemp(), s.onlineSeconds());
            case "activity" -> new StateVector(s.hydration(), s.fatigue(), s.temperature(), v,
                    s.quality(), s.shortLoad(), s.envTemp(), s.onlineSeconds());
            case "quality" -> new StateVector(s.hydration(), s.fatigue(), s.temperature(), s.activity(),
                    v, s.shortLoad(), s.envTemp(), s.onlineSeconds());
            default -> null;
        };
        if (next == null) {
            sender.sendMessage("§c状态名无效。");
            return;
        }
        rt.setState(next);
        p.sendMessage("§a已设置 " + args[1] + " = " + v);
        audit(sender, "set:" + args[1], String.valueOf(v), "-");
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("survival.admin.reload")) {
            sender.sendMessage("§c无权限。");
            return;
        }
        config.reloadSafe();
        sender.sendMessage(config.getMessages().simple("reloadDone"));
        audit(sender, "reload", "-", "-");
    }

    private void update(CommandSender sender, String[] args) {
        if (!sender.hasPermission("survival.admin.update")) {
            sender.sendMessage("§c无权限。");
            return;
        }
        if (!config.get().updaterEnabled()) {
            sender.sendMessage("§c自动更新已在配置中关闭。");
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase() : "";
        switch (sub) {
            case "check" -> {
                sender.sendMessage("§7正在检查更新…");
                updater.check().thenAccept(r -> plugin.getServer().getScheduler().runTask(plugin,
                        () -> sender.sendMessage(r.success() ? "§a发现新版本，可用 /survival update apply 暂存。"
                                : "§c检查失败: " + r.reason())));
            }
            case "apply" -> {
                if (!sender.isOp()) {
                    sender.sendMessage("§c需要 OP 二次确认。");
                    return;
                }
                sender.sendMessage("§7正在下载并验证…");
                updater.downloadAndStage().thenAccept(r -> plugin.getServer().getScheduler().runTask(plugin,
                        () -> sender.sendMessage(r.success() ? "§a已暂存更新，请联系运维执行守护重启。"
                                : "§c更新失败: " + r.reason())));
            }
            case "cancel" -> {
                updater.cancel();
                sender.sendMessage("§a已取消当前更新。");
            }
            case "status", "" -> sender.sendMessage("§7更新状态: " + updater.state()
                    + (updater.lastError().isEmpty() ? "" : " §c" + updater.lastError()));
            default -> sender.sendMessage("§7用法: /survival update <check|apply|cancel|status>");
        }
        audit(sender, "update:" + sub, "-", "-");
    }

    private void doctor(CommandSender sender) {
        if (!sender.hasPermission("survival.admin.doctor")) {
            sender.sendMessage("§c无权限。");
            return;
        }
        sender.sendMessage("§6=== 诊断报告 ===");
        sender.sendMessage("§7MC: " + probe.serverVersion() + "  Java: " + probe.javaVersion());
        sender.sendMessage("§7版本匹配: " + probe.versionOk() + "  API匹配: " + probe.allOk());
        sender.sendMessage("§7在线状态对象: " + states.all().size());
        sender.sendMessage("§7存档只读故障: " + storage.isReadOnlyFault());
        sender.sendMessage("§7更新状态: " + updater.state());
        for (String d : probe.diagnostics()) {
            sender.sendMessage("§c  - " + d);
        }
        audit(sender, "doctor", "-", "-");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("status", "sleep", "drink", "feed", "set", "reload", "update", "doctor"));
        } else if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "drink", "feed" -> out.addAll(List.of("clean", "suspect", "bad"));
                case "set" -> out.addAll(List.of("hydration", "fatigue", "temperature", "activity", "quality"));
                case "update" -> out.addAll(List.of("check", "apply", "cancel", "status"));
            }
        }
        return out;
    }

    // ---- 工具 ----

    private FoodSafety.Quality parseQuality(String s) {
        try {
            return FoodSafety.Quality.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private double fmt(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private int safeInt(String s, int def) {
        try {
            return Math.max(1, Integer.parseInt(s));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private double safeDouble(String s, double def) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void audit(CommandSender sender, String action, String oldValue, String newValue) {
        UUID uuid = sender instanceof Player p ? p.getUniqueId() : UUID.nameUUIDFromBytes("CONSOLE".getBytes());
        plugin.getLogger().info("[审计] " + sender.getName() + "(" + uuid + ") "
                + action + " 旧值=" + oldValue + " 新值=" + newValue
                + " 时间=" + System.currentTimeMillis());
    }
}

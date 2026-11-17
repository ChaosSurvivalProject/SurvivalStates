package io.github.chaossurvival.survivalstates.util;

import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BossBar;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 启动期 API 与版本核验：固定 1.21.1 / Java 21，不尝试兼容所有版本。
 *
 * <p>关键 API（属性常量、BossBar、床事件、Sound）缺失时返回诊断结果，
 * 由主类决定是否进入诊断模式（禁用玩法积分）。反射仅用于“存在性探测”，
 * 不访问任何 NMS/CraftBukkit 内部类。</p>
 */
public final class VersionProbe {

    /** 探测报告。 */
    public record Report(
            String serverVersion,
            String javaVersion,
            boolean versionOk,
            boolean javaOk,
            boolean attributesOk,
            boolean bossBarOk,
            boolean allOk,
            java.util.List<String> diagnostics
    ) {
    }

    private static final String REQUIRED_MC = "1.21.1";
    private static final int REQUIRED_JAVA = 21;

    /** 执行探测；不抛异常，所有失败都收集到 diagnostics。 */
    public static Report probe(JavaPlugin plugin) {
        java.util.List<String> diag = new java.util.ArrayList<>();

        String mcVersion = Bukkit.getMinecraftVersion();
        String javaVersion = System.getProperty("java.version", "unknown");
        int javaMajor = parseMajor(javaVersion);

        boolean versionOk = mcVersion != null && mcVersion.equals(REQUIRED_MC);
        boolean javaOk = javaMajor >= REQUIRED_JAVA;
        if (!versionOk) {
            diag.add("Minecraft 版本不匹配：期望 " + REQUIRED_MC + "，实际 " + mcVersion);
        }
        if (!javaOk) {
            diag.add("Java 版本过低：期望 ≥" + REQUIRED_JAVA + "，实际 " + javaVersion);
        }

        boolean attributesOk = true;
        for (String name : new String[]{
                "GENERIC_MAX_HEALTH", "GENERIC_MOVEMENT_SPEED", "GENERIC_ATTACK_DAMAGE",
                "GENERIC_ATTACK_SPEED", "PLAYER_MINING_EFFICIENCY"}) {
            try {
                Attribute.valueOf(name);
            } catch (Throwable t) {
                attributesOk = false;
                diag.add("缺少属性常量 Attribute." + name + "（疑似高版本 API）");
            }
        }

        boolean bossBarOk = true;
        try {
            BossBar b = Bukkit.createBossBar("probe", org.bukkit.boss.BarColor.BLUE,
                    org.bukkit.boss.BarStyle.SEGMENTED_10);
            b.removeAll();
        } catch (Throwable t) {
            bossBarOk = false;
            diag.add("BossBar API 不可用: " + t.getMessage());
        }

        // 关键事件与 Sound 的存在性（Class.forName 只做存在性检查，不实例化内部对象）。
        checkClass("org.bukkit.event.player.PlayerBedEnterEvent", diag);
        checkClass("org.bukkit.event.player.PlayerBedLeaveEvent", diag);
        checkSound("BLOCK_NOTE_BLOCK_BASS", diag);

        boolean allOk = versionOk && javaOk && attributesOk && bossBarOk;
        return new Report(mcVersion, javaVersion, versionOk, javaOk, attributesOk, bossBarOk, allOk, diag);
    }

    private static void checkClass(String name, java.util.List<String> diag) {
        try {
            Class.forName(name);
        } catch (Throwable t) {
            diag.add("缺少关键类: " + name);
        }
    }

    private static void checkSound(String name, java.util.List<String> diag) {
        try {
            org.bukkit.Sound.valueOf(name);
        } catch (Throwable t) {
            diag.add("缺少关键音效: " + name);
        }
    }

    private static int parseMajor(String v) {
        if (v == null || v.isEmpty()) {
            return 0;
        }
        String first = v.startsWith("1.") ? v.substring(2) : v;
        int dot = first.indexOf('.');
        if (dot > 0) {
            first = first.substring(0, dot);
        }
        try {
            return Integer.parseInt(first);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

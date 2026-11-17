package io.github.chaossurvival.survivalstates.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/**
 * 消息服务：Adventure MiniMessage，占位符白名单化。
 *
 * <p>严禁把玩家输入直接解析为组件；只接受固定的数值/字符串占位符。
 * 所有玩家可见文案使用中文。</p>
 */
public final class Messages {

    /** 允许的占位符键。 */
    public enum Key {
        PLAYER("player"), FATIGUE("fatigue"), HYDRATION("hydration"),
        TEMPERATURE("temperature"), SECONDS("seconds"), QUALITY("quality");

        final String tag;

        Key(String tag) {
            this.tag = tag;
        }
    }

    private final String prefix;
    private final java.util.Map<String, String> raw;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private Messages(String prefix, java.util.Map<String, String> raw) {
        this.prefix = prefix == null ? "" : prefix;
        this.raw = raw;
    }

    public static Messages from(FileConfiguration c) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        for (String key : List.of("join", "warnFatigue", "forceSleepArmed", "forceSleepActive",
                "restStarted", "restFinished", "restInterrupted", "noBedRest",
                "drank", "feelSick", "reloadDone", "lowHealthProtected")) {
            m.put(key, c.getString("messages." + key, ""));
        }
        return new Messages(c.getString("messages.prefix", ""), m);
    }

    /**
     * 渲染一条消息。placeholders 为 tag名→值 的映射，仅白名单键会被解析，
     * 值作为纯文本写入，不会被当作 MiniMessage 标签。
     */
    public Component render(String key, java.util.Map<Key, String> placeholders) {
        String template = raw.getOrDefault(key, "");
        TagResolver.Builder resolver = TagResolver.builder();
        if (placeholders != null) {
            for (java.util.Map.Entry<Key, String> e : placeholders.entrySet()) {
                resolver.resolver(Placeholder.unparsed(e.getKey().tag,
                        e.getValue() == null ? "" : e.getValue()));
            }
        }
        Component body = miniMessage.deserialize(prefix + template, resolver.build());
        return body;
    }

    public Component simple(String key) {
        return render(key, java.util.Map.of());
    }
}

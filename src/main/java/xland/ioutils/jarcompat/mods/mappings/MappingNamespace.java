package xland.ioutils.jarcompat.mods.mappings;

import java.util.Locale;

import org.jspecify.annotations.Nullable;
import xland.ioutils.jarcompat.mods.core.ModCompatException;

/**
 * 映射文件里使用的命名空间名。字符串与 Tiny v2 文件表头里的列名一致，因此可以直接传给
 * tiny-remapper 的 {@code createTinyMappingProvider(path, from, to)}。
 */
public enum MappingNamespace {

    /** 官方 JAR 原生的名字：{@code 1.x} 上是混淆名，{@code >= 26.x} 上已经是可读名。 */
    OFFICIAL("official"),

    /** Mojang 官方 mappings（ProGuard 文本）给出的可读名。 */
    MOJANG("mojang"),

    /** Fabric 的 intermediary。 */
    INTERMEDIARY("intermediary");

    private final String label;

    MappingNamespace(String label) {
        this.label = label;
    }

    /** Tiny v2 表头里的列名。 */
    public String label() {
        return label;
    }

    /**
     * 解析 {@code MappingsDecision.targetNamespace()} 给出的名字。
     *
     * @throws ModCompatException 无法识别的命名空间名
     */
    public static MappingNamespace of(@Nullable String name) {
        if (name == null) {
            throw new ModCompatException("内部错误: 目标命名空间为空");
        }
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "official" -> OFFICIAL;
            case "mojang" -> MOJANG;
            case "intermediary" -> INTERMEDIARY;
            default -> throw new ModCompatException("内部错误: 未知的目标命名空间 " + name);
        };
    }

    @Override
    public String toString() {
        return label;
    }
}

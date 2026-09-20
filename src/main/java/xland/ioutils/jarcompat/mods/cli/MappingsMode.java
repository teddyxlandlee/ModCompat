package xland.ioutils.jarcompat.mods.cli;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * {@code --mappings} 的取值：用户希望的命名空间策略。
 *
 * <p>命名空间是<b>一次比较的全局属性</b>：两侧所有带 Minecraft 的资源都被对齐到同一个目标命名空间，
 * 而不是按侧各自推导——否则两侧的 {@code net.minecraft.*} 名字根本不在同一个命名空间里，
 * 比较出来的“不兼容”不具备任何意义。</p>
 *
 * <p>本枚举只表达<b>用户意图</b>；把它和具体的 Minecraft 版本、loader、mod 自身的命名空间结合起来
 * 得到实际结论的是 {@code xland.ioutils.jarcompat.mods.env.MappingsDecision}。</p>
 */
public enum MappingsMode {

    /**
     * 自动判定（默认）。
     *
     * <p>{@code >= 26.x} 的版本本来就不混淆，因此不 remap；{@code 1.x} 的版本按 mod 自身的命名空间
     * 与已启用的 loader 推导目标命名空间。推导不出来（既没启用 loader、mod 也不是已知命名空间）时
     * 不强行 remap，而是降级为“只比库层”并明确说明原因。</p>
     */
    AUTO("auto"),

    /**
     * 对齐到 Mojang 官方名（NeoForge 1.20.2+ 的运行时命名空间，也是 {@code >= 26.x} 的原生命名空间）。
     *
     * <p>在 {@code >= 26.x} 上这是恒等操作（官方 JAR 本来就未混淆），不会启动任何 remapper。</p>
     */
    MOJANG("mojang"),

    /**
     * 对齐到 Fabric 的 intermediary。
     *
     * <p>只对 {@code >= 26.1} 的版本有意义：更早的版本根本没有对应的 intermediary 文件。</p>
     */
    INTERMEDIARY("intermediary"),

    /**
     * 不处理映射：原样使用官方（可能混淆的）JAR。
     *
     * <p>这是显式的降级模式，结论只覆盖库层与 JAR 中稳定的具名类。</p>
     */
    NONE("none");

    private final String label;

    MappingsMode(String label) {
        this.label = label;
    }

    /** 命令行上使用的规范名字。 */
    public String label() {
        return label;
    }

    /**
     * 解析 {@code --mappings} 的取值。
     *
     * @param value 取值；{@code official} 作为 {@link #MOJANG} 的别名被接受
     * @return 解析结果
     * @throws UsageException 取值无法识别
     */
    public static MappingsMode parse(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "auto" -> AUTO;
            case "mojang", "official" -> MOJANG;
            case "intermediary", "tiny" -> INTERMEDIARY;
            case "none" -> NONE;
            default -> throw new UsageException(
                    "--mappings 只能是 auto、mojang（别名 official）、intermediary 或 none，收到: " + value);
        };
    }

    /** 是否是“对齐到某个具体命名空间”的请求（即 {@link #AUTO} 与 {@link #NONE} 之外的取值）。 */
    public boolean isExplicitNamespace() {
        return this == MOJANG || this == INTERMEDIARY;
    }

    /** 该取值对应的目标命名空间；{@link #AUTO} 与 {@link #NONE} 没有固定目标，返回 {@code null}。 */
    public @Nullable String explicitNamespaceName() {
        return switch (this) {
            case MOJANG -> label;
            case INTERMEDIARY -> label;
            case AUTO, NONE -> null;
        };
    }

    @Override
    public String toString() {
        return label;
    }
}

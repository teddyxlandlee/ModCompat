package xland.ioutils.jarcompat.mods.core;

import org.jspecify.annotations.Nullable;

/**
 * Minecraft 版本号的一点点解析：本工具只关心一件事——这个版本的官方 JAR 是否仍然是混淆产物。
 *
 * <p>26.1 是第一个不再混淆的 Minecraft 版本（Fabric 也自该版本起从 Yarn 切到未混淆的
 * {@code fabric-loom}），因此判定规则就是<b>主版本号是否大于等于 26</b>。历史上（{@code 1.x}）
 * 官方 {@code client.jar} 里的 {@code net.minecraft.*} 绝大多数是混淆类名，需要映射才能与
 * Fabric（intermediary）/ NeoForge（Mojang 官方名）编译出的 mod 对上。</p>
 */
public final class MinecraftVersion {

    /** 第一个不再混淆的 Minecraft 主版本号。 */
    public static final int FIRST_UNOBFUSCATED_MAJOR = 26;

    private MinecraftVersion() {
    }

    /**
     * 解析版本号的主版本段。
     *
     * @param version 版本号，例如 {@code 1.21.1}、{@code 26.1}
     * @return 主版本号（{@code 1.21.1} 取 1，{@code 26.1} 取 26）；无法解析时返回 {@code null}
     */
    public static @Nullable Integer major(@Nullable String version) {
        if (version == null) {
            return null;
        }
        String trimmed = version.trim();
        int dot = trimmed.indexOf('.');
        String head = dot >= 0 ? trimmed.substring(0, dot) : trimmed;
        if (head.isEmpty()) {
            return null;
        }
        for (int i = 0; i < head.length(); i++) {
            if (!Character.isDigit(head.charAt(i))) {
                // "26w14a"、"a1.2.6" 这类快照/远古版本名不参与 26.x 判定
                return null;
            }
        }
        try {
            return Integer.valueOf(head);
        } catch (NumberFormatException e) {
            return null;   // 数字长到溢出 Integer
        }
    }

    /**
     * 该版本的官方 JAR 是否仍然混淆。
     *
     * <p>无法解析的版本号按“未混淆”处理：这种情况下不做任何 remap，比做一次来路不明的 remap 安全，
     * 判定层会把它当作 {@code >= 26.x} 处理。</p>
     */
    public static boolean isObfuscated(@Nullable String version) {
        Integer major = major(version);
        return major != null && major < FIRST_UNOBFUSCATED_MAJOR;
    }

    /** 该版本是否属于“本来就不混淆”的一代。 */
    public static boolean isUnobfuscated(@Nullable String version) {
        return !isObfuscated(version);
    }
}

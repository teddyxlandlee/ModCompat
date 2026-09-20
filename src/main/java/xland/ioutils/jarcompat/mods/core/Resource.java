package xland.ioutils.jarcompat.mods.core;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * 一个上游资源：Maven 坐标 + 下载地址（+ 可选的命名空间变体）。
 *
 * <p>对应 DEV_GUIDE 中的通用类型 {@code Resource = { url: string, coords: string }}，
 * 并按“映射处理”的需要把身份拆成两层：</p>
 *
 * <ul>
 *   <li>{@link #coords()} 是<b>基础身份</b>：{@code com.mojang:minecraft:1.21.1} 这种 Maven 坐标。
 *       “两侧的同一个构件”仍然由它判定（{@link #equivalentTo(Resource)}），
 *       {@code EnvironmentFilter} 也只按它去重/过滤——否则同一份 client.jar 在两侧因为
 *       命名空间不同就被当成两个不同的资源，同坐标过滤会失效。</li>
 *   <li>{@link #variant()} 是<b>变体</b>：例如 {@code mapped-mojang}、{@code mapped-intermediary}，
 *       表示“这份构件被 remap 成了哪个命名空间”。它参与缓存键与报告，但不参与“是不是同一个资源”的判定
 *       （{@code mapped-mojang} 与 {@code mapped-intermediary} 确实是两份不同的产物，
 *       但它们是同一份原始构件的两种加工结果）。</li>
 * </ul>
 *
 * @param url    下载地址（HTTP/HTTPS）
 * @param coords Maven 坐标，例如 {@code com.mojang:minecraft:1.21.1} 或
 *               {@code net.neoforged:neoforge:21.1.250:universal}
 * @param variant 命名空间变体；不需要 remap 时为 {@code null}
 */
public record Resource(String url, String coords, @Nullable String variant) {

    public Resource {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(coords, "coords");
        if (coords.isBlank()) {
            throw new IllegalArgumentException("资源的 Maven 坐标为空: " + url);
        }
        if (url.isBlank()) {
            throw new IllegalArgumentException("资源 " + coords + " 的下载地址为空");
        }
        if (variant != null && variant.isBlank()) {
            variant = null;
        }
    }

    /** 未加工的资源（最常见的形态）。 */
    public Resource(String url, String coords) {
        this(url, coords, null);
    }

    /** 同一构件、指定变体的副本。 */
    public Resource withVariant(@Nullable String newVariant) {
        return new Resource(url, coords, newVariant);
    }

    /**
     * DEV_GUIDE 中的 {@code isEquivalent}：两个资源的 Maven 坐标 {@code coords} 相同。
     *
     * <p>只比较基础身份：变体是加工结果，不影响“这是不是两侧的同一个构件”。</p>
     *
     * @param other 另一个资源；{@code null} 视为“不等价”
     */
    public boolean equivalentTo(@Nullable Resource other) {
        return other != null && coords.equals(other.coords);
    }

    /** 报告中显示的名字：有变体时附在坐标后面。 */
    public String displayName() {
        return variant == null ? coords : coords + " [" + variant + "]";
    }

    @Override
    public String toString() {
        return displayName() + " -> " + url;
    }
}

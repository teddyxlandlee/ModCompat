package xland.ioutils.jarcompat.mods.core;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * 一个上游资源：Maven 坐标 + 下载地址。
 *
 * <p>对应 DEV_GUIDE 中的通用类型 {@code Resource = { url: string, coords: string }}。
 * 两侧的“同一个资源”由 {@link #equivalentTo(Resource)}（即坐标相同）判定。</p>
 *
 * @param url    下载地址（HTTP/HTTPS）
 * @param coords Maven 坐标，例如 {@code com.mojang:minecraft:1.21.1} 或
 *               {@code net.neoforged:neoforge:21.1.250:universal}
 */
public record Resource(String url, String coords) {

    public Resource {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(coords, "coords");
        if (coords.isBlank()) {
            throw new IllegalArgumentException("资源的 Maven 坐标为空: " + url);
        }
        if (url.isBlank()) {
            throw new IllegalArgumentException("资源 " + coords + " 的下载地址为空");
        }
    }

    /**
     * DEV_GUIDE 中的 {@code isEquivalent}：两个资源的 Maven 坐标 {@code coords} 相同。
     *
     * @param other 另一个资源；{@code null} 视为“不等价”
     */
    public boolean equivalentTo(@Nullable Resource other) {
        return other != null && coords.equals(other.coords);
    }

    @Override
    public String toString() {
        return coords + " -> " + url;
    }
}

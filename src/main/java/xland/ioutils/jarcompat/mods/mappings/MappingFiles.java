package xland.ioutils.jarcompat.mods.mappings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import xland.ioutils.jarcompat.mods.core.Fetcher;
import xland.ioutils.jarcompat.mods.core.JarCache;
import xland.ioutils.jarcompat.mods.core.ModCompatException;
import xland.ioutils.jarcompat.mods.core.Resource;
import xland.ioutils.jarcompat.mods.core.Zips;

/**
 * 映射文件的获取与缓存。
 *
 * <p>涉及两种文件，都当作普通 {@link Resource} 走 {@link JarCache}（下载 + 复用 + ZIP 校验）：</p>
 *
 * <ul>
 *   <li><b>Mojang 官方 mappings</b>：{@code versionMeta.downloads.client_mappings} 指向的
 *       {@code client.txt}，ProGuard 文本格式。注意它的方向是 <b>{@code mojang -> official}</b>
 *       （左边可读名、右边混淆名），用之前必须 {@code reverse()}。</li>
 *   <li><b>Fabric intermediary</b>：{@code net.fabricmc:intermediary:<mc>:v2}，是一个 JAR，
 *       里面 {@code mappings/mappings.tiny} 是 Tiny v2，方向已经是 {@code official -> intermediary}。</li>
 * </ul>
 *
 * <p>不需要 NeoForm / SRG：{@code net.neoforged:neoforge:<v>} 的 {@code :universal} 构件发出来时
 * 就已经是 Mojang 官方名（实测 21.1.234 的 universal JAR 里 SRG 名 {@code f_*} / {@code m_*} 出现
 * 0 次），因此它不需要 remap。</p>
 */
public final class MappingFiles {

    /** Fabric intermediary 的 Maven 仓库。 */
    public static final String FABRIC_MAVEN = "https://maven.fabricmc.net/";

    private static final String INTERMEDIARY_GROUP_PATH = "net/fabricmc/intermediary";

    private final JarCache cache;
    private final Path mappingsDir;

    /**
     * @param cache   JAR 缓存（映射文件与其它构件共用同一个缓存目录）
     * @param fetcher 下载器（由 {@link JarCache} 持有，这里只为构造 {@link Resource} 的便利保留）
     */
    public MappingFiles(JarCache cache, Fetcher fetcher) {
        this.cache = Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(fetcher, "fetcher");
        this.mappingsDir = cache.root().resolve("mappings");
    }

    /** 映射文件的落盘目录（{@code <cacheDir>/mappings}）。 */
    public Path mappingsDir() {
        return mappingsDir;
    }

    /** Mojang 官方 mappings 的坐标。 */
    public static Resource clientMappings(String mcVersion, String url) {
        return new Resource(url, "com.mojang:minecraft:" + mcVersion + ":client_mappings");
    }

    /** Fabric intermediary 的坐标。 */
    public static Resource intermediary(String mcVersion) {
        return new Resource(intermediaryUrl(mcVersion), "net.fabricmc:intermediary:" + mcVersion + ":v2");
    }

    /** Fabric intermediary v2 构件的下载地址。 */
    public static String intermediaryUrl(String mcVersion) {
        return FABRIC_MAVEN + INTERMEDIARY_GROUP_PATH + "/" + mcVersion
                + "/intermediary-" + mcVersion + "-v2.jar";
    }

    /**
     * 取得 {@code mojang -> official} 的 ProGuard 映射文件（用前需要 reverse）。
     *
     * @param clientMappingsUrl 版本元数据里的 {@code downloads.client_mappings.url}
     * @throws ModCompatException 该版本没有官方 mappings
     */
    public Path mojangMappings(String mcVersion, @Nullable String clientMappingsUrl) {
        if (clientMappingsUrl == null || clientMappingsUrl.isBlank()) {
            throw new ModCompatException("Minecraft " + mcVersion
                    + " 的版本元数据里没有 downloads.client_mappings（官方映射），无法反混淆");
        }
        return fetch(clientMappings(mcVersion, clientMappingsUrl),
                "Minecraft " + mcVersion + " 的官方映射");
    }

    /**
     * 取得 {@code official -> intermediary} 的 Tiny v2 映射文件。
     *
     * <p>intermediary 自 {@code 26.1} 起才存在；对更早的版本该构件根本不存在。</p>
     */
    public Path intermediaryMappings(String mcVersion) {
        Resource resource = intermediary(mcVersion);
        Path jar = fetch(resource, "Minecraft " + mcVersion + " 的 intermediary 映射");
        return extractTiny(jar, "mappings/mappings.tiny",
                mappingsDir.resolve("intermediary-" + mcVersion + "-v2.tiny"), resource.coords());
    }

    private Path fetch(Resource resource, String what) {
        try {
            return cache.fetch(resource);
        } catch (IOException e) {
            throw new ModCompatException(what + " 获取失败（" + resource.displayName() + "）: "
                    + e.getMessage(), e);
        }
    }

    /** intermediary 是装在 JAR 里的，取出来落成独立的 .tiny 文件，便于 tiny-remapper 直接读。 */
    private static Path extractTiny(Path jar, String entry, Path target, String coords) {
        if (Files.isRegularFile(target)) {
            return target;
        }
        try {
            byte[] bytes = Zips.readEntry(Files.readAllBytes(jar), entry);
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            return target;
        } catch (IOException e) {
            throw new ModCompatException("无法从 " + coords + " 中取出 " + entry + ": " + e.getMessage(), e);
        }
    }
}

package xland.ioutils.jarcompat.mods.cli;

import java.nio.file.Path;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import xland.ioutils.jarcompat.api.ReachabilityScope;
import xland.ioutils.jarcompat.api.ReportFormat;

/**
 * 解析后的命令行选项。
 *
 * <p>本记录位于 {@code @NullMarked} 包内：只有显式标注 {@link Nullable} 的分量才可能为
 * {@code null}（未指定的可选选项，以及 {@code --help}/{@code --version} 快速路径下未填充的必填项）。</p>
 *
 * @param program          必填，待比较的 Minecraft mod JAR
 * @param mcVersionA       环境 A 的 Minecraft 版本；{@code --help}/{@code --version} 快速路径下为 {@code null}
 * @param mcVersionB       环境 B 的 Minecraft 版本；{@code --help}/{@code --version} 快速路径下为 {@code null}
 * @param fabric           是否在环境构建中加入 Fabric Loader 库
 * @param neoForge         是否在环境构建中加入 NeoForge 库
 * @param fabricOverrideA  环境 A 的 Fabric Loader 版本覆盖（可为 {@code null}）
 * @param fabricOverrideB  环境 B 的 Fabric Loader 版本覆盖（可为 {@code null}）
 * @param neoForgeOverrideA 环境 A 的 NeoForge 版本覆盖（可为 {@code null}）
 * @param neoForgeOverrideB 环境 B 的 NeoForge 版本覆盖（可为 {@code null}）
 * @param mappings         命名空间策略（默认 {@link MappingsMode#AUTO}）
 * @param cacheDir         JAR 下载缓存目录
 * @param format           报告格式
 * @param output           报告输出文件（可为 {@code null}）
 * @param failOnError      存在确定不兼容项时是否返回退出码 2
 * @param dryRun           只构建并打印 lib-a/lib-b，不下载、不比较
 * @param reachability     可达性策略；mod 通常没有 Main-Class，默认 {@link ReachabilityScope#ALL}
 * @param entryClass       显式入口类（可为 {@code null}）
 * @param entryMethod      入口方法名，默认 {@code main}；{@code --help}/{@code --version} 快速路径下为 {@code null}
 * @param help             是否请求帮助
 * @param version          是否请求版本号
 */
public record CliOptions(
        Path program,
        @Nullable String mcVersionA,
        @Nullable String mcVersionB,
        boolean fabric,
        boolean neoForge,
        @Nullable String fabricOverrideA,
        @Nullable String fabricOverrideB,
        @Nullable String neoForgeOverrideA,
        @Nullable String neoForgeOverrideB,
        MappingsMode mappings,
        Path cacheDir,
        ReportFormat format,
        @Nullable Path output,
        boolean failOnError,
        boolean dryRun,
        ReachabilityScope reachability,
        @Nullable String entryClass,
        @Nullable String entryMethod,
        boolean help,
        boolean version) {

    /** 非空分量的尽早校验：这些值一旦为 {@code null}，会在很远的地方才炸开。 */
    public CliOptions {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(mappings, "mappings");
        Objects.requireNonNull(cacheDir, "cacheDir");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(reachability, "reachability");
    }

    /** 默认缓存目录：{@code $MODCOMPAT_CACHE_DIR} → {@code $XDG_CACHE_HOME/modcompat} → {@code ~/.cache/modcompat}。 */
    public static Path defaultCacheDir() {
        String explicit = System.getenv("MODCOMPAT_CACHE_DIR");
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit.trim());
        }
        String xdg = System.getenv("XDG_CACHE_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Path.of(xdg.trim(), "modcompat");
        }
        return Path.of(System.getProperty("user.home", "."), ".cache", "modcompat");
    }
}

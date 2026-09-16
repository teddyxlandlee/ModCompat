package xland.ioutils.jarcompat.mods.cli;

import java.nio.file.Path;

import xland.ioutils.jarcompat.api.ReachabilityScope;
import xland.ioutils.jarcompat.api.ReportFormat;

/**
 * 解析后的命令行选项。
 *
 * @param program          必填，待比较的 Minecraft mod JAR
 * @param mcVersionA       环境 A 的 Minecraft 版本
 * @param mcVersionB       环境 B 的 Minecraft 版本
 * @param fabric           是否在环境构建中加入 Fabric Loader 库
 * @param neoForge         是否在环境构建中加入 NeoForge 库
 * @param fabricOverrideA  环境 A 的 Fabric Loader 版本覆盖（可为 {@code null}）
 * @param fabricOverrideB  环境 B 的 Fabric Loader 版本覆盖（可为 {@code null}）
 * @param neoForgeOverrideA 环境 A 的 NeoForge 版本覆盖（可为 {@code null}）
 * @param neoForgeOverrideB 环境 B 的 NeoForge 版本覆盖（可为 {@code null}）
 * @param cacheDir         JAR 下载缓存目录
 * @param format           报告格式
 * @param output           报告输出文件（可为 {@code null}）
 * @param failOnError      存在确定不兼容项时是否返回退出码 2
 * @param dryRun           只构建并打印 lib-a/lib-b，不下载、不比较
 * @param reachability     可达性策略；mod 通常没有 Main-Class，默认 {@link ReachabilityScope#ALL}
 * @param entryClass       显式入口类（可为 {@code null}）
 * @param entryMethod      入口方法名，默认 {@code main}
 * @param help             是否请求帮助
 * @param version          是否请求版本号
 */
public record CliOptions(
        Path program,
        String mcVersionA,
        String mcVersionB,
        boolean fabric,
        boolean neoForge,
        String fabricOverrideA,
        String fabricOverrideB,
        String neoForgeOverrideA,
        String neoForgeOverrideB,
        Path cacheDir,
        ReportFormat format,
        Path output,
        boolean failOnError,
        boolean dryRun,
        ReachabilityScope reachability,
        String entryClass,
        String entryMethod,
        boolean help,
        boolean version) {

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

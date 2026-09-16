package xland.ioutils.jarcompat.mods.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import xland.ioutils.jarcompat.api.ReachabilityScope;
import xland.ioutils.jarcompat.api.ReportFormat;

/**
 * ModCompat 命令行解析器。
 *
 * <p>必填：{@code <program>}、{@code -a/--version-a}、{@code -b/--version-b}。
 * 可选开关：{@code --fabric}、{@code --neoforge}（可独立或同时指定）。
 * 可选覆盖：{@code --fabric-override-a/b}、{@code --neoforge-override-a/b}
 * （指定了 override 却没有开启对应 loader 时报错）。</p>
 *
 * <p>解析失败一律抛出 {@link UsageException}，由上层转成退出码 1。</p>
 */
public final class CliParser {

    private CliParser() {
    }

    /** 帮助文本。 */
    public static String usage() {
        return """
                ModCompat — Minecraft mod 双环境兼容性比较工具（基于 xland.ioutils:JarCompat）

                用法:
                  modcompat <program> -a <版本> -b <版本> [选项]

                必填:
                  <program>                        要比较的 Minecraft mod JAR 路径
                  -a, --version-a <version>        环境 A 的 Minecraft 版本号（例如 1.21.1）
                  -b, --version-b <version>        环境 B 的 Minecraft 版本号（例如 1.21.4）

                环境开关（可独立指定、可同时指定、也可都不指定）:
                      --fabric                     环境构建时加入 Fabric Loader 相关库
                      --neoforge                   环境构建时加入 NeoForge 相关库

                可选覆盖版本（未指定时使用该 loader 在该侧 Minecraft 版本下的最新版本）:
                      --fabric-override-a <version>    环境 A 的 Fabric Loader 版本（需 --fabric）
                      --fabric-override-b <version>    环境 B 的 Fabric Loader 版本（需 --fabric）
                      --neoforge-override-a <version>  环境 A 的 NeoForge 版本（需 --neoforge）
                      --neoforge-override-b <version>  环境 B 的 NeoForge 版本（需 --neoforge）

                其他选项:
                      --cache-dir <dir>            JAR 下载缓存目录（默认 $MODCOMPAT_CACHE_DIR 或 ~/.cache/modcompat）
                      --format <text|json>         报告格式（默认 text）
                  -o, --output <file>              额外把报告写入文件
                      --fail-on-error              存在确定不兼容项时返回退出码 2（默认仍返回 0）
                      --dry-run                    只解析元数据并打印 lib-a/lib-b，不下载、不比较
                      --reachability <all|entry>   可达性策略（默认 all：mod 通常没有 Main-Class）
                      --entry <class>              入口类（配合 --reachability entry）
                      --entry-method <name>        入口方法名（默认 main）
                  -h, --help                       显示本帮助并退出
                  -V, --version                    显示版本并退出

                退出码:
                  0  比较成功完成（无论结论是否兼容）
                  1  参数错误（缺少必填项、override 与开关冲突、无意义的比较等）
                  2  发现确定不兼容项，且指定了 --fail-on-error
                  3  网络、元数据解析、下载或 JarCompat 分析失败

                示例:
                  modcompat mymod.jar -a 1.21.1 -b 1.21.4
                  modcompat mymod.jar -a 1.21.1 -b 1.21.4 --fabric --neoforge
                  modcompat mymod.jar -a 1.21.1 -b 1.21.4 --neoforge --neoforge-override-b 21.4.100-beta
                  modcompat mymod.jar -a 1.21.1 -b 1.21.1 --fabric --fabric-override-b 0.19.5 --format json
                """;
    }

    /** 解析命令行。 */
    public static CliOptions parse(String[] args) {
        Objects.requireNonNull(args, "args");
        Builder b = new Builder();
        List<String> positionals = new ArrayList<>();
        Args cursor = new Args(args);
        boolean endOfOptions = false;

        while (cursor.hasNext()) {
            String arg = cursor.next();
            if (endOfOptions) {
                positionals.add(arg);
                continue;
            }
            if ("--".equals(arg)) {
                endOfOptions = true;
                continue;
            }

            String name = arg;
            String inline = null;
            int eq = arg.indexOf('=');
            if (arg.startsWith("-") && eq > 0) {
                name = arg.substring(0, eq);
                inline = arg.substring(eq + 1);
            }

            switch (name) {
                case "-h", "--help" -> {
                    rejectValue(name, inline);
                    b.help = true;
                }
                case "-V", "--version" -> {
                    rejectValue(name, inline);
                    b.version = true;
                }
                case "--fabric" -> {
                    rejectValue(name, inline);
                    b.fabric = true;
                }
                case "--neoforge" -> {
                    rejectValue(name, inline);
                    b.neoForge = true;
                }
                case "--fail-on-error" -> {
                    rejectValue(name, inline);
                    b.failOnError = true;
                }
                case "--dry-run" -> {
                    rejectValue(name, inline);
                    b.dryRun = true;
                }
                case "-a", "--version-a" -> b.mcVersionA = value(name, inline, cursor);
                case "-b", "--version-b" -> b.mcVersionB = value(name, inline, cursor);
                case "--fabric-override-a" -> b.fabricOverrideA = value(name, inline, cursor);
                case "--fabric-override-b" -> b.fabricOverrideB = value(name, inline, cursor);
                case "--neoforge-override-a" -> b.neoForgeOverrideA = value(name, inline, cursor);
                case "--neoforge-override-b" -> b.neoForgeOverrideB = value(name, inline, cursor);
                case "--cache-dir" -> b.cacheDir = Path.of(requireNonBlank(name, value(name, inline, cursor)));
                case "-o", "--output" -> b.output = Path.of(requireNonBlank(name, value(name, inline, cursor)));
                case "--entry" -> b.entryClass = requireNonBlank(name, value(name, inline, cursor));
                case "--entry-method" -> b.entryMethod = requireNonBlank(name, value(name, inline, cursor));
                case "--format" -> b.format = parseFormat(value(name, inline, cursor));
                case "--reachability" -> b.reachability = parseReachability(value(name, inline, cursor));
                default -> {
                    if (arg.startsWith("-") && arg.length() > 1) {
                        throw new UsageException("未知选项: " + arg);
                    }
                    positionals.add(arg);
                }
            }
        }

        if (b.help || b.version) {
            // --help / --version 优先于必填校验
            return b.build(Path.of("."));
        }

        if (positionals.isEmpty()) {
            throw new UsageException("缺少必填参数 <program>（要比较的 mod JAR 路径）");
        }
        if (positionals.size() > 1) {
            throw new UsageException("只能指定一个 <program>，收到 " + positionals.size() + " 个: " + positionals);
        }
        if (b.mcVersionA == null || b.mcVersionA.isBlank()) {
            throw new UsageException("缺少必填参数 -a/--version-a（环境 A 的 Minecraft 版本）");
        }
        if (b.mcVersionB == null || b.mcVersionB.isBlank()) {
            throw new UsageException("缺少必填参数 -b/--version-b（环境 B 的 Minecraft 版本）");
        }
        if (!b.fabric && (b.fabricOverrideA != null || b.fabricOverrideB != null)) {
            String option = b.fabricOverrideA != null ? "--fabric-override-a" : "--fabric-override-b";
            throw new UsageException(option + " 需要同时指定 --fabric（指定的版本不会被使用）");
        }
        if (!b.neoForge && (b.neoForgeOverrideA != null || b.neoForgeOverrideB != null)) {
            String option = b.neoForgeOverrideA != null ? "--neoforge-override-a" : "--neoforge-override-b";
            throw new UsageException(option + " 需要同时指定 --neoforge（指定的版本不会被使用）");
        }
        if (b.entryMethod == null) {
            b.entryMethod = "main";
        }

        return b.build(Path.of(positionals.getFirst()));
    }

    private static ReportFormat parseFormat(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "text" -> ReportFormat.TEXT;
            case "json" -> ReportFormat.JSON;
            default -> throw new UsageException("--format 只能是 text 或 json，收到: " + value);
        };
    }

    private static ReachabilityScope parseReachability(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "all" -> ReachabilityScope.ALL;
            case "entry" -> ReachabilityScope.ENTRY;
            default -> throw new UsageException("--reachability 只能是 all 或 entry，收到: " + value);
        };
    }

    private static void rejectValue(String option, @Nullable String inline) {
        if (inline != null) {
            throw new UsageException("选项 " + option + " 不接受值: " + inline);
        }
    }

    private static String value(String option, @Nullable String inline, Args cursor) {
        return inline != null ? inline : cursor.requireValue(option);
    }

    private static String requireNonBlank(String option, String value) {
        if (value.isBlank()) {
            throw new UsageException("选项 " + option + " 的值不能为空");
        }
        return value.trim();
    }

    /** 顺序读取参数并支持“选项 + 下一个参数”取值。 */
    private static final class Args {
        private final String[] values;
        private int index;

        Args(String[] values) {
            this.values = values;
        }

        boolean hasNext() {
            return index < values.length;
        }

        String next() {
            return values[index++];
        }

        String requireValue(String option) {
            if (index >= values.length) {
                throw new UsageException("选项 " + option + " 需要一个值");
            }
            return values[index++];
        }
    }

    /** 可变收集器。 */
    private static final class Builder {
        private @Nullable String mcVersionA;
        private @Nullable String mcVersionB;
        private boolean fabric;
        private boolean neoForge;
        private @Nullable String fabricOverrideA;
        private @Nullable String fabricOverrideB;
        private @Nullable String neoForgeOverrideA;
        private @Nullable String neoForgeOverrideB;
        private @Nullable Path cacheDir;
        private ReportFormat format = ReportFormat.TEXT;
        private @Nullable Path output;
        private boolean failOnError;
        private boolean dryRun;
        private ReachabilityScope reachability = ReachabilityScope.ALL;
        private @Nullable String entryClass;
        private @Nullable String entryMethod;
        private boolean help;
        private boolean version;

        CliOptions build(Path program) {
            return new CliOptions(
                    program,
                    mcVersionA,
                    mcVersionB,
                    fabric,
                    neoForge,
                    fabricOverrideA,
                    fabricOverrideB,
                    neoForgeOverrideA,
                    neoForgeOverrideB,
                    cacheDir != null ? cacheDir : CliOptions.defaultCacheDir(),
                    format,
                    output,
                    failOnError,
                    dryRun,
                    reachability,
                    entryClass,
                    entryMethod,
                    help,
                    version);
        }
    }
}

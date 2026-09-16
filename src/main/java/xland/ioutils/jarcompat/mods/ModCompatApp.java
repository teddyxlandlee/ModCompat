package xland.ioutils.jarcompat.mods;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonWriter;

import xland.ioutils.jarcompat.api.CheckReport;
import xland.ioutils.jarcompat.api.CheckRequest;
import xland.ioutils.jarcompat.api.JarCompat;
import xland.ioutils.jarcompat.api.JarCompatException;
import xland.ioutils.jarcompat.api.ReachabilityScope;
import xland.ioutils.jarcompat.api.ReportFormat;
import xland.ioutils.jarcompat.mods.cli.CliOptions;
import xland.ioutils.jarcompat.mods.cli.CliParser;
import xland.ioutils.jarcompat.mods.cli.ExitCodes;
import xland.ioutils.jarcompat.mods.cli.UsageException;
import xland.ioutils.jarcompat.mods.core.Fetcher;
import xland.ioutils.jarcompat.mods.core.HttpFetcher;
import xland.ioutils.jarcompat.mods.core.JarCache;
import xland.ioutils.jarcompat.mods.core.MetaClient;
import xland.ioutils.jarcompat.mods.core.ModCompatException;
import xland.ioutils.jarcompat.mods.core.Resource;
import xland.ioutils.jarcompat.mods.env.BuiltEnvironment;
import xland.ioutils.jarcompat.mods.env.EnvironmentBuilder;
import xland.ioutils.jarcompat.mods.env.EnvironmentFilter;
import xland.ioutils.jarcompat.mods.report.ReportJson;

/**
 * ModCompat 主流程：解析参数 → 构建两侧环境 → 过滤同坐标资源 → 下载 JAR → 调用 JarCompat → 输出报告。
 *
 * <p>核心比较逻辑完全交给 {@code xland.ioutils:JarCompat:0.1.3} 的公共 API
 * （{@link JarCompat#request()} / {@link JarCompat#check(CheckRequest)}），本项目只负责
 * 环境元数据获取、资源列表构建与结果呈现。</p>
 */
public final class ModCompatApp {

    /** 工具名。 */
    public static final String TOOL_NAME = "ModCompat";

    /** 工具版本。 */
    public static final String TOOL_VERSION = "0.1.0";

    /**
     * 依赖的 JarCompat 版本，与 {@code build.gradle.kts} 中的坐标保持一致。
     *
     * <p>不用 {@link JarCompat#TOOL_VERSION}：它读取的是 JarCompat 包清单里的
     * {@code Implementation-Version}，而 shadow 打包后的 fat JAR 会丢掉这个属性，
     * 于是会退化成 {@code 0.1.0}，显示出来的版本就不对了。</p>
     */
    public static final String JAR_COMPAT_VERSION = "0.1.3";

    private final CliOptions options;
    private final Fetcher fetcher;
    private final PrintStream out;
    private final PrintStream err;

    public ModCompatApp(CliOptions options, Fetcher fetcher, PrintStream out, PrintStream err) {
        this.options = options;
        this.fetcher = fetcher;
        this.out = out;
        this.err = err;
    }

    /**
     * 进程入口：解析参数、按需创建 HTTP 下载器并执行比较。
     *
     * @return 进程退出码，见 {@link ExitCodes}
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        CliOptions options;
        try {
            options = CliParser.parse(args);
        } catch (UsageException e) {
            return usageError(e, err);
        }
        if (options.help()) {
            out.println(CliParser.usage());
            return ExitCodes.OK;
        }
        if (options.version()) {
            out.println(versionLine());
            return ExitCodes.OK;
        }
        try (HttpFetcher fetcher = new HttpFetcher()) {
            return new ModCompatApp(options, fetcher, out, err).execute();
        } catch (IOException e) {
            err.println("错误: 无法关闭下载器: " + e.getMessage());
            return ExitCodes.FAILURE;
        }
    }

    /**
     * 使用自定义 {@link Fetcher} 执行（便于测试，不触发真实网络访问）。
     */
    public static int runWith(String[] args, Fetcher fetcher, PrintStream out, PrintStream err) {
        CliOptions options;
        try {
            options = CliParser.parse(args);
        } catch (UsageException e) {
            return usageError(e, err);
        }
        if (options.help()) {
            out.println(CliParser.usage());
            return ExitCodes.OK;
        }
        if (options.version()) {
            out.println(versionLine());
            return ExitCodes.OK;
        }
        return new ModCompatApp(options, fetcher, out, err).execute();
    }

    /** 执行一次完整比较。 */
    public int execute() {
        Path program = options.program();
        if (!Files.isRegularFile(program)) {
            err.println("参数错误: mod JAR 不存在或不是普通文件: " + program);
            return ExitCodes.USAGE;
        }

        try {
            MetaClient client = new MetaClient(fetcher);
            EnvironmentBuilder builder = new EnvironmentBuilder(client);

            BuiltEnvironment environmentA = builder.build("A", options.mcVersionA(), options.fabric(),
                    options.neoForge(), options.fabricOverrideA(), options.neoForgeOverrideA());
            BuiltEnvironment environmentB = builder.build("B", options.mcVersionB(), options.fabric(),
                    options.neoForge(), options.fabricOverrideB(), options.neoForgeOverrideB());

            if (environmentA.versions().sameEnvironmentAs(environmentB.versions(), options.fabric(), options.neoForge())) {
                err.println("无意义的比较: 两侧环境完全相同（" + environmentA.versions()
                        .describe(options.fabric(), options.neoForge()) + "）。");
                err.println("请至少让 Minecraft 版本或某个已启用 loader 的版本不同（未启用的 loader 不参与判断）。");
                return ExitCodes.USAGE;
            }

            // 同坐标过滤：以对方“未过滤前”的完整坐标集合为基准，保证结果对称
            EnvironmentFilter.Result libA = EnvironmentFilter.filter(environmentA.resources(),
                    EnvironmentFilter.coordsOf(environmentB.resources()));
            EnvironmentFilter.Result libB = EnvironmentFilter.filter(environmentB.resources(),
                    EnvironmentFilter.coordsOf(environmentA.resources()));

            if (options.format() == ReportFormat.JSON) {
                printEnvironmentHeader(err, environmentA, environmentB, libA, libB);
            } else {
                printEnvironmentHeader(out, environmentA, environmentB, libA, libB);
            }

            if (options.dryRun()) {
                if (options.format() == ReportFormat.JSON) {
                    out.println(renderDryRunJson(environmentA, environmentB, libA, libB));
                } else {
                    printResourceList("lib-a", libA.resources());
                    printResourceList("lib-b", libB.resources());
                }
                return ExitCodes.OK;
            }

            if (libA.resources().isEmpty() || libB.resources().isEmpty()) {
                err.println("无意义的比较: 同坐标过滤后 " + (libA.resources().isEmpty() ? "lib-a" : "lib-b")
                        + " 为空，两侧环境没有可比的上游差异。");
                return ExitCodes.USAGE;
            }

            JarCache cache = new JarCache(options.cacheDir(), fetcher,
                    message -> err.println("  " + message));
            List<Path> libAPaths = localize(cache, "A", libA.resources());
            List<Path> libBPaths = localize(cache, "B", libB.resources());
            err.println("资源就绪: 新下载 " + cache.downloadedCount() + " 个，复用缓存 "
                    + cache.reusedCount() + " 个（缓存目录 " + cache.root() + "）");

            CheckReport report = check(program, libAPaths, libBPaths);
            emit(report, environmentA, environmentB, libA, libB);
            if (report.errorCount() > 0 && options.failOnError()) {
                return ExitCodes.INCOMPATIBLE;
            }
            return ExitCodes.OK;
        } catch (ModCompatException e) {
            err.println("错误: " + e.getMessage());
            return ExitCodes.FAILURE;
        } catch (JarCompatException e) {
            err.println("JarCompat 分析失败: " + e.getMessage());
            return ExitCodes.FAILURE;
        } catch (IllegalArgumentException e) {
            err.println("参数错误: " + e.getMessage());
            return ExitCodes.USAGE;
        } catch (RuntimeException e) {
            err.println("内部错误: " + e);
            return ExitCodes.FAILURE;
        }
    }

    /** 调用 JarCompat 公共 API 完成比较。 */
    private CheckReport check(Path program, List<Path> libA, List<Path> libB) {
        CheckRequest.Builder request = JarCompat.request()
                .program(program)
                .libA(libA.toArray(Path[]::new))
                .libB(libB.toArray(Path[]::new))
                .reachability(options.reachability())
                .entryMethod(options.entryMethod());
        if (options.entryClass() != null) {
            request.entry(options.entryClass());
        }
        if (options.reachability() == ReachabilityScope.ENTRY && options.entryClass() == null) {
            err.println("提示: --reachability entry 会尝试使用 mod JAR 的 Main-Class；mod 通常没有 Main-Class，"
                    + "此时所有引用只会给出 WARN。需要确定性结论请用默认的 --reachability all。");
        }
        return JarCompat.check(request.build());
    }

    /** 下载（或复用缓存）一侧的全部资源，保持 classpath 顺序。 */
    private List<Path> localize(JarCache cache, String side, List<Resource> resources) {
        List<Path> paths = new ArrayList<>(resources.size());
        for (Resource resource : resources) {
            try {
                paths.add(cache.fetch(resource));
            } catch (IOException e) {
                throw new ModCompatException("环境 " + side + " 的资源 " + resource.coords()
                        + " 下载失败: " + e.getMessage(), e);
            }
        }
        return paths;
    }

    private void printEnvironmentHeader(PrintStream target, BuiltEnvironment a, BuiltEnvironment b,
                                        EnvironmentFilter.Result libA, EnvironmentFilter.Result libB) {
        target.println(TOOL_NAME + " " + TOOL_VERSION + " — Minecraft mod 双环境兼容性比较（JarCompat "
                + JAR_COMPAT_VERSION + "）");
        target.println("mod JAR   : " + options.program().toAbsolutePath());
        target.println("环境 A    : " + a.versions().describe(options.fabric(), options.neoForge()));
        target.println("环境 B    : " + b.versions().describe(options.fabric(), options.neoForge()));
        target.println("缓存目录  : " + options.cacheDir().toAbsolutePath());
        target.println("lib-a     : " + describe(libA));
        target.println("lib-b     : " + describe(libB));
        target.println("可达性    : " + (options.reachability() == ReachabilityScope.ALL
                ? "all（全量：program 的每个类/字段/方法都视为入口）"
                : "entry（从入口方法做调用图分析）"));
        target.println();
    }

    private static String describe(EnvironmentFilter.Result result) {
        return result.rawCount() + " 个上游资源 -> 保留 " + result.resources().size()
                + " 个（过滤掉另一侧同坐标 " + result.removedSharedWithOtherSide()
                + " 个，本侧重复坐标 " + result.removedDuplicates() + " 个）";
    }

    private void printResourceList(String label, List<Resource> resources) {
        out.println("== " + label + "（" + resources.size() + " 个）==");
        for (Resource resource : resources) {
            out.println("  - " + resource.coords() + "  <- " + resource.url());
        }
        out.println();
    }

    /** {@code --dry-run --format json}：只输出两侧环境与资源列表，不含报告。 */
    private String renderDryRunJson(BuiltEnvironment a, BuiltEnvironment b,
                                    EnvironmentFilter.Result libA, EnvironmentFilter.Result libB) {
        JsonObject root = new JsonObject();
        root.put("tool", TOOL_NAME);
        root.put("toolVersion", TOOL_VERSION);
        root.put("program", options.program().toAbsolutePath().toString());
        root.put("dryRun", true);
        root.put("environmentA", environmentJson(a, libA));
        root.put("environmentB", environmentJson(b, libB));
        return JsonWriter.indent("  ").string().value(root).done();
    }

    private void emit(CheckReport report, BuiltEnvironment a, BuiltEnvironment b,
                      EnvironmentFilter.Result libA, EnvironmentFilter.Result libB) {
        String rendered;
        if (options.format() == ReportFormat.JSON) {
            rendered = renderJson(report, a, b, libA, libB);
        } else {
            rendered = report.toText();
        }

        if (options.format() == ReportFormat.JSON) {
            out.println(rendered);
            err.println(verdictLine(report));
        } else {
            out.print(rendered);
            out.println();
            out.println(verdictLine(report));
            if (report.errorCount() > 0 && !options.failOnError()) {
                out.println("提示: 加 --fail-on-error 可在存在确定不兼容项时返回退出码 2。");
            }
        }
        out.flush();

        if (options.output() != null) {
            Path target = options.output().toAbsolutePath();
            try {
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(target, rendered, StandardCharsets.UTF_8);
                err.println("报告已写入 " + target);
            } catch (IOException e) {
                throw new ModCompatException("无法写入报告 " + target + ": " + e.getMessage(), e);
            }
        }
    }

    /**
     * 组合输出：环境信息 + JarCompat 报告（置于 {@code report} 字段）。
     *
     * <p>报告 JSON 由 {@link ReportJson} 依据 JarCompat 的公共报告 API 序列化：
     * 0.1.3 自带的 {@code CheckReport.toJson()}（即 {@code JarCompat.render(report, JSON)}）
     * 因为 nanojson {@code JsonStringWriter} 未覆写 {@code toString()} 而返回对象字符串，不是合法 JSON。</p>
     */
    private String renderJson(CheckReport report, BuiltEnvironment a, BuiltEnvironment b,
                              EnvironmentFilter.Result libA, EnvironmentFilter.Result libB) {
        JsonObject root = new JsonObject();
        root.put("tool", TOOL_NAME);
        root.put("toolVersion", TOOL_VERSION);
        root.put("jarCompatVersion", JAR_COMPAT_VERSION);
        root.put("program", options.program().toAbsolutePath().toString());
        root.put("cacheDir", options.cacheDir().toAbsolutePath().toString());
        root.put("reachability", options.reachability().name());
        root.put("environmentA", environmentJson(a, libA));
        root.put("environmentB", environmentJson(b, libB));
        root.put("report", ReportJson.toJsonObject(report));
        return JsonWriter.indent("  ").string().value(root).done();
    }

    private JsonObject environmentJson(BuiltEnvironment environment, EnvironmentFilter.Result result) {
        JsonObject json = new JsonObject();
        json.put("minecraft", environment.versions().minecraftVersion());
        json.put("fabricLoader", environment.versions().fabricLoaderVersion());
        json.put("neoForge", environment.versions().neoForgeVersion());
        json.put("resourceCount", result.resources().size());
        json.put("removedSharedWithOtherSide", result.removedSharedWithOtherSide());
        json.put("removedDuplicates", result.removedDuplicates());
        json.put("coords", result.resources().stream().map(Resource::coords).toList());
        return json;
    }

    private static String verdictLine(CheckReport report) {
        String verdict = switch (report.verdict()) {
            case COMPATIBLE -> "兼容";
            case INCOMPATIBLE -> "不兼容";
            case UNKNOWN -> "无法判定";
        };
        return "兼容性结论: " + verdict + "（确定不兼容 " + report.errorCount()
                + " 项，潜在不兼容 " + report.warningCount()
                + " 项，未发现问题的引用 " + report.compatibleReferenceCount()
                + " 项，外部引用 " + report.externalReferenceCount()
                + " 项，耗时 " + report.durationMillis() + " ms）";
    }

    private static int usageError(UsageException e, PrintStream err) {
        err.println("参数错误: " + e.getMessage());
        err.println();
        err.println(CliParser.usage());
        return ExitCodes.USAGE;
    }

    private static String versionLine() {
        return TOOL_NAME + " " + TOOL_VERSION + " (JarCompat " + JAR_COMPAT_VERSION + ", "
                + "Java " + Runtime.version().feature() + ")";
    }
}
